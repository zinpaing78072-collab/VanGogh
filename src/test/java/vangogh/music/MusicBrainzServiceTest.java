package vangogh.music;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.MusicBrainzClient;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Option;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link MusicBrainzService} with a stubbed client — no real
 * MusicBrainz/CAA network calls.
 */
class MusicBrainzServiceTest {

    private static final String SEARCH_JSON = """
        {
          "count": 1,
          "release-groups": [
            { "id": "9162580e-5df4-32de-80cc-f45a8d8a9b1d", "title": "Abbey Road",
              "primary-type": "Album", "first-release-date": "1969-09-26",
              "artist-credit": [
                { "name": "The Beatles",
                  "artist": { "id": "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d", "name": "The Beatles" } }
              ],
              "tags": [ { "count": 5, "name": "classic rock" },
                        { "count": 3, "name": "rock" },
                        { "count": 1, "name": "pop" } ] }
          ]
        }
        """;

    private static final String ABBEY_RGID = "9162580e-5df4-32de-80cc-f45a8d8a9b1d";
    private static final String THRILLER_RGID = "f32fab67-77dd-3937-addc-9062e28e4c37";

    /** Stub client returning canned responses instead of hitting the network. */
    static class StubClient extends MusicBrainzClient {
        ApiResponse trendingResponse;
        ApiResponse searchResponse;
        final List<String> coverCalls = new ArrayList<>();

        @Override
        public io.vavr.control.Try<ApiResponse> releaseGroupsByMbids(java.util.List<String> mbids) {
            return io.vavr.control.Try.success(trendingResponse);
        }

        @Override
        public io.vavr.control.Try<ApiResponse> searchReleaseGroups(String query) {
            return io.vavr.control.Try.success(searchResponse);
        }

        @Override
        public Option<String> loadPosterDataUri(String posterUrl) {
            coverCalls.add(posterUrl);
            return Option.some("data:image/jpeg;base64,stubbed");
        }
    }

    private static String trendingJson(String... rgids) {
        StringBuilder sb = new StringBuilder("{\"count\":").append(rgids.length).append(",\"release-groups\":[");
        for (int i = 0; i < rgids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(rgids[i]).append("\",\"title\":\"Album ").append(i)
                    .append("\",\"primary-type\":\"Album\",\"first-release-date\":\"1973-03-24\",")
                    .append("\"artist-credit\":[{\"name\":\"Artist ").append(i).append("\"}]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void searchAlbumsMapsTheMusicBrainzPayload() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, SEARCH_JSON, null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.searchAlbums("abbey road");

        assertTrue(result.isRight());
        Album album = result.get().get(0);
        assertEquals(ABBEY_RGID, album.id());
        assertEquals("Abbey Road", album.title());
        assertEquals("The Beatles", album.artist());
        assertEquals(1969, album.year());
        assertEquals("Album", album.primaryType());
        assertEquals("classic rock · rock", album.genre());
        assertEquals("https://coverartarchive.org/release-group/" + ABBEY_RGID + "/front-250",
                album.posterUrl());
    }

    @Test
    void searchAlbumsWithBlankQueryFallsBackToTrending() {
        StubClient client = new StubClient();
        client.trendingResponse = new ApiResponse(200, trendingJson(ABBEY_RGID), null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.searchAlbums("   ");

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals(ABBEY_RGID, result.get().get(0).id());
    }

    @Test
    void trendingReordersTheShowcaseToSeedOrder() {
        StubClient client = new StubClient();
        // The rgid OR query returns hits in arbitrary relevance order.
        client.trendingResponse = new ApiResponse(200, trendingJson(THRILLER_RGID, ABBEY_RGID), null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.fetchTrending();

        assertTrue(result.isRight());
        assertEquals(ABBEY_RGID, result.get().get(0).id());
        assertEquals(THRILLER_RGID, result.get().get(1).id());
    }

    @Test
    void httpFailureSurfacesAsHttpStatusError() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(503, "rate limited", null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.searchAlbums("nirvana");

        assertTrue(result.isLeft());
        assertTrue(FetchError.uiMessage(result.getLeft()).contains("HTTP 503"));
    }

    @Test
    void unreadablePayloadSurfacesAsPayloadError() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, "<html>not json</html>", null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.searchAlbums("nirvana");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void malformedRowDefaultsDoNotBreakParsing() {
        StubClient client = new StubClient();
        client.searchResponse = new ApiResponse(200, """
            { "release-groups": [
                { "id": "x", "artist-credit": [], "tags": "not-an-array" },
                { "id": "y", "title": "Has Title", "first-release-date": "bad-date" }
              ] }
            """, null);
        MusicBrainzService service = new MusicBrainzService(client);

        Either<FetchError, java.util.List<Album>> result = service.searchAlbums("q");

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
        Album lenient = result.get().get(0);
        assertEquals("Untitled", lenient.title());
        assertEquals("Unknown Artist", lenient.artist());
        assertEquals(0, lenient.year());
        assertEquals("Release", lenient.primaryType());
        assertEquals("", lenient.genre());
        assertEquals(0, result.get().get(1).year());
    }

    @Test
    void posterDataUriCachesByUrl() {
        StubClient client = new StubClient();
        MusicBrainzService service = new MusicBrainzService(client);

        String cover = "https://coverartarchive.org/release-group/abc/front-250";
        Option<String> first = service.posterDataUri(cover);
        Option<String> second = service.posterDataUri(cover);

        assertTrue(first.isDefined());
        assertEquals(first.get(), second.get());
        assertEquals(1, client.coverCalls.size()); // second call hit the cache
    }
}
