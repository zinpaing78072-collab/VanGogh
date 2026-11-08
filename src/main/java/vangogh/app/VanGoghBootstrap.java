package vangogh.app;

import vangogh.controller.MovieController;
import build.krema.core.Krema;

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

    private static final String ASSET_PREFIX = "/movie-page";

    /** Entry point used by {@link VanGoghApp} and by modular runs. */
    static void bootstrap(String[] args) {
        // Constructed BEFORE the builder chain: new MovieController() inside
        // .commands(...) runs while the .events(...) consumer is still unread
        // (KremaBuilder only stores it; KremaApplication.run() invokes it),
        // so an eagerly-captured EventEmitter can only be null and posters
        // would never emit. Instead the controller receives the emitter via
        // setEventEmitter, which Krema calls inside run() — before the webview
        // navigates to the page, and therefore before any JS can request movies.
        MovieController controller = new MovieController();
        Krema.app()
                .title("Van Gogh")
                .identifier("van-gogh.krema")
                .size(1200, 800)
                .debug() // right-click → Inspect in the system webview to watch events
                .prodAssets(ASSET_PREFIX) // serves src/main/resources/movie-page/index.html
                .events(controller::setEventEmitter)
                .commands(controller)
                .run(); // blocks until the window closes
    }

    public static void main(String[] args) {
        bootstrap(args);
    }
}