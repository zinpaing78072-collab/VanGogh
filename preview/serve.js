#!/usr/bin/env node
/* Preview server — serves the REAL page from src/main/resources/movie-page
 * over loopback HTTP, with the mock bridge injected.
 *
 * Why a server instead of just opening the file: index.html loads ES modules,
 * and Chromium refuses module scripts from file:// (the same constraint that
 * made both shells serve the page over loopback HTTP in the first place).
 * Serving the resources dir means index.html, style.css, js/*.js and posters/
 * all load exactly as the packaged app loads them — the preview is the shipped
 * page, not a copy that can drift.
 *
 * The only transformed response is index.html, which gains two classic
 * scripts before </head>. Classic scripts run before the deferred module
 * (js/app.js), so the mock bridge is always installed first.
 *
 *   node preview/serve.js            → http://127.0.0.1:8788
 *   PORT=9000 node preview/serve.js
 *
 * Also exported as startPreviewServer({ port }) so preview/check.js can boot a
 * server on an ephemeral port instead of depending on one being up already.
 *
 * No dependencies, no build step, nothing written to disk.
 */
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const url = require("url");

const ROOT = path.resolve(__dirname, "..");
const PAGE_DIR = path.join(ROOT, "src", "main", "resources", "movie-page");
const PREVIEW_DIR = path.join(ROOT, "preview");
const HOST = "127.0.0.1";

const MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2"
};

// Two classic scripts, injected before </head> so they precede the deferred
// module entry point. mock-krema.js must follow fixtures.js (it reads them).
const INJECT = [
    '<script src="/preview/fixtures.js"></script>',
    '<script src="/preview/mock-krema.js"></script>'
].join("\n    ");

function send(res, status, body, type, method) {
    res.writeHead(status, {
        "Content-Type": type || "text/plain; charset=utf-8",
        "Cache-Control": "no-store"
    });
    res.end(method === "HEAD" ? undefined : body);
}

function insideDir(base, target) {
    const root = path.resolve(base) + path.sep;
    const resolved = path.resolve(target);
    return resolved.startsWith(root) ? resolved : null;
}

function serveFile(res, filePath, method) {
    const type = MIME[path.extname(filePath).toLowerCase()] || "application/octet-stream";

    fs.readFile(filePath, (err, buf) => {
        if (err) {
            send(res, 404, "Not found: " + path.relative(ROOT, filePath), null, method);
            return;
        }

        // The one transformed response: inject the mock bridge into the page.
        if (path.basename(filePath) === "index.html" && filePath.startsWith(PAGE_DIR)) {
            let html = buf.toString("utf8");
            if (html.indexOf("/preview/mock-krema.js") === -1) {
                html = html.replace("</head>", "    " + INJECT + "\n</head>");
            }
            send(res, 200, html, type, method);
            return;
        }

        send(res, 200, buf, type, method);
    });
}

function handler(req, res) {
    const parsed = url.parse(req.url);
    const pathname = decodeURIComponent(parsed.pathname || "/");
    const method = req.method || "GET";

    // Favicon noise says nothing about the page; skip it quietly.
    if (pathname === "/favicon.ico") {
        send(res, 204, "", null, method);
        return;
    }

    // Root lands on the app itself, keeping ?scenario= intact.
    if (pathname === "/") {
        res.writeHead(302, { Location: "/movie-page/index.html" + (parsed.search || "") });
        res.end();
        return;
    }

    let base = null;
    let rest = "";

    if (pathname.startsWith("/movie-page/")) {
        base = PAGE_DIR;
        rest = pathname.slice("/movie-page/".length);
    } else if (pathname.startsWith("/preview/")) {
        base = PREVIEW_DIR;
        rest = pathname.slice("/preview/".length);
    } else {
        send(res, 404, "Not found. Try /movie-page/index.html", null, method);
        return;
    }

    const target = insideDir(base, path.join(base, rest));
    if (!target) {
        send(res, 403, "Forbidden", null, method);
        return;
    }

    fs.stat(target, (err, stat) => {
        if (err) {
            send(res, 404, "Not found: " + pathname, null, method);
            return;
        }
        if (stat.isDirectory()) {
            serveFile(res, path.join(target, "index.html"), method);
            return;
        }
        serveFile(res, target, method);
    });
}

// Starts the server and resolves once it is listening. port 0 asks the OS for
// a free port, which is what preview/check.js uses.
function startPreviewServer(options) {
    const opts = options || {};
    const port = opts.port !== undefined ? opts.port : Number(process.env.PORT || 8788);
    const server = http.createServer(handler);

    return new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, HOST, () => {
            const actual = server.address().port;
            resolve({
                server: server,
                port: actual,
                url: "http://" + HOST + ":" + actual,
                close: () => new Promise((done) => server.close(() => done()))
            });
        });
    });
}

module.exports = { startPreviewServer: startPreviewServer, PAGE_DIR: PAGE_DIR };

if (require.main === module) {
    startPreviewServer().then((app) => {
        console.log("VanGogh preview (mock IPC, real page)");
        console.log("  " + app.url + "/movie-page/index.html");
        console.log("");
        console.log("States to check:");
        console.log("  " + app.url + "/?scenario=slow        skeletons, ~4s");
        console.log("  " + app.url + "/?scenario=empty       empty state");
        console.log("  " + app.url + "/?scenario=error       error state");
        console.log("  " + app.url + "/?scenario=noposters   placeholder artwork");
        console.log("");
        console.log("Sidebar (Music/Movie/Books/Games) and search work: each form");
        console.log("serves its own fixtures. Ctrl-C to stop.");
        console.log("Run `node preview/check.js` for the automated version.");
    }).catch((err) => {
        console.error("preview server failed:", err.message);
        process.exit(1);
    });
}
