package vangogh.net;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.Stream;
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

public class TgdbClient {
    private static final String BASE = "https://api.thegamesdb.net/v1/";
    private static final String IMAGE_BASE = "https://cdn.thegamesdb.net/images/original/";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration POSTER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    private volatile boolean preferProxy = posterProxyPreferred();

    public TgdbClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Transport is wrapped as a value: callers chain with {@code map}/{@code flatMap}
     * instead of {@code try}/{@code catch}. Network/HTTP failures surface as
     * {@code Try.failure} and are converted to {@code FetchError.Transport} at the
     * service seam.
     */
    public Try<ApiResponse> searchGame(String query) {
        String url = BASE + "Games/ByGameName?apikey=" + apiKey
                + "&name=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fields=players,publishers,genres,overview,rating,platform&include=boxart,platform&page=1";
        return send(url);
    }

    public Try<ApiResponse> trending() {
        String url = BASE + "Games/ByGameID?apikey=" + apiKey
                + "&id=139623,65101,114899,136323,14517,130236,142770,60527"
                + "&fields=players,publishers,genres,overview,rating,platform&include=boxart,platform&page=1";
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

    public static String posterPath(String game_id) {
        if (game_id == null || game_id.isEmpty()) {
            return null;
        }
        return IMAGE_BASE + game_id;
    }

    static List<String> posterSources(String posterUrl, boolean proxyFirst) {
        List<String> sources = new ArrayList<>();
        if (posterUrl == null || !posterUrl.startsWith(IMAGE_BASE + "boxart/front/")) {
            return sources;
        }
        String directUrl = posterUrl;
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
        String mime = contentType != null
                ? contentType.split(";")[0].trim()
                : "image/jpeg";

        if (!mime.startsWith("image/")) {
            mime = "image/jpeg";
        }

        return "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Failover chain: try each poster source (proxy / direct) as a {@link Try},
     * stop at the first success. vavr's {@code Stream} is lazy, so only sources up
     * to the first success are actually fetched. An interrupted fetch stops the
     * whole chain (interrupt flag restored) and yields {@link Option#none()}.
     */
    public Option<String> loadPosterDataUri(String posterUrl) {
        return Stream.ofAll(posterSources(posterUrl, preferProxy))
                .map(source -> Try.of(() -> Tuple.of(source, fetchAsUri(source))))
                .takeUntil(attempt -> attempt.isSuccess()
                        || attempt.getCause() instanceof InterruptedException)
                .lastOption()
                .flatMap(attempt -> attempt.toOption())
                .peek(pair -> rememberPosterSource(pair._1().startsWith(IMAGE_PROXY)))
                .map(Tuple2::_2);
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

            JsonObject data = root.getAsJsonObject("data");
            JsonArray games = data.getAsJsonArray("games");

            System.out.println("TheGamesDB total_results: " + games.size());

            JsonObject include = root.getAsJsonObject("include");
            JsonObject boxart = include.getAsJsonObject("boxart");

            JsonObject boxartData = boxart.getAsJsonObject("data");

            int limit = Math.min(3, games.size());

            for (int i = 0; i < limit; i++) {

                JsonObject game = games.get(i).getAsJsonObject();

                int id = game.get("id").getAsInt();

                String name = game.has("game_title")
                        ? game.get("game_title").getAsString()
                        : "N/A";

                System.out.println(" - " + name + " (ID: " + id + ")");

                if (boxartData.has(String.valueOf(id))) {

                    JsonArray images = boxartData.getAsJsonArray(String.valueOf(id));

                    for (int j = 0; j < images.size(); j++) {

                        JsonObject image = images.get(j).getAsJsonObject();

                        if ("boxart".equals(image.get("type").getAsString())
                                && "front".equals(image.get("side").getAsString())) {

                            String filename = image.get("filename").getAsString();

                            System.out.println("   Cover: " + filename);
                            break;
                        }
                    }
                }
            }
        }).onFailure(e -> System.out.println("TheGamesDB parse error: " + e.getMessage()));
    }
}
