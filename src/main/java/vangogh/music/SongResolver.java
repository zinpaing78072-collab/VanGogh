package vangogh.music;

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
import vangogh.net.GeniusClient;
import vangogh.recommend.PickResolver;

import java.util.regex.Pattern;

/**
 * Genius resolution for the music recommendation slice. The model is asked for
 * {@code song | artist} (see {@link vangogh.llm.Form#MUSIC}); this resolver
 * searches Genius for the title and keeps the first song hit in Genius'
 * relevance order that matches.
 *
 * <p>Matching is deliberately strict about the title and forgiving about
 * presentation: case, articles, punctuation and accents do not matter
 * ({@link Normalize}), and a trailing parenthetical or {@code feat.} clause is
 * ignored so "Anti-Hero" still resolves "Anti-Hero (feat. …)". A model-supplied
 * artist must appear in the hit's artist names — otherwise the hit is a cover
 * by someone else and is a clean miss. A bare year in the disambiguator slot is
 * treated as a format slip, not an artist.
 *
 * <p>Rows are handed to {@link GeniusMusicService#toSong(JsonObject)} so a
 * recommended card is built exactly like a trending one.
 */
public class SongResolver implements PickResolver<Song> {

    private static final int TOP_N = 5;

    /** A trailing bracket group: "(feat. X)", "[Remastered 2011]". */
    private static final Pattern TRAILING_BRACKET =
            Pattern.compile("\\s*[\\(\\[][^)\\]]*[\\)\\]]\\s*$");

    /** An unbracketed feature credit: "Song feat. X", "Song featuring X". */
    private static final Pattern TRAILING_FEATURE =
            Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$");

    private static final Pattern BARE_YEAR = Pattern.compile("\\d{4}");

    private final GeniusClient client;

    public SongResolver(GeniusClient client) {
        this.client = client;
    }

    @Override
    public Either<FetchError, Option<Song>> resolve(Pick pick) {
        return client.search(pick.title())
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Transport("Genius search", error))
                .flatMap(response -> response.isSuccess()
                        ? Either.right(response.body)
                        : Either.left(new FetchError.HttpStatus(
                                "Genius search", response.statusCode, truncate(response.body, 300))))
                .flatMap(this::parseHits)
                .map(hits -> choose(hits, pick));
    }

    private Either<FetchError, JsonArray> parseHits(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject response = root.has("response") && root.get("response").isJsonObject()
                            ? root.getAsJsonObject("response")
                            : new JsonObject();
                    return response.has("hits") && response.get("hits").isJsonArray()
                            ? response.getAsJsonArray("hits")
                            : new JsonArray();
                })
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Payload(
                        "Genius", "search results", error));
    }

    private static Option<Song> choose(JsonArray hits, Pick pick) {
        String wanted = matchKey(pick.title());
        if (wanted.isEmpty()) {
            return Option.none();
        }
        String artist = artistKey(pick);
        int considered = 0;

        for (JsonElement element : hits) {
            if (considered >= TOP_N) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hit = element.getAsJsonObject();
            // Genius search mixes songs, albums and artists in one hit list.
            if (hit.has("type") && !"song".equals(text(hit, "type"))) {
                continue;
            }
            JsonObject result = hit.has("result") && hit.get("result").isJsonObject()
                    ? hit.getAsJsonObject("result")
                    : hit;
            if (text(result, "title").isEmpty()) {
                continue;
            }
            considered++;
            if (!matchKey(text(result, "title")).equals(wanted)) {
                continue;
            }
            if (artist != null && !matchesArtist(result, artist)) {
                continue;
            }
            // Genius ranks by relevance: the first exact match is the pick.
            return Option.some(GeniusMusicService.toSong(result));
        }
        return Option.none();
    }

    /** The disambiguator is an artist; a bare year is a model format slip. */
    private static String artistKey(Pick pick) {
        String disambiguator = pick.disambiguator();
        if (disambiguator == null || disambiguator.isBlank()) {
            return null;
        }
        String trimmed = disambiguator.trim();
        if (BARE_YEAR.matcher(trimmed).matches()) {
            return null;
        }
        String key = Normalize.key(trimmed);
        return key.isEmpty() ? null : key;
    }

    private static boolean matchesArtist(JsonObject result, String artistKey) {
        String names = Normalize.key(GeniusMusicService.artistName(result));
        return names.equals(artistKey) || names.contains(artistKey);
    }

    /**
     * The comparison key for a song title, after dropping presentation-only
     * suffixes. Only a *trailing* bracket group is removed, so a title that
     * legitimately contains parentheses in the middle keeps it.
     */
    static String matchKey(String title) {
        String text = title == null ? "" : title.trim();
        text = TRAILING_BRACKET.matcher(text).replaceFirst("");
        text = TRAILING_FEATURE.matcher(text).replaceFirst("");
        return Normalize.key(text);
    }

    private static String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString()
                : "";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "null";
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
