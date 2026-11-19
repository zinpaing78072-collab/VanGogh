package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.book.Book;
import vangogh.book.OpenLibraryBookService;
import vangogh.error.FetchError;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The book domain's IPC handler, following the movie/game drain contract
 * exactly: {@code requestBooks} kicks off the Open Library fetch on the IO
 * pool and returns the request's generation at once; {@code pollBookData} is
 * the synchronous UI-thread data drain (Windows Krema engine.eval
 * constraint); covers prefetch on the IO pool and deliver through
 * {@code pollBookCovers}, which emits {@code "bookCover"} events on the UI
 * thread and returns −1 when nothing is pending.
 */
public class BookController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final OpenLibraryBookService service;
    private final ExecutorService executor;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingCover(String workKey, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingCover> coverQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger coverTotal = new AtomicInteger();
    private final AtomicInteger coverDelivered = new AtomicInteger();
    private final AtomicInteger coverSkipped = new AtomicInteger();

    /** One-slot holder for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private record PendingData(int generation, Either<FetchError, java.util.List<Book>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record BookDataReply(int generation, String status, String error, java.util.List<Book> items) {}

    public BookController() {
        this(new OpenLibraryBookService());
    }

    BookController(OpenLibraryBookService service) {
        this.service = service;
        this.executor = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    /**
     * Kicks off the Open Library fetch on the IO pool and returns the
     * request's generation immediately — never blocks on network I/O.
     */
    @KremaCommand
    public int requestBooks(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetCoverTracking();
        pendingData.set(null);

        // The AI toggle ships before this form's engine slice. Say so instead
        // of quietly running a direct search behind an AI-looking toggle.
        if ("recommend".equalsIgnoreCase(kind)) {
            pendingData.set(new PendingData(gen, Either.left(new FetchError.BadInput(
                    "kind", "AI recommendations are not wired for this form yet"))));
            return gen;
        }

        Future.of(executor, () -> fetchBooks(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Book>> fetchBooks(String kind, String query) {
        return "search".equals(kind)
                ? service.searchBooks(query)
                : service.fetchTrending();
    }

    /**
     * Runs on the IO pool: joins the fetch result, fans out cover prefetch,
     * and parks the outcome for the {@code pollBookData} drain. Stale
     * completions (a newer request superseded this one) are dropped.
     */
    private void deliverData(int gen, Try<Either<FetchError, java.util.List<Book>>> result) {
        if (generation.get() != gen) {
            return;
        }
        Either<FetchError, java.util.List<Book>> either = result.isFailure()
                ? Either.left(new FetchError.Transport("OpenLibrary request", result.getCause()))
                : result.get();

        java.util.List<Book> books = either.isRight() ? either.get() : java.util.List.of();
        List<Book> coverable = List.ofAll(books)
                .filter(b -> b.coverUrl() != null && !b.coverUrl().isEmpty());

        // Total is set BEFORE the data drain can hand the page this request's
        // items, so a pollBookCovers issued after render never observes a
        // stale count.
        coverTotal.set(coverable.size());
        Future.traverse(executor, coverable, book ->
                Future.run(executor, () -> fetchAndOffer(book.workKey(), book.coverUrl(), gen)));

        // Set last: the drain hands the page items only after the cover
        // bookkeeping above is in place.
        pendingData.set(new PendingData(gen, either));
    }

    /**
     * The data drain: returns the latest completed fetch for the page's own
     * generation, or a pending/superseded marker. Synchronous on the UI thread,
     * so the reply's engine.eval always fires (Windows Krema constraint).
     */
    @KremaCommand
    public BookDataReply pollBookData(int generation) {
        PendingData pending = pendingData.get();
        if (pending == null) {
            return new BookDataReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            // A newer request owns the grid; this poller should stop quietly.
            return new BookDataReply(pending.generation(), "superseded", null, null);
        }
        // Consume: the page renders what it drained; a later poll sees empty.
        // Safe without CAS — a new writer can only appear via requestBooks,
        // which runs on this same UI thread after this handler returns.
        pendingData.set(null);
        return pending.result().fold(
                error -> new BookDataReply(pending.generation(), "error", FetchError.uiMessage(error), null),
                books -> new BookDataReply(pending.generation(), "done", null, books));
    }

    @KremaCommand
    public int pollBookCovers() {
        PendingCover pending;
        while ((pending = coverQueue.poll()) != null) {
            if (events != null) {
                events.emit("bookCover", Map.of("id", pending.workKey(), "dataUri", pending.dataUri()));
            }
            coverDelivered.incrementAndGet();
        }
        int outstanding = coverTotal.get() - coverDelivered.get() - coverSkipped.get();
        if (coverQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    /** Clears the cover bookkeeping for a fresh render (UI-thread only). */
    private void resetCoverTracking() {
        coverQueue.clear();
        coverTotal.set(0);
        coverDelivered.set(0);
        coverSkipped.set(0);
    }

    private void fetchAndOffer(String workKey, String coverUrl, int gen) {
        Option<String> uri = service.coverDataUri(coverUrl)
                .filter(ignore -> generation.get() == gen);
        if (uri.isEmpty()) {
            if (generation.get() == gen) {
                coverSkipped.incrementAndGet();
            }
            return;
        }
        coverQueue.offer(new PendingCover(workKey, uri.get()));
    }
}
