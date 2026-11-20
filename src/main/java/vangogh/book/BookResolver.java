package vangogh.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Normalize;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;
import vangogh.recommend.PickResolver;

/**
 * Open Library resolution for the book recommendation slice. The model is
 * asked for {@code book | author}; two passes turn sparse, crowd-sourced
 * records into a card:
 *
 * <ol>
 *   <li><b>Structured pass:</b> {@code title=}/{@code author=} search. The
 *       normalized title must match exactly, and the author must match when
 *       the model supplied one. Highest {@code edition_count} wins.</li>
 *   <li><b>Containment fallback:</b> free-text {@code q=} search. A candidate
 *       title key must be contained in the model's key (so "The Hobbit" can
 *       resolve "The Hobbit, or There and Back Again"), guarded to keys of at
 *       least 6 characters or 2 words so "Dune" cannot swallow "Dune Messiah".
 *       Highest {@code edition_count} still wins.</li>
 * </ol>
 *
 * <p>Rows are handed to {@link OpenLibraryBookService#toBook(JsonObject, int)}
 * so a recommended card is built exactly like a trending one.
 */
public class BookResolver implements PickResolver<Book> {

    private static final int MIN_CONTAINMENT_LENGTH = 6;

    private final OpenLibraryClient client;

    public BookResolver(OpenLibraryClient client) {
        this.client = client;
    }

    @Override
    public Either<FetchError, Option<Book>> resolve(Pick pick) {
        String author = authorText(pick);
        String authorKey = author == null ? null : Normalize.key(author);

        return rows(client.searchBooksByTitleAuthor(pick.title(), author))
                .flatMap(rows -> {
                    Option<Book> strict = chooseStrict(rows, pick.title(), authorKey);
                    return strict.isDefined()
                            ? Either.right(strict)
                            : rows(client.searchBooks(pick.title()))
                                    .map(fallback -> chooseContained(fallback, pick.title(), authorKey));
                });
    }

    private static Either<FetchError, List<JsonObject>> rows(Try<ApiResponse> call) {
        return call.toEither()
                .mapLeft(error -> (FetchError) new FetchError.Transport("OpenLibrary search", error))
                .flatMap(response -> response.isSuccess()
                        ? parseRows(response.body)
                        : Either.left(new FetchError.HttpStatus(
                                "OpenLibrary search", response.statusCode, truncate(response.body, 300))));
    }

    private static Either<FetchError, List<JsonObject>> parseRows(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray docs = root.has("docs") && root.get("docs").isJsonArray()
                            ? root.getAsJsonArray("docs")
                            : new JsonArray();
                    return List.range(0, docs.size())
                            .map(i -> docs.get(i).getAsJsonObject());
                })
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Payload(
                        "OpenLibrary", "search results", error));
    }

    /** Pass 1: exact title (and author when supplied), best edition count. */
    private static Option<Book> chooseStrict(List<JsonObject> rows, String title, String authorKey) {
        String wanted = Normalize.key(title);
        JsonObject best = null;

        for (JsonObject row : rows) {
            if (!Normalize.key(text(row, "title")).equals(wanted)) {
                continue;
            }
            // Strict means the author must be present and match when the model
            // named one; the fallback pass is more forgiving about missing data.
            if (authorKey != null && !hasAuthorMatch(row, authorKey)) {
                continue;
            }
            if (best == null || editionCount(row) > editionCount(best)) {
                best = row;
            }
        }
        return best == null ? Option.none() : Option.some(OpenLibraryBookService.toBook(best, 0));
    }

    /**
     * Pass 2: the model title may carry a subtitle or a different edition name,
     * so a candidate key contained in the model key counts. The guard keeps a
     * short title ("Dune") from matching a longer different work.
     */
    private static Option<Book> chooseContained(List<JsonObject> rows, String title, String authorKey) {
        String wanted = Normalize.key(title);
        JsonObject best = null;

        for (JsonObject row : rows) {
            String candidate = Normalize.key(text(row, "title"));
            if (candidate.isEmpty() || !containmentAllowed(candidate) || !wanted.contains(candidate)) {
                continue;
            }
            if (authorKey != null && !authorMatchesOrMissing(row, authorKey)) {
                continue;
            }
            if (best == null || editionCount(row) > editionCount(best)) {
                best = row;
            }
        }
        return best == null ? Option.none() : Option.some(OpenLibraryBookService.toBook(best, 0));
    }

    private static boolean containmentAllowed(String candidateKey) {
        return candidateKey.length() >= MIN_CONTAINMENT_LENGTH
                || candidateKey.split(" ").length >= 2;
    }

    private static boolean hasAuthorMatch(JsonObject row, String authorKey) {
        List<String> authors = authors(row);
        return !authors.isEmpty() && authors.exists(name -> Normalize.key(name).contains(authorKey));
    }

    private static boolean authorMatchesOrMissing(JsonObject row, String authorKey) {
        List<String> authors = authors(row);
        return authors.isEmpty() || authors.exists(name -> Normalize.key(name).contains(authorKey));
    }

    private static List<String> authors(JsonObject row) {
        if (!row.has("author_name") || row.get("author_name").isJsonNull()
                || !row.get("author_name").isJsonArray()) {
            return List.empty();
        }
        return List.ofAll(row.getAsJsonArray("author_name"))
                .filter(element -> element.isJsonPrimitive())
                .map(element -> element.getAsString())
                .filter(name -> !name.isBlank());
    }

    private static int editionCount(JsonObject row) {
        return row.has("edition_count") && !row.get("edition_count").isJsonNull()
                ? row.get("edition_count").getAsInt()
                : 0;
    }

    /** The disambiguator is an author; a bare year is a model format slip. */
    private static String authorText(Pick pick) {
        String disambiguator = pick.disambiguator();
        if (disambiguator == null || disambiguator.isBlank()) {
            return null;
        }
        String trimmed = disambiguator.trim();
        return trimmed.matches("\\d{4}") ? null : trimmed;
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
