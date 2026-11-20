package vangogh.theme;

import io.vavr.control.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.error.FetchError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The theme file is a preference, not a secret: an unreadable or hand-edited
 * value must fall back to the default skin instead of locking the user out, and
 * the registry in {@link ThemeConfig#KNOWN} is what the Settings page renders.
 */
class ThemeConfigTest {

    @TempDir
    Path temp;

    private Path file() {
        return temp.resolve("theme.conf");
    }

    @Test
    void missingFileReadsAsTheDefaultTheme() {
        assertEquals(ThemeConfig.DEFAULT_THEME, ThemeConfig.read(file()).id());
    }

    @Test
    void storedThemeRoundTrips() throws Exception {
        ThemeConfig.of("liquidneon").save(file());

        assertEquals("liquidneon", ThemeConfig.read(file()).id());
        assertTrue(Files.readString(file()).contains("theme = liquidneon"));
    }

    @Test
    void unknownStoredThemeFallsBackToTheDefault() throws Exception {
        Files.writeString(file(), "theme = vaporwave\n");

        assertEquals("starry", ThemeConfig.read(file()).id());
    }

    @Test
    void unknownThemeIsRejectedOnSave() {
        Either<FetchError, ThemeConfig> result = ThemeConfig.of("vaporwave").save(file());

        assertTrue(result.isLeft(), "an unknown id is a caller bug, not a preference");
        assertFalse(Files.exists(file()), "nothing may be written on a rejected save");
    }

    @Test
    void commentsAndBomAreTolerated() throws Exception {
        Files.writeString(file(), "\uFEFF# written by hand\n; still a comment\n\n  theme = liquidneon  \n");

        assertEquals("liquidneon", ThemeConfig.read(file()).id());
    }

    @Test
    void blankValueFallsBackToTheDefault() throws Exception {
        Files.writeString(file(), "theme = \n");

        assertEquals("starry", ThemeConfig.read(file()).id());
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() throws Exception {
        ThemeConfig.of("liquidneon").save(file());

        try (var entries = Files.list(temp)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("theme.conf"), names);
        }
    }

    @Test
    void everyKnownThemeHasAnIdAndALabel() {
        for (ThemeConfig.Choice choice : ThemeConfig.known()) {
            assertFalse(choice.id().isBlank());
            assertFalse(choice.label().isBlank());
            assertTrue(choice.id().matches("[a-z][a-z0-9]*"),
                    "the id becomes theme-<id>.css: " + choice.id());
        }
    }
}
