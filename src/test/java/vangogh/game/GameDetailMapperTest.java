package vangogh.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail mapper is the one place that reads TheGamesDB's field names, so
 * these tests pin the record shape the page depends on: art slot URLs, the
 * fact order, the name dictionaries, and the leniency for missing fields.
 */
class GameDetailMapperTest {

    private static final String GAME = """
            {
              "id": 60527,
              "game_title": "Yakuza 0",
              "release_date": "2018-08-01",
              "rating": "M - Mature 17+",
              "players": 1,
              "overview": "Tokyo, 1988. Kazuma Kiryu.",
              "youtube": "8X2kIfS6fb8",
              "platform": 4919,
              "genres": [1, 2],
              "publishers": [10],
              "developers": [20, 21]
            }
            """;

    private static final String IMAGES = """
            {
              "data": {
                "base_url": {
                  "original": "https://cdn.example/original/",
                  "medium": "https://cdn.example/medium/"
                },
                "images": {
                  "60527": [
                    {"type": "screenshot", "filename": "screenshots/a.jpg"},
                    {"type": "fanart", "filename": "fanart/b.jpg"},
                    {"type": "screenshot", "filename": "screenshots/c.jpg"}
                  ]
                }
              }
            }
            """;

    private static final String BOXART = """
            {
              "include": {
                "boxart": {
                  "base_url": {"original": "https://cdn.example/original/"},
                  "data": {
                    "60527": [
                      {"type": "boxart", "side": "back", "filename": "boxart/back.jpg"},
                      {"type": "boxart", "side": "front", "filename": "boxart/front.jpg"}
                    ]
                  }
                },
                "platform": {
                  "data": {"4919": {"id": 4919, "name": "PlayStation 4"}}
                }
              }
            }
            """;

    /** Folds GAME with the given {@code Games/Images} and root payloads. */
    private static GameDetail compose(String images, String root) {
        return GameDetailMapper.compose(
                JsonParser.parseString(root == null ? BOXART : root).getAsJsonObject(),
                JsonParser.parseString(GAME).getAsJsonObject(),
                images == null ? null : JsonParser.parseString(images).getAsJsonObject(),
                "series",
                List.of(new GameDetail.Related(2, "Yakuza Kiwami", 2016, "M - Mature 17+", "Action", "")),
                Map.of("20", "Sega", "21", "Ryu Ga Gotoku Studio"),
                Map.of("10", "Sega"));
    }

    @Test
    void foldsTheRecordIntoAPageShape() {
        GameDetail detail = compose(IMAGES, null);

        assertEquals(60527, detail.id());
        assertEquals("Yakuza 0", detail.title());
        assertEquals("2018-08-01", detail.releaseDate());
        assertEquals(2018, detail.year());
        assertEquals("M - Mature 17+", detail.rating());
        assertEquals(1, detail.players());
        assertEquals(List.of("Action", "Adventure"), detail.genres());
        assertEquals(List.of("PlayStation 4"), detail.platforms());
        assertEquals(List.of("Sega"), detail.publishers());
        assertEquals(List.of("Sega", "Ryu Ga Gotoku Studio"), detail.developers());
        assertEquals("https://www.youtube.com/watch?v=8X2kIfS6fb8", detail.youtubeUrl());
        assertEquals("https://thegamesdb.net/game/60527/", detail.recordUrl());
        assertEquals("series", detail.relatedSource());
        assertEquals(1, detail.related().size());
    }

    @Test
    void putsFanartOnTheHeroAndScreenshotsOnTheStills() {
        GameDetail detail = compose(IMAGES, null);

        assertEquals("https://cdn.example/medium/fanart/b.jpg", detail.heroUrl());
        assertEquals(List.of(
                "https://cdn.example/medium/screenshots/a.jpg",
                "https://cdn.example/medium/screenshots/c.jpg"), detail.stillUrls());
        assertEquals("https://cdn.example/original/boxart/front.jpg", detail.boxartUrl());
    }

    @Test
    void fallsBackToTheFirstScreenshotWhenThereIsNoFanart() {
        String images = """
                {"data": {"images": {"60527": [
                  {"type": "screenshot", "filename": "screenshots/a.jpg"}
                ]}}}
                """;

        GameDetail detail = compose(images, null);

        assertTrue(detail.heroUrl().endsWith("screenshots/a.jpg"));
        assertEquals(1, detail.stillUrls().size());
    }

    @Test
    void keepsTheFactOrderAndLeavesOutWhatTheRecordDoesNotCarry() {
        GameDetail detail = compose(null, null);

        assertEquals(List.of("Release", "Age rating", "Players", "Platform", "Genres",
                "Publisher", "Developer", "Game ID"), labels(detail));
        assertEquals("2018-08-01", valueOf(detail, "Release"));
        assertEquals("M - Mature 17+", valueOf(detail, "Age rating"));
    }

    @Test
    void treatsThePlaceholderReleaseDateAsUnknown() {
        String game = GAME.replace("2018-08-01", "1970-01-01");
        GameDetail detail = GameDetailMapper.compose(
                JsonParser.parseString(BOXART).getAsJsonObject(),
                JsonParser.parseString(game).getAsJsonObject(),
                null, "", List.of(), Map.of(), Map.of());

        assertEquals("", detail.releaseDate());
        assertEquals(0, detail.year());
    }

    @Test
    void acceptsAPayloadWithoutAnySupplementaryJson() {
        GameDetail detail = GameDetailMapper.compose(
                JsonParser.parseString("""
                        {"include": {"boxart": {"data": {"60527": []}}}}
                        """).getAsJsonObject(),
                JsonParser.parseString(GAME).getAsJsonObject(),
                null, "", List.of(), Map.of(), Map.of());

        assertEquals("", detail.heroUrl());
        assertTrue(detail.stillUrls().isEmpty());
        assertEquals("", detail.boxartUrl());
        // The id arrays are still on the game payload; with empty dictionaries
        // they resolve to nothing and the rail row disappears.
        assertEquals(List.of("Release", "Age rating", "Players", "Genres", "Game ID"), labels(detail));
    }

    @Test
    void passesAFullYoutubeUrlThroughUntouched() {
        String game = GAME.replace("8X2kIfS6fb8", "https://youtu.be/abc");
        GameDetail detail = GameDetailMapper.compose(
                null, JsonParser.parseString(game).getAsJsonObject(), null,
                "", List.of(), Map.of(), Map.of());

        assertEquals("https://youtu.be/abc", detail.youtubeUrl());
    }

    @Test
    void seedsTheSeriesSearchWithTheFirstMeaningfulWord() {
        assertEquals("Yakuza", TgdbGameService.seriesSeed("Yakuza 0"));
        assertEquals("Elden", TgdbGameService.seriesSeed("Elden Ring"));
        assertEquals("Cyberpunk", TgdbGameService.seriesSeed("Cyberpunk 2077 [Demo Disc]"));
        assertEquals("Game", TgdbGameService.seriesSeed("A Game (Collector's Edition)"));
        assertEquals("", TgdbGameService.seriesSeed("  "));
    }

    private static List<String> labels(GameDetail detail) {
        return detail.facts().stream().map(GameDetail.Fact::label).toList();
    }

    private static String valueOf(GameDetail detail, String label) {
        return detail.facts().stream()
                .filter(fact -> fact.label().equals(label))
                .map(GameDetail.Fact::value)
                .findFirst()
                .orElse("");
    }
}
