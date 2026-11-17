package vangogh.music;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.MusicBrainzClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Album fetch pipeline over MusicBrainz, following the same {@link Either}
 * spine as the movie/game services: transport failures become
 * {@link FetchError.Transport}, non-2xx becomes {@link FetchError.HttpStatus},
 * an unreadable payload becomes {@link FetchError.Payload}.
 */
public class MusicBrainzService {

    /**
     * The trending showcase. MusicBrainz has no popularity data — like the
     * games slice's fixed IDs, these are curated canonical albums, and the
     * parsed results are reordered to this seed order (the rgid OR query
     * returns them in arbitrary relevance order).
     */
    private static final List<String> TRENDING_RGIDS = List.of(
            "9162580e-5df4-32de-80cc-f45a8d8a9b1d", // Abbey Road — The Beatles
            "f5093c06-23e3-404f-aeaa-40f72885ee3a", // The Dark Side of the Moon — Pink Floyd
            "f32fab67-77dd-3937-addc-9062e28e4c37", // Thriller — Michael Jackson
            "416bb5e5-c7d1-3977-8fd7-7c9daf6c2be6", // Rumours — Fleetwood Mac
            "1b022e01-4da6-387b-8658-8678046e4cef", // Nevermind — Nirvana
            "6b47c9a0-b9e1-3df9-a5e8-50a6ce0dbdbd", // A Night at the Opera — Queen
            "8e8a594f-2175-38c7-a871-abb68ec363e7", // Kind of Blue — Miles Davis
            "d3bc1a64-7561-3787-b680-0003aa50f8f1", // Back in Black — AC/DC
            "77075d90-12a9-3414-9daf-32ed15a8e318", // Hotel California — Eagles
            "e4174758-d333-4a8e-a31f-dd0edd51518e", // 21 — Adele
            "39b22944-7503-3937-8bba-09b17281cc6a"  // Born to Run — Bruce Springsteen
    );

    private final MusicBrainzClient client;

    // Cover data URIs by cover URL, bounded like the movie/game caches.
    // Stays a concurrent map because vavr has no concurrent map.
    private final Map<String, String> posterCache = new ConcurrentHashMap<>();

    public MusicBrainzService(MusicBrainzClient client) {
        this.client = client;
    }

    public MusicBrainzService() {
        this(new MusicBrainzClient());
    }

    public Either<FetchError, java.util.List<Album>> fetchTrending() {
        return client.releaseGroupsByMbids(TRENDING_RGIDS.toJavaList())
                .toEither()
                .mapLeft(e -> (FetchError) new FetchError.Transport("MusicBrainz trending", e))
                .flatMap(res -> requireSuccess("MusicBrainz trending", res))
                .flatMap(this::parseRows)
                .map(albums -> albums.sorted(Comparator.comparingInt(
                        a -> TRENDING_RGIDS.indexOf(a.id()))))
                .map(List::toJavaList);
    }

    public Either<FetchError, java.util.List<Album>> searchAlbums(String query) {
        return (query == null || query.trim().isEmpty())
                ? fetchTrending()
                : client.searchReleaseGroups(query.trim())
                        .toEither()
                        .mapLeft(e -> (FetchError) new FetchError.Transport("MusicBrainz search", e))
                        .flatMap(res -> requireSuccess("MusicBrainz search", res))
                        .flatMap(this::parseRows)
                        .map(List::toJavaList);
    }

    private Either<FetchError, String> requireSuccess(String api, ApiResponse res) {
        return res.isSuccess()
                ? Either.right(res.body)
                : Either.left(new FetchError.HttpStatus(api, res.statusCode, truncate(res.body, 300)));
    }

    /** Structure-level strictness: an unreadable payload is an error, not an empty list. */
    private Either<FetchError, List<Album>> parseRows(String body) {
        return Try.of(() -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray rows = root.getAsJsonArray("release-groups");
                    return List.range(0, rows.size())
                            .map(i -> toAlbum(rows.get(i).getAsJsonObject()));
                })
                .toEither()
                .mapLeft(e -> new FetchError.Payload("MusicBrainz", "payload shape", e));
    }

    private Album toAlbum(JsonObject rg) {
        String id = optString(rg, "id");
        String title = Option.of(optString(rg, "title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");
        String artist = artistName(rg);
        int year = parseYear(optString(rg, "first-release-date"));
        String primaryType = Option.of(optString(rg, "primary-type"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Release");
        String genre = topTags(rg.get("tags"));
        return new Album(id, title, artist, year, primaryType, genre, MusicBrainzClient.coverUrl(id));
    }

    private static String artistName(JsonObject rg) {
        return Option.of(rg.get("artist-credit"))
                .filter(j -> !j.isJsonNull() && j.isJsonArray() && j.getAsJsonArray().size() > 0)
                .map(j -> j.getAsJsonArray().get(0).getAsJsonObject())
                .map(credit -> optString(credit, "name"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Unknown Artist");
    }

    /** The two most-voted community tags, joined for the genre line. */
    private static String topTags(com.google.gson.JsonElement tags) {
        return Option.of(tags)
                .filter(j -> !j.isJsonNull() && j.isJsonArray())
                .map(j -> List.range(0, j.getAsJsonArray().size())
                        .map(i -> j.getAsJsonArray().get(i).getAsJsonObject())
                        .sorted(Comparator.comparingInt((JsonObject t) ->
                                Option.of(t.get("count"))
                                        .filter(c -> !c.isJsonNull())
                                        .map(c -> c.getAsInt())
                                        .getOrElse(0)).reversed())
                        .map(t -> optString(t, "name"))
                        .filter(name -> !name.isEmpty())
                        .take(2)
                        .mkString(" · "))
                .filter(s -> !s.isEmpty())
                .getOrElse("");
    }

    private static int parseYear(String date) {
        if (date == null || date.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String optString(JsonObject o, String key) {
        return Option.of(o.get(key))
                .filter(j -> !j.isJsonNull())
                .map(j -> j.getAsString())
                .getOrElse("");
    }

    /**
     * Cache-aside as an {@link Option} chain: absent/empty URL is {@code None},
     * a cache hit short-circuits via {@code orElse}, a miss is fetched and put
     * back ({@code peek}) — bounded so long sessions don't grow the cache.
     */
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
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
