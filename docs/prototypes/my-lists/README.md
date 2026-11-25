# My Lists — design prototypes

Five static browser mock-ups of the **My Lists** destination, painted in the app's
**Liquid Neon** skin. They exist to compare layout directions and put possible
features on screen before the real slice starts.

These pages are **not wired to the app**: no IPC, no backend, no storage. There is
no `window.krema` call anywhere under this folder. The whole state is the sample
library in `data.js`, and a reload resets it.

- Entry page: [`index.html`](index.html) — the gallery, with the decided model and
  the speculative list.
- Branch: `design/my-lists-prototypes` (kept off `main`; `main` stays green).

## The five directions

| Page | Direction | What it tests |
|---|---|---|
| [`v1-shelf.html`](v1-shelf.html) | **01 · Filter-first — The Shelf** | The recommended core: one card grid for all four forms, list + form filters, library search, sort, per-card favorite/remove. |
| [`v2-ledger.html`](v2-ledger.html) | **02 · Management-first — The Ledger** | The dense read: smart views with counts, a sortable table, row selection that raises a bulk-action bar, compact density. |
| [`v3-studio.html`](v3-studio.html) | **03 · Insight-first — The Studio** | The library as a lens on taste: bento stats, favorites wall, watchlist queue, week timeline, "draft from my taste" CTA. |
| [`v4-board.html`](v4-board.html) | **04 · Tracking-first — The Board** (speculative) | A third axis on top of saving: Want to / In progress / Finished lanes, drag or arrow to move, Favorites stays a star. |
| [`v5-queue.html`](v5-queue.html) | **05 · Status-first — The Queue** (speculative, revision of 04) | The board's statuses folded into one list: a status dropdown replaces the three lanes, and horizontal rows keep full-size artwork in its own shape. |

`v1-shelf.html?view=favorites` opens the shelf pre-filtered to Favorites (the
sidebar Favorites link uses it).

## Run it

The pages are plain HTML/CSS/JS and need no build. Any static file server works;
serve the **repository root** so the pages keep their `/docs/prototypes/my-lists/`
URL shape.

```sh
# JDK is already on PATH for this project
jwebserver -p 8789 -b 127.0.0.1
# then open http://127.0.0.1:8789/docs/prototypes/my-lists/
```

`python -m http.server 8789` works the same way. Opening `index.html` directly
over `file://` also renders (all scripts are classic and all artwork is local),
but a server is the closer match to how the app serves its page.

## Decided vs speculative

**Decided** (AGENTS.md, 2026-11-10 — do not re-litigate):

- One library across Movies, Music, Books, Games.
- Watchlist + Favorites, saved from any card.
- Favorites feed the AI engine.
- Detail opens from every card, everywhere.
- My Searches is a separate destination, not a My Lists item.
- SQLite is the storage answer from day one.

**Speculative** (proposals only, parked unless the requirements land):

- Progress statuses — Want to / In progress / Finished (v4, and the status pills
  in v2).
- Custom named lists beyond Watchlist + Favorites (the v2 "New list" button is a
  toast, not a flow).
- Bulk export and arbitrary column sorting (v2).
- Saved dates, personal notes, "why I saved it" (`saved` and `note` in the data).
- Continue-where-you-left-off progress in percent (v4 bars).
- Draft-from-library as a first-class AI action (v3 CTA).

## What each page actually does

**01 Shelf** — list segment (All / Watchlist / Favorites), form chips with counts,
text filter over title + creator + meta, sort menu (newest, oldest, title, form),
favorite toggle and remove on each card, live summary counts, empty state, keyboard
activation on cards. Mixed artwork is normalized to a 3:4 frame with
`object-fit: cover` so a row of posters stays even (the CSS comment explains why).

**02 Ledger** — smart views (All, Watchlist, Favorites, Recent, one per form) with
counts, search, sortable columns with direction arrows, select-all with
indeterminate state, bulk Favorite / Watchlist / Remove, density toggle, row-level
favorite and remove, export/new-list toasts.

**03 Studio** — taste bars by form, four stat tiles, watchlist queue (5 newest),
continue rail (in-progress items), favorites wall (8 newest), week timeline
(6 newest saves), favorite buttons on the queue, and the Draft button: it disables,
writes a "composing…" line, then resolves to a fresh draft summary and becomes
"Re-run" — the demo moment, faked with a timeout.

**04 Board** — three lanes with live counts, form filter, Favorites-only toggle,
drag-and-drop between lanes (plus an arrow button that advances one status),
progress bars on in-progress cards, and status-aware toasts. Tracking is the
speculative part; the saving model is unchanged.

**05 Queue** — a review revision of the board, added after the first round of
feedback. The three lane containers are gone: a status dropdown (All saved /
Want to / In progress / Finished, each with a count) filters the same library in
place, so there is one list instead of three columns. Rows are horizontal with
full-size artwork in its natural shape — a 2:3 poster for films, books and games
and a 1:1 cover for albums, centered — instead of the board's tiny shared
thumbnail. Each row keeps favorite, advance-status and remove actions, and the
page keeps the form chips and Favorites-only toggle.

Nothing persists across reloads, and no page mutates another page's state.

## Sample data and artwork provenance

`data.js` holds 29 saved items shaped like the app's resolved records — 9 movies,
8 music, 8 books, 4 games; 18 on the Watchlist, 15 in Favorites, dates spread over
late November 2026. Each record carries the extra fields a saved-item table would
need: `lists`, `saved`, `status`, `progress`, `note`.

Artwork under `assets/` is **real source artwork downloaded once** so the pages
render offline, with no keys and no runtime network:

- Movies — TMDB posters, fetched through the app's `wsrv.nl` proxy (the same route
  the app uses because `image.tmdb.org` is unreachable from this machine).
- Music — Cover Art Archive album covers.
- Books — Open Library covers.
- Games — TheGamesDB boxart.

Movies and games were resized to 700 px wide to keep the folder light; books and
albums are near their source size. The files are checked in on purpose — the
prototypes must render for a reviewer who never runs the app.

## CSS provenance

The shell is not a re-implementation: it reuses the app's own sheets so the pages
are pixel-identical to Liquid Neon.

| File | Copied from | Rule |
|---|---|---|
| `base.css` | `frontend/src/styles/base.css` | Structure + role tokens. |
| `theme-liquidneon.css` | `frontend/public/theme-liquidneon.css` | Theme, loaded **last** in every page so it wins the cascade. |
| `liquidneon.png` | `frontend/public/liquidneon.png` | The theme backdrop. |

Re-copy these three when the app's structure or the Liquid Neon theme changes;
this folder is a snapshot, not a second source of truth. `my-lists.css` is the only
new stylesheet and holds just the My Lists components (toolbar, cards, ledger,
bento, board, toast, responsive rules). The responsive grid ladder matches the app
(5 columns, 4 ≤1180 px, 3 ≤900 px, 2 ≤768 px) and the sidebar auto-collapses at
900 px, the same boundary as `useNarrowSidebar`.

## Implementation seam

A real slice needs a library store and one command pair that follows the Windows
rule: synchronous handlers on the UI thread, IO-pool work behind a generation,
page polling for the drain. The four rendered cards already exist; a saved item
adds `lists`, `savedAt`, and an identity the detail request understands. The
suggested shape is the one `data.js` already uses:

```js
{ id, form, title, creator, year, meta, rating, art, lists, saved, status, progress, note }
```

`id` must be the source's own key (TMDB id, Genius song id, Open Library work key,
TheGamesDB id) so a card can open its detail view without a second lookup. Lists
are a join table in SQLite (`item_id`, `list`, `saved_at`), which is also what lets
Favorites feed the AI engine without duplicating the item row.

## Files

```
docs/prototypes/my-lists/
├── index.html              gallery / entry page
├── v1-shelf.html           filter-first grid
├── v2-ledger.html          table + bulk management
├── v3-studio.html          bento taste profile
├── v4-board.html           status lanes (speculative)
├── v5-queue.html           status dropdown + horizontal rows (revision of 04)
├── data.js                 29-item sample library
├── prototype.js            shell + all page interactions
├── my-lists.css            new components and responsive rules
├── base.css                copied app structure CSS
├── theme-liquidneon.css    copied Liquid Neon theme (loaded last)
├── liquidneon.png          copied theme backdrop
└── assets/                 movies, music, books, games artwork
```
