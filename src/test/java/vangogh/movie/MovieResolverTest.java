package vangogh.movie;

import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.TmdbClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TMDB resolution rule measured by the probe: exact normalized title
 * equality within the top 5, the year must match when the model gave one, the
 * highest vote count wins, and near-empty rows (under 10 votes) are rejected.
 */
class MovieResolverTest {

    static class StubClient extends TmdbClient {
        Try<ApiResponse> response = Try.success(new ApiResponse(200, "{\"results\":[]}", null));

        StubClient() {
            super("test-key");
        }

        @Override
        public Try<ApiResponse> searchMovie(String query) {
            return response;
        }
    }

    private static String row(int id, String title, String date, int votes) {
        return "{\"id\":" + id
                + ",\"title\":\"" + title + "\""
                + ",\"release_date\":\"" + date + "\""
                + ",\"vote_average\":7.8"
                + ",\"genre_ids\":[28]"
                + ",\"overview\":\"A story.\""
                + ",\"poster_path\":\"/p.jpg\""
                + ",\"vote_count\":" + votes + "}";
    }

    private static String results(String... rows) {
        return "{\"results\":[" + String.join(",", rows) + "]}";
    }

    private static MovieResolver resolverWith(String body) {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(200, body, null));
        return new MovieResolver(client);
    }

    @Test
    void anExactTitleAndYearResolveToACard() {
        MovieResolver resolver = resolverWith(results(
                row(27205, "Inception", "2010-07-16", 35000),
                row(999, "Inception", "2024-01-01", 400000)));

        Option<Movie> resolved = resolver.resolve(new Pick("Inception", "2010")).get();

        assertTrue(resolved.isDefined());
        assertEquals(27205, resolved.get().id());
        assertEquals("Inception", resolved.get().title());
        assertEquals(2010, resolved.get().year());
    }

    @Test
    void matchingIgnoresCaseArticlesAndTypography() {
        MovieResolver resolver = resolverWith(results(
                row(603, "The Matrix", "1999-03-31", 24000)));

        assertTrue(resolver.resolve(new Pick("matrix", null)).get().isDefined());
    }

    @Test
    void aYearMismatchIsACleanMiss() {
        MovieResolver resolver = resolverWith(results(
                row(1, "Dune", "1984-12-14", 3000),
                row(2, "Dune", "2021-09-15", 12000)));

        Option<Movie> resolved = resolver.resolve(new Pick("Dune", "2021")).get();

        assertEquals(2, resolved.get().id());
        assertTrue(resolver.resolve(new Pick("Dune", "1975")).get().isEmpty());
    }

    @Test
    void theHighestVoteCountWinsAmongMatches() {
        MovieResolver resolver = resolverWith(results(
                row(1, "Solaris", "1972-03-20", 500),
                row(2, "Solaris", "2002-11-27", 900)));

        assertEquals(2, resolver.resolve(new Pick("Solaris", null)).get().get().id());
    }

    @Test
    void aNearEmptyRowIsRejected() {
        MovieResolver resolver = resolverWith(results(
                row(1, "Obscure Indie", "2019-01-01", 3)));

        assertTrue(resolver.resolve(new Pick("Obscure Indie", null)).get().isEmpty());
    }

    @Test
    void onlyTheTopFiveResultsAreConsidered() {
        MovieResolver resolver = resolverWith(results(
                row(1, "One", "2001-01-01", 100),
                row(2, "Two", "2002-01-01", 100),
                row(3, "Three", "2003-01-01", 100),
                row(4, "Four", "2004-01-01", 100),
                row(5, "Five", "2005-01-01", 100),
                row(6, "Target", "2006-01-01", 100)));

        assertTrue(resolver.resolve(new Pick("Target", null)).get().isEmpty());
    }

    @Test
    void noResultsIsACleanMiss() {
        assertTrue(resolverWith("{\"results\":[]}")
                .resolve(new Pick("Anything", null)).get().isEmpty());
    }

    @Test
    void anHttpFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(500, "boom", "server error"));

        Either<FetchError, Option<Movie>> result =
                new MovieResolver(client).resolve(new Pick("Inception", "2010"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(500, ((FetchError.HttpStatus) result.getLeft()).status());
    }

    @Test
    void aTransportFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.failure(new RuntimeException("offline"));

        Either<FetchError, Option<Movie>> result =
                new MovieResolver(client).resolve(new Pick("Inception", "2010"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
    }

    @Test
    void aMalformedBodyIsAPayloadLeft() {
        Either<FetchError, Option<Movie>> result =
                resolverWith("not json").resolve(new Pick("Inception", "2010"));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }
}
