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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

    private static final int MAX_RELATED = 6;
    private static final int MIN_SERIES = 3;

    private final TgdbClient client;

    // Poster data URIs by poster URL, so re-renders (search, filter toggles)
    // don't re-download the same images. Bounded so long sessions don't grow it.
    // Stays a concurrent map because vavr has no concurrent map.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    // The name dictionaries are large (thousands of rows) and change slowly, so
    // they are fetched at most once per service instance — and only when a
    // detail page actually needs a name.
    private final AtomicReference<Map<String, String>> publisherDictionary = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> developerDictionary = new AtomicReference<>();

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

    /**
     * The recommendation engine's resolver, sharing this service's client — so
     * a controller (and its tests) can never end up with two TheGamesDB
     * clients.
     */
    public GameResolver resolver() {
        return new GameResolver(client);
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

    /**
     * Row builder shared with {@link GameResolver}: a recommended card must be
     * identical to a trending or search card. Static because it only reads the
     * payload — no service state involved.
     */
    static Game toGame(JsonObject g, JsonObject boxartData) {
        int id = Option.of(g.get("id"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        String title = Option.of(optString(g, "game_title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String release = optString(g, "release_date");
        // The API's rating is an age rating string, e.g. "M - Mature 17+", not a
        // numeric score — it passes through to the card untouched.
        String ageRating = optString(g, "rating");
        String genre = Option.of(g.get("genres"))
                .filter(j -> !j.isJsonNull())
                .map(j -> genreLabel(j.getAsJsonArray()))
                .getOrElse("");
        String overview = optString(g, "overview");
        String posterUrl = findFrontBoxart(boxartData, id);
        return new Game(id, title, release, ageRating, genre, overview, posterUrl);
    }

    static JsonObject extractBoxartData(JsonObject root) {
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

    // -------------------------------------------------------------- detail

    /**
     * The detail page's data. The record itself is the spine — its failure is
     * the page's error; the images, the name dictionaries and the series rail
     * are supplementary, so a failure there leaves an empty tile instead.
     *
     * <p>Only real record fields are exposed: the API's rating string passes
     * through untouched, and nothing is invented for records that do not carry
     * a developer, a price or a playtime.
     */
    public Either<FetchError, GameDetail> gameDetail(int gameId) {
        if (gameId <= 0) {
            return Either.left(new FetchError.BadInput("game id", "missing"));
        }
        return client.gameById(gameId)
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("TheGamesDB game", e))
                .flatMap(res -> requireSuccess("TheGamesDB game", res))
                .flatMap(this::requireGame)
                .map(root -> composeDetail(root, gameId));
    }

    /** Detail artwork (boxart, fanart, screenshots) through the same image cache. */
    public Option<String> detailImageDataUri(String imageUrl) {
        return Option.of(imageUrl)
                .filter(u -> !u.isEmpty())
                .flatMap(u -> Option.of(posterCache.get(u))
                        .orElse(() -> client.loadDetailImageDataUri(u)
                                .peek(uri -> cachePut(u, uri))));
    }

    private Either<FetchError, JsonObject> requireGame(String body) {
        return Try.of(() -> JsonParser.parseString(body).getAsJsonObject())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload("TheGamesDB", "payload shape", e))
                .flatMap(root -> Option.of(root.getAsJsonObject("data"))
                        .flatMap(data -> Option.of(data.getAsJsonArray("games")))
                        .filter(games -> games.size() > 0)
                        .map(games -> root)
                        .toEither(() -> (FetchError) new FetchError.Payload(
                                "TheGamesDB", "missing data.games", null)));
    }

    private GameDetail composeDetail(JsonObject root, int gameId) {
        JsonObject game = root.getAsJsonObject("data").getAsJsonArray("games")
                .get(0).getAsJsonObject();
        JsonObject images = client.gameImages(gameId)
                .toOption()
                .filter(ApiResponse::isSuccess)
                .flatMap(res -> Try.of(() ->
                        JsonParser.parseString(res.body).getAsJsonObject()).toOption())
                .getOrNull();
        RelatedRail rail = relatedGames(game, gameId);
        return GameDetailMapper.compose(
                root,
                game,
                images,
                rail.source(),
                rail.items(),
                developerNames(),
                publisherNames());
    }

    /** The rail plus where it came from, so the page can label it honestly. */
    private record RelatedRail(String source, java.util.List<GameDetail.Related> items) {}

    /**
     * The related rail. Same-series siblings come from a name search seeded with
     * the title's first meaningful word; when the record has too few (most
     * games only have platform/edition twins), the catalogue's trending list
     * fills the rest, and the rail says so.
     */
    private RelatedRail relatedGames(JsonObject game, int gameId) {
        String title = optString(game, "game_title");
        String seed = seriesSeed(title);
        java.util.List<GameDetail.Related> series = seed.isEmpty()
                ? java.util.List.of()
                : client.searchGame(seed)
                        .toOption()
                        .filter(ApiResponse::isSuccess)
                        .flatMap(res -> parseRelated(res.body, gameId, title, false).toOption())
                        .getOrElse(java.util.List.of());
        if (series.size() >= MIN_SERIES) {
            return new RelatedRail("series", List.ofAll(series).take(4).toJavaList());
        }

        java.util.List<GameDetail.Related> catalogue = client.trending()
                .toOption()
                .filter(ApiResponse::isSuccess)
                .flatMap(res -> parseRelated(res.body, gameId, "", true).toOption())
                .getOrElse(java.util.List.of());

        java.util.List<GameDetail.Related> combined = List.ofAll(series)
                .appendAll(List.ofAll(catalogue))
                .distinctBy(GameDetail.Related::id)
                .filter(related -> related.id() != gameId)
                .take(4)
                .toJavaList();
        return new RelatedRail("catalogue", combined);
    }

    private Either<FetchError, java.util.List<GameDetail.Related>> parseRelated(
            String body, int gameId, String title, boolean allowVariants) {
        return Try.of(() -> JsonParser.parseString(body).getAsJsonObject())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload("TheGamesDB", "payload shape", e))
                .map(root -> {
                    JsonArray rows = Option.of(root.getAsJsonObject("data"))
                            .flatMap(data -> Option.of(data.getAsJsonArray("games")))
                            .getOrElse(new JsonArray());
                    JsonObject boxartData = extractBoxartData(root);
                    List<GameDetail.Related> candidates = List.range(0, rows.size())
                            .map(i -> rows.get(i).getAsJsonObject())
                            .filter(candidate -> optInt(candidate, "id") != gameId)
                            .filter(candidate -> title.isEmpty()
                                    || !optString(candidate, "game_title").equalsIgnoreCase(title))
                            .filter(candidate -> allowVariants
                                    || !isVariant(optString(candidate, "game_title")))
                            .map(candidate -> toRelated(candidate, boxartData))
                            .filter(related -> !related.title().isEmpty());
                    return candidates
                            .distinctBy(related -> related.title().toLowerCase())
                            .sortBy(related -> related.posterUrl().isEmpty())
                            .take(MAX_RELATED)
                            .toJavaList();
                });
    }

    /** "[Demo Disc]", "(Collector's Edition)", "… Director's Cut": same game, not a sibling. */
    private static boolean isVariant(String title) {
        String lower = title.toLowerCase();
        return lower.contains("[") || lower.contains("(")
                || lower.contains("edition") || lower.contains("demo disc")
                || lower.contains("director's cut");
    }

    private static GameDetail.Related toRelated(JsonObject game, JsonObject boxartData) {
        int id = optInt(game, "id");
        String release = optString(game, "release_date");
        String genre = Option.of(game.get("genres"))
                .filter(element -> !element.isJsonNull() && element.isJsonArray())
                .map(element -> genreLabel(element.getAsJsonArray()))
                .getOrElse("");
        return new GameDetail.Related(
                id,
                optString(game, "game_title"),
                yearOf(release),
                optString(game, "rating"),
                genre,
                TgdbClient.mediumVariant(findFrontBoxart(boxartData, id)));
    }

    /** The first word of the title that can serve as a series name. */
    static String seriesSeed(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String cleaned = title.replaceAll("[\\[({].*$", " ").trim();
        for (String word : cleaned.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 4) {
                return word;
            }
        }
        return "";
    }

    private Map<String, String> publisherNames() {
        return nameDictionary(publisherDictionary, () -> client.publishers(), "publishers");
    }

    private Map<String, String> developerNames() {
        return nameDictionary(developerDictionary, () -> client.developers(), "developers");
    }

    /**
     * The API sends publisher/developer ids on the game payload, so the names
     * come from a full dictionary. One fetch per service instance: the
     * dictionary is thousands of rows and does not change within a session.
     */
    private Map<String, String> nameDictionary(
            AtomicReference<Map<String, String>> cache,
            java.util.function.Supplier<Try<ApiResponse>> fetch,
            String node) {
        Map<String, String> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Map<String, String> parsed = fetch.get()
                .toOption()
                .filter(ApiResponse::isSuccess)
                .flatMap(res -> Try.of(() -> parseNames(res.body, node)).toOption())
                .getOrElse(Map.of());
        if (!parsed.isEmpty()) {
            cache.set(parsed);
        }
        return parsed;
    }

    private static Map<String, String> parseNames(String body, String node) {
        JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
        JsonObject dictionary = data.getAsJsonObject(node);
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : dictionary.entrySet()) {
            names.put(entry.getKey(), optString(entry.getValue().getAsJsonObject(), "name"));
        }
        return names;
    }

    private static int optInt(JsonObject object, String key) {
        return Option.of(object.get(key))
                .filter(element -> !element.isJsonNull())
                .map(JsonElement::getAsInt)
                .getOrElse(0);
    }

    private static int yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return 0;
        }
        String year = releaseDate.substring(0, 4);
        return year.chars().allMatch(Character::isDigit) ? Integer.parseInt(year) : 0;
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

    static String genreName(int id) {
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
