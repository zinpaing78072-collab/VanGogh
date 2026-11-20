package vangogh.music;

import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.GeniusClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Genius resolution rule for the music recommendation slice: an exact
 * normalized title match within the top five song hits, the artist must match
 * when the model supplied one, and Genius' relevance order breaks the tie.
 * Presentation-only suffixes ("(feat. …)", "[Remastered]") do not prevent a
 * match; albums and artists in the mixed hit list are ignored.
 */
class SongResolverTest {

    static class StubClient extends GeniusClient {
        Try<ApiResponse> response = Try.success(new ApiResponse(200, hits(), null));

        StubClient() {
            super("stub-token");
        }

        @Override
        public Try<ApiResponse> search(String query) {
            return response;
        }
    }

    private static String hit(int id, String title, String artist) {
        return "{\"type\":\"song\",\"result\":{"
                + "\"id\":" + id
                + ",\"title\":\"" + title + "\""
                + ",\"primary_artist\":{\"name\":\"" + artist + "\"},"
                + "\"song_art_image_thumbnail_url\":\"https://img.test/" + id + ".jpg\","
                + "\"release_date_for_display\":\"October 21, 2022\"}}";
    }

    private static String nonSongHit(String type, int id, String title, String artist) {
        return "{\"type\":\"" + type + "\",\"result\":{"
                + "\"id\":" + id
                + ",\"title\":\"" + title + "\""
                + ",\"primary_artist\":{\"name\":\"" + artist + "\"}}}";
    }

    private static String hits(String... rows) {
        return "{\"response\":{\"hits\":[" + String.join(",", rows) + "]}}";
    }

    private static SongResolver resolverWith(String body) {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(200, body, null));
        return new SongResolver(client);
    }

    @Test
    void anExactTitleAndArtistResolveToACard() {
        SongResolver resolver = resolverWith(hits(
                hit(1, "Anti-Hero", "Taylor Swift")));

        Option<Song> resolved = resolver.resolve(new Pick("Anti-Hero", "Taylor Swift")).get();

        assertTrue(resolved.isDefined());
        assertEquals("1", resolved.get().id());
        assertEquals("Anti-Hero", resolved.get().title());
        assertEquals("Taylor Swift", resolved.get().artist());
        assertEquals(2022, resolved.get().year());
        assertEquals("https://img.test/1.jpg", resolved.get().posterUrl());
    }

    @Test
    void matchingIgnoresCaseArticlesPunctuationAndFeatureSuffixes() {
        SongResolver resolver = resolverWith(hits(
                hit(2, "Anti-Hero (feat. Bleachers)", "Taylor Swift")));

        assertTrue(resolver.resolve(new Pick("anti hero", "taylor swift")).get().isDefined());
        assertTrue(resolver.resolve(new Pick("The Anti-Hero", null)).get().isDefined());
    }

    @Test
    void theModelsParentheticalAlsoMatchesTheCleanTitle() {
        SongResolver resolver = resolverWith(hits(
                hit(3, "Anti-Hero", "Taylor Swift")));

        assertTrue(resolver.resolve(new Pick("Anti-Hero (feat. Bleachers)", null))
                .get().isDefined());
    }

    @Test
    void aDifferentArtistIsACleanMiss() {
        SongResolver resolver = resolverWith(hits(
                hit(4, "Anti-Hero", "Some Cover Band"),
                hit(5, "Anti-Hero", "Taylor Swift")));

        Option<Song> resolved = resolver.resolve(new Pick("Anti-Hero", "Taylor Swift")).get();

        assertTrue(resolved.isDefined());
        assertEquals("5", resolved.get().id());
        assertTrue(resolver.resolve(new Pick("Anti-Hero", "Nobody At All")).get().isEmpty());
    }

    @Test
    void withoutAnArtistTheFirstExactHitWins() {
        SongResolver resolver = resolverWith(hits(
                hit(6, "Time", "Hans Zimmer"),
                hit(7, "Time", "Pink Floyd")));

        assertEquals("6", resolver.resolve(new Pick("Time", null)).get().get().id());
    }

    @Test
    void aBareYearIsAFormatSlipNotAnArtist() {
        SongResolver resolver = resolverWith(hits(
                hit(8, "Time", "Hans Zimmer")));

        assertTrue(resolver.resolve(new Pick("Time", "2010")).get().isDefined());
    }

    @Test
    void albumsAndArtistsInTheHitListAreIgnored() {
        SongResolver resolver = resolverWith(hits(
                nonSongHit("album", 9, "Anti-Hero", "Taylor Swift"),
                nonSongHit("artist", 10, "Anti-Hero", "Taylor Swift")));

        assertTrue(resolver.resolve(new Pick("Anti-Hero", null)).get().isEmpty());
    }

    @Test
    void onlyTheTopFiveSongHitsAreConsidered() {
        SongResolver resolver = resolverWith(hits(
                hit(1, "One", "A"),
                hit(2, "Two", "B"),
                hit(3, "Three", "C"),
                hit(4, "Four", "D"),
                hit(5, "Five", "E"),
                hit(6, "Target", "F")));

        assertTrue(resolver.resolve(new Pick("Target", null)).get().isEmpty());
    }

    @Test
    void noHitsIsACleanMiss() {
        assertTrue(resolverWith(hits()).resolve(new Pick("Anything", null)).get().isEmpty());
    }

    @Test
    void anHttpFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(500, "boom", "server error"));

        Either<FetchError, Option<Song>> result =
                new SongResolver(client).resolve(new Pick("Anti-Hero", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(500, ((FetchError.HttpStatus) result.getLeft()).status());
    }

    @Test
    void aTransportFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.failure(new RuntimeException("offline"));

        Either<FetchError, Option<Song>> result =
                new SongResolver(client).resolve(new Pick("Anti-Hero", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
    }

    @Test
    void aMalformedBodyIsAPayloadLeft() {
        Either<FetchError, Option<Song>> result =
                resolverWith("not json").resolve(new Pick("Anti-Hero", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }
}
