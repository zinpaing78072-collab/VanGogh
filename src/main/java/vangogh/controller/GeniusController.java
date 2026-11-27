package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Form;
import vangogh.music.GeniusMusicService;
import vangogh.music.Song;
import vangogh.music.SongDetail;
import vangogh.recommend.PickResolver;
import vangogh.recommend.RecommendationService;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class GeniusController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final GeniusMusicService service;
    private final ExecutorService executor;
    private final PickResolver<Song> resolver;
    private final RecommendationService recommendations;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingPoster(String id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    private final LatestResult<Either<FetchError, java.util.List<Song>>> pendingData = new LatestResult<>();

    private record DetailOutcome(Either<FetchError, SongDetail> result, String artDataUri) {}

    private final AtomicInteger detailGeneration = new AtomicInteger();
    private final LatestResult<DetailOutcome> pendingDetail = new LatestResult<>();

    public record MusicDataReply(int generation, String status, String error, java.util.List<Song> items) {}
    public record SongDetailReply(int generation, String status, String error,
                                  SongDetail detail, String artDataUri) {}

    public GeniusController() {
        this(new GeniusMusicService());
    }

    /** Package-private constructor used by tests. */
    GeniusController(GeniusMusicService service) {
        this(service, newIoPool());
    }

    /** Package-private seam: lets a test inject the pool it will shut down. */
    GeniusController(GeniusMusicService service, ExecutorService executor) {
        this(service, executor, null);
    }

    /** Package-private seam: tests inject the engine and its stubbed model call. */
    GeniusController(GeniusMusicService service, ExecutorService executor,
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

    @KremaCommand
    public int requestGeniusMusic(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();
        pendingData.clear();

        Future.of(executor, () -> fetchSongs(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Song>> fetchSongs(String kind, String query) {
        if ("recommend".equalsIgnoreCase(kind)) {
            return recommendations.recommend(Form.MUSIC, query, resolver)
                    .map(songs -> List.ofAll(songs)
                            .distinctBy(Song::id)
                            .toJavaList());
        }
        return "search".equalsIgnoreCase(kind)
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

        pendingData.park(gen, either);
    }

    @KremaCommand
    public MusicDataReply pollGeniusMusicData(int generation) {
        if (this.generation.get() > generation) {
            return new MusicDataReply(generation, "superseded", null, null);
        }
        return pendingData.take(generation).fold(
                () -> new MusicDataReply(generation, "pending", null, null),
                either -> either.fold(
                        error -> new MusicDataReply(
                                generation, "error", FetchError.uiMessage(error), null),
                        songs -> new MusicDataReply(generation, "done", null, songs)));
    }

    /**
     * Kick off the song-detail fetch and return its generation at once (the UI
     * thread never blocks on network I/O). The page then polls
     * {@link #pollSongDetail(int)} until the detail or an error arrives.
     *
     * <p>Uses its own generation counter: opening a detail must not supersede a
     * list request (or its poster stream) that is still in flight.
     */
    @KremaCommand
    public int requestSongDetail(String id) {
        int gen = detailGeneration.incrementAndGet();
        pendingDetail.clear();

        Future.of(executor, () -> {
            Either<FetchError, SongDetail> result = service.songDetail(id);
            String art = result.isRight()
                    ? service.posterDataUri(result.get().artUrl()).getOrElse("")
                    : "";
            return new DetailOutcome(result, art);
        }).onComplete(result -> deliverDetail(gen, result));
        return gen;
    }

    private void deliverDetail(int gen, Try<DetailOutcome> result) {
        if (detailGeneration.get() != gen) {
            return;
        }
        DetailOutcome outcome = result.isFailure()
                ? new DetailOutcome(
                        Either.left(new FetchError.Transport("Genius song", result.getCause())), "")
                : result.get();
        pendingDetail.park(gen, outcome);
    }

    @KremaCommand
    public SongDetailReply pollSongDetail(int generation) {
        if (detailGeneration.get() > generation) {
            return new SongDetailReply(generation, "superseded", null, null, null);
        }
        return pendingDetail.take(generation).fold(
                () -> new SongDetailReply(generation, "pending", null, null, null),
                outcome -> outcome.result().fold(
                        error -> new SongDetailReply(generation,
                                "error", FetchError.uiMessage(error), null, null),
                        detail -> new SongDetailReply(generation,
                                "done", null, detail, outcome.artDataUri())));
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
