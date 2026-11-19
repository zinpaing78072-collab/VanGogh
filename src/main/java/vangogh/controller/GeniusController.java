package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.music.GeniusMusicService;
import vangogh.music.Song;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GeniusController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final GeniusMusicService service;
    private final ExecutorService executor;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingPoster(String id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    private record PendingData(int generation, Either<FetchError, java.util.List<Song>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    public record MusicDataReply(int generation, String status, String error, java.util.List<Song> items) {}
    public record GeniusMusicDataReply(int generation, String status, String error, java.util.List<Song> items) {}

    public GeniusController() {
        this(new GeniusMusicService());
    }

    GeniusController(GeniusMusicService service) {
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
    public int requestGeniusMusic(String kind, String query) {
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

        Future.of(executor, () -> fetchSongs(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Song>> fetchSongs(String kind, String query) {
        return "search".equals(kind)
                ? service.searchSongs(query)
                : service.fetchTrending();
    }

    private void deliverData(int gen, Try<Either<FetchError, java.util.List<Song>>> result) {
        if (generation.get() != gen) {
            return;
        }
        Either<FetchError, java.util.List<Song>> either = result.isFailure()
                ? Either.left(new FetchError.Transport("Genius request", result.getCause()))
                : result.get();

        java.util.List<Song> songs = either.isRight() ? either.get() : java.util.List.of();
        List<Song> posterable = List.ofAll(songs)
                .filter(a -> a.posterUrl() != null && !a.posterUrl().isEmpty());

        posterTotal.set(posterable.size());
        Future.traverse(executor, posterable, song ->
                Future.run(executor, () -> fetchAndOffer(song.id(), song.posterUrl(), gen)));

        pendingData.set(new PendingData(gen, either));
    }

    @KremaCommand
    public MusicDataReply pollGeniusMusicData(int generation) {
        PendingData pending = pendingData.get();
        if (pending == null) {
            return new MusicDataReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            return new MusicDataReply(pending.generation(), "superseded", null, null);
        }
        pendingData.set(null);
        return pending.result().fold(
                error -> new MusicDataReply(pending.generation(), "error", FetchError.uiMessage(error), null),
                songs -> new MusicDataReply(pending.generation(), "done", null, songs));
    }

    @KremaCommand
    public int pollGeniusMusicPosters() {
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
