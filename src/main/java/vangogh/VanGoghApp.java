package vangogh;

import com.google.gson.Gson;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.io.File;
import java.util.List;

/**
 * Van Gogh — Entertainment Content Recommendation Desktop App
 * Movie Page Prototype (Dark Mode) backed by the TMDB API.
 */
public class VanGoghApp extends Application {

    private static final String HTML_PATH = "src/main/resources/movie-page/index.html";

    @Override
    public void start(final Stage primaryStage) {
        primaryStage.setTitle("Van Gogh");

        final WebView webView = new WebView();
        final WebEngine engine = webView.getEngine();

        final File htmlFile = new File(HTML_PATH);
        if (!htmlFile.exists()) {
            System.err.println("HTML file not found: " + htmlFile.getAbsolutePath());
        }
        engine.load(htmlFile.toURI().toString());

        // Expose the TMDB-backed movie service to the page's JavaScript once loaded.
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                installBridge(engine);
            }
        });

        final Scene scene = new Scene(webView, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void installBridge(WebEngine engine) {
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("TMDB_API_KEY not set — movie data will not load.");
            injectError(engine, "API key not configured: set TMDB_API_KEY and restart the app.");
            return;
        }

        TmdbMovieService service = new TmdbMovieService(apiKey);
        Gson gson = new Gson();

        JSObject window = (JSObject) engine.executeScript("window");
        window.setMember("javaBridge", new Object() {
            public String fetchTrending() {
                try {
                    List<Movie> movies = service.fetchTrending();
                    return gson.toJson(movies);
                } catch (Exception e) {
                    return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                }
            }

            public String searchMovies(String query) {
                try {
                    List<Movie> movies = service.searchMovies(query);
                    return gson.toJson(movies);
                } catch (Exception e) {
                    return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                }
            }
        });

        // Seed the page with trending movies immediately.
        try {
            List<Movie> movies = service.fetchTrending();
            String json = gson.toJson(movies);
            engine.executeScript("window.__INITIAL_MOVIES__ = " + json + ";");
        } catch (Exception e) {
            System.err.println("Failed to seed initial movies: " + e.getMessage());
        }
    }

    private void injectError(WebEngine engine, String message) {
        Gson gson = new Gson();
        engine.executeScript("window.__API_ERROR__ = " + gson.toJson(message) + ";");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void main(final String[] args) {
        launch(args);
    }
}
