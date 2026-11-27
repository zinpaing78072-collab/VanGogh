package vangogh.controller;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import vangogh.error.FetchError;
import vangogh.music.Album;
import vangogh.music.MusicBrainzService;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for MusicController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestMusic", ...)}.
 *
 * Uses a stubbed {@link MusicBrainzService} (subclass, no real MusicBrainz
 * calls). Contract (see the class comment on {@link MusicController}): the
 * command kicks off the IO-pool fetch and returns the request generation at
 * once — the UI thread never blocks on network I/O. The resolved list arrives
 * via the {@code pollMusicData} drain; covers via the {@code pollMusicPosters}
 * drain.
 */
class MusicControllerTest {

    private static final Album ABBEY = new Album(
            "9162580e-5df4-32de-80cc-f45a8d8a9b1d", "Abbey Road", "The Beatles",
            1969, "Album", "classic rock",
            "https://coverartarchive.org/release-group/9162580e-5df4-32de-80cc-f45a8d8a9b1d/front-250");

    /** Stub service whose fetch sleeps, simulating a slow MusicBrainz round-trip. */
    static class SlowService extends MusicBrainzService {
        @Override
        public Either<FetchError, java.util.List<Album>> fetchTrending() {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, java.util.List<Album>> searchAlbums(String query) {
            return fetchTrending();
        }
    }

    /** Stub service returning one album, with a stubbed cover fetch. */
    static class OneAlbumService extends MusicBrainzService {
        @Override
        public Either<FetchError, java.util.List<Album>> fetchTrending() {
            return Either.right(java.util.List.of(ABBEY));
        }

        @Override
        public Either<FetchError, java.util.List<Album>> searchAlbums(String query) {
            return fetchTrending();
        }

        @Override
        public Option<String> posterDataUri(String posterUrl) {
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub service that fails the fetch, to test the error drain. */
    static class FailingService extends MusicBrainzService {
        @Override
        public Either<FetchError, java.util.List<Album>> fetchTrending() {
            return Either.left(new FetchError.Transport(
                    "MusicBrainz trending", new RuntimeException("boom")));
        }

        @Override
        public Either<FetchError, java.util.List<Album>> searchAlbums(String query) {
            return fetchTrending();
        }
    }

    /** EventEmitter that captures emitted events instead of talking to a webview. */
    static class TestEventEmitter extends EventEmitter {
        final List<KremaEvent> emitted = new ArrayList<>();

        TestEventEmitter() {
            super(null);
        }

        @Override
        public void emit(String eventName, Object payload) {
            emitted.add(new KremaEvent(eventName, payload));
        }
    }

    /** Drains pollMusicData until this generation's fetch resolves (2s deadline). */
    private MusicController.MusicDataReply drainData(MusicController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        MusicController.MusicDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollMusicData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    @Test
    void requestMusicDoesNotBlockTheCallingThreadDuringFetch() {
        MusicController controller = new MusicController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestMusic("trending", "");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O.
        assertTrue(elapsedMs < 250,
                "requestMusic blocked the calling thread for " + elapsedMs + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void requestMusicWithSearchKindDispatchesToSearchAlbums() throws Exception {
        MusicController controller = new MusicController(new SlowService());

        int gen = controller.requestMusic("search", "pink floyd");
        MusicController.MusicDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertNotNull(reply.items());
    }

    @Test
    void fetchedAlbumsArriveOnTheDataDrain() throws Exception {
        MusicController controller = new MusicController(new OneAlbumService());

        int gen = controller.requestMusic("trending", "");
        MusicController.MusicDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("Abbey Road", reply.items().get(0).title());
        assertEquals("9162580e-5df4-32de-80cc-f45a8d8a9b1d", reply.items().get(0).id());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        MusicController controller = new MusicController(new FailingService());

        int gen = controller.requestMusic("trending", "");
        MusicController.MusicDataReply reply = drainData(controller, gen);

        assertEquals("error", reply.status());
        assertEquals("MusicBrainz trending failed: boom", reply.error());
    }

    @Test
    void pollMusicDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        MusicController controller = new MusicController(new SlowService());

        int firstGen = controller.requestMusic("trending", "");
        int secondGen = controller.requestMusic("trending", "");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result: a
        // stale poller is superseded by generation alone, whatever the one-slot
        // drain happens to hold.
        assertEquals("superseded", controller.pollMusicData(firstGen).status());

        assertEquals("done", drainData(controller, secondGen).status());
    }

    @Test
    void pollMusicPostersDrainsQueuedCoversAndEmitsMusicPosterEvents() throws Exception {
        MusicController controller = new MusicController(new OneAlbumService());

        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        int gen = controller.requestMusic("trending", "");
        MusicController.MusicDataReply reply = drainData(controller, gen);
        assertEquals("done", reply.status()); // cover bookkeeping is in place

        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollMusicPosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }

        assertEquals(-1, result);
        assertEquals(1, emitter.emitted.size());
        KremaEvent e = emitter.emitted.get(0);
        assertEquals("musicPoster", e.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload();
        assertEquals("9162580e-5df4-32de-80cc-f45a8d8a9b1d", payload.get("id"));
        assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
    }

    @Test
    void pollMusicPostersSkippedCoversDoNotStallPolling() throws Exception {
        MusicController controller = new MusicController(new MusicBrainzService() {
            @Override
            public Either<FetchError, java.util.List<Album>> fetchTrending() {
                return Either.right(java.util.List.of(ABBEY));
            }

            @Override
            public Either<FetchError, java.util.List<Album>> searchAlbums(String query) {
                return fetchTrending();
            }

            @Override
            public Option<String> posterDataUri(String posterUrl) {
                return Option.none(); // every cover fetch fails
            }
        });
        controller.setEventEmitter(new TestEventEmitter());

        int gen = controller.requestMusic("trending", "");
        assertEquals("done", drainData(controller, gen).status());

        // Skipped covers are counted against the total, so the drain must
        // reach the -1 completion signal promptly instead of hanging.
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollMusicPosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        assertEquals(-1, result);
    }
}
