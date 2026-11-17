package vangogh.game;

import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TgdbGameServiceTest {

    private static final class PagedClient extends TgdbClient {
        private final ArrayList<Integer> requestedPages = new ArrayList<>();

        PagedClient() {
            super("test-key");
        }

        @Override
        public Try<ApiResponse> searchGame(String query, int page) {
            requestedPages.add(page);
            String previous = page == 1 ? "null"
                    : "\"https://api.thegamesdb.net/v1/Games/ByGameName?apikey=test-key&page=" + (page - 1) + "\"";
            String next = page == 1
                    ? "\"https://api.thegamesdb.net/v1/Games/ByGameName?apikey=test-key&page=2\""
                    : "null";
            String json = """
                {
                  "data": {"games": [{"id": %d, "game_title": "Page %d"}]},
                  "pages": {"previous": %s, "next": %s}
                }
                """.formatted(page, page, previous, next);
            return Try.success(new ApiResponse(200, json, null));
        }
    }

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
    void parsesWrappedApiResponseShape() {
        String json = """
            {
              "code": 200,
              "status": "Success",
              "data": {
                "games": [
                  {
                    "id": 99,
                    "game_title": "Wrapped Game",
                    "release_date": "2024-07-01",
                    "genres": [2],
                    "overview": "A wrapped response."
                  }
                ]
              },
              "include": {
                "boxart": {
                  "data": {
                    "99": [
                      {"type": "boxart", "side": "front", "filename": "boxart/front/wrapped.jpg"}
                    ]
                  }
                }
              }
            }
            """;

        var games = new TgdbGameService("test-key").parseGames(json);

        assertEquals(1, games.size());
        Game game = games.get(0);
        assertEquals(99, game.id());
        assertEquals("Wrapped Game", game.game_title());
        assertEquals("https://cdn.thegamesdb.net/images/original/boxart/front/wrapped.jpg", game.posterUrl());
    }

    @Test
    void searchPageUsesApiPreviousAndNextLinks() {
        PagedClient client = new PagedClient();
        TgdbGameService service = new TgdbGameService(client);

        var first = service.searchGamesPage("cyberpunk", 1).get();
        var second = service.searchGamesPage("cyberpunk", first.nextPage()).get();

        assertEquals(java.util.List.of(1, 2), client.requestedPages);
        assertEquals(1, first.page());
        assertEquals(2, first.nextPage());
        assertEquals(1, second.previousPage());
        assertTrue(second.nextPage() == null);
        assertEquals("Page 2", second.games().get(0).game_title());
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
