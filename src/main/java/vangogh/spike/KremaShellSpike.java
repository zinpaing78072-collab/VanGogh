package vangogh.spike;

import build.krema.core.Krema;
import build.krema.core.event.EventEmitter;

/**
 * PROTOTYPE (krema spike) — throwaway code, not for merging to main.
 *
 * <p>Question this answers: can the system-webview shell (Krema 0.3.2 on WKWebView)
 * replace the JavaFX {@code WebView} path without breaking VanGogh's push pattern
 * (movie list + posters streamed into the page from a background IO pool)?
 *
 * <p>Reuses the exact same {@code movie-page/} assets as the JavaFX shell. The
 * front-end detects which bridge is present ({@code window.krema} vs {@code window.javaBridge});
 * see the bridge shim at the top of {@code app.js}.
 *
 * <p>Covers the two suspects the JavaFX path leans on:
 * <ol>
 *   <li>Async command results: {@code requestMovies} returns a
 *       {@link java.util.concurrent.CompletableFuture}; Krema delivers the reply via
 *       {@code webview_eval} from the future's completion thread (i.e. an IO pool thread).</li>
 *   <li>Backend → frontend events: posters are pushed with
 *       {@link EventEmitter#emit(String, Object)} from that same background pool — the
 *       call Krema 0.3.2 does not shield internally ("no dispatch layer"). If macOS
 *       AppKit rejects it, the window dies or posters never appear.</li>
 * </ol>
 *
 * <p>Run ({@code exec:exec}, never {@code exec:java}; JDK 25 required so {@code ${java.home}}
 * resolves to it — Maven itself may default to Temurin 21):
 * <pre>
 *   JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn compile exec:exec \
 *     &gt; /private/var/folders/tmp/krema-spike.log 2&gt;&amp;1
 * </pre>
 * Expected startup markers: {@code Creating window} → {@code Window created} →
 * {@code Setting up IPC} → {@code IPC initialized} → {@code Asset server started at …} →
 * {@code Registered N commands} → {@code Running event loop...}. Absence of a window with
 * clean logs means the {@code -XstartOnFirstThread} flag was lost from the pom.
 */
public class KremaShellSpike {

    /**
     * Captured once at startup via {@code .events(...)} — then used later from
     * background threads. Deliberately a simple static field: throwaway spike.
     */
    static EventEmitter events;

    public static void main(String[] args) {
        Krema.app()
                .title("Van Gogh — Krema Spike (PROTOTYPE)")
                .identifier("van-gogh.krema-spike")
                .size(1200, 800)
                .debug() // right-click → Inspect in the system webview to watch events
                .prodAssets("/movie-page")
                .commands(new SpikeMovieCommands())
                .events(ee -> events = ee)
                .run();
    }
}
