package vangogh.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TgdbGameServiceTest {

    @Test
    void parsesGamesAndTheirFrontBoxart() {
        String json = """
            {
              "data": {
                "games": [
                  {
                    "id": 42,
                    "game_title": "Sample Game",
                    "release_date": "2024-06-15",
                    "genres": [1],
                    "overview": "An adventure."
                  }
                ]
              },
              "include": {
                "boxart": {
                  "data": {
                    "42": [
                      {"type": "boxart", "side": "back", "filename": "back.jpg"},
                      {"type": "boxart", "side": "front", "filename": "boxart/front/front.jpg"}
                    ]
                  }
                }
              }
            }
            """;

        var games = new TgdbGameService("test-key").parseGames(json);

        assertEquals(1, games.size());
        Game game = games.get(0);
        assertEquals(42, game.id());
        assertEquals("Sample Game", game.game_title());
        assertEquals("2024-06-15", game.release_date());
        assertEquals("Action", game.genre());
        assertEquals("https://cdn.thegamesdb.net/images/original/boxart/front/front.jpg", game.posterUrl());
    }

    @Test
    void parsesGamesWhenBoxartIsMissing() {
        String json = """
            {"data":{"games":[{"id":7,"game_title":"No Cover"}]}}
            """;

        var games = new TgdbGameService("test-key").parseGames(json);

        assertEquals(1, games.size());
        assertTrue(games.get(0).posterUrl().isEmpty());
    }
}
