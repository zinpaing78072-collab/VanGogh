package vangogh.net;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import io.vavr.control.Try;

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

/**
 * Open Library client: real trending (trending/now.json), search, and cover
 * fetching. See docs/music-and-book-domains.md for the upstream constraints
 * (cover 302-redirects to archive.org, so the HTTP client follows redirects).
 */
public class OpenLibraryClient {
    private static final String BASE = "https://openlibrary.org";
    private static final String COVER_BASE = "https://covers.openlibrary.org/b/id/";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration COVER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private volatile boolean preferProxy = posterProxyPreferred();

    public Try<ApiResponse> trending() {
        return send(BASE + "/trending/now.json?limit=12");
    }

    public Try<ApiResponse> search(String query) {
        return send(BASE + "/search.json?q=" + encode(query)
                + "&fields=key,title,author_name,first_publish_year,cover_i,ratings_average,first_sentence,subject&limit=12");
    }

    public static String coverUrl(String coverId) {
        if (coverId == null || coverId.isEmpty() || "0".equals(coverId)) {
            return "";
        }
        return COVER_BASE + coverId + "-L.jpg";
    }

    private Try<ApiResponse> send(String url) {
        return Try.of(() -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                })
                .map(response -> new ApiResponse(response.statusCode(), response.body(), null));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static List<String> posterSources(String posterUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (posterUrl == null || !posterUrl.startsWith(COVER_BASE)) {
            return sources;
        }
        String encoded = URLEncoder.encode(posterUrl, StandardCharsets.UTF_8);
        String proxyUrl = IMAGE_PROXY + encoded;
        if (proxyFirst) {
            sources.add(proxyUrl);
            sources.add(posterUrl);
        } else {
            sources.add(posterUrl);
            sources.add(proxyUrl);
        }
        return sources;
    }

    /**
     * Failover chain over the cover sources (direct / wsrv.nl), reusing the
     * shared proxy preference with the other image clients.
     */
    public Option<String> loadPosterDataUri(String posterUrl) {
        return PosterFailover.firstSuccess(posterSources(posterUrl, preferProxy), this::fetchAsUri)
                .peek(pair -> rememberPosterSource(pair._1().startsWith(IMAGE_PROXY)))
                .map(Tuple2::_2);
    }

    private String fetchAsUri(String source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .timeout(COVER_TIMEOUT)
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
            throw new IllegalStateException("cover source returned HTTP " + response.statusCode());
        }
        String uri = TmdbClient.toDataUri(response.body(),
                response.headers().firstValue("content-type").orElse(null));
        if (uri == null) {
            throw new IllegalStateException("cover body was empty");
        }
        return uri;
    }

    private void rememberPosterSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            savePosterProxyPreference(proxied);
        }
    }

    /** Cosmetic file IO stays best-effort: failure is a value that defaults to false. */
    static boolean posterProxyPreferred() {
        return Try.of(() -> Files.isRegularFile(PROXY_MARKER)).getOrElse(false);
    }

    private static void savePosterProxyPreference(boolean proxied) {
        Try.run(() -> {
            Files.createDirectories(PROXY_MARKER.getParent());
            if (proxied) {
                Files.writeString(PROXY_MARKER, "proxy");
            } else {
                Files.deleteIfExists(PROXY_MARKER);
            }
        });
    }
}
