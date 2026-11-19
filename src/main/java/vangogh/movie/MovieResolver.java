package vangogh.movie;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Normalize;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;
import vangogh.recommend.PickResolver;

/**
 * TMDB resolution rule from the probe: exact normalized title equality among
 * the top 5 results, the release year must match when the model supplied one,
 * the highest {@code vote_count} wins, and anything under 10 votes is rejected
 * (a real movie the model meant will not be near-empty).
 *
 * <p>Rows are handed to {@link TmdbMovieService#toMovie(JsonObject)} so a
 * recommended card is built exactly like a trending one.
 */
public class MovieResolver implements PickResolver<Movie> {

    private static final int TOP_N = 5;
    private static final int MIN_VOTES = 10;

    private final TmdbClient client;

    public MovieResolver(TmdbClient client) {
        this.client = client;
    }

    @Override
    public Either<FetchError, Option<Movie>> resolve(Pick pick) {
        return client.searchMovie(pick.title())
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Transport("TMDB search", error))
                .flatMap(response -> response.isSuccess()
                        ? Either.right(response.body)
                        : Either.left(new FetchError.HttpStatus(
                                "TMDB search", response.statusCode, truncate(response.body, 300))))
                .flatMap(this::parseResults)
                .map(rows -> choose(rows, pick));
    }

    private Either<FetchError, JsonArray> parseResults(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    return root.has("results") && root.get("results").isJsonArray()
                            ? root.getAsJsonArray("results")
                            : new JsonArray();
                })
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Payload(
                        "TMDB", "search results", error));
    }

    private static Option<Movie> choose(JsonArray rows, Pick pick) {
        String wanted = Normalize.key(pick.title());
        Integer year = pick.year();
        JsonObject best = null;

        for (int i = 0; i < Math.min(TOP_N, rows.size()); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (!Normalize.key(text(row, "title")).equals(wanted)) {
                continue;
            }
            if (year != null && !year.equals(releaseYear(row))) {
                continue;
            }
            if (best == null || votes(row) > votes(best)) {
                best = row;
            }
        }

        if (best == null || votes(best) < MIN_VOTES) {
            return Option.none();
        }
        return Option.some(TmdbMovieService.toMovie(best));
    }

    private static Integer releaseYear(JsonObject row) {
        String date = text(row, "release_date");
        return date.length() >= 4 ? Integer.valueOf(date.substring(0, 4)) : null;
    }

    private static int votes(JsonObject row) {
        return row.has("vote_count") && !row.get("vote_count").isJsonNull()
                ? row.get("vote_count").getAsInt()
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
}
