package vangogh.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Appearance card's backend. The reply must always carry a known theme and
 * the full chip list, and a bad id must be refused without touching the file.
 */
class ThemeControllerTest {

    @TempDir
    Path temp;

    private Path file() {
        return temp.resolve("theme.conf");
    }

    @Test
    void loadWithoutAFilePaintsTheDefaultAndTheThemeList() {
        var reply = new ThemeController(file()).loadTheme();

        assertEquals("ok", reply.status());
        assertNull(reply.error());
        assertEquals("starry", reply.theme());
        assertEquals(List.of("starry", "liquidneon", "darkplatinum"),
                reply.themes().stream().map(t -> t.id()).toList());
        assertEquals("Starry Night", reply.themes().get(0).label());
        assertEquals("Liquid Neon", reply.themes().get(1).label());
        assertEquals("Dark Platinum", reply.themes().get(2).label());
    }

    @Test
    void saveWritesTheThemeAndEchoesIt() throws Exception {
        var reply = new ThemeController(file()).saveTheme("liquidneon");

        assertEquals("ok", reply.status());
        assertEquals("liquidneon", reply.theme());
        assertTrue(Files.readString(file()).contains("theme = liquidneon"));
    }

    @Test
    void saveRejectsAnUnknownThemeAndLeavesTheFileAlone() {
        var controller = new ThemeController(file());

        var reply = controller.saveTheme("vaporwave");

        assertEquals("error", reply.status());
        assertNotNull(reply.error());
        assertTrue(reply.error().contains("vaporwave"));
        assertEquals("starry", reply.theme(), "the reply still reports the stored theme");
        assertFalse(Files.exists(file()));
    }

    @Test
    void aSavedThemeIsWhatTheNextLoadPaints() {
        var controller = new ThemeController(file());
        controller.saveTheme("liquidneon");

        assertEquals("liquidneon", controller.loadTheme().theme());
    }
}
