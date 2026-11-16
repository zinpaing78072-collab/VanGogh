package vangogh.controller;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import vangogh.book.Book;
import vangogh.book.OpenLibraryService;
import vangogh.error.FetchError;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for BookController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestBooks", ...)}.
 *
 * Uses a stubbed {@link OpenLibraryService} (subclass, no real Open Library
 * calls). Contract (see the class comment on {@link BookController}): the
 * command kicks off the IO-pool fetch and returns the request generation at
 * once — the UI thread never blocks on network I/O. The resolved list arrives
 * via the {@code pollBookData} drain; covers via the {@code pollBookPosters}
 * drain.
 */
class BookControllerTest {

    private static final Book HOLOCAUST = new Book(
            "/works/OL5123341W", "Holocaust", "Gerald Green", 1978, 5.0,
            "", "Drama · War", "https://covers.openlibrary.org/b/id/4442301-L.jpg");

    /** Stub service whose fetch sleeps, simulating a slow Open Library round-trip. */
    static class SlowService extends OpenLibraryService {
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

    /** Stub service returning one book, with a stubbed cover fetch. */
    static class OneBookService extends OpenLibraryService {
        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            return Either.right(java.util.List.of(HOLOCAUST));
        }

        @Override
        public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
            return fetchTrending();
        }

        @Override
        public Option<String> posterDataUri(String posterUrl) {
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    /** Stub service that fails the fetch, to test the error drain. */
    static class FailingService extends OpenLibraryService {
        @Override
        public Either<FetchError, java.util.List<Book>> fetchTrending() {
            return Either.left(new FetchError.Transport(
                    "Open Library trending", new RuntimeException("boom")));
        }

        @Override
        public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
            return fetchTrending();
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

    @Test
    void requestBooksDoesNotBlockTheCallingThreadDuringFetch() {
        BookController controller = new BookController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestBooks("trending", "");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O.
        assertTrue(elapsedMs < 250,
                "requestBooks blocked the calling thread for " + elapsedMs + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void requestBooksWithSearchKindDispatchesToSearchBooks() throws Exception {
        BookController controller = new BookController(new SlowService());

        int gen = controller.requestBooks("search", "holocaust");
        BookController.BookDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertNotNull(reply.items());
    }

    @Test
    void fetchedBooksArriveOnTheDataDrain() throws Exception {
        BookController controller = new BookController(new OneBookService());

        int gen = controller.requestBooks("trending", "");
        BookController.BookDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertEquals(1, reply.items().size());
        assertEquals("Holocaust", reply.items().get(0).title());
        assertEquals("/works/OL5123341W", reply.items().get(0).id());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        BookController controller = new BookController(new FailingService());

        int gen = controller.requestBooks("trending", "");
        BookController.BookDataReply reply = drainData(controller, gen);

        assertEquals("error", reply.status());
        assertEquals("Open Library trending failed: boom", reply.error());
    }

    @Test
    void pollBookDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        BookController controller = new BookController(new SlowService());

        int firstGen = controller.requestBooks("trending", "");
        int secondGen = controller.requestBooks("trending", "");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result.
        // "pending" vs "superseded" is timing-dependent; "done" would mean it
        // stole the newer request's data.
        assertNotEquals("done", controller.pollBookData(firstGen).status());

        assertEquals("done", drainData(controller, secondGen).status());
    }

    @Test
    void pollBookPostersDrainsQueuedCoversAndEmitsBookPosterEvents() throws Exception {
        BookController controller = new BookController(new OneBookService());

        TestEventEmitter emitter = new TestEventEmitter();
        controller.setEventEmitter(emitter);

        int gen = controller.requestBooks("trending", "");
        BookController.BookDataReply reply = drainData(controller, gen);
        assertEquals("done", reply.status()); // cover bookkeeping is in place

        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollBookPosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }

        assertEquals(-1, result);
        assertEquals(1, emitter.emitted.size());
        KremaEvent e = emitter.emitted.get(0);
        assertEquals("bookPoster", e.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload();
        assertEquals("/works/OL5123341W", payload.get("id"));
        assertTrue(((String) payload.get("dataUri")).startsWith("data:image"));
    }

    @Test
    void pollBookPostersSkippedCoversDoNotStallPolling() throws Exception {
        BookController controller = new BookController(new OpenLibraryService() {
            @Override
            public Either<FetchError, java.util.List<Book>> fetchTrending() {
                return Either.right(java.util.List.of(HOLOCAUST));
            }

            @Override
            public Either<FetchError, java.util.List<Book>> searchBooks(String query) {
                return fetchTrending();
            }

            @Override
            public Option<String> posterDataUri(String posterUrl) {
                return Option.none(); // every cover fetch fails
            }
        });
        controller.setEventEmitter(new TestEventEmitter());

        int gen = controller.requestBooks("trending", "");
        assertEquals("done", drainData(controller, gen).status());

        // Skipped covers are counted against the total, so the drain must
        // reach the -1 completion signal promptly instead of hanging.
        long deadline = System.currentTimeMillis() + 2000;
        int result = 0;
        while (result != -1 && System.currentTimeMillis() < deadline) {
            result = controller.pollBookPosters();
            if (result != -1) {
                Thread.sleep(10);
            }
        }
        assertEquals(-1, result);
    }
}
