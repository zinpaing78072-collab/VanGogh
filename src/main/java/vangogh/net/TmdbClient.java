package vangogh.net;

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
    static final String POSTER_SIZE = "w342";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration POSTER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

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

    static List<String> posterSources(String posterUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (posterUrl == null || !posterUrl.startsWith(IMAGE_BASE + "/")) {
            return sources;
        }
        String directUrl = posterUrl.replace("w500/", POSTER_SIZE + "/");
        String encoded = URLEncoder.encode(directUrl, StandardCharsets.UTF_8);
        String proxyUrl = IMAGE_PROXY + encoded;
        if (proxyFirst) {
            sources.add(proxyUrl);
            sources.add(directUrl);
        } else {
            sources.add(directUrl);
            sources.add(proxyUrl);
        }
        return sources;
    }

    static String toDataUri(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String mime = contentType != null ? contentType.split(";")[0].trim() : "image/jpeg";
        if (!mime.startsWith("image/")) {
            mime = "image/jpeg";
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

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
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private void rememberPosterSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            savePosterProxyPreference(proxied);
        }
    }

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
