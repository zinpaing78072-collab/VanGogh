#!/usr/bin/env node
/* Headless UI checks — one command, pass or fail, no eyeballs.
 *
 * Boots the preview server on an ephemeral port, drives a headless Chromium
 * over the DevTools protocol, and asserts the invariants that were previously
 * verified by hand: card counts, per-form card shapes, the artwork stream
 * settling, placeholder fallback, overflow, the empty/error states, and the
 * supersede guard when the sidebar switcher is clicked quickly.
 *
 * No dependencies. Node 22 has global fetch and WebSocket, which is all a CDP
 * client needs. The browser is any local Chromium/Chrome (see findBrowser());
 * override with CHROME=/path/to/binary.
 *
 *   node preview/check.js
 *   node preview/check.js --verbose     # per-test stats and console output
 *
 * Exits 1 on any failure, so it can gate a commit.
 */
"use strict";

const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const net = require("net");
const { startPreviewServer } = require("./serve.js");

const VERBOSE = process.argv.includes("--verbose");
// --page=frontend/dist runs the same assertions against a staged build.
const PAGE_ARG = process.argv.find((arg) => arg.startsWith("--page="));
const PAGE_DIR_ARG = PAGE_ARG ? PAGE_ARG.slice("--page=".length) : undefined;

// --------------------------------------------------------------- the browser

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function existsFile(p) {
    try {
        return fs.statSync(p).isFile();
    } catch (e) {
        return false;
    }
}

// Playwright and Puppeteer keep browsers in caches; using one means a machine
// that already runs end-to-end browser tests needs nothing installed.
function cachedBrowsers() {
    const home = os.homedir();
    const roots = [
        path.join(home, "Library", "Caches", "ms-playwright"),
        path.join(home, ".cache", "ms-playwright"),
        path.join(home, ".cache", "puppeteer"),
        path.join(home, ".cache", "puppeteer", "chrome")
    ];
    const found = [];

    roots.forEach((root) => {
        let entries;
        try {
            entries = fs.readdirSync(root, { withFileTypes: true });
        } catch (e) {
            return;
        }
        entries.filter((e) => e.isDirectory()).forEach((entry) => {
            const base = path.join(root, entry.name);
            [
                "chrome-mac/Chromium.app/Contents/MacOS/Chromium",
                "chrome-mac/headless_shell",
                "chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium",
                "chrome-linux/chrome",
                "chrome-linux64/chrome",
                "chrome-win/chrome.exe"
            ].forEach((rel) => {
                const candidate = path.join(base, rel);
                if (existsFile(candidate)) {
                    found.push(candidate);
                }
            });
        });
    });

    return found;
}

function findBrowser() {
    if (process.env.CHROME) {
        if (!existsFile(process.env.CHROME)) {
            throw new Error("CHROME is set but is not a file: " + process.env.CHROME);
        }
        return { binary: process.env.CHROME, headless: !/headless_shell/.test(process.env.CHROME) };
    }

    const installed = [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/usr/bin/google-chrome",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser"
    ];

    const candidates = installed.concat(cachedBrowsers());
    const binary = candidates.find(existsFile);

    if (!binary) {
        throw new Error(
            "No Chromium/Chrome found. Install Chrome, or set CHROME=/path/to/binary.\n" +
            "Looked in:\n  " + candidates.join("\n  ")
        );
    }

    return { binary: binary, headless: !/headless_shell/.test(binary) };
}

function freePort() {
    return new Promise((resolve, reject) => {
        const probe = net.createServer();
        probe.once("error", reject);
        probe.listen(0, "127.0.0.1", () => {
            const port = probe.address().port;
            probe.close(() => resolve(port));
        });
    });
}

async function launchBrowser(browser) {
    const port = await freePort();
    const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "vangogh-check-"));

    const args = [
        "--remote-debugging-port=" + port,
        "--user-data-dir=" + userDataDir,
        "--remote-allow-origins=*",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--disable-background-networking",
        "--disable-background-timer-throttling",
        "--disable-renderer-backgrounding",
        "--disable-sync",
        "--metrics-recording-only",
        "--hide-scrollbars",
        "--mute-audio",
        "--window-size=1280,900",
        "about:blank"
    ];
    if (browser.headless) {
        args.unshift("--headless=new");
    }

    const child = spawn(browser.binary, args, { stdio: "ignore" });
    let exited = false;
    child.on("exit", () => { exited = true; });

    const base = "http://127.0.0.1:" + port;
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline) {
        if (exited) {
            throw new Error("browser exited before DevTools was ready: " + browser.binary);
        }
        try {
            const res = await fetch(base + "/json/version");
            if (res.ok) {
                await res.json();
                return { port: port, base: base, child: child, userDataDir: userDataDir };
            }
        } catch (e) {
            // not listening yet
        }
        await sleep(100);
    }

    child.kill("SIGKILL");
    throw new Error("browser DevTools did not come up on port " + port);
}

async function stopBrowser(handle) {
    if (!handle) {
        return;
    }
    try {
        const res = await fetch(handle.base + "/json/version");
        const info = await res.json();
        const ws = new WebSocket(info.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
            ws.addEventListener("open", () => resolve(), { once: true });
            ws.addEventListener("error", reject, { once: true });
        });
        ws.send(JSON.stringify({ id: 1, method: "Browser.close" }));
        ws.close();
        await sleep(200);
    } catch (e) {
        // fall through to the signal path
    }
    try {
        handle.child.kill("SIGKILL");
    } catch (e) {
        // already gone
    }
    try {
        fs.rmSync(handle.userDataDir, { recursive: true, force: true });
    } catch (e) {
        // temp dir, best effort
    }
}

// ------------------------------------------------------------------- a page

const SETTLE = `(() => {
    const s = window.__preview && window.__preview.stats();
    if (!s) return false;
    if (s.status === "error") return true;
    return s.status === "done" && s.artDone;
})()`;

const DOM_STATS = `JSON.stringify({
    title: document.getElementById("sectionTitle").textContent,
    cards: document.querySelectorAll("#movieGrid .movie-card:not(.skeleton-card)").length,
    skeletons: document.querySelectorAll("#movieGrid .skeleton-card").length,
    cardClass: (document.querySelector("#movieGrid .movie-card:not(.skeleton-card)") || {}).className || "",
    withArt: document.querySelectorAll("#movieGrid .poster-img:not(.hidden)").length,
    placeholders: document.querySelectorAll("#movieGrid .poster-placeholder:not(.hidden)").length,
    broken: [...document.querySelectorAll("#movieGrid .poster-img")]
        .filter(i => i.getAttribute("src") && i.complete && i.naturalWidth === 0).length,
    // Real overflow = text painting outside its card. Deliberate ellipsis
    // (nowrap + text-overflow) and deliberate line clamping both keep text
    // inside the box, so they must not be flagged. Comparing scrollHeight to
    // clientHeight would flag every clamped title as a bug.
    overflowing: [...document.querySelectorAll("#movieGrid .movie-card")].filter(card => {
        const cr = card.getBoundingClientRect();
        return [...card.querySelectorAll(".card-title, .card-subtitle, .card-meta-item")]
            .some(el => {
                const r = el.getBoundingClientRect();
                return r.right > cr.right + 1 || r.left < cr.left - 1 || r.bottom > cr.bottom + 1;
            });
    }).length,
    gridOverflow: (() => {
        const grid = document.getElementById("movieGrid");
        return grid ? Math.max(0, grid.scrollWidth - grid.clientWidth) : 0;
    })(),
    titles: [...document.querySelectorAll("#movieGrid .card-title")].map(e => e.textContent),
    subtitle: (document.querySelector("#movieGrid .card-subtitle") || {}).textContent || "",
    emptyText: (document.querySelector("#movieGrid .empty-state p") || {}).textContent || "",
    meta: [...document.querySelectorAll("#movieGrid .movie-card:not(.skeleton-card)")]
        .map(c => [...c.querySelectorAll(".card-meta-item")].map(e => e.textContent).join(" / "))
})`;

class Page {
    constructor(port, id, wsUrl) {
        this.port = port;
        this.id = id;
        this.wsUrl = wsUrl;
        this.ws = null;
        this.seq = 0;
        this.pending = new Map();
        this.errors = [];
        this.loaded = false;
        this.loadWaiters = [];
    }

    static async open(browser, url) {
        const res = await fetch(browser.base + "/json/new", { method: "PUT" });
        if (!res.ok) {
            throw new Error("/json/new failed with HTTP " + res.status);
        }
        const target = await res.json();
        const page = new Page(browser.port, target.id, target.webSocketDebuggerUrl);
        await page.connect();
        await page.send("Runtime.enable");
        await page.send("Page.enable");
        await page.goto(url);
        return page;
    }

    connect() {
        return new Promise((resolve, reject) => {
            this.ws = new WebSocket(this.wsUrl);
            this.ws.addEventListener("open", () => resolve(), { once: true });
            this.ws.addEventListener("error", () => reject(new Error("CDP socket failed: " + this.wsUrl)), { once: true });
            this.ws.addEventListener("message", (event) => this.onMessage(event.data));
        });
    }

    onMessage(raw) {
        let msg;
        try {
            msg = JSON.parse(raw);
        } catch (e) {
            return;
        }

        if (msg.id && this.pending.has(msg.id)) {
            const waiter = this.pending.get(msg.id);
            this.pending.delete(msg.id);
            if (msg.error) {
                waiter.reject(new Error(msg.error.message || "CDP error"));
            } else {
                waiter.resolve(msg.result);
            }
            return;
        }

        if (msg.method === "Page.loadEventFired") {
            this.loaded = true;
            this.loadWaiters.splice(0).forEach((fn) => fn());
            return;
        }

        if (msg.method === "Runtime.exceptionThrown") {
            const d = msg.params.exceptionDetails || {};
            this.errors.push((d.exception && d.exception.description) || d.text || "uncaught exception");
            return;
        }

        if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
            const text = (msg.params.args || []).map((a) => a.value !== undefined ? String(a.value) : (a.description || a.type)).join(" ");
            this.errors.push(text);
        }
    }

    send(method, params) {
        const id = ++this.seq;
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve: resolve, reject: reject });
            this.ws.send(JSON.stringify({ id: id, method: method, params: params || {} }));
        });
    }

    async goto(url) {
        const loaded = new Promise((resolve) => this.loadWaiters.push(resolve));
        await this.send("Page.navigate", { url: url });
        await Promise.race([loaded, sleep(20000)]);
    }

    async evaluate(script) {
        const result = await this.send("Runtime.evaluate", {
            expression: script,
            awaitPromise: true,
            returnByValue: true,
            userGesture: true
        });
        if (result.exceptionDetails) {
            const d = result.exceptionDetails;
            throw new Error((d.exception && d.exception.description) || d.text || "page evaluation failed");
        }
        return result.result.value;
    }

    async waitFor(script, timeoutMs) {
        const limit = timeoutMs || 20000;
        const ok = await this.evaluate(`(async () => {
            const deadline = Date.now() + ${limit};
            while (Date.now() < deadline) {
                try { if (${script}) return true; } catch (e) { /* retry */ }
                await new Promise(r => setTimeout(r, 25));
            }
            return false;
        })()`);
        if (!ok) {
            throw new Error("timed out waiting for: " + script.replace(/\s+/g, " ").trim());
        }
    }

    async stats() {
        const raw = await this.evaluate("window.__preview ? JSON.stringify(window.__preview.stats()) : null");
        return raw ? JSON.parse(raw) : null;
    }

    async dom() {
        return JSON.parse(await this.evaluate(DOM_STATS));
    }

    /** Emulate a viewport — the only way to exercise a CSS breakpoint without
     *  relaunching the browser with a different --window-size. */
    async resize(width, height) {
        await this.send("Emulation.setDeviceMetricsOverride", {
            width: width,
            height: height || 860,
            deviceScaleFactor: 1,
            mobile: false
        });
    }

    async resetViewport() {
        await this.send("Emulation.clearDeviceMetricsOverride");
    }

    async settle(timeoutMs) {
        await this.waitFor(SETTLE, timeoutMs);
        return this.stats();
    }

    async selectForm(value) {
        const before = (await this.stats()).generation;
        await this.evaluate(`document.querySelector('#sidebarNav [data-domain="${value}"]').click(); true`);
        // A form with a saved session restores without firing a request, so
        // wait for the form to become active rather than for a new generation.
        await this.waitFor(
            `document.querySelector('#sidebarNav .nav-item.active[data-domain="${value}"]') !== null`);
        await sleep(50);
        const after = (await this.stats()).generation;
        return after > before ? this.settle() : this.stats();
    }

    async search(term) {
        const before = (await this.stats()).generation;
        await this.evaluate(`document.getElementById("searchInput").value = ${JSON.stringify(term)};
            document.getElementById("searchBtn").click(); true`);
        await this.waitFor(`window.__preview.stats().generation > ${before}`);
        return this.settle();
    }

    async close() {
        try {
            this.ws.close();
        } catch (e) {
            // already closed
        }
        try {
            await fetch("http://127.0.0.1:" + this.port + "/json/close/" + this.id);
        } catch (e) {
            // target may already be gone
        }
    }
}

// ------------------------------------------------------------------ asserts

function check(label, ok, detail) {
    return { label: label, ok: !!ok, detail: detail === undefined ? "" : String(detail) };
}

// One reading of the card grid at the current viewport. The responsive tests
// walk a ladder of widths, so they need the column count and the overflow
// numbers rather than the page's own DOM stats.
const GRID_PROBE = `JSON.stringify((() => {
    const grid = document.getElementById("movieGrid");
    const card = grid.querySelector(".movie-card:not(.skeleton-card)");
    if (!card) return null;
    const cr = card.getBoundingClientRect();
    const text = [...card.querySelectorAll(".card-title, .card-subtitle, .card-meta-item")];
    return {
        vw: Math.round(window.innerWidth),
        columns: getComputedStyle(grid).gridTemplateColumns.trim().split(/\\s+/).length,
        cardWidth: Math.round(cr.width),
        gridOverflow: Math.max(0, grid.scrollWidth - grid.clientWidth),
        pageOverflow: Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth),
        escaping: text.filter((el) => {
            const r = el.getBoundingClientRect();
            return r.right > cr.right + 1 || r.left < cr.left - 1 || r.bottom > cr.bottom + 1;
        }).length
    };
})())`;

async function measureGrid(page, width) {
    await page.resize(width, 860);
    // hooks/useNarrowSidebar.ts collapses the sidebar below 900px, and that rail animates: wait
    // until it has stopped moving before trusting the card widths.
    await page.evaluate(`(async () => {
        const rail = document.querySelector(".sidebar");
        let last = -1;
        for (let i = 0; i < 80; i++) {
            await new Promise((r) => requestAnimationFrame(r));
            const w = Math.round(rail.getBoundingClientRect().width);
            if (w === last) return true;
            last = w;
        }
        return false;
    })()`);
    return JSON.parse(await page.evaluate(GRID_PROBE));
}

// Every rendered card should finish with real artwork or a visible placeholder,
// never both and never a broken image.
function artworkInvariants(d) {
    return [
        check("no broken images", d.broken === 0, d.broken),
        check("no text escapes its card", d.overflowing === 0, d.overflowing),
        check("the grid does not overflow horizontally", d.gridOverflow === 0, d.gridOverflow),
        check("artwork and placeholders are exclusive", d.withArt + d.placeholders === d.cards,
            d.withArt + " art + " + d.placeholders + " placeholder for " + d.cards + " cards")
    ];
}

const TESTS = [
    {
        name: "trending movies",
        path: "/movie-page/index.html",
        run: async (page) => {
            const stats = await page.settle();
            const d = await page.dom();

            return [
                check("6 cards rendered", d.cards === 6, d.cards),
                check('section title is "Trending Movies"', d.title === "Trending Movies", d.title),
                check("5 posters streamed from the drain", d.withArt === 5, d.withArt),
                check("1 placeholder for the art-less fixture", d.placeholders === 1, d.placeholders),
                check("data drain settled", stats.status === "done", stats.status),
                check("every queued poster was emitted", stats.artworkEmitted === 5, stats.artworkEmitted)
            ].concat(artworkInvariants(d));
        }
    },
    {
        name: "sidebar switches all four forms",
        path: "/movie-page/index.html",
        run: async (page) => {
            const results = [];
            await page.settle();

            const forms = [
                { value: "game", title: "Trending Games", cls: "game-card", art: 6 },
                { value: "music", title: "Trending Music", cls: "music-card", art: 6 },
                { value: "book", title: "Trending Books", cls: "book-card", art: 6 },
                // movies: one fixture is deliberately art-less, so 5 of 6
                { value: "movie", title: "Trending Movies", cls: "movie-card", art: 5 }
            ];

            for (const form of forms) {
                await page.selectForm(form.value);
                const d = await page.dom();
                results.push(check(
                    form.value + ": title + card class",
                    d.title === form.title && d.cardClass.indexOf(form.cls) !== -1,
                    d.title + " / " + d.cardClass
                ));
                results.push(check(form.value + ": 6 cards, " + form.art + " with artwork",
                    d.cards === 6 && d.withArt === form.art, d.cards + " cards, " + d.withArt + " art"));
            }

            // The music row is the only one with an artist subtitle; the book row
            // carries the author in its meta line. Those are the domain-specific
            // shapes worth pinning.
            await page.selectForm("music");
            const music = await page.dom();
            results.push(check("music shows an artist subtitle", music.subtitle.trim().length > 0, music.subtitle));

            await page.selectForm("book");
            const book = await page.dom();
            results.push(check("book meta carries the author", /Tolkien|Herbert|Fitzgerald/.test(book.meta.join(" ")),
                book.meta[0]));

            return results;
        }
    },
    {
        name: "a form's last search survives switching away and back",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            await page.search("Inception");
            const searched = await page.dom();

            await page.selectForm("game");
            const game = await page.dom();
            const afterGameGen = (await page.stats()).generation;
            await page.selectForm("movie");
            const back = await page.dom();
            const backGen = (await page.stats()).generation;
            const input = await page.evaluate(`document.getElementById("searchInput").value`);

            return [
                check("the search narrows the grid", searched.cards === 1, searched.cards),
                check("a form visited for the first time still loads",
                    game.cards === 6 && game.title === "Trending Games",
                    game.cards + " / " + game.title),
                check("returning restores the saved cards",
                    back.cards === 1 && back.title === "Search Results",
                    back.cards + " / " + back.title),
                check("returning restores the query in the box", input === "Inception", input),
                check("returning restores the posters", back.withArt === 1, back.withArt),
                check("returning fires no request", backGen === afterGameGen,
                    afterGameGen + " → " + backGen)
            ].concat(artworkInvariants(back));
        }
    },
    {
        name: "an open detail survives form switches and Settings",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();

            await page.evaluate(`document.querySelector("#movieGrid .movie-card").click(); true`);
            await page.waitFor(`window.__preview.stats().movieDetailArtDone
                && document.querySelectorAll("#movieView .movie-facts > div").length > 0`);
            const opened = await page.stats();

            await page.selectForm("music");
            await page.selectForm("movie");
            await page.waitFor(`!document.getElementById("movieView").classList.contains("hidden")`);

            const restored = JSON.parse(await page.evaluate(`JSON.stringify({
                movieVisible: !document.getElementById("movieView").classList.contains("hidden"),
                title: ((document.querySelector("#movieView .movie-title") || {}).textContent || "").trim(),
                posterArt: document.querySelectorAll("#movieView .movie-poster-img:not(.hidden)").length,
                castArt: document.querySelectorAll("#movieView .movie-cast-img:not(.hidden)").length,
                stillArt: document.querySelectorAll("#movieView .movie-shot-img:not(.hidden)").length,
                simArt: document.querySelectorAll("#movieView .movie-sim-img:not(.hidden)").length,
                generation: window.__preview.stats().movieDetailGeneration
            })`));

            await page.evaluate(`document.getElementById("settingsNav").click(); true`);
            await page.waitFor(`!document.getElementById("settingsView").classList.contains("hidden")`);
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.getElementById("movieView").classList.contains("hidden")`);

            const afterSettings = JSON.parse(await page.evaluate(`JSON.stringify({
                movieVisible: !document.getElementById("movieView").classList.contains("hidden"),
                title: ((document.querySelector("#movieView .movie-title") || {}).textContent || "").trim(),
                generation: window.__preview.stats().movieDetailGeneration
            })`));

            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.getElementById("browseView").classList.contains("hidden")
                && document.getElementById("movieView").classList.contains("hidden")`);
            const back = await page.dom();

            return [
                check("the detail reopens after a form switch",
                    restored.movieVisible && restored.title === "Inception",
                    restored.movieVisible + " / " + restored.title),
                check("every artwork slot comes back",
                    restored.posterArt === 1 && restored.castArt === 8
                        && restored.stillArt === 3 && restored.simArt === 5,
                    restored.posterArt + "/" + restored.castArt + "/" + restored.stillArt + "/" + restored.simArt),
                check("reopening the detail fires no request",
                    restored.generation === opened.movieDetailGeneration,
                    opened.movieDetailGeneration + " → " + restored.generation),
                check("Settings returns to the detail it covered",
                    afterSettings.movieVisible && afterSettings.title === "Inception"
                        && afterSettings.generation === opened.movieDetailGeneration,
                    afterSettings.movieVisible + " / " + afterSettings.title),
                check("Back then returns to the restored grid",
                    back.cards === 6 && back.title === "Trending Movies",
                    back.cards + " / " + back.title)
            ];
        }
    },
    {
        name: "a form's scroll position survives switching away and back",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            // Narrow enough that the six-card grid is taller than the viewport.
            await page.resize(600, 700);
            await page.evaluate(`window.scrollTo(0, 300); true`);
            await page.waitFor(`window.scrollY > 0`);
            const before = await page.evaluate(`window.scrollY`);

            await page.selectForm("game");
            const left = await page.evaluate(`window.scrollY`);

            await page.selectForm("movie");
            await page.waitFor(`Math.abs(window.scrollY - ${before}) <= 1`);
            const after = await page.evaluate(`window.scrollY`);
            await page.resetViewport();

            return [
                check("a fresh form starts at the top", left === 0, left),
                check("returning restores the scroll position",
                    Math.abs(after - before) <= 1, before + " → " + after)
            ];
        }
    },
    {
        name: "music card opens the song detail",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            await page.selectForm("music");

            await page.evaluate(`document.querySelector("#movieGrid .music-card").click(); true`);
            await page.waitFor(`document.querySelectorAll("#songView .lyrics-line").length > 0`);

            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                browseHidden: document.getElementById("browseView").classList.contains("hidden"),
                songVisible: !document.getElementById("songView").classList.contains("hidden"),
                title: ((document.querySelector("#songView .song-title") || {}).textContent || "").trim(),
                artist: ((document.querySelector("#songView .song-artist") || {}).textContent || "").trim(),
                credits: document.querySelectorAll("#songView .credit-row").length,
                lyrics: document.querySelectorAll("#songView .lyrics-line").length,
                related: document.querySelectorAll("#songView .related-row").length,
                dummyMarks: document.querySelectorAll("#songView .dummy-mark").length,
                playerVisible: !document.getElementById("playerBar").classList.contains("hidden"),
                playerDuration: document.getElementById("playerDuration").textContent,
                artShown: !!document.querySelector("#songView .song-art-img:not(.hidden)"),
                language: ((document.querySelector("#songView .song-lang") || {}).textContent || "")
                    .replace(/\\s+/g, " ").trim(),
                relatedAll: (document.querySelector("#songView .related-all") || {}).href || "",
                creditsToggle: document.querySelectorAll("#songView .credits-toggle").length,
                creditsHidden: (function () {
                    var panel = document.querySelector("#songView .credit-all");
                    return !!panel && panel.classList.contains("hidden");
                })(),
                listenLinks: document.querySelectorAll("#songView .song-listen-link").length,
                aboutParas: document.querySelectorAll("#songView .about-body p").length,
                hasAboutToggle: document.querySelectorAll('#songView [data-action="about"]').length,
                verified: document.querySelectorAll("#songView .artist-verified").length,
                avatar: document.querySelectorAll("#songView .artist-avatar").length,
                albumCover: document.querySelectorAll("#songView .meta-cover").length,
                recorded: document.querySelectorAll("#songView .song-meta-cell.wide").length,
                stats: document.querySelectorAll("#songView .song-stat").length,
                palette: (document.querySelector("#songView .song-artwork") || {getAttribute: function () { return ""; }})
                    .getAttribute("style") || "",
                tiles: document.querySelectorAll("#songView .song-bento > .song-tile").length,
                paletteChips: document.querySelectorAll("#songView .song-palette .palette-chip").length,
                headPlay: document.querySelectorAll("#songView .t-head .song-action-primary").length,
                headGenius: document.querySelectorAll("#songView .t-head a.song-action").length,
                overflow: Math.max(0, document.getElementById("songView").scrollWidth
                    - document.getElementById("songView").clientWidth)
            })`));

            await page.evaluate(`document.querySelector('#songView [data-action="credits"]').click(); true`);
            const creditsOpen = JSON.parse(await page.evaluate(`JSON.stringify({
                open: !document.querySelector("#songView .credit-all").classList.contains("hidden"),
                rows: document.querySelectorAll("#songView .credit-all-row").length
            })`));

            await page.evaluate(`document.querySelector('#songView [data-action="about"]').click(); true`);
            const aboutOpen = JSON.parse(await page.evaluate(`JSON.stringify({
                expanded: document.querySelector("#songView .about-body").classList.contains("expanded"),
                label: document.querySelector('#songView [data-action="about"]').textContent.trim()
            })`));

            await page.evaluate(`document.querySelector('#songView [data-action="back"]').click(); true`);
            await page.waitFor(`!document.getElementById("browseView").classList.contains("hidden")
                && document.getElementById("songView").classList.contains("hidden")`);
            const back = JSON.parse(await page.evaluate(`JSON.stringify({
                cards: document.querySelectorAll("#movieGrid .movie-card").length,
                playerHidden: document.getElementById("playerBar").classList.contains("hidden"),
                title: document.getElementById("sectionTitle").textContent
            })`));

            return [
                check("clicking a music card opens the detail view",
                    d.browseHidden && d.songVisible, d.browseHidden + "/" + d.songVisible),
                check("detail shows the song title and artist",
                    d.title === "Rumours" && d.artist === "Fleetwood Mac",
                    d.title + " / " + d.artist),
                check("detail streams the hero artwork", d.artShown, d.artShown),
                check("credits rows rendered", d.credits >= 2, d.credits),
                check("dummy lyrics render with timings", d.lyrics >= 6, d.lyrics),
                check("related rail resolves rows", d.related === 5, d.related),
                check("duration is marked as demo data", d.dummyMarks === 1, d.dummyMarks),
                check("language pill carries the translation count",
                    d.language === "English · 22 translations", d.language),
                check("related rail links Genius' full relationships page",
                    d.relatedAll.indexOf("/relationships") !== -1, d.relatedAll),
                check("all-credits expander opens with the full bill",
                    d.creditsToggle === 1 && d.creditsHidden
                        && creditsOpen.open && creditsOpen.rows === 3,
                    d.creditsToggle + "/" + creditsOpen.rows),
                check("listen links render per provider", d.listenLinks === 2, d.listenLinks),
                check("About this song is collapsed behind Read more",
                    d.aboutParas === 3 && d.hasAboutToggle === 1
                        && aboutOpen.expanded && aboutOpen.label === "Show less",
                    d.aboutParas + "/" + aboutOpen.label),
                check("artist identity shows avatar and verified badge",
                    d.avatar === 1 && d.verified === 1, d.avatar + "/" + d.verified),
                check("album cover, recording location and community stats render",
                    d.albumCover === 1 && d.recorded === 1 && d.stats === 4,
                    d.albumCover + "/" + d.recorded + "/" + d.stats),
                check("artwork palette is applied to the hero",
                    d.palette.indexOf("--song-primary") !== -1, d.palette),
                check("detail lays out as seven bento tiles",
                    d.tiles === 7, d.tiles),
                check("details tile shows the three palette swatches",
                    d.paletteChips === 3, d.paletteChips),
                check("identity tile carries the Play pill and Genius link",
                    d.headPlay === 1 && d.headGenius === 1,
                    d.headPlay + "/" + d.headGenius),
                check("player shows the dummy 4:12 length",
                    d.playerVisible && d.playerDuration === "4:12", d.playerDuration),
                check("detail view does not overflow horizontally", d.overflow === 0, d.overflow),
                check("back returns to the intact card grid",
                    back.cards === 6 && back.title === "Trending Music", back.cards + " / " + back.title),
                check("back hides the player", back.playerHidden, back.playerHidden)
            ];
        }
    },
    {
        name: "movie card opens the movie detail",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();

            await page.evaluate(`document.querySelector("#movieGrid .movie-card").click(); true`);
            await page.waitFor(`window.__preview.stats().movieDetailArtDone
                && document.querySelectorAll("#movieView .movie-facts > div").length > 0`);

            const stats = await page.stats();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                browseHidden: document.getElementById("browseView").classList.contains("hidden"),
                movieVisible: !document.getElementById("movieView").classList.contains("hidden"),
                title: ((document.querySelector("#movieView .movie-title") || {}).textContent || "").trim(),
                tagline: ((document.querySelector("#movieView .movie-tagline") || {}).textContent || "").trim(),
                chips: document.querySelectorAll("#movieView .movie-kicker .chip").length,
                facts: document.querySelectorAll("#movieView .movie-facts > div").length,
                firstFact: ((document.querySelector("#movieView .movie-facts dt") || {}).textContent || "").trim()
                    + " = " + ((document.querySelector("#movieView .movie-facts dd") || {}).textContent || "").trim(),
                mutedFacts: document.querySelectorAll("#movieView .movie-facts dd.muted").length,
                keywords: document.querySelectorAll("#movieView .movie-keywords li").length,
                cast: document.querySelectorAll("#movieView .movie-cast-card").length,
                crewRoles: document.querySelectorAll("#movieView .movie-cast-meta .role.crew").length,
                stills: document.querySelectorAll("#movieView .movie-shot").length,
                similar: document.querySelectorAll("#movieView .movie-sim-card").length,
                tiles: document.querySelectorAll("#movieView .movie-bento > .movie-tile").length,
                posterHeight: document.querySelector("#movieView .t-poster").getBoundingClientRect().height,
                identHeight: document.querySelector("#movieView .t-ident").getBoundingClientRect().height,
                posterArt: document.querySelectorAll("#movieView .movie-poster-img:not(.hidden)").length,
                castArt: document.querySelectorAll("#movieView .movie-cast-img:not(.hidden)").length,
                stillArt: document.querySelectorAll("#movieView .movie-shot-img:not(.hidden)").length,
                simArt: document.querySelectorAll("#movieView .movie-sim-img:not(.hidden)").length,
                // The rail card is a <button>; WebKit's UA sheet sets it to
                // align-items:flex-start, which collapsed this aspect-ratio box
                // to 2×3px on macOS. Assert the 2:3 shape, not just the event.
                simPoster: (function () {
                    var r = document.querySelector("#movieView .movie-sim-poster").getBoundingClientRect();
                    return { w: Math.round(r.width), h: Math.round(r.height) };
                })(),
                visiblePlaceholders: document.querySelectorAll("#movieView .poster-placeholder:not(.hidden)").length,
                broken: [...document.querySelectorAll("#movieView img")]
                    .filter(i => i.getAttribute("src") && i.complete && i.naturalWidth === 0).length,
                trailerButton: !!document.querySelector('#movieView [data-action="trailer"]'),
                tmdb: [...document.querySelectorAll("#movieView a.movie-action")]
                    .map(a => a.href).filter(h => h.indexOf("themoviedb.org") !== -1).length,
                actions: document.querySelectorAll("#movieView .movie-action").length,
                overflow: Math.max(0, document.getElementById("movieView").scrollWidth
                    - document.getElementById("movieView").clientWidth)
            })`));

            await page.evaluate(`document.querySelector('#movieView [data-action="watchlist"]').click(); true`);
            const watchlist = JSON.parse(await page.evaluate(`JSON.stringify({
                pressed: document.querySelector('#movieView [data-action="watchlist"]').getAttribute("aria-pressed"),
                text: document.querySelector('#movieView [data-action="watchlist"]').textContent.trim()
            })`));

            // The trailer plays in-page: the button opens an embedded YouTube
            // player, and Escape closes the player without leaving the detail.
            await page.evaluate(`document.querySelector('#movieView [data-action="trailer"]').click(); true`);
            await page.waitFor(`!!document.querySelector(".movie-trailer iframe")`);
            const trailer = JSON.parse(await page.evaluate(`JSON.stringify((function () {
                var overlay = document.querySelector(".movie-trailer");
                var frame = document.querySelector(".movie-trailer-frame").getBoundingClientRect();
                var topLeft = document.elementFromPoint(6, 6);
                return {
                    overlay: !!overlay,
                    dialog: overlay.getAttribute("role"),
                    lightbox: overlay.dataset.lightboxOpen,
                    src: overlay.querySelector("iframe").src,
                    title: overlay.querySelector("iframe").title,
                    coversShell: !!topLeft && overlay.contains(topLeft),
                    ratio: frame.width / frame.height,
                    inViewport: frame.top >= 0 && frame.left >= 0
                        && frame.bottom <= window.innerHeight && frame.right <= window.innerWidth
                };
            })())`));
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.querySelector(".movie-trailer")`);
            const trailerClosed = await page.evaluate(
                `!document.getElementById("movieView").classList.contains("hidden")`);

            // The related rail opens another film through the same detail pair.
            const firstGen = stats.movieDetailGeneration;
            const relatedTitle = await page.evaluate(
                `document.querySelector("#movieView .movie-sim-card").dataset.title`);
            await page.evaluate(`document.querySelector("#movieView .movie-sim-card").click(); true`);
            await page.waitFor(`window.__preview.stats().movieDetailGeneration > ${firstGen}
                && window.__preview.stats().movieDetailStatus === "done"
                && ((document.querySelector("#movieView .movie-title") || {}).textContent || "").trim()
                    === ${JSON.stringify(relatedTitle)}`);
            const related = JSON.parse(await page.evaluate(`JSON.stringify({
                title: ((document.querySelector("#movieView .movie-title") || {}).textContent || "").trim(),
                generation: window.__preview.stats().movieDetailGeneration
            })`));

            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.getElementById("browseView").classList.contains("hidden")
                && document.getElementById("movieView").classList.contains("hidden")`);
            const back = JSON.parse(await page.evaluate(`JSON.stringify({
                cards: document.querySelectorAll("#movieGrid .movie-card").length,
                title: document.getElementById("sectionTitle").textContent
            })`));

            return [
                check("clicking a movie card opens the detail view",
                    d.browseHidden && d.movieVisible, d.browseHidden + "/" + d.movieVisible),
                check("detail shows the clicked film's identity",
                    d.title === "Inception" && d.tagline.indexOf("Your mind") === 0,
                    d.title + " / " + d.tagline),
                check("kicker carries year, certification and runtime", d.chips === 3, d.chips),
                check("facts ledger renders all twelve rows", d.facts === 12, d.facts),
                check("facts start with the release date",
                    d.firstFact === "Release = Jul 16, 2010" && d.mutedFacts === 5,
                    d.firstFact + " / " + d.mutedFacts + " muted"),
                check("keywords render as chips", d.keywords === 6, d.keywords),
                check("cast & crew render with the two crew roles",
                    d.cast === 8 && d.crewRoles === 2, d.cast + " / " + d.crewRoles),
                check("artwork stills render", d.stills === 3, d.stills),
                check("similar rail renders", d.similar === 5, d.similar),
                check("detail lays out as seven bento tiles", d.tiles === 7, d.tiles),
                check("the poster tile matches the identity tile's height",
                    Math.abs(d.posterHeight - d.identHeight) <= 1,
                    d.posterHeight + " / " + d.identHeight),
                check("poster streams into its slot", d.posterArt === 1, d.posterArt),
                check("every headshot streams into its slot", d.castArt === 8, d.castArt),
                check("every still streams into its slot", d.stillArt === 3, d.stillArt),
                check("every similar poster streams into its slot", d.simArt === 5, d.simArt),
                check("similar rail lays every poster out at 2:3",
                    d.simPoster.h > 100 && Math.abs(d.simPoster.w / d.simPoster.h - 2 / 3) < 0.02,
                    d.simPoster.w + "×" + d.simPoster.h),
                check("artwork replaces every placeholder",
                    d.visiblePlaceholders === 0 && d.broken === 0,
                    d.visiblePlaceholders + " placeholders / " + d.broken + " broken"),
                check("every queued image was emitted", stats.movieDetailArtEmitted === 17,
                    stats.movieDetailArtEmitted),
                check("identity carries trailer, watchlist, favorite and TMDB actions",
                    d.trailerButton && d.tmdb === 1 && d.actions === 4,
                    d.trailerButton + " / " + d.tmdb + " / " + d.actions),
                check("the trailer opens an in-page YouTube embed",
                    trailer.overlay && trailer.dialog === "dialog" && trailer.lightbox === "true"
                        && trailer.src.indexOf("youtube.com/embed/preview") !== -1
                        && trailer.title === "Inception trailer",
                    trailer.src + " / " + trailer.title),
                check("the player covers the shell at 16:9",
                    trailer.coversShell && Math.abs(trailer.ratio - 16 / 9) < 0.02
                        && trailer.inViewport,
                    trailer.coversShell + " / " + trailer.ratio.toFixed(3)),
                check("Escape closes the player and stays on the detail",
                    trailerClosed === true, trailerClosed),
                check("watchlist toggles on and says so",
                    watchlist.pressed === "true" && watchlist.text.indexOf("In watchlist") !== -1,
                    watchlist.pressed + " / " + watchlist.text),
                check("detail view does not overflow horizontally", d.overflow === 0, d.overflow),
                check("a similar card opens that film in the same view",
                    related.title === relatedTitle && related.generation === firstGen + 1,
                    related.title + " / gen " + related.generation),
                check("Escape returns to the intact card grid",
                    back.cards === 6 && back.title === "Trending Movies",
                    back.cards + " / " + back.title)
            ];
        }
    },
    {
        name: "book card opens the book detail",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.selectForm("book");

            await page.evaluate(`document.querySelector("#movieGrid .book-card").click(); true`);
            await page.waitFor(`window.__preview.stats().bookDetailArtDone
                && document.querySelectorAll("#bookView .book-facts > div").length > 0`);

            const stats = await page.stats();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                browseHidden: document.getElementById("browseView").classList.contains("hidden"),
                bookVisible: !document.getElementById("bookView").classList.contains("hidden"),
                title: ((document.querySelector("#bookView .book-title") || {}).textContent || "").trim(),
                byline: ((document.querySelector("#bookView .book-byline-who b") || {}).textContent || "").trim(),
                chips: document.querySelectorAll("#bookView .book-kicker .chip").length,
                facts: document.querySelectorAll("#bookView .book-facts > div").length,
                firstFact: ((document.querySelector("#bookView .book-facts dt") || {}).textContent || "").trim()
                    + " = " + ((document.querySelector("#bookView .book-facts dd") || {}).textContent || "").trim(),
                spanFacts: document.querySelectorAll("#bookView .book-facts > div.span-all").length,
                keywords: document.querySelectorAll("#bookView .book-keywords li").length,
                editions: document.querySelectorAll("#bookView .book-edition").length,
                expenses: document.querySelectorAll("#bookView .book-edition-meta b").length,
                similar: document.querySelectorAll("#bookView .book-sim-card").length,
                tiles: document.querySelectorAll("#bookView .book-bento > .book-tile").length,
                actions: document.querySelectorAll("#bookView .book-action").length,
                sampleButton: !!document.querySelector('#bookView [data-action="sample"]'),
                coverArt: document.querySelectorAll("#bookView .book-cover-img:not(.hidden)").length,
                authorArt: document.querySelectorAll("#bookView .book-author-img:not(.hidden)").length,
                editionArt: document.querySelectorAll("#bookView .book-edition-img:not(.hidden)").length,
                simArt: document.querySelectorAll("#bookView .book-sim-img:not(.hidden)").length,
                // Same button/flex trap as the movie rail (see its comment).
                simPoster: (function () {
                    var r = document.querySelector("#bookView .book-sim-poster").getBoundingClientRect();
                    return { w: Math.round(r.width), h: Math.round(r.height) };
                })(),
                visiblePlaceholders: document.querySelectorAll("#bookView .poster-placeholder:not(.hidden)").length,
                broken: [...document.querySelectorAll("#bookView img")]
                    .filter(i => i.getAttribute("src") && i.complete && i.naturalWidth === 0).length,
                overflow: Math.max(0, document.getElementById("bookView").scrollWidth
                    - document.getElementById("bookView").clientWidth)
            })`));

            // Browse sample opens the Internet Archive reader in an overlay;
            // Escape closes the reader without leaving the detail.
            await page.evaluate(`document.querySelector('#bookView [data-action="sample"]').click(); true`);
            await page.waitFor(`!!document.querySelector(".book-sample-overlay iframe")`);
            const sample = JSON.parse(await page.evaluate(`JSON.stringify((function () {
                var overlay = document.querySelector(".book-sample-overlay");
                var frame = document.querySelector(".book-sample-frame").getBoundingClientRect();
                var topLeft = document.elementFromPoint(6, 6);
                return {
                    overlay: !!overlay,
                    dialog: overlay.getAttribute("role"),
                    lightbox: overlay.dataset.lightboxOpen,
                    src: overlay.querySelector("iframe").src,
                    title: overlay.querySelector("iframe").title,
                    coversShell: !!topLeft && overlay.contains(topLeft),
                    inViewport: frame.top >= 0 && frame.left >= 0
                        && frame.bottom <= window.innerHeight && frame.right <= window.innerWidth
                };
            })())`));
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.querySelector(".book-sample-overlay")`);
            const sampleClosed = await page.evaluate(
                `!document.getElementById("bookView").classList.contains("hidden")`);

            // The similar rail opens another work through the same detail pair.
            const firstGen = stats.bookDetailGeneration;
            const relatedTitle = await page.evaluate(
                `document.querySelector("#bookView .book-sim-card").dataset.title`);
            await page.evaluate(`document.querySelector("#bookView .book-sim-card").click(); true`);
            await page.waitFor(`window.__preview.stats().bookDetailGeneration > ${firstGen}
                && window.__preview.stats().bookDetailStatus === "done"
                && ((document.querySelector("#bookView .book-title") || {}).textContent || "").trim()
                    === ${JSON.stringify(relatedTitle)}`);
            const related = JSON.parse(await page.evaluate(`JSON.stringify({
                title: ((document.querySelector("#bookView .book-title") || {}).textContent || "").trim(),
                generation: window.__preview.stats().bookDetailGeneration
            })`));

            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.getElementById("browseView").classList.contains("hidden")
                && document.getElementById("bookView").classList.contains("hidden")`);
            const back = JSON.parse(await page.evaluate(`JSON.stringify({
                cards: document.querySelectorAll("#movieGrid .book-card").length,
                title: document.getElementById("sectionTitle").textContent
            })`));

            return [
                check("clicking a book card opens the detail view",
                    d.browseHidden && d.bookVisible, d.browseHidden + "/" + d.bookVisible),
                check("detail shows the clicked work's identity",
                    d.title === "The Lord of the Rings" && d.byline === "J. R. R. Tolkien",
                    d.title + " / " + d.byline),
                check("kicker carries year, pages and edition count", d.chips === 3, d.chips),
                check("facts ledger renders all eight rows", d.facts === 8, d.facts),
                check("facts start with the publication year",
                    d.firstFact === "First published = 1954" && d.spanFacts === 2,
                    d.firstFact + " / " + d.spanFacts + " full width"),
                check("subjects render as chips", d.keywords === 3, d.keywords),
                check("other editions render", d.editions === 4 && d.expenses === 4,
                    d.editions + " / " + d.expenses),
                check("similar rail renders", d.similar === 5, d.similar),
                check("detail lays out as seven bento tiles", d.tiles === 7, d.tiles),
                check("identity carries sample, want-to-read, favorite and Open Library actions",
                    d.sampleButton && d.actions === 4, d.sampleButton + " / " + d.actions),
                check("cover streams into its slot", d.coverArt === 1, d.coverArt),
                check("the author photo paints the byline and the author tile", d.authorArt === 2, d.authorArt),
                check("every covered edition streams into its slot", d.editionArt === 3, d.editionArt),
                check("every similar cover streams into its slot", d.simArt === 5, d.simArt),
                check("similar rail lays every cover out at 2:3",
                    d.simPoster.h > 100 && Math.abs(d.simPoster.w / d.simPoster.h - 2 / 3) < 0.02,
                    d.simPoster.w + "×" + d.simPoster.h),
                check("a coverless edition keeps its placeholder instead of breaking",
                    d.visiblePlaceholders === 1 && d.broken === 0,
                    d.visiblePlaceholders + " placeholders / " + d.broken + " broken"),
                check("every queued image was emitted", stats.bookDetailArtEmitted === 10,
                    stats.bookDetailArtEmitted),
                check("Browse sample opens the Internet Archive reader",
                    sample.overlay && sample.dialog === "dialog" && sample.lightbox === "true"
                        && sample.src.indexOf("archive.org/embed/lordofrings0000tolk") !== -1
                        && sample.title === "The Lord of the Rings sample reader",
                    sample.src + " / " + sample.title),
                check("the sample reader covers the shell and fits the viewport",
                    sample.coversShell && sample.inViewport,
                    sample.coversShell + " / " + sample.inViewport),
                check("Escape closes the sample and stays on the detail",
                    sampleClosed === true, sampleClosed),
                check("detail view does not overflow horizontally", d.overflow === 0, d.overflow),
                check("a similar card opens that work in the same view",
                    related.title === relatedTitle && related.generation === firstGen + 1,
                    related.title + " / gen " + related.generation),
                check("Escape returns to the intact card grid",
                    back.cards === 6 && back.title === "Trending Books",
                    back.cards + " / " + back.title)
            ];
        }
    },
    {
        name: "game card opens the game detail",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.selectForm("game");

            await page.evaluate(`document.querySelector("#movieGrid .game-card").click(); true`);
            await page.waitFor(`window.__preview.stats().gameDetailArtDone
                && document.querySelectorAll("#gameView .game-facts > div").length > 0`);

            const stats = await page.stats();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                browseHidden: document.getElementById("browseView").classList.contains("hidden"),
                gameVisible: !document.getElementById("gameView").classList.contains("hidden"),
                title: ((document.querySelector("#gameView .game-title") || {}).textContent || "").trim(),
                lede: ((document.querySelector("#gameView .game-lede") || {}).textContent || "").trim(),
                chips: document.querySelectorAll("#gameView .game-chip").length,
                meta: document.querySelectorAll("#gameView .game-meta-row span:not(.game-dot)").length,
                stats: document.querySelectorAll("#gameView .game-stat").length,
                firstStat: ((document.querySelector("#gameView .game-stat dt") || {}).textContent || "").trim()
                    + " = " + ((document.querySelector("#gameView .game-stat dd") || {}).textContent || "").trim(),
                facts: document.querySelectorAll("#gameView .game-facts > div").length,
                firstFact: ((document.querySelector("#gameView .game-facts dt") || {}).textContent || "").trim()
                    + " = " + ((document.querySelector("#gameView .game-facts dd") || {}).textContent || "").trim(),
                quote: document.querySelectorAll("#gameView .game-opening").length,
                paragraphs: document.querySelectorAll("#gameView .game-prose p").length,
                stills: document.querySelectorAll("#gameView .game-still").length,
                platforms: document.querySelectorAll("#gameView .game-platform-tile").length,
                specs: document.querySelectorAll("#gameView .game-spec-row").length,
                related: document.querySelectorAll("#gameView .game-rel-card").length,
                relatedKicker: ((document.querySelector("#gameView .game-related .game-kicker") || {}).textContent || "").trim(),
                actions: document.querySelectorAll("#gameView .game-action").length,
                recordLink: [...document.querySelectorAll("#gameView a")]
                    .filter(a => a.href.indexOf("thegamesdb.net") !== -1).length,
                boxartArt: document.querySelectorAll("#gameView .game-cover-img:not(.hidden)").length,
                heroArt: document.querySelectorAll("#gameView .game-hero-bg:not(.hidden)").length,
                stillArt: document.querySelectorAll("#gameView .game-still-img:not(.hidden)").length,
                relatedArt: document.querySelectorAll("#gameView .game-rel-img:not(.hidden)").length,
                // Same button/flex trap as the movie and book rails (see their
                // comments): WebKit collapses the aspect-ratio box to 2×3px.
                relPoster: (function () {
                    var r = document.querySelector("#gameView .game-rel-poster").getBoundingClientRect();
                    return { w: Math.round(r.width), h: Math.round(r.height) };
                })(),
                visiblePlaceholders: document.querySelectorAll("#gameView .poster-placeholder:not(.hidden)").length,
                broken: [...document.querySelectorAll("#gameView img")]
                    .filter(i => i.getAttribute("src") && i.complete && i.naturalWidth === 0).length,
                overflow: Math.max(0, document.getElementById("gameView").scrollWidth
                    - document.getElementById("gameView").clientWidth)
            })`));

            // The save pills toggle locally, like the other detail views.
            await page.evaluate(`document.querySelector('#gameView [data-action="watchlist"]').click(); true`);
            const watchlist = JSON.parse(await page.evaluate(`JSON.stringify({
                pressed: document.querySelector('#gameView [data-action="watchlist"]').getAttribute("aria-pressed"),
                text: document.querySelector('#gameView [data-action="watchlist"]').textContent.trim()
            })`));

            // A screenshot opens the in-view lightbox; Escape closes the
            // lightbox first and only the second Escape leaves the page.
            await page.evaluate(`document.querySelector("#gameView .game-still-open").click(); true`);
            const lightbox = JSON.parse(await page.evaluate(`JSON.stringify((function () {
                const l = document.querySelector("#gameView .game-lightbox");
                const r = l.getBoundingClientRect();
                const vw = document.documentElement.clientWidth;
                const vh = document.documentElement.clientHeight;
                const atSidebar = document.elementFromPoint(20, Math.round(vh / 2));
                return {
                    open: !l.classList.contains("hidden"),
                    src: (l.querySelector(".game-lightbox-img") || {}).src || "",
                    caption: (l.querySelector(".game-lightbox-caption") || {}).textContent || "",
                    covers: Math.abs(r.top) < 1 && Math.abs(r.left) < 1
                        && Math.abs(r.width - vw) < 1 && Math.abs(r.height - vh) < 1,
                    aboveShell: !!atSidebar && l.contains(atSidebar)
                };
            })())`));
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            const afterFirstEscape = JSON.parse(await page.evaluate(`JSON.stringify({
                lightbox: !document.querySelector("#gameView .game-lightbox").classList.contains("hidden"),
                gameVisible: !document.getElementById("gameView").classList.contains("hidden")
            })`));

            // The related rail opens another game through the same detail pair.
            const firstGen = stats.gameDetailGeneration;
            const relatedTitle = await page.evaluate(
                `document.querySelector("#gameView .game-rel-card").dataset.title`);
            await page.evaluate(`document.querySelector("#gameView .game-rel-card").click(); true`);
            await page.waitFor(`window.__preview.stats().gameDetailGeneration > ${firstGen}
                && window.__preview.stats().gameDetailStatus === "done"
                && ((document.querySelector("#gameView .game-title") || {}).textContent || "").trim()
                    === ${JSON.stringify(relatedTitle)}`);
            const related = JSON.parse(await page.evaluate(`JSON.stringify({
                title: ((document.querySelector("#gameView .game-title") || {}).textContent || "").trim(),
                generation: window.__preview.stats().gameDetailGeneration,
                facts: document.querySelectorAll("#gameView .game-facts > div").length
            })`));

            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.waitFor(`!document.getElementById("browseView").classList.contains("hidden")
                && document.getElementById("gameView").classList.contains("hidden")`);
            const back = JSON.parse(await page.evaluate(`JSON.stringify({
                cards: document.querySelectorAll("#movieGrid .game-card").length,
                title: document.getElementById("sectionTitle").textContent
            })`));

            // The sparse fixture: no rating, platform, publisher or developer,
            // no boxart and one screenshot — the page keeps placeholders and
            // drops the sections the record cannot fill.
            const sparseGen = await page.evaluate(`window.__preview.stats().gameDetailGeneration`);
            await page.evaluate(`document.querySelectorAll("#movieGrid .game-card")[3].click(); true`);
            await page.waitFor(`window.__preview.stats().gameDetailGeneration > ${sparseGen}
                && window.__preview.stats().gameDetailStatus === "done"
                && window.__preview.stats().gameDetailArtDone`);
            const sparse = JSON.parse(await page.evaluate(`JSON.stringify({
                title: ((document.querySelector("#gameView .game-title") || {}).textContent || "").trim(),
                facts: document.querySelectorAll("#gameView .game-facts > div").length,
                chips: document.querySelectorAll("#gameView .game-chip").length,
                platforms: document.querySelectorAll("#gameView .game-platform-tile").length,
                stills: document.querySelectorAll("#gameView .game-still").length,
                coverPlaceholder: document.querySelectorAll("#gameView .game-hero-cover .poster-placeholder:not(.hidden)").length,
                broken: [...document.querySelectorAll("#gameView img")]
                    .filter(i => i.getAttribute("src") && i.complete && i.naturalWidth === 0).length
            })`));

            return [
                check("clicking a game card opens the detail view",
                    d.browseHidden && d.gameVisible, d.browseHidden + "/" + d.gameVisible),
                check("detail shows the clicked game's identity",
                    d.title === "Minecraft" && d.lede.indexOf("Build, explore") === 0,
                    d.title + " / " + d.lede.slice(0, 32)),
                check("kicker and meta line carry year, genres, rating, players and platforms",
                    d.chips === 4 && d.meta === 3, d.chips + " chips / " + d.meta + " meta"),
                check("stat band renders four cells starting with the release year",
                    d.stats === 4 && d.firstStat.indexOf("Released = 2011") === 0,
                    d.stats + " / " + d.firstStat),
                check("record rail renders eight facts starting with the release date",
                    d.facts === 8 && d.firstFact === "Release = 2011-11-18",
                    d.facts + " / " + d.firstFact),
                check("the opening pull quote and both overview paragraphs render",
                    d.quote === 1 && d.paragraphs === 2, d.quote + " / " + d.paragraphs),
                check("screenshots render", d.stills === 3, d.stills),
                check("every platform renders as a tile", d.platforms === 4, d.platforms),
                check("the spec grid pairs players, release, genres and rating", d.specs === 4, d.specs),
                check("the related rail labels itself as same-series",
                    d.related === 4 && d.relatedKicker === "Same series",
                    d.related + " / " + d.relatedKicker),
                check("hero carries trailer, watchlist, favorite and record actions",
                    d.actions === 4 && d.recordLink >= 1, d.actions + " / " + d.recordLink),
                check("boxart and fanart stream into their slots",
                    d.boxartArt === 1 && d.heroArt === 1, d.boxartArt + " / " + d.heroArt),
                check("every screenshot streams into its slot", d.stillArt === 3, d.stillArt),
                check("every related boxart streams into its slot", d.relatedArt === 4, d.relatedArt),
                check("related rail lays every boxart out at 2:3",
                    d.relPoster.h > 100 && Math.abs(d.relPoster.w / d.relPoster.h - 2 / 3) < 0.02,
                    d.relPoster.w + "×" + d.relPoster.h),
                check("artwork replaces every placeholder",
                    d.visiblePlaceholders === 0 && d.broken === 0,
                    d.visiblePlaceholders + " placeholders / " + d.broken + " broken"),
                check("every queued image was emitted", stats.gameDetailArtEmitted === 9,
                    stats.gameDetailArtEmitted),
                check("watchlist toggles on and says so",
                    watchlist.pressed === "true" && watchlist.text.indexOf("In My Lists") !== -1,
                    watchlist.pressed + " / " + watchlist.text),
                check("a screenshot opens the lightbox with the same image",
                    lightbox.open && lightbox.src.indexOf("data:image/svg") === 0
                        && lightbox.caption.indexOf("Screenshot 1") !== -1,
                    lightbox.open + " / " + lightbox.caption.trim()),
                check("the lightbox covers the viewport above the shell",
                    lightbox.covers && lightbox.aboveShell,
                    lightbox.covers + " / " + lightbox.aboveShell),
                check("Escape closes the lightbox without leaving the page",
                    !afterFirstEscape.lightbox && afterFirstEscape.gameVisible,
                    afterFirstEscape.lightbox + " / " + afterFirstEscape.gameVisible),
                check("detail view does not overflow horizontally", d.overflow === 0, d.overflow),
                check("a related card opens that game in the same view",
                    related.title === relatedTitle && related.generation === firstGen + 1,
                    related.title + " / gen " + related.generation),
                check("Escape returns to the intact card grid",
                    back.cards === 6 && back.title === "Trending Games",
                    back.cards + " / " + back.title),
                check("a sparse record keeps its boxart placeholder",
                    sparse.coverPlaceholder === 1 && sparse.broken === 0,
                    sparse.coverPlaceholder + " placeholder / " + sparse.broken + " broken"),
                check("a sparse record drops the sections it cannot fill",
                    sparse.title.indexOf("Supercalifragilistic") === 0
                        && sparse.facts === 2 && sparse.chips === 1
                        && sparse.platforms === 0 && sparse.stills === 1,
                    sparse.facts + " facts / " + sparse.chips + " chips / "
                        + sparse.platforms + " platforms / " + sparse.stills + " stills")
            ];
        }
    },
    {
        name: "sidebar: form buttons, inert items, collapse",
        path: "/movie-page/index.html",
        run: async (page) => {
            const shell = async () => JSON.parse(await page.evaluate(`JSON.stringify({
                forms: document.querySelectorAll("#sidebarNav [data-domain]").length,
                inert: document.querySelectorAll('[data-nav][aria-disabled="true"]').length,
                labels: [...document.querySelectorAll("#sidebarNav .nav-label")]
                    .map(el => el.textContent.trim()).join(" / "),
                active: (document.querySelector("#sidebarNav .nav-item.active") || {dataset: {}}).dataset.domain || "",
                sidebarWidth: document.getElementById("sidebar").getBoundingClientRect().width,
                gridWidth: document.getElementById("movieGrid").getBoundingClientRect().width,
                brandDisplay: getComputedStyle(document.querySelector(".brand")).display,
                expanded: document.getElementById("sidebarToggle").getAttribute("aria-expanded"),
                title: document.getElementById("sectionTitle").textContent
            })`));

            await page.settle();
            const open = await shell();

            // Home is shell chrome: it must not fire a request or steal the
            // active marker from the form that is actually on screen.
            const beforeDummy = await page.stats();
            await page.evaluate(`document.querySelector('#sidebarNav [data-nav="home"]').click(); true`);
            const dummy = await shell();
            const afterDummy = await page.stats();

            await page.evaluate(`document.getElementById("sidebarToggle").click(); true`);
            await page.waitFor(`document.getElementById("sidebar").getBoundingClientRect().width < 100`);
            const collapsed = await shell();

            await page.evaluate(`document.getElementById("sidebarToggle").click(); true`);
            await page.waitFor(`document.getElementById("sidebar").getBoundingClientRect().width > 200`);
            const restored = await shell();

            return [
                check("four form buttons in the sidebar", open.forms === 4, open.forms),
                check("three not-yet-wired items", open.inert === 3, open.inert),
                check("Menu and Library are section labels", open.labels === "Menu / Library", open.labels),
                check("the form on screen is the active button", open.active === "movie", open.active),
                check("a dummy item fires no request", afterDummy.generation === beforeDummy.generation,
                    beforeDummy.generation + " → " + afterDummy.generation),
                check("a dummy item leaves the page alone",
                    dummy.title === open.title && dummy.active === "movie",
                    dummy.title + " / " + dummy.active),
                check("the toggle collapses to the icon rail",
                    collapsed.sidebarWidth < 100 && collapsed.brandDisplay === "none"
                        && collapsed.expanded === "false",
                    collapsed.sidebarWidth + "px, brand " + collapsed.brandDisplay),
                check("collapsing hands the width to the card grid",
                    collapsed.gridWidth > open.gridWidth + 100,
                    open.gridWidth + " → " + collapsed.gridWidth),
                check("expanding restores the labels and the width",
                    restored.sidebarWidth > 200 && restored.brandDisplay !== "none"
                        && restored.expanded === "true",
                    restored.sidebarWidth + "px, brand " + restored.brandDisplay)
            ];
        }
    },
    {
        name: "search hit and miss",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            await page.selectForm("book");

            await page.search("dune");
            const hit = await page.dom();

            await page.search("zzzz");
            const miss = await page.dom();

            return [
                check('search title is "Search Results"', hit.title === "Search Results", hit.title),
                check("dune matches exactly one book", hit.cards === 1 && hit.titles[0] === "Dune",
                    hit.cards + " cards: " + hit.titles.join(", ")),
                check("a miss falls through to the empty state", /No books found\./.test(miss.emptyText), miss.emptyText)
            ];
        }
    },
    {
        name: "AI toggle: opt-in, recommend kind, survives form switch",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();

            const toggleState = () => page.evaluate(
                `document.getElementById("aiToggle").getAttribute("aria-pressed")`);
            const initial = await toggleState();

            // Off by default: the box stays a direct catalog search.
            await page.search("dreams");
            const off = await page.stats();

            await page.evaluate(`document.getElementById("aiToggle").click(); true`);
            const pressed = await toggleState();

            await page.search("dreams");
            const on = await page.stats();
            const d = await page.dom();

            // A form switch reloads trending; it must not silently flip AI off.
            await page.selectForm("book");
            const afterSwitch = await toggleState();

            // Empty box with AI on: trending, never a model call.
            const beforeEmpty = (await page.stats()).generation;
            await page.evaluate(`document.getElementById("searchInput").value = "";
                document.getElementById("searchBtn").click(); true`);
            await page.waitFor(`window.__preview.stats().generation > ${beforeEmpty}`);
            await page.settle();
            const empty = await page.stats();

            return [
                check("AI mode starts off", initial === "false", initial),
                check("AI off sends a direct search",
                    off.kind === "search" && off.query === "dreams",
                    off.kind + " / " + off.query),
                check("the toggle reports its pressed state", pressed === "true", pressed),
                check("AI on sends kind=recommend",
                    on.kind === "recommend" && on.query === "dreams",
                    on.kind + " / " + on.query),
                check('AI results are titled "AI Recommendations"',
                    d.title === "AI Recommendations" && d.cards === 6,
                    d.title + " / " + d.cards + " cards"),
                check("the toggle survives a form switch", afterSwitch === "true", afterSwitch),
                check("an empty AI search falls back to trending",
                    empty.kind === "trending" && empty.query === "",
                    empty.kind + " / " + empty.query)
            ];
        }
    },
    {
        name: "settings: loads the stored config, validates, saves, keeps the key",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            const before = await page.stats();
            const gridBefore = (await page.dom()).cards;

            await page.evaluate(`document.getElementById("settingsNav").click(); true`);
            // The form paints from the backend reply, not from the markup.
            await page.waitFor("window.__preview.stats().settingsLoads > 0");

            const view = async () => JSON.parse(await page.evaluate(`JSON.stringify({
                open: !document.getElementById("settingsView").classList.contains("hidden"),
                browseHidden: document.getElementById("browseView").classList.contains("hidden"),
                navActive: document.getElementById("settingsNav").classList.contains("active"),
                formActive: document.querySelectorAll("#sidebarNav .nav-item.active").length,
                heading: document.getElementById("settingsTitle").textContent,
                baseUrl: document.getElementById("settingsBaseUrl").value,
                model: document.getElementById("settingsModel").value,
                timeout: document.getElementById("settingsTimeout").value,
                titles: document.getElementById("settingsTitles").value,
                keyType: document.getElementById("settingsApiKey").type,
                keyValue: document.getElementById("settingsApiKey").value,
                keyPlaceholder: document.getElementById("settingsApiKey").placeholder,
                revealDisabled: document.getElementById("settingsReveal").disabled,
                path: document.getElementById("settingsPath").textContent,
                preset: [...document.querySelectorAll(".provider-chip[data-provider]")]
                    .filter(b => b.getAttribute("aria-pressed") === "true")
                    .map(b => b.dataset.provider).join(","),
                status: document.getElementById("settingsStatus").textContent,
                statusError: document.getElementById("settingsStatus").classList.contains("is-error")
            })`));

            const open = await view();

            // A preset fills the base URL and moves the pressed marker.
            await page.evaluate(`document.querySelector('.provider-chip[data-provider="openai"]').click(); true`);
            const preset = await view();

            // Saving without a key (none stored, none typed) is refused by the
            // backend, and nothing is written.
            await page.evaluate(`document.getElementById("settingsSave").click(); true`);
            await page.waitFor(`document.getElementById("settingsStatus").classList.contains("is-error")`);
            const refused = await view();
            const afterRefusal = await page.stats();

            // Typing a key enables Reveal; the key is only ever sent on Save.
            await page.evaluate(`
                const key = document.getElementById("settingsApiKey");
                key.value = "sk-test-key";
                key.dispatchEvent(new Event("input"));
                true`);
            const typed = await view();

            // Save succeeds, the key leaves the field, and the page says the
            // stored key is now on the engine's side of the bridge.
            await page.evaluate(`document.getElementById("settingsSave").click(); true`);
            await page.waitFor("window.__preview.stats().settingsSaves === 1");
            const saved = await view();
            const afterSave = await page.stats();

            // Leaving and reopening loads the stored state, still without the key.
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            const closed = await view();
            await page.evaluate(`document.getElementById("settingsNav").click(); true`);
            await page.waitFor("window.__preview.stats().settingsLoads > 1");
            const reopened = await view();

            // A blank key on a second save keeps the secret the page cannot read.
            await page.evaluate(`
                document.getElementById("settingsModel").value = "model-b";
                document.getElementById("settingsSave").click();
                true`);
            await page.waitFor("window.__preview.stats().settingsSaves === 2");
            const kept = await page.stats();

            // Reveal is a field toggle only: it never fills or reads the key.
            await page.evaluate(`document.getElementById("settingsReveal").click(); true`);
            const revealed = await view();
            await page.evaluate(`document.getElementById("settingsReveal").click(); true`);
            const remasked = await view();

            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            const d = await page.dom();

            return [
                check("Settings opens from the sidebar",
                    open.open && open.browseHidden && open.navActive === true
                        && open.formActive === 0,
                    open.open + " / nav " + open.navActive + " / forms " + open.formActive),
                check("the form paints the loaded config, not markup defaults",
                    open.heading === "Settings"
                        && open.baseUrl === "https://openrouter.ai/api/v1"
                        && open.model === "baidu/ernie-5.5-flash-preview:free"
                        && open.timeout === "60" && open.titles === "10"
                        && open.path === "~/.van-gogh/llm.conf",
                    open.baseUrl + " / " + open.model + " / " + open.timeout + " / "
                        + open.titles + " / " + open.path),
                check("no stored key: empty field, nothing to reveal",
                    open.keyValue === "" && open.keyType === "password"
                        && open.revealDisabled === true
                        && open.keyPlaceholder === "sk-…",
                    open.keyType + " / " + (open.keyValue === "" ? "empty" : "filled")
                        + " / reveal " + open.revealDisabled),
                check("a provider preset fills the base URL",
                    preset.baseUrl === "https://api.openai.com/v1" && preset.preset === "openai",
                    preset.baseUrl + " / " + preset.preset),
                check("saving with no key anywhere is an error, not a write",
                    refused.statusError && /apiKey/.test(refused.status)
                        && afterRefusal.settingsSaves === 0,
                    refused.status + " / saves " + afterRefusal.settingsSaves),
                check("typing a key enables Reveal",
                    typed.revealDisabled === false && typed.keyValue === "sk-test-key",
                    "reveal " + typed.revealDisabled),
                check("Save writes the file and reports the stored values",
                    !saved.statusError && /Saved/.test(saved.status)
                        && afterSave.settingsSaves === 1 && afterSave.settingsHasKey === true,
                    saved.status + " / saves " + afterSave.settingsSaves),
                check("the key leaves the field and the stored-key hint appears",
                    saved.keyValue === ""
                        && /Key saved/.test(saved.keyPlaceholder)
                        && saved.revealDisabled === false,
                    saved.keyValue + " / " + saved.keyPlaceholder),
                check("reopening loads the saved config, still without the key",
                    reopened.baseUrl === "https://api.openai.com/v1"
                        && reopened.keyValue === "" && reopened.keyType === "password"
                        && /Key saved/.test(reopened.keyPlaceholder),
                    reopened.baseUrl + " / " + (reopened.keyValue === "" ? "empty" : "filled")),
                check("a blank key on a later save keeps the stored one",
                    kept.settingsSaves === 2 && kept.settingsHasKey === true,
                    "saves " + kept.settingsSaves + " / key " + kept.settingsHasKey),
                check("Reveal toggles the field, never the key",
                    revealed.keyType === "text" && remasked.keyType === "password"
                        && revealed.keyValue === "" && remasked.keyValue === "",
                    revealed.keyType + " → " + remasked.keyType),
                check("opening and saving fire no browse request",
                    afterSave.generation === before.generation,
                    before.generation + " → " + afterSave.generation),
                check("Escape returns to the intact grid",
                    !closed.open && closed.navActive === false && closed.formActive === 1
                        && d.cards === gridBefore && d.title === "Trending Movies",
                    closed.open + " / nav " + closed.navActive + " / " + d.cards + " cards, " + d.title)
            ];
        }
    },
    {
        name: "profile: identity, universe, collections, edit round-trip",
        path: "/movie-page/index.html",
        run: async (page) => {
            const probe = async () => JSON.parse(await page.evaluate(`JSON.stringify((() => {
                const visible = !document.getElementById("profileView").classList.contains("hidden");
                const grid = document.getElementById("profileCollections");
                const stats = document.querySelector(".profile-stats");
                return {
                    visible,
                    headerActive: document.getElementById("profileBtn").classList.contains("active"),
                    formsActive: document.querySelectorAll("#sidebarNav .nav-item.active").length,
                    name: document.getElementById("profileName").textContent,
                    member: document.querySelector(".profile-member").textContent,
                    stats: [...document.querySelectorAll(".profile-stat-value")]
                        .map(e => e.textContent).join(" / "),
                    statLabels: [...document.querySelectorAll(".profile-stat-label")]
                        .map(e => e.textContent).join(" / "),
                    collectionTitles: [...document.querySelectorAll(".profile-collection-title")]
                        .map(e => e.textContent).join(" / "),
                    collectionCounts: [...document.querySelectorAll(".profile-collection-count")]
                        .map(e => e.textContent).join(" / "),
                    collectionColumns: grid
                        ? getComputedStyle(grid).gridTemplateColumns.trim().split(/\\s+/).length : 0,
                    statColumns: stats
                        ? getComputedStyle(stats).gridTemplateColumns.trim().split(/\\s+/).length : 0,
                    links: document.querySelectorAll(".profile-collection-link").length,
                    disabledLinks: document.querySelectorAll(".profile-collection-link:disabled").length,
                    overflow: Math.max(0,
                        document.documentElement.scrollWidth - document.documentElement.clientWidth),
                    scrollY: window.scrollY,
                    editing: document.getElementById("profileEditPanel") !== null,
                    editExpanded: document.getElementById("editProfile").getAttribute("aria-expanded"),
                    browseVisible: !document.getElementById("browseView").classList.contains("hidden")
                };
            })())`));

            await page.settle();
            const gridBefore = (await page.dom()).cards;

            // Open from the header's avatar, from a scrolled grid: the page must
            // start at its own top and the form buttons must go quiet.
            await page.evaluate(`window.scrollTo(0, 220); true`);
            await page.evaluate(`document.getElementById("profileBtn").click(); true`);
            await page.waitFor(`!document.getElementById("profileView").classList.contains("hidden")`);
            await new Promise((resolve) => setTimeout(resolve, 60));
            const open = await probe();

            // Edit: prefilled, focused, then saved into the page-local store.
            await page.evaluate(`document.getElementById("editProfile").click(); true`);
            await page.waitFor(`document.getElementById("profileEditPanel") !== null`);
            const editValues = JSON.parse(await page.evaluate(`JSON.stringify({
                name: document.getElementById("profileNameInput").value,
                member: document.getElementById("profileMemberInput").value,
                focused: document.activeElement === document.getElementById("profileNameInput")
            })`));

            await page.evaluate(`
                document.getElementById("profileNameInput").value = "Vincent";
                document.getElementById("profileMemberInput").value = "April, 2024";
                document.getElementById("profileSave").click(); true
            `);
            await page.waitFor(`document.getElementById("profileEditPanel") === null`);
            const saved = await probe();

            // Escape is two-stage: close the editor, keep the page; then leave.
            await page.evaluate(`document.getElementById("editProfile").click(); true`);
            await page.waitFor(`document.getElementById("profileEditPanel") !== null`);
            await page.evaluate(
                `document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); true`);
            await page.waitFor(`document.getElementById("profileEditPanel") === null
                && !document.getElementById("profileView").classList.contains("hidden")`);
            const afterFirstEscape = await probe();

            await page.evaluate(
                `document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); true`);
            await page.waitFor(`document.getElementById("profileView").classList.contains("hidden")`);
            const closed = await probe();
            const gridAfter = await page.dom();

            // Back closes to the covered view too; reopening keeps the edited
            // identity (it lives in the store, not the backend).
            await page.evaluate(`document.getElementById("profileBtn").click(); true`);
            await page.waitFor(`!document.getElementById("profileView").classList.contains("hidden")`);
            const reopened = await probe();
            await page.evaluate(`document.getElementById("profileBack").click(); true`);
            await page.waitFor(`document.getElementById("profileView").classList.contains("hidden")`);
            const backed = await probe();

            // The ladder: two collection tiles per row below 1180, one below
            // 640; stats stack below 900. Nothing may overflow sideways.
            await page.evaluate(`document.getElementById("profileBtn").click(); true`);
            await page.waitFor(`!document.getElementById("profileView").classList.contains("hidden")`);
            await page.resize(1000, 900);
            await new Promise((resolve) => setTimeout(resolve, 120));
            const mid = await probe();
            await page.resize(600, 900);
            await new Promise((resolve) => setTimeout(resolve, 120));
            const narrow = await probe();
            await page.resetViewport();

            return [
                check("the header avatar opens the profile",
                    open.visible && open.headerActive && open.browseVisible === false,
                    open.visible + " / active " + open.headerActive),
                check("no form button is active behind the overlay",
                    open.formsActive === 0, open.formsActive),
                check("entering starts at the top of the page",
                    open.scrollY === 0, open.scrollY + "px"),
                check("identity matches the sketch",
                    open.name === "John" && open.member === "Member since March, 2024",
                    open.name + " / " + open.member),
                check("three universe numbers, in the sketch's order",
                    open.stats === "230 / 12 / 1,034"
                        && open.statLabels === "Favorite / Total Playlist / Entertainment Action",
                    open.stats + " / " + open.statLabels),
                check("four collection tiles with their counts",
                    open.collectionTitles
                        === "Movies Collection / Music Collection / TV Shows Collection / Books Collection"
                        && open.collectionCounts === "112 items / 45 items / 50 items / 94 items",
                    open.collectionTitles + " / " + open.collectionCounts),
                check('"View collection" is present but inert until the lists land',
                    open.links === 4 && open.disabledLinks === 4,
                    open.links + " links / " + open.disabledLinks + " disabled"),
                check("Edit Profile is prefilled, focused, and expanded",
                    editValues.name === "John" && editValues.member === "March, 2024"
                        && editValues.focused && saved.editExpanded === "false",
                    JSON.stringify(editValues)),
                check("Save updates the identity without leaving the page",
                    saved.name === "Vincent" && saved.member === "Member since April, 2024"
                        && !saved.editing && saved.visible,
                    saved.name + " / " + saved.member),
                check("Escape closes the editor but stays on the profile",
                    !afterFirstEscape.editing && afterFirstEscape.visible,
                    afterFirstEscape.editing + " / " + afterFirstEscape.visible),
                check("a second Escape returns to the intact grid",
                    !closed.visible && closed.browseVisible && closed.formsActive === 1
                        && gridAfter.cards === gridBefore && gridAfter.title === "Trending Movies",
                    closed.visible + " / " + gridAfter.cards + " cards, " + gridAfter.title),
                check("Back closes the page and keeps the edited identity",
                    !backed.visible && reopened.name === "Vincent",
                    backed.visible + " / " + reopened.name),
                check("collections step 4 -> 2 -> 1 columns; stats stack below 900",
                    open.collectionColumns === 4 && mid.collectionColumns === 2
                        && narrow.collectionColumns === 1 && mid.statColumns === 3
                        && narrow.statColumns === 1,
                    open.collectionColumns + " / " + mid.collectionColumns + " / "
                        + narrow.collectionColumns + " cols"),
                check("the profile never overflows horizontally",
                    open.overflow === 0 && mid.overflow === 0 && narrow.overflow === 0,
                    open.overflow + " / " + mid.overflow + " / " + narrow.overflow)
            ];
        }
    },
    {
        name: "scenario: empty",
        path: "/movie-page/index.html?scenario=empty",
        run: async (page) => {
            await page.settle();
            const d = await page.dom();
            return [
                check("no cards", d.cards === 0, d.cards),
                check("empty state explains why", /No movies found\./.test(d.emptyText), d.emptyText)
            ];
        }
    },
    {
        name: "scenario: error",
        path: "/movie-page/index.html?scenario=error",
        run: async (page) => {
            const stats = await page.settle();
            const d = await page.dom();
            return [
                check("drain reports an error", stats.status === "error", stats.status),
                check("error text reaches the page", /Simulated fetch failure/.test(d.emptyText), d.emptyText)
            ];
        }
    },
    {
        name: "scenario: no posters",
        path: "/movie-page/index.html?scenario=noposters",
        run: async (page) => {
            await page.settle();
            const d = await page.dom();
            return [
                check("6 cards still render", d.cards === 6, d.cards),
                check("every card keeps its placeholder", d.placeholders === 6 && d.withArt === 0,
                    d.withArt + " art, " + d.placeholders + " placeholders")
            ].concat(artworkInvariants(d));
        }
    },
    {
        name: "scenario: slow shows skeletons",
        path: "/movie-page/index.html?scenario=slow",
        run: async (page) => {
            await page.waitFor('document.querySelectorAll("#movieGrid .skeleton-card").length === 8', 8000);
            const during = await page.dom();

            await page.settle(30000);
            const after = await page.dom();

            return [
                check("8 skeletons while the fetch is pending", during.skeletons === 8, during.skeletons),
                check("no real cards before the reply", during.cards === 0, during.cards),
                check("skeletons are replaced by 6 cards", after.cards === 6 && after.skeletons === 0,
                    after.cards + " cards, " + after.skeletons + " skeletons")
            ];
        }
    },
    {
        name: "rapid form switching settles on the last form",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            const start = (await page.stats()).generation;

            // Three kicks with no wait between them: only the last may paint.
            await page.evaluate(`["game", "music", "book"].forEach(d =>
                document.querySelector('#sidebarNav [data-domain="' + d + '"]').click()); true`);

            await page.settle(30000);
            const d = await page.dom();
            const stats = await page.stats();

            return [
                check("every switch opened a generation", stats.generation === start + 3,
                    start + " → " + stats.generation),
                check("the last form wins", d.title === "Trending Books" && d.cardClass.indexOf("book-card") !== -1,
                    d.title + " / " + d.cardClass),
                check("no stale cards from the superseded forms",
                    d.cards === 6 && !d.cardClass.match(/game-card|music-card/), d.cardClass)
            ].concat(artworkInvariants(d));
        }
    },
    {
        name: "theme: the picker applies, saves and survives reopening",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            await page.evaluate(`document.getElementById("settingsNav").click(); true`);
            await page.waitFor("window.__preview.stats().themeLoads > 0");

            // Everything the Appearance card promises, read back from the page.
            const view = async () => JSON.parse(await page.evaluate(`JSON.stringify({
                open: !document.getElementById("settingsView").classList.contains("hidden"),
                chips: [...document.querySelectorAll("#themeChips .theme-chip")]
                    .map(c => ({ id: c.dataset.themeId, label: c.textContent.trim(),
                                 pressed: c.getAttribute("aria-pressed") })),
                theme: document.documentElement.dataset.theme,
                skin: document.getElementById("themeSkin").getAttribute("href"),
                status: document.getElementById("themeStatus").textContent,
                statusError: document.getElementById("themeStatus").classList.contains("is-error"),
                header: getComputedStyle(document.querySelector(".app-header")).backgroundColor,
                cardBorder: getComputedStyle(document.querySelector("#movieGrid .movie-card")).borderColor,
                toggleColor: getComputedStyle(document.getElementById("aiToggle")).color,
                sky: getComputedStyle(document.querySelector(".sky")).backgroundImage,
                overlap: (() => {
                    const a = document.getElementById("aiToggle").getBoundingClientRect();
                    const b = document.getElementById("searchBtn").getBoundingClientRect();
                    return a.right > b.left + 1;   // 1px rounding guard
                })()
            })`));

            const starry = await view();

            await page.evaluate(`document.querySelector('#themeChips .theme-chip[data-theme-id="liquidneon"]').click(); true`);
            await page.waitFor("window.__preview.stats().themeSaves === 1");
            // Swapping the theme <link> is instant, but the browser fetches the
            // new sheet asynchronously — read the painted colours only once it
            // has landed, or every comparison sees the old skin.
            const skinLoaded = await page.evaluate(`new Promise((resolve) => {
                const hrefs = () => [...document.styleSheets].map((s) => String(s.href || ""));
                const active = () => hrefs().some((h) => h.indexOf("theme-liquidneon.css") !== -1)
                    && !hrefs().some((h) => h.indexOf("theme-starry.css") !== -1);
                if (active()) return resolve(true);
                const tick = setInterval(() => { if (active()) { clearInterval(tick); resolve(true); } }, 25);
                setTimeout(() => { clearInterval(tick); resolve(false); }, 3000);
            })`);
            // The controls transition their colours (0.2s), and a transition only
            // starts on the first style recalc — which is the read itself. Force
            // that recalc, then let the fade finish before comparing skins.
            await page.evaluate(`getComputedStyle(document.getElementById("aiToggle")).color`);
            await page.evaluate(`new Promise((resolve) => setTimeout(resolve, 350))`);
            const neon = await view();
            const saved = await page.stats();

            // Leaving and reopening must paint the stored choice as pressed.
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);
            await page.evaluate(`document.getElementById("settingsNav").click(); true`);
            const reopened = await view();
            await page.evaluate(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); true`);

            return [
                check("the Appearance card lists one chip per known theme",
                    starry.open && starry.chips.length === 3
                        && starry.chips[0].id === "starry"
                        && starry.chips[1].id === "liquidneon"
                        && starry.chips[2].id === "darkplatinum"
                        && starry.chips[0].label === "Starry Night"
                        && starry.chips[1].label === "Liquid Neon"
                        && starry.chips[2].label === "Dark Platinum",
                    JSON.stringify(starry.chips)),
                check("Starry Night is the default and is pressed",
                    starry.theme === "starry" && starry.skin === "theme-starry.css"
                        && starry.chips[0].pressed === "true"
                        && starry.chips[1].pressed === "false",
                    starry.theme + " / " + starry.skin),
                check("picking Liquid Neon repaints the whole shell",
                    skinLoaded && neon.theme === "liquidneon" && neon.skin === "theme-liquidneon.css"
                        && neon.header !== starry.header
                        && neon.cardBorder !== starry.cardBorder
                        && neon.sky.indexOf("liquidneon.png") !== -1,
                    [neon.theme, neon.skin, "loaded " + skinLoaded, neon.header,
                        neon.cardBorder, neon.sky.slice(-46)].join(" / ")),
                check("the AI toggle is styled by whichever theme is active",
                    starry.toggleColor !== neon.toggleColor
                        && neon.toggleColor === "rgb(0, 240, 255)",
                    starry.toggleColor + " → " + neon.toggleColor),
                check("the AI toggle never sits under the Search button",
                    !starry.overlap && !neon.overlap,
                    "starry overlap " + starry.overlap + " / neon overlap " + neon.overlap),
                check("the pick is saved at once, exactly once",
                    saved.themeSaves === 1 && saved.theme === "liquidneon"
                        && !neon.statusError && /saved/i.test(neon.status),
                    "saves " + saved.themeSaves + " / " + neon.status),
                check("reopening shows the saved theme pressed",
                    reopened.chips[1].pressed === "true"
                        && reopened.chips[0].pressed === "false"
                        && reopened.theme === "liquidneon",
                    JSON.stringify(reopened.chips.map(c => c.pressed)))
            ];
        }
    },
    {
        name: "theme: a stored theme is painted at launch",
        path: "/movie-page/index.html?theme=liquidneon",
        run: async (page) => {
            await page.settle();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                theme: document.documentElement.dataset.theme,
                skin: document.getElementById("themeSkin").getAttribute("href"),
                sky: getComputedStyle(document.querySelector(".sky")).backgroundImage,
                cardBorder: getComputedStyle(document.querySelector("#movieGrid .movie-card")).borderColor,
                toggle: getComputedStyle(document.getElementById("aiToggle")).color,
                aiToggleStyled: getComputedStyle(document.getElementById("aiToggle")).borderTopWidth,
                cardTween: getComputedStyle(document.querySelector("#movieGrid .movie-card")).transitionProperty,
                artTween: getComputedStyle(document.querySelector("#movieGrid .movie-card .card-poster img")).transitionProperty,
                navTween: getComputedStyle(document.querySelector(".nav-item svg")).transitionProperty
            })`));

            return [
                check("the saved skin is the one loaded",
                    d.theme === "liquidneon" && d.skin === "theme-liquidneon.css",
                    d.theme + " / " + d.skin),
                check("the backdrop is the Liquid Neon image",
                    d.sky.indexOf("liquidneon.png") !== -1, d.sky),
                check("the neon tokens reached the cards and the AI toggle",
                    d.cardBorder === "rgba(0, 240, 255, 0.2)"
                        && d.toggle === "rgb(0, 240, 255)"
                        && d.aiToggleStyled === "1px",
                    d.cardBorder + " / " + d.toggle + " / border " + d.aiToggleStyled),
                check("the card hover eases lift, edge and tint",
                    d.cardTween === "transform, border-color, background-color", d.cardTween),
                check("the poster scale and grade both tween",
                    d.artTween === "transform, filter", d.artTween),
                check("the nav icon's cyan turns on the tween too",
                    d.navTween === "opacity, color", d.navTween)
            ];
        }
    },
    {
        name: "theme: Dark Platinum paints its own backdrop and tokens",
        path: "/movie-page/index.html?theme=darkplatinum",
        run: async (page) => {
            await page.settle();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                theme: document.documentElement.dataset.theme,
                skin: document.getElementById("themeSkin").getAttribute("href"),
                sky: getComputedStyle(document.querySelector(".sky")).backgroundImage,
                cardBorder: getComputedStyle(document.querySelector("#movieGrid .movie-card")).borderColor,
                toggle: getComputedStyle(document.getElementById("aiToggle")).color,
                cardTween: getComputedStyle(document.querySelector("#movieGrid .movie-card")).transitionProperty,
                artTween: getComputedStyle(document.querySelector("#movieGrid .movie-card .card-poster img")).transitionProperty,
                navTween: getComputedStyle(document.querySelector(".nav-item svg")).transitionProperty
            })`));

            const rgba = (s) => (s.match(/[\d.]+/g) || []).map(Number);
            const border = rgba(d.cardBorder);
            const toggle = rgba(d.toggle);

            return [
                check("the saved skin is the one loaded",
                    d.theme === "darkplatinum" && d.skin === "theme-darkplatinum.css",
                    d.theme + " / " + d.skin),
                check("the backdrop is the Dark Platinum image",
                    d.sky.indexOf("darkplatinum.jpeg") !== -1, d.sky),
                check("the platinum tokens reached the cards and the AI toggle",
                    // A visible silver hairline (cool, light, not washed out)…
                    border[0] > 180 && border[1] > 190 && border[2] > 200
                        && border[3] > 0.18
                        // …and a quiet, cool grey for the resting AI label.
                        && toggle[0] > 140 && toggle[0] < 200
                        && Math.abs(toggle[0] - toggle[2]) < 40,
                    d.cardBorder + " / " + d.toggle),
                check("the card hover eases lift, edge, tint and glow",
                    d.cardTween === "transform, border-color, background-color, box-shadow",
                    d.cardTween),
                check("the poster scale and grade both tween",
                    d.artTween === "transform, filter", d.artTween),
                check("the nav icon eases opacity, colour and glow",
                    d.navTween === "opacity, color, filter", d.navTween)
            ];
        }
    },
    {
        name: "theme: an unknown stored id falls back to Starry Night",
        path: "/movie-page/index.html?theme=vaporwave",
        run: async (page) => {
            await page.settle();
            const d = JSON.parse(await page.evaluate(`JSON.stringify({
                theme: document.documentElement.dataset.theme,
                skin: document.getElementById("themeSkin").getAttribute("href")
            })`));

            return [
                check("an unknown id reads as the default skin",
                    d.theme === "starry" && d.skin === "theme-starry.css",
                    d.theme + " / " + d.skin)
            ];
        }
    },
    {
        name: "responsive: one grid ladder serves every width",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();

            // [viewport width, expected columns] — base.css owns the ladder.
            const LADDER = [[1280, 5], [1180, 4], [1000, 4], [900, 3], [800, 3], [768, 2], [600, 2]];
            const rows = [];
            for (const [width, columns] of LADDER) {
                const m = await measureGrid(page, width);
                rows.push({ expected: columns, ...m });
            }

            // Every card shape must survive both ends of the ladder, not only
            // movies: the book meta ("Author · N pages") is a prose phrase and
            // the music subtitle is nowrap, so they stress the tightest column
            // count differently.
            const forms = [];
            for (const form of ["game", "music", "book"]) {
                await page.selectForm(form);
                const wide = await measureGrid(page, 1280);
                const narrow = await measureGrid(page, 600);
                forms.push({ form: form, wide: wide, narrow: narrow });
            }
            await page.resetViewport();

            const squeezed = rows.filter((r) => r.cardWidth < 150).map((r) => r.vw + "→" + r.cardWidth);
            const escaping = rows.filter((r) => r.escaping > 0 || r.gridOverflow > 0 || r.pageOverflow > 0);

            const clean = (m) => m.escaping === 0 && m.gridOverflow === 0 && m.pageOverflow === 0;

            return rows.map((r) => check(
                r.vw + "px shows " + r.expected + " columns",
                r.columns === r.expected,
                r.columns + " columns / card " + r.cardWidth + "px"))
                .concat([
                    check("every step keeps a readable card", squeezed.length === 0,
                        squeezed.length ? squeezed.join(", ") : rows.map((r) => r.cardWidth).join(", ")),
                    check("no card text escapes at any width", escaping.length === 0,
                        escaping.length
                            ? escaping.map((r) => r.vw + "px escape=" + r.escaping
                                + " grid=" + r.gridOverflow + " page=" + r.pageOverflow).join("; ")
                            : rows.map((r) => r.cardWidth).join(", "))
                ])
                .concat(forms.map((f) => check(
                    "the " + f.form + " cards fit five columns and two",
                    f.wide.columns === 5 && f.narrow.columns === 2 && clean(f.wide) && clean(f.narrow),
                    f.wide.columns + " cols/" + f.wide.escaping + " escape → "
                        + f.narrow.columns + " cols/" + f.narrow.escaping + " escape")));
        }
    },
    {
        name: "responsive: the neon skin reflows exactly like Starry Night",
        path: "/movie-page/index.html?theme=liquidneon",
        run: async (page) => {
            await page.settle();
            const widths = [1280, 1180, 1000, 900, 800, 768, 600];
            const columns = [];
            const widths_ = [];
            for (const width of widths) {
                const m = await measureGrid(page, width);
                columns.push(m.columns);
                widths_.push(m.cardWidth);
            }
            await page.resetViewport();

            return [
                check("the shared ladder is the only ladder",
                    columns.join(" ") === "5 4 4 3 3 2 2",
                    columns.join(" ")),
                check("neon cards are never squeezed below the default skin's floor",
                    Math.min(...widths_) >= 150, widths_.join(", "))
            ];
        }
    }
];

// -------------------------------------------------------------------- runner

async function run() {
    const browserInfo = findBrowser();
    const server = await startPreviewServer({ port: 0, pageDir: PAGE_DIR_ARG });
    let browser = null;

    const results = [];
    let failures = 0;

    console.log("VanGogh UI checks");
    console.log("  browser: " + path.basename(browserInfo.binary));
    console.log("  server:  " + server.url);
    console.log("");

    try {
        browser = await launchBrowser(browserInfo);

        for (const test of TESTS) {
            const page = await Page.open(browser, server.url + test.path);
            process.stdout.write(test.name + "\n");

            let assertions = [];
            try {
                assertions = await test.run(page);
            } catch (err) {
                assertions = [check("test crashed: " + err.message, false)];
            }

            if (VERBOSE) {
                const stats = await page.stats().catch(() => null);
                if (stats) {
                    console.log("    · " + JSON.stringify(stats));
                }
            }

            // Page-level console errors count once per test, not per assertion.
            if (page.errors.length > 0) {
                assertions.push(check("no console errors", false, page.errors.slice(0, 3).join(" | ")));
            }

            assertions.forEach((assertion) => {
                console.log("    " + (assertion.ok ? "✓" : "✗") + " " + assertion.label +
                    (assertion.ok || assertion.detail === "" ? "" : "  → " + assertion.detail));
                results.push(assertion);
                if (!assertion.ok) {
                    failures++;
                }
            });

            await page.close();
            console.log("");
        }
    } finally {
        await stopBrowser(browser);
        await server.close();
    }

    const passed = results.length - failures;
    if (failures === 0) {
        console.log(passed + " checks passed.");
    } else {
        console.log(failures + " of " + results.length + " checks FAILED (" + passed + " passed).");
    }

    return failures === 0 ? 0 : 1;
}

run().then((code) => {
    process.exit(code);
}).catch((err) => {
    console.error("check run failed: " + err.message);
    process.exit(1);
});
