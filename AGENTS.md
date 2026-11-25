# AGENTS.md — VanGogh

Project rules for VanGogh — entertainment content recommendation desktop app (Java 25 + Krema 0.3.2 system webview).

## Project description

VanGogh is a desktop application (Java 25 + Krema 0.3.2 system webview) that recommends entertainment content (movies, music, books, games) from a single user query. The app displays results in a dark-themed card grid with poster artwork, and streams artwork through Krema IPC.

### Core behavior
- **Input**: A keyword or content name (e.g. `"Inception"`, `"Taylor Swift"`, `"Minecraft"`)
- **Output**: Related content across Movies, Music, Books, and Games (all four wired)
- **Mechanism**: The frontend invokes `requestMovies` / `requestGames` / `requestGeniusMusic` / `requestBooks` — these `@KremaCommand` handlers only *kick off* the IO-pool fetch and return the request's generation immediately (blocking the UI thread on network I/O froze the window; see the Windows constraint below). Card metadata arrives through the `pollMovieData` / `pollGameData` / `pollGeniusMusicData` / `pollBookData` drains (`pending` / `done` / `error` / `superseded`); artwork streams through the `pollMoviePosters` / `pollGamePosters` / `pollGeniusMusicPosters` / `pollBookCovers` drains (emitting `"poster"` / `"gamePoster"` / `"musicPoster"` / `"bookCover"`). Skeleton cards show while a fetch is in flight.
- **Navigation**: a collapsible sidebar switches the content form with one button per form (Music / Movies / Books / Games) — all four connect to their APIs (TMDB, TheGamesDB, Genius, Open Library). **Settings** (sidebar foot) opens two cards: **Appearance** (theme picker, read/written through `loadTheme` / `saveTheme` into `~/.van-gogh/theme.conf`) and **AI recommendations** (reads and writes `~/.van-gogh/llm.conf` through `loadLlmSettings` / `saveLlmSettings`, so a key saved there is the key the engine uses). The other menu items (Home, My Lists, Favorites) and the notification button are shell chrome with no backend yet; the header avatar opens the **Profile** page (a presentation slice: identity, universe stats and collection tiles, with no profile store behind it). `Menu` and `Library` are section labels, not destinations.

### Tech stack
- **Language**: Java 25 (LTS), compiled with `--release 25`
- **UI framework**: Krema 0.3.2 (system webview)
- **Frontend**: React 19 + TypeScript (strict) + Tailwind CSS v4, source in
  `frontend/`, built by Vite into the committed classpath page
  `src/main/resources/movie-page/`
- **HTTP client**: Java 17+ `java.net.http.HttpClient` (Gson for JSON parsing) for the content sources; the LLM slice uses the official `com.openai:openai-java` SDK (OkHttp + Jackson) with `baseUrl` pointed at any OpenAI-compatible endpoint
- **Data sources**: TMDB (The Movie Database) for movies, TheGamesDB for games, MusicBrainz (+ Cover Art Archive) for music, Open Library (+ Open Library Covers) for books
- **Poster proxy**: `wsrv.nl` with persistent user preference stored in `~/.van-gogh/poster-proxy.conf`. Needed because `image.tmdb.org` is unreachable from this machine/ISP (TLS dies mid-handshake; `api.themoviedb.org` works) — poster loading depends on the proxy failover, see team-vault "Debug Record — TMDB posters blocked".
- **Tests**: JUnit 5; all network calls stubbed (no real TMDB API keys needed)
- **IPC**: `@KremaCommand` methods on controllers; `<parameters>true</parameters>` in maven-compiler-plugin for arg binding by name

### Vavr conventions (functional core)

`io.vavr:vavr:1.0.1` is a compile dependency; the vendored `repos/vavr` subtree is the reference for its API. Functional types are used throughout the codebase as follows (mirroring the already-vavr-ified movie slice):

- **`io.vavr.control.Option`** instead of `java.util.Optional` — for absent/present JSON fields, nullable lookups, and `posterUrl`/`genre` defaults. Prefer `.map`, `.flatMap`, `.filter`, `.getOrElse`.
- **`io.vavr.control.Try`** instead of `throws Exception` on parsing and network methods where representing failure as a value is cleaner. Recover with `.getOrElse` / `.getOrElseThrow`.
- **`io.vavr.concurrent.Future`** instead of `CompletableFuture` — for async poster prefetch on the IO pool; Krema command handlers on the UI thread stay synchronous.
- **`io.vavr.collection.List`/`Seq`/`Map`** — persistent immutable collections for in-memory transformations. Convert to `java.util.List` only at Krema IPC boundaries (`@KremaCommand` return types).
- **Caches stay `java.util.concurrent.ConcurrentHashMap`** — vavr has no concurrent map; immutable vavr maps are not a fit for a mutable read-through cache.
- **Records stay data carriers** — `Movie`, `Game` are unchanged; only their construction/population goes functional.
- **Verify op semantics against the vendored source, never intuition.** Two traps verified 2026-11-15 (poster failover, [[Debug Record — VanGogh Posters Never Loaded (vavr takeUntil)]] in the agent-docs vault): vavr's `takeUntil` is `takeWhile(negate)` — it **excludes** the element satisfying the predicate, so "first match, else none" is `find(...)`, never `takeUntil(...).lastOption()`; and `Try` treats `InterruptedException` as **fatal** — `Try.of` rethrows it sneakily instead of capturing it, so catch interrupts at the boundary (restore the flag) and carry a non-fatal marker if a chain must observe the stop.

### Project state
- **Features wired**:
  - `VanGoghApp` — Krema window builder (title, identifier, size, debug mode, assets, events, commands)
  - `MovieController` — `@KremaCommand requestMovies(kind, query)` (non-blocking kickoff, returns the generation), data + poster drains, generation-counted poster streaming via `events.emit("poster", ...)`; `kind=recommend` routes through the LLM engine (below). `requestMovieDetail` / `pollMovieDetail` / `pollMovieDetailArt` resolve one film for the detail page (identity, facts ledger, cast & crew, stills, similar rail) with its own generation pair, streaming poster/headshots/stills/similar posters as data URIs through `movieDetailArt` events keyed by slot (`poster`, `cast:<i>`, `still:<i>`, `similar:<id>`). The identity's **Watch trailer** button opens the TMDB trailer in an in-page YouTube embed (portaled above the shell) rather than linking out; a trailer URL that is not YouTube falls back to the plain watch link
  - `vangogh.llm` (`LlmConfig`, `LlmClient`, `PromptFactory`, `Pick`, `TitleParser`, `Normalize`), `vangogh.recommend` (`PickResolver`, `RecommendationService`), `MovieResolver`, `SongResolver`, `BookResolver`, and `GameResolver` — the AI recommendation engine (all four forms wired): one model request composes `title | disambiguator` picks, then TMDB resolves movie picks, Genius resolves song picks, Open Library resolves book picks and TheGamesDB resolves game picks in parallel. Config lives in `~/.van-gogh/llm.conf` — the single source of truth, written by the Settings page and re-read on every AI search (no restart, no environment overrides); the AI toggle is opt-in, so direct search and trending are unchanged — see `docs/llm-engine.md`
  - `TmdbMovieService` — trending + search, genre label map, poster data-URI cache (bounded at 512 entries)
  - `TmdbClient` — TMDB API calls, poster proxy with auto failover + preference persistence
  - `GameController`, `TgdbGameService`, and `TgdbClient` — TheGamesDB search/trending and front-boxart streaming; `requestGames` with `kind=recommend` routes through the LLM engine (games resolved by `GameResolver`). `requestGameDetail` / `pollGameDetail` / `pollGameDetailArt` resolve one game for the immersive detail page (fanart hero, stat band, record rail, overview, screenshots, platforms, related-games rail) with its own generation pair, streaming boxart/fanart/screenshots/related boxarts as data URIs through `gameDetailArt` events keyed by slot (`boxart`, `hero`, `still:<i>`, `related:<id>`)
  - `MusicController`, `MusicBrainzService`, and `MusicBrainzClient` — the earlier MusicBrainz release-group slice (album cards), curated trending MBID list, Cover Art Archive cover streaming; MBZ requests are paced (~1/s) with a 503 backoff-retry — see `docs/music-domain.md`. Not the page's live music form: it uses `GeniusController`.
  - `GeniusController`, `GeniusMusicService`, and `GeniusClient` — Genius
    chart/search song lists; `requestGeniusMusic` with `kind=recommend` routes
    through the LLM engine (songs resolved by `SongResolver`), and
    `requestSongDetail` / `pollSongDetail` resolve one
    song for the detail page: credits (written/produced/featuring plus the full
    `custom_performances` bill), album, release date, pageviews, the editorial
    About copy, listen/watch links, language + translation count, artwork
    palette, artist/album identity, recording location, community stats and
    related songs (lyrics, duration and audio are dummy data in `DummyMedia`
    until those sources land)
  - `BookController`, `OpenLibraryBookService`, and `OpenLibraryClient` — Open Library search/weekly-trending and cover streaming (books are the design-system Book Card: poster-card layout, `year  Author · N pages` meta, own click detail); `requestBooks` with `kind=recommend` routes through the LLM engine (books resolved by `BookResolver`). `requestBookDetail` / `pollBookDetail` / `pollBookDetailArt` resolve one work (identity, record ledger, description + subjects, editions, author card, similar rail) with `BookDetailMapper` folding the work/editions/ratings/author payloads, and stream cover/author/edition/similar artwork as data URIs through `bookDetailArt` events keyed by slot (`cover`, `author`, `edition:<i>`, `similar:<workKey>`)
  - `vangogh.theme.ThemeConfig` + `ThemeController` — the skin picker: `loadTheme` / `saveTheme` (synchronous, `~/.van-gogh/theme.conf`), reply `{status, theme, themes}`, so the Settings chips are rendered from the Java registry (`ThemeConfig.KNOWN`) and the page carries no theme ids, labels or colours. Theme = CSS only: `frontend/src/theme/theme.ts` moves `html[data-theme]` and the `#themeSkin` href. See `docs/themes.md`
  - Profile page — presentation slice: the header avatar opens `ProfileView`
    (identity + "Member since", the "My Entertainment Universe" numbers, four
    collection tiles). It is an overlay like Settings: Back/Escape return to the
    covered browse/detail and Escape closes the inline editor first. The identity
    edit is page-local (`state.profile`; no backend or My Lists/Favorites write),
    and "View collection" stays inert until those stores exist. Fixtures:
    `frontend/src/components/profile/profileData.ts`; skin:
    `frontend/src/styles/profile.css`.
  - Frontend: React 19 + TypeScript + Tailwind v4 under `frontend/`, built by
    Vite into `src/main/resources/movie-page/` (see "Frontend" below): shared
    card grid with per-domain card shapes (movie/game portrait posters, music
    square covers with an artist subtitle, books the Book Card), search bar,
    artwork rendering. The content-form switcher is the collapsible sidebar: one
    `.nav-item[data-domain]` button per form (it replaced the old dropdown).
    The active form lives in `state.activeDomain` in `app/store.ts`; the sidebar
    paints the active button from it, and a click runs the trending load.
    Buttons without `data-domain` are inert shell chrome, except Settings,
    which opens the Settings page. The header AI
    toggle (`#aiToggle`, `state.ai`) is opt-in and only changes the `kind` a
    search sends (`recommend` instead of `search`); it survives a form switch.
- **Features pending**: the "Presentation build" features below. Loading states shipped (skeleton cards while a fetch is in flight); retry/empty polish is backlog.

### Frontend (React + TypeScript + Tailwind, built into the classpath)

The page is a React app. Source lives in `frontend/`; `vite build` writes the
shipped page into `src/main/resources/movie-page/`, which **is committed** —
both shells serve it from the classpath over `http://127.0.0.1:<port>` (Krema's
asset server, krema-core's `AssetServer` for JCEF), and Maven must never need
Node. `index.html` loads `krema-shim.js` (classic) and the hashed module bundle.

- `frontend/src/bridge/` — the **only** place `window.krema`, command names and
  payload shapes appear: `bridge.ts` (typed `invoke`/`on`, unwraps Krema's
  `{payload, timestamp}` envelope, the "API bridge not ready yet." error),
  `types.ts` (hand-written mirrors of the Java records and replies),
  `domains.ts` (one `DomainIpc` row per form: request/data/art/event names, the
  detail request/poll/art triple, `idField`), `commands.ts` (domain → command
  switches, so each call is narrowly typed).
- `frontend/src/app/` — `store.ts` (module-level observable state +
  `useSyncExternalStore`; the sessions map *is* the store), `actions.ts` (named
  transitions; no bridge calls, so it tests in isolation), `types.ts`,
  `navigation.ts`, `App.tsx` (subscriptions + layout).
- `frontend/src/hooks/` — `useContent` (request → data drain → artwork drain),
  `useDetail` (detail request + art drain, keyed by the detail `nonce`),
  `useArtwork` (App-level event subscriptions), `useEscape`,
  `useNarrowSidebar` (`NARROW_WIDTH = 900`).
- `frontend/src/components/` — `shell/{Sidebar,Header}`, `browse/BrowseView`
  plus `cards/` (shared `CardShell`/`CardArtwork`, portrait `PosterCard`,
  `MusicCard`, `BookCard`, placeholders), `settings/SettingsView`,
  `profile/ProfileView` (plus its `profileData.ts` fixtures), and one
  folder per detail view: `detail/song` (incl. `player.ts`, the module-level
  player store the detail and its `PlayerBar` share), `detail/movie`,
  `detail/book`, `detail/game`.
- `frontend/src/styles/` — `base.css` (structure + role tokens' Starry
  defaults), `music-detail.css`, `movie-detail.css`, `book-detail.css`,
  `game-detail.css`, `settings.css`, `profile.css`, `tailwind.css`; imported
  once, in that order, so the bundled sheet is a single `<link>`.
- `frontend/public/` — `krema-shim.js`, `theme-starry.css`,
  `theme-liquidneon.css`, the backdrops, `posters/`. Vite copies them to the
  output root verbatim, so `#themeSkin` and the backdrop `url()`s still resolve
  from the page directory.
- `frontend/src/theme/theme.ts` — the only module that knows a theme exists:
  `loadTheme`/`saveTheme`, moves `html[data-theme]` and the `#themeSkin` href.
  `main.tsx` awaits it before the first render; the chip list is painted from
  the reply (`ThemeConfig.KNOWN` is the registry).

Build rules:

- `cd frontend && npm run build` — writes the committed page. **Commit the
  output with the source change**; a source edit without a rebuild ships a stale
  page.
- `npm run check:dist` rebuilds into a temp dir and byte-compares it against the
  committed page; `.githooks/pre-commit` (installed by `npm install` through
  `prepare`) runs it when a commit touches `frontend/` or the page.
- `npm run verify` — typecheck, lint, Vitest, build, then `preview/check.js`
  against the fresh output.
- `npm run dev` / `npm run build:staging` — HMR and `frontend/dist` for UI work
  (see "UI preview harness"). `node preview/check.js --page=frontend/dist`
  drives the staging build.
- `mvn compile exec:exec` and `mvn test` are untouched and require no Node.

Layout and cascade invariants (unchanged, now enforced by the bundle):

- CSS is layered, not per-component: structure and role tokens first, then the
  active `theme-<id>.css` **last** (`#themeSkin`). The `themeLast()` Vite plugin
  moves the theme link back to the end of `<head>` after Vite appends its own
  bundle link — a theme must win the cascade without `!important`. Adding a
  theme is a CSS file in `frontend/public/` plus a `ThemeConfig.KNOWN` row
  (recipe: `docs/themes.md`).
- **The responsive grid ladder is base-only** — one ladder at the end of
  `base.css` (5 columns, 4 ≤1180px, 3 ≤900px, 2 ≤768px), never duplicated in a
  theme file: a theme decorates, it does not reflow. The 900px step matches
  `NARROW_WIDTH = 900` in `hooks/useNarrowSidebar.ts`, where the sidebar
  auto-collapses, so the grid regains the rail's room on the same boundary —
  change one, change both. `.card-meta-item` is `nowrap` (tokens stay whole)
  except in book cards, whose meta is the prose phrase `Author · N pages`.
- **A flex-column card that is a `<button>` needs `align-items: stretch`.**
  WebKit's UA stylesheet sets `align-items: flex-start` on buttons, so the
  column's items keep their intrinsic width; a poster whose `aspect-ratio` box
  holds only an absolutely positioned `<img>` then collapses to 2×3px and the
  rail renders bare on macOS (Chromium's UA differs, so Windows looked fine).
  All three similar rails (`.movie-sim-card`, `.book-sim-card`,
  `.game-rel-card`) carry the fix and `preview/check.js` asserts the 2:3 shape —
  do the same for a new detail rail.
- **Streamed artwork is state, never DOM surgery.** Every slot (`poster`,
  `cast:<i>`, `still:<i>`, `similar:<id>`, `cover`, `author`, `edition:<i>`,
  `similar:<workKey>`, `boxart`, `hero`, `related:<id>`) is a value in the
  session's `art` map; a missing image leaves its placeholder instead of a
  broken icon. The failed-URI components remember *which* URI failed, so a late
  data URI is a fresh attempt.

**Sessions survive navigation.** One session per content form lives in the
store (`app/types.ts` → `Session`), so leaving a form saves nothing and
returning restores everything — last query, cards, artwork, open detail and
scroll position — with no IPC. Switching to a form with no cards loads
trending. Page memory only — closing the app clears it. A form's own button is
a no-op while that form is active; Back/Escape leave a detail. Settings and
Profile are round-trips: `settingsReturn` / `profileReturn` remember whether the
overlay covered the grid or a detail, and a detail's late reply cannot unhide
over either. Leaving a loading form cancels its request and returning re-fires
it; leaving a ready form keeps the cards and only resumes the poster drain.

Rules that keep the graph acyclic: `bridge/domains.ts` is data only (no React);
cards take an `onOpen` callback, so `cards/` never imports `actions.ts`;
`BrowseView` passes `openDetail` in; the detail views paint themselves and
`App` only decides visibility. A new content form is a `DomainIpc` row, a card,
a detail view and its hook wiring — nothing else needs to know it exists.

### Presentation build — decided feature set (2026-11-10 brainstorm)

Main goal: **the course presentation**, not production. These decisions are
settled — agents follow them. Implementation details (API providers, LLM
models, prompt design) are left to each builder slice.

- **Recommendation engine core: LLM API.** The LLM *composes* the picks for a
  query; the wired content sources (TMDB, TheGamesDB, MusicBrainz, Open Library)
  *resolve* each pick into a real card (artwork, year, etc.). Do not build a
  deterministic API search as the result source — it would contradict the
  re-run behavior below.
- **One query, four content forms.** A single search can yield recommendation
  cards in Movies, Music, Books, and Games; each form comes from a source slice
  (one form per slice — see "Build one thing at a time"); the recommandation is lazy, only when the drop bar get toggle.
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
- `docs/llm-engine.md` — LLM recommendation engine: config, request kinds, error text, quota, tests
- `docs/themes.md` — theme layers, the role-token contract, and the recipe for theme #3
- `repos/krema/` — vendored Krema source (read-only reference)
- Vault counterpart of this description: `10 Projects/VanGogh/description.md`
  (keep in sync when this section changes)

## Build one thing at a time

Agent work happens in **single-feature slices**. One slice per branch and PR.
Finish a slice (build green, tests pass, feature demonstrably works in the app)
before starting the next. Never expand scope mid-slice.

- **Content sources:** one content form per slice. Movies, Music, Books, and
  Games are each wired; any new form (e.g. TV) gets its own slice.
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
- Commit messages: short, imperative, lowercase first word (e.g. `add theme css file`, `switch ui shell to krema webview`). Use conventional-ish style; no strict prefix required.
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
- **`java.lang.Error: Unresolved compilation problems` at startup is stale IDE
  output, not a source error.** The VS Code Java extension compiles into
  `target/classes`; a stale language-server model writes JDT error stubs there,
  and Maven's timestamp check then skips recompiling them. Fix: `mvn clean
  compile` (the `clean` matters) and, if it recurs, Cmd+Shift+P → "Java: Clean
  Java Language Server Workspace". Story: vault "Debug Record — IDE wrote an
  error stub into target/classes".
- The workspace config routes Code Runner's Java Run Code action through
  `mvn compile exec:exec`; do not run `javac VanGoghApp.java` directly, since
  that omits the other project classes and Maven dependencies.
- Main class: `vangogh.app.VanGoghApp` (Krema shell) or
  `vangogh.app.jcef.JcefVanGoghApp` (JCEF experiment shell — see below).
- IPC: frontend calls `await window.krema.invoke("requestMovies", { kind, query })`
  (or `requestGames`) — the handler kicks off the IO-pool fetch and returns
  the request generation immediately (never blocks the UI thread). The page
  then polls `pollMovieData` / `pollGameData` with `{ generation }` until the
  card list (or error) arrives, and `pollMoviePosters` / `pollGamePosters`
  for artwork. Handlers are `@KremaCommand` methods on `MovieController` and
  `GameController` and require `<parameters>true</parameters>` in
  maven-compiler-plugin (args bind by parameter name). Posters arrive as
  `window.krema.on("poster", …)` / `window.krema.on("gamePoster", …)` events
  emitted by the drains on the UI thread. Detail views have their own generation
  pairs so opening one never supersedes the browse request: `requestMovieDetail`
  / `pollMovieDetail` + `pollMovieDetailArt`, `requestSongDetail` /
  `pollSongDetail`, `requestBookDetail` / `pollBookDetail` +
  `pollBookDetailArt`, and `requestGameDetail` / `pollGameDetail` +
  `pollGameDetailArt`. The Settings page is the one pair
  that needs no drain: `loadLlmSettings` / `saveLlmSettings` on
  `SettingsController` and `loadTheme` / `saveTheme` on `ThemeController` are
  synchronous (one small file each) and reply at once.

### UI preview harness (browser, mock IPC)

`node preview/serve.js` (Zed task "preview UI (browser, mock IPC)") serves the
**real** page from `src/main/resources/movie-page/` over loopback HTTP with a
fake `window.krema` injected, so layout and styling iterate on a browser
refresh instead of `mvn compile exec:exec` plus a native window. For React
source edits, run `cd frontend && npm run dev` — Vite serves the source with
HMR and the same fixtures/mock injected (`?scenario=…`, `?theme=…`). To exercise
a production build, `npm run build:staging` writes `frontend/dist` and
`node preview/serve.js` / `node preview/check.js` accept `VANGOGH_PAGE_DIR` /
`--page=frontend/dist`. `krema-shim.js` self-disables when a bridge is present,
so no app code changes for this. `?scenario=slow|empty|error|noposters`
reproduces the states that are otherwise unreproducible (skeletons, empty,
error, missing artwork), and `?theme=<id>` starts the page with that skin
stored. Fixtures are keyed to the real resolved-item shapes and survive the LLM
engine slice. `node preview/check.js` (Zed task "check UI (headless)") is the
same harness driven headless: assertions over the card grid, the sidebar (form
buttons, inert items, section labels, collapse/reflow), search, the Settings
form (loads the stored config, refuses a save with no key, saves and keeps the
key off the page), the theme picker (chips from the reply, live repaint of
shell + cards + AI toggle, one save, reopening shows it pressed, `?theme=`
startup and fallback), the song, movie, book and game detail views (every
bento/immersive tile, each artwork slot, actions, Escape back to the grid), the
profile screen (identity, the universe numbers, the four collection counts, the
inert "View collection", the editor round-trip and the two-stage Escape) and
every scenario, exit 1 on failure. Full guide: `docs/preview-harness.md`.
Layout, styling and page-side code go through the preview; shell, IPC and
network code still need a real app run.

### JCEF experiment (merged to `main` via PR #28; further shell work happens on its own branches)

A standalone shell swap that replaces Krema's OS system webview with a bundled
Chromium engine via `me.friwi:jcefmaven:152.0.6`. The existing controllers
(`MovieController`, `GameController`) are shell-agnostic — they receive a
`JcefEventEmitter` (a Krema `EventEmitter` subclass) via `setEventEmitter()`
and their `@KremaCommand` methods are called directly by the JCEF bridge.

Files:
- `src/main/java/vangogh/app/jcef/JcefVanGoghApp.java` — `main()`: boots
  `CefApp`, serves `/movie-page` from the classpath through krema-core's
  `AssetServer` over loopback HTTP (the same load path as the Krema shell — and
  the reason ES modules work in this shell), creates a Swing `JFrame` hosting a
  **windowed**-rendered `CefBrowser` (OSR is deliberately not used — the
  OSR/JOGL path crashes on macOS, see the app's own comments).
- `src/main/java/vangogh/app/jcef/JcefBridge.java` — `CefMessageRouter`
  handler for JS→Java (`window.krema.invoke` → controller methods) plus
  `JcefEventEmitter` wiring for Java→JS poster events.
- `src/main/java/vangogh/app/jcef/JcefEventEmitter.java` — extends Krema's
  `EventEmitter`, overrides **both** `emit` overloads to deliver via
  `executeJavaScript` on the Swing EDT (the base class's path would hit the
  null engine).
- `src/main/resources/movie-page/krema-shim.js` — implements
  `window.krema.invoke/on` on top of `cefQuery` and `window.__krema_emit`
  (payloads wrapped as `{payload, timestamp}`, like Krema's bridge). It is part
  of the page and loaded by `index.html`, and it returns immediately when
  `window.krema` already exists — the Krema shell injects its native bridge
  before page scripts, so the shim is inert there. No HTML rewriting anywhere.
- `pom.xml` — adds `me.friwi:jcefmaven` dependency and macOS
  `--add-opens java.desktop/...=ALL-UNNAMED` flags (required by jcefmaven;
  harmless for Krema).

To run:
- **Krema** (default): `mvn compile exec:exec` — Zed task "run van-gogh (Krema)" or the `java-main` ▶ indicator.
- **JCEF**: `mvn compile exec:exec -Pjcef` — Zed task "run van-gogh (JCEF)" or "java run (JcefVanGoghApp via exec:exec -Pjcef)". Uses the vendored `jcef-bundle/` install dir, so no ~100 MB download; delete that dir to force a fresh CEF install.
- Windows constraint (krema-core 0.3.2, same family as the `AppNativePreload`
  workaround): `engine.eval` is silently dropped when called off the UI thread,
  so async command replies and backend events never reach the page. Rule:
  every command that touches the page must be synchronous (handlers run on the
  UI thread) — and that thread must never block on network I/O either: the
  original handlers fetched synchronously and the whole window froze for the
  round-trip (see "Debug Record — UI thread blocked by content fetches").
  Delivery is drain-based for all four content slices: the request commands kick
  off IO-pool fetches and return at once; card metadata drains through
  `pollMovieData` / `pollGameData` / `pollMusicData` / `pollBookData` and
  posters through `pollMoviePosters` / `pollGamePosters` / `pollMusicPosters` /
  `pollBookCovers` (which doubles as the completion signal, returning −1 when
  nothing is pending) — all synchronous UI-thread commands. The song, movie and
  book details follow the same rule with their own generation pairs
  (`requestSongDetail` / `pollSongDetail`, `requestMovieDetail` /
  `pollMovieDetail` plus `pollMovieDetailArt`, `requestBookDetail` /
  `pollBookDetail` plus `pollBookDetailArt`), so opening a detail cannot
  supersede the browse request or its artwork stream. Drop the dance
  when the project can move to a Krema release whose eval is thread-safe on
  Windows (0.3.3+ source does not fix it either; nothing newer is published on
  Maven Central).
- macOS constraint (jcef windowed rendering): any native NSMenu tracking
  session — the OS app menu bar, the green button's hover menu (fullscreen
  on/off via the green button; the native `<select>` popup was the other
  trigger, and the page no longer contains a `<select>` — the content switcher
  is plain sidebar buttons) — can trap the AppKit thread permanently.
  The window stops taking all input while the renderer keeps running; only
  relaunching recovers. Fullscreen: green button only (user decision
  2026-11-14 — no DOM/keyboard toggle); aim-and-click it quickly, a long
  hover pops its menu and can freeze. Accepted for the experiment — do not
  rediscover this. Details: vault "Debug Record — JCEF window freezes on
  native menus".

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
| `repos/vavr` (https://github.com/vavr-io/vavr, `main`) | Read-only reference for `io.vavr:vavr:1.0.1` — inspect `Future`, `Try`, `Either`, `Option`, and persistent collections when working with functional patterns. |
