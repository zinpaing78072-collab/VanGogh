package vangogh.book;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;

import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Open Library fetch pipeline — the same {@link Either} spine as the
 * game service ({@code vangogh.game.TgdbGameService}). One row parser serves both endpoints:
 * {@code search.json} returns its hits under {@code docs},
 * {@code trending/weekly.json} under {@code works}, and both accept the same
 * {@code fields=} list. Rows build leniently (Open Library work records are
 * crowd-sourced and sparse); only an unreadable payload structure is an error.
 */
public class OpenLibraryBookService {

    private static final int SUBJECT_LIMIT = 3;

    private final OpenLibraryClient client;

    // Cover data URIs by cover URL, so re-renders don't re-download the same
    // images. Bounded so long sessions don't grow it. Stays a concurrent map
    // because vavr has no concurrent map.
    private final Map<String, String> coverCache = new ConcurrentHashMap<>();

    public OpenLibraryBookService(OpenLibraryClient client) {
        this.client = client;
    }

    public OpenLibraryBookService() {
        this(new OpenLibraryClient());
    }

    public Either<FetchError, java.util.List<Book>> fetchTrending() {
        return client.trending()
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("OpenLibrary trending", e))
                .flatMap(res -> requireSuccess("OpenLibrary trending", res))
                .flatMap(this::parseRows);
    }

    public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.searchBooks(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("OpenLibrary search", e))
                        .flatMap(res -> requireSuccess("OpenLibrary search", res))
                        .flatMap(this::parseRows);
    }

    /**
     * The recommendation engine's resolver, sharing this service's client — so
     * a controller (and its tests) can never end up with two Open Library
     * clients.
     */
    public BookResolver resolver() {
        return new BookResolver(client);
    }

    // ------------------------------------------------------------ detail

    /**
     * The detail page's data. The work record is the spine (its failure is the
     * page's error); the author, editions, ratings and similar payloads are
     * supplementary — a failure there leaves an empty tile, not an error.
     *
     * <p>The supplementary fetches start together on vavr's virtual-thread
     * executor, so the page waits for the slowest call rather than their sum.
     */
    public Either<FetchError, BookDetail> bookDetail(String requestedKey) {
        String workKey = BookDetailMapper.normaliseKey(requestedKey);
        if (workKey.isEmpty()) {
            return Either.left(new FetchError.BadInput("workKey", "required"));
        }
        return client.workDetail(workKey)
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("OpenLibrary work", e))
                .flatMap(res -> requireSuccess("OpenLibrary work", res))
                .flatMap(body -> parseObject("OpenLibrary work", body))
                .map(work -> composeDetail(workKey, work));
    }

    /** Detail artwork (cover, author photo, edition covers, similar covers). */
    public Option<String> detailImageDataUri(String imageUrl) {
        return Option.of(imageUrl)
                .filter(u -> !u.isEmpty())
                .flatMap(u -> Option.of(coverCache.get(u))
                        .orElse(() -> client.loadDetailImageDataUri(u)
                                .peek(uri -> cachePut(u, uri))));
    }

    private BookDetail composeDetail(String workKey, JsonObject work) {
        String authorKey = BookDetailMapper.authorKey(work);
        java.util.List<String> subjects = BookDetailMapper.subjects(work);

        Future<Option<JsonObject>> author =
                authorKey.isEmpty() ? Future.successful(Option.none())
                        : Future.of(() -> jsonOption(client.authorDetail(authorKey)));
        Future<Option<JsonObject>> editions =
                Future.of(() -> jsonOption(client.editions(workKey)));
        Future<Option<JsonObject>> ratings =
                Future.of(() -> jsonOption(client.ratings(workKey)));
        Future<Option<JsonObject>> bySubject =
                subjects.isEmpty() ? Future.successful(Option.none())
                        : Future.of(() -> jsonOption(client.searchBySubject(subjects.get(0))));

        Option<JsonObject> authorJson = await(author);
        Option<JsonObject> editionsJson = await(editions);
        Option<JsonObject> ratingsJson = await(ratings);
        Option<JsonObject> similarJson = await(bySubject);

        // Works without subjects still get a rail: same-author titles. This
        // one call waits for the author record, so it is the only supplement
        // that cannot start with the others.
        if (similarJson.isEmpty()) {
            String name = authorJson.map(BookDetailMapper::authorName).getOrElse("");
            if (!name.isEmpty()) {
                similarJson = jsonOption(client.searchByAuthor(name));
            }
        }

        return BookDetailMapper.compose(
                work,
                workKey,
                editionsJson.getOrNull(),
                ratingsJson.getOrNull(),
                authorJson.getOrNull(),
                similarBooks(similarJson, workKey));
    }

    private java.util.List<Book> similarBooks(Option<JsonObject> payload, String workKey) {
        return payload
                .flatMap(root -> firstPresentArray(root, "docs", "works"))
                .map(rows -> List.range(0, rows.size())
                        .map(i -> toBook(rows.get(i).getAsJsonObject(), i))
                        .filter(book -> !book.workKey().equals(workKey))
                        .distinctBy(Book::workKey)
                        .take(6)
                        .toJavaList())
                .getOrElse(java.util.List.of());
    }

    private static Option<JsonObject> await(Future<Option<JsonObject>> future) {
        Try<Option<JsonObject>> result = future.await().getValue().get();
        return result.getOrElse(Option.none());
    }

    private static Option<JsonObject> jsonOption(Try<ApiResponse> response) {
        return response.toOption()
                .filter(ApiResponse::isSuccess)
                .flatMap(res -> Try.of(() ->
                        com.google.gson.JsonParser.parseString(res.body).getAsJsonObject()).toOption());
    }

    private static Either<FetchError, JsonObject> parseObject(String api, String body) {
        return Try.of(() -> com.google.gson.JsonParser.parseString(body).getAsJsonObject())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload(api, "payload shape", e));
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    /** Structure-level strictness: an unreadable payload is an error, not an empty list. Package-private for tests. */
    Either<FetchError, java.util.List<Book>> parseRows(String body) {
        return Try.of(() -> JsonParser.parseString(body).getAsJsonObject())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Payload("OpenLibrary", "payload shape", e))
                .flatMap(this::extractStructure)
                .map(books -> books.toJavaList());
    }

    /**
     * The single structure extraction: the docs array ({@code search.json})
     * or the works array ({@code trending/weekly.json}). Their absence is the
     * one structural failure; everything inside the rows is lenient.
     */
    private Either<FetchError, List<Book>> extractStructure(JsonObject root) {
        return firstPresentArray(root, "docs", "works")
                .toEither(() -> (FetchError) new FetchError.Payload("OpenLibrary",
                        "missing docs/works array", null))
                .map(rows -> List.range(0, rows.size())
                        .map(i -> toBook(rows.get(i).getAsJsonObject(), i)));
    }

    /** The first of the named arrays that is present (vavr {@code find}, not takeUntil). */
    private static Option<JsonArray> firstPresentArray(JsonObject root, String... names) {
        return List.of(names)
                .map(name -> Option.of(root.get(name))
                        .filter(j -> !j.isJsonNull() && j.isJsonArray())
                        .map(j -> j.getAsJsonArray()))
                .flatMap(o -> o.toList())
                .headOption();
    }

    /** Lenient facade kept for tests/tooling: the strict spine, unwrapped with an empty default. */
    public java.util.List<Book> parseBooks(String json) {
        return parseRows(json).getOrElse(java.util.List.of());
    }

    /**
     * Row builder shared with {@link BookResolver}: a recommended card must be
     * identical to a trending or search card. Static because it only reads the
     * payload — no service state involved.
     */
    static Book toBook(JsonObject doc, int position) {
        String workKey = Option.of(optString(doc, "key"))
                .map(k -> k.replaceFirst("^/works/", ""))
                .filter(k -> !k.isEmpty())
                .getOrElse("book-" + position);
        String title = Option.of(optString(doc, "title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String author = Option.of(doc.get("author_name"))
                .filter(j -> !j.isJsonNull() && j.isJsonArray())
                .map(j -> j.getAsJsonArray())
                .map(names -> names.size() > 0 ? names.get(0).getAsString() : "")
                .getOrElse("");
        int year = Option.of(doc.get("first_publish_year"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        double rating = Option.of(doc.get("ratings_average"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsDouble())
                .getOrElse(0.0);
        String subjects = Option.of(doc.get("subject"))
                .filter(j -> !j.isJsonNull() && j.isJsonArray())
                .map(j -> j.getAsJsonArray())
                .map(arr -> List.range(0, arr.size())
                        .take(SUBJECT_LIMIT)
                        .map(i -> arr.get(i).getAsString())
                        .mkString(" · "))
                .getOrElse("");
        String firstSentence = Option.of(doc.get("first_sentence"))
                .filter(j -> !j.isJsonNull() && j.isJsonArray())
                .map(j -> pickEnglish(j.getAsJsonArray()))
                .getOrElse("");
        int pages = Option.of(doc.get("number_of_pages_median"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        int editionCount = Option.of(doc.get("edition_count"))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsInt())
                .getOrElse(0);
        String coverUrl = Option.of(doc.get("cover_i"))
                .filter(j -> !j.isJsonNull())
                .map(j -> OpenLibraryClient.coverPath(j.getAsLong()))
                .getOrElse("");
        return new Book(workKey, title, author, year, rating, subjects, firstSentence, pages, editionCount, coverUrl);
    }

    /**
     * {@code first_sentence} pools every translation; the English line is
     * usually the ASCII-only entry. Falls back to the first entry.
     */
    private static String pickEnglish(JsonArray sentences) {
        if (sentences.size() == 0) {
            return "";
        }
        return List.range(0, sentences.size())
                .map(i -> sentences.get(i).getAsString())
                .find(OpenLibraryBookService::isAscii)
                .getOrElse(() -> sentences.get(0).getAsString());
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    private static String optString(JsonObject doc, String key) {
        return Option.of(doc.get(key))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsString())
                .getOrElse("");
    }

    /**
     * Cache-aside as an {@link Option} chain: absent/empty URL is {@code None},
     * a cache hit short-circuits via {@code orElse}, a miss is fetched and put
     * back ({@code peek}) — bounded so long sessions don't grow the cache.
     */
    public Option<String> coverDataUri(String coverUrl) {
        return Option.of(coverUrl)
                .filter(u -> !u.isEmpty())
                .flatMap(u -> Option.of(coverCache.get(u))
                        .orElse(() -> client.loadCoverDataUri(u)
                                .peek(uri -> cachePut(u, uri))));
    }

    private void cachePut(String url, String uri) {
        if (coverCache.size() >= 512) {
            coverCache.clear();
        }
        coverCache.put(url, uri);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
