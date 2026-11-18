---
title: "Frontend architecture — domain-driven page module"
date: 2026-11-17
project: "[[VanGogh]]"
tags: [frontend, architecture, design]
status: accepted
---

# Frontend architecture — domain-driven page module

## Intent

Four content forms are wired, but the page is one 506-line `app.js` closure in
which each domain repeats the same wiring: an event listener, a poster setter,
an empty state, a render function, a card builder, and a detail action. The
`DOMAINS` table covers only the IPC names. Upcoming work — detail views, My
Lists, My Searches, and one query returning four forms — needs seams the
current shape does not have.

This slice is behavior-preserving except for one real bug: a completed fetch
for one domain can render using the domain selected *after* the fetch started.

## Goals

- One `DOMAINS` descriptor per content form as the single per-domain contract:
  IPC commands, artwork event, card class, id field, artwork shape, labels,
  card builder, detail builder.
- One page state object and one `render()` entry point for the browse view.
- One artwork subscriber and one artwork setter instead of four copies.
- Stale-request protection in the front end: a reply or poster drain belonging
  to a superseded domain/query must not touch the page.
- No changes to the `window.krema` contract, the shells, or the CSS.

## Non-goals

- No new views (detail page, My Lists, My Searches), router, or framework.
- No build step, bundler, or ES modules.
- No CSS renames (`.movie-card` stays).
- No backend/command/event renames. The existing asymmetry
  (`pollMusicPosters`/`musicPoster` vs `pollBookCovers`/`bookCover`) is absorbed
  by the descriptor rather than fixed.
- No change to polling cadence, card markup, or visual design.

## Constraints

- Classic scripts only: the JCEF shell loads the page over `file://`, where ES
  modules are blocked. `index.html` keeps a single `<script src="app.js">`;
  the JCEF shim injects itself before that exact tag.
- `app.js` remains a single file. Splitting it requires a namespace or module
  system and adds load-order rules for no immediate payoff; revisit when a
  second view lands.
- Java controllers, tests, and `style.css` are untouched.

## Design

### State

```js
const state = {
    view: "browse",   // future views dispatch here; only "browse" exists now
    domain: "movie",
    title: "Trending Movies",
    status: "idle",   // "idle" | "loading" | "ready" | "error"
    items: [],
    error: null,
};
```

`requestSeq` (a monotonic counter) lives beside the state. Every load captures
its sequence; every asynchronous continuation checks it before touching state
or the DOM.

### Domain descriptor

```js
DOMAINS = {
  movie: {
    request, data, art, event,        // IPC names
    plural, emptyLabel,               // headings and empty state
    cardClass, artClass, idField,     // markup
    card(item, idx) -> HTMLElement,
    detail(item) -> { title, body },
  },
  ... game, music, book
}
```

Derived from the key: `data-<key>-id` selector and `<key>Id` dataset. Movie
and game share `posterCard`; music and book keep their own card builders.
Existing CSS classes and markup are preserved exactly.

### Render seam

`render()` dispatches on `state.view`. `renderBrowse()` is the only view and
selects one of: skeleton grid (loading), empty/error grid, or cards (ready).
Cards are built through `d.card`, so `renderContent`'s book/music special cases
and the movie/game fallthrough disappear.

### Artwork routing

Register one `window.krema.on(d.event, ...)` per descriptor. A single
`setArtwork(key, id, dataUri)` finds the card via
`[data-<key>-id="..."]` with `CSS.escape`, then reveals the poster and hides
the placeholder. The four `window.set*Poster` globals become internal (no
callers outside `app.js`).

### Request lifecycle

`loadContent(domainKey, kind, term)`:

1. bump `requestSeq`, set `state` to loading, `render()`;
2. invoke the kickoff command;
3. poll the data drain while `seq === requestSeq`;
4. if stale or superseded, stop silently;
5. on error, set `state.status = "error"` and render only if still current;
6. on done, set items/title/status and render, then drain artwork with the same
   current-check.

This fixes the current line that calls `renderContent(reply.items, selectedContent)`
with the live selection instead of the domain that was requested.

### Detail seam

Card clicks call `openDetail(domainKey, item)`, which reads
`d.detail(item)` and shows it with `alert` for now. The detail-view slice
replaces this one function instead of touching four card builders.

### Small fixes folded in

- Alert text is no longer HTML-escaped (alerts are plain text; today `&` shows
  as `&amp;`).
- Error text is escaped once at render (today the transport message is escaped
  and then escaped again).
- The dead "not connected" filter branch is removed — all four options are
  wired.

## Approaches considered

1. **Dedupe poster wiring only.** Smallest diff, but leaves the render dispatch,
   state, and stale-render bug in place.
2. **Domain descriptor + state/render seam in one classic script.** Chosen:
   removes the per-domain copies, fixes the race, and prepares the view seam
   without tooling.
3. **ES modules and file split now.** Rejected: `file://` in JCEF needs a
   Chromium flag or a bundler, and the size does not justify it yet.

## Verification

- `node --check src/main/resources/movie-page/app.js`.
- `mvn -q test` (no Java changes; confirms nothing else broke).
- Throwaway mock harness outside the repo: the real `index.html`, `app.js`,
  and `style.css` with a fake `window.krema` that serves canned rows and emits
  poster events. Exercise: initial trending, each of the four filters, search,
  and a rapid Movie → Music switch while the movie reply is delayed (the album
  grid must never receive movie rows). Not committed.
- Launch `mvn compile exec:exec` and visually check the four filters, search,
  skeletons, and artwork.

## Delivery

- Branch `feat/frontend-architecture`, stacked on `feat/music-domain` because
  this slice rewrites the file the music slice changed. Retarget/merge after
  the music PR.
- One commit for the refactor (design doc may ride with it or land first).
