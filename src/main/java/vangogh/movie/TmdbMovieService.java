package vangogh.movie;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetch pipeline as an {@link Either} spine: transport failures become
 * {@link FetchError.Transport}, a non-2xx status becomes
 * {@link FetchError.HttpStatus} (an expected outcome, not an exception), and an
 * unreadable payload becomes {@link FetchError.Payload}. The chain short-circuits
 * at the first {@code Left}; controllers unwrap at the edge only.
 */
public class TmdbMovieService {

    private final TmdbClient client;

    // Poster data URIs by poster URL, so re-renders (search, filter toggles)
    // don't re-download the same images. Bounded so long sessions don't grow it.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public TmdbMovieService(String apiKey) {
        this(new TmdbClient(apiKey));
    }

    /**
     * Constructs the service with an injected {@link TmdbClient}. Public so
     * tests (and other packages) can supply a stubbed client instead of hitting
     * the real TMDB network.
     */
    public TmdbMovieService(TmdbClient client) {
        this.client = client;
    }

    public Either<FetchError, List<Movie>> fetchTrending() {
        return client.trending()
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("TMDB trending", e))
                .flatMap(res -> requireSuccess("TMDB trending", res))
                .flatMap(this::parseRows);
    }

    public Either<FetchError, List<Movie>> searchMovies(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.searchMovie(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("TMDB search", e))
                        .flatMap(res -> requireSuccess("TMDB search", res))
                        .flatMap(this::parseRows);
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    /** Structure-level strictness: an unreadable payload is an error, not an empty list. */
    private Either<FetchError, List<Movie>> parseRows(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray rows = root.getAsJsonArray("results");
                    return io.vavr.collection.List.range(0, rows.size())
                            .map(i -> toMovie(rows.get(i).getAsJsonObject()))
                            .toJavaList();
                })
                .toEither()
                .mapLeft(e -> new FetchError.Payload("TMDB", "payload shape", e));
    }

    /**
     * Lenient facade kept for callers that want the old swallow-and-return-empty
     * behavior (tests, tooling): the strict spine, unwrapped with an empty default.
     */
    public List<Movie> parseMovies(String json) {
        return parseRows(json).getOrElse(List.of());
    }

    private Movie toMovie(JsonObject m) {
        int id = Option.of(m.get("id"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        String title = Option.of(optString(m, "title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String release = optString(m, "release_date");
        int year = parseYear(release);
        double rating = Option.of(m.get("vote_average"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsDouble())
                .getOrElse(0.0);
        String genre = Option.of(m.get("genre_ids"))
                .filter(j -> !j.isJsonNull())
                .map(j -> genreLabel(j.getAsJsonArray()))
                .getOrElse("");
        String overview = optString(m, "overview");
        String posterPath = optString(m, "poster_path");
        String posterUrl = TmdbClient.posterPath(posterPath);
        return new Movie(id, title, year, rating, genre, overview, posterUrl);
    }

    private static String optString(JsonObject m, String key) {
        if (!m.has(key) || m.get(key).isJsonNull()) {
            return "";
        }
        return m.get(key).getAsString();
    }

    private static int parseYear(String release) {
        if (release == null || release.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(release.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String genreLabel(JsonArray ids) {
        if (ids == null || ids.size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(genreName(ids.get(i).getAsInt()));
        }
        return sb.toString();
    }

    private static String genreName(int id) {
        return switch (id) {
            case 28 -> "Action";
            case 12 -> "Adventure";
            case 16 -> "Animation";
            case 35 -> "Comedy";
            case 80 -> "Crime";
            case 99 -> "Documentary";
            case 18 -> "Drama";
            case 10751 -> "Family";
            case 14 -> "Fantasy";
            case 36 -> "History";
            case 27 -> "Horror";
            case 10402 -> "Music";
            case 9648 -> "Mystery";
            case 10749 -> "Romance";
            case 878 -> "Science Fiction";
            case 10770 -> "TV Movie";
            case 53 -> "Thriller";
            case 10752 -> "War";
            case 37 -> "Western";
            default -> "Unknown";
        };
    }

    /**
     * Cache-aside as an {@link Option} chain: absent/empty URL is {@code None},
     * a cache hit short-circuits via {@code orElse}, a miss is fetched and put
     * back ({@code peek}) — bounded so long sessions don't grow the cache.
     */
    public Option<String> posterDataUri(String posterUrl) {
        return Option.of(posterUrl)
                .filter(u -> !u.isEmpty())
                .flatMap(u -> Option.of(posterCache.get(u))
                        .orElse(() -> client.loadPosterDataUri(u)
                                .peek(uri -> cachePut(u, uri))));
    }

    private void cachePut(String url, String uri) {
        if (posterCache.size() >= 512) {
            posterCache.clear();
        }
        posterCache.put(url, uri);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
