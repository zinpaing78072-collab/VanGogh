package com.entertainment.tester;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TmdbClientTest {

    private static final String POSTER = "https://image.tmdb.org/t/p/w500/abc123.jpg";

    @Test
    void posterPathWithNullReturnsNull() {
        assertNull(TmdbClient.posterPath(null));
    }

    @Test
    void posterPathWithEmptyStringReturnsNull() {
        assertNull(TmdbClient.posterPath(""));
    }

    @Test
    void posterPathWithValidPathReturnsFullUrl() {
        String result = TmdbClient.posterPath("/abc123.jpg");
        assertEquals("https://image.tmdb.org/t/p/w500/abc123.jpg", result);
    }

    @Test
    void posterPathWithLeadingSlashPreserved() {
        String result = TmdbClient.posterPath("/xyz.png");
        assertTrue(result.endsWith("/xyz.png"));
        assertTrue(result.startsWith("https://image.tmdb.org/t/p/w500/"));
    }

    @Test
    void posterSourcesWithNullReturnsEmpty() {
        assertTrue(TmdbClient.posterSources(null, false).isEmpty());
    }

    @Test
    void posterSourcesWithUnknownHostReturnsEmpty() {
        assertTrue(TmdbClient.posterSources("https://example.com/poster.jpg", false).isEmpty());
    }

    @Test
    void posterSourcesDirectFirstStartsWithDirectHost() {
        List<String> sources = TmdbClient.posterSources(POSTER, false);

        assertEquals(2, sources.size());
        assertTrue(sources.get(0).startsWith("https://image.tmdb.org/t/p/w342/"));
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", sources.get(0));
        assertTrue(sources.get(1).startsWith("https://wsrv.nl/?url="));
    }

    @Test
    void posterSourcesProxyFirstPutsProxyAheadOfDirect() {
        List<String> sources = TmdbClient.posterSources(POSTER, true);

        assertEquals(2, sources.size());
        assertTrue(sources.get(0).startsWith("https://wsrv.nl/?url="));
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", sources.get(1));
    }

    @Test
    void posterSourcesProxyUrlIsEncoded() {
        String proxied = TmdbClient.posterSources(POSTER, false).get(1);

        assertTrue(proxied.contains("url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342%2Fabc123.jpg"));
    }

    @Test
    void toDataUriWithNullBytesReturnsNull() {
        assertNull(TmdbClient.toDataUri(null, "image/jpeg"));
    }

    @Test
    void toDataUriWithEmptyBytesReturnsNull() {
        assertNull(TmdbClient.toDataUri(new byte[0], "image/jpeg"));
    }

    @Test
    void toDataUriEncodesBytesAsBase64() {
        byte[] bytes = {1, 2, 3};

        String uri = TmdbClient.toDataUri(bytes, "image/jpeg");

        assertTrue(uri.startsWith("data:image/jpeg;base64,"));
        assertEquals("AQID", uri.substring("data:image/jpeg;base64,".length()));
    }

    @Test
    void toDataUriStripsContentTypeParameters() {
        String uri = TmdbClient.toDataUri(new byte[]{1}, "image/jpeg; charset=binary");

        assertTrue(uri.startsWith("data:image/jpeg;base64,"));
        assertFalse(uri.contains("charset"));
    }

    @Test
    void toDataUriWithNonImageTypeFallsBackToJpeg() {
        String uri = TmdbClient.toDataUri(new byte[]{1}, "application/octet-stream");

        assertTrue(uri.startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void toDataUriWithBlankTypeFallsBackToJpeg() {
        String uri = TmdbClient.toDataUri(new byte[]{1}, "  ");

        assertTrue(uri.startsWith("data:image/jpeg;base64,"));
    }
}
