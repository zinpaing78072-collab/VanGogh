package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.book.Book;
import vangogh.book.BookDetail;
import vangogh.book.OpenLibraryBookService;
import vangogh.error.FetchError;
import vangogh.llm.Form;
import vangogh.recommend.PickResolver;
import vangogh.recommend.RecommendationService;

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
    private final PickResolver<Book> resolver;
    private final RecommendationService recommendations;

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

    // ------------------------------------------------------------ detail

    /** One-slot holder for the latest completed detail fetch. */
    private record PendingDetail(int generation, Either<FetchError, BookDetail> result) {}

    private record ArtRef(String slot, String url) {}

    private record PendingArt(String slot, String dataUri) {}

    private final AtomicInteger detailGeneration = new AtomicInteger();
    private final AtomicReference<PendingDetail> pendingDetail = new AtomicReference<>();

    private final ConcurrentLinkedQueue<PendingArt> detailArtQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger detailArtTotal = new AtomicInteger();
    private final AtomicInteger detailArtDelivered = new AtomicInteger();
    private final AtomicInteger detailArtSkipped = new AtomicInteger();

    /** The detail drain's reply; artwork arrives separately as bookDetailArt events. */
    public record BookDetailReply(int generation, String status, String error, BookDetail detail) {}

    public BookController() {
        this(new OpenLibraryBookService());
    }

    /** Package-private constructor used by tests. */
    BookController(OpenLibraryBookService service) {
        this(service, newIoPool());
    }

    /** Package-private seam: lets a test inject the pool it will shut down. */
    BookController(OpenLibraryBookService service, ExecutorService executor) {
        this(service, executor, null);
    }

    /** Package-private seam: tests inject the engine and its stubbed model call. */
    BookController(OpenLibraryBookService service, ExecutorService executor,
                   RecommendationService recommendations) {
        this.service = service;
        this.executor = executor;
        this.resolver = service.resolver();
        this.recommendations = recommendations != null
                ? recommendations
                : new RecommendationService(executor);
    }

    private static ExecutorService newIoPool() {
        return Executors.newFixedThreadPool(IO_THREADS, runnable -> {
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

        Future.of(executor, () -> fetchBooks(kind, query))
                .onComplete(result -> deliverData(gen, result));
        return gen;
    }

    private Either<FetchError, java.util.List<Book>> fetchBooks(String kind, String query) {
        if ("recommend".equalsIgnoreCase(kind)) {
            return recommendations.recommend(Form.BOOK, query, resolver)
                    .map(books -> List.ofAll(books)
                            .distinctBy(Book::workKey)
                            .toJavaList());
        }
        return "search".equalsIgnoreCase(kind)
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

    // -------------------------------------------------------------- detail

    /**
     * Kicks off the book-detail fetch and returns its generation at once (the
     * UI thread never blocks on network I/O). The page polls
     * {@link #pollBookDetail(int)} until the detail or an error arrives, then
     * {@link #pollBookDetailArt()} for the streamed artwork.
     *
     * <p>Its own generation counter means opening a book cannot supersede the
     * grid request (or its cover stream) and vice versa.
     */
    @KremaCommand
    public int requestBookDetail(String workKey) {
        int gen = detailGeneration.incrementAndGet();
        pendingDetail.set(null);
        resetDetailArt();

        Future.of(executor, () -> service.bookDetail(workKey))
                .onComplete(result -> deliverDetail(gen, result));
        return gen;
    }

    /**
     * Fans out the artwork fetches. The total is set before the detail drain
     * can hand the page this request, so a poll issued right after render
     * never sees a stale count.
     */
    private void queueDetailArt(BookDetail detail, int gen) {
        java.util.List<ArtRef> refs = new java.util.ArrayList<>();
        addArtRef(refs, "cover", detail.coverUrl());
        addArtRef(refs, "author", detail.author().photoUrl());
        java.util.List<BookDetail.Edition> editions = detail.editions();
        for (int i = 0; i < editions.size(); i++) {
            addArtRef(refs, "edition:" + i, editions.get(i).coverUrl());
        }
        for (Book similar : detail.similar()) {
            addArtRef(refs, "similar:" + similar.workKey(), similar.coverUrl());
        }

        detailArtTotal.set(refs.size());
        Future.traverse(executor, io.vavr.collection.List.ofAll(refs),
                ref -> Future.run(executor, () -> fetchDetailArt(ref, gen)));
    }

    private static void addArtRef(java.util.List<ArtRef> refs, String slot, String url) {
        if (url != null && !url.isEmpty()) {
            refs.add(new ArtRef(slot, url));
        }
    }

    private void deliverDetail(int gen, Try<Either<FetchError, BookDetail>> result) {
        if (detailGeneration.get() != gen) {
            return;
        }
        Either<FetchError, BookDetail> either = result.isFailure()
                ? Either.left(new FetchError.Transport("OpenLibrary work", result.getCause()))
                : result.get();

        either.peek(detail -> queueDetailArt(detail, gen));
        pendingDetail.set(new PendingDetail(gen, either));
    }

    /**
     * The detail drain: the latest completed fetch for the page's generation,
     * or a pending/superseded marker. Synchronous on the UI thread.
     */
    @KremaCommand
    public BookDetailReply pollBookDetail(int generation) {
        PendingDetail pending = pendingDetail.get();
        if (pending == null) {
            return new BookDetailReply(generation, "pending", null, null);
        }
        if (pending.generation() > generation) {
            return new BookDetailReply(pending.generation(), "superseded", null, null);
        }
        pendingDetail.set(null);
        return pending.result().fold(
                error -> new BookDetailReply(pending.generation(), "error",
                        FetchError.uiMessage(error), null),
                detail -> new BookDetailReply(pending.generation(), "done", null, detail));
    }

    /**
     * The artwork drain: emits one {@code bookDetailArt} event per queued
     * image ({@code {slot, dataUri}}) and returns the outstanding count, or −1
     * when the stream for this detail is complete.
     */
    @KremaCommand
    public int pollBookDetailArt() {
        PendingArt pending;
        while ((pending = detailArtQueue.poll()) != null) {
            if (events != null) {
                events.emit("bookDetailArt",
                        Map.of("slot", pending.slot(), "dataUri", pending.dataUri()));
            }
            detailArtDelivered.incrementAndGet();
        }
        int outstanding = detailArtTotal.get() - detailArtDelivered.get() - detailArtSkipped.get();
        if (detailArtQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    private void resetDetailArt() {
        detailArtQueue.clear();
        detailArtTotal.set(0);
        detailArtDelivered.set(0);
        detailArtSkipped.set(0);
    }

    private void fetchDetailArt(ArtRef ref, int gen) {
        Option<String> uri = service.detailImageDataUri(ref.url())
                .filter(ignore -> detailGeneration.get() == gen);
        if (uri.isEmpty()) {
            if (detailGeneration.get() == gen) {
                detailArtSkipped.incrementAndGet();
            }
            return;
        }
        detailArtQueue.offer(new PendingArt(ref.slot(), uri.get()));
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
