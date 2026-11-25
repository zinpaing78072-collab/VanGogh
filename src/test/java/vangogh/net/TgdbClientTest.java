package vangogh.net;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void mergeTrendingResponsesKeepsGamesAndIncludedBoxartFromEachBatch() {
        JsonObject first = JsonParser.parseString("""
                {"data":{"games":[{"id":1}]},"include":{"boxart":{"data":{"1":[{"filename":"one.jpg"}]}}}}
                """).getAsJsonObject();
        JsonObject second = JsonParser.parseString("""
                {"data":{"games":[{"id":2}]},"include":{"boxart":{"data":{"2":[{"filename":"two.jpg"}]}}}}
                """).getAsJsonObject();

        JsonObject merged = TgdbClient.mergeTrendingResponses(first, second);

        assertEquals(2, merged.getAsJsonObject("data").getAsJsonArray("games").size());
        JsonObject boxartData = merged.getAsJsonObject("include")
                .getAsJsonObject("boxart")
                .getAsJsonObject("data");
        assertTrue(boxartData.has("1"));
        assertTrue(boxartData.has("2"));
    }
}
