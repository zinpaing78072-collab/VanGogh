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
 * MusicBrainz WS2 client for release-group search plus Cover Art Archive
 * (CAA) cover fetching. See docs/music-and-book-domains.md for the upstream
 * constraints this encodes (mandatory User-Agent, redirect-following for
 * covers, no popularity data so "trending" is a curated MBID list).
 */
public class MusicBrainzClient {
    private static final String BASE = "https://musicbrainz.org/ws/2";
    private static final String COVER_BASE = "https://coverartarchive.org/release-group/";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration COVER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    /** MusicBrainz 503s requests without an identifying User-Agent. */
    private static final String USER_AGENT = "VanGogh/0.1 (https://github.com/zinpaing78072-collab/VanGogh)";

    /** MusicBrainz's documented limit: ~1 request/second average; bursts answer 503. */
    static final long MIN_REQUEST_INTERVAL_MS = 1100;
    static final long THROTTLE_RETRY_BACKOFF_MS = 2000;

    private long lastRequestMillis;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private volatile boolean preferProxy = posterProxyPreferred();

    public Try<ApiResponse> searchReleaseGroups(String query) {
        String url = BASE + "/release-group?query=" + encode(query) + "&fmt=json&limit=12";
        return send(url);
    }

    /** Fetches release-groups by MBID — the curated trending showcase. */
    public Try<ApiResponse> releaseGroupsByMbids(List<String> mbids) {
        String query = String.join(" OR ", mbids.stream().map(id -> "rgid:" + id).toList());
        String url = BASE + "/release-group?query=" + encode(query) + "&fmt=json&limit=" + mbids.size();
        return send(url);
    }

    public static String coverUrl(String releaseGroupMbid) {
        if (releaseGroupMbid == null || releaseGroupMbid.isEmpty()) {
            return "";
        }
        return COVER_BASE + releaseGroupMbid + "/front-250";
    }

    private Try<ApiResponse> send(String url) {
        try {
            return Try.success(doSend(url));
        } catch (InterruptedException e) {
            // vavr treats InterruptedException as fatal (Try.of rethrows it
            // sneakily) — catch at the boundary, restore the flag, carry a value.
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    private ApiResponse doSend(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = sendOnce(request);
        if (response.statusCode() == 503) {
            // Throttled: back off and try once more before surfacing the error.
            Thread.sleep(THROTTLE_RETRY_BACKOFF_MS);
            response = sendOnce(request);
        }
        return new ApiResponse(response.statusCode(), response.body(), null);
    }

    private HttpResponse<String> sendOnce(HttpRequest request) throws IOException, InterruptedException {
        pace();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Serializes MBZ requests to the documented ~1/s pace (IO-pool threads only). */
    private void pace() throws InterruptedException {
        synchronized (this) {
            long wait = lastRequestMillis + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastRequestMillis = System.currentTimeMillis();
        }
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
     * Failover chain over the CAA sources (direct / wsrv.nl), reusing the
     * shared proxy preference with the TMDB/TheGamesDB clients — the machine's
     * TLS health against image hosts is the same condition for all of them.
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
