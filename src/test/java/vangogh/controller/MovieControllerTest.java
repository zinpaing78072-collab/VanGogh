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
 * {@code window.krema.invoke("requestMovies", ...)} and
 * {@code window.krema.invoke("pollPosters", ...)}.
 *
 * Uses stubbed {@link TmdbClient}/{@link TmdbMovieService} injected via the
 * package-private constructors, so no real TMDB network calls are made.
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
        public ApiResponse trending() throws Exception {
            return trendingResponse;
        }

        @Override
        public ApiResponse searchMovie(String query) throws Exception {
            return searchResponse;
        }

        @Override
        public String loadPosterDataUri(String posterUrl) {
            posterCalls.add(posterUrl);
            return "data:image/jpeg;base64,stubbed";
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

    @Test
    void requestMoviesWithSearchKindDispatchesToSearchMovies() throws Exception {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        List<Movie> movies = controller.requestMovies("search", "Inception");

        assertEquals(1, movies.size());
        assertEquals("Inception", movies.get(0).title());
        assertEquals(2010, movies.get(0).year());
    }

    @Test
    void requestMoviesWithNonSearchKindDispatchesToFetchTrending() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        List<Movie> movies = controller.requestMovies("trending", "ignored");

        assertEquals(1, movies.size());
        assertEquals("Inception", movies.get(0).title());
    }

    @Test
    void requestMoviesWithEmptyQueryFallsBackToTrending() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        List<Movie> movies = controller.requestMovies("search", "");

        assertEquals(1, movies.size());
        assertEquals("Inception", movies.get(0).title());
    }

    @Test
    void requestMoviesWhenServiceThrowsRethrowsWithServiceMessage() {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(500, "boom", "server error");
        MovieController controller = controllerWith(serviceWith(client));

        // The UI thread joins the IO future; the fetch failure's own message
        // must surface (it becomes Krema's error reply to the page).
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> controller.requestMovies("trending", "x"));
        assertTrue(e.getMessage().contains("TMDB trending failed: HTTP 500"));
    }

    // pollPosters removed in the Vavr rewrite (posters emit directly).

    @Test
    void pollPostersDrainsQueuedPostersAndEmitsPosterEvents() throws Exception {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));

        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        controller.requestMovies("trending", "x"); // kicks off poster prefetch

        long deadline = System.currentTimeMillis() + 2000;
        while (emitter.emitted.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, emitter.emitted.size());
        KremaEvent e = emitter.emitted.get(0);
        assertEquals("poster", e.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload();
        assertEquals(27205, payload.get("id"));
        assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
    }

    @Test
    void pollPostersSkippedPostersDoNotStallPolling() throws Exception {
        StubClient client = new StubClient() {
            @Override
            public String loadPosterDataUri(String posterUrl) {
                return null; // every poster fetch fails
            }
        };
        client.trendingResponse = new ApiResponse(200, VALID_JSON, null);
        MovieController controller = controllerWith(serviceWith(client));
        controller.setEventEmitter(new TestEventEmitter());

        controller.requestMovies("trending", "x");

        // With direct emission, a failed poster simply emits nothing; ensure the
        // call returns promptly without hanging.
        Thread.sleep(200);
    }
}
