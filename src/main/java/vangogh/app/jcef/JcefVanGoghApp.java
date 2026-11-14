package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Panel;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * JCEF shell: a plain AWT frame hosting a windowed-rendered Chromium browser
 * that runs the existing movie-page frontend. The frontend is
 * unchanged — {@code jcef/krema-shim.js} implements the {@code window.krema}
 * API on top of CEF (see {@link JcefBridge}).
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

    private static final String RESOURCE_PREFIX = "movie-page/";
    private static final String SHIM_RESOURCE = "jcef/krema-shim.js";
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

        // Step 3: Extract the frontend into a fresh temp dir and inject the
        // krema shim (rewrites index.html to load krema-shim.js before app.js).
        System.out.println("[Van Gogh JCEF] Step 3: Extracting resources...");
        File appDir = extractResources(RESOURCE_PREFIX);
        injectShim(appDir);

        // Step 4: Client, then the message router, then the browser. The
        // router must be registered BEFORE the browser loads its page — CEF
        // injects window.cefQuery into a page only when the router already
        // exists on the client (same order as the upstream jcef test apps).
        System.out.println("[Van Gogh JCEF] Step 4: Browser init...");
        CefClient client = cefApp_.createClient();
        JcefBridge bridge = new JcefBridge(new MovieController(), new GameController());
        bridge.installRouter(client);
        // Windowed rendering: osr=false (see the CefSettings comment above —
        // the OSR/JOGL path crashes on macOS during GLProfile init).
        CefBrowser browser = client.createBrowser(
                "file://" + appDir.getAbsolutePath() + "/index.html", false, false);
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
        frame_ = new Frame("Van Gogh (JCEF)");
        frame_.setLayout(new BorderLayout());
        Panel holder = new Panel(new BorderLayout());
        holder.add(browser.getUIComponent(), BorderLayout.CENTER);
        frame_.add(holder, BorderLayout.CENTER);
        frame_.setSize(1200, 800);
        frame_.setLocationRelativeTo(null);
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

    /**
     * Copies the frontend resources into a fresh temp dir (never the classpath
     * dir they came from — injectShim rewrites index.html in the returned dir)
     * and returns it, ready for {@code file://} loading.
     */
    private static File extractResources(String prefix) throws IOException {
        File dir = Files.createTempDirectory("vangogh-jcef-").toFile();
        dir.deleteOnExit();

        URL res = JcefVanGoghApp.class.getClassLoader().getResource(prefix);
        if (res == null) {
            throw new FileNotFoundException("Classpath resource not found: " + prefix);
        }

        if ("file".equals(res.getProtocol())) {
            // Running from target/classes (mvn exec:exec): copy the tree.
            try {
                copyTree(new File(res.toURI()), dir);
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Bad resource URI: " + res, e);
            }
            return dir;
        }

        // Running from a packaged jar: walk the entries and copy them out.
        String jarUrl = res.toString();
        int bang = jarUrl.indexOf("!/");
        if (bang < 0) {
            throw new IOException("Unexpected resource URL: " + jarUrl);
        }
        String jarPath = jarUrl.substring("jar:".length(), bang);
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring("file:".length());
        }

        try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(prefix) && !entry.isDirectory()) {
                    Path dest = dir.toPath().resolve(name.substring(prefix.length()));
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return dir;
    }

    private static void copyTree(File src, File dest) throws IOException {
        File[] children = src.listFiles();
        if (children == null) {
            throw new FileNotFoundException("Not a directory: " + src);
        }
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (child.isDirectory()) {
                target.mkdirs();
                copyTree(child, target);
            } else {
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void injectShim(File appDir) throws IOException {
        URL shimUrl = JcefVanGoghApp.class.getClassLoader().getResource(SHIM_RESOURCE);
        if (shimUrl == null) {
            throw new FileNotFoundException("Shim resource not found: " + SHIM_RESOURCE);
        }
        File shimDest = new File(appDir, "krema-shim.js");
        try (InputStream in = shimUrl.openStream()) {
            Files.copy(in, shimDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File html = new File(appDir, "index.html");
        String content = new String(Files.readAllBytes(html.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        content = content.replace("<script src=\"app.js\"></script>",
                "<script src=\"krema-shim.js\"></script>\n    <script src=\"app.js\"></script>");
        Files.write(html.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
