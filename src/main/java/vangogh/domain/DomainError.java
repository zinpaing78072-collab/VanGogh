package vangogh.domain;

import java.io.IOException;

/**
 * Closed set of domain-level errors returned by content services.
 *
 * <p>Services return {@code Either<DomainError, List<T>>}: {@code Right(items)}
 * on success, {@code Left(error)} on failure. The sealed hierarchy lets
 * callers exhaustively pattern-match instead of catching exceptions.
 */
public sealed interface DomainError {
    record ServiceUnavailable(String source, int statusCode, String detail) implements DomainError {
        public String message() {
            return source + " HTTP " + statusCode + " failed: " + detail;
        }
    }
    record NetworkError(String source, String detail) implements DomainError {
        public String message() {
            return source + " network error: " + detail;
        }
    }
    record ParseError(String source, String detail) implements DomainError {
        public String message() {
            return source + " parse failed: " + detail;
        }
    }
    record Unexpected(String detail) implements DomainError {
        public String message() {
            return "unexpected error: " + detail;
        }
    }

    String message();

    static DomainError fromThrowable(String source, Throwable t) {
        if (t instanceof IOException) {
            return new NetworkError(source, t.getMessage());
        }
        return new Unexpected(t.getMessage());
    }
}
