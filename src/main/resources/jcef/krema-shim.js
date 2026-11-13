/* Van Gogh JCEF shim: implements the window.krema API on top of CEF.
 *
 * The page (app.js) expects:
 *   window.krema.invoke("requestMovies", {kind, query}) → Promise<list>
 *   window.krema.invoke("requestGames",  {kind, query}) → Promise<list>
 *   window.krema.invoke("pollGamePosters", {})          → Promise<number>
 *   window.krema.on("poster", data)                     → data = {payload, timestamp}
 *   window.krema.on("gamePoster", data)
 *
 * JS→Java: window.cefQuery({request: JSON, callback}) handled by JcefBridge.
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
        var data;
        try {
            data = jsonPayload ? JSON.parse(jsonPayload) : { payload: null };
        } catch (e) {
            data = { payload: null };
        }
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
                        callback: function (success, response) {
                            var p = pending[id];
                            delete pending[id];
                            if (!p) return;
                            if (success) {
                                try {
                                    p.resolve(response ? JSON.parse(response) : null);
                                } catch (err) { p.reject(err); }
                            } else {
                                p.reject(new Error(response || "query failed"));
                            }
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
})();
