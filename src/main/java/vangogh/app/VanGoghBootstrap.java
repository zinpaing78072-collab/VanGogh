package vangogh.app;

import vangogh.controller.MovieController;
import vangogh.bridge.JavaScriptBridge;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.net.URL;

/**
 * JavaFX Application entry point. Responsible only for the UI lifecycle:
 * creating the stage, loading the HTML page, and wiring the controller and
 * JavaScript bridge once the page has loaded.
 *
 * Networking, threading, and JS↔Java delegation live in {@link MovieController}
 * and {@link JavaScriptBridge}. This split keeps the main class free of
 * business logic so classpath launches bypass the JDK's JavaFX-components
 * check without dragging UI code into the networking layer.
 */
public class VanGoghBootstrap extends Application {

    private static final String HTML_RESOURCE = "movie-page/index.html";

    @Override
    public void start(final Stage primaryStage) {
        primaryStage.setTitle("Van Gogh");

        final WebView webView = new WebView();
        final WebEngine engine = webView.getEngine();

        // Load the movie page HTML from the classpath so the working directory doesn't matter.
        final URL htmlUrl = getClass().getClassLoader().getResource(HTML_RESOURCE);
        if (htmlUrl == null) {
            System.err.println("HTML resource not found on classpath: " + HTML_RESOURCE);
        } else {
            engine.load(htmlUrl.toString());
        }

        // Once the page is ready, wire up the controller and the JS bridge.
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                String apiKey = resolveApiKey();
                MovieController controller = new MovieController(engine, apiKey);
                JavaScriptBridge bridge = new JavaScriptBridge(engine, controller);
                bridge.install();
                controller.seedTrending();
            }
        });

        final Scene scene = new Scene(webView, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /** Resolves the TMDB API key from the environment, falling back to a built-in key. */
    private String resolveApiKey() {
        // ⚠️  Hard-coding an API key in source is a security risk — for production,
        //     load it from an env var or config file and never commit the real key.
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "d0269af3b7b5a40e157c5e8ae28ff0a3";
        }
        return apiKey;
    }

    /** Entry point used by {@link VanGoghApp} and by modular runs. */
    static void bootstrap(String[] args) {
        Application.launch(VanGoghBootstrap.class, args);
    }

    public static void main(String[] args) {
        bootstrap(args);
    }
}