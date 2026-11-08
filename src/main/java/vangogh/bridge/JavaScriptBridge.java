package vangogh.bridge;

import vangogh.controller.MovieController;
import netscape.javascript.JSObject;
import javafx.scene.web.WebEngine;

/**
 * Wires Java into the page's JavaScript global scope. Exposes a
 * {@code javaBridge} object on {@code window} so the page can ask Java
 * for movies without knowing how TMDB, threading, or the generation
 * counter works.
 *
 * This class knows nothing about the TMDB API or the page's render logic —
 * it only translates JavaScript calls into controller method calls.
 */
public class JavaScriptBridge {

    private final WebEngine webEngine;
    private final MovieController controller;

    public JavaScriptBridge(WebEngine webEngine, MovieController controller) {
        this.webEngine = webEngine;
        this.controller = controller;
    }

    /** Installs {@code window.javaBridge} on the loaded page. */
    public void install() {
        JSObject window = (JSObject) webEngine.executeScript("window");
        window.setMember("javaBridge", new Object() {
            /**
             * Called from JS (search box, content filter). Returns immediately;
             * the movies are pushed back into the page via renderMoviesFromJava
             * once the API call finishes, so the UI thread never blocks.
             */
            public void requestMovies(String kind, String query) {
                controller.requestMovies(kind, query);
            }
        });
    }
}