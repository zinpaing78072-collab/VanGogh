# AGENTS.md — VanGogh

Project rules for VanGogh — entertainment content recommendation desktop app (Java 25 + Krema 0.3.2 system webview).

## Project description

VanGogh is a desktop application (Java 25 + Krema 0.3.2 system webview) that recommends entertainment content (movies, music, books, games) from a single user query. The app displays results in a dark-themed card grid with poster artwork, and streams posters into the UI as native `"poster"` events from the Java backend via Krema's IPC system.

### Core behavior
- **Input**: A keyword or content name (e.g. `"Inception"`, `"Taylor Swift"`, `"Minecraft"`)
- **Output**: Related content names, titles, and references across Movies, Music, Books, and Games (currently only Movies are fully wired; other domains show a stub)
- **Mechanism**: Frontend calls `await window.krema.invoke("requestMovies", {kind, query})`; Java `MovieController.requestMovies()` dispatches to `TmdbMovieService` → `TmdbClient` (TMDB API + wsrv.nl poster proxy); posters stream as `"poster"` events into the webview
- **Filter**: Dropdown offers Movie / Music / Book / Game — only Movie connects to a real API (TMDB); others display a "not yet connected" message

### Tech stack
- **Language**: Java 25 (LTS), compiled with `--release 25`
- **UI framework**: Krema 0.3.2 (system webview)
- **Frontend**: HTML/CSS/JS served from classpath (`src/main/resources/movie-page/`)
- **HTTP client**: Java 17+ `java.net.http.HttpClient` (Gson for JSON parsing)
- **Data source**: TMDB (The Movie Database) for movies; other domains not yet wired
- **Poster proxy**: `wsrv.nl` with persistent user preference stored in `~/.van-gogh/poster-proxy.conf`
- **Tests**: JUnit 5; all network calls stubbed (no real TMDB API keys needed)
- **IPC**: `@KremaCommand` methods on controllers; `<parameters>true</parameters>` in maven-compiler-plugin for arg binding by name

### Project state
- **Branch**: `fix/agents-krema-only` (project description consolidated into AGENTS.md)
- **Features wired**:
  - `VanGoghApp` — Krema window builder (title, identifier, size, debug mode, assets, events, commands)
  - `MovieController` — `@KremaCommand requestMovies(kind, query)`, generation-counted poster streaming via `events.emit("poster", ...)`
  - `TmdbMovieService` — trending + search, genre label map, poster data-URI cache (bounded at 512 entries)
  - `TmdbClient` — TMDB API calls, poster proxy with auto failover + preference persistence
  - Frontend: card grid, search bar, content filter dropdown, fullscreen toggle, poster rendering
- **Features pending**: see "Presentation build — decided feature set" below. Loading/retry states are backlog (see below).

### Presentation build — decided feature set (2026-11-10 brainstorm)

Main goal: **the course presentation**, not production. These decisions are
settled — agents follow them. Implementation details (API providers, LLM
models, prompt design) are left to each builder slice.

- **Recommendation engine core: LLM API.** The LLM *composes* the picks for a
  query; the wired content sources (TMDB now; Music/Book/Game to be wired)
  *resolve* each pick into a real card (artwork, year, etc.). Do not build a
  deterministic API search as the result source — it would contradict the
  re-run behavior below.
- **One query, four content forms.** A single search yields recommendation
  cards in Movies, Music, Books, and Games; each form comes from a source slice
  (one form per slice — see "Build one thing at a time").
- **Search box accepts titles or vibes**, plus preset **tag chips** and
  fill-in **templates** (e.g. "albums that sound like ___, but for ___").
- **Re-running a search gives a fresh recommendation set** — the headline
  demo moment ("it thinks, it is not a database").
- **Favorites feed the engine.** Saved items influence later recommendations,
  across all four forms.
- **Detail view from every card**, everywhere cards appear (results, My Lists,
  My Searches, recommendations) — implemented per content form, one form at a
  time.
- **Saving model.** A card saves into **My Lists** (Watchlist + Favorites,
  across all four content forms). A whole query's saved results go into
  **My Searches** — a separate top-level area, *not* a My Lists item.
- **Storage: SQLite from day one.** Single database file under `~/.van-gogh/`
  (decided over flat files so the later database + login requirement rides on
  real tables).
- **Parked until their requirements land:** user accounts / login. Storage is
  designed so profiles slide on top without a rewrite.
- **Backlog (not in the presentation build):** trending-on-launch,
  recent-search chips, list export, "already enjoyed" marks, loading/empty
  polish. The LLM engine slice is the riskiest — schedule it early.

### Related project notes
- `architecture.md` — how to add new content domains (Music, Book, Game, TV)
- `repos/krema/` — vendored Krema source (read-only reference)
- Vault counterpart of this description: `10 Projects/VanGogh/description.md`
  (keep in sync when this section changes)

## Build one thing at a time

Agent work happens in **single-feature slices**. One slice per branch and PR.
Finish a slice (build green, tests pass, feature demonstrably works in the app)
before starting the next. Never expand scope mid-slice.

- **Content sources:** one content form per slice. Movies are wired; the next
  slice adds exactly one of Music / Books / Games (propose which in the PR).
- **Detail view:** implement for one content form at a time (Movies first);
  roll to the next form only when the first is complete.
- **My Lists / My Searches:** one view per slice — building the Favorites page
  i.e. Watchlist/Favorites does not jump onto My Searches, tags/templates, or
  any other feature.
- If a slice turns out to need changes outside its scope, stop and note it in
  the PR description; do not expand silently.

## Project management (Obsidian vault)

- The team's shared project-management vault is synced via Google Drive. On any machine, locate it by searching for a folder named `team-obsidian-vault` (look under the Google Drive sync folder first).
- Key files inside the vault (paths relative to vault root):
  - `00 Meta/conventions.md` — naming, linking (`[[...]]`), YAML frontmatter, tasks, Google Drive sync rules
- The project description now lives in this `AGENTS.md` (see "Project description" above); the vault copy `10 Projects/VanGogh/description.md` is the team's Google Drive-synced counterpart.
- Follow the vault conventions for note naming, linking, and YAML properties.
- One person at a time per note (Google Drive does not merge Markdown). Wait for sync before switching machines.

## Git conventions

- Remote: `https://github.com/zinpaing78072-collab/VanGogh.git` (origin)
- Default branch: `main` (protected — never commit directly to `main`)
- Sync with main before every coding sessions
- Workflow: create a feature/fix branch (`feat/<short-name>`, `fix/<short-name>`), commit there, open a PR, merge after review. Keep `main` green.
- Commit messages: short, imperative, lowercase first word (e.g. `add .pen file`, `switch ui shell to krema webview`). Use conventional-ish style; no strict prefix required.
- Build artifacts (`target/`, `.class`, `.log`, `.DS_Store`) are gitignored — never commit them.
- Run `mvn -q clean compile` (or `mvn test`) before committing; keep the build green.
- Push branches to `origin`; merge into `main` via PR only. Do not rewrite published history on `main` or any shared branch.

## Build / run

- Java 25 (LTS), Krema 0.3.2 (system webview via Panama FFM). Compiling requires
  a JDK 25+ install (pom compiles with `--release 25`); runtime is macOS default
  JAVA_HOME.
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn compile exec:exec`
  to run the app. `exec:exec` forks `${java.home}/bin/java` so the pom's JVM flags
  (`-XstartOnFirstThread`, `--enable-native-access=ALL-UNNAMED`) apply.
- Do NOT use `mvn exec:java` — it reuses Maven's JVM and drops the pom flags, so
  the Krema window never appears on macOS (AppKit requires the event loop on the
  first thread).
- macOS launch rule: `-XstartOnFirstThread` must be the **first** JVM argument.
  Without it the process runs cleanly but no window ever appears.
- VS Code's Run/Debug Java tasks work as-is: `vangogh.app.VanGoghApp` is a plain
  launcher that builds the Krema window and runs the system-webview event loop.
  Add `-XstartOnFirstThread` and `--enable-native-access=ALL-UNNAMED` to the launch task's `vmArgs`.
- The workspace config routes Code Runner's Java Run Code action through
  `mvn compile exec:exec`; do not run `javac VanGoghApp.java` directly, since
  that omits the other project classes and Maven dependencies.
- Main class: `vangogh.app.VanGoghApp`
- IPC: frontend calls `await window.krema.invoke("requestMovies", { kind, query })`;
  handlers are `@KremaCommand` methods on `MovieController` and require
  `<parameters>true</parameters>` in maven-compiler-plugin (args bind by
  parameter name). Posters stream as `window.krema.on("poster", …)` events.
- Windows constraint (krema-core 0.3.2, same family as the `AppNativePreload`
  workaround): `engine.eval` is silently dropped when called off the UI thread,
  so async command replies and backend events never reach the page. Rule:
  every command that touches the page must be synchronous (handlers run on the
  UI thread), and background work delivers through a polled drain command —
  currently `pollPosters`. Drop the dance when the project can move to a Krema
  release whose eval is thread-safe on Windows (0.3.3+ source does not fix it
  either; nothing newer is published on Maven Central).

## Vendored repositories (git subtree)

External library source may be vendored under `repos/` via `git subtree` so agents can read real implementation/tests as reference (not `node_modules`/compiled jars).

- Use vendored repos as **read-only reference material** when working with related libraries.
- Prefer examples and patterns from vendored source over generated guesses or web search.
- Do **not** edit files under `repos/` unless explicitly asked.
- Do **not** import from `repos/` — application code imports from normal Maven dependencies.

Setup (when adding a new subtree):

```sh
git subtree add --prefix=repos/<name> <repo-url> <branch> --squash
# update later:
git subtree pull --prefix=repos/<name> <repo-url> <branch> --squash
```

Editor/agent config: exclude `repos/**` from auto-import, file watcher, search, and file explorer (see `.vscode/settings.json`). AGENTS.md should name each vendored repo and its intended usage.

| Vendored repo | Intended usage |
|---|---|
| `repos/krema` (https://github.com/krema-build/krema, `master`) | Read-only reference for `build.krema:krema-core:0.3.2` — inspect real `Krema`, `KremaCommand`, `EventEmitter`, `WebViewCLibEngine` implementations and tests when working with Krema IPC/events/threading. |
