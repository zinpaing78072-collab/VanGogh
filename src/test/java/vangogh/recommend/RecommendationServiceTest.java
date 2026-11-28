package vangogh.recommend;

import io.vavr.control.Either;
import io.vavr.control.Option;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;
import vangogh.llm.Form;
import vangogh.llm.LlmClient;
import vangogh.llm.LlmConfig;
import vangogh.llm.Pick;
import vangogh.movie.Movie;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pipeline's contract: one model call per request, partial lists are
 * success, and a Left only when the model call failed or every lookup errored.
 * The model itself is stubbed, so these tests never touch the network.
 */
class RecommendationServiceTest {

    private static final LlmConfig CONFIG =
            new LlmConfig("https://llm.test/v1", "k", "test-model", 10, 5, LlmConfig.DEFAULT_TEMPERATURE);

    /** Stub client: records the prompt and returns a canned reply. */
    static class StubLlm extends LlmClient {
        Either<FetchError, String> result = Either.right("A | 2000");
        final List<String> users = new ArrayList<>();
        int calls;

        StubLlm() {
            super();
        }

        @Override
        public Either<FetchError, String> complete(LlmConfig config, String system, String user) {
            calls++;
            users.add(user);
            return result;
        }
    }

    private ExecutorService io;
    private StubLlm llm;
    private Supplier<Either<FetchError, LlmConfig>> config;

    @BeforeEach
    void setUp() {
        io = Executors.newFixedThreadPool(4);
        llm = new StubLlm();
        config = () -> Either.right(CONFIG);
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
    }

    private RecommendationService service() {
        return new RecommendationService(io, llm, config);
    }

    private static Movie movie(int id, String title) {
        return new Movie(id, title, 2000, 7.5, "Drama", "A story.", "https://img.test/p.jpg");
    }

    private static PickResolver<Movie> resolves(Movie... movies) {
        return pick -> {
            for (Movie movie : movies) {
                if (movie.title().equals(pick.title())) {
                    return Either.right(Option.some(movie));
                }
            }
            return Either.right(Option.none());
        };
    }

    @Test
    void oneModelCallComposesThePicksAndResolvesThem() {
        llm.result = Either.right("Alpha | 2000\nBeta | 2001\nGamma | 2002");

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(
                        movie(1, "Alpha"), movie(2, "Beta"), movie(3, "Gamma")));

        assertTrue(result.isRight());
        assertEquals(3, result.get().size());
        assertEquals("Alpha", result.get().get(0).title());
        assertEquals(1, llm.calls);
        assertTrue(llm.users.get(0).contains("List 5 movie titles related to: dreams"),
                llm.users.get(0));
    }

    @Test
    void unresolvedPicksAreDroppedWithoutFailingTheList() {
        llm.result = Either.right("Alpha | 2000\nMissing | 2001\nGamma | 2002");

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha"), movie(3, "Gamma")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
    }

    @Test
    void aTransportFailureIsALeft() {
        llm.result = Either.left(new FetchError.Transport(
                "LLM request", new RuntimeException("offline")));

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
        assertEquals(1, llm.calls);
    }

    @Test
    void aFinal429IsRenderedAsAFriendlyRateLimitMessage() {
        llm.result = Either.left(new FetchError.BadInput(
                "AI recommendations", "rate-limited — try again in a minute"));

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.BadInput.class, result.getLeft());
        assertTrue(FetchError.uiMessage(result.getLeft()).contains("rate-limited"),
                FetchError.uiMessage(result.getLeft()));
    }

    @Test
    void anyOtherHttpStatusIsCarriedThrough() {
        llm.result = Either.left(new FetchError.HttpStatus("LLM", 502, "bad gateway"));

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        FetchError.HttpStatus error = (FetchError.HttpStatus) result.getLeft();
        assertEquals(502, error.status());
        assertEquals("LLM", error.api());
    }

    @Test
    void aReplyWithoutAnyTitlesIsAPayloadError() {
        llm.result = Either.right("Here are some picks:");

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void aBlankQueryIsBadInputAndNeverCallsTheModel() {
        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "   ", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.BadInput.class, result.getLeft());
        assertEquals(0, llm.calls);
    }

    @Test
    void aMissingConfigNeverCallsTheModel() {
        config = () -> Either.left(new FetchError.BadInput("llm", "no API key"));

        Either<FetchError, List<Movie>> result = service().recommend(
                Form.MOVIE, "dreams", resolves(movie(1, "Alpha")));

        assertTrue(result.isLeft());
        assertEquals(0, llm.calls);
    }

    @Test
    void everyLookupFailingSurfacesTheErrorInsteadOfAnEmptyList() {
        // "Everything errored" must not render as "no recommendations".
        PickResolver<Movie> failing = pick -> Either.left(
                new FetchError.HttpStatus("TMDB search", 503, "down"));

        llm.result = Either.right("Alpha | 2000\nBeta | 2001");

        Either<FetchError, List<Movie>> result =
                service().recommend(Form.MOVIE, "dreams", failing);

        assertTrue(result.isLeft());
        assertEquals(503, ((FetchError.HttpStatus) result.getLeft()).status());
    }

    @Test
    void aPartialListSurvivesOneFailedLookup() {
        PickResolver<Movie> mixed = pick -> pick.title().equals("Alpha")
                ? Either.right(Option.some(movie(1, "Alpha")))
                : Either.left(new FetchError.HttpStatus("TMDB search", 503, "down"));

        llm.result = Either.right("Alpha | 2000\nBeta | 2001");

        Either<FetchError, List<Movie>> result = service().recommend(Form.MOVIE, "dreams", mixed);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
    }

    @Test
    void aThrowingResolverIsContained() {
        PickResolver<Movie> throwing = pick -> {
            if (pick.title().equals("Beta")) {
                throw new IllegalStateException("resolver bug");
            }
            return Either.right(Option.some(movie(1, "Alpha")));
        };

        llm.result = Either.right("Alpha | 2000\nBeta | 2001");

        Either<FetchError, List<Movie>> result = service().recommend(Form.MOVIE, "dreams", throwing);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
    }

    @Test
    void theConfiguredTitleCountReachesThePrompt() {
        config = () -> Either.right(new LlmConfig("https://llm.test/v1", "k", "m", 10, 3, LlmConfig.DEFAULT_TEMPERATURE));
        llm.result = Either.right("Alpha | 2000");

        service().recommend(Form.MUSIC, "rainy nights", resolves(movie(1, "Alpha")));

        assertTrue(llm.users.get(0).contains("List 3 song titles related to: rainy nights"),
                llm.users.get(0));
    }
}
