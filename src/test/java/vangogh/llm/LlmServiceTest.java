package vangogh.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for {@link LlmService} — parses LLM output into {@link Recommendation}
 * records and degrades gracefully on bad input. Uses a stubbed {@link LlmClient}
 * so no network calls are made.
 */
class LlmServiceTest {

    /** Stub client returning canned LLM responses instead of hitting the network. */
    static class StubLlmClient extends LlmClient {
        String lastPrompt;
        String response;

        StubLlmClient() {
            super(new LlmConfig("test-key", "http://localhost/v1", "test-model", "sys-prompt", 5));
        }

        @Override
        public String complete(String userPrompt) {
            lastPrompt = userPrompt;
            return response;
        }
    }

    private LlmService serviceWith(StubLlmClient client) {
        return new LlmService(client); // package-private ctor
    }

    @Test
    void recommendWithBlankQueryReturnsEmptyList() {
        StubLlmClient client = new StubLlmClient();
        LlmService service = serviceWith(client);
        assertTrue(service.recommend("").isEmpty());
        assertTrue(service.recommend("   ").isEmpty());
        assertTrue(service.recommend(null).isEmpty());
        assertNull(client.lastPrompt); // no LLM call made
    }

    @Test
    void recommendWithUnconfiguredClientReturnsEmptyList() {
        // A stub client that returns null simulates an unconfigured/unreachable LLM
        StubLlmClient client = new StubLlmClient();
        client.response = null;
        LlmService service = serviceWith(client);
        assertTrue(service.recommend("Harry Potter").isEmpty());
    }

    @Test
    void recommendParsesValidJsonArray() {
        StubLlmClient client = new StubLlmClient();
        client.response = """
            [
              {"kind":"movie","title":"Blade Runner 2049","reason":"A visually stunning sci-fi sequel."},
              {"kind":"book","title":"Dune","reason":"Sci-fi classic with world-building."}
            ]
            """;
        LlmService service = serviceWith(client);

        List<Recommendation> recs = service.recommend("sci-fi");

        assertEquals(2, recs.size());
        assertEquals("movie", recs.get(0).kind());
        assertEquals("Blade Runner 2049", recs.get(0).title());
        assertEquals("A visually stunning sci-fi sequel.", recs.get(0).reason());
        assertEquals("book", recs.get(1).kind());
        assertEquals("Dune", recs.get(1).title());
        assertEquals("sci-fi", client.lastPrompt);
    }

    @Test
    void recommendStripsMarkdownFences() {
        StubLlmClient client = new StubLlmClient();
        client.response = "```json\n[{\"kind\":\"music\",\"title\":\"Radiohead\",\"reason\":\"Art-rock band.\"}]\n```";
        LlmService service = serviceWith(client);

        List<Recommendation> recs = service.recommend("Radiohead");

        assertEquals(1, recs.size());
        assertEquals("music", recs.get(0).kind());
        assertEquals("Radiohead", recs.get(0).title());
    }

    @Test
    void recommendWithNonArrayReturnsEmptyList() {
        StubLlmClient client = new StubLlmClient();
        client.response = "{\"kind\":\"movie\",\"title\":\"X\",\"reason\":\"y\"}";
        LlmService service = serviceWith(client);
        assertTrue(service.recommend("x").isEmpty());
    }

    @Test
    void recommendWithInvalidJsonReturnsEmptyList() {
        StubLlmClient client = new StubLlmClient();
        client.response = "not json at all";
        LlmService service = serviceWith(client);
        assertTrue(service.recommend("x").isEmpty());
    }

    @Test
    void recommendSkipsItemsWithoutTitle() {
        StubLlmClient client = new StubLlmClient();
        client.response = """
            [
              {"kind":"movie","title":"","reason":"no title"},
              {"kind":"book","title":"Valid Book","reason":"a good book"}
            ]
            """;
        LlmService service = serviceWith(client);
        List<Recommendation> recs = service.recommend("x");
        assertEquals(1, recs.size());
        assertEquals("Valid Book", recs.get(0).title());
    }

    @Test
    void recommendWithNullResponseReturnsEmptyList() {
        StubLlmClient client = new StubLlmClient();
        client.response = null;
        LlmService service = serviceWith(client);
        assertTrue(service.recommend("x").isEmpty());
    }
}
