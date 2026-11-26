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

    /**
     * The chart route the Genius website itself calls. The public API's
     * {@code /charts/songs} answers 403 "Action forbidden for current scope" for
     * ordinary client tokens, so trending reads this route first.
     */
    private static final String WEB_CHART = "https://genius.com/api/songs/chart";

    /** Chart window for trending: {@code day}, {@code week}, {@code month}, {@code all_time}. */
    private static final String CHART_PERIOD = "day";

    /**
     * genius.com answers 403 to requests without a browser-like User-Agent (the
     * web chart route is behind that check), so every request carries one.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 VanGogh/0.1";

    private static final String ACCESS_TOKEN_ENV = "GENIUS_ACCESS_TOKEN";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    /** Fallback client access token (course demo); set GENIUS_ACCESS_TOKEN to override. */
    public static final String DEFAULT_ACCESS_TOKEN =
            "5sBtZulV1wRJLfW1HafeSrqWv8SgZ0Nrg3oa6cmr-ZcAFwpIxBK_avdt2Y98vnwn";

    private final String accessToken;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private volatile boolean preferProxy = posterProxyPreferred();

    public GeniusClient() {
        this(System.getenv(ACCESS_TOKEN_ENV));
    }

    public GeniusClient(String accessToken) {
        this.accessToken = (accessToken == null || accessToken.isBlank())
                ? DEFAULT_ACCESS_TOKEN : accessToken.trim();
    }

    public String getAccessToken() {
        return accessToken;
    }

    /** Transport wrapped as a value — same seam as the other clients. */
    public Try<ApiResponse> chartSongs() {
        return sendWeb(chartUrl()).flatMap(web ->
                isChartPayload(web) ? Try.success(web) : sendAuthenticatedChart(web));
    }

    /**
     * The public website chart route, not the API's {@code /charts/songs}: the
     * latter rejects ordinary client tokens with 403 "Action forbidden for
     * current scope", which left the trending grid empty.
     */
    static String chartUrl() {
        return WEB_CHART + "?page=1&per_page=20&time_period=" + CHART_PERIOD;
    }

    /**
     * Fallback for tokens whose scope allows the documented chart API. When that
     * also fails, the web route's response is reported: it is the real reason
     * trending is empty (usually a Cloudflare challenge).
     */
    private Try<ApiResponse> sendAuthenticatedChart(ApiResponse webAttempt) {
        return send(BASE + "/charts/songs?per_page=20&page=1")
                .map(api -> isChartPayload(api) ? api : webAttempt);
    }

    static boolean isChartPayload(ApiResponse res) {
        return res != null
                && res.isSuccess()
                && res.body != null
                && res.body.contains("\"chart_items\"");
    }

    public Try<ApiResponse> trending() {
        return chartSongs();
    }

    public Try<ApiResponse> search(String query) {
        String url = BASE + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&per_page=20";
        return send(url);
    }

    /** One song by its Genius id — the detail page's data source. */
    public Try<ApiResponse> song(String id) {
        String path = (id == null) ? "" : URLEncoder.encode(id.trim(), StandardCharsets.UTF_8);
        return send(BASE + "/songs/" + path);
    }

    public Try<ApiResponse> searchSongs(String query) {
        return search(query);
    }

    public Try<ApiResponse> searchMusic(String query) {
        return search(query);
    }

    private Try<ApiResponse> send(String url) {
        return send(url, true);
    }

    /** genius.com web routes take no token — the Bearer header is API-only. */
    private Try<ApiResponse> sendWeb(String url) {
        return send(url, false);
    }

    private Try<ApiResponse> send(String url, boolean authenticated) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET();
            if (authenticated) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
