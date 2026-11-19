package vangogh.app.jcef;

import vangogh.controller.BookController;
import vangogh.controller.GameController;
import vangogh.controller.GeniusController;
import vangogh.controller.MovieController;
import vangogh.controller.MusicController;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import build.krema.core.AssetServer;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Panel;
import java.io.File;

import javax.swing.JFrame;

/**
 * JCEF shell: a plain AWT frame hosting a windowed-rendered Chromium browser
 * that runs the existing movie-page frontend. The page is served from the
 * classpath over loopback HTTP through krema-core's {@link AssetServer} — the
 * same way the Krema shell serves it, and the reason the page can use ES
 * modules (Chromium refuses module scripts from {@code file://} origins).
 * {@code movie-page/krema-shim.js} implements the {@code window.krema} API on
 * top of CEF (see {@link JcefBridge}) and no-ops when a native bridge is
 * already present, so one page works in both shells.
 *
 * <p>CEF is initialized through jcefmaven's {@link CefAppBuilder}, the
 * canonical entry point. Going around it (raw {@code CefApp.getInstance()})
 * breaks on macOS: {@code CefInitializer} is the code that explicitly loads
 * {@code libjcef.dylib}, neutralizes jcef's own {@code SystemBootstrap}
 * loader, and injects the macOS CEF args ({@code --framework-dir-path},
 * {@code --main-bundle-path}, {@code --browser-subprocess-path}); skipping it
 * leaves every {@code N_*} native unbound. The app handler must be
 * {@link MavenCefAppHandlerAdapter} (via {@code builder.setAppHandler}) — a
 * plain {@code CefAppHandlerAdapter} breaks command-line processing on macOS.
 *
 * <p>Window order matters: the browser is created first and added to the
 * frame's <em>final</em> layout, and only then is the frame shown — the
 * jcefsampleapp pattern. Showing a placeholder first and swapping the browser
 * in later (removeAll → re-add → pack → resize) re-lays-out the browser's
 * canvas after the native view attached, which on macOS leaves the page
 * misplaced inside the window.
 */
public class JcefVanGoghApp {

    /** Classpath directory the AssetServer publishes (index.html lives here). */
    private static final String ASSET_PATH = "/movie-page";
    /** Vendored jcefmaven install dir (its default install dir name). */
    private static final String INSTALL_DIR = "jcef-bundle";

    private static CefApp cefApp_;
    private static Frame frame_;

    public static void main(String[] args) throws Exception {
        // Step 1: Configure CEF via CefAppBuilder. installDir points at the
        // vendored bundle, so install() passes its completeness check offline
        // (install.lock + matching build_meta.json, no ~100 MB download).
        System.out.println("[Van Gogh JCEF] Step 1: Configuring CefAppBuilder...");
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(INSTALL_DIR));
        // The renderer subprocess must run the JVM (CefApp implements the
        // render process handler — it registers the message-router renderer
        // side that injects window.cefQuery). Sandbox helpers load only
        // libcef_sandbox.dylib and never bootstrap the JVM, so without this
        // switch queries go out but no callback ever fires (page renders,
        // cefQuery exists, invoke hangs forever). java-cef master hardcodes
        // settings.no_sandbox = true for the same reason ("sandbox is not
        // supported"); this fork does not, so pass the switch explicitly.
        builder.addJcefArgs("--no-sandbox");
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefAppState state) {
                System.out.println("[Van Gogh JCEF] CEF state changed: " + state);
                // Shutdown the app if the native CEF part is terminated.
                if (state == CefAppState.TERMINATED) System.exit(0);
            }
        });
        CefSettings settings = builder.getCefSettings();
        // Windowed rendering (the jcefsampleapp default). OSR would be built on
        // a JOGL GLCanvas whose GLProfile.initSingleton creates+destroys a
        // dummy NSWindow off the AppKit thread — modern macOS traps that in
        // NSWMWindowCoordinator with SIGILL (hs_err: _NSOrderWindow →
        // performTransactionUsingBlock). Windowed mode never touches JOGL.
        settings.windowless_rendering_enabled = false;
        File cacheDir = new File(System.getProperty("user.home"), ".van-gogh/cef-cache");
        cacheDir.mkdirs();
        settings.cache_path = cacheDir.getAbsolutePath();
        // Dev switch: enable CEF's DevTools server to inspect the page from
        // the outside (targets at http://localhost:<port>/json). Off by
        // default; set -Dvangogh.cef.debug.port=<port> or the
        // VANGOGH_CEF_DEBUG_PORT env var when debugging.
        int debugPort = debugPort();
        if (debugPort > 0) {
            settings.remote_debugging_port = debugPort;
            System.out.println("[Van Gogh JCEF] Remote debugging on port " + debugPort);
            settings.log_file = new File(cacheDir.getParentFile(), "cef-debug.log").getAbsolutePath();
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;
            System.out.println("[Van Gogh JCEF] CEF log: " + settings.log_file);
        }

        // Step 2: build() = install check (vendored dir) + CefInitializer
        // initialization: loads the native libraries, appends the macOS CEF
        // args, runs CefApp.startup, and returns the CefApp singleton.
        System.out.println("[Van Gogh JCEF] Step 2: Building CefApp (blocking)...");
        cefApp_ = builder.build();
        System.out.println("[Van Gogh JCEF] Step 2 done: CefApp initialized");

        // Step 3: Publish the frontend over loopback HTTP, exactly as the
        // Krema shell does. This is what lets the page use ES modules:
        // Chromium refuses <script type="module"> from file:// origins. The
        // page also ships its own krema-shim.js (it no-ops when a native
        // window.krema already exists), so no HTML rewriting is needed.
        System.out.println("[Van Gogh JCEF] Step 3: Starting asset server...");
        AssetServer assets = new AssetServer(ASSET_PATH);
        assets.start();
        Runtime.getRuntime().addShutdownHook(
                new Thread(assets::close, "vangogh-asset-server-close"));
        String pageUrl = assets.getUrl() + "/index.html";
        System.out.println("[Van Gogh JCEF] Serving " + ASSET_PATH + " at " + pageUrl);

        // Step 4: Client, then the message router, then the browser. The
        // router must be registered BEFORE the browser loads its page — CEF
        // injects window.cefQuery into a page only when the router already
        // exists on the client (same order as the upstream jcef test apps).
        System.out.println("[Van Gogh JCEF] Step 4: Browser init...");
        CefClient client = cefApp_.createClient();
        JcefBridge bridge = new JcefBridge(new MovieController(), new GameController(),
                new MusicController(), new GeniusController(), new BookController());
        bridge.installRouter(client);
        // Windowed rendering: osr=false (see the CefSettings comment above —
        // the OSR/JOGL path crashes on macOS during GLProfile init).
        CefBrowser browser = client.createBrowser(pageUrl, false, false);
        bridge.attachBrowser(browser);

        // Step 5: Frame LAST — build the final layout and show the window
        // once. The browser component must NOT be a direct child of the
        // Frame: java-cef derives the browser's window-relative rect by
        // walking the component's parent chain and stops only when a parent's
        // own parent is a Window. As a direct Frame child the walk never
        // stops — it translates by frame.getLocation() (screen coords), and
        // AWT child coords add the title-bar inset — so the page rendered
        // offset by the window's on-screen position (white strip top/left,
        // clipped right/bottom). An intermediate container ends the walk at
        // the right place, yielding a (0,0)-anchored window-local rect.
        System.out.println("[Van Gogh JCEF] Step 5: Building frame...");
        // JFrame (not Frame): FullScreenUtilities.setWindowCanFullScreen
        // requires a RootPaneContainer — plain AWT Frames cannot opt into
        // native macOS fullscreen (green button / Ctrl+Cmd+F). The browser
        // rect walk (see below) ends at the root pane with (0,0) offsets, so
        // the offset fix holds. JFrame IS-A Frame: field type unchanged.
        frame_ = new JFrame("Van Gogh (JCEF)");
        frame_.setLayout(new BorderLayout());
        Panel holder = new Panel(new BorderLayout());
        holder.add(browser.getUIComponent(), BorderLayout.CENTER);
        frame_.add(holder, BorderLayout.CENTER);
        frame_.setSize(1200, 800);
        frame_.setLocationRelativeTo(null);
        // Native macOS fullscreen: hides the title bar AND the menu bar (the
        // menu bar reappears on hover at the top edge). AWT windows must opt
        // in via com.apple.eawt.FullScreenUtilities — without it the green
        // button only "zooms" the window and both bars stay visible.
        // User decision (2026-11-14): the green button is the only fullscreen
        // path — no DOM toggle button (removed), no keyboard shortcut.
        // Hovering the green button long enough to pop its zoom/fullscreen
        // menu can freeze the window (accepted limitation, see the vault
        // debug record); quick aim-and-clicks are usually fine.
        enableNativeFullscreen(frame_);
        frame_.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // CefAppBuilder.build() registered a shutdown hook that
                // disposes the CefApp instance on JVM exit.
                System.exit(0);
            }
        });
        frame_.setVisible(true);

        System.out.println("[Van Gogh JCEF] Done! Window should be showing.");
    }

    /**
     * Enables native macOS fullscreen for the frame (green button, or
     * Ctrl+Cmd+F when the window is frontmost): the window moves to its own
     * Space with the title bar and menu bar hidden. Without this flag AWT
     * windows only zoom — both bars stay. Reflection keeps this file
     * compilable on non-macOS JDK builds (no com.apple.eawt there); at
     * runtime the pom passes
     * {@code --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED} because
     * the package is internal (not exported) on JDK 9+. The window must be a
     * RootPaneContainer (a JFrame — a plain java.awt.Frame throws
     * IllegalArgumentException) and must not have its native peer yet
     * (call before setVisible(true) — the flag is read at addNotify).
     */
    private static void enableNativeFullscreen(Frame frame) {
        try {
            Class<?> util = Class.forName("com.apple.eawt.FullScreenUtilities");
            util.getMethod("setWindowCanFullScreen", java.awt.Window.class, boolean.class)
                    .invoke(null, frame, true);
            System.out.println("[Van Gogh JCEF] Native fullscreen enabled (green button / Ctrl+Cmd+F)");
        } catch (Throwable t) {
            System.out.println("[Van Gogh JCEF] Native fullscreen not available: " + t);
        }
    }

    /** Debug port from -Dvangogh.cef.debug.port or VANGOGH_CEF_DEBUG_PORT; 0 = off. */
    private static int debugPort() {
        String raw = System.getProperty("vangogh.cef.debug.port");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("VANGOGH_CEF_DEBUG_PORT");
        }
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[Van Gogh JCEF] Ignoring bad debug port: " + raw);
            return 0;
        }
    }
}
