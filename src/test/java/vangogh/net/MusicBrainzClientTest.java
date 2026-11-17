package vangogh.net;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzClientTest {

    private static final String COVER = "https://coverartarchive.org/release-group/9162580e-5df4-32de-80cc-f45a8d8a9b1d/front-250";

    @Test
    void coverUrlWithNullReturnsEmpty() {
        assertEquals("", MusicBrainzClient.coverUrl(null));
    }

    @Test
    void coverUrlWithEmptyReturnsEmpty() {
        assertEquals("", MusicBrainzClient.coverUrl(""));
    }

    @Test
    void coverUrlBuildsFront250Url() {
        assertEquals("https://coverartarchive.org/release-group/abc/front-250",
                MusicBrainzClient.coverUrl("abc"));
    }

    @Test
    void posterSourcesWithNullReturnsEmpty() {
        assertTrue(MusicBrainzClient.posterSources(null, false).isEmpty());
    }

    @Test
    void posterSourcesWithUnknownHostReturnsEmpty() {
        assertTrue(MusicBrainzClient.posterSources("https://example.com/cover.jpg", false).isEmpty());
    }

    @Test
    void posterSourcesDirectFirstStartsWithCoverArchive() {
        List<String> sources = MusicBrainzClient.posterSources(COVER, false);

        assertEquals(2, sources.size());
        assertEquals(COVER, sources.get(0));
        assertTrue(sources.get(1).startsWith("https://wsrv.nl/?url="));
    }

    @Test
    void posterSourcesProxyFirstPutsProxyAheadOfDirect() {
        List<String> sources = MusicBrainzClient.posterSources(COVER, true);

        assertEquals(2, sources.size());
        assertTrue(sources.get(0).startsWith("https://wsrv.nl/?url="));
        assertEquals(COVER, sources.get(1));
    }

    @Test
    void posterSourcesProxyUrlIsEncoded() {
        String proxied = MusicBrainzClient.posterSources(COVER, false).get(1);

        assertTrue(proxied.contains("url=https%3A%2F%2Fcoverartarchive.org"));
    }
}
