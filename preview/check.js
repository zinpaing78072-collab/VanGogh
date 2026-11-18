#!/usr/bin/env node
/* Headless UI checks — one command, pass or fail, no eyeballs.
 *
 * Boots the preview server on an ephemeral port, drives a headless Chromium
 * over the DevTools protocol, and asserts the invariants that were previously
 * verified by hand: card counts, per-form card shapes, the artwork stream
 * settling, placeholder fallback, overflow, the empty/error states, and the
 * supersede guard when the filter is switched quickly.
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

    async setFilter(value) {
        const before = (await this.stats()).generation;
        await this.evaluate(`document.getElementById("contentFilter").click();
            document.querySelector('.filter-option[data-value="${value}"]').click(); true`);
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
        name: "filter switches all four forms",
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
                await page.setFilter(form.value);
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
            await page.setFilter("music");
            const music = await page.dom();
            results.push(check("music shows an artist subtitle", music.subtitle.trim().length > 0, music.subtitle));

            await page.setFilter("book");
            const book = await page.dom();
            results.push(check("book meta carries the author", /Tolkien|Herbert|Fitzgerald/.test(book.meta.join(" ")),
                book.meta[0]));

            return results;
        }
    },
    {
        name: "search hit and miss",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            await page.setFilter("book");

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
        name: "rapid filter switching settles on the last form",
        path: "/movie-page/index.html",
        run: async (page) => {
            await page.settle();
            const start = (await page.stats()).generation;

            // Three kicks with no wait between them: only the last may paint.
            await page.evaluate(`document.getElementById("contentFilter").click();
                document.querySelector('.filter-option[data-value="game"]').click();
                document.getElementById("contentFilter").click();
                document.querySelector('.filter-option[data-value="music"]').click();
                document.getElementById("contentFilter").click();
                document.querySelector('.filter-option[data-value="book"]').click(); true`);

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
