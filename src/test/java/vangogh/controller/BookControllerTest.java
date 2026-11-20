package vangogh.controller;

import vangogh.book.Book;
import vangogh.book.BookResolver;
import vangogh.book.OpenLibraryBookService;
import vangogh.error.FetchError;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;
import vangogh.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Try;
import static org.junit.jupiter.api.Assertions.*;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for BookController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestBooks", ...)}. Uses a stubbed
 * {@link OpenLibraryBookService} (subclass, no real Open Library calls).
 *
 * Contract (see the class comment on {@link BookController}): the command
 * kicks off the IO-pool fetch and returns the request generation at once —
 * the UI thread never blocks on network I/O. The resolved list arrives via
 * the {@code pollBookData} drain; covers via the {@code pollBookCovers}
 * drain, which emits {@code "bookCover"} events.
 */
class BookControllerTest {

    private static final Book COVERED_BOOK = new Book(
            "OL27482W", "The Hobbit", "J.R.R. Tolkien", 1937, 4.29,
            "Fantasy", "In a hole in the ground there lived a hobbit.", 310, 482,
            "https://covers.openlibrary.org/b/id/14627509-L.jpg");

    /** Stub service whose fetch sleeps, simulating a slow Open Library round-trip. */
    static class SlowService extends OpenLibraryBookService {
        SlowService() {
            super();
        }

        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
            return fetchTrending();
        }
    }

    /** Stub service that fails the fetch, to test the error drain. */
    static class FailingService extends OpenLibraryBookService {
        FailingService() {
            super();
        }

        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            return Either.left(new FetchError.Transport(
                    "OpenLibrary trending", new RuntimeException("boom")));
        }

        @Override
        public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
            return fetchTrending();
        }
    }

    /** Stub service returning one covered book, covers served instantly. */
    static class CoveredService extends SlowService {
        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            return Either.right(java.util.List.of(COVERED_BOOK));
        }

        @Override
        public io.vavr.control.Option<String> coverDataUri(String coverUrl) {
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Open Library client with canned search replies (no network). */
    static class StubOlClient extends OpenLibraryClient {
        final String structured;
        final String free;
        int structuredCalls;

        StubOlClient(String structured, String free) {
            this.structured = structured;
            this.free = free;
        }

        @Override
        public Try<ApiResponse> searchBooksByTitleAuthor(String title, String author) {
            structuredCalls++;
            return Try.success(new ApiResponse(200, structured, null));
        }

        @Override
        public Try<ApiResponse> searchBooks(String query) {
            return Try.success(new ApiResponse(200, free, null));
        }
    }

    /** Stub service whose resolver uses the canned client, covers served instantly. */
    static class RecommendingService extends SlowService {
        private final BookResolver resolver;

        RecommendingService(BookResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public BookResolver resolver() {
            return resolver;
        }

        @Override
        public io.vavr.control.Option<String> coverDataUri(String coverUrl) {
            return io.vavr.control.Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub model call: returns a canned reply or a mapped failure. */
    static class StubLlm extends LlmClient {
        final Either<FetchError, String> result;
        int calls;

        StubLlm(Either<FetchError, String> result) {
            this.result = result;
        }

        @Override
        public Either<FetchError, String> complete(LlmConfig config, String system, String user) {
            calls++;
            return result;
        }
    }

    private static final String HOBBIT_DOC = """
            {"key":"/works/OL27482W","title":"The Hobbit",\
            "author_name":["J.R.R. Tolkien"],"first_publish_year":1937,\
            "edition_count":482,"cover_i":14627509}
            """;

    private static String docs(String... rows) {
        return "{\"docs\":[" + String.join(",", rows) + "]}";
    }

    /** Controller wired to the engine with a stubbed model call. */
    private BookController recommendController(OpenLibraryBookService service, StubLlm llm,
                                               ExecutorService io) {
        RecommendationService engine = new RecommendationService(io, llm,
                () -> Either.right(new LlmConfig("https://llm.test/v1", "k", "test-model", 10, 10)));
        return new BookController(service, io, engine);
    }

    /** Stub service returning one covered book whose cover never loads. */
    static class UncoverableService extends SlowService {
        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            return Either.right(java.util.List.of(COVERED_BOOK));
        }

        @Override
        public io.vavr.control.Option<String> coverDataUri(String coverUrl) {
            return io.vavr.control.Option.none();
        }
    }

    /** EventEmitter that captures emitted events instead of talking to a webview. */
    static class TestEventEmitter extends EventEmitter {
        final List<KremaEvent> emitted = new ArrayList<>();

        TestEventEmitter() {
            super(null);
        }

        @Override
        public void emit(String eventName, Object payload) {
            emitted.add(new KremaEvent(eventName, payload));
        }
    }

    /** Drains pollBookData until this generation's fetch resolves (2s deadline). */
    private BookController.BookDataReply drainData(BookController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        BookController.BookDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollBookData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    /** Drains pollBookCovers to its -1 completion signal (2s deadline). */
    private int drainCovers(BookController controller) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollBookCovers();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        return result;
    }

    @Test
    void requestBooksDoesNotBlockTheCallingThreadDuringFetch() {
        BookController controller = new BookController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestBooks("trending", "");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O (the UI-thread-freeze
        // regression pinned for movies and games).
        assertTrue(elapsedMs < 250,
                "requestBooks blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void fetchedBooksArriveOnTheDataDrain() throws Exception {
        BookController controller = new BookController(new CoveredService());

        int gen = controller.requestBooks("trending", "");
        BookController.BookDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("The Hobbit", reply.items().get(0).title());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        BookController controller = new BookController(new FailingService());

        int gen = controller.requestBooks("trending", "");
        BookController.BookDataReply reply = drainData(controller, gen);

        // The fetch failure's own message must surface (it becomes the page's
        // error state, same text the movie/game drains produce).
        assertEquals("error", reply.status());
        assertEquals("OpenLibrary trending failed: boom", reply.error());
    }

    @Test
    void pollBookDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        BookController controller = new BookController(new SlowService());

        int firstGen = controller.requestBooks("trending", "");
        int secondGen = controller.requestBooks("trending", "");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result —
        // two loops can poll the same one-slot drain. Whether the first poll
        // observes "pending" or "superseded" is timing-dependent; "done"
        // would mean it stole the newer request's data.
        assertNotEquals("done", controller.pollBookData(firstGen).status());

        // The newer request still delivers through the drain.
        assertEquals("done", drainData(controller, secondGen).status());
    }

    @Test
    void pollBookCoversDrainsQueuedCoversAndEmitsBookCoverEvents() throws Exception {
        BookController controller = new BookController(new CoveredService());
        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        int gen = controller.requestBooks("trending", "");
        assertEquals("done", drainData(controller, gen).status()); // bookkeeping in place

        assertEquals(-1, drainCovers(controller));

        assertEquals(1, emitter.emitted.size());
        KremaEvent e = emitter.emitted.get(0);
        assertEquals("bookCover", e.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload();
        assertEquals("OL27482W", payload.get("id"));
        assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
    }

    @Test
    void skippedCoversDoNotStallTheCoverDrain() throws Exception {
        BookController controller = new BookController(new UncoverableService());
        controller.setEventEmitter(new TestEventEmitter());

        int gen = controller.requestBooks("trending", "");
        assertEquals("done", drainData(controller, gen).status());

        // Skipped covers are counted against the total, so the drain must
        // reach the -1 completion signal promptly instead of hanging.
        assertEquals(-1, drainCovers(controller));
    }

    @Test
    void requestBooksWithRecommendKindRunsTheModelAndResolvesItsPicks() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.right(
                    "The Hobbit | J.R.R. Tolkien\nNot A Real Book | Nobody At All"));
            BookController controller = recommendController(
                    new RecommendingService(new BookResolver(
                            new StubOlClient(docs(HOBBIT_DOC), docs()))), llm, io);

            int gen = controller.requestBooks("recommend", "comforting fantasy");
            BookController.BookDataReply reply = drainData(controller, gen);

            assertEquals("done", reply.status());
            assertNull(reply.error());
            assertEquals(1, reply.items().size());   // the unresolvable pick is dropped
            assertEquals("The Hobbit", reply.items().get(0).title());
            assertEquals("J.R.R. Tolkien", reply.items().get(0).author());
            assertEquals(1937, reply.items().get(0).year());
            assertEquals(1, llm.calls);
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void recommendWithoutAQueryIsABadInputError() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            // The page never sends this (an empty box is trending), but the
            // engine must not spend a model call on nothing.
            StubLlm llm = new StubLlm(Either.right("The Hobbit | J.R.R. Tolkien"));
            BookController controller = recommendController(
                    new RecommendingService(new BookResolver(
                            new StubOlClient(docs(HOBBIT_DOC), docs()))), llm, io);

            int gen = controller.requestBooks("recommend", "  ");
            BookController.BookDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("AI recommendations need a query"), reply.error());
            assertEquals(0, llm.calls);
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelFailureSurfacesOnTheDataDrain() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.left(new FetchError.Transport(
                    "LLM request", new RuntimeException("offline"))));
            BookController controller = recommendController(
                    new RecommendingService(new BookResolver(
                            new StubOlClient(docs(HOBBIT_DOC), docs()))), llm, io);

            int gen = controller.requestBooks("recommend", "comforting fantasy");
            BookController.BookDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM request failed: offline"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aModelHttpErrorSurfacesWithItsStatus() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.left(
                    new FetchError.HttpStatus("LLM", 401, "{\"error\":\"bad key\"}")));
            BookController controller = recommendController(
                    new RecommendingService(new BookResolver(
                            new StubOlClient(docs(HOBBIT_DOC), docs()))), llm, io);

            int gen = controller.requestBooks("recommend", "comforting fantasy");
            BookController.BookDataReply reply = drainData(controller, gen);

            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("LLM failed: HTTP 401"), reply.error());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void aCatalogFailureWithNoResolvedPicksSurfacesItsError() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        try {
            StubLlm llm = new StubLlm(Either.right("The Hobbit | J.R.R. Tolkien"));
            OpenLibraryClient failing = new OpenLibraryClient() {
                @Override
                public Try<ApiResponse> searchBooksByTitleAuthor(String title, String author) {
                    return Try.success(new ApiResponse(503, "busy", "try later"));
                }
            };
            BookController controller = recommendController(
                    new RecommendingService(new BookResolver(failing)), llm, io);

            int gen = controller.requestBooks("recommend", "comforting fantasy");
            BookController.BookDataReply reply = drainData(controller, gen);

            // "The network ate everything" must not render as "no books".
            assertEquals("error", reply.status());
            assertTrue(reply.error().contains("OpenLibrary search failed: HTTP 503"),
                    reply.error());
        } finally {
            io.shutdownNow();
        }
    }
}
