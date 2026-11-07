package vangogh.movie;

import com.entertainment.tester.ApiResponse;
import com.entertainment.tester.TmdbClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class TmdbMovieService {

    private final TmdbClient client;

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

    private List<Movie> parseMovies(String json) {
        List<Movie> movies = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            for (int i = 0; i < results.size(); i++) {
                JsonObject m = results.get(i).getAsJsonObject();
                int id = m.has("id") ? m.get("id").getAsInt() : 0;
                String title = m.has("title") ? m.get("title").getAsString() : "Untitled";
                String release = m.has("release_date") ? m.get("release_date").getAsString() : "";
                int year = parseYear(release);
                double rating = m.has("vote_average") ? m.get("vote_average").getAsDouble() : 0.0;
                String genre = m.has("genre_ids") ? genreLabel(m.getAsJsonArray("genre_ids")) : "";
                String overview = m.has("overview") ? m.get("overview").getAsString() : "";
                String posterPath = m.has("poster_path") ? m.get("poster_path").getAsString() : null;
                String posterUrl = TmdbClient.posterPath(posterPath);
                movies.add(new Movie(id, title, year, rating, genre, overview, posterUrl));
            }
        } catch (Exception e) {
            System.err.println("TMDB parse error: " + e.getMessage());
        }
        return movies;
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