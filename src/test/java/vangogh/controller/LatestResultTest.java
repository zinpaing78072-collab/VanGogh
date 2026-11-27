package vangogh.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-slot handoff's contract: a result belongs to exactly one generation,
 * is delivered once, and a stale completion can never displace or masquerade as
 * a newer request's result.
 */
class LatestResultTest {

    @Test
    void takeIsEmptyUntilSomethingIsParked() {
        LatestResult<String> slot = new LatestResult<>();

        assertTrue(slot.take(1).isEmpty());
    }

    @Test
    void takeDeliversTheValueOnce() {
        LatestResult<String> slot = new LatestResult<>();
        slot.park(7, "seven");

        assertEquals("seven", slot.take(7).get());
        assertTrue(slot.take(7).isEmpty());
    }

    @Test
    void takeLeavesAnotherGenerationsResultAlone() {
        LatestResult<String> slot = new LatestResult<>();
        slot.park(7, "seven");

        assertTrue(slot.take(6).isEmpty(), "an older poller must not consume a newer result");
        assertTrue(slot.take(8).isEmpty(), "a newer poller must not consume an older result");
        assertEquals("seven", slot.take(7).get());
    }

    @Test
    void aStaleCompletionCannotDisplaceANewerResult() {
        LatestResult<String> slot = new LatestResult<>();
        slot.park(2, "second");

        // The first request's fetch finishes last, after the second already parked.
        slot.park(1, "first (stale)");

        assertEquals("second", slot.take(2).get());
        assertTrue(slot.take(1).isEmpty());
    }

    @Test
    void aNewerResultReplacesAnOlderParkedOne() {
        LatestResult<String> slot = new LatestResult<>();
        slot.park(1, "first");

        slot.park(2, "second");

        assertEquals("second", slot.take(2).get());
        assertTrue(slot.take(1).isEmpty());
    }

    @Test
    void clearDropsWhateverIsParked() {
        LatestResult<String> slot = new LatestResult<>();
        slot.park(1, "first");

        slot.clear();

        assertTrue(slot.take(1).isEmpty());
    }
}
