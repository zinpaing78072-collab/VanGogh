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

import java.lang.reflect.Method;

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

    public JcefBridge(MovieController movieController, GameController gameController) {
        this.movieController = movieController;
        this.gameController = gameController;
    }

    /**
     * Wires the bridge into a browser: creates a {@link CefMessageRouter} for
     * JS→Java calls and attaches the event emitter to both controllers.
     *
     * @param client the {@link CefClient} (from {@code CefApp#createClient()})
     */
    public void install(CefBrowser browser, CefClient client) {
        this.emitter = new JcefEventEmitter(browser);
        movieController.setEventEmitter(emitter);
        gameController.setEventEmitter(emitter);

        this.router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig());
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                                   String request, boolean persistent, CefQueryCallback callback) {
                handleQuery(request, callback);
                return true;
            }
        }, false);

        // CefClientHandler.addMessageRouter is protected; use reflection to invoke it.
        addMessageRouter(client, router);
    }

    private static void addMessageRouter(CefClient client, CefMessageRouter router) {
        try {
            Method m = client.getClass().getSuperclass().getDeclaredMethod(
                    "addMessageRouter", CefMessageRouter.class);
            m.setAccessible(true);
            m.invoke(client, router);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register CefMessageRouter", e);
        }
    }

    public void dispose() {
        if (router != null) {
            router.dispose();
        }
    }

    private void handleQuery(String request, CefQueryCallback callback) {
        try {
            JsonObject req = JsonParser.parseString(request).getAsJsonObject();
            String command = req.get("command").getAsString();
            JsonObject args = req.getAsJsonObject("args");

            Object result;
            switch (command) {
                case "requestMovies":
                    result = movieController.requestMovies(
                            args.get("kind").getAsString(),
                            args.has("query") ? args.get("query").getAsString() : null);
                    break;
                case "requestGames":
                    result = gameController.requestGames(
                            args.get("kind").getAsString(),
                            args.has("query") ? args.get("query").getAsString() : null);
                    break;
                case "pollGamePosters":
                    result = gameController.pollGamePosters();
                    break;
                default:
                    callback.failure(400, "Unknown command: " + command);
                    return;
            }

            callback.success(GSON.toJson(result));
        } catch (Exception e) {
            callback.failure(500, e.getMessage());
        }
    }
}
