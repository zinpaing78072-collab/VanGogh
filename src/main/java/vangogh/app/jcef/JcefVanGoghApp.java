package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * JCEF-based shell for Van Gogh. Replaces Krema's system webview with a bundled
 * Chromium engine (via jcefmaven). Same controllers, same frontend HTML/JS,
 * different native shell.
 *
 * <p>macOS launch: {@code -XstartOnFirstThread} must be the first JVM argument,
 * plus {@code --add-opens java.desktop/sun.awt=ALL-UNNAMED} etc. (see the
 * {@code macos} profile in pom.xml and jcefmaven's requirements).
 */
public class JcefVanGoghApp {

    private static final String RESOURCE_PREFIX = "movie-page/";
    private static final String SHIM_RESOURCE = "jcef/krema-shim.js";

    public static void main(String[] args) throws Exception {
        // 1. Initialize JCEF (downloads CEF natives on first run via jcefmaven).
        //    windowless_rendering_enabled = true → OSR: Chromium draws into a
        //    Swing Component we can drop in a JFrame.
        CefAppBuilder builder = new CefAppBuilder();
        builder.setProgressHandler(new ConsoleProgressHandler());
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {});
        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = true;
        CefApp cefApp = builder.build();

        // 2. Extract frontend resources from classpath to a temp dir so the
        //    browser can load them via file:// (relative CSS/JS/image refs).
        //    Also injects krema-shim.js into index.html before app.js.
        File appDir;
        try {
            appDir = extractResources(RESOURCE_PREFIX);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract frontend resources", e);
        }
        injectShim(appDir);
        File indexHtml = new File(appDir, "index.html");

        // 3. Build the controllers + JCEF bridge.
        MovieController movieController = new MovieController();
        GameController gameController = new GameController();
        JcefBridge bridge = new JcefBridge(movieController, gameController);

        // 4. Swing UI on the EDT.
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Van Gogh (JCEF)");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1200, 800);
            frame.setLocationRelativeTo(null);

            CefClient client = cefApp.createClient();
            CefBrowser browser = client.createBrowser(
                    indexHtml.toURI().toString(), true, false);
            bridge.install(browser, client);

            frame.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
            frame.setVisible(true);
        });
    }

    /**
     * Copies classpath resources under {@code prefix} into a temp directory,
     * handling both exploded-classes (file:) and JAR (jar:) classpaths.
     */
    private static File extractResources(String prefix) throws IOException {
        File dir = Files.createTempDirectory("vangogh-jcef-").toFile();
        dir.deleteOnExit();

        URL res = JcefVanGoghApp.class.getClassLoader().getResource(prefix);
        if (res == null) {
            throw new java.io.FileNotFoundException(
                    "Classpath resource not found: " + prefix);
        }
        java.net.URI resUri;
        try {
            resUri = res.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Bad resource URI: " + res, e);
        }

        if ("file".equals(res.getProtocol())) {
            // Exploded classes: use the directory directly.
            return new File(resUri);
        }

        // JAR: open the JAR and copy each entry under the prefix.
        String jarUrl = res.toString();
        int bang = jarUrl.indexOf("!/");
        if (bang < 0) {
            throw new IOException("Unexpected resource URL: " + jarUrl);
        }
        String jarPath = jarUrl.substring("jar:".length(), bang);
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring("file:".length());
        }

        try (JarFile jar = new JarFile(jarPath)) {
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

    /** Copies krema-shim.js into the app dir and adds a &lt;script&gt; tag to index.html. */
    private static void injectShim(File appDir) throws IOException {
        URL shimUrl = JcefVanGoghApp.class.getClassLoader().getResource(SHIM_RESOURCE);
        if (shimUrl == null) {
            throw new java.io.FileNotFoundException("Shim resource not found: " + SHIM_RESOURCE);
        }
        File shimDest = new File(appDir, "krema-shim.js");
        try (InputStream in = shimUrl.openStream()) {
            Files.copy(in, shimDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        File html = new File(appDir, "index.html");
        String content = new String(Files.readAllBytes(html.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        // Insert the shim script tag right before app.js.
        content = content.replace("<script src=\"app.js\"></script>",
                "<script src=\"krema-shim.js\"></script>\n    <script src=\"app.js\"></script>");
        Files.write(html.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
