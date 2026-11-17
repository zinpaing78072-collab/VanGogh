package vangogh.controller;

import vangogh.book.Book;
import vangogh.book.OpenLibraryBookService;
import vangogh.error.FetchError;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import static org.junit.jupiter.api.Assertions.*;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
