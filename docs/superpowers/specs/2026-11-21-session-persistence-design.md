---
title: "Per-form sessions that survive navigation"
date: 2026-11-21
project: "[[VanGogh]]"
tags: [frontend, navigation, state, design]
status: draft
---

# Per-form sessions that survive navigation

## Intent

Today the page holds exactly one browse result set. `loadContent()` clears
`state.items` and reloads trending whenever the user switches content form
(`js/ui.js`), opens Settings, or runs a new search. So Movies → Music → Movies
comes back as Trending Movies, not the query and cards the user left. Opening
Settings from a detail view loses the detail the same way.

This slice makes the last thing opened in each content form survive navigation
for as long as the app stays open: one live session per form (Movies, Music,
Books, Games), including an open detail view. Closing the app still starts
fresh — this is page memory, not storage.

Success looks like: search "Inception" in Movies, switch to Music, switch back
— the same six cards, the same query in the search box, the same scroll
position, no network request. Leave a movie detail open, visit Books, come
back — the detail is still there with its poster, cast, stills and similar
rail.

## Goals

- One saved session per content form: browse state (title, cards, status,
  query, artwork) or the form's open detail view.
- Switching to a form with a session restores it with **no IPC call** and no
  refetch; switching to a form with no session loads trending as today.
- Restored cards keep their posters; restored detail views keep every artwork
  slot (poster, cast, stills, similar, cover, author, editions, boxart,
  fanart, screenshots).
- Restore the search box to the form's saved query (empty for trending), so
  the box and the grid agree again.
- Restore the page scroll position for the form.
- Opening Settings and returning preserves the form state — including an open
  detail — instead of resetting to the grid.
- The preview harness proves the behavior; existing checks keep passing.

## Non-goals

- **Surviving an app restart.** No disk, no SQLite, no `localStorage`. Sessions
  are page memory only.
- A search history or My Searches. One session per form, replaced by the next
  search in that form.
- Per-form AI toggle. `state.ai` stays the single global toggle it is today.
- Restoring in-flight work as if it never stopped. A browse request that was
  still loading is re-run on return; a detail that was loading or failed is
  re-requested.
- Player position, scroll position inside a detail's own sub-rails, or any
  other widget-local state.
- Any Java, IPC, command or event change.

## Anti-goals

- Keeping detached DOM per form (approach B). It preserves artwork for free
  but turns `render.js` from "state in, DOM out" into a tab switcher and
  multiplies the render/empty/skeleton paths.
- Replaying poster data URIs from Java (approach C). Four controllers gain
  commands, and the services' bounded poster caches can no longer supply every
  image anyway.
- Refetching a session on restore. That reintroduces the network dependency
  and, for AI sessions, would produce a fresh recommendation set — the exact
  thing the session exists to prevent.
- Duplicating session state in a second store (e.g. `localStorage` mirrored
  into `state`). The snapshot map is the only extra state.

## Constraints

- Both shells (Krema system webview, JCEF) must behave the same; this is all
  page-side, so no shell work is expected.
- `render.js` stays the only painter of the browse view; detail modules keep
  painting themselves.
- Imports stay acyclic: `content.js` may import `render.js`, `artwork.js`,
  `detail.js` and `DOMAINS`; detail modules may import `state.js`; nothing in
  the session path imports `ui.js`.
- A native `<select>` remains forbidden (macOS NSMenu trap).
- The 900px sidebar auto-collapse and the card grid must be unaffected.

## Approaches

- **A. Per-form snapshot in page state (chosen).** A `sessions` map beside
  `state`; on leaving a form, copy its live state (view, title, status, items,
  query, artwork, detail, scroll) into `sessions[domain]`; on entering, copy it
  back and render. Artwork data URIs move into state as they arrive, so a
  repaint is instant and offline.
- **B. One detached DOM grid per form.** Artwork and scroll survive with no
  copying, but the renderer, skeletons, error/empty states and preview
  assertions all assume one grid. Rejected: structural change for no benefit
  over A.
- **C. Metadata in the page, artwork replayed by Java.** Keeps image bytes out
  of JS, but adds IPC surface to four controllers and cannot guarantee the
  images are still cached. Rejected as overbuilt for a presentation build.

## Decisions

- **Session unit is the content form**, keyed by the `DOMAINS` key. There is no
  global session stack and no history.
- **A snapshot holds** `{ view, title, status, items, error, query, kind,
  artwork, detail, scrollY }`.
  - `view` is `"browse"` or the form's detail view name.
  - `detail` is the live detail state object (`state.movie`, `state.song`,
    `state.book`, `state.game`) including its `art` slot map.
  - `artwork` is the browse card map `id → dataUri`.
- **Artwork lives in state from now on.** `js/artwork.js` records each
  `{id, dataUri}` in `state.artwork` before painting the card; detail modules
  record each slot in `state[view].art` before painting. `renderCards()` and
  each `paintXView()` reapply the maps after building markup. This is what
  makes a restore, and a Settings round-trip, instant.
- **Snapshot on leaving a form**: at the top of `loadContent()` (before it
  mutates state) and when Settings opens. The active form's live state is the
  session; nothing is saved while staying in it.
- **Restore on entering a form**: if `sessions[key]` exists, copy it into
  `state` and `render()`; reapply artwork; restore `scrollY` on the next
  animation frame (clamped). If the saved view is a detail that was ready,
  paint it; if it was loading or errored, call `openDetail(key, detail)` to
  request it again.
- **A browse snapshot that was still loading** is restored by re-running
  `loadContent(key, kind, query)` — the superseded fetch is gone, so pretending
  otherwise would leave a permanent skeleton.
- **Clicking the already-active form is a no-op.** It cannot restore anything
  the live state does not already show, and reloading trending would throw the
  session away. Back/Escape remain the way out of a detail.
- **Settings is a round-trip, not a reset.** `openSettings()` snapshots the
  active form, then covers it; Cancel/Escape restores the view it covered (grid
  or detail). In-flight work is left running behind the page — the detail paint
  functions ignore a paint while `state.view === "settings"`, which fixes the
  existing latent paint-over and lets the artwork stream finish into state.
- **Domain rows gain a `view` field** (`movie` → `"movie"`, `music` →
  `"song"`, `book` → `"book"`, `game` → `"game"`), so the session code never
  hardcodes the mapping. One line per row, per the registry contract.
- **Startup is unchanged**: Trending Movies. It becomes the Movies session.
- **New searches replace that form's session.** The old snapshot is dropped
  when the new one is saved, releasing its image data.
- **Memory is bounded by what the user viewed**, one snapshot per form, and is
  released on replacement. No artificial cap in this slice.
- **Detail artwork maps are per state object**, not global: `state.movie.art`,
  `state.book.art`, `state.game.art`; songs already carry `artDataUri` in
  `state.song`, so they need no map.

## Parts of the build

- `js/state.js` — `sessions` map; `state.query`, `state.kind`,
  `state.artwork`; `art` maps on detail state objects.
- `js/content.js` — `rememberCurrent()`, `restoreSession(key)`, snapshot/restore
  around `loadContent()`, scroll save/restore.
- `js/ui.js` — sidebar click restores a session instead of always reloading
  trending; active-form click is a no-op.
- `js/artwork.js` — record data URIs in `state.artwork`.
- `js/domains/{movie,music,book,game}.js` — `view` field.
- `js/movie-detail.js`, `js/book-detail.js`, `js/game-detail.js` — record art
  slots in state and reapply them in `paintXView()`.
- `js/settings.js` — snapshot on open, restore on Cancel/Escape.
- `preview/check.js` — a session test: search, switch away and back (no new
  generation, same cards, query and artwork), plus a detail-open round trip.
- `AGENTS.md` (frontend modules) — note the session behavior.

## Verification

- `node preview/check.js` — the new session checks plus every existing check
  (all four forms, details, Settings, themes, scenarios).
- `mvn -q clean compile` and `mvn test` — unchanged Java, must stay green.
- One real app run (`mvn compile exec:exec`): search → switch → return, and
  detail → Settings → return, in the native window.

## Open questions

None blocking. The active-form no-op and the Settings round-trip are calls made
here; both are listed above so they can be challenged before implementation.
