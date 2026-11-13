package vangogh.game;

import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;

import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TgdbGameService {

    private final TgdbClient client;

    // Poster data URIs by poster URL, so re-renders (search, filter toggles)
    // don't re-download the same images. Bounded so long sessions don't grow it.
    // Stays a concurrent map because vavr has no concurrent map.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public TgdbGameService(TgdbClient client) {
        this.client = client;
    }

    public TgdbGameService(String apiKey) {
        this(new TgdbClient(apiKey));
    }

    public List<Game> fetchTrending() throws Exception {
        ApiResponse res = client.trending();
        if (!res.isSuccess()) {
            throw new RuntimeException("TheGamesDB trending failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseGames(res.body).toJavaList();
    }

    public List<Game> searchGames(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return fetchTrending();
        }
        ApiResponse res = client.searchGame(query.trim());
        if (!res.isSuccess()) {
            throw new RuntimeException("TheGamesDB search failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseGames(res.body).toJavaList();
    }

    /**
     * Parses the games payload into a persistent vavr list. Any parse failure
     * (malformed JSON, missing nodes) is represented as an empty list rather
     * than thrown, matching the previous swallow-and-log behavior.
     */
    public io.vavr.collection.List<Game> parseGames(String json) {
        return Try.of(() -> JsonParser.parseString(json).getAsJsonObject())
                .map(root -> {
                    JsonObject data = root.getAsJsonObject("data");
                    JsonArray results = data == null ? null : data.getAsJsonArray("games");
                    JsonObject boxartData = extractBoxartData(root);
                    if (results == null) {
                        return io.vavr.collection.List.<Game>empty();
                    }
                    return io.vavr.collection.List.range(0, results.size())
                            .map(i -> results.get(i).getAsJsonObject())
                            .map(g -> toGame(g, boxartData));
                })
                .getOrElse(io.vavr.collection.List.empty());
    }

    private Game toGame(JsonObject g, JsonObject boxartData) {
        int id = Option.of(g.get("id"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        String title = Option.of(optString(g, "game_title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String release = optString(g, "release_date");
        double rating = 0.0;
        // API rating is currently a string, e.g. "M - Mature 17+"; don't parse it numerically.
        String genre = Option.of(g.get("genres"))
                .filter(j -> !j.isJsonNull())
                .map(j -> genreLabel(j.getAsJsonArray()))
                .getOrElse("");
        String overview = optString(g, "overview");
        String posterUrl = findFrontBoxart(boxartData, id);
        return new Game(id, title, release, rating, genre, overview, posterUrl);
    }

    private static JsonObject extractBoxartData(JsonObject root) {
        if (root == null || !root.has("include") || root.get("include").isJsonNull()) {
            return null;
        }
        JsonObject include = root.getAsJsonObject("include");
        if (!include.has("boxart") || include.get("boxart").isJsonNull()) {
            return null;
        }
        JsonObject boxart = include.getAsJsonObject("boxart");
        if (!boxart.has("data") || !boxart.get("data").isJsonObject()) {
            return null;
        }
        return boxart.getAsJsonObject("data");
    }

    private static String findFrontBoxart(JsonObject boxartData, int gameId) {
        if (boxartData == null || !boxartData.has(String.valueOf(gameId))
                || !boxartData.get(String.valueOf(gameId)).isJsonArray()) {
            return "";
        }
        JsonArray images = boxartData.getAsJsonArray(String.valueOf(gameId));
        return io.vavr.collection.List.range(0, images.size())
                .map(i -> images.get(i).getAsJsonObject())
                .filter(image -> "boxart".equals(optString(image, "type"))
                        && "front".equals(optString(image, "side")))
                .map(image -> optString(image, "filename"))
                .filter(filename -> !filename.isEmpty())
                .map(TgdbClient::posterPath)
                .headOption()
                .getOrElse("");
    }

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

    private static String optString(JsonObject g, String key) {
        return Option.of(g.get(key))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsString())
                .getOrElse("");
    }

    private static String genreLabel(JsonArray ids) {
        if (ids == null || ids.size() == 0) {
            return "";
        }
        return io.vavr.collection.List.range(0, ids.size())
                .map(i -> genreName(ids.get(i).getAsInt()))
                .mkString(" · ");
    }

    private static String genreName(int id) {
        return switch (id) {
            case 1 -> "Action";
            case 2 -> "Adventure";
            case 3 -> "Construction and Management Simulation";
            case 4 -> "Role-Playing";
            case 5 -> "Puzzle";
            case 6 -> "Strategy";
            case 7 -> "Racing";
            case 8 -> "Shooter";
            case 9 -> "Life Simulation";
            case 10 -> "Fighting";
            case 11 -> "Sports";
            case 12 -> "Sandbox";
            case 13 -> "Flight Simulator";
            case 14 -> "MMO";
            case 15 -> "Platform";
            case 16 -> "Stealth";
            case 17 -> "Music";
            case 18 -> "Horror";
            case 19 -> "Vehicle Simulation";
            case 20 -> "Board";
            case 21 -> "Education";
            case 22 -> "Family";
            case 23 -> "Party";
            case 24 -> "Productivity";
            case 25 -> "Quiz";
            case 26 -> "Utility";
            case 27 -> "Virtual Console";
            case 28 -> "Unofficial";
            case 29 -> "GBA Video / PSP Video";
            case 30 -> "Demo";
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
