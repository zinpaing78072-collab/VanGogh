package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import com.google.gson.Gson;
import vangogh.llm.LlmService;
import vangogh.llm.Recommendation;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates movie fetching and page updates under Krema. Holds the TMDB
 * service, the background thread pool, the {@link EventEmitter} for pushing
 * posters into the page, and the generation counter that prevents stale poster
 * results from overwriting newer renders.
 *
 * This class knows nothing about the Krema window or the HTML page — it only
 * exposes {@link #requestMovies} as an IPC command (returning a
 * {@link CompletableFuture} that Krema delivers back to JS) and emits
 * {@code "poster"} events that the page consumes. All network and threading
 * concerns live here, separated from UI lifecycle.
 */
public class MovieController {

    private static final int IO_THREADS = 6;

    // volatile: written once from the startup thread via {@link #setEventEmitter}
    // (runs inside Krema's run(), before any page can invoke commands), then
    // read from the IO pool threads that stream posters.
    private volatile EventEmitter events;
    private final TmdbMovieService service;
    private final LlmService llmService;
    private final ExecutorService io;
    private final Gson gson = new Gson();

    // Bumped on every render request; poster pushes only apply when still
    // current, so a slow fetch can never overwrite a newer render's posters.
    private final AtomicInteger generation = new AtomicInteger();

    public MovieController() {
        this(new TmdbMovieService(resolveApiKey()), new LlmService());
    }

    /** Package-private seam for tests: inject a stubbed {@link TmdbMovieService}. */
    MovieController(TmdbMovieService service) {
        this(service, new LlmService());
    }

    /** Package-private seam for tests: inject stubbed services. */
    MovieController(TmdbMovieService service, LlmService llmService) {
        this.service = service;
        this.llmService = llmService;
        this.io = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Krema calls the {@code .events(...)} consumer inside {@code run()} once the
     * {@link EventEmitter} exists — before the window navigates to the page, so
     * before any JS can invoke {@code requestMovies}. Wire the emitter there
     * instead of constructing this class inside the builder chain, where the
     * emitter cannot exist yet (capturing it eagerly yields a permanent {@code null}).
     */
    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    /**
     * Krema IPC command. Called from JS via
     * {@code await window.krema.invoke("requestMovies", { kind, query })}.
     * Resolves with the parsed movie list; Krema delivers the reply from the
     * IO thread that completes the future.
     *
     * <p>Posters are streamed into the page as {@code "poster"} events
     * (one per movie) from background IO threads — see
     * {@link #streamPosters(List, int)}.
     */
    @KremaCommand
    public CompletableFuture<List<Movie>> requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        CompletableFuture<List<Movie>> future = new CompletableFuture<>();
        io.submit(() -> {
            try {
                List<Movie> movies = "search".equals(kind)
                        ? service.searchMovies(query)
                        : service.fetchTrending();
                future.complete(movies);
                streamPosters(movies, gen);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Krema IPC command. Called from JS via
     * {@code await window.krema.invoke("recommend", { query })}.
     * Delegates to the {@link LlmService}, which asks the OpenAI-compatible LLM for
     * cross-media recommendations related to the query and parses them into
     * {@link Recommendation} records. Degrades gracefully to an empty list when the
     * LLM is unconfigured or unavailable, so the UI never breaks because of AI.
     *
     * <p>Runs on the IO pool so the blocking LLM HTTP call never blocks the webview
     * event loop.
     */
    @KremaCommand
    public CompletableFuture<List<Recommendation>> recommend(String query) {
        CompletableFuture<List<Recommendation>> future = new CompletableFuture<>();
        io.submit(() -> {
            try {
                List<Recommendation> recommendations = llmService.recommend(query);
                future.complete(recommendations);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Loads poster bytes off-thread and emits each finished poster into the
     * page as a {@code "poster"} event carrying {@code { id, dataUri }}. The
     * FIRST poster acts as the connectivity probe: after its host decision no
     * other fetch repeats a dead 5s attempt (see
     * vangogh.net.TmdbClient#loadPosterDataUri), so the rest go out over the
     * live host.
     *
     * <p>Krema 0.3.2 has no internal dispatch thread, so {@code events.emit}
     * runs on the caller's (IO pool) thread. The page-side {@code setPoster}
     * looks up the card by data-movie-id, so posters from a superseded search
     * simply never find a matching card and are silently ignored.
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
                if (uri != null && events != null) {
                    events.emit("poster", Map.of("id", movie.id(), "dataUri", uri));
                }
                continue;
            }
            io.submit(() -> {
                String uri = service.posterDataUri(posterUrl);
                if (uri != null && events != null && generation.get() == gen) {
                    events.emit("poster", Map.of("id", movie.id(), "dataUri", uri));
                }
            });
        }
    }

    /** Same key resolution as the old {@code VanGoghApp#resolveApiKey}. */
    private static String resolveApiKey() {
        // ⚠️  Hard-coding an API key in source is a security risk — for production,
        //     load it from an env var or config file and never commit the real key.
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "d0269af3b7b5a40e157c5e8ae28ff0a3";
        }
        return apiKey;
    }
}
