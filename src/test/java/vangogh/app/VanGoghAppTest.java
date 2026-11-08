package vangogh.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanGoghAppTest {

    @Test
    void escapeJsonNullReturnsEmptyString() {
        assertEquals("", VanGoghApp.escapeJson(null));
    }

    @Test
    void escapeJsonEmptyReturnsEmptyString() {
        assertEquals("", VanGoghApp.escapeJson(""));
    }

    @Test
    void escapeJsonPlainTextUnchanged() {
        assertEquals("Hello World", VanGoghApp.escapeJson("Hello World"));
    }

    @Test
    void escapeJsonEscapesBackslash() {
        assertEquals("a\\\\b", VanGoghApp.escapeJson("a\\b"));
    }

    @Test
    void escapeJsonEscapesDoubleQuote() {
        assertEquals("say \\\"hi\\\"", VanGoghApp.escapeJson("say \"hi\""));
    }

    @Test
    void escapeJsonEscapesBothBackslashAndQuote() {
        assertEquals("a\\\\b \\\"c\\\"", VanGoghApp.escapeJson("a\\b \"c\""));
    }
}
