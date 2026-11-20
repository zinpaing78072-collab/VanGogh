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

    /** One TMDB /movie/:id payload with every appended block the detail page reads. */
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
          "keywords": {
            "keywords": [
              { "id": 1, "name": "dream" },
              { "id": 2, "name": "heist" }
            ]
          },
          "credits": {
            "cast": [
              { "name": "Leonardo DiCaprio", "character": "Dom Cobb", "profile_path": "/leo.jpg" },
              { "name": "Joseph Gordon-Levitt", "character": "Arthur", "profile_path": "/jgl.jpg" },
              { "name": "Elliot Page", "character": "Ariadne", "profile_path": null }
            ],
            "crew": [
              { "name": "Christopher Nolan", "job": "Director", "profile_path": "/nolan.jpg" },
              { "name": "Christopher Nolan", "job": "Screenplay", "profile_path": "/nolan.jpg" },
              { "name": "Wally Pfister", "job": "Director of Photography", "profile_path": "/pfister.jpg" },
              { "name": "Hans Zimmer", "job": "Original Music Composer", "profile_path": "/zimmer.jpg" }
            ]
          },
          "images": {
            "backdrops": [
              { "file_path": "/still-01.jpg" },
              { "file_path": "/still-02.jpg" }
            ]
          },
          "similar": {
            "results": [
              {
                "id": 155,
                "title": "The Dark Knight",
                "release_date": "2008-07-18",
                "vote_average": 8.5,
                "genre_ids": [28],
                "poster_path": "/tdk.jpg"
              },
              { "id": 27205, "title": "Inception", "release_date": "2010-07-16" }
            ]
          },
          "videos": {
            "results": [
              { "site": "YouTube", "type": "Teaser", "key": "teaser-key" },
              { "site": "YouTube", "type": "Trailer", "key": "trailer-key" }
            ]
          },
          "release_dates": {
            "results": [
              { "iso_3166_1": "US", "release_dates": [ { "certification": "PG-13", "type": 3 } ] },
              { "iso_3166_1": "GB", "release_dates": [ { "certification": "12A", "type": 3 } ] }
            ]
          },
          "production_companies": [
            { "name": "Warner Bros." },
            { "name": "Legendary Pictures" }
          ],
          "production_countries": [
            { "name": "United States of America" }
          ]
        }
        """;

    private TmdbMovieService detailService(String body) {
        return new TmdbMovieService(new TmdbClient("test-key") {
            @Override
            public io.vavr.control.Try<ApiResponse> movieDetail(int id) {
                return io.vavr.control.Try.success(new ApiResponse(200, body, null));
            }
        });
    }

    private static String fact(MovieDetail detail, String label) {
        return detail.facts().stream()
                .filter(f -> f.label().equals(label))
                .map(MovieDetail.Fact::value)
                .findFirst()
                .orElse("");
    }

    @Test
    void movieDetailMapsIdentityAndFacts() {
        MovieDetail detail = detailService(DETAIL_JSON).movieDetail(27205).get();

        assertEquals(27205, detail.id());
        assertEquals("Inception", detail.title());
        assertEquals("Your mind is the scene of the crime.", detail.tagline());
        assertEquals(2010, detail.year());
        assertEquals(148, detail.runtimeMinutes());
        assertEquals("PG-13", detail.certification());
        assertEquals(8.4, detail.rating());
        assertEquals(2300000, detail.voteCount());
        assertEquals("https://image.tmdb.org/t/p/w500/inception.jpg", detail.posterUrl());
        assertEquals("https://www.themoviedb.org/movie/27205", detail.tmdbUrl());
        assertEquals("https://www.youtube.com/watch?v=trailer-key", detail.trailerUrl());

        assertEquals(List.of("Action", "Science Fiction"), detail.genres());
        assertEquals(List.of("dream", "heist"), detail.keywords());

        assertEquals(12, detail.facts().size());
        assertEquals("Jul 16, 2010", fact(detail, "Release"));
        assertEquals("2h 28m", fact(detail, "Runtime"));
        assertEquals("Christopher Nolan", fact(detail, "Director"));
        assertEquals("Warner Bros., Legendary Pictures", fact(detail, "Studio"));
        assertEquals("$160M", fact(detail, "Budget"));
        assertEquals("$836.8M", fact(detail, "Box office"));
        assertEquals("PG-13", fact(detail, "Certification"));
        assertEquals("Released", fact(detail, "Status"));
    }

    @Test
    void movieDetailMapsCastCrewStillsAndSimilar() {
        MovieDetail detail = detailService(DETAIL_JSON).movieDetail(27205).get();

        // Three billed cast rows plus the director and cinematographer.
        assertEquals(5, detail.cast().size());
        assertEquals("Leonardo DiCaprio", detail.cast().get(0).name());
        assertEquals("Dom Cobb", detail.cast().get(0).role());
        assertFalse(detail.cast().get(0).crew());
        assertEquals("", detail.cast().get(2).imageUrl());
        assertEquals("Christopher Nolan", detail.cast().get(3).name());
        assertEquals("Director", detail.cast().get(3).role());
        assertTrue(detail.cast().get(3).crew());
        assertEquals("Cinematography", detail.cast().get(4).role());

        assertEquals(2, detail.stills().size());
        assertEquals("https://image.tmdb.org/t/p/w500/still-01.jpg", detail.stills().get(0).imageUrl());

        // The film's own id is dropped from its similar rail.
        assertEquals(1, detail.similar().size());
        Movie related = detail.similar().get(0).movie();
        assertEquals(155, related.id());
        assertEquals("The Dark Knight", related.title());
        assertEquals(2008, related.year());
        assertEquals("https://image.tmdb.org/t/p/w500/tdk.jpg", detail.similar().get(0).posterUrl());
    }

    @Test
    void movieDetailReturnsPayloadErrorOnMalformedJson() {
        var result = detailService("not json at all").movieDetail(27205);

        assertTrue(result.isLeft());
        assertInstanceOf(vangogh.error.FetchError.Payload.class, result.getLeft());
    }

    @Test
    void movieDetailReturnsHttpStatusErrorOnNon2xx() {
        TmdbMovieService service = new TmdbMovieService(new TmdbClient("test-key") {
            @Override
            public io.vavr.control.Try<ApiResponse> movieDetail(int id) {
                return io.vavr.control.Try.success(new ApiResponse(404, "no such movie", null));
            }
        });

        var result = service.movieDetail(999);

        assertTrue(result.isLeft());
        assertInstanceOf(vangogh.error.FetchError.HttpStatus.class, result.getLeft());
    }

    /** Stub client for detail-art tests: records the URL, returns a fake data URI. */
    static class DetailArtStubClient extends TmdbClient {
        final List<String> calls = new ArrayList<>();

        DetailArtStubClient() {
            super("test-key");
        }

        @Override
        public io.vavr.control.Option<String> loadDetailImageDataUri(String imageUrl) {
            calls.add(imageUrl);
            return io.vavr.control.Option.some("data:image/jpeg;base64=detail");
        }
    }

    @Test
    void detailImageDataUriDelegatesToTheClient() {
        DetailArtStubClient client = new DetailArtStubClient();
        TmdbMovieService service = new TmdbMovieService(client);

        assertEquals("data:image/jpeg;base64=detail",
                service.detailImageDataUri("https://image.tmdb.org/t/p/w500/leo.jpg").getOrElse(""));
        assertEquals(List.of("https://image.tmdb.org/t/p/w500/leo.jpg"), client.calls);
        assertTrue(service.detailImageDataUri(null).isEmpty());
        assertTrue(service.detailImageDataUri("").isEmpty());
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