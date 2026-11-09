package vangogh.app;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Windows-only startup workaround for krema-core 0.3.2 (no newer version is
 * published on Maven Central yet — this matches the fix that landed in krema
 * master's {@code NativeLibraryLoader}, "pre-loading companion" files).
 *
 * <p>The problem: Krema's {@code NativeLibraryLoader} extracts only
 * {@code webview.dll} from the JAR into a fresh temp directory, then opens it
 * with {@code SymbolLookup.libraryLookup}. On Windows, {@code LoadLibraryW}
 * does <em>not</em> resolve dependencies from the DLL's own directory, and that
 * temp directory is not on the loader search path either. Our webview.dll (a
 * MinGW/MSYS2 build that statically links the WebView2 loader and CRT) imports
 * {@code libwinpthread-1.dll}, so the load fails with
 * {@code ERROR_MOD_NOT_FOUND} and startup dies with
 * {@code SymbolLookup: Cannot open library: ...\webview.dll}.
 *
 * <p>The fix: pre-load {@code libwinpthread-1.dll} — which ships in the
 * krema-core JAR next to webview.dll — via {@link System#load} BEFORE Krema
 * loads webview.dll. A successfully loaded module registers in the process
 * module list, and the Windows loader binds later dependency requests by base
 * name to an already-loaded module, so the subsequent webview.dll load
 * resolves. Drop this class once the project can bump to a krema release that
 * pre-loads companions itself (0.3.3+).
 *
 * <p>macOS/Linux: no-op — those loaders find dylibs/so via rpath/search paths
 * and krema-core 0.3.2 already works there.
 */
final class AppNativePreload {

    private AppNativePreload() {}

    static void run() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }

        // Directory name must mirror krema's JAR layout: /native/windows/<arch>/
        String arch = System.getProperty("os.arch", "");
        String archDir;
        switch (arch) {
            case "amd64", "x86_64" -> archDir = "x86_64";
            case "aarch64", "arm64" -> archDir = "aarch64";
            default -> {
                // Unknown arch: don't guess — let Krema's own loader produce its
                // (already descriptive) error instead of loading the wrong build.
                return;
            }
        }

        String resource = "/native/windows/" + archDir + "/libwinpthread-1.dll";
        try (InputStream in = AppNativePreload.class.getResourceAsStream(resource)) {
            if (in == null) {
                // Companion absent from this JAR: nothing to pre-load; Krema's
                // loader will surface the real error if webview.dll needs it.
                System.out.println("[Van Gogh] " + resource + " not on classpath, skipping preload");
                return;
            }

            // Resolve to a real path (avoids 8.3 short-name weirdness, same as
            // krema's loader does), extract, then System.load. Loading a DLL
            // with System.load keeps it alive for the app classloader's
            // lifetime, which is exactly what we need for dependency binding.
            Path tempDir = Files.createTempDirectory("krema-companion-").toRealPath();
            tempDir.toFile().deleteOnExit();

            Path dll = tempDir.resolve("libwinpthread-1.dll");
            Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
            dll.toFile().deleteOnExit();

            System.load(dll.toAbsolutePath().toString());
            System.out.println("[Van Gogh] Pre-loaded " + resource
                    + " (krema-core 0.3.2 Windows companion workaround)");
        } catch (Exception e) {
            // Best-effort: if this fails, let Krema's own loader attempt proceed
            // and report its usual diagnostic rather than aborting startup here.
            System.out.println("[Van Gogh] Warning: could not pre-load libwinpthread-1.dll: " + e);
        }
    }
}
