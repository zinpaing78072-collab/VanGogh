package vangogh.net;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.Stream;
import io.vavr.control.Option;
import io.vavr.control.Try;

import java.util.List;

/**
 * Shared poster failover: try each source (proxy / direct) in order, stop at
 * the first success. vavr's {@code Stream} is lazy, so only sources up to the
 * winner are actually fetched; an interrupted fetch stops the whole chain
 * (interrupt flag restored inside the fetch) and yields {@link Option#none()}.
 *
 * <p>Uses {@code find(...)} — NOT {@code takeUntil(...).lastOption()}.
 * vavr's {@code takeUntil} is {@code takeWhile(negate)}: it EXCLUDES the
 * element satisfying the predicate, so the original chain dropped the winning
 * attempt in every source ordering and posters never loaded (regression pinned
 * by {@code PosterFailoverTest}).
 */
final class PosterFailover {

    private PosterFailover() {
    }

    /** One poster source fetch as a throwing function, so {@link Try} can carry failures. */
    interface ThrowingFetch {
        String fetch(String source) throws Exception;
    }

    /**
     * Non-fatal marker for an interrupted fetch: vavr treats
     * {@link InterruptedException} as fatal ({@code Try.of} rethrows it
     * sneakily), so the interrupt is caught at the fetch boundary, the flag is
     * restored, and a non-fatal marker carries the stop signal through the
     * {@code Try} chain instead.
     */
    static final class Interrupted extends IllegalStateException {
        Interrupted(InterruptedException cause) {
            super("poster fetch interrupted", cause);
        }
    }

    /**
     * Runs {@code fetch} over {@code sources} in order and returns the winning
     * (source, dataUri) pair: the first successful fetch, or {@code none} when
     * every source fails or the chain was interrupted (interrupt flag restored).
     */
    static Option<Tuple2<String, String>> firstSuccess(List<String> sources,
                                                       ThrowingFetch fetch) {
        return Stream.ofAll(sources)
                .map(source -> {
                    try {
                        return Try.success(Tuple.of(source, fetch.fetch(source)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Try.<Tuple2<String, String>>failure(new Interrupted(e));
                    } catch (Exception e) {
                        return Try.<Tuple2<String, String>>failure(e);
                    }
                })
                .find(attempt -> attempt.isSuccess()
                        || attempt.getCause() instanceof Interrupted)
                .flatMap(attempt -> attempt.toOption());
    }
}

