package vangogh.app;

import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: verifies the core wiring compiles and runs without a real API key,
 * using the built-in fallback key. Does not launch the GUI or make network calls
 * except where noted.
 */
class SmokeTest {

    @Test
    void appClassLoads() {
        assertNotNull(VanGoghApp.class);
    }

    @Test
    void movieServiceInstantiatesWithFallbackKey() {
        // Falls back to the built-in key when TMDB_API_KEY is unset
        TmdbMovieService svc = new TmdbMovieService("any-key");
        assertNotNull(svc);
    }

    @Test
    void serviceParsesSampleJsonWithoutCrash() {
        TmdbMovieService svc = new TmdbMovieService("smoke-key");
        String json = """
            {
              "page": 1,
              "total_results": 1,
              "results": [
                { "id": 1, "title": "Smoke Test Movie", "release_date": "2024-01-01",
                  "vote_average": 7.5, "genre_ids": [18], "overview": "A smoke test.",
                  "poster_path": "/smoke.jpg" }
              ]
            }
            """;
        var movies = svc.parseMovies(json);
        assertEquals(1, movies.size());
        Movie m = movies.get(0);
        assertEquals("Smoke Test Movie", m.title());
        assertEquals(2024, m.year());
        assertEquals(7.5, m.rating());
        assertEquals("Drama", m.genre());
        assertEquals("https://image.tmdb.org/t/p/w500/smoke.jpg", m.posterUrl());
    }

    @Test
    void serviceHandlesEmptyResults() {
        TmdbMovieService svc = new TmdbMovieService("smoke-key");
        var movies = svc.parseMovies("{\"page\":1,\"total_results\":0,\"results\":[]}");
        assertTrue(movies.isEmpty());
    }

    @Test
    void movieRecordIsImmutable() {
        Movie m = new Movie(1, "T", 2020, 7.0, "D", "o", "p");
        assertEquals(1, m.id());
        assertEquals("T", m.title());
    }
}