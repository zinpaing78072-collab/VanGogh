package vangogh.app;

import vangogh.controller.MovieController;
import build.krema.core.Krema;
import build.krema.core.event.EventEmitter;

/**
 * Krema entry point. Responsible only for the UI lifecycle: creating the
 * system-webview window, serving the movie-page HTML assets, and wiring the
 * controller and Krema IPC commands once the app is ready.
 *
 * Networking, threading, and JS↔Java delegation live in {@link MovieController}.
 * This split keeps the main class free of business logic.
 *
 * <p>macOS launch rule: {@link #run()} must be invoked on the process's first
 * thread, so the forked JVM must start with {@code -XstartOnFirstThread} as its
 * first argument (see the exec-maven-plugin config in pom.xml). Without it the
 * process runs cleanly but no window ever appears.
 */
public class VanGoghBootstrap {

    /** Captured once at startup via {@code .events(...)}; used later from
     *  background threads to stream poster events into the page. */
    static EventEmitter events;

    private static final String ASSET_PREFIX = "/movie-page";

    /** Entry point used by {@link VanGoghApp} and by modular runs. */
    static void bootstrap(String[] args) {
        Krema.app()
                .title("Van Gogh")
                .identifier("van-gogh.krema")
                .size(1200, 800)
                .debug() // right-click → Inspect in the system webview to watch events
                .prodAssets(ASSET_PREFIX) // serves src/main/resources/movie-page/index.html
                .events(ee -> events = ee)
                .commands(new MovieController(events))
                .run(); // blocks until the window closes
    }

    public static void main(String[] args) {
        bootstrap(args);
    }
}