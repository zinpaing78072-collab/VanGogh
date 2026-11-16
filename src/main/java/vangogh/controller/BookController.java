package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.book.Book;
import vangogh.book.OpenLibraryService;
import vangogh.error.FetchError;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Book-domain command handler, same contract as {@link GameController}:
 * {@code requestBooks} kicks off the IO-pool fetch and returns the request's
 * generation immediately (it runs on the Krema UI thread — blocking on network
 * I/O freezes the window). The resolved list lands in a one-slot holder that
 * the {@code pollBookData} drain hands the page; covers prefetch on the IO
 * pool and deliver through the {@code pollBookPosters} drain emitting
 * {@code "bookPoster"} events on the UI thread.
 */
public class BookController {

    private static final int IO_THREADS = 6;

    private volatile EventEmitter events;
    private final OpenLibraryService service;
    private final ExecutorService executor;

    private final AtomicInteger generation = new AtomicInteger();

    private record PendingPoster(String id, String dataUri) {}

    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    /** One-slot holder for the latest completed fetch (written by the IO pool, drained on the UI thread). */
    private record PendingData(int generation, Either<FetchError, java.util.List<Book>> result) {}

    private final AtomicReference<PendingData> pendingData = new AtomicReference<>();

    /** The reply the data drain hands the page (Gson-serialized; null fields omitted). */
    public record BookDataReply(int generation, String status, String error, java.util.List<Book> items) {}

    public BookController() {
        this(new OpenLibraryService());
    }

    /** Package-private constructor used by tests. */
    BookController(OpenLibraryService service) {
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
     * Kicks off the Open Library fetch on the IO pool and returns the request's
     * generation immediately — never blocks on network I/O.
     */
    @KremaCommand
    public int requestBooks(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();
        pendingData.set(null);

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
                ? Either.left(new FetchError.Transport("Open Library request", result.getCause()))
                : result.get();

        java.util.List<Book> books = either.isRight() ? either.get() : java.util.List.of();
        List<Book> posterable = List.ofAll(books)
                .filter(b -> b.posterUrl() != null && !b.posterUrl().isEmpty());

        // Total is set BEFORE the data drain can hand the page this request's
        // items, so a pollBookPosters issued after render never observes a
        // stale count.
        posterTotal.set(posterable.size());
        Future.traverse(executor, posterable, book ->
                Future.run(executor, () -> fetchAndOffer(book.id(), book.posterUrl(), gen)));

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
    public int pollBookPosters() {
        PendingPoster pending;
        while ((pending = posterQueue.poll()) != null) {
            if (events != null) {
                events.emit("bookPoster", Map.of("id", pending.id(), "dataUri", pending.dataUri()));
            }
            posterDelivered.incrementAndGet();
        }
        int outstanding = posterTotal.get() - posterDelivered.get() - posterSkipped.get();
        if (posterQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    /** Clears the cover bookkeeping for a fresh render (UI-thread only). */
    private void resetPosterTracking() {
        posterQueue.clear();
        posterTotal.set(0);
        posterDelivered.set(0);
        posterSkipped.set(0);
    }

    private void fetchAndOffer(String id, String posterUrl, int gen) {
        Option<String> uri = service.posterDataUri(posterUrl)
                .filter(ignore -> generation.get() == gen);
        if (uri.isEmpty()) {
            if (generation.get() == gen) {
                posterSkipped.incrementAndGet();
            }
            return;
        }
        posterQueue.offer(new PendingPoster(id, uri.get()));
    }
}
