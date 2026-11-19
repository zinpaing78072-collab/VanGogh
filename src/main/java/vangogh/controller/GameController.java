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
import vangogh.game.TgdbGameService;

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

    GameController(TgdbGameService service) {
        this.service = service;
        this.executor = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
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

        // The AI toggle ships before this form's engine slice. Say so instead
        // of quietly running a direct search behind an AI-looking toggle.
        if ("recommend".equalsIgnoreCase(kind)) {
            pendingData.set(new PendingData(gen, Either.left(new FetchError.BadInput(
                    "kind", "AI recommendations are not wired for this form yet"))));
            return gen;
        }

        Future.of(executor, () -> fetchGames(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Game>> fetchGames(String kind, String query) {
        return "search".equals(kind)
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
