package vangogh.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TgdbClientTest {

    @Test
    void posterPathBuildsUrlFromApiFilename() {
        assertNull(TgdbClient.posterPath(null));
        assertEquals(
                "https://cdn.thegamesdb.net/images/original/boxart/front/front.jpg",
                TgdbClient.posterPath("boxart/front/front.jpg")
        );
    }

    @Test
    void searchGameUrlUsesRequestedPageAndEncodesQueryAndKey() {
        assertEquals(
                "https://api.thegamesdb.net/v1/Games/ByGameName?apikey=test%2Bkey"
                        + "&name=cyberpunk+2077"
                        + "&fields=players,publishers,genres,overview,rating,platform"
                        + "&include=boxart,platform&page=2",
                TgdbClient.searchGameUrl("test+key", "cyberpunk 2077", 2)
        );
    }

    @Test
    void posterSourcesAcceptFrontBoxartUrls() {
        var sources = TgdbClient.posterSources(
                "https://cdn.thegamesdb.net/images/original/boxart/front/front.jpg",
                false
        );

        assertFalse(sources.isEmpty());
        assertEquals(
                "https://cdn.thegamesdb.net/images/original/boxart/front/front.jpg",
                sources.get(0)
        );
    }
}
