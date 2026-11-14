package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.concurrent.Future;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vavr-based rewrite (pretending the Krema Windows UI-thread constraint does
 * not exist): poster fetches run on an IO pool as Vavr {@link Future}s and emit
 * {@code "poster"} events directly on completion.
 */
public class MovieController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final TmdbMovieService service;
    private final ExecutorService executor;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile Future<?> currentFetch;

    public MovieController() {
        this(new TmdbMovieService(resolveApiKey()));
    }

    public MovieController(TmdbMovieService service) {
        this.service = service;
        this.executor = Executors.newFixedThreadPool(IO_THREADS);
    }

    /** Package-private constructor used by tests. */
    MovieController(TmdbMovieService service, ExecutorService executor) {
        this.service = service;
        this.executor = executor;
    }

    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    @KremaCommand
    public List<Movie> requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        List<Movie> movies;
        try {
            if ("search".equalsIgnoreCase(kind) && query != null && !query.trim().isEmpty()) {
                movies = service.searchMovies(query.trim());
            } else {
                movies = service.fetchTrending();
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        // Cancel any in-flight prefetch from a previous request.
        if (currentFetch != null) {
            currentFetch.cancel();
        }

        // Fan-out composition: start all poster fetches in parallel via
        // Future.traverse; each emits its "poster" event as it completes.
        io.vavr.collection.List<Movie> posterable = io.vavr.collection.List.ofAll(movies)
                .filter(m -> m.posterUrl() != null && !m.posterUrl().isEmpty());

        currentFetch = Future.traverse(executor, posterable, movie ->
                Future.run(executor, () -> {
                    if (generation.get() != gen) {
                        return;
                    }
                    String uri = service.posterDataUri(movie.posterUrl());
                    if (generation.get() != gen || uri == null) {
                        return;
                    }
                    if (events != null) {
                        events.emit("poster", Map.of("id", movie.id(), "dataUri", uri));
                    }
                })
        );
        return movies;
    }

    /** Resolve the API key the same way as before. */
    private static String resolveApiKey() {
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "d0269af3b7b5a40e157c5e8ae28ff0a3";
        }
        return apiKey;
    }
}
