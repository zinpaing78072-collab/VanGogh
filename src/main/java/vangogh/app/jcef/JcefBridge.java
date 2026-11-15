package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;
import vangogh.error.FetchError;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Option;
import io.vavr.control.Try;
import io.vavr.control.Validation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Bridges the frontend's {@code window.krema} API to Van Gogh's controllers.
 *
 * <ul>
 *   <li><b>JS→Java:</b> {@code window.krema.invoke("requestMovies", {...})}
 *       travels through CEF's {@code cefQuery} into a {@link CefMessageRouter}
 *       handler, which calls {@link MovieController#requestMovies} /
 *       {@link GameController#requestGames} / {@link GameController#pollGamePosters}.
 *   <li><b>Java→JS:</b> poster events flow through {@link JcefEventEmitter}
 *       via {@code executeJavaScript} → {@code window.__krema_emit}.
 * </ul>
 *
 * <p>Request validation accumulates instead of short-circuiting: the command
 * name, {@code kind}, and {@code query} are independent extractions combined
 * with {@code Validation.combine(...).ap(...)}, so a request missing several
 * fields reports ALL of them in one {@code 400} reply.
 * Controller calls themselves stay sequential (the spine short-circuits).
 *
 * <p>The existing controllers are shell-agnostic: they receive a
 * {@link JcefEventEmitter} (a Krema {@link EventEmitter} subclass) via
 * {@code setEventEmitter(...)} and their {@code @KremaCommand} methods are
 * called directly here (no Krema annotation processing needed).
 */
public class JcefBridge {

    private static final Gson GSON = new Gson();

    private final MovieController movieController;
    private final GameController gameController;
    private JcefEventEmitter emitter;
    private CefMessageRouter router;
    /**
     * Queries are answered asynchronously: controller calls (network) must not
     * run on the thread that dispatches CEF queries — that thread pumps the
     * macOS window's event loop, and a slow/hung HTTP call would freeze the
     * whole window (red button included). Single-threaded so queries resolve
     * in order.
     */
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vangogh-query-worker");
        t.setDaemon(true);
        return t;
    });

    public JcefBridge(MovieController movieController, GameController gameController) {
        this.movieController = movieController;
        this.gameController = gameController;
    }

    /**
     * Registers the JS→Java message router on the client. Must be called
     * BEFORE the browser is created — CEF injects {@code window.cefQuery}
     * into a page only when the router already exists on the client (the
     * upstream jcef test apps use the same order).
     *
     * @param client the {@link CefClient} (from {@code CefApp#createClient()})
     */
    public void installRouter(CefClient client) {
        this.router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig());
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                                   String request, boolean persistent, CefQueryCallback callback) {
                handleQuery(request, callback);
                return true;
            }
        }, false);

        // CefClient.addMessageRouter (public) delegates to
        // CefClientHandler.addMessageRouter: registers the router for
        // renderer-side cefQuery injection and browser-side dispatch.
        client.addMessageRouter(router);
    }

    /**
     * Attaches the browser after it is created: creates the
     * {@link JcefEventEmitter} and wires it into both controllers, so their
     * poster events flow into the page via {@code window.__krema_emit}.
     *
     * @param browser the {@link CefBrowser} (from {@code client.createBrowser()})
     */
    public void attachBrowser(CefBrowser browser) {
        this.emitter = new JcefEventEmitter(browser);
        movieController.setEventEmitter(emitter);
        gameController.setEventEmitter(emitter);
    }

    public void dispose() {
        if (router != null) {
            router.dispose();
        }
        queryExecutor.shutdownNow();
    }

    /** A command's work, prepared on the dispatch thread, run on the worker. */
    private record Command(Callable<Object> work) {
        Object run() throws Exception {
            return work.call();
        }
    }

    private void handleQuery(String request, CefQueryCallback callback) {
        // Parse + validate (cheap, accumulating), offload the actual work.
        Validation<Seq<FetchError>, Command> prepared = prepare(request);
        prepared.toEither().fold(
                errors -> {
                    callback.failure(400,
                            errors.map(FetchError::uiMessage).mkString("; "));
                    return null;
                },
                command -> dispatch(command, callback));
    }

    private Void dispatch(Command command, CefQueryCallback callback) {
        // The message-router callback may be completed from any thread.
        queryExecutor.submit(() -> {
            try {
                callback.success(GSON.toJson(command.run()));
            } catch (Exception e) {
                callback.failure(500, e.getMessage());
            }
        });
        return null;
    }

    private Validation<Seq<FetchError>, Command> prepare(String request) {
        Try<JsonObject> parsed = Try.of(() -> JsonParser.parseString(request).getAsJsonObject());
        if (parsed.isFailure()) {
            return Validation.invalid(List.<FetchError>of(
                    new FetchError.Payload("bridge", "request body", parsed.getCause())));
        }
        return validCommand(parsed.get());
    }

    private Validation<Seq<FetchError>, Command> validCommand(JsonObject req) {
        String name = Option.of(req.get("command"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsString())
                .getOrNull();
        if (name == null) {
            return Validation.invalid(List.of(new FetchError.BadInput("command", "missing")));
        }
        JsonObject args = req.has("args") && req.get("args").isJsonObject()
                ? req.getAsJsonObject("args")
                : null;
        return switch (name) {
            case "requestMovies" -> argsCommand(args, this::movieCommand);
            case "requestGames" -> argsCommand(args, this::gameCommand);
            case "pollGamePosters" -> Validation.valid(new Command(gameController::pollGamePosters));
            default -> Validation.invalid(List.of(
                    new FetchError.BadInput("command", "unknown command: " + name)));
        };
    }

    /**
     * The accumulating arg node: {@code kind} and {@code query} are independent
     * extractions combined with {@code Validation.combine(...).ap(...)} — a
     * request missing both reports both, not just the first.
     */
    private Validation<Seq<FetchError>, Command> argsCommand(JsonObject args,
                                                             BiFunction<String, String, Command> build) {
        if (args == null) {
            return Validation.invalid(List.of(new FetchError.BadInput("args", "missing")));
        }
        return Validation.combine(validKind(args), validQuery(args))
                .ap(build::apply);
    }

    private Validation<FetchError, String> validKind(JsonObject args) {
        return Try.of(() -> args.get("kind").getAsString())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.BadInput("kind", "missing or not a string"))
                .toValidation();
    }

    /** {@code query} is optional; but when present it must be a string. */
    private Validation<FetchError, String> validQuery(JsonObject args) {
        if (!args.has("query") || args.get("query").isJsonNull()) {
            return Validation.valid(null);
        }
        return Try.of(() -> args.get("query").getAsString())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.BadInput("query", "not a string"))
                .toValidation();
    }

    private Command movieCommand(String kind, String query) {
        return new Command(() -> movieController.requestMovies(kind, query));
    }

    private Command gameCommand(String kind, String query) {
        return new Command(() -> gameController.requestGames(kind, query));
    }
}
