package vangogh.controller;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
              "vote_average": 8.8, "genre_ids": [28, 878], "overview": "A dream-heist story.",
              "poster_path": "/x.jpg" }
          ]
        }
        """;

    /** Stub client returning canned responses instead of hitting the network. */
    static class StubClient extends TmdbClient {
        ApiResponse trendingResponse;
        ApiResponse searchResponse;
        final List<String> posterCalls = new ArrayList<>();

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
        public io.vavr.control.Option<String> loadPosterDataUri(String posterUrl) {
            posterCalls.add(posterUrl);
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
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
}
