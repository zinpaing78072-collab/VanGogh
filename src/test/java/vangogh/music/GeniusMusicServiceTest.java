package vangogh.music;

import vangogh.error.FetchError;
import vangogh.net.ApiResponse;
import vangogh.net.GeniusClient;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import io.vavr.control.Try;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeniusMusicService#songDetail(String)} with a stubbed
 * client — no real Genius network calls.
 */
class GeniusMusicServiceTest {

    private static final String SONG_JSON = """
        {
          "response": {
            "song": {
              "id": 8434253,
              "title": "Anti-Hero",
              "artist_names": "Taylor Swift",
              "release_date": "2022-10-21",
              "lyrics_state": "complete",
              "url": "https://genius.com/Taylor-swift-anti-hero-lyrics",
              "song_art_image_url": "https://images.genius.com/anti-hero.1000x1000x1.jpg",
              "album": { "name": "Midnights", "release_date_for_display": "October 21, 2022" },
              "stats": { "pageviews": 2364330 },
              "primary_artist": { "name": "Taylor Swift" },
              "writer_artists": [ { "name": "Taylor Swift" }, { "name": "Jack Antonoff" } ],
              "producer_artists": [ { "name": "Jack Antonoff" }, { "name": "Taylor Swift" } ],
              "featured_artists": [],
              "song_relationships": [
                { "type": "covered_by", "songs": [
                  { "id": 9000001, "title": "Anti-Hero (Cover)",
                    "artist_names": "Some Artist",
                    "song_art_image_thumbnail_url": "https://images.genius.com/cover.300x300x1.jpg" }
                ] },
                { "type": "remixed_by", "songs": [
                  { "id": 9000002, "title": "Anti-Hero (Remix)", "artist_names": "DJ Somebody" }
                ] },
                { "type": "translations", "songs": [
                  { "id": 9000003, "title": "Anti-Hero (Tradução)", "artist_names": "Translator" }
                ] }
              ]
            }
          }
        }
        """;

    /** Stub client returning a canned /songs/:id payload. */
    static class StubClient extends GeniusClient {
        ApiResponse songResponse = new ApiResponse(200, SONG_JSON, null);

        StubClient() {
            super("stub-token");
        }

        @Override
        public Try<ApiResponse> song(String id) {
            return Try.success(songResponse);
        }
    }

    @Test
    void songDetailMapsGeniusMetadataAndKeepsDummyMedia() {
        GeniusMusicService service = new GeniusMusicService(new StubClient());

        Either<FetchError, SongDetail> result = service.songDetail("8434253");

        assertTrue(result.isRight(), () -> "expected a right, got " + result);
        SongDetail detail = result.get();
        assertEquals("8434253", detail.id());
        assertEquals("Anti-Hero", detail.title());
        assertEquals("Taylor Swift", detail.artist());
        assertEquals("Midnights", detail.album());
        assertEquals(2022, detail.year());
        assertEquals("2022-10-21", detail.releaseDate());
        assertEquals(java.util.List.of("Taylor Swift", "Jack Antonoff"), detail.writers());
        assertEquals(java.util.List.of("Jack Antonoff", "Taylor Swift"), detail.producers());
        assertEquals(2364330L, detail.pageviews());
        assertEquals("complete", detail.lyricsState());
        assertEquals("https://genius.com/Taylor-swift-anti-hero-lyrics", detail.geniusUrl());
        assertEquals("https://images.genius.com/anti-hero.1000x1000x1.jpg", detail.artUrl());

        // Dummy media: present, labelled and clearly placeholder length.
        assertFalse(detail.lyrics().isEmpty());
        assertEquals(DummyMedia.DURATION_SECONDS, detail.durationSeconds());
        assertEquals(DummyMedia.AUDIO_URL, detail.audioUrl());
    }

    @Test
    void relatedSongsSkipTranslationsAndCarryTheRelationLabel() {
        GeniusMusicService service = new GeniusMusicService(new StubClient());

        SongDetail detail = service.songDetail("8434253").get();

        assertEquals(2, detail.related().size());
        RelatedSong cover = detail.related().get(0);
        assertEquals("Anti-Hero (Cover)", cover.title());
        assertEquals("Some Artist", cover.artist());
        assertEquals("Cover", cover.relation());
        assertEquals("https://images.genius.com/cover.300x300x1.jpg", cover.posterUrl());
        assertEquals("Remix", detail.related().get(1).relation());
    }

    @Test
    void nonSuccessStatusBecomesAnHttpError() {
        StubClient client = new StubClient();
        client.songResponse = new ApiResponse(404, "{\"meta\":{\"status\":404}}", null);
        GeniusMusicService service = new GeniusMusicService(client);

        Either<FetchError, SongDetail> result = service.songDetail("0");

        assertTrue(result.isLeft());
        assertTrue(result.getLeft() instanceof FetchError.HttpStatus);
    }
}
