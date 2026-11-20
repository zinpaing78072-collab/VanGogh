package vangogh.net;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import io.vavr.control.Try;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /**
     * Transport is wrapped as a value: callers chain with {@code map}/{@code flatMap}
     * instead of {@code try}/{@code catch}. Network/HTTP failures surface as
     * {@code Try.failure} and are converted to {@code FetchError.Transport} at the
     * service seam.
     */
    public Try<ApiResponse> searchMovie(String query) {
        String url = BASE + "/search/movie?api_key=" + apiKey
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&language=en-US&page=1";
        return send(url);
    }

    public Try<ApiResponse> trending() {
        String url = BASE + "/trending/movie/week?api_key=" + apiKey
                + "&language=en-US";
        return send(url);
    }

    /**
     * One call carries the whole detail page: identity, facts, cast &amp; crew,
     * keywords, stills, videos, certifications and the similar rail all come
     * back appended to the movie object.
     */
    public Try<ApiResponse> movieDetail(int id) {
        String url = BASE + "/movie/" + id + "?api_key=" + apiKey
                + "&language=en-US"
                + "&append_to_response=credits,keywords,similar,images,videos,release_dates";
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

    /**
     * Failover chain: try each poster source (proxy / direct) as a {@link Try},
     * stop at the first success. vavr's {@code Stream} is lazy, so only sources up
     * to the first success are actually fetched. An interrupted fetch stops the
     * whole chain (interrupt flag restored) and yields {@link Option#none()}.
     */
    public Option<String> loadPosterDataUri(String posterUrl) {
        return PosterFailover.firstSuccess(posterSources(posterUrl, preferProxy), this::fetchAsUri)
                .peek(pair -> rememberPosterSource(pair._1().startsWith(IMAGE_PROXY)))
                .map(Tuple2::_2);
    }

    /**
     * Detail artwork (poster, headshots, stills, similar posters) keeps TMDB's
     * requested size instead of being re-encoded to the card size: a headshot
     * is cropped in a 2:3 card and a still runs 16:9, so the card rule would
     * make both look soft. Same proxy failover, same preference as the grid.
     */
    public Option<String> loadDetailImageDataUri(String imageUrl) {
        return PosterFailover.firstSuccess(detailImageSources(imageUrl, preferProxy), this::fetchAsUri)
                .peek(pair -> rememberPosterSource(pair._1().startsWith(IMAGE_PROXY)))
                .map(Tuple2::_2);
    }

    /**
     * Direct/proxy pair for a detail image at its original size. Direct first
     * unless a previous fetch proved the proxy necessary (the image host is
     * unreachable on some networks — see the class comment).
     */
    static List<String> detailImageSources(String imageUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (imageUrl == null || !imageUrl.startsWith(IMAGE_BASE + "/")) {
            return sources;
        }
        String proxyUrl = IMAGE_PROXY + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8);
        if (proxyFirst) {
            sources.add(proxyUrl);
            sources.add(imageUrl);
        } else {
            sources.add(imageUrl);
            sources.add(proxyUrl);
        }
        return sources;
    }

    /** One poster source fetch as a throwing function, so {@link Try} can carry it. */
    private String fetchAsUri(String source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .timeout(POSTER_TIMEOUT)
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
            throw new IllegalStateException("poster source returned HTTP " + response.statusCode());
        }
        String uri = toDataUri(response.body(),
                response.headers().firstValue("content-type").orElse(null));
        if (uri == null) {
            throw new IllegalStateException("poster body was empty");
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

    /** Cosmetic file IO stays best-effort: never break poster loading over it. */
    static void savePosterProxyPreference(boolean proxied) {
        Try.run(() -> {
            Files.createDirectories(PROXY_MARKER.getParent());
            if (proxied) {
                Files.writeString(PROXY_MARKER, "proxy");
            } else {
                Files.deleteIfExists(PROXY_MARKER);
            }
        }).onFailure(e -> {
            // Preference is cosmetic; never break poster loading over file IO.
        });
    }

    public static void summarize(String json) {
        Try.run(() -> {
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
        }).onFailure(e -> System.out.println("  TMDB parse error: " + e.getMessage()));
    }
}
