package com.entertainment.tester;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TmdbClientTest {

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
}