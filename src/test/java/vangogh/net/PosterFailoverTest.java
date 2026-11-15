package vangogh.net;

import io.vavr.Tuple2;
import io.vavr.control.Option;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Poster failover selection: try each source in order, stop at the first
 * success (lazily — sources after the winner are never fetched), yield
 * {@code none} when every source fails or the chain was interrupted.
 *
 * <p>Regression: the original chain used {@code takeUntil(...).lastOption()},
 * but vavr's {@code takeUntil} EXCLUDES the element satisfying the predicate,
 * so the successful attempt was always dropped and posters never loaded in
 * any shell (Krema or JCEF). These tests pin the intended semantics.
 */
class PosterFailoverTest {

    private static final String OK = "data:image/jpeg;base64,AAAA";

    /** Fake fetcher: succeeds for urls containing "/ok", fails otherwise. */
    private static PosterFailover.ThrowingFetch fakeFetch() {
        return source -> {
            if (source.contains("/ok")) {
                return OK;
            }
            throw new IllegalStateException("unreachable: " + source);
        };
    }

    private static Tuple2<String, String> pair(String source, String uri) {
        return new Tuple2<>(source, uri);
    }

    @Test
    void firstSourceSucceedsReturnsItWithoutFetchingRest() {
        AtomicInteger fetches = new AtomicInteger();
        PosterFailover.ThrowingFetch fetch = source -> {
            fetches.incrementAndGet();
            return fakeFetch().fetch(source);
        };

        Option<Tuple2<String, String>> picked = PosterFailover.firstSuccess(
                List.of("https://proxy/ok", "https://direct/bad"), fetch);

        assertTrue(picked.isDefined());
        assertEquals("https://proxy/ok", picked.get()._1());
        assertEquals(OK, picked.get()._2());
        assertEquals(1, fetches.get(), "lazy chain must stop at the first success");
    }

    @Test
    void firstSourceFailsSecondSucceedsReturnsSecond() {
        Option<Tuple2<String, String>> picked = PosterFailover.firstSuccess(
                List.of("https://direct/bad", "https://proxy/ok"), fakeFetch());

        assertTrue(picked.isDefined());
        assertEquals("https://proxy/ok", picked.get()._1());
    }

    @Test
    void allSourcesFailReturnsEmpty() {
        Option<Tuple2<String, String>> picked = PosterFailover.firstSuccess(
                List.of("https://a/bad", "https://b/bad"), fakeFetch());

        assertTrue(picked.isEmpty());
    }

    @Test
    void emptySourceListReturnsEmpty() {
        Option<Tuple2<String, String>> picked = PosterFailover.firstSuccess(
                List.of(), fakeFetch());

        assertTrue(picked.isEmpty());
    }

    @Test
    void interruptedFetchStopsTheChainAndReturnsEmpty() {
        AtomicInteger fetches = new AtomicInteger();
        PosterFailover.ThrowingFetch fetch = source -> {
            fetches.incrementAndGet();
            // fetchAsUri restores the flag and rethrows InterruptedException;
            // the chain must treat that as "stop now", not "try the next source".
            throw new InterruptedException("interrupted mid-fetch");
        };

        Option<Tuple2<String, String>> picked = PosterFailover.firstSuccess(
                List.of("https://a/x", "https://b/x"), fetch);

        assertTrue(picked.isEmpty());
        assertEquals(1, fetches.get(), "an interrupt must stop the whole chain");
    }
}
