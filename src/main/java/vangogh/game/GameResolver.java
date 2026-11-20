package vangogh.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Normalize;
import vangogh.llm.Pick;
import vangogh.net.TgdbClient;
import vangogh.recommend.PickResolver;

/**
 * TheGamesDB resolution for the game recommendation slice: exact normalized
 * {@code game_title}, the release year must match when the model supplied one,
 * and the first matching row wins. TheGamesDB exposes no popularity data, so
 * there is no vote/edition floor — a real game row is enough.
 *
 * <p>Rows are handed to {@link TgdbGameService#toGame(JsonObject, JsonObject)}
 * so a recommended card is built exactly like a trending one, including the
 * front-boxart URL.
 */
public class GameResolver implements PickResolver<Game> {

    private final TgdbClient client;

    public GameResolver(TgdbClient client) {
        this.client = client;
    }

    @Override
    public Either<FetchError, Option<Game>> resolve(Pick pick) {
        return client.searchGame(pick.title())
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Transport("TheGamesDB search", error))
                .flatMap(response -> response.isSuccess()
                        ? Either.right(response.body)
                        : Either.left(new FetchError.HttpStatus(
                                "TheGamesDB search", response.statusCode, truncate(response.body, 300))))
                .flatMap(GameResolver::parsePayload)
                .map(payload -> choose(payload, pick));
    }

    private static Either<FetchError, Payload> parsePayload(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject data = root.has("data") && root.get("data").isJsonObject()
                            ? root.getAsJsonObject("data")
                            : null;
                    if (data == null || !data.has("games") || !data.get("games").isJsonArray()) {
                        throw new IllegalStateException("missing data.games");
                    }
                    return new Payload(data.getAsJsonArray("games"),
                            TgdbGameService.extractBoxartData(root));
                })
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Payload(
                        "TheGamesDB", "search results", error));
    }

    private static Option<Game> choose(Payload payload, Pick pick) {
        String wanted = Normalize.key(pick.title());
        Integer year = pick.year();
        java.util.Set<Integer> seen = new java.util.HashSet<>();

        for (JsonElement element : payload.games()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            if (!Normalize.key(text(row, "game_title")).equals(wanted)) {
                continue;
            }
            if (year != null && !year.equals(releaseYear(row))) {
                continue;
            }
            int id = intValue(row, "id");
            if (!seen.add(id)) {
                continue;   // the same game can appear more than once in a search
            }
            return Option.some(TgdbGameService.toGame(row, payload.boxart()));
        }
        return Option.none();
    }

    private static Integer releaseYear(JsonObject row) {
        String date = text(row, "release_date");
        return date.length() >= 4 ? Integer.valueOf(date.substring(0, 4)) : null;
    }

    private static int intValue(JsonObject row, String field) {
        return row.has(field) && !row.get(field).isJsonNull()
                ? row.get(field).getAsInt()
                : 0;
    }

    private static String text(JsonObject row, String field) {
        return row.has(field) && !row.get(field).isJsonNull()
                ? row.get(field).getAsString()
                : "";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "null";
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private record Payload(JsonArray games, JsonObject boxart) {}
}
