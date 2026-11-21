package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.game.Game;
import vangogh.game.GameDetail;
import vangogh.game.TgdbGameService;
import vangogh.llm.Form;
import vangogh.recommend.PickResolver;
import vangogh.recommend.RecommendationService;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vavr-based rewrite: the game fetch returns an {@link Either} value and runs
 * on the IO pool. The {@code @KremaCommand} handler only <em>kicks off</em>
 * the fetch and returns at once — it runs on the Krema UI thread, and blocking
 * it on network I/O (the old {@code future.get()}) froze the whole window for
 * the round-trip. The resolved list (or error) lands in a one-slot holder that
 * the {@code pollGameData} drain — itself a synchronous UI-thread command, per
 * the Windows Krema engine.eval constraint — hands to the page. Posters are
 * prefetched on the IO pool via {@link Future#traverse} and delivered through
 * the {@code pollGamePosters} drain, which doubles as the completion signal
 * (returns −1 when nothing is pending).
 */
public class GameController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final TgdbGameService service;
    private final ExecutorService executor;
    private final PickResolver<Game> resolver;
    private final RecommendationService recommendations;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingPoster(int id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    /** One-slot holder for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private record PendingData(int generation, Either<FetchError, java.util.List<Game>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record GameDataReply(int generation, String status, String error, java.util.List<Game> items) {}

    public GameController() {
        this(new TgdbGameService(resolveApiKey()));
    }

    /** Package-private constructor used by tests. */
    GameController(TgdbGameService service) {
        this(service, newIoPool());
    }

    /** Package-private seam: lets a test inject the pool it will shut down. */
    GameController(TgdbGameService service, ExecutorService executor) {
        this(service, executor, null);
    }

    /** Package-private seam: tests inject the engine and its stubbed model call. */
    GameController(TgdbGameService service, ExecutorService executor,
                   RecommendationService recommendations) {
        this.service = service;
        this.executor = executor;
        this.resolver = service.resolver();
        this.recommendations = recommendations != null
                ? recommendations
                : new RecommendationService(executor);
    }

    private static ExecutorService newIoPool() {
        return Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    /**
     * Kicks off the TheGamesDB fetch on the IO pool and returns the request's
     * generation immediately — never blocks on network I/O.
     */
    @KremaCommand
    public int requestGames(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();
        pendingData.set(null);

        Future.of(executor, () -> fetchGames(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Game>> fetchGames(String kind, String query) {
        if ("recommend".equalsIgnoreCase(kind)) {
            return recommendations.recommend(Form.GAME, query, resolver)
                    .map(games -> List.ofAll(games)
                            .distinctBy(Game::id)
                            .toJavaList());
        }
        return "search".equalsIgnoreCase(kind)
                ? service.searchGames(query)
                : service.fetchTrending();
    }

    /**
     * Runs on the IO pool: joins the fetch result, fans out poster prefetch,
     * and parks the outcome for the {@code pollGameData} drain. Stale
     * completions (a newer request superseded this one) are dropped.
     */
    private void deliverData(int gen, Try<Either<FetchError, java.util.List<Game>>> result) {
        if (generation.get() != gen) {
            return;
        }
        Either<FetchError, java.util.List<Game>> either = result.isFailure()
                ? Either.left(new FetchError.Transport("TheGamesDB request", result.getCause()))
                : result.get();

        java.util.List<Game> games = either.isRight() ? either.get() : java.util.List.of();
        List<Game> posterable = List.ofAll(games)
                .filter(g -> g.posterUrl() != null && !g.posterUrl().isEmpty());

        // Total is set BEFORE the data drain can hand the page this request's
        // items, so a pollGamePosters issued after render never observes a
        // stale count.
        posterTotal.set(posterable.size());
        Future.traverse(executor, posterable, game ->
                Future.run(executor, () -> fetchAndOffer(game.id(), game.posterUrl(), gen)));

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
    public GameDataReply pollGameData(int generation) {
        PendingData pending = pendingData.get();
        if (pending == null) {
            return new GameDataReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            // A newer request owns the grid; this poller should stop quietly.
            return new GameDataReply(pending.generation(), "superseded", null, null);
        }
        // Consume: the page renders what it drained; a later poll sees empty.
        // Safe without CAS — a new writer can only appear via requestGames,
        // which runs on this same UI thread after this handler returns.
        pendingData.set(null);
        return pending.result().fold(
                error -> new GameDataReply(pending.generation(), "error", FetchError.uiMessage(error), null),
                games -> new GameDataReply(pending.generation(), "done", null, games));
    }

    @KremaCommand
    public int pollGamePosters() {
        PendingPoster pending;
        while ((pending = posterQueue.poll()) != null) {
            if (events != null) {
                events.emit("gamePoster", Map.of("id", pending.id(), "dataUri", pending.dataUri()));
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

    // -------------------------------------------------------------- detail

    /** One-slot holder for the latest completed detail fetch. */
    private record PendingDetail(int generation, Either<FetchError, GameDetail> result) {}

    private record ArtRef(String slot, String url) {}

    private record PendingArt(String slot, String dataUri) {}

    private final AtomicInteger detailGeneration = new AtomicInteger();
    private final AtomicReference<PendingDetail> pendingDetail = new AtomicReference<>();

    private final ConcurrentLinkedQueue<PendingArt> detailArtQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger detailArtTotal = new AtomicInteger();
    private final AtomicInteger detailArtDelivered = new AtomicInteger();
    private final AtomicInteger detailArtSkipped = new AtomicInteger();

    /** The detail drain's reply; artwork arrives separately as gameDetailArt events. */
    public record GameDetailReply(int generation, String status, String error, GameDetail detail) {}

    /**
     * Kicks off the game-detail fetch and returns its generation at once (the
     * UI thread never blocks on network I/O). The page polls
     * {@link #pollGameDetail(int)} until the detail or an error arrives, then
     * {@link #pollGameDetailArt()} for the streamed artwork.
     *
     * <p>Its own generation counter means opening a game cannot supersede the
     * grid request (or its poster stream) and vice versa.
     */
    @KremaCommand
    public int requestGameDetail(int id) {
        int gen = detailGeneration.incrementAndGet();
        pendingDetail.set(null);
        resetDetailArt();

        Future.of(executor, () -> service.gameDetail(id))
                .onComplete(result -> deliverDetail(gen, result));
        return gen;
    }

    /**
     * Fans out the artwork fetches. The total is set before the detail drain
     * can hand the page this request, so a poll issued right after render
     * never sees a stale count.
     */
    private void queueDetailArt(GameDetail detail, int gen) {
        java.util.List<ArtRef> refs = new java.util.ArrayList<>();
        addArtRef(refs, "boxart", detail.boxartUrl());
        addArtRef(refs, "hero", detail.heroUrl());
        java.util.List<String> stills = detail.stillUrls();
        for (int i = 0; i < stills.size(); i++) {
            addArtRef(refs, "still:" + i, stills.get(i));
        }
        for (GameDetail.Related related : detail.related()) {
            addArtRef(refs, "related:" + related.id(), related.posterUrl());
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

    private void deliverDetail(int gen, Try<Either<FetchError, GameDetail>> result) {
        if (detailGeneration.get() != gen) {
            return;
        }
        Either<FetchError, GameDetail> either = result.isFailure()
                ? Either.left(new FetchError.Transport("TheGamesDB game", result.getCause()))
                : result.get();

        either.peek(detail -> queueDetailArt(detail, gen));
        pendingDetail.set(new PendingDetail(gen, either));
    }

    /**
     * The detail drain: the latest completed fetch for the page's generation,
     * or a pending/superseded marker. Synchronous on the UI thread.
     */
    @KremaCommand
    public GameDetailReply pollGameDetail(int generation) {
        PendingDetail pending = pendingDetail.get();
        if (pending == null) {
            return new GameDetailReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            return new GameDetailReply(pending.generation(), "superseded", null, null);
        }
        pendingDetail.set(null);
        return pending.result().fold(
                error -> new GameDetailReply(pending.generation(), "error",
                        FetchError.uiMessage(error), null),
                detail -> new GameDetailReply(pending.generation(), "done", null, detail));
    }

    /**
     * The artwork drain: emits one {@code gameDetailArt} event per queued
     * image ({@code {slot, dataUri}}) and returns the outstanding count, or −1
     * when the stream for this detail is complete.
     */
    @KremaCommand
    public int pollGameDetailArt() {
        PendingArt pending;
        while ((pending = detailArtQueue.poll()) != null) {
            if (events != null) {
                events.emit("gameDetailArt",
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

    private static String resolveApiKey() {
        // ⚠️  Hard-coding an API key in source is a security risk — for production,
        //     load it from an env var or config file and never commit the real key.
        String apiKey = System.getenv("TGDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "bfd1d68433f6086d6cf88eab01be2aa610b50fc932d8cd19fd4965e3e29d5af9";
        }
        return apiKey;
    }

}
