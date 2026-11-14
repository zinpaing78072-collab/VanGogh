package vangogh.error;

/**
 * The one error vocabulary for every effect-full operation in Van Gogh
 * (fetch, parse, build — movies, games, and the later music/book/LLM slices).
 *
 * <p>Expected failures are values in the result type
 * ({@code Either<FetchError, T>} / {@code Validation<Seq<FetchError>, T>}) instead of
 * invented {@code RuntimeException}s. {@link #uiMessage} is the single place that
 * renders an error for humans; the Java 25 exhaustive switch makes adding a new
 * content form a compile-time checklist.
 */
public sealed interface FetchError {

    /** The underlying cause, when the error came from an exception. Null otherwise. */
    default Throwable cause() {
        return null;
    }

    /** Renders an error for humans — the only place that translates to UI text. */
    static String uiMessage(FetchError error) {
        return switch (error) {
            case Transport t -> t.op() + " failed: " + t.cause().getMessage();
            case HttpStatus h -> h.api() + " failed: HTTP " + h.status() + " " + h.bodyExcerpt();
            case Payload p -> p.api() + " payload unreadable (" + p.where() + ")";
            case BadInput b -> b.field() + ": " + b.reason();
        };
    }

    /** The network call itself blew up (connection refused, DNS, timeout). */
    record Transport(String op, Throwable cause) implements FetchError {}

    /** The API answered, but with a non-2xx status — an expected outcome, not an exception. */
    record HttpStatus(String api, int status, String bodyExcerpt) implements FetchError {}

    /** The API answered 2xx, but the body is not the shape we can read. */
    record Payload(String api, String where, Throwable cause) implements FetchError {}

    /** A caller-side argument is missing or malformed (e.g. an IPC command's args). */
    record BadInput(String field, String reason) implements FetchError {}
}
