package vangogh.book;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Book fetch pipeline over Open Library, following the same {@link Either}
 * spine as the other services. Trending and search return different wrapper
 * keys ("works" vs "docs") but the same per-work fields.
 */
public class OpenLibraryService {

    private final OpenLibraryClient client;

    // Cover data URIs by cover URL, bounded like the other caches.
    // Stays a concurrent map because vavr has no concurrent map.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public OpenLibraryService(OpenLibraryClient client) {
        this.client = client;
    }

    public OpenLibraryService() {
        this(new OpenLibraryClient());
    }

    public Either<FetchError, java.util.List<Book>> fetchTrending() {
        return client.trending()
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("Open Library trending", e))
                .flatMap(res -> requireSuccess("Open Library trending", res))
                .flatMap(body -> parseRows(body, "works"))
                .map(List::toJavaList);
    }

    public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.search(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("Open Library search", e))
                        .flatMap(res -> requireSuccess("Open Library search", res))
                        .flatMap(body -> parseRows(body, "docs"))
                        .map(List::toJavaList);
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    /** Structure-level strictness: an unreadable payload is an error, not an empty list. */
    private Either<FetchError, List<Book>> parseRows(String body, String rowsKey) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray rows = root.getAsJsonArray(rowsKey);
                    return List.range(0, rows.size())
                            .map(i -> toBook(rows.get(i).getAsJsonObject()));
                })
                .toEither()
                .mapLeft(e -> new FetchError.Payload("Open Library", "payload shape (" + rowsKey + ")", e));
    }

    private Book toBook(JsonObject w) {
        String id = optString(w, "key");
        String title = Option.of(optString(w, "title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String author = firstArrayEntry(w, "author_name").getOrElse("Unknown Author");
        int year = Option.of(w.get("first_publish_year"))
                .filter(j -> !j.isJsonNull())
                .flatMap(j -> Try.of(j::getAsInt).toOption())
                .getOrElse(0);
        double rating = Option.of(w.get("ratings_average"))
                .filter(j -> !j.isJsonNull())
                .flatMap(j -> Try.of(j::getAsDouble).toOption())
                .getOrElse(0.0);
        String overview = firstArrayEntry(w, "first_sentence").getOrElse("");
        String genre = topSubjects(w.get("subject"));
        String posterUrl = OpenLibraryClient.coverUrl(
                Option.of(w.get("cover_i"))
                        .filter(j -> !j.isJsonNull())
                        .flatMap(j -> Try.of(j::getAsInt).toOption())
                        .map(String::valueOf)
                        .getOrElse(""));
        return new Book(id, title, author, year, rating, overview, genre, posterUrl);
    }

    /** The two first community subjects, joined for the genre line. */
    private static String topSubjects(com.google.gson.JsonElement subject) {
        return Option.of(subject)
                .filter(j -> !j.isJsonNull() && j.isJsonArray())
                .map(j -> List.range(0, j.getAsJsonArray().size())
                        .map(i -> j.getAsJsonArray().get(i))
                        .map(el -> el.isJsonPrimitive() ? el.getAsString() : "")
                        .filter(name -> !name.isEmpty())
                        .take(2)
                        .mkString(" · "))
                .getOrElse("");
    }

    private static Option<String> firstArrayEntry(JsonObject w, String key) {
        return Option.of(w.get(key))
                .filter(j -> !j.isJsonNull() && j.isJsonArray() && j.getAsJsonArray().size() > 0)
                .map(j -> (JsonElement) j.getAsJsonArray().get(0))
                .map(JsonElement::getAsString)
                .filter(s -> !s.isEmpty());
    }

    private static String optString(JsonObject o, String key) {
        return Option.of(o.get(key))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsString())
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
