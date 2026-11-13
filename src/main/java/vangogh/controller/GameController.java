package vangogh.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import build.krema.core.event.EventEmitter;
import vangogh.game.Game;
import vangogh.game.TgdbGameService;

public class GameController {
	private static final int IO_THREADS = 6;
	
	private volatile EventEmitter events;
    private final TgdbGameService service;
    private final ExecutorService io;
    
    private final AtomicInteger generation = new AtomicInteger();
    
    private record PendingPoster(int id, String dataUri) {}
    
    private final ConcurrentLinkedQueue<PendingPoster> posterQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger posterTotal = new AtomicInteger();
    private final AtomicInteger posterDelivered = new AtomicInteger();
    private final AtomicInteger posterSkipped = new AtomicInteger();
    
    public GameController() {
        this(new TgdbGameService(resolveApiKey()));
    }
    
    GameController(TgdbGameService service) {
        this.service = service;
        this.io = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "van-gogh-io");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }
    
    public List<Game> requestMovies(String kind, String query) {
        int gen = generation.incrementAndGet();
        resetPosterTracking();

        CompletableFuture<List<Game>> future = new CompletableFuture<>();
        io.submit(() -> {
            try {
                future.complete("search".equals(kind)
                        ? service.searchGames(query)
                        : service.fetchTrending());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        List<Game> Games;
        try {
        	Games = future.join();
        } catch (CompletionException e) {
            // Surface the fetch failure's message through Krema's error reply
            // instead of a bare "java.util.concurrent.CompletionException".
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        // Total is set on the UI thread BEFORE this command returns, so a
        // pollPosters issued for this request can never observe a stale count.
        List<Game> posterable = Games.stream()
                .filter(m -> m.posterUrl() != null && !m.posterUrl().isEmpty())
                .toList();
        posterTotal.set(posterable.size());

        io.submit(() -> prefetchPosters(posterable, gen));
        return Games;
    }

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
    
    private void prefetchPosters(List<Game> Games, int gen) {
        boolean first = true;
        for (Game game : Games) {
            String posterUrl = game.posterUrl();
            if (posterUrl == null || posterUrl.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                fetchAndOffer(game.id(), posterUrl, gen);
                continue;
            }
            io.submit(() -> fetchAndOffer(game.id(), posterUrl, gen));
        }
    }
    
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
	
	private static String resolveApiKey() {
        // ⚠️  Hard-coding an API key in source is a security risk — for production,
        //     load it from an env var or config file and never commit the real key.
        String apiKey = System.getenv("TGDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "bfd1d68433f6086d6cf88eab01be2aa610b50fc932d8cd19fd4965e3e29d5af9";
        }
        return apiKey;
    }

}
