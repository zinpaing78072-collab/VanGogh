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
 *   invoke("requestSongDetail", { id })       → song-detail generation
 *   invoke("pollSongDetail", { generation })  → { status, detail?, artDataUri?, error? }
 *   invoke("requestMovieDetail", { id })      → movie-detail generation
 *   invoke("pollMovieDetail", { generation }) → { status, detail?, error? }
 *   invoke("pollMovieDetailArt", {})          → remaining detail art, −1 when done
 *   invoke("requestBookDetail", { workKey })  → book-detail generation
 *   invoke("pollBookDetail", { generation })  → { status, detail?, error? }
 *   invoke("pollBookDetailArt", {})           → remaining book art, −1 when done
 *   invoke("requestGameDetail", { id })       → game-detail generation
 *   invoke("pollGameDetail", { generation })  → { status, detail?, error? }
 *   invoke("pollGameDetailArt", {})           → remaining game art, −1 when done
 *   invoke("loadLlmSettings", {})             → { status, baseUrl, model, …, hasApiKey }
 *   invoke("saveLlmSettings", { … })          → the stored values, never the key
 *   invoke("loadTheme", {})                   → { status, theme, themes }
 *   invoke("saveTheme", { theme })            → the stored id + the theme list
 *   on("<artEvent>", handler)                 → { payload: { id, dataUri }, timestamp }
 *   on("movieDetailArt", handler)             → { payload: { slot, dataUri }, timestamp }
 *   on("gameDetailArt", handler)              → { payload: { slot, dataUri }, timestamp }
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
 *
 * ?theme=<id> starts the mock with that theme stored, so either skin can be
 * inspected without clicking through Settings (unknown ids fall back to starry,
 * exactly like ThemeConfig.read).
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
        requestGeniusMusic: "music",
        requestBooks: "book"
    };
    var FORM_BY_DATA = {
        pollMovieData: "movie",
        pollGameData: "game",
        pollMusicData: "music",
        pollGeniusMusicData: "music",
        pollBookData: "book"
    };
    var FORM_BY_ART = {
        pollMoviePosters: "movie",
        pollGamePosters: "game",
        pollMusicPosters: "music",
        pollGeniusMusicPosters: "music",
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

    // Detail has its own generation counter, like the real controller: opening
    // a song must not supersede the browse request (or its artwork stream).
    var detail = { gen: 0, polls: 0, status: "idle", id: null };

    // Movie detail is its own pair too (poster, headshots, stills and similar
    // posters stream as data URIs through the movieDetailArt event).
    var movieDetail = {
        gen: 0,
        polls: 0,
        status: "idle",
        id: null,
        value: null,
        delivered: false,
        artQueue: null,
        artEmitted: 0,
        artDone: false
    };

    // Book detail is its own pair too (work cover, author photo, edition
    // covers and similar covers stream as data URIs through bookDetailArt).
    var bookDetail = {
        gen: 0,
        polls: 0,
        status: "idle",
        workKey: null,
        value: null,
        delivered: false,
        artQueue: null,
        artEmitted: 0,
        artDone: false
    };

    // Game detail is its own pair too (boxart, fanart, stills and related
    // boxarts stream as data URIs through gameDetailArt).
    var gameDetail = {
        gen: 0,
        polls: 0,
        status: "idle",
        id: null,
        value: null,
        delivered: false,
        artQueue: null,
        artEmitted: 0,
        artDone: false
    };

    // Settings: an in-memory stand-in for ~/.van-gogh/llm.conf, with the same
    // contract as SettingsController — the key never comes back out, and a
    // blank key on save keeps the stored one.
    var llm = {
        baseUrl: "https://openrouter.ai/api/v1",
        model: "baidu/ernie-5.5-flash-preview:free",
        timeoutSeconds: 60,
        titles: 10,
        apiKey: null,
        loads: 0,
        saves: 0
    };
    var LLM_CONFIG_PATH = "~/.van-gogh/llm.conf";

    // Theme: one stored id, and the same two-row registry ThemeConfig.KNOWN
    // holds in Java — the page renders its chips from this reply, so the mock
    // must not invent a third list either.
    var THEMES = [
        { id: "starry", label: "Starry Night" },
        { id: "liquidneon", label: "Liquid Neon" },
        { id: "darkplatinum", label: "Dark Platinum" }
    ];
    var theme = {
        id: new URLSearchParams(location.search).get("theme") || "starry",
        loads: 0,
        saves: 0
    };
    if (!THEMES.some(function (t) { return t.id === theme.id; })) {
        theme.id = "starry";
    }
    var THEME_CONFIG_PATH = "~/.van-gogh/theme.conf";

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

    // ----------------------------------------------------------- song detail

    function detailFor(id) {
        var list = fixtures.music || [];
        var item = list.filter(function (x) { return String(x.id) === String(id); })[0] || list[0];
        if (!item) {
            return null;
        }
        var dummy = fixtures.songDetail || {};
        var related = list
            .filter(function (x) { return x.id !== item.id; })
            .slice(0, 5)
            .map(function (x, i) {
                return {
                    id: x.id,
                    title: x.title,
                    artist: x.artist || "Unknown Artist",
                    relation: i % 2 ? "Remix" : "Cover",
                    posterUrl: "",
                    durationSeconds: 180 + i * 13
                };
            });
        var stats = dummy.stats || {};
        return {
            id: item.id,
            title: item.title,
            artist: item.artist || "Unknown Artist",
            album: item.title + " (Preview Album)",
            releaseDate: String(item.year) + "-01-01",
            releaseDateDisplay: "January 1, " + item.year,
            year: item.year,
            writers: dummy.writers || [],
            producers: dummy.producers || [],
            featuredArtists: [],
            geniusUrl: "https://genius.com",
            pageviews: 2364330,
            lyricsState: "complete",
            artUrl: "",
            lyrics: dummy.lyrics || [],
            durationSeconds: dummy.durationSeconds || 252,
            audioUrl: dummy.audioUrl || "",
            related: related,

            language: dummy.language || "",
            translationCount: dummy.translationCount || 0,
            relationshipsIndexUrl: "https://genius.com/albums/preview/relationships",
            credits: dummy.credits || [],
            media: dummy.media || [],
            artPrimaryColor: "#b98d55",
            artSecondaryColor: "#744a19",
            artTextColor: "#fff",

            about: dummy.about || "",
            albumUrl: "https://genius.com/albums/preview",
            albumCoverUrl: svgCover("Album", 64, 64, 40),
            artistImageUrl: svgCover("Artist", 64, 64, 200),
            artistUrl: "https://genius.com/artists/preview",
            artistVerified: true,
            recordingLocation: dummy.recordingLocation || "",
            contributors: stats.contributors || 0,
            transcribers: stats.transcribers || 0,
            acceptedAnnotations: stats.acceptedAnnotations || 0,
            annotationCount: stats.annotationCount || 0,
            pyongsCount: stats.pyongsCount || 0
        };
    }

    function requestDetail(args) {
        detail = {
            gen: detail.gen + 1,
            polls: 0,
            status: "pending",
            id: (args && args.id) || null
        };
        console.log("[preview] song detail request gen=" + detail.gen + " id=" + detail.id);
        return detail.gen;
    }

    function pollDetail(args) {
        var generation = args && args.generation;
        if (generation !== detail.gen) {
            return { generation: generation, status: "superseded", detail: null };
        }
        detail.polls += 1;
        var ticks = scenario === "slow" ? 20 : 2;
        if (detail.polls <= ticks) {
            return { generation: generation, status: "pending", detail: null };
        }
        if (scenario === "error") {
            detail.status = "error";
            return { generation: generation, status: "error", error: "Simulated song fetch failure (scenario=error).", detail: null };
        }
        var song = detailFor(detail.id);
        if (!song) {
            detail.status = "error";
            return { generation: generation, status: "error", error: "No song fixture for id " + detail.id, detail: null };
        }
        detail.status = "done";
        console.log("[preview] song detail done gen=" + generation + " title=" + song.title);
        return {
            generation: generation,
            status: "done",
            detail: song,
            artDataUri: svgCover(song.title, 600, 600, hueOf(song.id))
        };
    }

    // ----------------------------------------------------------- movie detail

    function movieDetailFor(id) {
        var list = fixtures.movie || [];
        var item = list.filter(function (x) { return String(x.id) === String(id); })[0] || list[0];
        if (!item) {
            return null;
        }
        var dummy = fixtures.movieDetail || {};
        var others = list
            .filter(function (x) { return String(x.id) !== String(item.id); })
            .slice(0, 5);

        return {
            id: Number(item.id),
            title: item.title,
            tagline: dummy.tagline || "",
            overview: item.overview || "",
            releaseDate: String(item.year) + "-07-16",
            year: Number(item.year) || 0,
            runtimeMinutes: dummy.runtimeMinutes || 0,
            certification: dummy.certification || "",
            rating: Number(item.rating) || 0,
            voteCount: 2340000,
            genres: item.genre ? [item.genre] : [],
            keywords: dummy.keywords || [],
            posterUrl: "https://image.tmdb.org/t/p/w500/" + item.id + "-poster.jpg",
            tmdbUrl: "https://www.themoviedb.org/movie/" + item.id,
            trailerUrl: "https://www.youtube.com/watch?v=preview",
            facts: dummy.facts || [],
            cast: (dummy.cast || []).map(function (c) {
                return {
                    name: c.name,
                    role: c.role || "",
                    crew: !!c.crew,
                    imageUrl: "https://image.tmdb.org/t/p/w500/cast-" + c.name.replace(/\s+/g, "-") + ".jpg"
                };
            }),
            stills: (dummy.stills || []).map(function (s, i) {
                return {
                    caption: s.caption || "",
                    imageUrl: "https://image.tmdb.org/t/p/w500/still-" + i + ".jpg"
                };
            }),
            similar: others.map(function (x) {
                return {
                    movie: {
                        id: Number(x.id),
                        title: x.title,
                        year: x.year,
                        rating: x.rating,
                        genre: x.genre || "",
                        overview: x.overview || "",
                        posterUrl: ""
                    },
                    posterUrl: "https://image.tmdb.org/t/p/w500/similar-" + x.id + ".jpg"
                };
            })
        };
    }

    // One generated image per slot, keyed exactly like the controller's refs:
    // poster, cast:<i>, still:<i>, similar:<id>.
    function buildMovieArtQueue(value) {
        var entries = [];
        if (value.posterUrl) {
            entries.push({
                slot: "poster",
                dataUri: svgCover(value.title, 500, 750, hueOf(value.id))
            });
        }
        (value.cast || []).forEach(function (credit, i) {
            if (credit.imageUrl) {
                entries.push({
                    slot: "cast:" + i,
                    dataUri: svgCover(credit.name, 300, 450, hueOf(credit.name))
                });
            }
        });
        (value.stills || []).forEach(function (still, i) {
            if (still.imageUrl) {
                entries.push({
                    slot: "still:" + i,
                    dataUri: svgCover(still.caption || value.title, 640, 400, hueOf("still" + i))
                });
            }
        });
        (value.similar || []).forEach(function (entry) {
            if (entry.posterUrl) {
                entries.push({
                    slot: "similar:" + entry.movie.id,
                    dataUri: svgCover(entry.movie.title, 300, 450, hueOf(entry.movie.id))
                });
            }
        });
        return entries;
    }

    function requestMovieDetail(args) {
        movieDetail = {
            gen: movieDetail.gen + 1,
            polls: 0,
            status: "pending",
            id: (args && args.id) || null,
            value: null,
            delivered: false,
            artQueue: null,
            artEmitted: 0,
            artDone: false
        };
        console.log("[preview] movie detail request gen=" + movieDetail.gen +
            " id=" + movieDetail.id);
        return movieDetail.gen;
    }

    function pollMovieDetail(args) {
        var generation = args && args.generation;
        if (generation !== movieDetail.gen) {
            return { generation: generation, status: "superseded", detail: null };
        }
        movieDetail.polls += 1;
        var ticks = scenario === "slow" ? 20 : 2;
        if (movieDetail.polls <= ticks) {
            return { generation: generation, status: "pending", detail: null };
        }
        if (scenario === "error") {
            movieDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "Simulated movie fetch failure (scenario=error).",
                detail: null
            };
        }
        var value = movieDetailFor(movieDetail.id);
        if (!value) {
            movieDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "No movie fixture for id " + movieDetail.id,
                detail: null
            };
        }
        movieDetail.status = "done";
        movieDetail.value = value;
        movieDetail.delivered = true;
        console.log("[preview] movie detail done gen=" + generation +
            " title=" + value.title);
        return { generation: generation, status: "done", detail: value };
    }

    function pollMovieDetailArt() {
        if (!movieDetail.delivered || !movieDetail.value || scenario === "noposters") {
            movieDetail.artDone = true;
            return -1;
        }
        if (movieDetail.artQueue === null) {
            movieDetail.artQueue = buildMovieArtQueue(movieDetail.value);
        }

        // Two per drain call, like a network stream landing in bursts.
        var batch = movieDetail.artQueue.splice(0, 2);
        batch.forEach(function (entry) {
            movieDetail.artEmitted += 1;
            emit("movieDetailArt", { slot: entry.slot, dataUri: entry.dataUri });
        });

        if (movieDetail.artQueue.length === 0) {
            movieDetail.artDone = true;
            return -1;
        }
        return movieDetail.artQueue.length;
    }

    // ------------------------------------------------------------ book detail

    function bookDetailFor(workKey) {
        var list = fixtures.book || [];
        var item = list.filter(function (x) { return String(x.workKey) === String(workKey); })[0] || list[0];
        if (!item) {
            return null;
        }
        var extras = fixtures.bookDetail || {};
        var year = Number(item.year) || 0;
        var pages = Number(item.pages) || 0;
        var editionCount = Number(item.editionCount) || 0;
        var rating = Number(item.rating) || 0;
        var setting = (extras.settings || {})[item.workKey] || "";
        var ratingCount = extras.ratingCount || 0;

        var editions = [0, 1, 2, 3].map(function (i) {
            return {
                year: year ? String(year + i * 3) : "",
                pages: pages ? (pages + i * 8) + " pp" : "",
                isbn: "97800000000" + (10 + i),
                // The last edition deliberately has no cover, so the preview
                // keeps one edition placeholder visible.
                coverUrl: i < 3 ? "https://covers.openlibrary.org/b/id/" + (700000 + i) + "-L.jpg" : ""
            };
        });

        var facts = [];
        function fact(label, value, muted, spanAll) {
            if (value !== "" && value != null) {
                facts.push({ label: label, value: String(value), muted: !!muted, spanAll: !!spanAll });
            }
        }
        fact("First published", year, false, false);
        fact("Pages", pages, false, false);
        fact("Editions", editionCount, false, false);
        fact("Setting", setting, false, false);
        if (rating > 0) {
            fact("Rating", rating.toFixed(1) + " / 5", false, false);
        }
        fact("Ratings", ratingCount, false, false);
        fact("Featured", item.author + ", " + year, true, true);
        fact("ISBN", editions[0].isbn, true, true);

        var authorName = item.author || "Unknown author";
        var author = {
            name: authorName,
            dates: (extras.authorDates || {})[authorName] || "",
            bio: (extras.authorBios || {})[authorName] || "",
            photoUrl: "https://covers.openlibrary.org/a/id/" + (9000 + year % 100) + "-L.jpg",
            wikipediaUrl: "https://en.wikipedia.org/wiki/" + encodeURIComponent(authorName.replace(/ /g, "_")),
            openLibraryUrl: "https://openlibrary.org/authors/OL" + (1000 + year % 900) + "A"
        };

        var others = list
            .filter(function (x) { return String(x.workKey) !== String(item.workKey); })
            .slice(0, 6)
            .map(function (x, i) {
                return {
                    workKey: x.workKey,
                    title: x.title,
                    author: x.author || "",
                    year: Number(x.year) || 0,
                    rating: Number(x.rating) || 0,
                    subjects: x.subjects || "",
                    firstSentence: x.firstSentence || "",
                    pages: Number(x.pages) || 0,
                    editionCount: Number(x.editionCount) || 0,
                    coverUrl: "https://covers.openlibrary.org/b/id/" + (800000 + i) + "-L.jpg"
                };
            });

        return {
            workKey: item.workKey,
            title: item.title,
            year: year,
            pages: pages,
            editionCount: editionCount,
            rating: rating,
            ratingCount: ratingCount,
            description: (extras.descriptions || {})[item.workKey] || "",
            sampleUrl: (extras.iaIds || {})[item.workKey]
                ? "https://archive.org/embed/" + extras.iaIds[item.workKey]
                : "",
            subjects: item.subjects
                ? String(item.subjects).split(/[,;\u00b7]/).map(function (s) { return s.trim(); })
                    .filter(Boolean)
                : [],
            setting: setting,
            coverUrl: "https://covers.openlibrary.org/b/id/600000-L.jpg",
            openLibraryUrl: "https://openlibrary.org/works/" + item.workKey,
            facts: facts,
            editions: editions,
            author: author,
            similar: others
        };
    }

    // One generated image per slot, keyed exactly like the controller's refs:
    // cover, author, edition:<i>, similar:<workKey>.
    function buildBookArtQueue(value) {
        var entries = [];
        if (value.coverUrl) {
            entries.push({
                slot: "cover",
                dataUri: svgCover(value.title, 500, 750, hueOf(value.workKey))
            });
        }
        if (value.author && value.author.photoUrl) {
            entries.push({
                slot: "author",
                dataUri: svgCover(value.author.name, 300, 450, hueOf(value.author.name))
            });
        }
        (value.editions || []).forEach(function (edition, i) {
            if (edition.coverUrl) {
                entries.push({
                    slot: "edition:" + i,
                    dataUri: svgCover((edition.year || value.title) + " edition", 300, 450, hueOf("edition" + i))
                });
            }
        });
        (value.similar || []).forEach(function (book) {
            if (book.coverUrl) {
                entries.push({
                    slot: "similar:" + book.workKey,
                    dataUri: svgCover(book.title, 300, 450, hueOf(book.workKey))
                });
            }
        });
        return entries;
    }

    function requestBookDetail(args) {
        bookDetail = {
            gen: bookDetail.gen + 1,
            polls: 0,
            status: "pending",
            workKey: (args && args.workKey) || null,
            value: null,
            delivered: false,
            artQueue: null,
            artEmitted: 0,
            artDone: false
        };
        console.log("[preview] book detail request gen=" + bookDetail.gen +
            " workKey=" + bookDetail.workKey);
        return bookDetail.gen;
    }

    function pollBookDetail(args) {
        var generation = args && args.generation;
        if (generation !== bookDetail.gen) {
            return { generation: generation, status: "superseded", detail: null };
        }
        bookDetail.polls += 1;
        var ticks = scenario === "slow" ? 20 : 2;
        if (bookDetail.polls <= ticks) {
            return { generation: generation, status: "pending", detail: null };
        }
        if (scenario === "error") {
            bookDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "Simulated book fetch failure (scenario=error).",
                detail: null
            };
        }
        var value = bookDetailFor(bookDetail.workKey);
        if (!value) {
            bookDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "No book fixture for workKey " + bookDetail.workKey,
                detail: null
            };
        }
        bookDetail.status = "done";
        bookDetail.value = value;
        bookDetail.delivered = true;
        console.log("[preview] book detail done gen=" + generation +
            " title=" + value.title);
        return { generation: generation, status: "done", detail: value };
    }

    function pollBookDetailArt() {
        if (!bookDetail.delivered || !bookDetail.value || scenario === "noposters") {
            bookDetail.artDone = true;
            return -1;
        }
        if (bookDetail.artQueue === null) {
            bookDetail.artQueue = buildBookArtQueue(bookDetail.value);
        }

        // Two per drain call, like a network stream landing in bursts.
        var batch = bookDetail.artQueue.splice(0, 2);
        batch.forEach(function (entry) {
            bookDetail.artEmitted += 1;
            emit("bookDetailArt", { slot: entry.slot, dataUri: entry.dataUri });
        });

        if (bookDetail.artQueue.length === 0) {
            bookDetail.artDone = true;
            return -1;
        }
        return bookDetail.artQueue.length;
    }

    // ------------------------------------------------------------ game detail

    /** The rail's record sheet, in the same order GameDetailMapper builds it. */
    function gameFacts(value) {
        var facts = [];
        function fact(label, text) {
            if (text !== "" && text != null) {
                facts.push({ label: label, value: String(text) });
            }
        }
        fact("Release", value.releaseDate);
        fact("Age rating", value.rating);
        fact("Players", value.players > 0 ? value.players : "");
        fact("Platform", value.platforms.join(" \u00b7 "));
        fact("Genres", value.genres.join(" \u00b7 "));
        fact("Publisher", value.publishers.join(" \u00b7 "));
        fact("Developer", value.developers.join(" \u00b7 "));
        fact("Game ID", value.id > 0 ? value.id : "");
        return facts;
    }

    function gameDetailFor(id) {
        var list = fixtures.game || [];
        var extras = fixtures.gameDetail || {};
        var item = list.filter(function (x) { return String(x.id) === String(id); })[0];

        if (!item) {
            // A related-rail card is a record too. Build it from the fixture's
            // related row instead of falling back to an unrelated game.
            var found = null;
            Object.keys(extras).forEach(function (key) {
                (extras[key].related || []).forEach(function (r) {
                    if (String(r.id) === String(id)) {
                        found = r;
                    }
                });
            });
            if (!found) {
                return null;
            }
            item = {
                id: found.id,
                game_title: found.title,
                year: found.year,
                rating: found.rating,
                genre: found.genre
            };
        }

        var extra = extras[String(item.id)] || {};
        var stills = Number(extra.stills) || 0;

        var value = {
            id: Number(item.id),
            title: item.game_title,
            releaseDate: extra.releaseDate || "",
            year: Number(item.year) || 0,
            rating: extra.rating || "",
            players: Number(extra.players) || 0,
            genres: extra.genres || (item.genre ? [item.genre] : []),
            platforms: extra.platforms || [],
            publishers: extra.publishers || [],
            developers: extra.developers || [],
            overview: extra.overview || item.overview || "",
            youtubeUrl: extra.youtube || "",
            recordUrl: "https://thegamesdb.net/game/" + item.id + "/",
            boxartUrl: extra.boxart
                ? "https://cdn.thegamesdb.net/images/original/boxart/" + item.id + ".jpg" : "",
            heroUrl: extra.hero
                ? "https://cdn.thegamesdb.net/images/medium/fanart/" + item.id + ".jpg" : "",
            stillUrls: [],
            facts: [],
            relatedSource: extra.relatedSource || "catalogue",
            related: (extra.related || []).map(function (r) {
                return {
                    id: Number(r.id),
                    title: r.title,
                    year: Number(r.year) || 0,
                    rating: r.rating || "",
                    genre: r.genre || "",
                    posterUrl: "https://cdn.thegamesdb.net/images/medium/boxart/" + r.id + ".jpg"
                };
            })
        };

        for (var i = 0; i < stills; i++) {
            value.stillUrls.push(
                "https://cdn.thegamesdb.net/images/medium/screenshots/" + item.id + "-" + i + ".jpg");
        }
        // Same fallback the mapper applies: no fanart → the first screenshot.
        if (!value.heroUrl && value.stillUrls.length) {
            value.heroUrl = value.stillUrls[0];
        }
        value.facts = gameFacts(value);
        return value;
    }

    // One generated image per slot, keyed exactly like the controller's refs:
    // boxart, hero, still:<i>, related:<id>.
    function buildGameArtQueue(value) {
        var entries = [];
        if (value.boxartUrl) {
            entries.push({
                slot: "boxart",
                dataUri: svgCover(value.title, 460, 690, hueOf(value.id))
            });
        }
        if (value.heroUrl) {
            entries.push({
                slot: "hero",
                dataUri: svgCover(value.title.charAt(0), 1280, 620, hueOf("hero" + value.id))
            });
        }
        (value.stillUrls || []).forEach(function (still, i) {
            entries.push({
                slot: "still:" + i,
                dataUri: svgCover(value.title.charAt(0), 800, 450, hueOf("still" + value.id + i))
            });
        });
        (value.related || []).forEach(function (game) {
            if (game.posterUrl) {
                entries.push({
                    slot: "related:" + game.id,
                    dataUri: svgCover(game.title, 300, 450, hueOf(game.id))
                });
            }
        });
        return entries;
    }

    function requestGameDetail(args) {
        gameDetail = {
            gen: gameDetail.gen + 1,
            polls: 0,
            status: "pending",
            id: (args && args.id) || null,
            value: null,
            delivered: false,
            artQueue: null,
            artEmitted: 0,
            artDone: false
        };
        console.log("[preview] game detail request gen=" + gameDetail.gen +
            " id=" + gameDetail.id);
        return gameDetail.gen;
    }

    function pollGameDetail(args) {
        var generation = args && args.generation;
        if (generation !== gameDetail.gen) {
            return { generation: generation, status: "superseded", detail: null };
        }
        gameDetail.polls += 1;
        var ticks = scenario === "slow" ? 20 : 2;
        if (gameDetail.polls <= ticks) {
            return { generation: generation, status: "pending", detail: null };
        }
        if (scenario === "error") {
            gameDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "Simulated game fetch failure (scenario=error).",
                detail: null
            };
        }
        var value = gameDetailFor(gameDetail.id);
        if (!value) {
            gameDetail.status = "error";
            return {
                generation: generation,
                status: "error",
                error: "No game fixture for id " + gameDetail.id,
                detail: null
            };
        }
        gameDetail.status = "done";
        gameDetail.value = value;
        gameDetail.delivered = true;
        console.log("[preview] game detail done gen=" + generation +
            " title=" + value.title);
        return { generation: generation, status: "done", detail: value };
    }

    function pollGameDetailArt() {
        if (!gameDetail.delivered || !gameDetail.value || scenario === "noposters") {
            gameDetail.artDone = true;
            return -1;
        }
        if (gameDetail.artQueue === null) {
            gameDetail.artQueue = buildGameArtQueue(gameDetail.value);
        }

        // Two per drain call, like a network stream landing in bursts.
        var batch = gameDetail.artQueue.splice(0, 2);
        batch.forEach(function (entry) {
            gameDetail.artEmitted += 1;
            emit("gameDetailArt", { slot: entry.slot, dataUri: entry.dataUri });
        });

        if (gameDetail.artQueue.length === 0) {
            gameDetail.artDone = true;
            return -1;
        }
        return gameDetail.artQueue.length;
    }

    function llmReply() {
        return {
            status: "ok",
            error: null,
            baseUrl: llm.baseUrl,
            model: llm.model,
            timeoutSeconds: llm.timeoutSeconds,
            titles: llm.titles,
            hasApiKey: !!llm.apiKey,
            configPath: LLM_CONFIG_PATH
        };
    }

    function llmError(field, reason) {
        return {
            status: "error",
            error: field + ": " + reason,
            baseUrl: null,
            model: null,
            timeoutSeconds: 0,
            titles: 0,
            hasApiKey: !!llm.apiKey,
            configPath: LLM_CONFIG_PATH
        };
    }

    function loadLlm() {
        llm.loads += 1;
        return llmReply();
    }

    function themeReply(id) {
        return {
            status: "ok",
            error: null,
            theme: id || theme.id,
            themes: THEMES
        };
    }

    function loadTheme() {
        theme.loads += 1;
        return themeReply();
    }

    function saveTheme(args) {
        var id = args && typeof args.theme === "string" ? args.theme.trim() : "";
        if (!THEMES.some(function (t) { return t.id === id; })) {
            theme.saves += 1;
            return {
                status: "error",
                error: "theme: unknown theme: " + id,
                theme: theme.id,
                themes: THEMES
            };
        }
        theme.id = id;
        theme.saves += 1;
        console.log("[preview] theme saved: " + id);
        return themeReply(id);
    }

    function saveLlm(args) {
        args = args || {};
        var typed = typeof args.apiKey === "string" ? args.apiKey.trim() : "";
        var key = typed || llm.apiKey;
        if (!key) {
            return llmError("apiKey", "required — enter a key");
        }
        var baseUrl = String(args.baseUrl || "").trim().replace(/\/+$/, "");
        var model = String(args.model || "").trim();
        if (!baseUrl) {
            return llmError("baseUrl", "required");
        }
        if (!model) {
            return llmError("model", "required");
        }

        // Same normalization the record applies, so the harness sees the
        // clamped values the page will paint.
        llm.apiKey = key;
        llm.baseUrl = baseUrl;
        llm.model = model;
        llm.timeoutSeconds = clamp(Number(args.timeoutSeconds) || 60, 10, 300);
        llm.titles = clamp(Number(args.titles) || 10, 1, 50);
        llm.saves += 1;
        console.log("[preview] settings saved, hasApiKey=" + !!llm.apiKey);
        return llmReply();
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, Math.round(value)));
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
            if (command === "requestSongDetail") {
                return Promise.resolve(requestDetail(args));
            }
            if (command === "pollSongDetail") {
                return Promise.resolve(pollDetail(args));
            }
            if (command === "requestMovieDetail") {
                return Promise.resolve(requestMovieDetail(args));
            }
            if (command === "pollMovieDetail") {
                return Promise.resolve(pollMovieDetail(args));
            }
            if (command === "pollMovieDetailArt") {
                return Promise.resolve(pollMovieDetailArt());
            }
            if (command === "requestBookDetail") {
                return Promise.resolve(requestBookDetail(args));
            }
            if (command === "pollBookDetail") {
                return Promise.resolve(pollBookDetail(args));
            }
            if (command === "pollBookDetailArt") {
                return Promise.resolve(pollBookDetailArt());
            }
            if (command === "requestGameDetail") {
                return Promise.resolve(requestGameDetail(args));
            }
            if (command === "pollGameDetail") {
                return Promise.resolve(pollGameDetail(args));
            }
            if (command === "pollGameDetailArt") {
                return Promise.resolve(pollGameDetailArt());
            }
            if (command === "loadLlmSettings") {
                return Promise.resolve(loadLlm());
            }
            if (command === "saveLlmSettings") {
                return Promise.resolve(saveLlm(args));
            }
            if (command === "loadTheme") {
                return Promise.resolve(loadTheme());
            }
            if (command === "saveTheme") {
                return Promise.resolve(saveTheme(args));
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
                kind: active.kind,
                query: active.query,
                generation: g,
                status: status[g] || "idle",
                dataPolls: polls[g] || 0,
                delivered: (delivered[g] || []).length,
                artworkEmitted: emitted[g] || 0,
                artDone: !!artDone[g],
                detailGeneration: detail.gen,
                detailStatus: detail.status,
                detailPolls: detail.polls,
                movieDetailGeneration: movieDetail.gen,
                movieDetailStatus: movieDetail.status,
                movieDetailPolls: movieDetail.polls,
                movieDetailArtEmitted: movieDetail.artEmitted,
                movieDetailArtDone: movieDetail.artDone,
                bookDetailGeneration: bookDetail.gen,
                bookDetailStatus: bookDetail.status,
                bookDetailPolls: bookDetail.polls,
                bookDetailArtEmitted: bookDetail.artEmitted,
                bookDetailArtDone: bookDetail.artDone,
                gameDetailGeneration: gameDetail.gen,
                gameDetailStatus: gameDetail.status,
                gameDetailPolls: gameDetail.polls,
                gameDetailArtEmitted: gameDetail.artEmitted,
                gameDetailArtDone: gameDetail.artDone,
                settingsLoads: llm.loads,
                settingsSaves: llm.saves,
                settingsHasKey: !!llm.apiKey,
                theme: theme.id,
                themeLoads: theme.loads,
                themeSaves: theme.saves,
                themeConfigPath: THEME_CONFIG_PATH
            };
        }
    };

    console.log("[preview] mock bridge installed, scenario=" + scenario);
})();
