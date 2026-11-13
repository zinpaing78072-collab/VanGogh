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
