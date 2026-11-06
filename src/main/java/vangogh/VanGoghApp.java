package vangogh;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.File;

/**
 * Van Gogh — Entertainment Content Recommendation Desktop App
 * Movie Page Prototype (Dark Mode)
 */
public class VanGoghApp extends Application {

    @Override
    public void start(final Stage primaryStage) {
        primaryStage.setTitle("Van Gogh");

        final WebView webView = new WebView();
        final WebEngine engine = webView.getEngine();

        // Load the local movie page HTML
        final File htmlFile = new File("src/main/resources/movie-page/index.html");
        if (htmlFile.exists()) {
            engine.load(htmlFile.toURI().toString());
        } else {
            System.err.println("HTML file not found: " + htmlFile.getAbsolutePath());
        }

        final Scene scene = new Scene(webView, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(final String[] args) {
        launch(args);
    }
}
