/* Van Gogh JCEF shim: implements the window.krema API on top of CEF.
 *
 * The page (app.js) expects:
 *   window.krema.invoke("requestMovies", {kind, query}) → Promise<list>
 *   window.krema.invoke("requestGames",  {kind, query}) → Promise<list>
 *   window.krema.invoke("pollGamePosters", {})          → Promise<number>
 *   window.krema.on("poster", data)                     → data = {payload, timestamp}
 *   window.krema.on("gamePoster", data)
 *
 * JS→Java: window.cefQuery({request, persistent, onSuccess, onFailure}) — CEF
 * message-router contract (the keys are exact: onSuccess/onFailure; a wrong
 * key means the response is computed but silently dropped). JcefBridge.
 * Java→JS: window.__krema_emit(eventName, jsonPayload) via executeJavaScript.
 */

(function () {
    var seq = 0;
    var pending = {};
    var handlers = {};

    window.__krema_seq = 0;
    window.__krema_pending = pending;
    window.__krema_handlers = handlers;

    window.__krema_emit = function (eventName, jsonPayload) {
        var payload;
        try {
            payload = jsonPayload ? JSON.parse(jsonPayload) : null;
        } catch (e) {
            payload = null;
        }
        // Krema's bridge wraps every event payload as {payload, timestamp} —
        // app.js (written for the Krema shell) reads data.payload.id etc.
        var data = { payload: payload, timestamp: Date.now() };
        var hs = handlers[eventName];
        if (hs) {
            hs.forEach(function (h) {
                try { h(data); } catch (err) { console.error(err); }
            });
        }
    };

    window.krema = {
        invoke: function (command, args) {
            return new Promise(function (resolve, reject) {
                var id = ++seq;
                pending[id] = { resolve: resolve, reject: reject };
                var request = JSON.stringify({
                    seq: id,
                    command: command,
                    args: args || {}
                });
                if (typeof window.cefQuery === "function") {
                    window.cefQuery({
                        request: request,
                        persistent: false,
                        onSuccess: function (response) {
                            var p = pending[id];
                            delete pending[id];
                            if (!p) return;
                            try {
                                p.resolve(response ? JSON.parse(response) : null);
                            } catch (err) { p.reject(err); }
                        },
                        onFailure: function (errorCode, errorMessage) {
                            var p = pending[id];
                            delete pending[id];
                            if (!p) return;
                            p.reject(new Error("[" + errorCode + "] " + (errorMessage || "query failed")));
                        }
                    });
                } else {
                    delete pending[id];
                    reject(new Error("cefQuery not available"));
                }
            });
        },

        on: function (eventName, handler) {
            if (!handlers[eventName]) {
                handlers[eventName] = [];
            }
            handlers[eventName].push(handler);
        }
    };

    // JCEF-only fullscreen toggle button. The native green button's hover
    // menu is a freeze trigger in this AWT+CEF hybrid (its modal loop traps
    // the AppKit thread — vault "Debug Record — JCEF window freezes on native
    // menus"), so the safe path is this button → bridge command
    // "toggleFullscreen" → GraphicsDevice.setFullScreenWindow: the same
    // native fullscreen (title bar + menu bar hidden) with no menu surfaces.
    function injectFullscreenButton() {
        var header = document.querySelector(".app-header");
        if (!header || document.getElementById("van-gogh-fullscreen")) return;
        var btn = document.createElement("button");
        btn.id = "van-gogh-fullscreen";
        btn.title = "Toggle fullscreen";
        btn.setAttribute("aria-label", "Toggle fullscreen");
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 14 14" fill="none" ' +
            'stroke="currentColor" stroke-width="1.6" stroke-linecap="round">' +
            '<path d="M1 5V1h4M9 1h4v4M13 9v4H9M5 13H1V9"/></svg>';
        btn.style.cssText = "display:flex;align-items:center;justify-content:center;" +
            "height:42px;min-width:44px;margin-left:12px;padding:0 12px;border-radius:10px;" +
            "border:1px solid rgba(255,255,255,0.14);background:#17171a;color:#e5e5e7;" +
            "cursor:pointer;flex:0 0 auto;";
        btn.addEventListener("mouseenter", function () { btn.style.background = "#232327"; });
        btn.addEventListener("mouseleave", function () { btn.style.background = "#17171a"; });
        btn.addEventListener("click", function () {
            window.krema.invoke("toggleFullscreen", {}).catch(function (e) {
                console.error("toggleFullscreen failed:", e);
            });
        });
        header.appendChild(btn);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", injectFullscreenButton);
    } else {
        injectFullscreenButton();
    }
})();
