package vangogh.error;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FetchErrorTest {

    @Test
    void uiMessageForTransport() {
        assertEquals("TMDB trending failed: connection refused",
                FetchError.uiMessage(new FetchError.Transport("TMDB trending",
                        new java.io.IOException("connection refused"))));
    }

    @Test
    void uiMessageForHttpStatusMatchesLegacyFormat() {
        assertEquals("TMDB trending failed: HTTP 500 boom",
                FetchError.uiMessage(new FetchError.HttpStatus("TMDB trending", 500, "boom")));
    }

    @Test
    void uiMessageForPayload() {
        assertEquals("TheGamesDB payload unreadable (payload shape)",
                FetchError.uiMessage(new FetchError.Payload("TheGamesDB", "payload shape", null)));
    }

    @Test
    void uiMessageForBadInput() {
        assertEquals("kind: missing",
                FetchError.uiMessage(new FetchError.BadInput("kind", "missing")));
    }

    @Test
    void causeDefaultsToNull() {
        assertNull(new FetchError.HttpStatus("api", 500, "x").cause());
        assertNull(new FetchError.BadInput("field", "reason").cause());
        assertNotNull(new FetchError.Transport("op", new RuntimeException("x")).cause());
    }
}
