package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Form;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import vangogh.recommend.PickResolver;
import vangogh.recommend.RecommendationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vavr-based rewrite: the movie fetch returns an {@link Either} value and runs
 * on the IO pool. The {@code @KremaCommand} handler only <em>kicks off</em> the
 * fetch and returns at once — it runs on the Krema UI thread, and blocking it
 * on network I/O freezes the whole window for the round-trip (no repaints, no
 * loading state). The resolved list (or error) lands in a one-slot holder that
 * the {@code pollMovieData} drain — itself a synchronous UI-thread command,
 * per the Windows Krema engine.eval constraint — hands to the page, exactly
 * like the poster drain below. Posters prefetch on the IO pool and queue for
 * the {@code pollMoviePosters} drain, which emits {@code "poster"} events on
 * the UI thread.
 */
public class MovieController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final TmdbMovieService service;
    private final ExecutorService executor;
    private final PickResolver<Movie> resolver;
    private final RecommendationService recommendations;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile Future<?> currentFetch;

    private record PendingPoster(int id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    /** One-slot holder for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private record PendingData(int generation, Either<FetchError, List<Movie>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record MovieDataReply(int generation, String status, String error, List<Movie> items) {}

    public MovieController() {
        this(new TmdbMovieService(resolveApiKey()));
    }

    public MovieController(TmdbMovieService service) {
        this(service, Executors.newFixedThreadPool(IO_THREADS), null);
    }

    /** Package-private constructor used by tests. */
    MovieController(TmdbMovieService service, ExecutorService executor) {
        this(service, executor, null);
    }

    /** Package-private seam: tests inject the engine and its stubbed model call. */
    MovieController(TmdbMovieService service, ExecutorService executor,
                    RecommendationService recommendations) {
        this.service = service;
        this.executor = executor;
        this.resolver = service.resolver();
        this.recommendations = recommendations != null
                ? recommendations
                : new RecommendationService(executor);
    }

    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    /**
     * Kicks off the fetch on the IO pool and returns the request's generation
     * immediately — never blocks on network I/O.
     *
     * <p>{@code kind="recommend"} runs the LLM recommendation pipeline (one
     * model request, then parallel TMDB resolution); {@code "search"} is the
     * direct catalog lookup; anything else loads trending. AI mode is opt-in
     * from the page's toggle, so the direct paths stay byte-for-byte as before.
     */
    @KremaCommand
    public int requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();
        pendingData.set(null);

        Future.of(executor, () -> fetchMovies(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Movie>> fetchMovies(String kind, String query) {
        if ("recommend".equalsIgnoreCase(kind)) {
            return recommendations.recommend(Form.MOVIE, query, resolver);
        }
        boolean search = "search".equalsIgnoreCase(kind)
                && query != null && !query.trim().isEmpty();
        String term = query == null ? "" : query.trim();
        return search ? service.searchMovies(term) : service.fetchTrending();
    }

    /**
     * Runs on the IO pool: joins the fetch result, fans out poster prefetch,
     * and parks the outcome for the {@code pollMovieData} drain. Stale
     * completions (a newer request superseded this one) are dropped.
     */
    private void deliverData(int gen, Try<Either<FetchError, List<Movie>>> result) {
        if (generation.get() != gen) {
            return;
        }
        Either<FetchError, List<Movie>> either = result.isFailure()
                ? Either.left(new FetchError.Transport("TMDB request", result.getCause()))
                : result.get();

        // Cancel any in-flight prefetch from a previous request.
        if (currentFetch != null) {
            currentFetch.cancel();
        }

        // Fan-out composition: start all poster fetches in parallel via
        // Future.traverse; each queues its poster for the UI-thread drain.
        java.util.List<Movie> movies = either.isRight() ? either.get() : java.util.List.of();
        io.vavr.collection.List<Movie> posterable = io.vavr.collection.List.ofAll(movies)
                .filter(m -> m.posterUrl() != null && !m.posterUrl().isEmpty());

        // Total is set BEFORE the data drain can hand the page this request's
        // items, so a pollMoviePosters issued after render never observes a
        // stale count.
        posterTotal.set(posterable.size());
        currentFetch = Future.traverse(executor, posterable, movie ->
                Future.run(executor, () -> fetchAndOffer(movie.id(), movie.posterUrl(), gen)));

        // Set last: the drain hands the page items only after the poster
        // bookkeeping above is in place.
        pendingData.set(new PendingData(gen, either));
    }

    /**
     * The data drain: returns the latest completed fetch for the page's own
     * generation, or a pending/superseded marker. Synchronous on the UI thread,
     * so the reply's engine.eval always fires (Windows Krema constraint).
     */
    @KremaCommand
    public MovieDataReply pollMovieData(int generation) {
        PendingData pending = pendingData.get();
        if (pending == null) {
            return new MovieDataReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            // A newer request owns the grid; this poller should stop quietly.
            return new MovieDataReply(pending.generation(), "superseded", null, null);
        }
        // Consume: the page renders what it drained; a later poll sees empty.
        // Safe without CAS — a new writer can only appear via requestMovies,
        // which runs on this same UI thread after this handler returns.
        pendingData.set(null);
        return pending.result().fold(
                error -> new MovieDataReply(pending.generation(), "error", FetchError.uiMessage(error), null),
                movies -> new MovieDataReply(pending.generation(), "done", null, movies));
    }

    @KremaCommand
    public int pollMoviePosters() {
        PendingPoster pending;
        while ((pending = posterQueue.poll()) != null) {
            if (events != null) {
                events.emit("poster", Map.of("id", pending.id(), "dataUri", pending.dataUri()));
            }
            posterDelivered.incrementAndGet();
        }
        int outstanding = posterTotal.get() - posterDelivered.get() - posterSkipped.get();
        if (posterQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    /** Clears the poster bookkeeping for a fresh render (UI-thread only). */
    private void resetPosterTracking() {
        posterQueue.clear();
        posterTotal.set(0);
        posterDelivered.set(0);
        posterSkipped.set(0);
    }

    private void fetchAndOffer(int id, String posterUrl, int gen) {
        Option<String> uri = service.posterDataUri(posterUrl)
                .filter(ignore -> generation.get() == gen);
        if (uri.isEmpty()) {
            if (generation.get() == gen) {
                posterSkipped.incrementAndGet();
            }
            return;
        }
        posterQueue.offer(new PendingPoster(id, uri.get()));
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
