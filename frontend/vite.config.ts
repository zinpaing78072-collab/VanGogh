import { defineConfig, type Plugin } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "..");
const previewDir = path.join(repoRoot, "preview");

/**
 * The theme sheet has to win the cascade. Vite appends its own stylesheet link
 * at the end of <head>, which would put it after #themeSkin; move the theme
 * link back to the last position after Vite is done.
 */
function themeLast(): Plugin {
  return {
    name: "vangogh-theme-last",
    transformIndexHtml: {
      order: "post",
      handler(html) {
        const match = html.match(/[ \t]*<link[^>]*id="themeSkin"[^>]*>[ \t]*\r?\n?/);
        if (!match) return html;
        const tag = match[0].trim();
        return html
          .replace(match[0], "")
          .replace(/\n[ \t]*<\/head>/, `\n    ${tag}\n  </head>`);
      },
    },
  };
}

/**
 * Dev-only: serve the repo's preview/ folder (fixtures + mock bridge) and
 * inject the two classic scripts before </head>, exactly like
 * preview/serve.js does for the built page.
 */
function previewBridge(): Plugin {
  return {
    name: "vangogh-preview-bridge",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const pathname = (req.url ?? "").split("?")[0] ?? "";
        if (!pathname.startsWith("/preview/")) return next();
        const file = path.join(previewDir, pathname.slice("/preview/".length));
        if (!file.startsWith(previewDir + path.sep)) return next();
        fs.readFile(file, (err, buf) => {
          if (err) {
            res.statusCode = 404;
            res.end("not found");
            return;
          }
          const ext = path.extname(file);
          const type =
            ext === ".js"
              ? "text/javascript; charset=utf-8"
              : ext === ".css"
                ? "text/css; charset=utf-8"
                : ext === ".json"
                  ? "application/json; charset=utf-8"
                  : "text/plain; charset=utf-8";
          res.setHeader("Content-Type", type);
          res.setHeader("Cache-Control", "no-store");
          res.end(buf);
        });
      });
    },
    transformIndexHtml(html) {
      return html.replace(
        "</head>",
        `    <script src="/preview/fixtures.js"></script>
    <script src="/preview/mock-krema.js"></script>
  </head>`
      );
    },
  };
}

export default defineConfig(({ mode }) => ({
  root: here,
  base: "./",
  publicDir: path.join(here, "public"),
  plugins: [react(), tailwindcss(), themeLast(), previewBridge()],
  build: {
    // The shipped page is the committed build output the Java shells serve from
    // the classpath. `npm run build:staging` writes to frontend/dist instead.
    outDir: process.env.VANGOGH_OUT_DIR
      ? path.resolve(repoRoot, process.env.VANGOGH_OUT_DIR)
      : path.join(repoRoot, "src", "main", "resources", "movie-page"),
    emptyOutDir: true,
    assetsDir: "assets",
    target: ["safari16.4", "chrome111"],
    sourcemap: mode === "debug",
    minify: mode !== "debug",
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
  },
}));
