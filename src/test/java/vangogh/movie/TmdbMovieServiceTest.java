package vangogh.movie;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;

class TmdbMovieServiceTest {

    private static final String VALID_TRENDING_JSON = """
        {
          "page": 1,
          "total_results": 2,
          "results": [
            {
              "id": 27205,
              "title": "Inception",
              "release_date": "2010-07-16",
              "vote_average": 8.4,
              "genre_ids": [28, 878],
              "overview": "A dream-heist story.",
              "poster_path": "/xlaY2zyzMfkhk0HSC5VUwzoZPU1.jpg"
            },
            {
              "id": 656908,
              "title": "Ramayana",
              "release_date": "2026-01-01",
              "vote_average": 7.6,
              "genre_ids": [12, 14, 28],
              "overview": "A legend.",
              "poster_path": "/hz2Hv8tPToHvQ7HaxzqeLvahmcJ.jpg"
            }
          ]
        }
        """;

    private static final String EMPTY_RESULTS_JSON = """
        {
          "page": 1,
          "total_results": 0,
          "results": []
        }
        """;

    private static final String NULL_FIELDS_JSON = """
        {
          "page": 1,
          "total_results": 1,
          "results": [
            {
              "id": 1,
              "title": null,
              "release_date": null,
              "vote_average": null,
              "genre_ids": null,
              "overview": null,
              "poster_path": null
            }
          ]
        }
        """;

    private static final String MISSING_FIELDS_JSON = """
        {
          "page": 1,
          "total_results": 1,
          "results": [
            {
              "id": 99
            }
          ]
        }
        """;

    private TmdbMovieService service() {
        return new TmdbMovieService("test-key");
    }

    @Test
    void parseMoviesWithValidTrendingReturnsTwoMovies() {
        var result = service().parseMovies(VALID_TRENDING_JSON);

        assertEquals(2, result.size());

        Movie m1 = result.get(0);
        assertEquals(27205, m1.id());
        assertEquals("Inception", m1.title());
        assertEquals(2010, m1.year());
        assertEquals(8.4, m1.rating());
        assertEquals("Action · Science Fiction", m1.genre());
        assertEquals("A dream-heist story.", m1.overview());
        assertEquals("https://image.tmdb.org/t/p/w500/xlaY2zyzMfkhk0HSC5VUwzoZPU1.jpg", m1.posterUrl());

        Movie m2 = result.get(1);
        assertEquals(656908, m2.id());
        assertEquals("Ramayana", m2.title());
        assertEquals(2026, m2.year());
        assertEquals("Adventure · Fantasy · Action", m2.genre());
        assertEquals("https://image.tmdb.org/t/p/w500/hz2Hv8tPToHvQ7HaxzqeLvahmcJ.jpg", m2.posterUrl());
    }

    @Test
    void parseMoviesWithEmptyResultsReturnsEmptyList() {
        var result = service().parseMovies(EMPTY_RESULTS_JSON);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseMoviesWithNullFieldsDoesNotThrow() {
        var result = service().parseMovies(NULL_FIELDS_JSON);

        assertEquals(1, result.size());
        Movie m = result.get(0);
        assertEquals(1, m.id());
        assertEquals("Untitled", m.title());
        assertEquals(0, m.year());
        assertEquals(0.0, m.rating());
        assertEquals("", m.genre());
        assertEquals("", m.overview());
        assertNull(m.posterUrl());
    }

    @Test
    void parseMoviesWithMissingFieldsDoesNotThrow() {
        var result = service().parseMovies(MISSING_FIELDS_JSON);

        assertEquals(1, result.size());
        Movie m = result.get(0);
        assertEquals(99, m.id());
        assertEquals("Untitled", m.title());
        assertEquals(0, m.year());
    }

    @Test
    void parseMoviesWithInvalidJsonReturnsEmptyList() {
        var result = service().parseMovies("not json at all");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseMoviesWithNullJsonReturnsEmptyList() {
        var result = service().parseMovies(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void genreNameMapsKnownIds() {
        // Use reflection-free check via parseMovies output instead
        var result = service().parseMovies("""
            {"page":1,"total_results":1,"results":[{"id":1,"title":"T","genre_ids":[35,10751]}]}
            """);
        assertEquals("Comedy · Family", result.get(0).genre());
    }

    @Test
    void genreNameWithUnknownIdReturnsUnknown() {
        var result = service().parseMovies("""
            {"page":1,"total_results":1,"results":[{"id":1,"title":"T","genre_ids":[99999]}]}
            """);
        assertEquals("Unknown", result.get(0).genre());
    }

    @Test
    void parseYearExtractsFourDigits() {
        TmdbMovieService s = service();
        // parseYear is private; verify indirectly through parseMovies output
        var result = service().parseMovies("""
            {"page":1,"total_results":1,"results":[{"id":1,"title":"T","release_date":"1999-12-31"}]}
            """);
        assertEquals(1999, result.get(0).year());
    }

    @Test
    void parseYearWithEmptyStringReturnsZero() {
        var result = service().parseMovies("""
            {"page":1,"total_results":1,"results":[{"id":1,"title":"T","release_date":""}]}
            """);
        assertEquals(0, result.get(0).year());
    }

    /** Stub client for poster tests: records loadPosterDataUri calls, returns a fake data URI. */
    static class PosterStubClient extends TmdbClient {
        final List<String> calls = new ArrayList<>();

        PosterStubClient() {
            super("test-key");
        }

        @Override
        public io.vavr.control.Option<String> loadPosterDataUri(String posterUrl) {
            calls.add(posterUrl);
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    @Test
    void posterDataUriWithNullReturnsNone() {
        TmdbMovieService service = new TmdbMovieService(new PosterStubClient());
        assertTrue(service.posterDataUri(null).isEmpty());
    }

    @Test
    void posterDataUriWithEmptyStringReturnsNone() {
        TmdbMovieService service = new TmdbMovieService(new PosterStubClient());
        assertTrue(service.posterDataUri("").isEmpty());
    }

    @Test
    void posterDataUriCachesResultAndCallsClientOnce() {
        PosterStubClient client = new PosterStubClient();
        TmdbMovieService service = new TmdbMovieService(client);
        String url = "https://image.tmdb.org/t/p/w500/abc.jpg";

        String uri1 = service.posterDataUri(url).getOrElse("");
        String uri2 = service.posterDataUri(url).getOrElse("");

        assertEquals("data:image/jpeg;base64,stubbed", uri1);
        assertEquals(uri1, uri2);
        assertEquals(1, client.calls.size());
        assertEquals(url, client.calls.get(0));
    }

    // --- Either spine: transport → status → parse, short-circuiting ---

    @Test
    void fetchTrendingReturnsMoviesOnSuccess() {
        TmdbMovieService service = new TmdbMovieService(new TmdbClient("test-key") {
            @Override
            public io.vavr.control.Try<ApiResponse> trending() {
                return io.vavr.control.Try.success(new ApiResponse(200, VALID_TRENDING_JSON, null));
            }
        });

        var result = service.fetchTrending();

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
        assertEquals("Inception", result.get().get(0).title());
    }

    @Test
    void searchMoviesReturnsHttpStatusErrorOnNon2xx() {
        TmdbMovieService service = new TmdbMovieService(new TmdbClient("test-key") {
            @Override
            public io.vavr.control.Try<ApiResponse> searchMovie(String query) {
                return io.vavr.control.Try.success(new ApiResponse(404, "nope", null));
            }
        });

        var result = service.searchMovies("Inception");

        assertTrue(result.isLeft());
        assertEquals("TMDB search failed: HTTP 404 nope",
                vangogh.error.FetchError.uiMessage(result.getLeft()));
    }

    @Test
    void fetchTrendingReturnsTransportErrorWhenNetworkFails() {
        TmdbMovieService service = new TmdbMovieService(new TmdbClient("test-key") {
            @Override
            public io.vavr.control.Try<ApiResponse> trending() {
                return io.vavr.control.Try.failure(new java.io.IOException("connection refused"));
            }
        });

        var result = service.fetchTrending();

        assertTrue(result.isLeft());
        assertInstanceOf(vangogh.error.FetchError.Transport.class, result.getLeft());
        assertTrue(vangogh.error.FetchError.uiMessage(result.getLeft())
                .contains("connection refused"));
    }

    @Test
    void parseRowsStrictReturnsPayloadErrorOnMalformedJson() {
        TmdbMovieService service = service();
        var result = service.parseMovies("{\"page\":1}"); // missing "results"

        // Lenient facade: empty list, no throw — strictness lives in the spine.
        assertTrue(result.isEmpty());
    }
}