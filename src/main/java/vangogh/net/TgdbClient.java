package vangogh.net;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TgdbClient {
	private static final String BASE = "https://api.thegamesdb.net/v1/";
    private static final String IMAGE_BASE = "https://cdn.thegamesdb.net/images/original/boxart/front/";
    static final String POSTER_SIZE = "w342";
    static final String IMAGE_PROXY = "https://wsrv.nl/?url=";
    private static final Duration POSTER_TIMEOUT = Duration.ofSeconds(5);
    private static final Path PROXY_MARKER = Path.of(System.getProperty("user.home"), ".van-gogh", "poster-proxy.conf");

    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    private volatile boolean preferProxy = posterProxyPreferred();

    public TgdbClient(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public ApiResponse searchGame(String query) throws Exception {
    	String url = BASE+"Games/ByGameName?apikey="+apiKey+"&name="+URLEncoder.encode(query, StandardCharsets.UTF_8)+"&fields=players,publishers,genres,overview,rating,platform&include=boxart,platform&page=1";


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), null);
    }
    
    public ApiResponse trending() throws Exception {
        String url = BASE+"Games/ByGameID?apikey="+apiKey+"&id=139623,65101,114899,136323,14517,130236,142770,60527&fields=players,publishers,genres,overview,rating,platform&include=boxart,platform&page=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
   
        return new ApiResponse(response.statusCode(), response.body(), null);
    }
    public static String posterPath(String game_id) {
        if (game_id == null || game_id.isEmpty()) {
            return null;
        }
        return IMAGE_BASE + game_id + "-1.jpg";
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

        String mime = contentType != null
                ? contentType.split(";")[0].trim()
                : "image/jpeg";

        if (!mime.startsWith("image/")) {
            mime = "image/jpeg";
        }

        return "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    public String loadPosterDataUri(String posterUrl) {

        for (String source : posterSources(posterUrl, preferProxy)) {

            try {

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(source))
                        .timeout(POSTER_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = http.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

                if (response.statusCode() == 200) {

                    String uri = toDataUri(
                            response.body(),
                            response.headers()
                                    .firstValue("content-type")
                                    .orElse(null)
                    );

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
    
    static boolean posterProxyPreferred() {
        try {
            return Files.isRegularFile(PROXY_MARKER);
        } catch (Exception e) {
            return false;
        }
    }
    
    private void rememberPosterSource(boolean proxied) {
        if (preferProxy != proxied) {
            preferProxy = proxied;
            savePosterProxyPreference(proxied);
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

        } catch (Exception e) {
            System.out.println("TheGamesDB parse error: " + e.getMessage());
        }
    }
    
}
