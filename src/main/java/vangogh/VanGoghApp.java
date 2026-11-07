package vangogh;

import com.google.gson.Gson;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;

import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Van Gogh — Entertainment Content Recommendation Desktop App
 * Movie Page Prototype (Dark Mode) backed by the TMDB API.
 *
 * Poster images are fetched by Java (direct image host with a proxy fallback,
 * see TmdbClient) and pushed into the page as data: URIs. The page's own
 * <img src="...tmdb.org..."> loading is never used: on networks that reset the
 * TMDB image host, WebKit stalls for minutes before firing onerror, which made
 * every poster show alt-text "loading" forever.
 */
public class VanGoghApp extends Application {

    private static final String HTML_RESOURCE = "movie-page/index.html";

    // Built-in fallback key so the app works out of the box.
    // Prefer the TMDB_API_KEY environment variable; it takes precedence over this value.
    // ⚠️  Hard-coding an API key in source is a security risk — for production, load it from
    //     an env var or config file and never commit the real key to version control.
    private static final String DEFAULT_TMDB_API_KEY = "d0269af3b7b5a40e157c5e8ae28ff0a3";

    private WebEngine engine;
    private TmdbMovieService service;
    private ExecutorService io;
    private final Gson gson = new Gson();

    // Bumped on every render request; results/pushes only apply when still current,
    // so a slow fetch (or stale poster load) can never overwrite a newer render.
    private final AtomicInteger generation = new AtomicInteger();

    @Override
    public void start(final Stage primaryStage) {
        primaryStage.setTitle("Van Gogh");

        final WebView webView = new WebView();
        engine = webView.getEngine();

        // Load the movie page HTML from the classpath so the working directory doesn't matter.
        final URL htmlUrl = getClass().getClassLoader().getResource(HTML_RESOURCE);
        if (htmlUrl == null) {
            System.err.println("HTML resource not found on classpath: " + HTML_RESOURCE);
        } else {
            engine.load(htmlUrl.toString());
        }

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

    private void installBridge(WebEngine webEngine) {
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = DEFAULT_TMDB_API_KEY;
        }

        service = new TmdbMovieService(apiKey);
        io = Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });

        JSObject window = (JSObject) webEngine.executeScript("window");
        window.setMember("javaBridge", new Object() {
            /**
             * Called from JS (search box, content filter). Returns immediately;
             * the movies are pushed back into the page via renderMoviesFromJava
             * once the API call finishes, so the UI thread never blocks.
             */
            public void requestMovies(String kind, String query) {
                int gen = generation.incrementAndGet();
                io.submit(() -> fetchAndRender(kind, query, gen));
            }
        });

        // Seed the page with trending movies right away, off the FX thread.
        int gen = generation.incrementAndGet();
        io.submit(() -> fetchAndRender("trending", "", gen));
    }

    /** Fetches the movie list off-thread, renders it, then streams poster data URIs in. */
    private void fetchAndRender(String kind, String query, int gen) {
        try {
            List<Movie> movies = "search".equals(kind)
                    ? service.searchMovies(query)
                    : service.fetchTrending();
            String json = gson.toJson(movies);
            pushToPage(gen, () -> engine.executeScript(
                    "window.renderMoviesFromJava(" + gson.toJson(json) + ");"));
            streamPosters(movies, gen);
        } catch (Exception e) {
            String json = "{\"error\":" + gson.toJson(String.valueOf(e.getMessage())) + "}";
            pushToPage(gen, () -> engine.executeScript(
                    "window.renderMoviesFromJava(" + gson.toJson(json) + ");"));
        }
    }

    /**
     * Loads poster bytes off-thread and pushes each finished poster into the
     * page as a data: URI. The FIRST poster acts as the connectivity probe:
     * after its host decision no other fetch repeats a dead 5s attempt (see
     * TmdbClient#loadPosterDataUri), so the rest go out over the live host.
     */
    private void streamPosters(List<Movie> movies, int gen) {
        boolean first = true;
        for (Movie movie : movies) {
            String posterUrl = movie.posterUrl();
            if (posterUrl == null || posterUrl.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                String uri = service.posterDataUri(posterUrl);
                if (uri != null) {
                    pushToPage(gen, () -> execSetPoster(movie, uri));
                }
                continue;
            }
            io.submit(() -> {
                String uri = service.posterDataUri(posterUrl);
                if (uri != null) {
                    pushToPage(gen, () -> execSetPoster(movie, uri));
                }
            });
        }
    }

    private void execSetPoster(Movie movie, String dataUri) {
        engine.executeScript("window.setPoster("
                + gson.toJson(movie.id()) + ", " + gson.toJson(dataUri) + ");");
    }

    /** Runs a page call on the FX thread, dropping it when a newer render has begun. */
    private void pushToPage(int gen, Runnable action) {
        try {
            Platform.runLater(() -> {
                if (generation.get() != gen) {
                    return; // stale: a newer render superseded this result
                }
                try {
                    action.run();
                } catch (JSException | IllegalStateException e) {
                    System.err.println("Push into page failed: " + e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            // Toolkit shutting down; nothing to update anyway.
        }
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void main(final String[] args) {
        launch(args);
    }
}
