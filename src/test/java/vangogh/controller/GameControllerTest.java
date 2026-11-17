package vangogh.controller;

import vangogh.error.FetchError;
import vangogh.game.Game;
import vangogh.game.TgdbGameService;
import org.junit.jupiter.api.Test;
import io.vavr.control.Either;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for GameController — the Krema IPC command handler that backs
 * {@code window.krema.invoke("requestGames", ...)}.
 *
 * Uses a stubbed {@link TgdbGameService} (subclass, no real TheGamesDB calls).
 *
 * Contract (see the class comment on {@link GameController}): the command
 * kicks off the IO-pool fetch and returns the request generation at once —
 * the UI thread never blocks on network I/O. The resolved list arrives via
 * the {@code pollGameData} drain; posters via the {@code pollGamePosters}
 * drain.
 */
class GameControllerTest {

    /** Stub service whose fetch sleeps, simulating a slow TheGamesDB round-trip. */
    static class SlowService extends TgdbGameService {
        SlowService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, java.util.List<Game>> fetchTrending() {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Either.right(java.util.List.of());
        }

        @Override
        public Either<FetchError, java.util.List<Game>> searchGames(String query) {
            return fetchTrending();
        }
    }

    /** Stub service that fails the fetch, to test the error drain. */
    static class FailingService extends TgdbGameService {
        FailingService() {
            super("test-key");
        }

        @Override
        public Either<FetchError, java.util.List<Game>> fetchTrending() {
            return Either.left(new FetchError.Transport(
                    "TheGamesDB trending", new RuntimeException("boom")));
        }

        @Override
        public Either<FetchError, java.util.List<Game>> searchGames(String query) {
            return fetchTrending();
        }
    }

    /** Drains pollGameData until this generation's fetch resolves (2s deadline). */
    private GameController.GameDataReply drainData(GameController controller, int generation)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        GameController.GameDataReply reply = null;
        while (System.currentTimeMillis() < deadline) {
            reply = controller.pollGameData(generation);
            if (!"pending".equals(reply.status())) {
                return reply;
            }
            Thread.sleep(10);
        }
        return reply;
    }

    @Test
    void requestGamesDoesNotBlockTheCallingThreadDuringFetch() {
        GameController controller = new GameController(new SlowService());

        long start = System.nanoTime();
        int gen = controller.requestGames("trending", "");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The Krema handler runs on the UI thread: it must kick off the fetch
        // and return at once, never block on network I/O (that froze the
        // window for the whole round-trip — the 1-5s movie→game toggle freeze).
        assertTrue(elapsedMs < 250,
                "requestGames blocked the calling thread for " + elapsedMs
                        + "ms — UI-thread freeze");
        assertTrue(gen > 0);
    }

    @Test
    void fetchedGamesArriveOnTheDataDrain() throws Exception {
        GameController controller = new GameController(new SlowService());

        int gen = controller.requestGames("trending", "");
        GameController.GameDataReply reply = drainData(controller, gen);

        assertEquals("done", reply.status());
        assertNotNull(reply.items());
        assertTrue(reply.items().isEmpty());
    }

    @Test
    void fetchFailureSurfacesAsAnErrorStatusOnTheDataDrain() throws Exception {
        GameController controller = new GameController(new FailingService());

        int gen = controller.requestGames("trending", "");
        GameController.GameDataReply reply = drainData(controller, gen);

        // The fetch failure's own message must surface (it becomes the page's
        // error state, same text the old thrown-edge produced).
        assertEquals("error", reply.status());
        assertEquals("TheGamesDB trending failed: boom", reply.error());
    }

    @Test
    void pollGameDataOlderGenerationIsSupersededByANewerRequest() throws Exception {
        GameController controller = new GameController(new SlowService());

        int firstGen = controller.requestGames("trending", "");
        int secondGen = controller.requestGames("trending", "");
        assertTrue(secondGen > firstGen);

        // The first poller must never consume the second request's result —
        // two loops can poll the same one-slot drain. Whether the first poll
        // observes "pending" (fetch still in flight) or "superseded" (resolved
        // and dropped as stale) is timing-dependent; "done" would mean it
        // stole the newer request's data.
        assertNotEquals("done", controller.pollGameData(firstGen).status());

        // The newer request still delivers through the drain.
        assertEquals("done", drainData(controller, secondGen).status());
    }
}
