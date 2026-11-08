package vangogh.spike;

import build.krema.core.KremaCommand;
import vangogh.movie.Movie;
import vangogh.movie.TmdbMovieService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PROTOTYPE (krema spike) — the {@code @KremaCommand} equivalent of the JavaFX
 * {@code JavaScriptBridge} + {@code MovieController} pair, reimplemented against
 * the same {@code TmdbMovieService}.
 *
 * <p>Deliberately kept in one flat class with duplicated key resolution: this is
 * throwaway probe code, not a refactor of {@code MovieController}.
 */
public class SpikeMovieCommands {

    private static final int IO_THREADS = 6; // same pool shape as the JavaFX shell

    private final TmdbMovieService service = new TmdbMovieService(resolveApiKey());
    private final ExecutorService io = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "krema-spike-io");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Resolves with the parsed movie list to JS as
     * {@code await window.krema.invoke('requestMovies', {kind, query})}.
     *
     * <p>Deliberately returns a {@link CompletableFuture}: the reply is then delivered
     * from this pool's threads via {@code webview_eval} instead of the main-thread
     * {@code webview_return} path — exercising Krema's async delivery from a
     * non-main thread.
     */
    @KremaCommand
    public CompletableFuture<List<Movie>> requestMovies(String kind, String query) {
        // Spike probe: proves <parameters>true</parameters> arg-name binding actually
        // works (null kind/query here would mean the compiler flag got lost).
        System.out.println("[spike] requestMovies kind=" + kind + " query='" + query + "'");
        CompletableFuture<List<Movie>> future = new CompletableFuture<>();
        io.submit(() -> {
            try {
                List<Movie> movies = "search".equals(kind)
                        ? service.searchMovies(query)
                        : service.fetchTrending();
                future.complete(movies);
                streamPosters(movies);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Streams posters into the page one per event, through
     * {@link KremaShellSpike#events}. The emit happens on an IO pool thread — the
     * exact pattern the JavaFX shell does via {@code Platform.runLater}, and the
     * thing Krema 0.3.2 does not queue for us.
     */
    private void streamPosters(List<Movie> movies) {
        for (Movie movie : movies) {
            String posterUrl = movie.posterUrl();
            if (posterUrl == null || posterUrl.isEmpty()) {
                continue;
            }
            io.submit(() -> {
                String dataUri = service.posterDataUri(posterUrl);
                if (dataUri != null && KremaShellSpike.events != null) {
                    KremaShellSpike.events.emit("poster", Map.of("id", movie.id(), "dataUri", dataUri));
                }
            });
        }
    }

    /** Same key resolution as {@code VanGoghBootstrap#resolveApiKey} (duplicated on purpose). */
    private static String resolveApiKey() {
        String apiKey = System.getenv("TMDB_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "d0269af3b7b5a40e157c5e8ae28ff0a3";
        }
        return apiKey;
    }
}
