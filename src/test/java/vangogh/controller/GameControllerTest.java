package vangogh.controller;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import vangogh.error.FetchError;
import vangogh.game.Game;
import vangogh.game.GameDetail;
import vangogh.game.GameResolver;
import vangogh.game.TgdbGameService;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;
import vangogh.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for GameController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestGames", ...)}.
 *
 * Uses a stubbed {@link TgdbGameService} (subclass, no real TheGamesDB calls).
 *
 * Contract (see the class comment on {@link GameController}): the command
 * kicks off the IO-pool fetch and returns the request generation at once —
 * the UI thread never blocks on network I/O. The resolved list arrives via
 * the {@code pollGameData} drain; posters via the {@code pollGamePosters}
 * drain.
 */
class GameControllerTest {

    /** Stub service whose fetch sleeps, simulating a slow TheGamesDB round-trip. */
    static class SlowService extends TgdbGameService {
        SlowService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, java.util.List<Game>> fetchTrending() {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, java.util.List<Game>> searchGames(String query) {
            return fetchTrending();
        }
    }

    /** Stub service that fails the fetch, to test the error drain. */
    static class FailingService extends TgdbGameService {
        FailingService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, java.util.List<Game>> fetchTrending() {
            return Either.left(new FetchError.Transport(
                    "TheGamesDB trending", new RuntimeException("boom")));
        }

        @Override
        public Either<FetchError, java.util.List<Game>> searchGames(String query) {
            return fetchTrending();
        }
    }

    /** TheGamesDB client with a canned search reply (no network). */
    static class StubTgdbClient extends TgdbClient {
        final String body;

        StubTgdbClient(String body) {
            super("stub-key");
            this.body = body;
        }

        @Override
        public Try<ApiResponse> searchGame(String query) {
            return Try.success(new ApiResponse(200, body, null));
        }
    }

    /** Stub service whose resolver uses the canned client, posters served instantly. */
    static class RecommendingService extends SlowService {
        private final GameResolver resolver;

        RecommendingService(GameResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public GameResolver resolver() {
            return resolver;
        }

        @Override
        public io.vavr.control.Option<String> posterDataUri(String posterUrl) {
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** A complete record the detail page can paint, art included. */
    private static final GameDetail DETAIL = new GameDetail(
            42,
            "Sample Game",
            "2024-06-15",
            2024,
            "E10+ - Everyone 10+",
            1,
            java.util.List.of("Action"),
            java.util.List.of("PC"),
            java.util.List.of("Sample Publisher"),
            java.util.List.of("Sample Developer"),
            "An adventure.",
            "https://youtu.be/sample",
            "https://thegamesdb.net/game/42/",
            "https://cdn.thegamesdb.net/images/original/boxart/front/42.jpg",
            "https://cdn.thegamesdb.net/images/medium/fanart/42.jpg",
            java.util.List.of("https://cdn.thegamesdb.net/images/medium/screenshots/42-1.jpg"),
            java.util.List.of(new GameDetail.Fact("Release", "2024-06-15")),
            "series",
            java.util.List.of(new GameDetail.Related(
                    43, "Sample Game 2", 2025, "E10+ - Everyone 10+", "Action",
                    "https://cdn.thegamesdb.net/images/medium/boxart/front/43.jpg")));

    /** Stub service: the detail record is canned, artwork resolves instantly. */
    static class DetailService extends TgdbGameService {
        DetailService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, GameDetail> gameDetail(int id) {
            return Either.right(DETAIL);
        }

        @Override
        public Option<String> detailImageDataUri(String imageUrl) {
            return Option.some("data:image/jpeg;base64,"
                    + Integer.toHexString(imageUrl.hashCode()));
        }

        @Override
        public Either<FetchError, java.util.List<Game>> fetchTrending() {
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, java.util.List<Game>> searchGames(String query) {
            return fetchTrending();
        }
    }

    /** Detail fetch that sleeps, simulating a slow TheGamesDB round-trip. */
    static class SlowDetailService extends DetailService {
        @Override
        public Either<FetchError, GameDetail> gameDetail(int id) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(DETAIL);
        }
    }

    /** Detail fetch that fails, to test the error drain. */
    static class FailingDetailService extends TgdbGameService {
        FailingDetailService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, GameDetail> gameDetail(int id) {
            return Either.left(new FetchError.Transport(
                    "TheGamesDB game", new RuntimeException("boom")));
        }
    }

    /** EventEmitter that captures emitted events instead of talking to a webview. */
    static class TestEventEmitter extends EventEmitter {
        final java.util.List<KremaEvent> emitted = new ArrayList<>();

        TestEventEmitter() {
            super(null);
        }

        @Override
        public void emit(String eventName, Object payload) {
            emitted.add(new KremaEvent(eventName, payload));
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

    private static final String SAMPLE_GAME = """
            {"id":42,"game_title":"Sample Game","release_date":"2024-06-15",\
            "overview":"An adventure.","genres":[1]}
            """;

    private static String payload(String... rows) {
        return "{\"data\":{\"games\":[" + String.join(",", rows) + "]}," +
                "\"include\":{\"boxart\":{\"data\":{}}}}";
    }

    /** Controller wired to the engine with a stubbed model call. */
    private GameController recommendController(TgdbGameService service, StubLlm llm,
                                               ExecutorService io) {
        RecommendationService engine = new RecommendationService(io, llm,
                () -> Either.right(new LlmConfig("https://llm.test/v1", "k", "test-model", 10, 10, LlmConfig.DEFAULT_TEMPERATURE)));
        return new GameController(service, io, engine);
    }

    /** Drains pollGameData until this generation's fetch resolves (2s deadline). */
    private GameController.GameDataReply drainData(GameController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        GameController.GameDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollGameData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    /** Drains pollGameDetail until this generation's fetch resolves (2s deadline). */
    private GameController.GameDetailReply drainDetail(GameController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        GameController.GameDetailReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollGameDetail(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    /** Drains pollGameDetailArt until the −1 completion signal (2s deadline). */
    private int drainDetailArt(GameController controller) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollGameDetailArt();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        return result;
    }

    @Test
    void requestGamesDoesNotBlockTheCallingThreadDuringFetch() {
        GameController controller = new GameController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestGames("trending", "");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O (that froze the
        // window for the whole round-trip — the 1-5s movie→game toggle freeze).
        assertTrue(elapsedMs < 250,
                "requestGames blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void fetchedGamesArriveOnTheDataDrain() throws Exception {
        GameController controller = new GameController(new SlowService());

        int gen = controller.requestGames("trending", "");
        GameController.GameDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertNotNull(reply.items());
        assertTrue(reply.items().isEmpty());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        GameController controller = new GameController(new FailingService());

        int gen = controller.requestGames("trending", "");
        GameController.GameDataReply reply = drainData(controller, gen);

        // The fetch failure's own message must surface (it becomes the page's
        // error state, same text the old thrown-edge produced).
        assertEquals("error", reply.status());
        assertEquals("TheGamesDB trending failed: boom", reply.error());
    }

    @Test
    void requestGamesWithRecommendKindRunsTheModelAndResolvesItsPicks() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.right(
                    "Sample Game | 2024\nNot A Real Game | 1999"));
            GameController controller = recommendController(
                    new RecommendingService(new GameResolver(
                            new StubTgdbClient(payload(SAMPLE_GAME)))), llm, io);

            int gen = controller.requestGames("recommend", "cozy adventures");
            GameController.GameDataReply reply = drainData(controller, gen);

            assertEquals("done", reply.status());
            assertNull(reply.error());
            assertEquals(1, reply.items().size());   // the unresolvable pick is dropped
            assertEquals("Sample Game", reply.items().get(0).game_title());
            assertEquals("2024-06-15", reply.items().get(0).release_date());
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
            StubLlm llm = new StubLlm(Either.right("Sample Game | 2024"));
            GameController controller = recommendController(
                    new RecommendingService(new GameResolver(
                            new StubTgdbClient(payload(SAMPLE_GAME)))), llm, io);

            int gen = controller.requestGames("recommend", "  ");
            GameController.GameDataReply reply = drainData(controller, gen);

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
            GameController controller = recommendController(
                    new RecommendingService(new GameResolver(
                            new StubTgdbClient(payload(SAMPLE_GAME)))), llm, io);

            int gen = controller.requestGames("recommend", "cozy adventures");
            GameController.GameDataReply reply = drainData(controller, gen);

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
            GameController controller = recommendController(
                    new RecommendingService(new GameResolver(
                            new StubTgdbClient(payload(SAMPLE_GAME)))), llm, io);

            int gen = controller.requestGames("recommend", "cozy adventures");
            GameController.GameDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM failed: HTTP 401"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aCatalogFailureWithNoResolvedPicksSurfacesItsError() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.right("Sample Game | 2024"));
            TgdbClient failing = new TgdbClient("stub-key") {
                @Override
                public Try<ApiResponse> searchGame(String query) {
                    return Try.success(new ApiResponse(500, "boom", "server error"));
                }
            };
            GameController controller = recommendController(
                    new RecommendingService(new GameResolver(failing)), llm, io);

            int gen = controller.requestGames("recommend", "cozy adventures");
            GameController.GameDataReply reply = drainData(controller, gen);

            // "The network ate everything" must not render as "no games".
            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("TheGamesDB search failed: HTTP 500"),
                    reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void pollGameDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        GameController controller = new GameController(new SlowService());

        int firstGen = controller.requestGames("trending", "");
        int secondGen = controller.requestGames("trending", "");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result: a
        // stale poller is superseded by generation alone, whatever the one-slot
        // drain happens to hold.
        assertEquals("superseded", controller.pollGameData(firstGen).status());

        // The newer request still delivers through the drain.
        assertEquals("done", drainData(controller, secondGen).status());
    }

    @Test
    void requestGameDetailDoesNotBlockTheCallingThreadDuringFetch() {
        GameController controller = new GameController(new SlowDetailService());

        long start = System.nanoTime();
        int gen = controller.requestGameDetail(42);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 250,
                "requestGameDetail blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void gameDetailArrivesOnItsDrainWithTheWholeRecord() throws Exception {
        GameController controller = new GameController(new DetailService());

        int gen = controller.requestGameDetail(42);
        GameController.GameDetailReply reply = drainDetail(controller, gen);

        assertEquals("done", reply.status());
        assertNull(reply.error());
        assertNotNull(reply.detail());
        assertEquals("Sample Game", reply.detail().title());
        assertEquals("E10+ - Everyone 10+", reply.detail().rating());
        assertEquals(java.util.List.of("PC"), reply.detail().platforms());
        assertEquals(1, reply.detail().related().size());
        assertEquals("https://cdn.thegamesdb.net/images/original/boxart/front/42.jpg",
                reply.detail().boxartUrl());
    }

    @Test
    void gameDetailArtStreamsEverySlotAndCompletes() throws Exception {
        TestEventEmitter emitter = new TestEventEmitter();
        GameController controller = new GameController(new DetailService());
        controller.setEventEmitter(emitter);

        int gen = controller.requestGameDetail(42);
        assertEquals("done", drainDetail(controller, gen).status());
        assertEquals(-1, drainDetailArt(controller));

        // boxart + hero + one screenshot + one related boxart.
        assertEquals(4, emitter.emitted.size());
        Set<String> slots = new java.util.HashSet<>();
        for (KremaEvent event : emitter.emitted) {
            assertEquals("gameDetailArt", event.name());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> payload =
                    (java.util.Map<String, Object>) event.payload();
            slots.add((String) payload.get("slot"));
            assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
        }
        assertEquals(Set.of("boxart", "hero", "still:0", "related:43"), slots);
    }

    @Test
    void gameDetailFailureSurfacesOnItsDrain() throws Exception {
        GameController controller = new GameController(new FailingDetailService());

        int gen = controller.requestGameDetail(42);
        GameController.GameDetailReply reply = drainDetail(controller, gen);

        assertEquals("error", reply.status());
        assertEquals("TheGamesDB game failed: boom", reply.error());
    }

    @Test
    void aMissingGameIdIsABadInputError() throws Exception {
        // id <= 0 short-circuits before any network call.
        GameController controller = new GameController(new TgdbGameService("test-key"));

        int gen = controller.requestGameDetail(0);
        GameController.GameDetailReply reply = drainDetail(controller, gen);

        assertEquals("error", reply.status());
        assertTrue(reply.error().contains("game id"), reply.error());
    }

    @Test
    void openingASecondGameDetailSupersedesTheFirstPoller() throws Exception {
        GameController controller = new GameController(new SlowDetailService());

        int firstGen = controller.requestGameDetail(42);
        int secondGen = controller.requestGameDetail(43);
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's detail.
        assertNotEquals("done", controller.pollGameDetail(firstGen).status());
        assertEquals("done", drainDetail(controller, secondGen).status());
    }

    @Test
    void theGameDetailAndGridRequestsDoNotSupersedeEachOther() throws Exception {
        GameController controller = new GameController(new DetailService());

        int detailGen = controller.requestGameDetail(42);
        int gridGen = controller.requestGames("trending", "");

        assertEquals("done", drainDetail(controller, detailGen).status());
        assertEquals("done", drainData(controller, gridGen).status());
        assertEquals(-1, drainDetailArt(controller));
    }
}
