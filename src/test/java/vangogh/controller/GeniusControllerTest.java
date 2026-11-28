package vangogh.controller;

import vangogh.error.FetchError;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.music.Credit;
import vangogh.music.GeniusMusicService;
import vangogh.music.MediaLink;
import vangogh.music.SongDetail;
import vangogh.net.ApiResponse;
import vangogh.net.GeniusClient;
import vangogh.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for {@link GeniusController}: the song list, AI recommendations
 * and the song-detail commands. Same contract throughout: the handler returns
 * the request generation at once (UI thread never blocks on network I/O) and
 * results arrive through their drain.
 */
class GeniusControllerTest {

    private static final SongDetail DETAIL = new SongDetail(
            "8434253", "Anti-Hero", "Taylor Swift", "Midnights", "2022-10-21", 2022,
            java.util.List.of("Taylor Swift", "Jack Antonoff"),
            java.util.List.of("Jack Antonoff", "Taylor Swift"),
            java.util.List.of(),
            "https://genius.com/Taylor-swift-anti-hero-lyrics",
            2364330L, "complete",
            "https://images.genius.com/anti-hero.1000x1000x1.jpg",
            java.util.List.of(),
            252, "",
            java.util.List.of(),
            "October 21, 2022", "English", 22,
            "https://genius.com/albums/Taylor-swift/Midnights/relationships",
            java.util.List.of(new Credit("Label", java.util.List.of("Republic Records"))),
            java.util.List.of(new MediaLink("YouTube", "video", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0)),
            "#b98d55", "#744a19", "#fff",
            "Anti-Hero is the lead single from Midnights.",
            "https://genius.com/albums/Taylor-swift/Midnights",
            "https://images.genius.com/album.1000x1000x1.jpg",
            "https://images.genius.com/artist.1000x1000x1.jpg",
            "https://genius.com/artists/Taylor-swift", true,
            "Electric Lady Studios, New York, NY",
            224, 4, 16, 17, 81);

    /** Stub service that sleeps, simulating a slow Genius round-trip. */
    static class SlowService extends GeniusMusicService {
        @Override
        public Either<FetchError, SongDetail> songDetail(String id) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(DETAIL);
        }

        @Override
        public Option<String> posterDataUri(String posterUrl) {
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub service returning the detail and a stubbed artwork data URI. */
    static class OneSongService extends GeniusMusicService {
        @Override
        public Either<FetchError, java.util.List<vangogh.music.Song>> fetchTrending() {
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, SongDetail> songDetail(String id) {
            return Either.right(DETAIL);
        }

        @Override
        public Option<String> posterDataUri(String posterUrl) {
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub service failing the fetch, to test the error drain. */
    static class FailingService extends GeniusMusicService {
        @Override
        public Either<FetchError, SongDetail> songDetail(String id) {
            return Either.left(new FetchError.Transport("Genius song", new RuntimeException("boom")));
        }
    }

    private static final String SEARCH_HITS = """
        {"response":{"hits":[
          {"type":"song","result":{
            "id":8434253,
            "title":"Anti-Hero",
            "primary_artist":{"name":"Taylor Swift"},
            "song_art_image_thumbnail_url":"https://images.genius.com/anti-hero.300x300x1.jpg",
            "release_date_for_display":"October 21, 2022"}}
        ]}}
        """;

    /** Stub Genius client: canned search hits and a stubbed artwork fetch. */
    static class StubSearchClient extends GeniusClient {
        ApiResponse searchResponse = new ApiResponse(200, SEARCH_HITS, null);

        StubSearchClient() {
            super("stub-token");
        }

        @Override
        public Try<ApiResponse> search(String query) {
            return Try.success(searchResponse);
        }

        @Override
        public Option<String> loadPosterDataUri(String imageUrl) {
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub model call: returns a canned reply or a mapped failure. */
    static class StubLlm extends LlmClient {
        final Either<FetchError, String> result;
        int calls;

        StubLlm(Either<FetchError, String> result) {
            this.result = result;
        }

        @Override
        public Either<FetchError, String> complete(LlmConfig config, String system, String user) {
            calls++;
            return result;
        }
    }

    /** Controller wired to the engine with a stubbed model call. */
    private GeniusController recommendController(GeniusMusicService service, StubLlm llm,
                                                 ExecutorService io) {
        RecommendationService engine = new RecommendationService(io, llm,
                () -> Either.right(new LlmConfig("https://llm.test/v1", "k", "test-model", 10, 10, LlmConfig.DEFAULT_TEMPERATURE)));
        return new GeniusController(service, io, engine);
    }

    /** Drains pollGeniusMusicData until this generation's fetch resolves (2s deadline). */
    private GeniusController.MusicDataReply drainMusic(GeniusController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        GeniusController.MusicDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollGeniusMusicData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    private GeniusController.SongDetailReply drainDetail(GeniusController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        GeniusController.SongDetailReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollSongDetail(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    @Test
    void requestSongDetailDoesNotBlockTheCallingThread() {
        GeniusController controller = new GeniusController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestSongDetail("8434253");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 250,
                "requestSongDetail blocked for " + elapsedMs + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void detailArrivesOnTheSongDetailDrainWithArtwork() throws Exception {
        GeniusController controller = new GeniusController(new OneSongService());

        int gen = controller.requestSongDetail("8434253");
        GeniusController.SongDetailReply reply = drainDetail(controller, gen);

        assertEquals("done", reply.status());
        assertNull(reply.error());
        assertEquals("Anti-Hero", reply.detail().title());
        assertEquals("Midnights", reply.detail().album());
        assertEquals(252, reply.detail().durationSeconds());
        assertEquals("data:image/jpeg;base64,stubbed", reply.artDataUri());
    }

    @Test
    void detailFailureSurfacesAsAnErrorStatus() throws Exception {
        GeniusController controller = new GeniusController(new FailingService());

        int gen = controller.requestSongDetail("0");
        GeniusController.SongDetailReply reply = drainDetail(controller, gen);

        assertEquals("error", reply.status());
        assertNull(reply.detail());
        assertTrue(reply.error().contains("Genius song"));
    }

    @Test
    void openingASecondDetailSupersedesTheFirstPoller() throws Exception {
        GeniusController controller = new GeniusController(new SlowService());

        int first = controller.requestSongDetail("1");
        int second = controller.requestSongDetail("2");
        assertTrue(second > first);

        // The first poller must never consume the second request's result.
        assertNotEquals("done", controller.pollSongDetail(first).status());

        assertEquals("done", drainDetail(controller, second).status());
    }

    @Test
    void songDetailAndListRequestsDoNotSupersedeEachOther() throws Exception {
        GeniusController controller = new GeniusController(new OneSongService());

        int listGen = controller.requestGeniusMusic("trending", "");
        int detailGen = controller.requestSongDetail("8434253");

        assertEquals("done", drainDetail(controller, detailGen).status());
        // The list request is independent, so its drain still resolves.
        long deadline = System.currentTimeMillis() + 2000;
        GeniusController.MusicDataReply listReply = controller.pollGeniusMusicData(listGen);
        while ("pending".equals(listReply.status()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            listReply = controller.pollGeniusMusicData(listGen);
        }
        assertNotEquals("superseded", listReply.status());
    }

    @Test
    void requestGeniusMusicWithRecommendKindRunsTheModelAndResolvesItsPicks() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.right(
                    "Anti-Hero | Taylor Swift\nNot A Real Song | Nobody At All"));
            GeniusController controller = recommendController(
                    new GeniusMusicService(new StubSearchClient()), llm, io);

            int gen = controller.requestGeniusMusic("recommend", "sad pop");
            GeniusController.MusicDataReply reply = drainMusic(controller, gen);

            assertEquals("done", reply.status());
            assertNull(reply.error());
            assertEquals(1, reply.items().size());   // the unresolvable pick is dropped
            assertEquals("Anti-Hero", reply.items().get(0).title());
            assertEquals("Taylor Swift", reply.items().get(0).artist());
            assertEquals(2022, reply.items().get(0).year());
            assertEquals(1, llm.calls);
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void recommendWithoutAQueryIsABadInputError() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            // The page never sends this (an empty box is trending), but the
            // engine must not spend a model call on nothing.
            StubLlm llm = new StubLlm(Either.right("Anti-Hero | Taylor Swift"));
            GeniusController controller = recommendController(
                    new GeniusMusicService(new StubSearchClient()), llm, io);

            int gen = controller.requestGeniusMusic("recommend", "  ");
            GeniusController.MusicDataReply reply = drainMusic(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("AI recommendations need a query"), reply.error());
            assertEquals(0, llm.calls);
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelFailureSurfacesOnTheDataDrain() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.left(new FetchError.Transport(
                    "LLM request", new RuntimeException("offline"))));
            GeniusController controller = recommendController(
                    new GeniusMusicService(new StubSearchClient()), llm, io);

            int gen = controller.requestGeniusMusic("recommend", "sad pop");
            GeniusController.MusicDataReply reply = drainMusic(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM request failed: offline"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelHttpErrorSurfacesWithItsStatus() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.left(
                    new FetchError.HttpStatus("LLM", 401, "{\"error\":\"bad key\"}")));
            GeniusController controller = recommendController(
                    new GeniusMusicService(new StubSearchClient()), llm, io);

            int gen = controller.requestGeniusMusic("recommend", "sad pop");
            GeniusController.MusicDataReply reply = drainMusic(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM failed: HTTP 401"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }
}
