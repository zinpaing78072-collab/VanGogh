package vangogh.game;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import io.vavr.control.Validation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fetch pipeline as an {@link Either} spine with an accumulating structure
 * node: the games array and the boxart map are two <em>independent</em>
 * extractions from the payload, combined with {@code Validation.combine(...).ap(...)}
 * — if both are unreadable, BOTH errors come back in one
 * {@code Seq<FetchError>}. Row building stays lenient (missing fields get
 * defaults, as before); only structure-level failures are errors.
 */
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

    public Either<FetchError, java.util.List<Game>> fetchTrending() {
        return client.trending()
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("TheGamesDB trending", e))
                .flatMap(res -> requireSuccess("TheGamesDB trending", res))
                .flatMap(this::parseRows);
    }

    public Either<FetchError, java.util.List<Game>> searchGames(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.searchGame(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("TheGamesDB search", e))
                        .flatMap(res -> requireSuccess("TheGamesDB search", res))
                        .flatMap(this::parseRows);
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    /** Structure-level strictness: an unreadable payload is an error, not an empty list. */
    private Either<FetchError, java.util.List<Game>> parseRows(String body) {
        return Try.of(() -> JsonParser.parseString(body).getAsJsonObject())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload("TheGamesDB", "payload shape", e))
                .flatMap(this::extractStructure)
                .map(games -> games.toJavaList());
    }

    /**
     * The accumulating node: games array and boxart map extracted independently,
     * combined with {@code Validation.combine(...).ap(...)} so all structure
     * failures collect into one {@code Seq<FetchError>} instead of
     * short-circuiting at the first one.
     */
    private Either<FetchError, List<Game>> extractStructure(JsonObject root) {
        Validation<Seq<FetchError>, List<Game>> rows =
                Validation.combine(validGames(root), validBoxart(root))
                        .ap(this::zipRows);
        return rows.toEither()
                .mapLeft(errors -> (FetchError) new FetchError.Payload("TheGamesDB",
                        "payload structure: " + errors.map(FetchError::uiMessage).mkString("; "),
                        null));
    }

    private Validation<FetchError, JsonArray> validGames(JsonObject root) {
        return Try.of(() -> root.getAsJsonObject("data"))
                .map(data -> data == null ? null : data.getAsJsonArray("games"))
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload("TheGamesDB", "unreadable data.games", e))
                .flatMap(games -> games != null
                        ? Either.<FetchError, JsonArray>right(games)
                        : Either.<FetchError, JsonArray>left(
                                new FetchError.Payload("TheGamesDB", "missing data.games", null)))
                .toValidation();
    }

    /** Boxart absence is NOT an error — games parse with empty poster URLs (as before). */
    private Validation<FetchError, JsonObject> validBoxart(JsonObject root) {
        return Validation.valid(extractBoxartData(root));
    }

    private List<Game> zipRows(JsonArray games, JsonObject boxartData) {
        return List.range(0, games.size())
                .map(i -> toGame(games.get(i).getAsJsonObject(), boxartData));
    }

    /**
     * Lenient facade kept for callers that want the old swallow-and-return-empty
     * behavior (tests, tooling): the strict spine, unwrapped with an empty default.
     */
    public java.util.List<Game> parseGames(String json) {
        return parseRows(json).getOrElse(java.util.List.of());
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
        return List.range(0, images.size())
                .map(i -> images.get(i).getAsJsonObject())
                .filter(image -> "boxart".equals(optString(image, "type"))
                        && "front".equals(optString(image, "side")))
                .map(image -> optString(image, "filename"))
                .filter(filename -> !filename.isEmpty())
                .map(TgdbClient::posterPath)
                .headOption()
                .getOrElse("");
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
        return List.range(0, ids.size())
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
