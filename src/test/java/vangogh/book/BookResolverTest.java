package vangogh.book;

import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Open Library resolution rule from the design: a strict structured
 * title/author search first, then a guarded free-text containment fallback,
 * with {@code edition_count} breaking ties. Sparse crowd-sourced rows must not
 * crash the resolver, and only lookup failures are {@code Left} values.
 */
class BookResolverTest {

    static class StubClient extends OpenLibraryClient {
        Try<ApiResponse> structured = Try.success(new ApiResponse(200, docs(), null));
        Try<ApiResponse> free = Try.success(new ApiResponse(200, docs(), null));
        int structuredCalls;
        int freeCalls;

        @Override
        public Try<ApiResponse> searchBooksByTitleAuthor(String title, String author) {
            structuredCalls++;
            return structured;
        }

        @Override
        public Try<ApiResponse> searchBooks(String query) {
            freeCalls++;
            return free;
        }
    }

    private static String doc(String key, String title, String author, int year, int editions) {
        return "{\"key\":\"/works/" + key + "\","
                + "\"title\":\"" + title + "\","
                + "\"author_name\":[\"" + author + "\"],"
                + "\"first_publish_year\":" + year + ","
                + "\"edition_count\":" + editions + ","
                + "\"cover_i\":14627509}";
    }

    private static String authorlessDoc(String key, String title, int year, int editions) {
        return "{\"key\":\"/works/" + key + "\","
                + "\"title\":\"" + title + "\","
                + "\"first_publish_year\":" + year + ","
                + "\"edition_count\":" + editions + "}";
    }

    private static String docs(String... rows) {
        return "{\"docs\":[" + String.join(",", rows) + "]}";
    }

    private static StubClient clientWith(String structured, String free) {
        StubClient client = new StubClient();
        client.structured = Try.success(new ApiResponse(200, structured, null));
        client.free = Try.success(new ApiResponse(200, free, null));
        return client;
    }

    @Test
    void anExactTitleAndAuthorResolveToACard() {
        StubClient client = clientWith(docs(
                doc("OL27482W", "The Hobbit", "J.R.R. Tolkien", 1937, 482)), docs());

        Option<Book> resolved = new BookResolver(client)
                .resolve(new Pick("The Hobbit", "J.R.R. Tolkien")).get();

        assertTrue(resolved.isDefined());
        assertEquals("OL27482W", resolved.get().workKey());
        assertEquals("The Hobbit", resolved.get().title());
        assertEquals("J.R.R. Tolkien", resolved.get().author());
        assertEquals(1937, resolved.get().year());
        assertEquals(482, resolved.get().editionCount());
        assertEquals("https://covers.openlibrary.org/b/id/14627509-L.jpg",
                resolved.get().coverUrl());
        // A strict hit never spends the fallback request.
        assertEquals(0, client.freeCalls);
    }

    @Test
    void matchingIgnoresCaseArticlesAndTypography() {
        StubClient client = clientWith(docs(
                doc("OL1W", "The Hobbit", "J.R.R. Tolkien", 1937, 10)), docs());

        assertTrue(new BookResolver(client).resolve(new Pick("hobbit", null)).get().isDefined());
    }

    @Test
    void theHighestEditionCountWinsAmongExactMatches() {
        StubClient client = clientWith(docs(
                doc("OL1W", "The Hobbit", "J.R.R. Tolkien", 1937, 10),
                doc("OL2W", "The Hobbit", "J.R.R. Tolkien", 1937, 482)), docs());

        assertEquals("OL2W", new BookResolver(client)
                .resolve(new Pick("The Hobbit", "Tolkien")).get().get().workKey());
    }

    @Test
    void anAuthorMismatchIsACleanMiss() {
        String wrongAuthor = docs(doc("OL1W", "The Hobbit", "Some Other Author", 1937, 482));
        StubClient client = clientWith(wrongAuthor, wrongAuthor);

        assertTrue(new BookResolver(client)
                .resolve(new Pick("The Hobbit", "J.R.R. Tolkien")).get().isEmpty());
        assertEquals(1, client.freeCalls);
    }

    @Test
    void strictMissFallsThroughToTheContainmentFallback() {
        StubClient client = clientWith(docs(), docs(
                doc("OL27482W", "The Hobbit", "J.R.R. Tolkien", 1937, 482)));

        Option<Book> resolved = new BookResolver(client)
                .resolve(new Pick("The Hobbit, or There and Back Again", "Tolkien")).get();

        assertTrue(resolved.isDefined());
        assertEquals("The Hobbit", resolved.get().title());
        assertEquals(1, client.freeCalls);
    }

    @Test
    void containmentGuardRejectsShortSingleWordTitles() {
        StubClient client = clientWith(docs(), docs(
                doc("OL1W", "Dune", "Frank Herbert", 1965, 5000)));

        // "dune" is 4 characters and one word: too weak to stand in for
        // "Dune Messiah", which is a different book.
        assertTrue(new BookResolver(client)
                .resolve(new Pick("Dune Messiah", "Frank Herbert")).get().isEmpty());
    }

    @Test
    void theContainedMatchWithTheMostEditionsWins() {
        StubClient client = clientWith(docs(), docs(
                doc("OL1W", "The Hobbit", "J.R.R. Tolkien", 1937, 40),
                doc("OL2W", "The Hobbit", "J.R.R. Tolkien", 1937, 482)));

        assertEquals("OL2W", new BookResolver(client)
                .resolve(new Pick("The Hobbit, or There and Back Again", null))
                .get().get().workKey());
    }

    @Test
    void missingAuthorDataIsAllowedInTheFallbackButNotStrict() {
        String rows = docs(authorlessDoc("OL1W", "The Hobbit", 1937, 482));
        StubClient client = clientWith(rows, rows);

        Option<Book> resolved = new BookResolver(client)
                .resolve(new Pick("The Hobbit", "J.R.R. Tolkien")).get();

        assertTrue(resolved.isDefined());
        assertEquals(1, client.freeCalls);
    }

    @Test
    void aBareYearIsAFormatSlipNotAnAuthor() {
        StubClient client = clientWith(docs(
                authorlessDoc("OL1W", "The Hobbit", 1937, 482)), docs());

        assertTrue(new BookResolver(client)
                .resolve(new Pick("The Hobbit", "1937")).get().isDefined());
        assertEquals(0, client.freeCalls);
    }

    @Test
    void noMatchesIsACleanMiss() {
        StubClient client = clientWith(docs(), docs());

        assertTrue(new BookResolver(client)
                .resolve(new Pick("Nothing At All", null)).get().isEmpty());
    }

    @Test
    void anHttpFailureOnTheStrictSearchIsALeft() {
        StubClient client = new StubClient();
        client.structured = Try.success(new ApiResponse(500, "boom", "server error"));

        Either<FetchError, Option<Book>> result = new BookResolver(client)
                .resolve(new Pick("The Hobbit", "Tolkien"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(500, ((FetchError.HttpStatus) result.getLeft()).status());
        assertEquals(0, client.freeCalls);
    }

    @Test
    void aFallbackFailureAfterACleanStrictMissIsALeft() {
        StubClient client = new StubClient();
        client.structured = Try.success(new ApiResponse(200, docs(), null));
        client.free = Try.success(new ApiResponse(503, "busy", "try later"));

        Either<FetchError, Option<Book>> result = new BookResolver(client)
                .resolve(new Pick("The Hobbit", "Tolkien"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(1, client.freeCalls);
    }

    @Test
    void aTransportFailureIsALeft() {
        StubClient client = new StubClient();
        client.structured = Try.failure(new RuntimeException("offline"));

        Either<FetchError, Option<Book>> result = new BookResolver(client)
                .resolve(new Pick("The Hobbit", "Tolkien"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
    }

    @Test
    void aMalformedBodyIsAPayloadLeft() {
        StubClient client = clientWith("not json", docs());

        Either<FetchError, Option<Book>> result = new BookResolver(client)
                .resolve(new Pick("The Hobbit", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }
}
