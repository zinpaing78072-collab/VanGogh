/* Preview mock bridge — a stand-in for Krema's window.krema.
 *
 * Loaded as a classic script in <head>, before krema-shim.js, which
 * self-disables because it only installs when window.krema is missing
 * (krema-shim.js:22). Nothing under src/main/java or the app's own JS changes
 * for this to work: the page has exactly one shell seam, invoke() + on().
 *
 * Implements the real contracts, not simplified ones:
 *   invoke("request<Form>", { kind, query })  → generation (kicks off a fetch)
 *   invoke("poll<Form>Data", { generation })  → { status, items?, error? }
 *   invoke("poll<Form>Art", {})               → remaining posters, −1 when done
 *   on("<artEvent>", handler)                 → { payload: { id, dataUri }, timestamp }
 *
 * Because pacing stays pending→done like the real drains, the skeleton cards
 * and the staggered artwork stream are both exercised, which is the whole
 * point: those states are otherwise impossible to inspect on demand.
 *
 * Scenarios, via ?scenario=:
 *   (none)      trending results, ~3 polls of skeleton, artwork streams in
 *   slow        40 pending polls (~4s of skeletons)
 *   empty       a done reply with zero items (the empty state)
 *   error       an error reply (the red error state)
 *   noposters   done, artwork never arrives (every card shows its placeholder)
 */
(function () {
    "use strict";

    // A real bridge present means a real shell; never shadow it.
    if (window.krema && typeof window.krema.invoke === "function") {
        return;
    }

    var fixtures = window.__previewFixtures;
    if (!fixtures) {
        console.error("[preview] fixtures.js did not load before mock-krema.js");
        return;
    }

    var scenario = new URLSearchParams(location.search).get("scenario") || "trending";

    var FORM_BY_REQUEST = {
        requestMovies: "movie",
        requestGames: "game",
        requestMusic: "music",
        requestBooks: "book"
    };
    var FORM_BY_DATA = {
        pollMovieData: "movie",
        pollGameData: "game",
        pollMusicData: "music",
        pollBookData: "book"
    };
    var FORM_BY_ART = {
        pollMoviePosters: "movie",
        pollGamePosters: "game",
        pollMusicPosters: "music",
        pollBookCovers: "book"
    };
    var EVENT_BY_FORM = {
        movie: "poster",
        game: "gamePoster",
        music: "musicPoster",
        book: "bookCover"
    };
    var ID_FIELD = { movie: "id", game: "id", music: "id", book: "workKey" };

    // Real local artwork for movies (they exist under movie-page/posters/).
    // 278 is deliberately absent so the placeholder path stays visible.
    var MOVIE_ART = {
        "27205": "posters/inception.jpg",
        "155": "posters/dark_knight.jpg",
        "157336": "posters/interstellar.jpg",
        "680": "posters/pulp_fiction.jpg",
        "238": "posters/godfather.jpg"
    };

    var handlers = {};
    var active = { gen: 0, form: "movie", kind: "trending", query: "" };
    var polls = {};       // generation → data-poll ticks
    var delivered = {};   // generation → items a done reply handed over
    var artQueue = {};    // generation → remaining { id, dataUri }
    var emitted = {};     // generation → artwork events sent
    var status = {};      // generation → "pending" | "done" | "error"
    var artDone = {};     // generation → the artwork drain reported −1

    // ---------------------------------------------------------------- artwork

    function initialsOf(title) {
        var words = String(title || "?").split(/\s+/).filter(Boolean);
        return words.slice(0, 2).map(function (w) { return w.charAt(0).toUpperCase(); }).join("") || "?";
    }

    // Generated cover so every generated card is visually distinct — a card
    // grid of identical grey boxes tells you nothing about layout.
    function svgCover(title, w, h, hue) {
        var mark = initialsOf(title).replace(/[<&>"]/g, "");
        var svg =
            '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '">' +
            '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
            '<stop offset="0" stop-color="hsl(' + hue + ',42%,22%)"/>' +
            '<stop offset="1" stop-color="hsl(' + ((hue + 42) % 360) + ',52%,42%)"/>' +
            "</linearGradient></defs>" +
            '<rect width="' + w + '" height="' + h + '" fill="url(#g)"/>' +
            '<circle cx="' + (w * 0.78) + '" cy="' + (h * 0.22) + '" r="' + (w * 0.16) + '" fill="rgba(255,255,255,0.12)"/>' +
            '<circle cx="' + (w * 0.2) + '" cy="' + (h * 0.62) + '" r="' + (w * 0.05) + '" fill="rgba(255,255,255,0.18)"/>' +
            '<text x="50%" y="52%" text-anchor="middle" dominant-baseline="middle" ' +
            'font-family="-apple-system, BlinkMacSystemFont, sans-serif" font-size="' + Math.round(w * 0.3) + '" ' +
            'font-weight="700" fill="rgba(255,255,255,0.82)">' + mark + "</text>" +
            "</svg>";
        return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
    }

    function hueOf(id) {
        var hash = 0;
        var s = String(id || "");
        for (var i = 0; i < s.length; i++) {
            hash = (hash * 31 + s.charCodeAt(i)) % 360;
        }
        return hash;
    }

    function artFor(form, item) {
        if (form === "movie") {
            return MOVIE_ART[String(item.id)] || null;   // null → placeholder stays
        }
        var title = item.title || item.game_title || "";
        var id = item[ID_FIELD[form]];
        var square = form === "music";
        return svgCover(title, square ? 300 : 300, square ? 300 : 450, hueOf(id));
    }

    // ------------------------------------------------------------------ reply

    function itemsFor(form, kind, query) {
        var list = fixtures[form] || [];
        if (kind !== "search") {
            return list;
        }
        var q = String(query || "").trim().toLowerCase();
        if (q === "") {
            return list;
        }
        return list.filter(function (item) {
            var title = String(item.title || item.game_title || "").toLowerCase();
            return title.indexOf(q) !== -1;
        });
    }

    function emit(eventName, payload) {
        var hs = handlers[eventName];
        if (!hs) {
            return;
        }
        var data = { payload: payload, timestamp: Date.now() };
        hs.slice().forEach(function (h) {
            try {
                h(data);
            } catch (e) {
                console.error("[preview] artwork handler failed", e);
            }
        });
    }

    function buildArtQueue(generation, form) {
        var items = delivered[generation] || [];
        return items
            .map(function (item) {
                return { id: item[ID_FIELD[form]], dataUri: artFor(form, item) };
            })
            .filter(function (entry) {
                return entry.id != null && entry.dataUri;
            });
    }

    // ---------------------------------------------------------------- bridge

    function request(form, args) {
        active = {
            gen: active.gen + 1,
            form: form,
            kind: (args && args.kind) || "trending",
            query: (args && args.query) || ""
        };
        polls[active.gen] = 0;
        emitted[active.gen] = 0;
        artQueue[active.gen] = null;
        status[active.gen] = "pending";
        artDone[active.gen] = false;
        console.log("[preview] " + form + " request gen=" + active.gen +
            " kind=" + active.kind + " scenario=" + scenario);
        return active.gen;
    }

    function pollData(form, args) {
        var generation = args && args.generation;
        if (generation !== active.gen) {
            return { status: "superseded" };
        }

        polls[generation] = (polls[generation] || 0) + 1;
        var ticks = scenario === "slow" ? 40 : 3;
        if (polls[generation] <= ticks) {
            return { status: "pending" };
        }

        if (scenario === "error") {
            status[generation] = "error";
            return { status: "error", error: "Simulated fetch failure (scenario=error)." };
        }

        var items = scenario === "empty" ? [] : itemsFor(form, active.kind, active.query);
        delivered[generation] = items;
        status[generation] = "done";
        console.log("[preview] " + form + " done gen=" + generation + " items=" + items.length);
        return { status: "done", items: items };
    }

    function pollArt(form) {
        var generation = active.gen;
        if (!delivered[generation] || scenario === "noposters") {
            artDone[generation] = true;
            return -1;
        }
        if (artQueue[generation] === null) {
            artQueue[generation] = buildArtQueue(generation, form);
        }

        // Two per drain call, like a network stream landing in bursts.
        var batch = artQueue[generation].splice(0, 2);
        batch.forEach(function (entry) {
            emitted[generation] = (emitted[generation] || 0) + 1;
            emit(EVENT_BY_FORM[form], { id: entry.id, dataUri: entry.dataUri });
        });

        if (artQueue[generation].length === 0) {
            artDone[generation] = true;
            return -1;
        }
        return artQueue[generation].length;
    }

    window.krema = {
        invoke: function (command, args) {
            var form;

            if (FORM_BY_REQUEST[command]) {
                return Promise.resolve(request(FORM_BY_REQUEST[command], args));
            }
            if (FORM_BY_DATA[command]) {
                form = FORM_BY_DATA[command];
                return Promise.resolve(pollData(form, args));
            }
            if (FORM_BY_ART[command]) {
                return Promise.resolve(pollArt(FORM_BY_ART[command]));
            }

            console.warn("[preview] unmocked command: " + command);
            return Promise.reject(new Error("preview: command not mocked: " + command));
        },

        on: function (eventName, handler) {
            if (!handlers[eventName]) {
                handlers[eventName] = [];
            }
            handlers[eventName].push(handler);
            console.log("[preview] subscribed " + eventName);
        }
    };

    // Read-only introspection for scripts and agents asserting on a run.
    // `status` and `artDone` are what preview/check.js waits on: a page is
    // settled when the data drain resolved and the artwork drain reported −1.
    window.__preview = {
        scenario: scenario,
        stats: function () {
            var g = active.gen;
            return {
                scenario: scenario,
                form: active.form,
                generation: g,
                status: status[g] || "idle",
                dataPolls: polls[g] || 0,
                delivered: (delivered[g] || []).length,
                artworkEmitted: emitted[g] || 0,
                artDone: !!artDone[g]
            };
        }
    };

    console.log("[preview] mock bridge installed, scenario=" + scenario);
})();
