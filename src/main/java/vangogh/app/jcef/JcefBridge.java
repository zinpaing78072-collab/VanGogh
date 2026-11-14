package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;

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
        // Parse and validate here (cheap), offload the actual work.
        final Command command;
        try {
            JsonObject req = JsonParser.parseString(request).getAsJsonObject();
            String name = req.get("command").getAsString();
            JsonObject args = req.getAsJsonObject("args");
            switch (name) {
                case "requestMovies" -> command = new Command(() -> movieController.requestMovies(
                        args.get("kind").getAsString(),
                        args.has("query") ? args.get("query").getAsString() : null));
                case "requestGames" -> command = new Command(() -> gameController.requestGames(
                        args.get("kind").getAsString(),
                        args.has("query") ? args.get("query").getAsString() : null));
                case "pollGamePosters" -> command = new Command(gameController::pollGamePosters);
                default -> {
                    callback.failure(400, "Unknown command: " + name);
                    return;
                }
            }
        } catch (Exception e) {
            callback.failure(400, "Bad request: " + e.getMessage());
            return;
        }

        // The message-router callback may be completed from any thread.
        queryExecutor.submit(() -> {
            try {
                callback.success(GSON.toJson(command.run()));
            } catch (Exception e) {
                callback.failure(500, e.getMessage());
            }
        });
    }
}
