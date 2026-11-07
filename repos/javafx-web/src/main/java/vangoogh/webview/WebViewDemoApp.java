package vangoogh.webview;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.net.URL;

/**
 * Minimal, runnable reference for JavaFX WebView / WebEngine usage.
 *
 * Demonstrates:
 *  - Loading a local HTML resource
 *  - Observing load state via Worker
 *  - Exposing a Java object to JavaScript (JSObject bridge)
 *  - Calling JavaScript from Java
 *  - Receiving console messages from the page
 *
 * This file is reference material only. Do not import it into application code.
 */
public class WebViewDemoApp extends Application {

    /** Object exposed to JavaScript as `window.javaApp`. */
    public static class JavaBridge {
        public String greet(String name) {
            return "Hello from Java, " + name + "!";
        }

        public void log(String message) {
            System.out.println("[JS] " + message);
        }
    }

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        // Surface page console messages in the Java console.
        engine.setOnAlert(event -> System.out.println("[alert] " + event.getData()));

        // Track load state.
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            System.out.println("Load state: " + newState);
            if (newState == Worker.State.SUCCEEDED) {
                // Expose the Java bridge to JavaScript once the page is ready.
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaApp", new JavaBridge());

                // Call into JavaScript from Java.
                engine.executeScript("onJavaReady()");
            }
        });

        URL page = getClass().getResource("/web/index.html");
        if (page == null) {
            throw new IllegalStateException("Missing /web/index.html resource");
        }
        engine.load(page.toExternalForm());

        stage.setTitle("JavaFX WebView Reference");
        stage.setScene(new Scene(webView, 900, 600));
        stage.show();
    }

    public static void main(String[] args) {
        // Launch on the JavaFX Application Thread.
        launch(args);
    }
}
