package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates movie fetching and page updates under Krema. Holds the TMDB
 * service, the background thread pool, the {@link EventEmitter} for pushing
 * posters into the page, and the generation counter that prevents stale poster
 * results from overwriting newer renders.
 *
 * <h2>Windows constraint (krema-core 0.3.2): UI-thread-only delivery</h2>
 *
 * Krema delivers async command replies (commands returning
 * {@code CompletableFuture}) and events by calling {@code engine.eval(...)}
 * from whatever thread finished the work. On Windows, {@code webview_eval}
 * maps to WebView2's {@code ExecuteScript}, which is silently dropped when
 * called off the UI thread — the webview C library's own binding-reply helper
 * ({@code resolve()}) wraps every reply in {@code dispatch()} for exactly this
 * reason, but Krema's async path and {@link EventEmitter} bypass it. macOS is
 * unaffected (eval is marshaled to the main queue there). Verified empirically
 * against this stack: a background-thread eval never runs, while the same eval
 * from a command handler — which Krema dispatches onto the UI thread — works.
 *
 * <p>Therefore every call that reaches the page must originate on the UI
 * thread, and a command handler is the only UI-thread entry point app code
 * gets:
 *
 * <ul>
 *   <li>{@link #requestMovies} is a synchronous command. The TMDB fetch runs
 *       on the IO pool; the UI thread only waits on it (~0.3–1 s) and Krema
 *       delivers the reply via {@code webview_return}, which dispatches safely
 *       on every platform.</li>
 *   <li>Posters are prefetched on the IO pool into {@link #posterQueue};
 *       {@link #pollPosters} — invoked from the page — drains the queue and
 *       emits the {@code "poster"} events from the UI thread. The page polls
 *       until it returns {@code -1} (nothing pending).</li>
 * </ul>
 *
 * Drop this dance once the project can move to a Krema release whose eval is
 * thread-safe on Windows.
 */
public class MovieController {

    private static final int IO_THREADS = 6;

    // volatile: written once from the startup thread via {@link #setEventEmitter}
    // (runs inside Krema's run(), before any page can invoke commands), then
    // read from UI-thread command handlers and IO pool threads.
    private volatile EventEmitter events;
    private final TmdbMovieService service;
    private final ExecutorService io;

    // Bumped on every render request; IO threads discard any poster work for a
    // superseded request — no delivery and no bookkeeping — so a slow fetch can
    // never stall a newer render's poster polling.
    private final AtomicInteger generation = new AtomicInteger();

    /** A poster whose prefetch finished; waiting for the UI thread to emit it. */
    private record PendingPoster(int id, String dataUri) {}

    // Poster bookkeeping. requestMovies and pollPosters both run on the UI
    // thread (Krema dispatches command handlers there), so they are serialized
    // with each other; IO threads only offer to the queue and touch the
    // generation-checked counters.
    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();

    public MovieController() {
        this(new TmdbMovieService(resolveApiKey()));
    }

    /** Package-private seam for tests: inject a stubbed {@link TmdbMovieService}. */
    MovieController(TmdbMovieService service) {
        this.service = service;
        this.io = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Krema calls the {@code .events(...)} consumer inside {@code run()} once the
     * {@link EventEmitter} exists — before the window navigates to the page, so
     * before any JS can invoke {@code requestMovies}. Wire the emitter there
     * instead of constructing this class inside the builder chain, where the
     * emitter cannot exist yet (capturing it eagerly yields a permanent {@code null}).
     */
    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    /**
     * Krema IPC command. Called from JS via
     * {@code await window.krema.invoke("requestMovies", { kind, query })} and
     * resolves synchronously with the parsed movie list.
     *
     * <p>Synchronous on purpose — see the class comment. The TMDB fetch itself
     * runs on the IO pool; the UI thread only waits for it, so its reply is
     * never delivered from a background thread. Poster prefetch is kicked off
     * in the background; the page collects those through {@link #pollPosters}.
     */
    @KremaCommand
    public List<Movie> requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();

        CompletableFuture<List<Movie>> future = new CompletableFuture<>();
        io.submit(() -> {
            try {
                future.complete("search".equals(kind)
                        ? service.searchMovies(query)
                        : service.fetchTrending());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        List<Movie> movies;
        try {
            movies = future.join();
        } catch (CompletionException e) {
            // Surface the fetch failure's message through Krema's error reply
            // instead of a bare "java.util.concurrent.CompletionException".
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        // Total is set on the UI thread BEFORE this command returns, so a
        // pollPosters issued for this request can never observe a stale count.
        List<Movie> posterable = movies.stream()
                .filter(m -> m.posterUrl() != null && !m.posterUrl().isEmpty())
                .toList();
        posterTotal.set(posterable.size());

        io.submit(() -> prefetchPosters(posterable, gen));
        return movies;
    }

    /**
     * Krema IPC command, polled by the page after each render. Drains the
     * poster prefetch queue and emits one {@code "poster"} event per entry —
     * running on the UI thread, which is the only place an eval reaches the
     * page on Windows (see the class comment).
     *
     * @return how many posters are still outstanding for the current request,
     *         or {@code -1} once nothing is pending and nothing more can
     *         arrive — the page stops polling at that point.
     */
    @KremaCommand
    public int pollPosters() {
        PendingPoster pending;
        while ((pending = posterQueue.poll()) != null) {
            if (events != null) {
                events.emit("poster", Map.of("id", pending.id(), "dataUri", pending.dataUri()));
            }
            posterDelivered.incrementAndGet();
        }
        int outstanding = posterTotal.get() - posterDelivered.get() - posterSkipped.get();
        if (posterQueue.isEmpty() && outstanding <= 0) {
            return -1;
        }
        return Math.max(outstanding, 0);
    }

    /** Clears the poster bookkeeping for a fresh render (UI-thread only). */
    private void resetPosterTracking() {
        posterQueue.clear();
        posterTotal.set(0);
        posterDelivered.set(0);
        posterSkipped.set(0);
    }

    /**
     * Prefetches every poster into {@link #posterQueue} on the IO pool. The
     * FIRST poster acts as the connectivity probe: after its host decision no
     * other fetch repeats a dead 5s attempt (see
     * vangogh.net.TmdbClient#loadPosterDataUri), so the rest go out over the
     * live host.
     */
    private void prefetchPosters(List<Movie> movies, int gen) {
        boolean first = true;
        for (Movie movie : movies) {
            String posterUrl = movie.posterUrl();
            if (posterUrl == null || posterUrl.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                fetchAndOffer(movie.id(), posterUrl, gen);
                continue;
            }
            io.submit(() -> fetchAndOffer(movie.id(), posterUrl, gen));
        }
    }

    /**
     * Loads one poster off-thread and queues it for the UI thread. Superseded
     * fetches (generation moved on) deliver nothing and count nothing, so
     * {@link #pollPosters} never waits on them; a fetch that yields no URI is
     * skipped and accounted, so polling still terminates.
     */
    private void fetchAndOffer(int id, String posterUrl, int gen) {
        String uri = service.posterDataUri(posterUrl);
        if (generation.get() != gen) {
            return;
        }
        if (uri == null) {
            posterSkipped.incrementAndGet();
            return;
        }
        posterQueue.offer(new PendingPoster(id, uri));
    }

    /** Same key resolution as the old {@code VanGoghApp#resolveApiKey}. */
    private static String resolveApiKey() {
        // ⚠️  Hard-coding an API key in source is a security risk — for production,
        //     load it from an env var or config file and never commit the real key.
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "d0269af3b7b5a40e157c5e8ae28ff0a3";
        }
        return apiKey;
    }
}
