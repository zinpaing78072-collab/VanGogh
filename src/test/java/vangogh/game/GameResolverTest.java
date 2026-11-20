package vangogh.game;

import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;
import vangogh.llm.Pick;
import vangogh.net.ApiResponse;
import vangogh.net.TgdbClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TheGamesDB resolution rule from the design: exact normalized
 * {@code game_title}, the release year must match when the model supplied one,
 * and duplicate rows collapse by id. TheGamesDB has no popularity data, so
 * there is no vote floor; a matching row is a match.
 */
class GameResolverTest {

    static class StubClient extends TgdbClient {
        Try<ApiResponse> response = Try.success(new ApiResponse(200, payload(), null));

        StubClient() {
            super("stub-key");
        }

        @Override
        public Try<ApiResponse> searchGame(String query) {
            return response;
        }
    }

    private static String game(int id, String title, String releaseDate) {
        return "{\"id\":" + id
                + ",\"game_title\":\"" + title + "\""
                + ",\"release_date\":\"" + releaseDate + "\""
                + ",\"overview\":\"An adventure.\","
                + "\"genres\":[1]}";
    }

    private static String payload(String... rows) {
        return "{\"data\":{\"games\":[" + String.join(",", rows) + "]},"
                + "\"include\":{\"boxart\":{\"data\":{"
                + "\"42\":[{\"type\":\"boxart\",\"side\":\"front\","
                + "\"filename\":\"boxart/front/front.jpg\"}]}}}}";
    }

    private static GameResolver resolverWith(String body) {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(200, body, null));
        return new GameResolver(client);
    }

    @Test
    void anExactNameAndYearResolveToACard() {
        GameResolver resolver = resolverWith(payload(game(42, "Sample Game", "2024-06-15")));

        Option<Game> resolved = resolver.resolve(new Pick("Sample Game", "2024")).get();

        assertTrue(resolved.isDefined());
        assertEquals(42, resolved.get().id());
        assertEquals("Sample Game", resolved.get().game_title());
        assertEquals("2024-06-15", resolved.get().release_date());
        assertEquals("An adventure.", resolved.get().overview());
        assertEquals("https://cdn.thegamesdb.net/images/original/boxart/front/front.jpg",
                resolved.get().posterUrl());
    }

    @Test
    void matchingIgnoresCaseArticlesAndPunctuation() {
        GameResolver resolver = resolverWith(payload(
                game(1, "The Legend of Zelda: Breath of the Wild", "2017-03-03")));

        assertTrue(resolver
                .resolve(new Pick("legend of zelda breath of the wild", null))
                .get().isDefined());
    }

    @Test
    void aYearMismatchIsACleanMiss() {
        GameResolver resolver = resolverWith(payload(game(1, "Doom", "2016-05-13")));

        assertTrue(resolver.resolve(new Pick("Doom", "1993")).get().isEmpty());
    }

    @Test
    void aMissingYearOnTheRowIsAllowedOnlyWhenTheModelGaveNoYear() {
        GameResolver resolver = resolverWith(payload(game(1, "Doom", "")));

        assertTrue(resolver.resolve(new Pick("Doom", null)).get().isDefined());
        assertTrue(resolver.resolve(new Pick("Doom", "1993")).get().isEmpty());
    }

    @Test
    void aNonExactNameIsACleanMiss() {
        GameResolver resolver = resolverWith(payload(game(1, "Doom Eternal", "2020-03-20")));

        assertTrue(resolver.resolve(new Pick("Doom", "2020")).get().isEmpty());
    }

    @Test
    void duplicateRowsCollapseAndStillResolve() {
        GameResolver resolver = resolverWith(payload(
                game(42, "Sample Game", "2024-06-15"),
                game(42, "Sample Game", "2024-06-15")));

        Option<Game> resolved = resolver.resolve(new Pick("Sample Game", "2024")).get();

        assertTrue(resolved.isDefined());
        assertEquals(42, resolved.get().id());
    }

    @Test
    void aDuplicateThatDoesNotMatchDoesNotBlockALaterMatch() {
        GameResolver resolver = resolverWith(payload(
                game(42, "Sample Game", "1999-01-01"),
                game(42, "Sample Game", "2024-06-15")));

        // Same id, different release rows: dedupe must not hide the row whose
        // year satisfies the pick.
        assertTrue(resolver.resolve(new Pick("Sample Game", "2024")).get().isDefined());
    }

    @Test
    void anHttpFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.success(new ApiResponse(503, "busy", "try later"));

        Either<FetchError, Option<Game>> result = new GameResolver(client)
                .resolve(new Pick("Sample Game", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(503, ((FetchError.HttpStatus) result.getLeft()).status());
    }

    @Test
    void aTransportFailureIsALeft() {
        StubClient client = new StubClient();
        client.response = Try.failure(new RuntimeException("offline"));

        Either<FetchError, Option<Game>> result = new GameResolver(client)
                .resolve(new Pick("Sample Game", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
    }

    @Test
    void aMalformedBodyIsAPayloadLeft() {
        Either<FetchError, Option<Game>> result = resolverWith("not json")
                .resolve(new Pick("Sample Game", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void aPayloadWithoutDataGamesIsAPayloadLeft() {
        Either<FetchError, Option<Game>> result = resolverWith("{\"include\":{}}")
                .resolve(new Pick("Sample Game", null));

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }
}
