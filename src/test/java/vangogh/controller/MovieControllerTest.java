package vangogh.controller;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import io.vavr.control.Either;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;
import vangogh.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for MovieController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestMovies", ...)}.
 *
 * Uses stubbed {@link TmdbClient}/{@link TmdbMovieService} injected via the
 * package-private constructors, so no real TMDB network calls are made.
 *
 * Contract (see the class comment on {@link MovieController}): the command
 * kicks off the IO-pool fetch and returns the request generation at once —
 * the UI thread never blocks on network I/O. The resolved list arrives via
 * the {@code pollMovieData} drain; posters via the {@code pollMoviePosters}
 * drain.
 */
class MovieControllerTest {

    private static final String VALID_JSON = """
        {
          "page": 1,
          "total_results": 1,
          "results": [
            { "id": 27205, "title": "Inception", "release_date": "2010-07-16",
              "vote_average": 8.8, "vote_count": 35000, "genre_ids": [28, 878],
              "overview": "A dream-heist story.", "poster_path": "/x.jpg" }
          ]
        }
        """;

    /** One /movie/:id payload with enough appended blocks for the bento view. */
    private static final String DETAIL_JSON = """
        {
          "id": 27205,
          "title": "Inception",
          "tagline": "Your mind is the scene of the crime.",
          "overview": "A thief who steals corporate secrets through dream-sharing technology.",
          "release_date": "2010-07-16",
          "runtime": 148,
          "status": "Released",
          "vote_average": 8.4,
          "vote_count": 2300000,
          "budget": 160000000,
          "revenue": 836800000,
          "poster_path": "/inception.jpg",
          "genres": [
            { "id": 28, "name": "Action" },
            { "id": 878, "name": "Science Fiction" }
          ],
          "keywords": { "keywords": [ { "id": 1, "name": "dream" } ] },
          "credits": {
            "cast": [
              { "name": "Leonardo DiCaprio", "character": "Dom Cobb", "profile_path": "/leo.jpg" },
              { "name": "Joseph Gordon-Levitt", "character": "Arthur", "profile_path": "/jgl.jpg" }
            ],
            "crew": [
              { "name": "Christopher Nolan", "job": "Director", "profile_path": "/nolan.jpg" },
              { "name": "Wally Pfister", "job": "Director of Photography", "profile_path": "/pfister.jpg" }
            ]
          },
          "images": { "backdrops": [ { "file_path": "/still-01.jpg" } ] },
          "similar": {
            "results": [
              {
                "id": 155,
                "title": "The Dark Knight",
                "release_date": "2008-07-18",
                "vote_average": 8.5,
                "genre_ids": [28],
                "poster_path": "/tdk.jpg"
              }
            ]
          },
          "videos": { "results": [ { "site": "YouTube", "type": "Trailer", "key": "trailer-key" } ] },
          "release_dates": {
            "results": [
              { "iso_3166_1": "US", "release_dates": [ { "certification": "PG-13", "type": 3 } ] }
            ]
          },
          "production_companies": [ { "name": "Warner Bros." } ],
          "production_countries": [ { "name": "United States of America" } ]
        }
        """;

    /** Stub client returning canned responses instead of hitting the network. */
    static class StubClient extends TmdbClient {
        ApiResponse trendingResponse;
        ApiResponse searchResponse;
        ApiResponse detailResponse;
        final List<String> posterCalls = new ArrayList<>();
        final List<String> detailArtCalls = new ArrayList<>();

        StubClient() {
            super("test-key");
        }

        @Override
        public io.vavr.control.Try<ApiResponse> trending() {
            return io.vavr.control.Try.success(trendingResponse);
        }

        @Override
        public io.vavr.control.Try<ApiResponse> searchMovie(String query) {
            return io.vavr.control.Try.success(searchResponse);
        }

        @Override
        public io.vavr.control.Try<ApiResponse> movieDetail(int id) {
            return io.vavr.control.Try.success(detailResponse);
        }

        @Override
        public io.vavr.control.Option<String> loadPosterDataUri(String posterUrl) {
            posterCalls.add(posterUrl);
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
        }

        @Override
        public io.vavr.control.Option<String> loadDetailImageDataUri(String imageUrl) {
            detailArtCalls.add(imageUrl);
            return io.vavr.control.Option.some("data:image/jpeg;base64,detail");
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

    private TmdbMovieService serviceWith(StubClient client) {
        return new TmdbMovieService(client); // package-private ctor
    }

    private MovieController controllerWith(TmdbMovieService service) {
        return new MovieController(service); // package-private ctor
    }

    /** Stub model call: returns a canned reply or a mapped failure. */
    static class StubLlm extends LlmClient {
        final Either<FetchError, String> result;

        StubLlm(Either<FetchError, String> result) {
            this.result = result;
        }

        @Override
        public Either<FetchError, String> complete(LlmConfig config, String system, String user) {
            return result;
        }
    }

    /** Controller wired to the engine with a stubbed model call. */
    private MovieController recommendController(StubClient client, Either<FetchError, String> llmResult,
                                                ExecutorService io) {
        RecommendationService engine = new RecommendationService(io, new StubLlm(llmResult),
                () -> Either.right(new LlmConfig("https://llm.test/v1", "k", "test-model", 10, 10)));
        return new MovieController(serviceWith(client), io, engine);
    }

    /** Drains pollMovieData until this generation's fetch resolves (2s deadline). */
    private MovieController.MovieDataReply drainData(MovieController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        MovieController.MovieDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollMovieData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    /** Drains pollMovieDetail until this generation's fetch resolves (2s deadline). */
    private MovieController.MovieDetailReply drainDetail(MovieController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        MovieController.MovieDetailReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollMovieDetail(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    /** Drains pollMovieDetailArt until the −1 completion signal (2s deadline). */
    private int drainDetailArt(MovieController controller) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollMovieDetailArt();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        return result;
    }

    @Test
    void requestMoviesWithSearchKindDispatchesToSearchMovies() throws Exception {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovies("search", "Inception");
        MovieController.MovieDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("Inception", reply.items().get(0).title());
        assertEquals(2010, reply.items().get(0).year());
    }

    @Test
    void requestMoviesWithNonSearchKindDispatchesToFetchTrending() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovies("trending", "ignored");
        MovieController.MovieDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("Inception", reply.items().get(0).title());
    }

    @Test
    void requestMoviesWithEmptyQueryFallsBackToTrending() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovies("search", "");
        MovieController.MovieDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("Inception", reply.items().get(0).title());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(500, "boom", "server error");
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovies("trending", "x");
        MovieController.MovieDataReply reply = drainData(controller, gen);

        // The fetch failure's own message must surface (it becomes the page's
        // error state, same text the old thrown-edge produced).
        assertEquals("error", reply.status());
        assertTrue(reply.error().contains("TMDB trending failed: HTTP 500"));
    }

    @Test
    void requestMoviesDoesNotBlockTheCallingThreadDuringFetch() throws Exception {
        StubClient client = new StubClient() {
            @Override
            public io.vavr.control.Try<ApiResponse> trending() {
                try {
                    Thread.sleep(500); // simulate a slow TMDB round-trip
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return io.vavr.control.Try.success(new ApiResponse(200, VALID_JSON, null));
            }
        };
        MovieController controller = controllerWith(serviceWith(client));

        long start = System.nanoTime();
        controller.requestMovies("trending", "x");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O (that freezes the
        // window — no repaints, no loading state, 1-3s of dead UI at startup).
        assertTrue(elapsedMs < 250,
                "requestMovies blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
    }

    @Test
    void pollMovieDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int firstGen = controller.requestMovies("trending", "x");
        int secondGen = controller.requestMovies("trending", "y");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result —
        // two loops can poll the same one-slot drain. Whether the first poll
        // observes "pending" (fetch still in flight) or "superseded" (resolved
        // and dropped as stale) is timing-dependent; "done" would mean it
        // stole the newer request's data.
        assertNotEquals("done", controller.pollMovieData(firstGen).status());

        // The newer request still delivers through the drain.
        assertEquals("done", drainData(controller, secondGen).status());
    }

    @Test
    void pollMoviePostersDrainsQueuedPostersAndEmitsPosterEvents() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        int gen = controller.requestMovies("trending", "x");
        MovieController.MovieDataReply reply = drainData(controller, gen);
        assertEquals("done", reply.status()); // poster bookkeeping is in place

        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollMoviePosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }

        assertEquals(-1, result);
        assertEquals(1, emitter.emitted.size());
        KremaEvent e = emitter.emitted.get(0);
        assertEquals("poster", e.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload();
        assertEquals(27205, payload.get("id"));
        assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
    }

    @Test
    void pollMoviePostersSkippedPostersDoNotStallPolling() throws Exception {
        StubClient client = new StubClient() {
            @Override
            public io.vavr.control.Option<String> loadPosterDataUri(String posterUrl) {
                return io.vavr.control.Option.none(); // every poster fetch fails
            }
        };
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));
        controller.setEventEmitter(new TestEventEmitter());

        int gen = controller.requestMovies("trending", "x");
        assertEquals("done", drainData(controller, gen).status());

        // Skipped posters are counted against the total, so the drain must
        // reach the -1 completion signal promptly instead of hanging.
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollMoviePosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        assertEquals(-1, result);
    }

    @Test
    void requestMoviesWithRecommendKindRunsTheModelAndResolvesItsPicks() throws Exception {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, VALID_JSON, null);
        ExecutorService io = Executors.newFixedThreadPool(4);

        try {
            MovieController controller = recommendController(client,
                    Either.right("Inception | 2010\nNot A Real Film | 1999"), io);

            int gen = controller.requestMovies("recommend", "dream heist");
            MovieController.MovieDataReply reply = drainData(controller, gen);

            assertEquals("done", reply.status());
            assertEquals(1, reply.items().size());   // the unresolvable pick is dropped
            assertEquals("Inception", reply.items().get(0).title());
            assertEquals(2010, reply.items().get(0).year());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void recommendWithoutAQueryIsABadInputError() throws Exception {
        StubClient client = new StubClient();
        ExecutorService io = Executors.newFixedThreadPool(4);

        try {
            // The page never sends this (an empty box is trending), but the
            // engine must not spend a model call on nothing.
            MovieController controller = recommendController(client,
                    Either.right("Inception | 2010"), io);

            int gen = controller.requestMovies("recommend", "  ");
            MovieController.MovieDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("AI recommendations need a query"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelFailureSurfacesOnTheDataDrain() throws Exception {
        StubClient client = new StubClient();
        ExecutorService io = Executors.newFixedThreadPool(4);

        try {
            MovieController controller = recommendController(client,
                    Either.left(new FetchError.Transport(
                            "LLM request", new RuntimeException("offline"))), io);

            int gen = controller.requestMovies("recommend", "dream heist");
            MovieController.MovieDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM request failed: offline"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelHttpErrorSurfacesWithItsStatus() throws Exception {
        StubClient client = new StubClient();
        ExecutorService io = Executors.newFixedThreadPool(4);

        try {
            MovieController controller = recommendController(client,
                    Either.left(new FetchError.HttpStatus("LLM", 401, "{\"error\":\"bad key\"}")), io);

            int gen = controller.requestMovies("recommend", "dream heist");
            MovieController.MovieDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM failed: HTTP 401"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    // ------------------------------------------------------------ detail view

    @Test
    void requestMovieDetailDoesNotBlockTheCallingThreadDuringFetch() {
        StubClient client = new StubClient() {
            @Override
            public io.vavr.control.Try<ApiResponse> movieDetail(int id) {
                try {
                    Thread.sleep(500); // simulate a slow TMDB round-trip
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return io.vavr.control.Try.success(new ApiResponse(200, DETAIL_JSON, null));
            }
        };
        MovieController controller = controllerWith(serviceWith(client));

        long start = System.nanoTime();
        controller.requestMovieDetail(27205);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 250,
                "requestMovieDetail blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
    }

    @Test
    void movieDetailArrivesOnItsDrainWithFactsCastStillsAndSimilar() throws Exception {
        StubClient client = new StubClient();
        client.detailResponse = new ApiResponse(200, DETAIL_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovieDetail(27205);
        MovieController.MovieDetailReply reply = drainDetail(controller, gen);

        assertEquals("done", reply.status());
        assertEquals("Inception", reply.detail().title());
        assertEquals(148, reply.detail().runtimeMinutes());
        assertEquals(10, reply.detail().facts().size());  // no screenplay/music rows in this fixture
        assertEquals(4, reply.detail().cast().size());   // two cast + director + DP
        assertEquals(1, reply.detail().stills().size());
        assertEquals(1, reply.detail().similar().size());
    }

    @Test
    void movieDetailArtDrainStreamsEverySlot() throws Exception {
        StubClient client = new StubClient();
        client.detailResponse = new ApiResponse(200, DETAIL_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));
        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        int gen = controller.requestMovieDetail(27205);
        assertEquals("done", drainDetail(controller, gen).status());

        assertEquals(-1, drainDetailArt(controller));

        // poster + four credit headshots + one still + one similar poster
        assertEquals(7, emitter.emitted.size());
        List<String> slots = new ArrayList<>();
        for (KremaEvent event : emitter.emitted) {
            assertEquals("movieDetailArt", event.name());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.payload();
            slots.add((String) payload.get("slot"));
            assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
        }
        assertTrue(slots.containsAll(List.of(
                "poster", "cast:0", "cast:1", "cast:2", "cast:3", "still:0", "similar:155")),
                slots.toString());
    }

    @Test
    void movieDetailArtSkippedImagesDoNotStallPolling() throws Exception {
        StubClient client = new StubClient() {
            @Override
            public io.vavr.control.Option<String> loadDetailImageDataUri(String imageUrl) {
                return io.vavr.control.Option.none(); // every image fetch fails
            }
        };
        client.detailResponse = new ApiResponse(200, DETAIL_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));
        controller.setEventEmitter(new TestEventEmitter());

        int gen = controller.requestMovieDetail(27205);
        assertEquals("done", drainDetail(controller, gen).status());

        assertEquals(-1, drainDetailArt(controller));
    }

    @Test
    void movieDetailErrorSurfacesOnItsDrain() throws Exception {
        StubClient client = new StubClient();
        client.detailResponse = new ApiResponse(500, "boom", "server error");
        MovieController controller = controllerWith(serviceWith(client));

        int gen = controller.requestMovieDetail(27205);
        MovieController.MovieDetailReply reply = drainDetail(controller, gen);

        assertEquals("error", reply.status());
        assertTrue(reply.error().contains("TMDB movie failed: HTTP 500"), reply.error());
    }

    @Test
    void pollMovieDetailOlderGenerationIsSupersededByANewerRequest() throws Exception {
        StubClient client = new StubClient();
        client.detailResponse = new ApiResponse(200, DETAIL_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        int firstGen = controller.requestMovieDetail(27205);
        int secondGen = controller.requestMovieDetail(155);
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the newer request's detail.
        assertNotEquals("done", controller.pollMovieDetail(firstGen).status());
        assertEquals("done", drainDetail(controller, secondGen).status());
    }
}
