package vangogh.app;

/**
 * Plain launcher for the JavaFX app. Deliberately does NOT extend
 * {@link javafx.application.Application}: classpath-based launches (VS Code's
 * Run/Debug task via exec-maven-plugin, or a bare {@code java -classpath})
 * refuse to start a main class that extends Application with
 * "JavaFX runtime components are missing, and are required to run this
 * application" because no --module-path/--add-modules flags reach the JVM
 * there. The real Application lives in {@link VanGoghBootstrap}; keeping this
 * class's name/module path unchanged keeps both launchers working.
 */
public class VanGoghApp {

    public static void main(String[] args) {
        VanGoghBootstrap.bootstrap(args);
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}