package vangogh.controller;

import com.google.gson.Gson;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSException;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates movie fetching and page updates. Holds the TMDB service,
 * the background thread pool, and the generation counter that prevents
 * stale results from overwriting newer renders.
 *
 * This class knows nothing about the JavaFX stage or scene — it only pushes
 * JavaScript calls into the page through a WebEngine. All network and
 * threading concerns live here, separated from UI lifecycle and JS interop.
 */
public class MovieController {

    private static final int IO_THREADS = 6;

    private final WebEngine engine;
    private final TmdbMovieService service;
    private final ExecutorService io;
    private final Gson gson = new Gson();

    // Bumped on every render request; results/pushes only apply when still current,
    // so a slow fetch (or stale poster load) can never overwrite a newer render.
    private final AtomicInteger generation = new AtomicInteger();

    public MovieController(WebEngine engine, String apiKey) {
        this.engine = engine;
        this.service = new TmdbMovieService(apiKey);
        this.io = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Called from JavaScript via {@code window.javaBridge.requestMovies}. */
    public void requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        io.submit(() -> fetchAndRender(kind, query, gen));
    }

    /** Seeds the page with trending movies right away, off the FX thread. */
    public void seedTrending() {
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
     * vangogh.net.TmdbClient#loadPosterDataUri), so the rest go out over the live host.
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
}