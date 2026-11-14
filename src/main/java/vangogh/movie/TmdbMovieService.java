package vangogh.movie;

import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.control.Option;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public List<Movie> fetchTrending() throws Exception {
        ApiResponse res = client.trending();
        if (!res.isSuccess()) {
            throw new RuntimeException("TMDB trending failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseMovies(res.body).toJavaList();
    }

    public List<Movie> searchMovies(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return fetchTrending();
        }
        ApiResponse res = client.searchMovie(query.trim());
        if (!res.isSuccess()) {
            throw new RuntimeException("TMDB search failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseMovies(res.body).toJavaList();
    }

    /**
     * Loads one poster as a data: URI (never the direct URL — the WebView must
     * not talk to image.tmdb.org itself, see TmdbClient). Cached so switching
     * between trending and past searches is instant.
     */
    public String posterDataUri(String posterUrl) {
        if (posterUrl == null || posterUrl.isEmpty()) {
            return null;
        }
        String cached = posterCache.get(posterUrl);
        if (cached != null) {
            return cached;
        }
        String uri = client.loadPosterDataUri(posterUrl);
        if (uri != null) {
            if (posterCache.size() >= 512) {
                posterCache.clear();
            }
            posterCache.put(posterUrl, uri);
        }
        return uri;
    }

    public io.vavr.collection.List<Movie> parseMovies(String json) {
        Option<JsonObject> root = Option.of(json)
                .flatMap(s -> io.vavr.control.Try.of(() -> JsonParser.parseString(s).getAsJsonObject()).toOption());
        Option<JsonArray> results = root.map(r -> r.getAsJsonArray("results"));

        return results
                .map(arr -> io.vavr.collection.List.range(0, arr.size())
                        .map(i -> arr.get(i).getAsJsonObject())
                        .map(this::toMovie))
                .getOrElse(io.vavr.collection.List.empty());
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}