package vangogh.llm;

import io.vavr.control.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.error.FetchError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Config resolution: {@code ~/.van-gogh/llm.conf} then defaults, with no
 * environment layer (a saved key must be the key the engine uses). A missing
 * key is a value (Left), never an exception, so the page can show setup
 * instructions instead of a stack trace. Saving is the Settings page's write
 * path: normalized, atomic, owner-only.
 */
class LlmConfigTest {

    @TempDir
    Path temp;

    @Test
    void aMissingKeyIsABadInputValue() {
        Either<FetchError, LlmConfig> loaded = LlmConfig.load(temp.resolve("absent.conf"));

        assertTrue(loaded.isLeft());
        assertInstanceOf(FetchError.BadInput.class, loaded.getLeft());
        assertEquals("llm", ((FetchError.BadInput) loaded.getLeft()).field());
        String reason = ((FetchError.BadInput) loaded.getLeft()).reason();
        assertTrue(reason.contains("no API key"), reason);
        assertTrue(reason.contains("Settings"), reason);
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
                temperature = 1.3
                """);

        LlmConfig config = LlmConfig.load(file).get();

        assertEquals("file-key", config.apiKey());
        assertEquals("https://example.test/v1", config.baseUrl()); // trailing slash stripped
        assertEquals("file-model", config.model());
        assertEquals(45, config.timeoutSeconds());
        assertEquals(7, config.titles());
        assertEquals(1.3, config.temperature());
    }

    @Test
    void readFillsDefaultsAndToleratesAMissingKey() {
        LlmConfig config = LlmConfig.read(temp.resolve("absent.conf"));

        assertNull(config.apiKey());
        assertFalse(config.hasApiKey());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals(LlmConfig.DEFAULT_MODEL, config.model());
        assertEquals(LlmConfig.DEFAULT_TIMEOUT_SECONDS, config.timeoutSeconds());
        assertEquals(LlmConfig.DEFAULT_TITLES, config.titles());
        assertEquals(LlmConfig.DEFAULT_TEMPERATURE, config.temperature());
    }

    @Test
    void numericSettingsAreClampedAndMalformedValuesFallBack() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=k\ntimeoutSeconds=not-a-number\ntitles=500\n");

        LlmConfig config = LlmConfig.load(file).get();

        assertEquals(LlmConfig.DEFAULT_TIMEOUT_SECONDS, config.timeoutSeconds());
        assertEquals(50, config.titles());

        Files.writeString(file, "apiKey=k\ntimeoutSeconds=1\ntitles=0\n");
        LlmConfig clamped = LlmConfig.load(file).get();
        assertEquals(10, clamped.timeoutSeconds());
        assertEquals(1, clamped.titles());
    }

    @Test
    void temperatureFallsBackToTheDefaultWhenMalformedOrNotFinite() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=k\ntemperature=warm\n");
        assertEquals(LlmConfig.DEFAULT_TEMPERATURE, LlmConfig.load(file).get().temperature());

        Files.writeString(file, "apiKey=k\ntemperature=NaN\n");
        assertEquals(LlmConfig.DEFAULT_TEMPERATURE, LlmConfig.load(file).get().temperature());

        Files.writeString(file, "apiKey=k\ntemperature=Infinity\n");
        assertEquals(LlmConfig.DEFAULT_TEMPERATURE, LlmConfig.load(file).get().temperature());
    }

    @Test
    void temperatureIsClampedToTheProvidersRange() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=k\ntemperature=9\n");
        assertEquals(LlmConfig.MAX_TEMPERATURE, LlmConfig.load(file).get().temperature());

        Files.writeString(file, "apiKey=k\ntemperature=-1\n");
        assertEquals(LlmConfig.MIN_TEMPERATURE, LlmConfig.load(file).get().temperature());
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

        LlmConfig config = LlmConfig.load(file).get();

        assertEquals("win-key", config.apiKey());
        assertEquals("win-model", config.model());
    }

    @Test
    void savingRoundsTripThroughTheReader() throws Exception {
        Path file = temp.resolve("nested/llm.conf");

        LlmConfig saved = new LlmConfig(
                "https://example.test/v1/", "new-key", "model-x", 45, 7, 1.25).save(file).get();

        // The reply the page paints is what was normalized and written.
        assertEquals("https://example.test/v1", saved.baseUrl());
        assertEquals("model-x", saved.model());
        assertEquals(45, saved.timeoutSeconds());
        assertEquals(7, saved.titles());
        assertEquals(1.25, saved.temperature());

        LlmConfig reread = LlmConfig.load(file).get();
        assertEquals(saved, reread);
        assertTrue(Files.readString(file).contains("apiKey = new-key"));
        assertTrue(Files.readString(file).contains("temperature = 1.25"));
    }

    @Test
    void savingClampsNumbers() throws Exception {
        Path file = temp.resolve("llm.conf");

        LlmConfig saved = new LlmConfig(
                "https://example.test/v1", "k", "m", 5000, 0, 9).save(file).get();

        assertEquals(300, saved.timeoutSeconds());
        assertEquals(1, saved.titles());
        assertEquals(LlmConfig.MAX_TEMPERATURE, saved.temperature());
        assertEquals(saved, LlmConfig.read(file));
    }

    @Test
    void savingReplacesTheFileWithoutLeavingTempFiles() throws Exception {
        Path file = temp.resolve("llm.conf");
        Files.writeString(file, "apiKey=old\nmodel=old-model\n");

        new LlmConfig("https://example.test/v1", "fresh", "new-model", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(file).get();

        assertFalse(Files.readString(file).contains("old-model"));
        try (var entries = Files.list(temp)) {
            assertEquals(List.of("llm.conf"),
                    entries.map(entry -> entry.getFileName().toString()).toList());
        }
    }

    @Test
    void aSavedFileIsOwnerOnlyOnPosix() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file = temp.resolve("llm.conf");

        new LlmConfig("https://example.test/v1", "k", "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(file).get();

        Set<PosixFilePermission> expected = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertEquals(expected, Files.getPosixFilePermissions(file));
    }

    @Test
    void savingRequiresAKeyABaseUrlAndAModel() {
        Path file = temp.resolve("llm.conf");

        assertInstanceOf(FetchError.BadInput.class,
                new LlmConfig("https://x/v1", null, "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(file).getLeft());
        assertInstanceOf(FetchError.BadInput.class,
                new LlmConfig("  ", "k", "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(file).getLeft());
        assertInstanceOf(FetchError.BadInput.class,
                new LlmConfig("https://x/v1", "k", null, 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(file).getLeft());

        assertFalse(Files.exists(file), "nothing may be written when validation fails");
    }

    @Test
    void anUnwritableTargetIsATransportValue() throws IOException {
        Path directory = temp.resolve("conf-dir");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("occupied.txt"), "keep");

        Either<FetchError, LlmConfig> saved = new LlmConfig(
                "https://x/v1", "k", "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).save(directory);

        assertTrue(saved.isLeft());
        assertInstanceOf(FetchError.Transport.class, saved.getLeft());
    }

    @Test
    void toStringNeverPrintsTheKey() {
        LlmConfig config = new LlmConfig("https://x/v1", "sk-secret", "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE);

        String rendered = config.toString();

        assertFalse(rendered.contains("sk-secret"));
        assertTrue(rendered.contains("***"));
        assertTrue(rendered.contains("https://x/v1"));
    }

    @Test
    void aMissingKeyIsRenderedAsMissing() {
        assertTrue(new LlmConfig("u", null, "m", 60, 10, LlmConfig.DEFAULT_TEMPERATURE).toString().contains("<missing>"));
    }
}
