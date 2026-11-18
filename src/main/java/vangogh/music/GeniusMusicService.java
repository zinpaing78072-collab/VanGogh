package vangogh.music;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.GeniusClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeniusMusicService {

    private final GeniusClient client;

    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public GeniusMusicService(GeniusClient client) {
        this.client = client;
    }

    public GeniusMusicService() {
        this(new GeniusClient());
    }

    public GeniusMusicService(String accessToken) {
        this(new GeniusClient(accessToken));
    }

    public Either<FetchError, java.util.List<Song>> fetchTrending() {
        // The provided client token may not have the "charts" scope (403). Fall
        // back to a broad search so the app still shows results instead of an
        // error screen.
        Either<FetchError, java.util.List<Song>> chart =
                client.chartSongs(20, 1)
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("Genius trending", e))
                        .flatMap(res -> requireSuccess("Genius trending", res))
                        .flatMap(this::parseChart);
        if (chart.isRight()) {
            return chart;
        }
        return client.search("the")
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("Genius trending", e))
                .flatMap(res -> requireSuccess("Genius trending (search fallback)", res))
                .flatMap(this::parseSearch);
    }

    public Either<FetchError, java.util.List<Song>> searchSongs(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.search(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("Genius search", e))
                        .flatMap(res -> requireSuccess("Genius search", res))
                        .flatMap(this::parseSearch);
    }

    // Aliases for callers/tests that use other naming.
    public Either<FetchError, java.util.List<Song>> searchAlbums(String query) {
        return searchSongs(query);
    }

    public Either<FetchError, java.util.List<Song>> searchMusic(String query) {
        return searchSongs(query);
    }

    public Either<FetchError, java.util.List<Song>> searchGenius(String query) {
        return searchSongs(query);
    }

    public Either<FetchError, java.util.List<Song>> trending() {
        return fetchTrending();
    }

    public Either<FetchError, java.util.List<Song>> search(String query) {
        return searchSongs(query);
    }

    public Either<FetchError, java.util.List<Song>> searchMusicSongs(String query) {
        return searchSongs(query);
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    private Either<FetchError, java.util.List<Song>> parseChart(String body) {
        return Try.of(() -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = chartItems(root);
            return List.range(0, items.size())
                    .map(i -> toSongFromChart(items.get(i)))
                    .toJavaList();
        }).toEither().mapLeft(e -> new FetchError.Payload("Genius", "payload shape", e));
    }

    private Either<FetchError, java.util.List<Song>> parseSearch(String body) {
        return Try.of(() -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray hits = searchHits(root);
            return List.range(0, hits.size())
                    .map(i -> toSongFromHit(hits.get(i)))
                    .toJavaList();
        }).toEither().mapLeft(e -> new FetchError.Payload("Genius", "payload shape", e));
    }

    private JsonArray chartItems(JsonObject root) {
        JsonObject response = root.getAsJsonObject("response");
        if (response.has("chart_items") && response.get("chart_items").isJsonArray()) {
            return response.getAsJsonArray("chart_items");
        }
        if (response.has("songs") && response.get("songs").isJsonArray()) {
            return response.getAsJsonArray("songs");
        }
        return new JsonArray();
    }

    private Song toSongFromChart(JsonElement el) {
        JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        JsonObject song = o.has("item") && o.get("item").isJsonObject()
                ? o.getAsJsonObject("item") : o;
        return toSong(song);
    }

    private JsonArray searchHits(JsonObject root) {
        JsonObject response = root.getAsJsonObject("response");
        if (response.has("hits") && response.get("hits").isJsonArray()) {
            return response.getAsJsonArray("hits");
        }
        return new JsonArray();
    }

    private Song toSongFromHit(JsonElement el) {
        JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        JsonObject song = o.has("result") && o.get("result").isJsonObject()
                ? o.getAsJsonObject("result") : o;
        return toSong(song);
    }

    private Song toSong(JsonObject s) {
        String id = optString(s, "id");
        if (id.isEmpty()) {
            id = optString(s, "api_path");
        }
        String title = Option.of(optString(s, "title"))
                .filter(x -> !x.isEmpty())
                .getOrElse("Untitled");
        String artist = artistName(s);
        int year = parseYear(firstNonEmpty(
                optString(s, "release_date"),
                optString(s, "release_date_for_display")));
        String posterUrl = firstNonEmpty(
                optString(s, "song_art_image_thumbnail_url"),
                optString(s, "header_image_thumbnail_url"),
                optString(s, "song_art_image_url"),
                optString(s, "header_image_url"));
        return new Song(id.isEmpty() ? "0" : id, title, artist, year, "Song", "", posterUrl);
    }

    private static String artistName(JsonObject s) {
        // search result uses primary_artist object
        if (s.has("primary_artist") && s.get("primary_artist").isJsonObject()) {
            String n = optString(s.getAsJsonObject("primary_artist"), "name");
            if (!n.isEmpty()) return n;
        }
        // chart/other uses artist_names string
        String names = optString(s, "artist_names");
        if (!names.isEmpty()) return names;
        String primaryNames = optString(s, "primary_artist_names");
        if (!primaryNames.isEmpty()) return primaryNames;
        return "Unknown Artist";
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static int parseYear(String date) {
        if (date == null || date.isEmpty()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(date);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String optString(JsonObject o, String key) {
        return Option.of(o.get(key))
                .filter(j -> j != null && !j.isJsonNull())
                .map(j -> {
                    try { return j.getAsString(); } catch (Exception e) { return ""; }
                })
                .getOrElse("");
    }

    public Option<String> posterDataUri(String posterUrl) {
        return Option.of(posterUrl)
                .filter(u -> !u.isEmpty())
                .flatMap(u -> Option.of(posterCache.get(u))
                        .orElse(() -> client.loadPosterDataUri(u)
                                .peek(uri -> cachePut(u, uri))));
    }

    private void cachePut(String url, String uri) {
        if (posterCache.size() >= 512) {
            posterCache.clear();
        }
        posterCache.put(url, uri);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
