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
    private final ExecutorService io;

    private final AtomicInteger generation = new AtomicInteger();

    public MovieController() {
        this(new TmdbMovieService(resolveApiKey()));
    }

    public MovieController(TmdbMovieService service) {
        this.service = service;
        this.io = Executors.newFixedThreadPool(IO_THREADS);
    }

    /** Package-private constructor used by tests. */
    MovieController(TmdbMovieService service, ExecutorService io) {
        this.service = service;
        this.io = io;
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

        // Fan out poster prefetch as Vavr Futures; emit directly on completion.
        for (Movie movie : movies) {
            String posterUrl = movie.posterUrl();
            if (posterUrl == null || posterUrl.isEmpty()) {
                continue;
            }
            Future.run(io, () -> {
                if (generation.get() != gen) {
                    return;
                }
                String uri = service.posterDataUri(posterUrl);
                if (generation.get() != gen || uri == null) {
                    return;
                }
                if (events != null) {
                    events.emit("poster", Map.of("id", movie.id(), "dataUri", uri));
                }
            });
        }
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
