package vangogh.app;

/**
 * Plain launcher for the Krema app. Deliberately does NOT extend any
 * JavaFX class: classpath-based launches (VS Code's Run/Debug task,
 * exec-maven-plugin, or a bare {@code java -classpath}) work without
 * --module-path/--add-modules flags. The real UI lifecycle lives in
 * {@link VanGoghBootstrap}, which builds the Krema window and runs the
 * system-webview event loop.
 */
public class VanGoghApp {

    public static void main(String[] args) {
        VanGoghBootstrap.bootstrap(args);
    }
}