package vangogh.llm;

import io.vavr.control.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.error.FetchError;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Config resolution: environment first, then {@code ~/.van-gogh/llm.conf},
 * then defaults. A missing key is a value (Left), never an exception, so the
 * page can show setup instructions instead of a stack trace.
 */
class LlmConfigTest {

    @TempDir
    Path temp;

    @Test
    void aMissingKeyIsABadInputValue() throws Exception {
        Either<FetchError, LlmConfig> loaded = LlmConfig.load(temp.resolve("absent.conf"), Map.of());

        assertTrue(loaded.isLeft());
        assertInstanceOf(FetchError.BadInput.class, loaded.getLeft());
        assertEquals("llm", ((FetchError.BadInput) loaded.getLeft()).field());
        assertTrue(((FetchError.BadInput) loaded.getLeft()).reason().contains("OPENAI_API_KEY"));
    }

    @Test
    void theFileProvidesKeyModelAndBaseUrl() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, """
                # local settings
                apiKey = file-key
                baseUrl = https://example.test/v1/
                model = file-model
                timeoutSeconds = 45
                titles = 7
                """);

        LlmConfig config = LlmConfig.load(file, Map.of()).get();

        assertEquals("file-key", config.apiKey());
        assertEquals("https://example.test/v1", config.baseUrl()); // trailing slash stripped
        assertEquals("file-model", config.model());
        assertEquals(45, config.timeoutSeconds());
        assertEquals(7, config.titles());
    }

    @Test
    void environmentOverridesTheFile() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=file-key\nmodel=file-model\nbaseUrl=https://file.test/v1\n");

        LlmConfig config = LlmConfig.load(file, Map.of(
                "OPENAI_API_KEY", "env-key",
                "LLM_MODEL", "env-model",
                "OPENAI_BASE_URL", "https://env.test/v1",
                "LLM_TITLES", "3")).get();

        assertEquals("env-key", config.apiKey());
        assertEquals("env-model", config.model());
        assertEquals("https://env.test/v1", config.baseUrl());
        assertEquals(3, config.titles());
    }

    @Test
    void openRouterKeyIsAcceptedAsAFallback() {
        LlmConfig config = LlmConfig.load(temp.resolve("absent.conf"),
                Map.of("OPENROUTER_API_KEY", "or-key")).get();

        assertEquals("or-key", config.apiKey());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals(LlmConfig.DEFAULT_MODEL, config.model());
    }

    @Test
    void numericSettingsAreClampedAndMalformedValuesFallBack() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=k\ntimeoutSeconds=not-a-number\ntitles=500\n");

        LlmConfig config = LlmConfig.load(file, Map.of()).get();

        assertEquals(LlmConfig.DEFAULT_TIMEOUT_SECONDS, config.timeoutSeconds());
        assertEquals(50, config.titles());

        Files.writeString(file, "apiKey=k\ntimeoutSeconds=1\ntitles=0\n");
        LlmConfig clamped = LlmConfig.load(file, Map.of()).get();
        assertEquals(10, clamped.timeoutSeconds());
        assertEquals(1, clamped.titles());
    }

    @Test
    void aWindowsFileWithBomAndCrLfLoads() throws Exception {
        // Notepad on Windows writes UTF-8 with a BOM and CRLF line endings.
        // Left in place, the BOM becomes part of the first key name and the
        // config silently reads as empty — the "no API key" report on a file
        // that visibly has one.
        Path file = temp.resolve("windows.conf");
        Files.write(file, ("\uFEFFapiKey = win-key\r\nmodel = win-model\r\n")
                .getBytes(StandardCharsets.UTF_8));

        LlmConfig config = LlmConfig.load(file, Map.of()).get();

        assertEquals("win-key", config.apiKey());
        assertEquals("win-model", config.model());
    }

    @Test
    void toStringNeverPrintsTheKey() {
        LlmConfig config = new LlmConfig("https://x/v1", "sk-secret", "m", 60, 10);

        String rendered = config.toString();

        assertFalse(rendered.contains("sk-secret"));
        assertTrue(rendered.contains("***"));
        assertTrue(rendered.contains("https://x/v1"));
    }

    @Test
    void aMissingKeyIsRenderedAsMissing() {
        assertTrue(new LlmConfig("u", null, "m", 60, 10).toString().contains("<missing>"));
    }
}
