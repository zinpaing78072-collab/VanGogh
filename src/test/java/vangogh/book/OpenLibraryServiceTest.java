package vangogh.book;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link OpenLibraryService} with a stubbed client — no real
 * Open Library network calls.
 */
class OpenLibraryServiceTest {

    private static final String SEARCH_JSON = """
        {
          "numFound": 1,
          "docs": [
            { "key": "/works/OL8199548W", "title": "I Have Lived a Thousand Years",
              "author_name": ["Livia Bitton-Jackson"], "cover_i": 435106,
              "first_publish_year": 1997, "ratings_average": 4.142857,
              "first_sentence": ["I dream of enrolling in the prep school in Budapest."],
              "subject": ["Biography", "Holocaust, Jewish (1939-1945)", "History"] }
          ]
        }
        """;

    private static final String TRENDING_JSON = """
        {
          "query": "/trending/now",
          "works": [
            { "key": "/works/OL1063267W", "title": "How to Win Friends and Influence People",
              "author_name": ["Dale Carnegie"], "cover_i": 13314878,
              "first_publish_year": 1936 }
          ]
        }
        """;

    /** Stub client returning canned responses instead of hitting the network. */
    static class StubClient extends OpenLibraryClient {
        ApiResponse trendingResponse;
        ApiResponse searchResponse;
        final List<String> coverCalls = new ArrayList<>();

        @Override
        public io.vavr.control.Try<ApiResponse> trending() {
            return io.vavr.control.Try.success(trendingResponse);
        }

        @Override
        public io.vavr.control.Try<ApiResponse> search(String query) {
            return io.vavr.control.Try.success(searchResponse);
        }

        @Override
        public Option<String> loadPosterDataUri(String posterUrl) {
            coverCalls.add(posterUrl);
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    @Test
    void searchBooksMapsTheOpenLibraryPayload() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, SEARCH_JSON, null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.searchBooks("i have lived");

        assertTrue(result.isRight());
        Book book = result.get().get(0);
        assertEquals("/works/OL8199548W", book.id());
        assertEquals("I Have Lived a Thousand Years", book.title());
        assertEquals("Livia Bitton-Jackson", book.author());
        assertEquals(1997, book.year());
        assertEquals(4.142857, book.rating(), 1e-9);
        assertEquals("I dream of enrolling in the prep school in Budapest.", book.overview());
        assertEquals("Biography · Holocaust, Jewish (1939-1945)", book.genre()); // top 2 subjects
        assertEquals("https://covers.openlibrary.org/b/id/435106-L.jpg", book.posterUrl());
    }

    @Test
    void trendingParsesTheWorksArray() {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, TRENDING_JSON, null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.fetchTrending();

        assertTrue(result.isRight());
        Book book = result.get().get(0);
        assertEquals("How to Win Friends and Influence People", book.title());
        assertEquals("Dale Carnegie", book.author());
        assertEquals(1936, book.year());
        assertEquals(0.0, book.rating(), 1e-9); // trending payload has no rating
        assertEquals("", book.overview());
        assertEquals("", book.genre()); // trending payload has no subjects
    }

    @Test
    void searchBooksWithBlankQueryFallsBackToTrending() {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, TRENDING_JSON, null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.searchBooks("  ");

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals("/works/OL1063267W", result.get().get(0).id());
    }

    @Test
    void httpFailureSurfacesAsHttpStatusError() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(500, "boom", null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.searchBooks("holocaust");

        assertTrue(result.isLeft());
        assertTrue(FetchError.uiMessage(result.getLeft()).contains("HTTP 500"));
    }

    @Test
    void unreadablePayloadSurfacesAsPayloadError() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, "<html>not json</html>", null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.searchBooks("holocaust");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void missingFieldsDefaultInsteadOfBreakingParsing() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, """
            { "docs": [
                { "key": "/works/OL1W", "author_name": [], "cover_i": 0 },
                { "key": "/works/OL2W", "title": "Has Title", "cover_i": "not-a-number" }
              ] }
            """, null);
        OpenLibraryService service = new OpenLibraryService(client);

        Either<FetchError, java.util.List<Book>> result = service.searchBooks("q");

        assertTrue(result.isRight());
        Book lenient = result.get().get(0);
        assertEquals("Untitled", lenient.title());
        assertEquals("Unknown Author", lenient.author());
        assertEquals(0, lenient.year());
        assertEquals("", lenient.posterUrl()); // cover_i 0 = no cover
        assertEquals("", lenient.genre()); // no subjects
        assertEquals(0, result.get().get(1).year()); // bad year ignored
    }

    @Test
    void posterDataUriCachesByUrl() {
        StubClient client = new StubClient();
        OpenLibraryService service = new OpenLibraryService(client);

        String cover = "https://covers.openlibrary.org/b/id/435106-L.jpg";
        Option<String> first = service.posterDataUri(cover);
        Option<String> second = service.posterDataUri(cover);

        assertTrue(first.isDefined());
        assertEquals(first.get(), second.get());
        assertEquals(1, client.coverCalls.size()); // second call hit the cache
    }
}
