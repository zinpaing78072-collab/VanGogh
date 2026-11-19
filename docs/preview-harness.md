# UI preview harness

The real page, in a normal browser, with a fake IPC bridge. Refresh to see a CSS
or JS edit — no `mvn`, no native window, no API keys, no poster proxy.

Why it exists: the app is a system-webview desktop app, so the only way to look
at a UI change used to be `mvn compile exec:exec` and then squint at a native
window. That loop is minutes long, cannot be automated, and an agent cannot
observe its result at all. Everything under `src/main/resources/movie-page/`
is plain ES modules, so it can run anywhere that serves it over HTTP. The page
has exactly one shell seam — `window.krema.invoke` and `window.krema.on` — and
`krema-shim.js` already self-disables when a bridge is present
(`krema-shim.js:22`). The harness fills that seam with fixtures.

Nothing in `src/main/java` or in the app's own JS is modified for this.

## Run it

```sh
node preview/serve.js           # → http://127.0.0.1:8788
PORT=9000 node preview/serve.js
```

Or the Zed task **preview UI (browser, mock IPC)**. Node 22 is already installed;
there are no dependencies and no build step.

| Path | Serves |
|---|---|
| `/` | redirects to the app, keeping `?scenario=` |
| `/movie-page/*` | `src/main/resources/movie-page/*` (the real files) |
| `/preview/*` | `preview/*` |

`index.html` is the only transformed response: two classic scripts are injected
before `</head>`, ahead of the deferred module entry point, so the mock bridge
always wins the race against `krema-shim.js`.

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

## What the mock implements

Real contracts, not simplified ones. Pacing stays `pending → done`, which is why
skeletons and the staggered artwork stream are both exercised.

| Command | Reply |
|---|---|
| `requestMovies` / `requestGames` / `requestMusic` / `requestBooks` | request generation (kicks off a fetch) |
| `pollMovieData` / `pollGameData` / `pollMusicData` / `pollBookData` | `{ status: "pending" \| "done" \| "error" \| "superseded", items?, error? }` |
| `pollMoviePosters` / `pollGamePosters` / `pollMusicPosters` / `pollBookCovers` | remaining count, `-1` when done |
| `on("poster" \| "gamePoster" \| "musicPoster" \| "bookCover")` | `{ payload: { id, dataUri }, timestamp }` |

A superseded generation answers `superseded`, so the `requestSeq` guard in
`js/state.js` is exercised rather than bypassed. An unmocked command rejects
with `preview: command not mocked: <name>` and logs a warning — add it to the
mock rather than letting it fail silently.

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

The fixtures deliberately include the cases CSS breaks on first: a long title, an
unbreakable 55-character word, a null rating, an empty artist, a book with no
page count, and a movie with no artwork (`The Shawshank Redemption`, id 278) so
the placeholder path stays visible.

## Using it as an agent

This is the point of the harness. A verification loop that used to need a human
looking at a native window is now:

1. edit `style.css` or a `js/` module
2. `browser.reload`
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
  dataPolls: 4, delivered: 6, artworkEmitted: 5 }
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

Note that `openDetail` in `js/detail.js` still calls `alert()`, which blocks the
page in an automated tab. Dismiss it with `browser.dialog`, or read `DOMAINS`
directly when the detail-view slice replaces that seam.

Those checks are codified in `preview/check.js` (see below), so prefer running
it over pasting the snippets by hand.

## Automated checks

```sh
node preview/check.js            # pass/fail, exit 1 on any failure
node preview/check.js --verbose  # also prints each test's preview stats
```

59 assertions across 10 groups: trending render, the sidebar (four form buttons,
inert placeholder items, section labels, collapse and card reflow, and the
active marker), the switcher across all four forms, search hit and miss, the AI
toggle (off by default, `kind=recommend` when on, survives a form switch, empty
box falls back to trending), the empty / error / no-posters / slow scenarios,
and a rapid form-switch that exercises the supersede guard. Exit code 1 on any
failure, so it can gate a commit.

It is self-contained: it boots the preview server on an ephemeral port, drives a
headless Chromium over the DevTools protocol, and shuts both down. No npm
packages — Node 22 ships `fetch` and `WebSocket`, which is all a CDP client
needs.

Browser discovery order: `$CHROME`, then the usual macOS/Linux install paths,
then the Playwright and Puppeteer caches (so a machine that already runs browser
tests needs nothing installed). Set `CHROME=/path/to/binary` to force one.

Each assertion is a `check(label, ok, detail)` in a test's `run(page)`. The
`page` helper gives `settle()`, `stats()`, `dom()`, `selectForm()`, `search()`
and `evaluate()`. To pin new behaviour, add a test object to `TESTS`.

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
- the JMV/native shell: window creation, `-XstartOnFirstThread`, the JCEF
  native-menu freeze
- the TMDB poster proxy and its failover, real API responses and rate limits
- anything in `src/main/java` — 16 test classes cover that side with stubs

Rule of thumb: **layout, styling and page-side JS go through the preview; shell,
IPC and network code go through the app.**
