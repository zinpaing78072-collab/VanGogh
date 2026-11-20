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

    /**
     * The recommendation engine's resolver, sharing this service's client — so
     * a controller (and its tests) can never end up with two Genius clients.
     */
    public SongResolver resolver() {
        return new SongResolver(client);
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

    /** One song with the fields the detail page needs, plus dummy media data. */
    public Either<FetchError, SongDetail> songDetail(String id) {
        return client.song(id)
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("Genius song", e))
                .flatMap(res -> requireSuccess("Genius song", res))
                .flatMap(this::parseSongDetail);
    }

    private Either<FetchError, SongDetail> parseSongDetail(String body) {
        return Try.of(() -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject song = root.getAsJsonObject("response").getAsJsonObject("song");
            return toSongDetail(song);
        }).toEither().mapLeft(e -> new FetchError.Payload("Genius", "song payload shape", e));
    }

    private SongDetail toSongDetail(JsonObject s) {
        String id = optString(s, "id");
        String title = Option.of(optString(s, "title"))
                .filter(x -> !x.isEmpty())
                .getOrElse("Untitled");
        JsonObject album = object(s, "album");
        JsonObject artist = object(s, "primary_artist");
        JsonObject stats = object(s, "stats");
        String releaseDate = firstNonEmpty(
                optString(s, "release_date"),
                optString(album, "release_date_for_display"));
        String releaseDateDisplay = firstNonEmpty(
                optString(s, "release_date_for_display"),
                optString(album, "release_date_for_display"),
                releaseDate);
        return new SongDetail(
                id.isEmpty() ? "0" : id,
                title,
                artistName(s),
                optString(album, "name"),
                releaseDate,
                parseYear(releaseDate),
                names(s, "writer_artists"),
                names(s, "producer_artists"),
                names(s, "featured_artists"),
                optString(s, "url"),
                optLong(stats, "pageviews"),
                optString(s, "lyrics_state"),
                firstNonEmpty(
                        optString(s, "song_art_image_url"),
                        optString(s, "header_image_url"),
                        optString(s, "song_art_image_thumbnail_url")),
                DummyMedia.LYRICS,
                DummyMedia.DURATION_SECONDS,
                DummyMedia.AUDIO_URL,
                relatedSongs(s),

                releaseDateDisplay,
                languageName(optString(s, "language")),
                translationCount(s),
                optString(s, "relationships_index_url"),
                credits(s),
                mediaLinks(s),
                color(s, "song_art_primary_color"),
                color(s, "song_art_secondary_color"),
                color(s, "song_art_text_color"),

                aboutText(s),
                optString(album, "url"),
                optString(album, "cover_art_url"),
                optString(artist, "image_url"),
                optString(artist, "url"),
                optBool(artist, "is_verified"),
                optString(s, "recording_location"),
                (int) optLong(stats, "contributors"),
                (int) optLong(stats, "transcribers"),
                (int) optLong(stats, "accepted_annotations"),
                (int) optLong(s, "annotation_count"),
                (int) optLong(s, "pyongs_count"));
    }

    private static JsonObject object(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject()
                ? o.getAsJsonObject(key) : new JsonObject();
    }

    /**
     * The full production bill ({@code custom_performances}): label → names,
     * in Genius' own order. Empty entries are dropped so the expander never
     * renders a blank row.
     */
    private static java.util.List<Credit> credits(JsonObject s) {
        JsonElement el = s.get("custom_performances");
        if (el == null || !el.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<Credit> rows = new java.util.ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject perf = item.getAsJsonObject();
            String label = optString(perf, "label").trim();
            java.util.List<String> names = names(perf, "artists");
            if (!label.isEmpty() && !names.isEmpty()) {
                rows.add(new Credit(label, names));
            }
        }
        return rows;
    }

    /** Outbound listen/watch links; the API never streams audio itself. */
    private static java.util.List<MediaLink> mediaLinks(JsonObject s) {
        JsonElement el = s.get("media");
        if (el == null || !el.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<MediaLink> links = new java.util.ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject m = item.getAsJsonObject();
            String url = optString(m, "url").trim();
            if (url.isEmpty()) {
                continue;
            }
            links.add(new MediaLink(
                    providerName(optString(m, "provider")),
                    optString(m, "type"),
                    url,
                    (int) optLong(m, "start")));
        }
        return links;
    }

    private static String providerName(String provider) {
        return switch (provider.trim().toLowerCase()) {
            case "soundcloud" -> "SoundCloud";
            case "youtube" -> "YouTube";
            case "spotify" -> "Spotify";
            case "apple_music", "apple" -> "Apple Music";
            case "" -> "";
            default -> provider.trim();
        };
    }

    /** Translation count only — the translated lyric texts are not in this payload. */
    private static int translationCount(JsonObject s) {
        JsonElement direct = s.get("translation_songs");
        if (direct != null && direct.isJsonArray()) {
            return direct.getAsJsonArray().size();
        }
        JsonElement rels = s.get("song_relationships");
        if (rels != null && rels.isJsonArray()) {
            for (JsonElement el : rels.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject rel = el.getAsJsonObject();
                if ("translations".equals(optString(rel, "type"))
                        && rel.has("songs") && rel.get("songs").isJsonArray()) {
                    return rel.getAsJsonArray("songs").size();
                }
            }
        }
        return 0;
    }

    private static String languageName(String code) {
        String value = code == null ? "" : code.trim();
        if (value.isEmpty()) {
            return "";
        }
        return switch (value.toLowerCase()) {
            case "en" -> "English";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "pt" -> "Portuguese";
            case "it" -> "Italian";
            case "nl" -> "Dutch";
            case "sv" -> "Swedish";
            case "pl" -> "Polish";
            case "tr" -> "Turkish";
            case "ru" -> "Russian";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh" -> "Chinese";
            case "hi" -> "Hindi";
            case "ar" -> "Arabic";
            default -> value.toUpperCase();
        };
    }

    /** Only a real hex colour reaches the page; anything else becomes empty. */
    private static String color(JsonObject o, String key) {
        String value = optString(o, key).trim();
        return value.matches("#[0-9a-fA-F]{3,8}") ? value : "";
    }

    private static boolean optBool(JsonObject o, String key) {
        return Option.of(o.get(key))
                .filter(j -> j != null && j.isJsonPrimitive())
                .map(j -> {
                    try { return j.getAsBoolean(); } catch (Exception e) { return false; }
                })
                .getOrElse(false);
    }

    /**
     * Genius' editorial "About this song" copy. It is not a plain string: the
     * payload carries a {@code {tag, children}} rich-text tree (and
     * {@code description.plain} is usually absent), so it is flattened to
     * blank-line-separated paragraphs here. Falls back to the description
     * annotation's body, which holds the same tree.
     */
    private static String aboutText(JsonObject s) {
        JsonObject description = object(s, "description");
        String flat = paragraphs(domOf(description));
        if (!flat.isEmpty()) {
            return flat;
        }
        for (JsonObject body : descriptionAnnotationBodies(s)) {
            flat = paragraphs(domOf(body));
            if (!flat.isEmpty()) {
                return flat;
            }
        }
        return optString(description, "plain").trim();
    }

    private static JsonObject domOf(JsonObject holder) {
        return holder == null ? null : object(holder, "dom");
    }

    private static java.util.List<JsonObject> descriptionAnnotationBodies(JsonObject s) {
        java.util.List<JsonObject> bodies = new java.util.ArrayList<>();
        JsonObject annotation = object(s, "description_annotation");
        JsonElement annotations = annotation.get("annotations");
        if (annotations == null || !annotations.isJsonArray()) {
            return bodies;
        }
        for (JsonElement el : annotations.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject body = object(el.getAsJsonObject(), "body");
            if (body.size() > 0) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** Flatten the rich-text tree; block tags become paragraph breaks. */
    private static String paragraphs(JsonObject dom) {
        if (dom == null) {
            return "";
        }
        JsonElement children = dom.get("children");
        if (children == null || !children.isJsonArray()) {
            return inlineText(dom).trim();
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (JsonElement child : children.getAsJsonArray()) {
            if (isBlock(child)) {
                String text = inlineText(child).trim();
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            } else {
                run.append(inlineText(child));
            }
        }
        String tail = run.toString().trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return String.join("\n\n", parts);
    }

    private static boolean isBlock(JsonElement child) {
        if (child == null || !child.isJsonObject()) {
            return false;
        }
        return switch (optString(child.getAsJsonObject(), "tag")) {
            case "p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> true;
            default -> false;
        };
    }

    private static String inlineText(JsonElement node) {
        if (node == null || node.isJsonNull()) {
            return "";
        }
        if (node.isJsonPrimitive()) {
            try {
                return node.getAsString();
            } catch (Exception e) {
                return "";
            }
        }
        if (!node.isJsonObject()) {
            return "";
        }
        JsonObject o = node.getAsJsonObject();
        if ("br".equals(optString(o, "tag"))) {
            return "\n";
        }
        JsonElement children = o.get("children");
        if (children == null || !children.isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement child : children.getAsJsonArray()) {
            sb.append(inlineText(child));
        }
        return sb.toString();
    }

    /** Artist names from an array of {@code {name: ...}} objects, empty when absent. */
    private static java.util.List<String> names(JsonObject s, String key) {
        JsonElement el = s.get(key);
        if (el == null || !el.isJsonArray()) {
            return java.util.List.of();
        }
        return List.ofAll(el.getAsJsonArray())
                .map(JsonElement::getAsJsonObject)
                .map(a -> optString(a, "name"))
                .filter(n -> !n.isEmpty())
                .toJavaList();
    }

    /**
     * Flatten Genius' song relationships into rail rows. Covers, remixes and
     * samples are the useful neighbours for a recommendation rail; translations
     * are a huge, low-signal set and are skipped.
     */
    private static java.util.List<RelatedSong> relatedSongs(JsonObject s) {
        JsonElement el = s.get("song_relationships");
        if (el == null || !el.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.Map<String, RelatedSong> rows = new java.util.LinkedHashMap<>();
        for (JsonElement group : el.getAsJsonArray()) {
            JsonObject rel = group.getAsJsonObject();
            String type = optString(rel, "type");
            String label = relationLabel(type);
            if (label.isEmpty() || !rel.has("songs") || !rel.get("songs").isJsonArray()) {
                continue;
            }
            for (JsonElement item : rel.getAsJsonArray("songs")) {
                JsonObject song = item.getAsJsonObject();
                String id = optString(song, "id");
                if (id.isEmpty() || rows.containsKey(id)) {
                    continue;
                }
                rows.put(id, new RelatedSong(
                        id,
                        Option.of(optString(song, "title")).filter(t -> !t.isEmpty()).getOrElse("Untitled"),
                        firstNonEmpty(optString(song, "artist_names"), artistName(song)),
                        label,
                        firstNonEmpty(
                                optString(song, "song_art_image_thumbnail_url"),
                                optString(song, "header_image_thumbnail_url")),
                        DummyMedia.relatedDurationSeconds(id)));
            }
        }
        return List.ofAll(rows.values()).take(8).toJavaList();
    }

    private static String relationLabel(String type) {
        return switch (type) {
            case "covered_by" -> "Cover";
            case "remixed_by" -> "Remix";
            case "sampled_in" -> "Sample";
            case "interpolated_by" -> "Interpolation";
            default -> "";
        };
    }

    private static long optLong(JsonObject o, String key) {
        return Option.of(o.get(key))
                .filter(j -> j != null && !j.isJsonNull())
                .map(j -> {
                    try { return j.getAsLong(); } catch (Exception e) { return 0L; }
                })
                .getOrElse(0L);
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

    /**
     * Row builder shared with {@link SongResolver}: a recommended card must be
     * identical to a trending or search card. Static because it only reads the
     * payload — no service state involved.
     */
    static Song toSong(JsonObject s) {
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

    static String artistName(JsonObject s) {
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
