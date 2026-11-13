package vangogh.game;

import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public class TgdbGameService 
{
	private final TgdbClient client;
	
	private final Map<String, String> posterCache = new ConcurrentHashMap<>();
	
	public TgdbGameService(TgdbClient client) {
        this.client = client;
    }
	
	public TgdbGameService(String apiKey) {this(new TgdbClient(apiKey));}
	
	public List<Game> fetchTrending() throws Exception {
        ApiResponse res = client.trending();
        if (!res.isSuccess()) {
            throw new RuntimeException("TheGamesDB trending failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseGames(res.body);
    }
	
	public List<Game> searchGames(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return fetchTrending();
        }
        ApiResponse res = client.searchGame(query.trim());
        if (!res.isSuccess()) {
            throw new RuntimeException("TheGamesDB search failed: HTTP " + res.statusCode + " " + truncate(res.body, 300));
        }
        return parseGames(res.body);
    }
	
	public List<Game> parseGames(String json) {

	    List<Game> games = new ArrayList<>();

	    try {
	        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

	        JsonObject data = root.getAsJsonObject("data");
	        JsonArray results = data.getAsJsonArray("games");
	        JsonObject boxartData = null;
	        if (root.has("include") && root.getAsJsonObject("include").has("boxart")) {
	            JsonObject boxart = root.getAsJsonObject("include").getAsJsonObject("boxart");
	            if (boxart.has("data") && boxart.get("data").isJsonObject()) {
	                boxartData = boxart.getAsJsonObject("data");
	            }
	        }

	        for (int i = 0; i < results.size(); i++) {

	            JsonObject g = results.get(i).getAsJsonObject();

	            int id = g.has("id") && !g.get("id").isJsonNull()
	                    ? g.get("id").getAsInt() : 0;

	            String title = optString(g, "game_title");

	            if (title.isEmpty()) {
	                title = "Untitled";
	            }

	            String release = optString(g, "release_date");

	            double rating = 0.0;

	            // API rating is currently a string, e.g. "M - Mature 17+"
	            // Don't parse it as a numeric rating.

	            String genre = "";

	            if (g.has("genres") && !g.get("genres").isJsonNull()) {
	                genre = genreLabel(g.getAsJsonArray("genres"));
	            }

	            String overview = optString(g, "overview");

	            String posterUrl = findFrontBoxart(boxartData, id);

	            games.add(new Game(
	                    id,
	                    title,
	                    release,
	                    rating,
	                    genre,
	                    overview,
	                    posterUrl
	            ));
	        }

	    } catch (Exception e) {
	        System.err.println("Game parse error: " + e.getMessage());
	    }

	    return games;
	}

	private static String findFrontBoxart(JsonObject boxartData, int gameId) {
	    if (boxartData == null || !boxartData.has(String.valueOf(gameId))
	            || !boxartData.get(String.valueOf(gameId)).isJsonArray()) {
	        return "";
	    }
	    JsonArray images = boxartData.getAsJsonArray(String.valueOf(gameId));
	    for (int i = 0; i < images.size(); i++) {
	        JsonObject image = images.get(i).getAsJsonObject();
	        if ("boxart".equals(optString(image, "type"))
	                && "front".equals(optString(image, "side"))) {
	            String filename = optString(image, "filename");
	            if (!filename.isEmpty()) {
	                return TgdbClient.posterPath(filename);
	            }
	        }
	    }
	    return "";
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

	    if (!g.has(key) || g.get(key).isJsonNull()) {
	        return "";
	    }

	    return g.get(key).getAsString();
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
