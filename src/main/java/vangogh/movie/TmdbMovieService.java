package vangogh.movie;

import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TmdbMovieService {

    private final TmdbClient client;

    // Poster data URIs by poster URL, so re-renders (search, filter toggles)
    // don't re-download the same images. Bounded so long sessions don't grow it.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public TmdbMovieService(String apiKey) {
        this.client = new TmdbClient(apiKey);
    }

    public List<Movie> fetchTrending() throws Exception {
        ApiResponse res = client.trending();
        if (!res.isSuccess()) {
            throw new RuntimeException("TMDB trending failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseMovies(res.body);
    }

    public List<Movie> searchMovies(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return fetchTrending();
        }
        ApiResponse res = client.searchMovie(query.trim());
        if (!res.isSuccess()) {
            throw new RuntimeException("TMDB search failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseMovies(res.body);
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

    public List<Movie> parseMovies(String json) {
        List<Movie> movies = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            for (int i = 0; i < results.size(); i++) {
                JsonObject m = results.get(i).getAsJsonObject();
                int id = m.has("id") && !m.get("id").isJsonNull() ? m.get("id").getAsInt() : 0;
                String title = optString(m, "title");
                if (title.isEmpty()) {
                    title = "Untitled";
                }
                String release = optString(m, "release_date");
                int year = parseYear(release);
                double rating = m.has("vote_average") && !m.get("vote_average").isJsonNull()
                        ? m.get("vote_average").getAsDouble() : 0.0;
                String genre = "";
                if (m.has("genre_ids") && !m.get("genre_ids").isJsonNull()) {
                    genre = genreLabel(m.getAsJsonArray("genre_ids"));
                }
                String overview = optString(m, "overview");
                String posterPath = optString(m, "poster_path");
                String posterUrl = TmdbClient.posterPath(posterPath);
                movies.add(new Movie(id, title, year, rating, genre, overview, posterUrl));
            }
        } catch (Exception e) {
            System.err.println("TMDB parse error: " + e.getMessage());
        }
        return movies;
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