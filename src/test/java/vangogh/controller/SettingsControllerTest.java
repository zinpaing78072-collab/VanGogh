package vangogh.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.llm.LlmConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Settings page's backend: read for the form, write on Save. The stored
 * key must never appear in a reply, and a blank key on save keeps the secret
 * the page cannot read.
 */
class SettingsControllerTest {

    @TempDir
    Path temp;

    private Path file() {
        return temp.resolve("llm.conf");
    }

    @Test
    void loadWithoutAFilePaintsDefaultsAndNoKey() {
        var reply = new SettingsController(file()).loadLlmSettings();

        assertEquals("ok", reply.status());
        assertNull(reply.error());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, reply.baseUrl());
        assertEquals(LlmConfig.DEFAULT_MODEL, reply.model());
        assertEquals(LlmConfig.DEFAULT_TIMEOUT_SECONDS, reply.timeoutSeconds());
        assertEquals(LlmConfig.DEFAULT_TITLES, reply.titles());
        assertFalse(reply.hasApiKey());
        assertEquals(file().toString(), reply.configPath());
    }

    @Test
    void loadPaintsTheStoredValuesWithoutTheKey() throws Exception {
        Files.writeString(file(), """
                apiKey = secret-key
                baseUrl = https://example.test/v1
                model = stored-model
                timeoutSeconds = 45
                titles = 7
                """);

        var reply = new SettingsController(file()).loadLlmSettings();

        assertEquals("ok", reply.status());
        assertEquals("https://example.test/v1", reply.baseUrl());
        assertEquals("stored-model", reply.model());
        assertEquals(45, reply.timeoutSeconds());
        assertEquals(7, reply.titles());
        assertTrue(reply.hasApiKey());
        assertFalse(reply.toString().contains("secret-key"), "the key must not cross the bridge");
    }

    @Test
    void saveWritesTheFileAndReturnsTheNormalizedValues() {
        var reply = new SettingsController(file()).saveLlmSettings(
                "https://example.test/v1/", "model-x", "fresh-key", 45, 7);

        assertEquals("ok", reply.status());
        assertNull(reply.error());
        assertEquals("https://example.test/v1", reply.baseUrl());
        assertEquals("model-x", reply.model());
        assertEquals(45, reply.timeoutSeconds());
        assertEquals(7, reply.titles());
        assertTrue(reply.hasApiKey());
        assertTrue(Files.exists(file()));
    }

    @Test
    void aBlankKeyKeepsTheStoredKey() throws Exception {
        new SettingsController(file()).saveLlmSettings(
                "https://example.test/v1", "model-a", "original-key", 60, 10);

        var reply = new SettingsController(file()).saveLlmSettings(
                "https://example.test/v1", "model-b", "", 60, 10);

        assertEquals("ok", reply.status());
        LlmConfig reread = LlmConfig.load(file()).get();
        assertEquals("original-key", reread.apiKey());
        assertEquals("model-b", reread.model());
    }

    @Test
    void savingWithNoKeyAnywhereIsAnError() {
        var reply = new SettingsController(file()).saveLlmSettings(
                "https://example.test/v1", "model", "   ", 60, 10);

        assertEquals("error", reply.status());
        assertTrue(reply.error().contains("apiKey"), reply.error());
        assertFalse(Files.exists(file()));
    }

    @Test
    void invalidInputIsReportedAsAValueNotAThrow() {
        var reply = new SettingsController(file()).saveLlmSettings(
                "  ", "model", "key", 60, 10);

        assertEquals("error", reply.status());
        assertTrue(reply.error().contains("baseUrl"), reply.error());
        assertFalse(Files.exists(file()));
    }

    @Test
    void numbersAreClampedOnTheWayIn() {
        var reply = new SettingsController(file()).saveLlmSettings(
                "https://example.test/v1", "model", "key", 9000, 0);

        assertEquals(300, reply.timeoutSeconds());
        assertEquals(1, reply.titles());
    }
}
