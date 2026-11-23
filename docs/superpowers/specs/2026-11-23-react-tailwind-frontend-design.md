---
title: "React + TypeScript + Tailwind frontend rewrite"
date: 2026-11-23
project: "[[VanGogh]]"
tags: [frontend, architecture, react, typescript, tailwind, design]
status: proposed
---

# React + TypeScript + Tailwind frontend rewrite

## Intent

The page works and is unusually well documented, but its shape fights coding
agents. State is one mutable singleton; `render.js` imports every detail view;
the four detail views build `innerHTML` strings; artwork is routed by querying
the DOM for `data-<domain>-id`; and ~12k lines of CSS cascade across views and
themes. An agent changing one view has to reason about all of them, and a change
can break a selector in `preview/check.js` three views away.

This slice replaces the hand-rolled page with a conventional, typed React app:
components own their markup and local state, one typed bridge owns the IPC
contract, and Tailwind utilities own styling over the existing role tokens. The
goal is **agent friendliness and architecture**, not a redesign: behavior and
look stay the same, and the existing preview harness remains the gate.

This supersedes the "no framework, bundler, or build step" non-goals of
`2026-11-17-frontend-architecture-design.md` and
`2026-11-17-frontend-modules-design.md`. Everything else in those documents —
the `window.krema` seam, the drained delivery model, the theme contract, the
per-form sessions — still holds.

## Goals

- One Vite + React 19 + TypeScript + Tailwind v4 project under `frontend/`,
  built into `src/main/resources/movie-page/`.
- One typed bridge (`frontend/src/bridge/`) that is the only place IPC command
  names, argument shapes and reply shapes appear.
- One typed app store plus hooks for the request/poll/artwork lifecycles; no
  imperative DOM painting.
- One component per view: shell, browse grid, Settings, four detail views, the
  song player.
- Tailwind v4 semantic utilities over the existing role tokens; the Java theme
  registry and `theme-<id>.css` contract are unchanged.
- Full behavior parity, including per-form sessions, the supersede guard,
  skeletons, scenarios, both themes, and the responsive ladder.
- Agent guardrails: strict `tsc --noEmit`, ESLint, Prettier with Tailwind class
  sorting, Vitest for logic/components, and the existing `preview/check.js` as
  the end-to-end gate.
- A clean clone still builds and runs with Maven alone: no Node required for
  `mvn compile exec:exec`.

## Non-goals

- No Java, controller, command, event, or payload changes. The bridge contract
  is frozen; TypeScript mirrors it.
- No new product features: no My Lists, My Searches, favorites persistence,
  SQLite, accounts, router/history, or search history.
- No visual redesign. Pixel parity is the baseline; a visual refresh is a
  separate slice.
- No React Router, SSR, Next.js, or file-based routing. View state is a
  discriminated union in the store.
- No component library (MUI, shadcn, Radix) and no icon package. The design
  system is bespoke and the inline SVGs stay.
- No code splitting or lazy routes. One bundle is fine for a local desktop app.
- No second theme registry in TypeScript. `ThemeConfig.KNOWN` remains the only
  one.
- No Maven-managed Node download, and no frontend build during `mvn test`.

## Anti-goals

- Two frontends maintained in parallel after cutover (vanilla views and React
  views both shipped).
- A blank window on a clean clone because `frontend/dist` was never built.
- Generated output in git that silently drifts from `frontend/`.
- Raw hex colours or theme-private token names inside components; that is how
  the theme contract dies.
- Utility strings that hide structure so completely that `check.js` and future
  agents cannot find a node.
- A native `<select>` anywhere: its macOS NSMenu popup traps the AppKit thread
  and freezes the window.

## Constraints

- **Shells.** Krema (system WKWebView) and JCEF (Chromium 152) both serve the
  page from the classpath over loopback HTTP. The build must be self-contained,
  use relative asset paths (`base: "./"`), and work when the page URL is
  `/movie-page/index.html` or the shell root.
- **Offline.** No CDN, no remote fonts, no runtime dependency downloads. All JS
  and CSS ship in the bundle.
- **Windows Krema.** `engine.eval` is dropped off the UI thread, so every
  command that touches the page stays synchronous on the UI thread. The page
  keeps the kickoff → `poll*` → `art` drain model exactly as it is.
- **macOS NSMenu trap.** No `<select>`, no native popup menus. Plain buttons and
  DOM overlays only.
- **Theme contract.** `theme-<id>.css` loads **last**, after all structural and
  utility CSS; `#themeSkin` swaps its href; `<html data-theme>` is the marker;
  `ThemeConfig.KNOWN` is the only registry. A theme decorates, it never
  reflows.
- **Responsive ladder.** 5 columns, 4 ≤1180px, 3 ≤900px, 2 ≤768px, declared
  once. The 900px step must stay tied to `NARROW_WIDTH = 900` in the sidebar
  code.
- **Preview harness.** `preview/serve.js` serves
  `src/main/resources/movie-page/`, injects `fixtures.js` and `mock-krema.js`
  before `</head>`, and `preview/check.js` asserts 206 invariants across 23
  groups. The cutover keeps those assertions meaningful.
- **Toolchain.** Node 22.23 / npm 10.9 are installed. The browser floor for
  Tailwind v4 (Safari 16.4+, Chrome 111+) is met by macOS 14.8 and CEF 152.
- **Types are hand-written.** There is no schema export from the Java records;
  `bridge/types.ts` mirrors them and a comment points at each record file.
- **Current branch.** `feat/session-persistence` is checked out with untracked
  theme/poster files. The rewrite branches from `main` after that work lands.

## Approaches considered

The application architecture was settled in conversation (full React + TS +
Tailwind). The remaining fork was how the build lands.

1. **Vite builds into the resource directory (chosen).** `frontend/` holds the
   Vite project; `vite build` writes to `src/main/resources/movie-page/` with
   relative paths and stable names for theme sheets. Maven stays Node-free, the
   shells are unchanged, and the existing preview harness tests the exact
   shipped page. Cost: the built bundle is committed and agents must rebuild;
   a drift check guards staleness.
2. **Dist gitignored, Maven copies it.** Cleaner repository, but a fresh clone's
   `mvn compile` produces an app with no UI until `npm ci && npm run build`
   runs. `mvn test` stays green while the window is blank — the failure mode
   that wastes an agent's afternoon.
3. **Maven runs the frontend build (`frontend-maven-plugin`).** One command
   builds everything and nothing generated is committed. Cost: a ~100 MB Node
   download and npm install on first build, network dependency, and a slower
   `mvn test` for a project whose Java build is currently offline and fast.

Approach 1 is chosen: it preserves the one-command launch, keeps the harness
honest, and makes stale output a visible, guardable problem rather than a blank
window.

Architecture alternatives dropped:

- **Keep the vanilla modules under a React shell.** Rejected: the stated goal is
  architecture, and a dual-paradigm page is worse for agents than either end
  state.
- **Next.js / Remix.** Rejected: no server, no routing, no SSR. Vite is the
  smallest thing that does the job.
- **Zustand / Redux / TanStack Query.** Rejected for this size. The store is a
  `Map` of sessions plus a few setters; the drain model is not a promise cache,
  and wrapping it in a query library hides the generation semantics.
- **Tailwind v3.** Rejected: v4's CSS-first `@theme` maps the existing role
  tokens directly and removes the JS config file; the browser floor is met.
- **Conditional rendering + `data-testid` rewrite of the harness.** Deferred:
  keep the current DOM contract (IDs, key classes, `.hidden`) through the
  cutover so 206 assertions stay meaningful; add testids for new assertions and
  revisit in a later slice.

## Target architecture

### Repository layout

```
frontend/
  index.html                 Vite entry; classic shim + module bundle
  package.json
  package-lock.json
  tsconfig.json              strict
  vite.config.ts             React + Tailwind plugins, base "./", preview bridge
  eslint.config.js
  .prettierrc
  public/                    copied verbatim to the output root
    krema-shim.js
    theme-starry.css
    theme-liquidneon.css
    starry.png
    liquidmetal.jpeg
    liquidneon.png
    posters/
  src/
    main.tsx                 createRoot + <App/>
    app/
      App.tsx                shell layout, view switch, bootstrap
      store.ts               typed store + useStore(selector)
      actions.ts             session/view/query/artwork actions
      types.ts               View, DomainKey, Session, BrowseState, DetailState
    bridge/
      types.ts               IPC payload types mirroring the Java records
      bridge.ts              typed invoke/on wrapper, bridge-ready guard
      domains.ts             one descriptor per content form
    hooks/
      useContent.ts          kickoff + data drain + artwork drain
      useDetail.ts           detail kickoff + poll + art drain
      useArtwork.ts          per-form poster subscription
      useSessionScroll.ts    save/restore per-form scroll
      useNarrowSidebar.ts    900px auto-collapse + user override
      useEscape.ts           shared Escape handler
    components/
      shell/                 Sidebar, Header, SearchBar, AiToggle
      browse/                BrowseView, CardGrid, SkeletonGrid, EmptyState
      cards/                 PosterCard, MovieCard, GameCard, MusicCard, BookCard
      settings/              SettingsView, AppearanceCard, LlmForm
      detail/song/           SongDetail + bento tiles + PlayerBar
      detail/movie/          MovieDetail + bento tiles
      detail/book/           BookDetail + bento tiles
      detail/game/           GameDetail + immersive tiles + Lightbox
    styles/
      tailwind.css           theme + utilities layers, semantic @theme tokens
      base.css               what Tailwind cannot express (backdrops, scrollbars,
                             keyframes, reduced motion)
    lib/
      format.ts              formatTime, formatVotes, initials, paragraphs,
                             secureLink, ratingParts
      cn.ts                  clsx + tailwind-merge helper
```

Outside `frontend/`, the slice adds `preview/baseline.js` (the parity capture
script), `preview/baselines/2026-11-23/` (its output), `scripts/check-dist.mjs`
(the drift guard) and `.githooks/pre-commit`.

`src/main/resources/movie-page/` becomes **generated output**: `index.html`,
`assets/*`, and the copied public files. It is committed so Maven works without
Node. The generated `index.html` carries a banner comment saying it is built
from `frontend/` and must not be edited.

### Build pipeline

- `@vitejs/plugin-react` + `@tailwindcss/vite`.
- `base: "./"`, `build.target: ["safari16.4", "chrome111"]`.
- **Staging build during the port.** Until the cutover commit, `build.outDir`
  is `frontend/dist` (gitignored) and the vanilla page in
  `src/main/resources/movie-page/` stays the shipped app. `preview/serve.js`
  gains a page-directory override (`VANGOGH_PAGE_DIR`, defaulting to the
  current resource dir), so `npm run check:react` can drive the staging build
  while the vanilla page keeps passing the existing harness. At cutover,
  `outDir` flips to `../src/main/resources/movie-page`, `emptyOutDir: true`,
  the vanilla `js/` and per-view CSS are deleted, and the built page is
  committed. The two pages coexist only *during* the branch, not after cutover;
  this keeps the branch demoable and the baseline page available for comparison.
- **Shipped artifact list.** `index.html`, `assets/*.js`, `assets/*.css`, and
  the copied public files (theme sheets, backdrops, posters, `krema-shim.js`).
  Sourcemaps are **not** shipped: `sourcemap: false` keeps `sourcesContent` out
  of the committed bundle. `npm run build:debug` writes a sourcemapped build to
  `frontend/dist-debug` for local webview debugging.
- Public assets keep stable names: theme sheets, backdrop images, posters and
  `krema-shim.js` are copied, not hashed.
- A `themeLast()` Vite plugin moves `<link id="themeSkin">` after the bundled
  stylesheet in the emitted `<head>`, because Vite injects its CSS link at the
  end and the theme must win the cascade. A harness assertion pins the order.
- A `previewBridge()` Vite plugin, active only in `serve`, serves `/preview/*`
  from the repo and injects the two classic mock scripts before `</head>`, so
  `npm run dev` gets HMR with the same fixtures and scenarios.
- **Drift guard.** `npm run check:dist` rebuilds and runs
  `scripts/check-dist.mjs`, which fails if
  `git status --porcelain -- src/main/resources/movie-page` is non-empty. A
  checked-in `.githooks/pre-commit` (enabled by `npm run prepare`, which sets
  `core.hooksPath`) runs it when `frontend/` or the output is staged. There is
  no CI, so AGENTS.md also states the rule: rebuild before committing frontend
  changes.
- `mvn compile exec:exec` and `mvn test` are untouched and require no Node.

Scripts:

```
dev          vite
build        vite build
build:debug  vite build --mode debug --outDir dist-debug --sourcemap
typecheck    tsc --noEmit
lint         eslint .
format       prettier --write .
test         vitest run
check        node preview/check.js
check:react  npm run build && node preview/check.js --page=frontend/dist
check:dist   npm run build && node scripts/check-dist.mjs
prepare      git config core.hooksPath .githooks
verify       npm run typecheck && npm run lint && npm run test && npm run build && npm run check
```

### Bridge layer

`bridge/bridge.ts` is the only module that touches `window.krema`:

```ts
export function invoke<K extends CommandName>(
  command: K,
  args: CommandArgs<K>
): Promise<CommandReply<K>>;

export function on<K extends EventName>(
  event: K,
  handler: (payload: EventPayload<K>) => void
): void;

export function bridgeReady(): boolean;
```

- `bridge/types.ts` declares `Movie`, `Game`, `Song`, `Book`, `MovieDetail`,
  `GameDetail`, `SongDetail`, `BookDetail`, the four data/detail replies, the
  LLM and theme replies, the artwork and detail-art payloads, and the
  `"pending" | "done" | "error" | "superseded"` status. Nullable JSON fields
  are typed `T | null`, not `T`.
- Command and event maps make a wrong command name or argument a compile error:
  `requestMovies`/`pollMovieData`/`pollMoviePosters`, the game, music
  (`requestGeniusMusic`/`pollGeniusMusicData`/`pollGeniusMusicPosters`) and
  book (`requestBooks`/`pollBookData`/`pollBookCovers`) rows, the four detail
  triples, `loadLlmSettings`/`saveLlmSettings`, `loadTheme`/`saveTheme`.
- `on` registers and returns nothing (Krema has no unsubscribe). Subscriptions
  are page-lifetime, as today.
- `bridgeReady()` replaces the scattered `window.krema` checks; a missing
  bridge paints the current "API bridge not ready yet" error.
- The bridge wrapper casts the untyped `window.krema.invoke` once. No other
  module uses `any` or `window.krema` directly.

### Store and state model

The current design keeps one live state plus a `sessions` snapshot map that is
copied on leave and restore. React makes a simpler model possible: **the
sessions map is the store**. Every form always has a session; switching forms
just changes which one is active. No copy/restore step, no chance of forgetting
to snapshot.

```ts
type DomainKey = "movie" | "music" | "book" | "game";
type QueryKind = "trending" | "search" | "recommend";
type ViewKind = "browse" | "detail" | "settings";

interface BrowseState<I> {
  status: "idle" | "loading" | "ready" | "error";
  title: string;
  items: I[];
  error: string | null;
  query: string;
  kind: QueryKind;
  artwork: Record<string, string>;   // id -> data URI
}

interface DetailState<D> {
  status: "idle" | "loading" | "ready" | "error";
  item: unknown;                     // the clicked card, for the loading shell
  detail: D | null;
  art: Record<string, string>;       // slot -> data URI
  error: string | null;
}

interface Session<I, D> {
  browse: BrowseState<I>;
  detail: DetailState<D> | null;
  view: "browse" | "detail";
  scrollY: number;
}

interface AppState {
  activeDomain: DomainKey;
  view: ViewKind;                    // "settings" is global, not per session
  ai: boolean;
  sessions: Record<DomainKey, Session<unknown, unknown>>;
  settingsReturn: "browse" | "detail";
}
```

- `store.ts` is a ~40-line observable store (`getState`, `setState`,
  `subscribe`) with `useStore(selector)` built on `useSyncExternalStore`. No
  state library.
- `actions.ts` holds the named transitions: `activateDomain`, `startBrowse`,
  `finishBrowse`, `failBrowse`, `setBrowseArtwork`, `openDetail`,
  `finishDetail`, `setDetailArt`, `backToBrowse`, `openSettings`,
  `closeSettings`, `toggleAi`. Async work lives in hooks and calls actions.
- **In-flight work is cancelled on leave, then resumed on return — the current
  behavior, kept deliberately.** `useContent` is mounted only for the active
  form. Unmount cancels the data poller and the poster drain. Leaving a form
  that was still loading cancels it; returning re-fires the request. Leaving a
  ready form keeps its cards and artwork; returning resumes the poster drain
  (which answers −1 when complete) with **no new content request**. This is what
  the harness asserts: one generation per sidebar click, and no generation on a
  session restore.
- `useContent` captures a run token; every poll continuation checks it before
  dispatching. `useDetail` does the same with a separate token, so opening a
  detail never supersedes the browse request and vice versa. The backend's own
  `superseded` reply is still handled.
- `useArtwork` is four App-level subscriptions (page lifetime, like today's
  `subscribeArtwork`) and only records into the **active** session's
  `browse.artwork`; a late event for a form the user already left is ignored,
  matching today's `state.domain` guard. The subscription is passive — the
  drain is driven by `useContent`, not by the event handler.
- `useDetail` is mounted only while a detail is active. Unmount cancels its
  poll. Returning to a ready detail resumes its art drain (`useDetailArt`),
  because the drain is idempotent and answers −1 when complete; a detail that
  was loading or errored is re-requested through the normal detail path.
- `useDetailArt(domain, detail)` polls the art drain while a ready detail is
  mounted, writing `slot -> dataUri` into `detail.art`. A detail hidden behind
  Settings stays mounted, so its stream can finish behind the page, as today.
- Sessions persist in page memory only. Closing the app clears them.
- The song detail carries `artDataUri` in its reply and needs no art drain.

### Domain registry

`bridge/domains.ts` replaces `DOMAINS`, `openDetail`, and the per-view paint
dispatch. One row per form:

```ts
interface DomainDescriptor<I, D> {
  key: DomainKey;
  view: "movie" | "song" | "book" | "game";
  label: string; plural: string; emptyLabel: string;
  request: CommandName; data: CommandName; art: CommandName;
  event: EventName;
  detailRequest?: CommandName; detailPoll?: CommandName; detailArt?: CommandName;
  idField: keyof I & string;
  Card: ComponentType<{ item: I; index: number; artwork?: string; onOpen(): void }>;
  Detail: ComponentType<{ session: Session<I, D>; onBack(): void }>;
}
```

- Movie and game share `PosterCard`; music uses the square `MusicCard`; book
  uses `BookCard`.
- Music's `view` is `"song"`; the registry maps it so no view code hardcodes
  the exception.
- A new content form is one row plus a card component plus a detail component.
  Nothing else knows the form exists.

### Components and behavior parity

- `App.tsx` renders the shell (`sky`, `veil`, `app-shell`, sidebar,
  main-column), the header, the always-present view containers
  (`#browseView`, `#songView`, `#movieView`, `#bookView`, `#gameView`,
  `#settingsView`, `#playerBar`) and toggles `.hidden` from the store. Keeping
  the containers and the class contract is what lets `check.js` survive the
  port; inner views mount only when they have data.
- `Sidebar` keeps the brand, collapse toggle, four form buttons, the inert
  Home / My Lists / Favorites items, the Menu/Library labels and the Settings
  item. `useNarrowSidebar` keeps `NARROW_WIDTH = 900`, the ResizeObserver and
  the "user chose, wins from now on" rule.
- `Header` keeps the search input, AI toggle and Search button. Search sends
  `trending` for an empty box, `recommend` when AI is on, `search` otherwise.
  AI stays a global toggle and survives form switches.
- `BrowseView` renders title, skeleton grid, empty/error state or the card
  grid. `CardGrid` owns the 5/4/3/2 ladder once, via Tailwind max-width
  variants; the 900px step stays tied to the sidebar constant.
- Cards keep the existing semantic classes (`.movie-card`, `.poster-img`,
  `.poster-placeholder`, `.card-title`, `.card-meta-item`, …) as stable hooks,
  with Tailwind utilities doing the styling. Artwork arrives as a prop from the
  session map, so no DOM querying and no `CSS.escape`. The
  `align-items: stretch` button-card fix belongs in the shared card/rail
  component.
- `SettingsView` has the Appearance card and the AI form. Chips are rendered
  from the `loadTheme` reply; the LLM form is controlled, paints the stored
  normalized values, never receives the key, keeps a blank key on save, and
  returns to the view Settings covered on Cancel/Escape. A sidebar form click
  while Settings is open activates that form and leaves Settings, as today.
  `useEscape` is shared with the detail views.
- The four detail views port their markup tile by tile: movie bento (poster,
  identity, facts, keywords + story, cast, stills, similar), book bento
  (cover, identity, record, description + similar, editions, author), song
  bento (cover, identity, details, lyrics + About, credits + related) plus the
  player, and game immersive (hero, stat band, record rail, article,
  screenshots + lightbox, platforms, related). Local UI state (watchlist,
  favorite, want-to-read, expanders, lightbox, player transport) lives in the
  component. Artwork slots read from `detail.art`; a missing slot keeps its
  placeholder.
- Back and Escape return to the intact grid. A similar/related card opens that
  item in the same view. The player bar is rendered from the active session's
  ready song detail and stops when the view leaves the song.

### Styling and themes

`styles/tailwind.css` imports the theme and utilities layers (not Preflight —
the app already has its own reset, and Preflight would change parity), then
maps the existing role tokens to semantic Tailwind names:

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/utilities.css" layer(utilities);

@theme {
  --color-canvas: var(--bg);
  --color-surface: var(--panel);
  --color-surface-hover: var(--panel-hover);
  --color-surface-deep: var(--panel-deep);
  --color-ink: var(--text);
  --color-ink-muted: var(--text-muted);
  --color-accent: var(--accent);
  --color-accent-ink: var(--accent-ink);
  --color-hairline: var(--border);
  --color-focus: var(--focus);
  --color-danger: var(--danger);
  --color-rating: var(--rating);
  --radius-card: var(--radius);
  --font-mono: var(--mono);
  --blur-glass: var(--blur-glass);
}
```

- Components use semantic utilities (`bg-surface`, `text-ink`,
  `border-hairline`, `text-accent`, `rounded-card`, `font-mono`). No raw hex,
  no theme-private names (`--neon-pink` stays inside Liquid Neon's sheet).
- `base.css` keeps only what utilities cannot express: the backdrop defaults,
  scrollbars, keyframes, reduced motion, and the global reset. The per-view
  sheets (`music-detail.css`, `movie-detail.css`, `book-detail.css`,
  `game-detail.css`, `settings.css`) are deleted as their components move to
  utilities. A component may keep a small scoped CSS block only for a
  `grid-template-areas` layout that would be unreadable as utilities.
- Theme files stay in `frontend/public/`, keep their names, and keep overriding
  the original role tokens (`--bg`, `--panel`, …). Because
  `--color-surface: var(--panel)` resolves at use time, a theme swap repaints
  every utility. Do not use `@theme inline` for role tokens, or the value is
  baked in and the skin stops following.
- Opacity modifiers (`bg-accent/10`) are allowed only on solid role colours;
  the translucent roles keep their existing alpha tokens for parity.
- The theme load order is pinned: bundled CSS first, `theme-<id>.css` last. The
  `themeLast()` Vite plugin enforces it in the build, and a harness assertion
  keeps it true.
- `ThemeProvider`/`useTheme` replaces `js/theme.js`: load on boot before the
  first content load, paint `data-theme` and `#themeSkin`, render chips from
  the reply, save on click, revert and report on failure. A theme is not form
  state, so it saves immediately.
- The Java registry (`ThemeConfig.KNOWN`), the `theme-<id>.css` convention and
  the config file are untouched. Adding a theme is still one CSS file plus one
  Java row (and the mock's mirror list).

### Preview harness

- `preview/serve.js` keeps its shape but learns `VANGOGH_PAGE_DIR` (and
  `preview/check.js` a `--page=<dir>` flag), so the harness can serve the
  staging build (`frontend/dist`) while the vanilla page stays shipped. The
  built `index.html` still has `</head>`, so injection works.
  `npm run check:react` points the harness at the staging build.
- `npm run dev` adds HMR through Vite with the same mock injection and
  scenarios (`?scenario=slow|empty|error|noposters`, `?theme=<id>`).
- `preview/check.js` stays the gate. The page keeps the IDs, key classes and
  `.hidden` toggling it asserts, so the 206 assertions remain meaningful. New
  assertions use `data-testid` rather than more styling classes.
- `window.__preview.stats()` and the mock's contracts are unchanged.
- `npm run verify` builds before it runs `check`, so the gate tests fresh
  output; after cutover `npm run check:dist` catches a bundle that was never
  rebuilt.

## Parts of the build

0. **Capture the baseline.** Before anything builds, add `preview/baseline.js`
   (same headless Chromium discovery as `check.js`) and record the current page:
   screenshots of browse (all four forms), Settings and the four detail views at
   1280px in both themes, plus computed-style/layout JSON for the selectors and
   properties `check.js` pins, at 1280/1180/900/768/600px in both themes. Store
   it under `preview/baselines/2026-11-23/` (JSON committed; screenshots
   committed, or kept out of git if size is a problem). This is the reference
   the port is checked against, and the reason the staging build exists.
1. **Scaffold and pipeline.** `frontend/` with Vite, React, strict TS, Tailwind
   v4, ESLint, Prettier, Vitest. `vite.config.ts` with the relative base,
   staging `outDir` (`frontend/dist` until cutover), `themeLast()` and
   `previewBridge()`. Generated banner. `preview/serve.js` learns
   `VANGOGH_PAGE_DIR`. Verify the shell loads a placeholder page in both shells
   and in the preview.
2. **Bridge and types.** `bridge/types.ts` mirroring every record and reply,
   `bridge.ts` with typed `invoke`/`on`, `domains.ts` descriptors. Unit tests
   for the command/event maps and the generation guard.
3. **Store and shell.** `store.ts`, `actions.ts`, `App`, `Sidebar`, `Header`,
   `useNarrowSidebar`, `useTheme`, `useEscape`. Browse trending renders through
   the existing mock.
4. **Browse.** `useContent`, `useArtwork`, `CardGrid`, skeletons, empty/error,
   the four cards, search, AI toggle, per-form sessions and scroll. Port the
   session assertions unchanged.
5. **Settings and theme.** `SettingsView`, `AppearanceCard`, `LlmForm`,
   provider chips, reveal, validation, save/load, theme chips and live skin
   swap.
6. **Song detail and player.** `useDetail`, the song bento tiles, credits
   expander, About, listen links, related rail, demo player.
7. **Movie, book and game details.** One commit each, tile by tile, with the
   art-slot streams.
8. **Cutover and cleanup.** Flip `build.outDir` to
   `../src/main/resources/movie-page`, set `emptyOutDir: true`, delete the
   vanilla `js/` tree and the per-view CSS, trim `base.css`, finalize the
   Tailwind conversion, update the harness selectors where needed, update
   AGENTS.md and the docs, rebuild and commit the output, and run the full
   verification.

Each commit keeps `npm run typecheck` and `npm run test` green. The harness is
fully green at the cutover commit; earlier commits exercise the ported views
through the preview.

## Decisions (calls made here)

- Build approach 1: Vite output committed into `src/main/resources/movie-page/`.
- Staging `outDir` (`frontend/dist`) during the port; the vanilla page stays the
  shipped app until the cutover commit flips `outDir` and deletes it.
- Vite 7 + React 19 + TypeScript strict + Tailwind v4 (`@tailwindcss/vite`).
- Hand-rolled observable store + `useSyncExternalStore`; no Zustand/Redux/
  TanStack Query.
- Sessions map is the store; no snapshot/copy step.
- In-flight browse and detail work cancels on leave and re-fires or resumes on
  return; artwork subscriptions are App-level and write only to the active
  session. This is the current behavior, kept for parity.
- Shipped build has no sourcemaps; `build:debug` writes them outside the
  committed output.
- Parity baseline captured before step 1; `check.js`'s exact computed values
  must match exactly.
- Keep the existing DOM contract (IDs, key classes, `.hidden`) through the
  cutover; `data-testid` for new assertions. Conditional rendering and a
  testid-only harness are a later slice.
- Theme sheets stay in `frontend/public/`, keep their role-token overrides,
  and load last. No `@theme inline` for role tokens.
- Preflight disabled; the app keeps its own reset.
- `clsx` + `tailwind-merge` behind one `cn()` helper.
- Hand-written bridge types; no codegen.
- npm (not pnpm/yarn); Node 22 pinned in `engines`.
- Java, IPC, commands, events and payloads are untouched.

## Risks and mitigations

- **Detail-view behavior drift** is the biggest risk (four views, ~2.3k lines of
  markup). Port tile by tile, keep the class names, and compare the preview
  before/after at both themes and the responsive steps.
- **Theme cascade** breaks if the bundled CSS lands after the theme sheet. The
  `themeLast()` plugin and a harness assertion pin the order; both themes are
  tested.
- **Stale committed output** is the cost of approach 1. `npm run verify` builds
  before testing, `npm run check:dist` fails on drift, and the checked-in
  pre-commit hook runs it when the output is staged; AGENTS.md states the rule
  too. The shipped build carries no sourcemaps, so the committed diff is the
  bundle only.
- **Polling races** can regress silently. Preserve the run-token guards and
  port the rapid-switch and superseded assertions.
- **Webview compatibility.** Tailwind v4's floor is met, but verify in Krema and
  JCEF, not just Chromium. No native selects.
- **Session memory** grows with what the user viewed; one session per form, as
  today. Replacing a search releases the old data.
- **Bundle size** is a non-issue locally, but production React and a single
  bundle keep startup fast.

## Verification

- `npm run verify` (typecheck, lint, unit tests, build, `preview/check.js`).
- `npm run check:dist` clean after a build.
- `mvn -q clean compile` and `mvn test` — Java untouched, must stay green.
- Preview: all scenarios, both themes, every detail view, sessions, settings,
  the responsive ladder, and the rapid-switch guard.
- Real app: Krema and JCEF — window loads, all four forms, search, AI toggle,
  Settings save/load, theme swap, all four details, player, sessions, back and
  Escape.
- **Parity gate.** Re-run `preview/baseline.js` against the React page and diff
  the JSON and screenshots against `preview/baselines/2026-11-23/`. Values that
  `check.js` asserts exactly (for example the neon skin's `rgb(0, 240, 255)`
  and its 20% fill) must match exactly; other computed properties may differ
  only within the tolerance the baseline script records. Screenshots at
  1280/1180/900/768/600px in both themes are the human review. "Looks the same"
  is not the gate where the harness pins a value.
- Offline check: no CDN or remote asset references in the built output; all
  asset paths relative.

## Delivery

- Branch `feat/react-tailwind-frontend`, cut from `main` after
  `feat/session-persistence` merges (the current branch has untracked theme and
  poster files that must land first).
- The vanilla page stays shipped on the branch; the React page is the staging
  build until the cutover commit. If another contributor needs frontend work
  while the branch is open, land it before the cutover or expect conflicts in
  the files being replaced.
- One PR for the slice, with the commits above. This is a single AGENTS.md
  "slice" (frontend framework migration); no product feature rides along.
- AGENTS.md (frontend modules, build/run, preview harness), `architecture.md`,
  `docs/preview-harness.md` and `docs/themes.md` are updated in the same PR.
  The vault copy of the project description is flagged, not edited silently.
- The generated output is committed; the drift check and pre-commit hook are
  documented in AGENTS.md.

## Settled with the user

- **Parity, not a refresh.** The rewrite keeps the current look; a visual pass
  is a separate slice.
- **No deadline pressure.** All four forms, Settings and all four detail views
  land before cutover; the reduced-scope fallback is not needed.
- **Sole contributor.** No parallel frontend work during the branch, so the
  staging build exists for reviewability and baseline comparison rather than to
  protect someone else's work.

## Open questions

- None blocking. Anything that surfaces during the port that would change the
  page's behavior or look gets raised before it is implemented.
