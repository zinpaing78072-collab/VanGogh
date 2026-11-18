package vangogh.net;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import io.vavr.control.Try;

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

/**
 * Genius (genius.com) client: chart songs (trending) and search, plus cover
 * fetching for song_art/header images. Uses a Bearer access token for auth.
 */
public class GeniusClient {

    private static final String BASE = "https://api.genius.com";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    /** Hard-coded client access token (course demo). */
    public static final String DEFAULT_ACCESS_TOKEN =
            "5sBtZulV1wRJLfW1HafeSrqWv8SgZ0Nrg3oa6cmr-ZcAFwpIxBK_avdt2Y98vnwn";

    private final String accessToken;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private volatile boolean preferProxy = posterProxyPreferred();

    public GeniusClient() {
        this(DEFAULT_ACCESS_TOKEN);
    }

    public GeniusClient(String accessToken) {
        this.accessToken = (accessToken == null || accessToken.isBlank())
                ? DEFAULT_ACCESS_TOKEN : accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    /** Transport wrapped as a value — same seam as the other clients. */
    public Try<ApiResponse> chartSongs(int perPage, int page) {
        String url = BASE + "/charts/songs?per_page=" + perPage + "&page=" + page;
        return send(url);
    }

    public Try<ApiResponse> trending() {
        return chartSongs(20, 1);
    }

    public Try<ApiResponse> search(String query) {
        String url = BASE + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&per_page=20";
        return send(url);
    }

    public Try<ApiResponse> searchSongs(String query) {
        return search(query);
    }

    public Try<ApiResponse> searchMusic(String query) {
        return search(query);
    }

    private Try<ApiResponse> send(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return Try.success(new ApiResponse(response.statusCode(), response.body(), null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /** Cover/image URL passthrough — Genius already gives full URLs. */
    public static String imageUrl(String url) {
        return (url == null) ? "" : url;
    }

    static List<String> imageSources(String imageUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (imageUrl == null || imageUrl.isBlank()) {
            return sources;
        }
        String encoded = URLEncoder.encode(imageUrl, StandardCharsets.UTF_8);
        String proxyUrl = IMAGE_PROXY + encoded;
        if (proxyFirst) {
            sources.add(proxyUrl);
            sources.add(imageUrl);
        } else {
            sources.add(imageUrl);
            sources.add(proxyUrl);
        }
        return sources;
    }

    public Option<String> loadPosterDataUri(String imageUrl) {
        return PosterFailover.firstSuccess(imageSources(imageUrl, preferProxy), this::fetchAsUri)
                .peek(pair -> rememberSource(pair._1().startsWith(IMAGE_PROXY)))
                .map(Tuple2::_2);
    }

    private String fetchAsUri(String source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .timeout(IMAGE_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("image source returned HTTP " + response.statusCode());
        }
        String uri = toDataUri(response.body(),
                response.headers().firstValue("content-type").orElse(null));
        if (uri == null) {
            throw new IllegalStateException("image body was empty");
        }
        return uri;
    }

    private void rememberSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            savePreference(proxied);
        }
    }

    static boolean posterProxyPreferred() {
        return Try.of(() -> Files.isRegularFile(PROXY_MARKER)).getOrElse(false);
    }

    private static void savePreference(boolean proxied) {
        Try.run(() -> {
            Files.createDirectories(PROXY_MARKER.getParent());
            if (proxied) {
                Files.writeString(PROXY_MARKER, "proxy");
            } else {
                Files.deleteIfExists(PROXY_MARKER);
            }
        });
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
}
