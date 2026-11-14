package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import vangogh.error.FetchError;
import vangogh.game.Game;
import vangogh.game.TgdbGameService;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Vavr-based rewrite: the game fetch returns an {@link Either} value and is
 * unwrapped to a thrown error only at this Krema edge — that thrown error
 * becomes Krema's error reply to the page. The reply still joins the IO future
 * on the UI thread (Krema handlers must stay synchronous; see AGENTS.md
 * Windows UI-thread rule). Posters are prefetched on the IO pool via
 * {@link Future#traverse} and delivered through the {@code pollGamePosters}
 * drain command, which doubles as the completion signal (returns −1 when
 * nothing is pending).
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

    @KremaCommand
    public java.util.List<Game> requestGames(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();

        // The fetch returns an Either value and never throws, so the blocking
        // join below cannot fail — the error unwraps at the edge instead.
        Future<Either<FetchError, java.util.List<Game>>> future =
                Future.of(executor, () -> fetchGames(kind, query));

        // Block the UI thread until the IO pool resolves the game list, so the
        // reply to the page is synchronous (Krema handlers must stay synchronous).
        java.util.List<Game> games = future.get()
                .fold(
                        error -> {
                            throw new RuntimeException(FetchError.uiMessage(error), error.cause());
                        },
                        Function.identity());

        // Total is set BEFORE this command returns, so a pollGamePosters issued
        // for this request can never observe a stale count.
        List<Game> posterable = List.ofAll(games)
                .filter(g -> g.posterUrl() != null && !g.posterUrl().isEmpty());
        posterTotal.set(posterable.size());

        Future.traverse(executor, posterable, game ->
                Future.run(executor, () -> fetchAndOffer(game.id(), game.posterUrl(), gen)));
        return games;
    }

    private Either<FetchError, java.util.List<Game>> fetchGames(String kind, String query) {
        return "search".equals(kind)
                ? service.searchGames(query)
                : service.fetchTrending();
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
