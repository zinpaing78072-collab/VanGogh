package vangogh.controller;

import io.vavr.control.Option;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The one-slot handoff between an IO-pool producer and the UI-thread drain,
 * keyed by request generation.
 *
 * <p>The naive "check the generation, then set the slot" pattern is racy: a
 * completion can pass the check, lose the CPU to a newer request (which clears
 * the slot), and then park its stale result. Two things go wrong. A poller with
 * the stale generation finds a matching slot and is handed data that is no
 * longer current; a poller with the <em>newer</em> generation either consumes
 * the stale result or waits forever if the newer result parked first and the
 * stale one overwrote it.
 *
 * <p>So parking refuses to displace a newer parked generation, and {@link #take}
 * only ever returns an entry whose generation is the caller's own.
 */
final class LatestResult<T> {

    private record Entry<T>(int generation, T value) {}

    private final AtomicReference<Entry<T>> slot = new AtomicReference<>();

    /** Drops whatever is parked. Called by a new request before it starts. */
    void clear() {
        slot.set(null);
    }

    /**
     * Parks {@code value} for {@code generation}, unless a newer generation is
     * already parked (in which case the caller's result is stale and dropped).
     * The compare-and-set loop is required because the slot is written from the
     * IO pool while a request may be clearing it on the UI thread.
     */
    void park(int generation, T value) {
        Entry<T> next = new Entry<>(generation, value);
        while (true) {
            Entry<T> current = slot.get();
            if (current != null && current.generation() > generation) {
                return;
            }
            if (slot.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * Consumes and returns the entry parked for exactly {@code generation}, or
     * empty when that generation's result has not arrived yet. A result parked
     * for a different generation is left alone: it belongs to another request.
     */
    Option<T> take(int generation) {
        Entry<T> current = slot.get();
        if (current == null || current.generation() != generation) {
            return Option.none();
        }
        if (!slot.compareAndSet(current, null)) {
            // Another poll consumed it first; deliver nothing twice.
            return Option.none();
        }
        return Option.some(current.value());
    }
}
