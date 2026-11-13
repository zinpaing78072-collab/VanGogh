package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAppHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JcefVanGoghApp {

    private static final String RESOURCE_PREFIX = "movie-page/";
    private static final String SHIM_RESOURCE = "jcef/krema-shim.js";
    private static CefApp cefApp_;
    private static Frame frame_;

    public static void main(String[] args) throws Exception {
        // Step 0: Create and show a simple placeholder window FIRST
        System.out.println("[Van Gogh JCEF] Step 0: Creating and showing window...");
        frame_ = new Frame("Van Gogh (JCEF)");
        frame_.setSize(1200, 800);
        frame_.setLocationRelativeTo(null);
        frame_.add(new Label("Starting CEF…", Label.CENTER));
        frame_.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                System.exit(0);
            }
        });
        frame_.setVisible(true);
        frame_.toFront();
        frame_.requestFocus();
        System.out.println("[Van Gogh JCEF] Step 0 done: window visible.");

        // Step 1: Configure CEF
        System.out.println("[Van Gogh JCEF] Step 1: Configuring CefSettings...");
        CefSettings settings = new CefSettings();
        // OSR mode (windowless) - remove to use windowed mode
        settings.windowless_rendering_enabled = true;
        // Set cache and other options
        settings.cache_path = "/tmp/vangogh-cef-cache";

        // Step 2: Initialize CEF (blocking call, but frame is already visible)
        System.out.println("[Van Gogh JCEF] Step 2: Initializing CEF (blocking)...");
        CefApp.addAppHandler(new CefAppHandlerAdapter(null) {
            @Override
            public void stateHasChanged(org.cef.CefApp.CefAppState state) {
                System.out.println("[Van Gogh JCEF] CEF state changed: " + state);
            }
        });
        cefApp_ = CefApp.getInstance(settings);
        System.out.println("[Van Gogh JCEF] Step 2 done: CefApp initialized");

        // Step 3: Create browser and attach to the existing frame
        System.out.println("[Van Gogh JCEF] Step 3: Extracting resources...");
        File appDir = extractResources(RESOURCE_PREFIX);
        System.out.println("[Van Gogh JCEF] Step 3: Browser init...");
        CefClient client = cefApp_.createClient();
        CefBrowser browser = client.createBrowser(
                "file://" + appDir.getAbsolutePath() + "/index.html", true, false);

        if (browser == null) {
            throw new RuntimeException("Browser creation returned null");
        }

        System.out.println("[Van Gogh JCEF] Step 4: Adding browser to frame...");
        frame_.removeAll();
        frame_.add(browser.getUIComponent(), BorderLayout.CENTER);
        frame_.pack();
        frame_.setVisible(true);
        frame_.toFront();
        frame_.requestFocus();

        System.out.println("[Van Gogh JCEF] Done! Window should be showing.");
    }

    private static File extractResources(String prefix) throws IOException {
        File dir = Files.createTempDirectory("vangogh-jcef-").toFile();
        dir.deleteOnExit();

        URL res = JcefVanGoghApp.class.getClassLoader().getResource(prefix);
        if (res == null) {
            throw new java.io.FileNotFoundException("Classpath resource not found: " + prefix);
        }
        java.net.URI resUri;
        try {
            resUri = res.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Bad resource URI: " + res, e);
        }

        if ("file".equals(res.getProtocol())) {
            return new File(resUri);
        }

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
        content = content.replace("<script src=\"app.js\"></script>",
                "<script src=\"krema-shim.js\"></script>\n    <script src=\"app.js\"></script>");
        Files.write(html.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
