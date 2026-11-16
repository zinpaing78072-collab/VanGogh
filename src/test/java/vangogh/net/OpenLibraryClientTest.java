package vangogh.net;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenLibraryClientTest {

    private static final String COVER = "https://covers.openlibrary.org/b/id/8732460-L.jpg";

    @Test
    void coverUrlWithNullReturnsEmpty() {
        assertEquals("", OpenLibraryClient.coverUrl(null));
    }

    @Test
    void coverUrlWithEmptyReturnsEmpty() {
        assertEquals("", OpenLibraryClient.coverUrl(""));
    }

    @Test
    void coverUrlWithZeroCoverReturnsEmpty() {
        // cover_i of 0 means "no cover" in Open Library results.
        assertEquals("", OpenLibraryClient.coverUrl("0"));
    }

    @Test
    void coverUrlBuildsLargeCoverUrl() {
        assertEquals("https://covers.openlibrary.org/b/id/8732460-L.jpg",
                OpenLibraryClient.coverUrl("8732460"));
    }

    @Test
    void posterSourcesWithNullReturnsEmpty() {
        assertTrue(OpenLibraryClient.posterSources(null, false).isEmpty());
    }

    @Test
    void posterSourcesWithUnknownHostReturnsEmpty() {
        assertTrue(OpenLibraryClient.posterSources("https://example.com/cover.jpg", false).isEmpty());
    }

    @Test
    void posterSourcesDirectFirstStartsWithCoversHost() {
        List<String> sources = OpenLibraryClient.posterSources(COVER, false);

        assertEquals(2, sources.size());
        assertEquals(COVER, sources.get(0));
        assertTrue(sources.get(1).startsWith("https://wsrv.nl/?url="));
    }

    @Test
    void posterSourcesProxyFirstPutsProxyAheadOfDirect() {
        List<String> sources = OpenLibraryClient.posterSources(COVER, true);

        assertEquals(2, sources.size());
        assertTrue(sources.get(0).startsWith("https://wsrv.nl/?url="));
        assertEquals(COVER, sources.get(1));
    }
}
