package vangogh.app;

import vangogh.controller.MovieController;
import build.krema.core.Krema;

/**
 * Single entry point for the Van Gogh Krema app. Deliberately does NOT extend any
 * JavaFX class: classpath-based launches (VS Code's Run/Debug task,
 * exec-maven-plugin, or a bare {@code java -classpath}) work without
 * --module-path/--add-modules flags.
 *
 * <p>macOS launch rule: {@code main()} runs on the process's first thread, so the
 * forked JVM must start with {@code -XstartOnFirstThread} as its first argument
 * (see the exec-maven-plugin config in pom.xml). Without it the process runs
 * cleanly but no window ever appears.
 */
public class VanGoghApp {

    private static final String ASSET_PREFIX = "/movie-page";

    public static void main(String[] args) {
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
}