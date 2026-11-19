package vangogh.recommend;

import io.vavr.control.Either;
import io.vavr.control.Option;
import vangogh.error.FetchError;
import vangogh.llm.Pick;

/**
 * Resolves one model pick against one content source.
 *
 * <p>Two levels of "not a card" are kept apart: {@code Right(None)} means the
 * catalog was searched and the work is not in it (a clean miss, normal), while
 * {@code Left} means the lookup itself failed (transport/HTTP/payload). The
 * recommendation service drops {@code None} picks and only reports an error
 * when every pick errored.
 */
@FunctionalInterface
public interface PickResolver<T> {

    Either<FetchError, Option<T>> resolve(Pick pick);
}
