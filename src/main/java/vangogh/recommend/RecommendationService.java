package vangogh.recommend;

import io.vavr.collection.List;
import io.vavr.concurrent.Future;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import vangogh.error.FetchError;
import vangogh.llm.Form;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.llm.Pick;
import vangogh.llm.PromptFactory;
import vangogh.llm.TitleParser;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * The recommendation pipeline: one model request composes the picks, then each
 * pick is resolved against the active form's catalog in parallel.
 *
 * <pre>
 * query → prompt → LlmClient → TitleParser → PickResolver per pick → cards
 * </pre>
 *
 * <p>Runs entirely on the caller's IO pool; the Krema command only kicks it
 * off, so the UI thread never blocks. The parallel resolution needs at least
 * two pool threads (the calling thread blocks on the join while the picks
 * run), which every controller's six-thread pool satisfies.
 *
 * <p>Partial lists are success: unresolved picks are dropped. The request is
 * a {@code Left} only when the model call fails, or when zero picks resolved
 * and at least one lookup errored — so "the network ate everything" never
 * renders as "no recommendations".
 */
public class RecommendationService {

    private final ExecutorService executor;
    private final LlmClient llm;
    private final Supplier<Either<FetchError, LlmConfig>> config;

    public RecommendationService(ExecutorService executor) {
        this(executor, new LlmClient(), LlmConfig::load);
    }

    public RecommendationService(ExecutorService executor, LlmClient llm,
                                 Supplier<Either<FetchError, LlmConfig>> config) {
        this.executor = executor;
        this.llm = llm;
        this.config = config;
    }

    public <T> Either<FetchError, java.util.List<T>> recommend(
            Form form, String query, PickResolver<T> resolver) {

        if (query == null || query.isBlank()) {
            return Either.left(new FetchError.BadInput("query",
                    "AI recommendations need a query"));
        }

        return config.get().flatMap(settings ->
                complete(settings, form, query.trim())
                        .flatMap(reply -> resolve(reply, settings.titles(), resolver)));
    }

    private Either<FetchError, String> complete(LlmConfig config, Form form, String query) {
        return llm.complete(
                config,
                PromptFactory.SYSTEM,
                PromptFactory.user(form, query, config.titles()));
    }

    private <T> Either<FetchError, java.util.List<T>> resolve(
            String reply, int titles, PickResolver<T> resolver) {

        TitleParser.Parsed parsed = TitleParser.parse(reply, titles);
        if (parsed.picks().isEmpty()) {
            return Either.left(new FetchError.Payload(
                    "LLM", "reply contained no titles", null));
        }

        io.vavr.collection.Seq<Either<FetchError, Option<T>>> outcomes =
                Future.traverse(executor, parsed.picks(), pick ->
                        Future.of(executor, () -> resolveSafely(resolver, pick))).get();

        List<Either<FetchError, Option<T>>> all = List.ofAll(outcomes);
        List<T> resolved = all.flatMap(outcome ->
                outcome.getOrElse(Option.<T>none()).toList());

        if (resolved.isEmpty() && all.exists(Either::isLeft)) {
            FetchError firstError = all.filter(Either::isLeft).head().getLeft();
            return Either.left(firstError);
        }
        return Either.right(resolved.toJavaList());
    }

    /** A throwing resolver must not escape the fan-out and kill the drain. */
    private static <T> Either<FetchError, Option<T>> resolveSafely(
            PickResolver<T> resolver, Pick pick) {
        return Try.of(() -> resolver.resolve(pick))
                .recover(error -> Either.<FetchError, Option<T>>left(
                        new FetchError.Transport("recommendation resolve", error)))
                .get();
    }
}
