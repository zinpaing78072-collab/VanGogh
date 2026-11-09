package vangogh.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmConfig} and {@link LlmClient} configuration behavior.
 * No network calls are made; the client is verified to degrade gracefully when
 * the LLM is unconfigured.
 */
class LlmClientTest {

    @Test
    void fromEnvWithoutApiKeyIsNotConfigured() {
        LlmConfig config = LlmConfig.fromEnv();
        // With no LLM_API_KEY set in this environment, the client is unconfigured.
        assertNull(config.apiKey());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertEquals("gpt-3.5-turbo", config.model());
        assertEquals(30, config.timeoutSeconds());
        assertFalse(config.isConfigured());
    }

    @Test
    void configWithApiKeyIsConfigured() {
        LlmConfig config = new LlmConfig("sk-test123", "http://localhost:11434/v1/", "llama3", "sys", 10);
        assertEquals("sk-test123", config.apiKey());
        assertEquals("http://localhost:11434/v1/", config.baseUrl()); // constructor does not strip slashes; fromEnv() does
        assertEquals("llama3", config.model());
        assertEquals(10, config.timeoutSeconds());
        assertTrue(config.isConfigured());
    }

    @Test
    void configWithoutApiKeyIsNotConfigured() {
        LlmConfig config = new LlmConfig(null, "http://localhost/v1", "model", "sys", 5);
        assertFalse(config.isConfigured());
    }

    @Test
    void unconfiguredClientCompleteReturnsNull() {
        LlmClient client = new LlmClient(new LlmConfig(null, "http://localhost/v1", "model", "sys", 5));
        assertNull(client.complete("Harry Potter"));
    }

    @Test
    void recommendationRecordStoresAllFields() {
        Recommendation r = new Recommendation("movie", "Blade Runner 2049", "A visually stunning sci-fi sequel.");
        assertEquals("movie", r.kind());
        assertEquals("Blade Runner 2049", r.title());
        assertEquals("A visually stunning sci-fi sequel.", r.reason());
    }

    @Test
    void recommendationRecordEqualsAndHashCode() {
        Recommendation a = new Recommendation("book", "Dune", "Sci-fi classic.");
        Recommendation b = new Recommendation("book", "Dune", "Sci-fi classic.");
        Recommendation c = new Recommendation("movie", "Dune", "Film adaptation.");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
