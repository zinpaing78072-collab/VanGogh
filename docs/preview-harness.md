# UI preview harness

The real page, in a normal browser, with a fake IPC bridge. Refresh (or let HMR
do it) to see a UI edit — no `mvn`, no native window, no API keys, no poster
proxy.

Why it exists: the app is a system-webview desktop app, so the only way to look
at a UI change used to be `mvn compile exec:exec` and then squint at a native
window. That loop is minutes long, cannot be automated, and an agent cannot
observe its result at all. The page is a static bundle served over loopback
HTTP, so it can run anywhere that serves it. The page has exactly one shell
seam — `window.krema.invoke` and `window.krema.on` — and the harness fills that
seam with fixtures.

Nothing in `src/main/java` or in the app's own code is modified for this.

## Run it

Two loops, pick by what you changed:

```sh
cd frontend && npm run dev       # → http://localhost:5173 — React source, HMR
```

Vite serves `frontend/src` and injects `preview/fixtures.js` +
`preview/mock-krema.js` before `</head>`, exactly like `preview/serve.js` does
for the built page. This is the fast loop for components, styles and page
logic.

```sh
node preview/serve.js            # → http://127.0.0.1:8788 (the built page)
PORT=9000 node preview/serve.js
VANGOGH_PAGE_DIR=frontend/dist node preview/serve.js
```

Use the second form when you need to look at what actually ships, or at a
staging build (`npm run build:staging`). Or the Zed tasks **preview UI (browser,
mock IPC)** and **check UI (headless)**. Node 22 is installed; the harness
itself has no dependencies, but `npm run dev` needs `npm install` once in
`frontend/`.

| Path | Serves |
|---|---|
| `/` | redirects to the app, keeping `?scenario=` |
| `/movie-page/*` | `src/main/resources/movie-page/*` (the committed Vite output) |
| `/preview/*` | `preview/*` |

`index.html` is the only transformed response: two classic scripts are injected
before `</head>`, ahead of the module bundle, so the mock bridge is installed
before the app asks for it.

## Scenarios

`?scenario=` picks a state. These are the states that are hard or impossible to
reproduce on demand in the real app.

| URL | Shows |
|---|---|
| `/movie-page/index.html` | trending results, ~3 drain ticks of skeletons, artwork streaming |
| `?scenario=slow` | 40 pending ticks (~4 s of skeleton cards) |
| `?scenario=empty` | done reply with zero items — the empty state |
| `?scenario=error` | error reply — the red error state |
| `?scenario=noposters` | done, artwork never arrives — every placeholder visible |

The sidebar form buttons and the search box work in every scenario. Each form serves its own
fixtures, and a search that matches nothing falls through to the empty state.

## Themes

`?theme=<id>` starts the mock with that skin stored, exactly as if Settings had
saved it last session — the page loads `theme-starry.css` and swaps to the saved
sheet while the grid is still empty.

| URL | Shows |
|---|---|
| `/movie-page/index.html` | Starry Night (the default) |
| `?theme=liquidneon` | Liquid Neon |
| `?theme=vaporwave` | falls back to Starry Night, like `ThemeConfig.read` |

Switch skins live from the Settings page instead (Appearance card) — the mock
implements `loadTheme` / `saveTheme`, so the chips, the repaint and the "reopen
shows it pressed" path all work without the Java side.

## What the mock implements

Real contracts, not simplified ones. Pacing stays `pending → done`, which is why
skeletons and the staggered artwork stream are both exercised.

| Command | Reply |
|---|---|
| `requestMovies` / `requestGames` / `requestGeniusMusic` / `requestBooks` | request generation (kicks off a fetch) |
| `pollMovieData` / `pollGameData` / `pollGeniusMusicData` / `pollBookData` | `{ status: "pending" \| "done" \| "error" \| "superseded", items?, error? }` |
| `pollMoviePosters` / `pollGamePosters` / `pollGeniusMusicPosters` / `pollBookCovers` | remaining count, `-1` when done |
| `requestSongDetail` / `pollSongDetail` | song-detail generation, then `{ status, detail?, artDataUri?, error? }` |
| `requestMovieDetail` / `pollMovieDetail` / `pollMovieDetailArt` | movie-detail generation, then `{ status, detail?, error? }`; the art drain returns the remaining count, `-1` when done |
| `requestBookDetail` / `pollBookDetail` / `pollBookDetailArt` | book-detail generation, then `{ status, detail?, error? }`; the art drain returns the remaining count, `-1` when done |
| `requestGameDetail` / `pollGameDetail` / `pollGameDetailArt` | game-detail generation, then `{ status, detail?, error? }`; the art drain returns the remaining count, `-1` when done |
| `loadTheme` / `saveTheme` | `{ status, theme, themes: [{ id, label }] }` (the same registry `ThemeConfig.KNOWN` holds) |
| `on("poster" \| "gamePoster" \| "musicPoster" \| "bookCover")` | `{ payload: { id, dataUri }, timestamp }` |
| `on("movieDetailArt")` | `{ payload: { slot, dataUri }, timestamp }` — slots `poster`, `cast:<i>`, `still:<i>`, `similar:<id>` |
| `on("bookDetailArt")` | `{ payload: { slot, dataUri }, timestamp }` — slots `cover`, `author`, `edition:<i>`, `similar:<workKey>` |
| `on("gameDetailArt")` | `{ payload: { slot, dataUri }, timestamp }` — slots `boxart`, `hero`, `still:<i>`, `related:<id>` |

A superseded generation answers `superseded`, so the request-sequence guard in
`frontend/src/hooks/useContent.ts` is exercised rather than bypassed. An
unmocked command rejects with `preview: command not mocked: <name>` and logs a
warning — add it to the mock rather than letting it fail silently.

## Fixtures

`preview/fixtures.js`, keyed by form. Items are the **real resolved-item shapes**
the controllers hand the page, so they survive the LLM engine slice: the engine
will pick *titles*, and the content sources still resolve those into these same
card objects.

| Form | Fields used by the card |
|---|---|
| `movie` | `id`, `title`, `year` (or `release_date`), `rating`, `genre`, `overview` |
| `game` | `id`, `game_title` (or `title`), `year`, `rating`, `genre`, `overview` |
| `music` | `id` (MBID), `title`, `artist`, `year`, `primaryType`, `genre` |
| `book` | `workKey`, `title`, `author`, `year`, `rating`, `pages`, `editionCount`, `subjects`, `firstSentence` |

Artwork: movies reuse the real posters in `movie-page/posters/`; the other forms
get generated SVG gradient covers, coloured from a hash of the item id, so cards
are visually distinct instead of a wall of identical grey boxes.

`songDetail` holds the song-detail extras the Genius payload supplies — dummy
lyrics/duration/audio plus credits, listen links, the About paragraphs, the
language and translation count, the artwork palette and the community stats — so
the detail test exercises the real view rather than a lookalike.

`movieDetail` holds the film extras the TMDB payload supplies — tagline, runtime,
certification, keywords, the facts ledger, cast & crew, stills and the similar
rail. The mock composes the real `MovieDetail` shape from those plus the clicked
card, and generates one artwork data URI per slot, so the test drives the same
`movieDetailArt` stream the controller emits.

`bookDetail` does the same for Open Library — a per-work description, author
dates and bio, and a setting — because the card fixtures only carry what the
grid shows. The mock composes the real `BookDetail` shape (record ledger,
editions, author card, similar works) from those plus the clicked book, and
streams `cover`, `author`, `edition:<i>` and `similar:<workKey>` data URIs. One
edition deliberately has no cover, so the page's placeholder fallback stays
exercised.

`gameDetail` holds the extras TheGamesDB supplies beyond the boxart card —
release date, age rating, player count, genres, platforms, publishers and
developers, the overview, the screenshot list and the related-games rail. The
mock composes the real `GameDetail` shape from those plus the clicked game and
generates one artwork data URI per slot, so the test drives the same `boxart`,
`hero`, `still:<i>` and `related:<id>` stream the controller emits. One game
deliberately has no entry, so the sparse-record path (placeholder boxart,
dropped platform and screenshot sections) stays exercised.

The fixtures deliberately include the cases CSS breaks on first: a long title, an
unbreakable 55-character word, a null rating, an empty artist, a book with no
page count, and a movie with no artwork (`The Shawshank Redemption`, id 278) so
the placeholder path stays visible.

## Using it as an agent

This is the point of the harness. A verification loop that used to need a human
looking at a native window is now:

1. edit a component, `frontend/src/styles/base.css`, a `theme-<id>.css` sheet in
   `frontend/public/`, or anything under `frontend/src/`
2. let Vite HMR repaint (`npm run dev`), or `npm run build` + `browser.reload`
3. `browser.snapshot` / `browser.evaluate` for structure, `browser.screenshot` for looks
4. `browser.console` with `level: "error"` — the mock logs every request and
   subscription with a `[preview]` prefix, so a wiring mistake is visible

Hop across builds instantly instead of rebuilding:

```
http://127.0.0.1:8788/?scenario=empty
http://127.0.0.1:8788/?scenario=error
http://127.0.0.1:8788/?scenario=noposters
```

`window.__preview.stats()` reports what the page actually consumed:

```js
{ scenario: "trending", form: "movie", generation: 1,
  dataPolls: 4, delivered: 6, artworkEmitted: 5,
  theme: "starry", themeLoads: 1, themeSaves: 0 }
```

Settled-state assertions worth copying:

```js
// every card that should have artwork got it, and nothing 404'd
[...document.querySelectorAll('#movieGrid .poster-img')]
  .filter(i => i.getAttribute('src') && i.complete && i.naturalWidth === 0).length  // 0
// no card title overflows its box
[...document.querySelectorAll('#movieGrid .card-title')]
  .filter(e => e.scrollHeight > e.clientHeight + 2).length                          // 0
```

Music cards open the song-detail view (`components/detail/song/SongDetailView.tsx`),
movie cards open the movie-detail view (`components/detail/movie/MovieDetailView.tsx`),
book cards open the book-detail view (`components/detail/book/BookDetailView.tsx`)
and game cards open the game-detail view
(`components/detail/game/GameDetailView.tsx`) through `openDetail`, so no dialog
blocks the page.

Those checks are codified in `preview/check.js` (see below), so prefer running
it over pasting the snippets by hand.

## Automated checks

```sh
node preview/check.js                          # pass/fail, exit 1 on any failure
node preview/check.js --verbose                # also prints each test's preview stats
node preview/check.js --page=frontend/dist     # a staging build instead
```

`cd frontend && npm run verify` runs typecheck, lint, Vitest, a fresh build and
then this gate against the shipped output.

225 assertions across 24 groups: trending render, the sidebar (four form buttons,
inert placeholder items, section labels, collapse and card reflow, and the
active marker), the switcher across all four forms, search hit and miss, the AI
toggle (off by default, `kind=recommend` when on, survives a form switch, empty
box falls back to trending), the per-form sessions (a search survives switching
away and back with its query and posters and no new request, and an open movie
detail — every artwork slot included — survives both a form switch and a
Settings round-trip, and scroll positions come back while a fresh form starts
at the top), the song, movie, book and game detail views (each
bento/immersive tile, every streamed artwork slot, actions, Escape back to the
grid; the movie detail also opens its trailer in an in-page YouTube embed that
covers the shell at 16:9, and Escape closes the player without leaving the
detail; the book detail opens its Internet Archive sample in an in-page reader
overlay that covers the shell and fits the viewport, and Escape closes the
reader without leaving the detail), the profile screen, the Settings
form (opens from the sidebar, paints
the loaded config, fills the base URL from a preset, refuses a save with no key,
saves and clears the field, keeps the stored key off the page and on a second
blank-key save, toggles Reveal without reading the key, and fires no browse
request), the themes (the Appearance chips come from the reply, Starry Night is
pressed by default, picking Liquid Neon repaints shell + cards + AI toggle and
saves exactly once, reopening shows it pressed, a stored theme is painted at
launch, an unknown stored id falls back), the responsive ladder (each step's
column count from 1280px down to 600px, no card text escaping at any step, all
three other card shapes at both ends of the ladder, and Liquid Neon stepping in
lockstep with Starry Night), the
empty / error / no-posters / slow scenarios, and a rapid form-switch that
exercises the supersede guard. Exit code 1 on any failure, so it can gate a
commit.

The responsive groups drive `Emulation.setDeviceMetricsOverride` through
`page.resize(width, height)` and read the grid back with `measureGrid(page,
width)`, which returns the column count, the first card's width, and the three
overflow numbers. Two things make it trustworthy: it waits for the sidebar rail
to stop animating (below 900px `hooks/useNarrowSidebar.ts` collapses it, and the
card widths are meaningless mid-transition), and it answers "does text fit" by
checking text boxes against the card box rather than trusting `scrollWidth`.

Two timing traps the theme tests document, because both look like a broken skin:
the swapped `<link>` loads asynchronously (wait for the new sheet to appear in
`document.styleSheets` before reading computed styles), and the controls
transition their colours, so the first style recalc after the swap returns the
*old* colour — force a recalc, then let the 0.2 s fade finish.

It is self-contained: it boots the preview server on an ephemeral port, drives a
headless Chromium over the DevTools protocol, and shuts both down. No npm
packages — Node 22 ships `fetch` and `WebSocket`, which is all a CDP client
needs.

Browser discovery order: `$CHROME`, then the usual macOS/Linux install paths,
then the Playwright and Puppeteer caches (so a machine that already runs browser
tests needs nothing installed). Set `CHROME=/path/to/binary` to force one.

Each assertion is a `check(label, ok, detail)` in a test's `run(page)`. The
`page` helper gives `settle()`, `stats()`, `dom()`, `selectForm()`, `search()`,
`evaluate()`, and `resize(w, h)` / `resetViewport()` for the breakpoint
ladder. To pin new behaviour, add a test object to `TESTS`.

Two things worth knowing before you write assertions:

- **Page console errors fail the test automatically.** A wiring mistake shows up
  as a failure without an explicit assertion.
- **`settle()` waits on `__preview.stats()`**, not on a timer: it returns when
  the data drain reported `done` (or `error`) *and* the artwork drain reported
  −1. That is why the checks are not flaky on a slow machine.

Verify the gate still bites after editing it: point one entry in `MOVIE_ART` at a
nonexistent file in `mock-krema.js` and three checks should fail. If they do not,
the artwork assertions have gone dead.

## What it does not cover

The harness fakes the bridge, so it says nothing about the bridge. These still
need a real run of the app:

- IPC threading and drain delivery (the Windows `engine.eval` constraint)
- the JVM/native shell: window creation and `-XstartOnFirstThread`
- the TMDB poster proxy and its failover, real API responses and rate limits
- anything in `src/main/java` — 16 test classes cover that side with stubs

Rule of thumb: **layout, styling and page-side code go through the preview;
shell, IPC and network code go through the app.**
