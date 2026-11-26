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
              "release_date_for_display": "October 21, 2022",
              "lyrics_state": "complete",
              "url": "https://genius.com/Taylor-swift-anti-hero-lyrics",
              "song_art_image_url": "https://images.genius.com/anti-hero.1000x1000x1.jpg",
              "language": "en",
              "relationships_index_url": "https://genius.com/albums/Taylor-swift/Midnights/relationships",
              "recording_location": "Electric Lady Studios, New York, NY",
              "annotation_count": 17,
              "pyongs_count": 81,
              "song_art_primary_color": "#b98d55",
              "song_art_secondary_color": "#744a19",
              "song_art_text_color": "#fff",
              "album": {
                "name": "Midnights",
                "url": "https://genius.com/albums/Taylor-swift/Midnights",
                "cover_art_url": "https://images.genius.com/album.1000x1000x1.jpg",
                "release_date_for_display": "October 21, 2022"
              },
              "stats": {
                "pageviews": 2364330,
                "contributors": 224,
                "transcribers": 4,
                "accepted_annotations": 16
              },
              "primary_artist": {
                "name": "Taylor Swift",
                "image_url": "https://images.genius.com/artist.1000x1000x1.jpg",
                "url": "https://genius.com/artists/Taylor-swift",
                "is_verified": true
              },
              "description": {
                "dom": {
                  "tag": "root",
                  "children": [
                    { "tag": "p", "children": [
                      "First paragraph with ",
                      { "tag": "em", "children": [ "emphasis" ] },
                      "."
                    ] },
                    "",
                    { "tag": "p", "children": [ "Second paragraph." ] }
                  ]
                }
              },
              "custom_performances": [
                { "label": "Mixing Engineer", "artists": [ { "name": "Serban Ghenea" } ] },
                { "label": "Label", "artists": [ { "name": "Republic Records" },
                                                 { "name": "Universal Music Group" } ] },
                { "label": "No Artists", "artists": [] }
              ],
              "media": [
                { "provider": "youtube", "type": "video",
                  "url": "https://www.youtube.com/watch?v=b1kbLwvqugk", "start": 0 },
                { "provider": "soundcloud", "type": "audio",
                  "url": "https://soundcloud.com/taylorswiftofficial/anti-hero" }
              ],
              "translation_songs": [ { "id": 9001 }, { "id": 9002 } ],
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

    /** The smallest payload the endpoint can realistically return. */
    private static final String MINIMAL_JSON = """
        { "response": { "song": { "id": 1, "title": "Bare" } } }
        """;

    private static final String CHART_JSON = """
        {"response":{"chart_items":[
          {"_type":"chart_item","type":"song","item":{
            "id":14109835,
            "title":"Ako ang bagyo",
            "artist_names":"SHEAN",
            "release_date_for_display":"October 2, 2026",
            "song_art_image_thumbnail_url":"https://images.genius.com/song.300x300x1.png"
          }}
        ],"next_page":2}}
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

    static class StubChartClient extends GeniusClient {
        ApiResponse chartResponse = new ApiResponse(200, CHART_JSON, null);
        ApiResponse searchResponse = new ApiResponse(200, "{}", null);
        int searchCalls;

        StubChartClient() {
            super("stub-token");
        }

        @Override
        public Try<ApiResponse> chartSongs() {
            return Try.success(chartResponse);
        }

        @Override
        public Try<ApiResponse> search(String query) {
            searchCalls++;
            return Try.success(searchResponse);
        }
    }

    @Test
    void trendingMapsTheCurrentGeniusChartPayload() {
        StubChartClient client = new StubChartClient();

        Either<FetchError, java.util.List<Song>> result =
                new GeniusMusicService(client).fetchTrending();

        assertTrue(result.isRight(), () -> "expected a right, got " + result);
        Song song = result.get().get(0);
        assertEquals("14109835", song.id());
        assertEquals("Ako ang bagyo", song.title());
        assertEquals("SHEAN", song.artist());
        assertEquals(2026, song.year());
        assertEquals("https://images.genius.com/song.300x300x1.png", song.posterUrl());
        assertEquals(0, client.searchCalls);
    }

    @Test
    void chartFailureIsReportedInsteadOfReturningUnrelatedSearchResults() {
        StubChartClient client = new StubChartClient();
        client.chartResponse = new ApiResponse(503, "chart unavailable", null);

        Either<FetchError, java.util.List<Song>> result =
                new GeniusMusicService(client).fetchTrending();

        assertTrue(result.isLeft());
        assertTrue(result.getLeft() instanceof FetchError.HttpStatus);
        assertEquals(0, client.searchCalls);
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
        assertEquals("October 21, 2022", detail.releaseDateDisplay());
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
    void detailCarriesTheTierOneFieldsTheDesignHasSlotsFor() {
        SongDetail detail = new GeniusMusicService(new StubClient()).songDetail("8434253").get();

        assertEquals("English", detail.language());
        assertEquals(2, detail.translationCount());
        assertEquals("https://genius.com/albums/Taylor-swift/Midnights/relationships",
                detail.relationshipsIndexUrl());
        assertEquals("#b98d55", detail.artPrimaryColor());
        assertEquals("#744a19", detail.artSecondaryColor());
        assertEquals("#fff", detail.artTextColor());

        // Full credits, in payload order, with blank entries dropped.
        assertEquals(2, detail.credits().size());
        assertEquals("Mixing Engineer", detail.credits().get(0).label());
        assertEquals(java.util.List.of("Serban Ghenea"), detail.credits().get(0).names());
        assertEquals(java.util.List.of("Republic Records", "Universal Music Group"),
                detail.credits().get(1).names());

        // Media links are links, not streams; the provider is display-ready.
        assertEquals(2, detail.media().size());
        MediaLink youtube = detail.media().get(0);
        assertEquals("YouTube", youtube.provider());
        assertEquals("video", youtube.type());
        assertEquals(0, youtube.startSeconds());
        assertEquals("SoundCloud", detail.media().get(1).provider());
    }

    @Test
    void aboutTextFlattensGeniusRichTextIntoParagraphs() {
        SongDetail detail = new GeniusMusicService(new StubClient()).songDetail("8434253").get();

        assertEquals("First paragraph with emphasis.\n\nSecond paragraph.", detail.about());
    }

    @Test
    void detailCarriesArtistAlbumAndCommunityContext() {
        SongDetail detail = new GeniusMusicService(new StubClient()).songDetail("8434253").get();

        assertEquals("https://genius.com/albums/Taylor-swift/Midnights", detail.albumUrl());
        assertEquals("https://images.genius.com/album.1000x1000x1.jpg", detail.albumCoverUrl());
        assertEquals("https://images.genius.com/artist.1000x1000x1.jpg", detail.artistImageUrl());
        assertEquals("https://genius.com/artists/Taylor-swift", detail.artistUrl());
        assertTrue(detail.artistVerified());
        assertEquals("Electric Lady Studios, New York, NY", detail.recordingLocation());
        assertEquals(224, detail.contributors());
        assertEquals(4, detail.transcribers());
        assertEquals(16, detail.acceptedAnnotations());
        assertEquals(17, detail.annotationCount());
        assertEquals(81, detail.pyongsCount());
    }

    @Test
    void aMinimalPayloadLeavesTheOptionalSectionsEmptyInsteadOfFailing() {
        StubClient client = new StubClient();
        client.songResponse = new ApiResponse(200, MINIMAL_JSON, null);

        Either<FetchError, SongDetail> result = new GeniusMusicService(client).songDetail("1");

        assertTrue(result.isRight(), () -> "expected a right, got " + result);
        SongDetail detail = result.get();
        assertEquals("Bare", detail.title());
        assertEquals("", detail.about());
        assertEquals("", detail.releaseDateDisplay());
        assertEquals("", detail.language());
        assertEquals(0, detail.translationCount());
        assertTrue(detail.credits().isEmpty());
        assertTrue(detail.media().isEmpty());
        assertTrue(detail.related().isEmpty());
        assertEquals("", detail.artPrimaryColor());
        assertFalse(detail.artistVerified());
        assertEquals(0, detail.contributors());
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
