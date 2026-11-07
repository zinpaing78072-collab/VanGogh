package com.entertainment.tester;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class TmdbClient {
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    // Poster size used for list cards; smaller/faster than w500 and still sharp at card size.
    static final String POSTER_SIZE = "w342";
    // Free image proxy: fetches TMDB images from its own servers and serves the bytes.
    // Fallback for networks that block image.tmdb.org (e.g. some ISPs reset its TLS).
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    // Per-poster source timeout: fail fast so the fallback source gets a turn.
    private static final Duration POSTER_TIMEOUT = Duration.ofSeconds(5);
    // Remembers across launches whether the direct image host was reachable here.
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    // Once the direct image host fails here, later posters skip straight to the proxy.
    private volatile boolean preferProxy = posterProxyPreferred();

    public TmdbClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public ApiResponse searchMovie(String query) throws Exception {
        String url = BASE + "/search/movie?api_key=" + apiKey
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&language=en-US&page=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), null);
    }

    public ApiResponse trending() throws Exception {
        String url = BASE + "/trending/movie/week?api_key=" + apiKey
                + "&language=en-US";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), null);
    }

    public static String posterPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return IMAGE_BASE + path;
    }

    /**
     * Ordered candidate URLs that can serve the raw bytes for this poster
     * (direct image host and/or the wsrv.nl proxy). posterUrl is the canonical
     * w500 direct URL as stored on Movie. Unknown hosts are not guessed at.
     */
    static List<String> posterSources(String posterUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (posterUrl == null || !posterUrl.startsWith(IMAGE_BASE + "/")) {
            return sources;
        }
        String filename = posterUrl.substring(IMAGE_BASE.length());
        String direct = "https://image.tmdb.org/t/p/" + POSTER_SIZE + filename;
        String proxied = IMAGE_PROXY + URLEncoder.encode(direct, StandardCharsets.UTF_8);
        if (proxyFirst) {
            sources.add(proxied);
            sources.add(direct);
        } else {
            sources.add(direct);
            sources.add(proxied);
        }
        return sources;
    }

    /**
     * Converts raw image bytes into a data: URI the WebView can use as an
     * &lt;img&gt; src with no network access at render time. Returns null for
     * empty input so callers can keep the placeholder.
     */
    static String toDataUri(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String mime = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
        int semi = mime.indexOf(';');
        if (semi >= 0) {
            mime = mime.substring(0, semi).trim();
        }
        if (!mime.startsWith("image/")) {
            mime = "image/jpeg";
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Downloads one poster and returns it as a data: URI, or null when every
     * candidate source fails (the page then keeps its placeholder). Direct host
     * is tried first; on total failure the preference sticks and is remembered
     * for the next launch so later posters and app starts pay no 5s toll.
     */
    public String loadPosterDataUri(String posterUrl) {
        for (String source : posterSources(posterUrl, preferProxy)) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                        .timeout(POSTER_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    String uri = toDataUri(response.body(),
                            response.headers().firstValue("content-type").orElse(null));
                    if (uri != null) {
                        rememberPosterSource(source.startsWith(IMAGE_PROXY));
                        return uri;
                    }
                }
            } catch (IOException | InterruptedException e) {
                // Try the next source; null is returned if none of them work.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /** Applies and persists a source preference; only writes when it flips. */
    private void rememberPosterSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            savePosterProxyPreference(proxied);
        }
    }

    /** True when a previous launch found the direct image host unreachable here. */
    static boolean posterProxyPreferred() {
        try {
            return Files.isRegularFile(PROXY_MARKER);
        } catch (Exception e) {
            return false;
        }
    }

    static void savePosterProxyPreference(boolean proxied) {
        try {
            Files.createDirectories(PROXY_MARKER.getParent());
            if (proxied) {
                Files.writeString(PROXY_MARKER, "proxy");
            } else {
                Files.deleteIfExists(PROXY_MARKER);
            }
        } catch (Exception e) {
            // Preference is cosmetic; never break poster loading over file IO.
        }
    }

    public static void summarize(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int total = root.get("total_results").getAsInt();
            System.out.println("  TMDB total_results: " + total);
            JsonArray results = root.getAsJsonArray("results");
            int limit = Math.min(3, results.size());
            for (int i = 0; i < limit; i++) {
                JsonObject m = results.get(i).getAsJsonObject();
                String title = m.has("title") ? m.get("title").getAsString() : "N/A";
                String date = m.has("release_date") ? m.get("release_date").getAsString() : "N/A";
                System.out.println("   - " + title + " (" + date + ")");
            }
        } catch (Exception e) {
            System.out.println("  TMDB parse error: " + e.getMessage());
        }
    }
}