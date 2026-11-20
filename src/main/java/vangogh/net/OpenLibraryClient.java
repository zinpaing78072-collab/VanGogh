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
 * Open Library (openlibrary.org) client: work search, weekly trending, and
 * cover fetching. No API key — the API is open. Covers come from
 * covers.openlibrary.org and load through the same direct/wsrv.nl failover
 * (and the same machine-wide preference marker) as the TMDB/TheGamesDB
 * artwork clients.
 */
public class OpenLibraryClient {
    private static final String BASE = "https://openlibrary.org/";
    private static final String COVER_BASE = "https://covers.openlibrary.org/b/id/";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration COVER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    /** Fields both search.json and trending.json accept; keeps payloads slim. */
    static final String WORK_FIELDS = String.join(",", "key", "title", "author_name",
            "first_publish_year", "cover_i", "first_sentence", "subject",
            "edition_count", "number_of_pages_median", "ratings_average");

    private final HttpClient http = HttpClient.newHttpClient();

    private volatile boolean preferProxy = posterProxyPreferred();

    /** Transport wrapped as a value — same seam as TmdbClient/TgdbClient. */
    public Try<ApiResponse> searchBooks(String query) {
        String url = BASE + "search.json?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=20&fields=" + WORK_FIELDS;
        return send(url);
    }

    /**
     * Structured lookup for the recommendation resolver: Open Library's
     * {@code title=}/{@code author=} filters, which are stricter than a
     * free-text {@code q=} search. The author filter is omitted when blank.
     */
    public Try<ApiResponse> searchBooksByTitleAuthor(String title, String author) {
        StringBuilder url = new StringBuilder(BASE)
                .append("search.json?title=")
                .append(URLEncoder.encode(title, StandardCharsets.UTF_8))
                .append("&limit=20&fields=")
                .append(WORK_FIELDS);
        if (author != null && !author.isBlank()) {
            url.append("&author=").append(URLEncoder.encode(author.trim(), StandardCharsets.UTF_8));
        }
        return send(url.toString());
    }

    public Try<ApiResponse> trending() {
        String url = BASE + "trending/weekly.json?limit=20&fields=" + WORK_FIELDS;
        return send(url);
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

    /** Cover URL for a work's numeric cover_i (empty string → empty string). */
    public static String coverPath(long coverId) {
        return COVER_BASE + coverId + "-L.jpg";
    }

    static List<String> coverSources(String coverUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (coverUrl == null || !coverUrl.startsWith(COVER_BASE)) {
            return sources;
        }
        String encoded = URLEncoder.encode(coverUrl, StandardCharsets.UTF_8);
        String proxyUrl = IMAGE_PROXY + encoded;
        if (proxyFirst) {
            sources.add(proxyUrl);
            sources.add(coverUrl);
        } else {
            sources.add(coverUrl);
            sources.add(proxyUrl);
        }
        return sources;
    }

    static String toDataUri(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String mime = contentType != null
                ? contentType.split(";")[0].trim()
                : "image/jpeg";

        if (!mime.startsWith("image/")) {
            mime = "image/jpeg";
        }

        return "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    /** Same failover chain as TgdbClient.loadPosterDataUri. */
    public Option<String> loadCoverDataUri(String coverUrl) {
        return PosterFailover.firstSuccess(coverSources(coverUrl, preferProxy), this::fetchAsUri)
                .peek(pair -> rememberCoverSource(pair._1().startsWith(IMAGE_PROXY)))
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
        String uri = toDataUri(response.body(),
                response.headers().firstValue("content-type").orElse(null));
        if (uri == null) {
            throw new IllegalStateException("cover body was empty");
        }
        return uri;
    }

    private void rememberCoverSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            saveCoverProxyPreference(proxied);
        }
    }

    /** Cosmetic file IO stays best-effort: failure is a value that defaults to false. */
    static boolean posterProxyPreferred() {
        return Try.of(() -> Files.isRegularFile(PROXY_MARKER)).getOrElse(false);
    }

    /** Cosmetic file IO stays best-effort: never break cover loading over it. */
    static void saveCoverProxyPreference(boolean proxied) {
        Try.run(() -> {
            Files.createDirectories(PROXY_MARKER.getParent());
            if (proxied) {
                Files.writeString(PROXY_MARKER, "proxy");
            } else {
                Files.deleteIfExists(PROXY_MARKER);
            }
        }).onFailure(e -> {
            // Preference is cosmetic; never break cover loading over file IO.
        });
    }
}
