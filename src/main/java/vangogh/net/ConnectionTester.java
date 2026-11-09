package vangogh.net;

import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import java.util.List;

/**
 * Console program that tests the TMDB API connection used by Van Gogh.
 *
 * Usage:
 *   TMDB_API_KEY=your_key java vangogh.net.ConnectionTester          # trending + search "inception"
 *   TMDB_API_KEY=your_key java vangogh.net.ConnectionTester godfather # custom search query
 *
 * Exit code: 0 if all checks pass, 1 otherwise.
 *
 * Run with:
 *   JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
 *     mvn -q compile exec:exec -Dexec.mainClass=vangogh.net.ConnectionTester
 */
public class ConnectionTester {

    private static final String FALLBACK_API_KEY =
            "d0269af3b7b5a40e157c5e8ae28ff0a3"; // same fallback as MovieController

    public static void main(String[] args) {
        String apiKey = resolveApiKey();
        String query = args.length > 0 ? args[0] : "inception";

        int failures = 0;
        failures += check("TMDB trending", () -> trendingCheck(apiKey));
        failures += check("TMDB search \"" + query + "\"", () -> searchCheck(apiKey, query));
        failures += check("TMDB poster load", () -> posterCheck(apiKey));

        System.out.println();
        if (failures == 0) {
            System.out.println("All connection tests passed.");
            System.exit(0);
        } else {
            System.out.println(failures + " connection test(s) failed.");
            System.exit(1);
        }
    }

    private static String resolveApiKey() {
        String key = System.getenv("TMDB_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("TMDB_API_KEY not set — using built-in fallback key.");
            key = FALLBACK_API_KEY;
        }
        return key;
    }

    private static int check(String name, ThrowingBoolean test) {
        System.out.print("[" + name + "] ... ");
        try {
            boolean ok = test.test();
            System.out.println(ok ? "PASS" : "FAIL");
            return ok ? 0 : 1;
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
            return 1;
        }
    }

    // ----- individual checks -----

    private static boolean trendingCheck(String apiKey) throws Exception {
        TmdbMovieService service = new TmdbMovieService(apiKey);
        List<Movie> movies = service.fetchTrending();
        if (movies.isEmpty()) {
            System.out.print("no movies returned");
            return false;
        }
        Movie m = movies.get(0);
        System.out.print(m.title() + " (" + m.year() + ") — " + movies.size() + " results");
        return true;
    }

    private static boolean searchCheck(String apiKey, String query) throws Exception {
        TmdbMovieService service = new TmdbMovieService(apiKey);
        List<Movie> movies = service.searchMovies(query);
        if (movies.isEmpty()) {
            System.out.print("no results for \"" + query + "\"");
            return false;
        }
        Movie m = movies.get(0);
        System.out.print(m.title() + " (" + m.year() + ") — " + movies.size() + " results");
        return true;
    }

    private static boolean posterCheck(String apiKey) throws Exception {
        TmdbMovieService service = new TmdbMovieService(apiKey);
        List<Movie> movies = service.fetchTrending();
        if (movies.isEmpty()) {
            System.out.print("no trending movies to test poster");
            return false;
        }
        Movie m = movies.get(0);
        String posterUrl = m.posterUrl();
        if (posterUrl == null || posterUrl.isEmpty()) {
            System.out.print("first movie has no poster path");
            return false;
        }
        String dataUri = service.posterDataUri(posterUrl);
        if (dataUri == null || dataUri.isBlank()) {
            System.out.print("poster data URI is empty for " + m.title());
            return false;
        }
        System.out.print(m.title() + " poster loaded (" + dataUri.length() + " chars)");
        return true;
    }

    @FunctionalInterface
    private interface ThrowingBoolean {
        boolean test() throws Exception;
    }
}
