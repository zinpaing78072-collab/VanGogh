package vangogh.app.jcef;

import vangogh.controller.GameController;
import vangogh.controller.MovieController;

import build.krema.core.event.EventEmitter;
import build.krema.core.event.KremaEvent;
import org.cef.browser.CefBrowser;

import com.google.gson.Gson;

import java.util.Map;

/**
 * Java-to-JS event emitter for JCEF. Extends Krema's {@link EventEmitter} so it
 * can be passed to {@link MovieController#setEventEmitter} and
 * {@link GameController#setEventEmitter} unchanged. Overrides both
 * {@code emit} overloads to deliver via {@link CefBrowser#executeJavaScript}
 * instead of Krema's {@code engine.eval}.
 *
 * <p>JCEF requires browser calls on the UI (Swing EDT) thread, so emissions
 * from IO-pool threads are marshalled via {@code EventQueue.invokeLater}.
 */
public class JcefEventEmitter extends EventEmitter {

    private static final Gson GSON = new Gson();

    private final CefBrowser browser;

    /** Pass a null engine — we override {@code emit()} and never use engine.eval. */
    public JcefEventEmitter(CefBrowser browser) {
        super(null);
        this.browser = browser;
    }

    /**
     * Emits an event to the frontend as {@code window.__krema_emit(eventName, jsonPayload)}.
     * Also notifies any backend listeners registered via {@code on()}.
     */
    @Override
    public void emit(String eventName, Object payload) {
        String json = GSON.toJson(payload != null ? payload : Map.of());
        // The shim's contract is window.__krema_emit(name, jsonText) where the
        // second argument is a JSON *string* that it JSON.parse()s. Pass it
        // quoted — a raw object literal would make JSON.parse throw and the
        // shim silently swallow the event as {payload: null}.
        String js = String.format("window.__krema_emit(%s, %s)",
                escapeJs(eventName), escapeJs(json));

        // JCEF browser calls must run on the UI (EDT) thread.
        if (java.awt.EventQueue.isDispatchThread()) {
            browser.executeJavaScript(js, "", 0);
        } else {
            java.awt.EventQueue.invokeLater(() ->
                    browser.executeJavaScript(js, "", 0));
        }
    }

    /**
     * Routes pre-built {@link KremaEvent}s through the EDT-safe path above.
     * The base class's implementation calls {@code engine.eval} on the null
     * engine this class passes to {@code super} — routing through
     * {@link #emit(String, Object)} avoids inheriting that NPE.
     */
    @Override
    public void emit(KremaEvent event) {
        emit(event.name(), event.payload());
    }

    private static String escapeJs(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
