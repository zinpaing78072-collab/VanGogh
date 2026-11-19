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

    async settle(timeoutMs) {
        await this.waitFor(SETTLE, timeoutMs);
        return this.stats();
    }

    async selectForm(value) {
        const before = (await this.stats()).generation;
        await this.evaluate(`document.querySelector('#sidebarNav [data-domain="${value}"]').click(); true`);
        await this.waitFor(`window.__preview.stats().generation > ${before}`);
        return this.settle();
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
                preset: [...document.querySelectorAll(".provider-chip")]
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
    }
];

// -------------------------------------------------------------------- runner

async function run() {
    const browserInfo = findBrowser();
    const server = await startPreviewServer({ port: 0 });
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
