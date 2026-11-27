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
import vangogh.movie.MovieDetail;
import vangogh.movie.TmdbMovieService;
import vangogh.recommend.PickResolver;
import vangogh.recommend.RecommendationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** One-slot handoff for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private final LatestResult<Either<FetchError, List<Movie>>> pendingData = new LatestResult<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record MovieDataReply(int generation, String status, String error, List<Movie> items) {}

    // ------------------------------------------------------------ movie detail

    private record ArtRef(String slot, String url) {}

    private record PendingArt(String slot, String dataUri) {}

    private final AtomicInteger detailGeneration = new AtomicInteger();
    private final LatestResult<Either<FetchError, MovieDetail>> pendingDetail = new LatestResult<>();

    private final ConcurrentLinkedQueue<PendingArt> detailArtQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger detailArtTotal = new AtomicInteger();
    private final AtomicInteger detailArtDelivered = new AtomicInteger();
    private final AtomicInteger detailArtSkipped = new AtomicInteger();

    /** The detail drain's reply; artwork arrives separately as movieDetailArt events. */
    public record MovieDetailReply(int generation, String status, String error, MovieDetail detail) {}

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
        pendingData.clear();

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
        // bookkeeping above is in place. park() drops the result if a newer
        // request took over while this one was computing.
        pendingData.park(gen, either);
    }

    /**
     * The data drain: returns the latest completed fetch for the page's own
     * generation, or a pending/superseded marker. Synchronous on the UI thread,
     * so the reply's engine.eval always fires (Windows Krema constraint).
     */
    @KremaCommand
    public MovieDataReply pollMovieData(int generation) {
        // A newer request owns the grid; this poller must stop quietly rather
        // than accept whatever happens to be parked.
        if (this.generation.get() > generation) {
            return new MovieDataReply(generation, "superseded", null, null);
        }
        return pendingData.take(generation).fold(
                () -> new MovieDataReply(generation, "pending", null, null),
                either -> either.fold(
                        error -> new MovieDataReply(
                                generation, "error", FetchError.uiMessage(error), null),
                        movies -> new MovieDataReply(generation, "done", null, movies)));
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

    // -------------------------------------------------------------- detail

    /**
     * Kick off the movie-detail fetch and return its generation at once (the UI
     * thread never blocks on network I/O). The page polls
     * {@link #pollMovieDetail(int)} until the detail or an error arrives, then
     * {@link #pollMovieDetailArt()} for the streamed artwork.
     *
     * <p>Uses its own generation counter, so opening a film cannot supersede a
     * grid request (or its poster stream) and vice versa.
     */
    @KremaCommand
    public int requestMovieDetail(int id) {
        int gen = detailGeneration.incrementAndGet();
        pendingDetail.clear();
        resetDetailArt();

        Future.of(executor, () -> service.movieDetail(id))
                .onComplete(result -> deliverDetail(gen, result));
        return gen;
    }

    /**
     * Fans out the artwork fetches. The total is set before the detail drain can
     * hand the page this request (the drain runs after this method returns), so
     * a poll issued right after render never sees a stale count.
     */
    private void queueDetailArt(MovieDetail detail, int gen) {
        java.util.List<ArtRef> refs = new java.util.ArrayList<>();
        addArtRef(refs, "poster", detail.posterUrl());
        java.util.List<MovieDetail.MovieCredit> cast = detail.cast();
        for (int i = 0; i < cast.size(); i++) {
            addArtRef(refs, "cast:" + i, cast.get(i).imageUrl());
        }
        java.util.List<MovieDetail.MovieStill> stills = detail.stills();
        for (int i = 0; i < stills.size(); i++) {
            addArtRef(refs, "still:" + i, stills.get(i).imageUrl());
        }
        for (MovieDetail.SimilarMovie similar : detail.similar()) {
            addArtRef(refs, "similar:" + similar.movie().id(), similar.posterUrl());
        }

        detailArtTotal.set(refs.size());
        Future.traverse(executor, io.vavr.collection.List.ofAll(refs),
                ref -> Future.run(executor, () -> fetchDetailArt(ref, gen)));
    }

    private static void addArtRef(java.util.List<ArtRef> refs, String slot, String url) {
        if (url != null && !url.isEmpty()) {
            refs.add(new ArtRef(slot, url));
        }
    }

    private void deliverDetail(int gen, Try<Either<FetchError, MovieDetail>> result) {
        if (detailGeneration.get() != gen) {
            return;
        }
        Either<FetchError, MovieDetail> either = result.isFailure()
                ? Either.left(new FetchError.Transport("TMDB movie", result.getCause()))
                : result.get();

        // Fan out artwork before the drain can hand the page this request: the
        // total is in place by the time the page's first art poll runs.
        either.peek(detail -> queueDetailArt(detail, gen));

        pendingDetail.park(gen, either);
    }

    /**
     * The detail drain: the latest completed fetch for the page's generation,
     * or a pending/superseded marker. Synchronous on the UI thread.
     */
    @KremaCommand
    public MovieDetailReply pollMovieDetail(int generation) {
        if (detailGeneration.get() > generation) {
            return new MovieDetailReply(generation, "superseded", null, null);
        }
        return pendingDetail.take(generation).fold(
                () -> new MovieDetailReply(generation, "pending", null, null),
                either -> either.fold(
                        error -> new MovieDetailReply(
                                generation, "error", FetchError.uiMessage(error), null),
                        detail -> new MovieDetailReply(generation, "done", null, detail)));
    }

    /**
     * The artwork drain: emits one {@code movieDetailArt} event per queued
     * image ({@code {slot, dataUri}}) and returns the outstanding count, or −1
     * when the stream for this detail is complete.
     */
    @KremaCommand
    public int pollMovieDetailArt() {
        PendingArt pending;
        while ((pending = detailArtQueue.poll()) != null) {
            if (events != null) {
                events.emit("movieDetailArt",
                        Map.of("slot", pending.slot(), "dataUri", pending.dataUri()));
            }
            detailArtDelivered.incrementAndGet();
        }
        int outstanding = detailArtTotal.get() - detailArtDelivered.get() - detailArtSkipped.get();
        if (detailArtQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    private void resetDetailArt() {
        detailArtQueue.clear();
        detailArtTotal.set(0);
        detailArtDelivered.set(0);
        detailArtSkipped.set(0);
    }

    private void fetchDetailArt(ArtRef ref, int gen) {
        Option<String> uri = service.detailImageDataUri(ref.url())
                .filter(ignore -> detailGeneration.get() == gen);
        if (uri.isEmpty()) {
            if (detailGeneration.get() == gen) {
                detailArtSkipped.incrementAndGet();
            }
            return;
        }
        detailArtQueue.offer(new PendingArt(ref.slot(), uri.get()));
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
