package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.music.Album;
import vangogh.music.MusicBrainzService;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Music-domain command handler, same contract as {@link GameController}:
 * {@code requestMusic} kicks off the IO-pool fetch and returns the request's
 * generation immediately (it runs on the Krema UI thread — blocking on network
 * I/O freezes the window). The resolved list lands in a one-slot holder that
 * the {@code pollMusicData} drain hands the page; covers prefetch on the IO
 * pool and deliver through the {@code pollMusicPosters} drain emitting
 * {@code "musicPoster"} events on the UI thread.
 */
public class MusicController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final MusicBrainzService service;
    private final ExecutorService executor;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingPoster(String id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    /** One-slot holder for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private record PendingData(int generation, Either<FetchError, java.util.List<Album>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record MusicDataReply(int generation, String status, String error, java.util.List<Album> items) {}

    public MusicController() {
        this(new MusicBrainzService());
    }

    /** Package-private constructor used by tests. */
    MusicController(MusicBrainzService service) {
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
     * Kicks off the MusicBrainz fetch on the IO pool and returns the request's
     * generation immediately — never blocks on network I/O.
     */
    @KremaCommand
    public int requestMusic(String kind, String query) {
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

        Future.of(executor, () -> fetchAlbums(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Album>> fetchAlbums(String kind, String query) {
        return "search".equals(kind)
                ? service.searchAlbums(query)
                : service.fetchTrending();
    }

    /**
     * Runs on the IO pool: joins the fetch result, fans out cover prefetch,
     * and parks the outcome for the {@code pollMusicData} drain. Stale
     * completions (a newer request superseded this one) are dropped.
     */
    private void deliverData(int gen, Try<Either<FetchError, java.util.List<Album>>> result) {
        if (generation.get() != gen) {
            return;
        }
        Either<FetchError, java.util.List<Album>> either = result.isFailure()
                ? Either.left(new FetchError.Transport("MusicBrainz request", result.getCause()))
                : result.get();

        java.util.List<Album> albums = either.isRight() ? either.get() : java.util.List.of();
        List<Album> posterable = List.ofAll(albums)
                .filter(a -> a.posterUrl() != null && !a.posterUrl().isEmpty());

        // Total is set BEFORE the data drain can hand the page this request's
        // items, so a pollMusicPosters issued after render never observes a
        // stale count.
        posterTotal.set(posterable.size());
        Future.traverse(executor, posterable, album ->
                Future.run(executor, () -> fetchAndOffer(album.id(), album.posterUrl(), gen)));

        // Set last: the drain hands the page items only after the cover
        // bookkeeping above is in place.
        pendingData.set(new PendingData(gen, either));
    }

    /**
     * The data drain: returns the latest completed fetch for the page's own
     * generation, or a pending/superseded marker. Synchronous on the UI thread,
     * so the reply's engine.eval always fires (Windows Krema constraint).
     */
    @KremaCommand
    public MusicDataReply pollMusicData(int generation) {
        PendingData pending = pendingData.get();
        if (pending == null) {
            return new MusicDataReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            // A newer request owns the grid; this poller should stop quietly.
            return new MusicDataReply(pending.generation(), "superseded", null, null);
        }
        // Consume: the page renders what it drained; a later poll sees empty.
        // Safe without CAS — a new writer can only appear via requestMusic,
        // which runs on this same UI thread after this handler returns.
        pendingData.set(null);
        return pending.result().fold(
                error -> new MusicDataReply(pending.generation(), "error", FetchError.uiMessage(error), null),
                albums -> new MusicDataReply(pending.generation(), "done", null, albums));
    }

    @KremaCommand
    public int pollMusicPosters() {
        PendingPoster pending;
        while ((pending = posterQueue.poll()) != null) {
            if (events != null) {
                events.emit("musicPoster", Map.of("id", pending.id(), "dataUri", pending.dataUri()));
            }
            posterDelivered.incrementAndGet();
        }
        int outstanding = posterTotal.get() - posterDelivered.get() - posterSkipped.get();
        if (posterQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    /** Clears the cover bookkeeping for a fresh render (UI-thread only). */
    private void resetPosterTracking() {
        posterQueue.clear();
        posterTotal.set(0);
        posterDelivered.set(0);
        posterSkipped.set(0);
    }

    private void fetchAndOffer(String id, String posterUrl, int gen) {
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
}
