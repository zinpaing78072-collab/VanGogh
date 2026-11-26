#!/usr/bin/env node
/* Drift guard for the committed page build.
 *
 * src/main/resources/movie-page/ is Vite's output directory and it is tracked:
 * the Java shells serve it from the classpath and the build must stay
 * Node-free. That only works if the committed output matches the source, so
 * this script rebuilds into a throwaway directory and compares every file
 * byte-for-byte. Exit 1 on any difference — run `npm run build` and commit the
 * result.
 *
 * `npm run check:dist` (and .githooks/pre-commit) call this.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const frontend = path.join(root, "frontend");
const committed = path.join(root, "src", "main", "resources", "movie-page");
// Run Vite's entry through the current Node binary: the .bin/vite shim is a
// shell script on Unix and a .cmd on Windows, and neither is spawnable as-is
// on every platform.
const vite = path.join(frontend, "node_modules", "vite", "bin", "vite.js");

function walk(dir, base = dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, base, out);
    else out.push(path.relative(base, full));
  }
  return out.sort();
}

if (!fs.existsSync(committed)) {
  console.error("check-dist: " + path.relative(root, committed) + " does not exist — run `npm run build`.");
  process.exit(1);
}
if (!fs.existsSync(vite)) {
  console.error("check-dist: frontend dependencies are missing — run `npm install` in frontend/.");
  process.exit(1);
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "vangogh-dist-"));
let failed = false;

try {
  execFileSync(process.execPath, [vite, "build", "--outDir", tmp, "--emptyOutDir"], {
    cwd: frontend,
    stdio: "pipe",
  });

  const expected = walk(tmp);
  const actual = walk(committed);
  const missing = expected.filter((file) => !actual.includes(file));
  const stale = actual.filter((file) => !expected.includes(file));
  const changed = expected.filter((file) => {
    if (!actual.includes(file)) return false;
    return !fs.readFileSync(path.join(tmp, file)).equals(fs.readFileSync(path.join(committed, file)));
  });

  if (missing.length || stale.length || changed.length) {
    failed = true;
    console.error("check-dist: the committed page is out of date with frontend/.");
    if (missing.length) console.error("  missing: " + missing.join(", "));
    if (stale.length) console.error("  stale:   " + stale.join(", "));
    if (changed.length) console.error("  changed: " + changed.join(", "));
    console.error("Run `npm run build` in frontend/ and commit the result.");
  } else {
    console.log("check-dist: committed page matches a fresh build (" + expected.length + " files).");
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

process.exit(failed ? 1 : 0);
