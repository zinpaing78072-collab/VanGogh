# Favorites — static prototypes

Four standalone directions for the **Favorites** screen, drawn in the
**Liquid Neon** skin. They exist to make design decisions cheaper before the
real React slice is built.

- **No backend, no IPC, no build step.** Classic `<script>` tags, plain CSS and
  a sample library in `data.js`.
- **One feature slice.** Nothing here imports or changes `frontend/` or
  `src/main/java/`; the pages are review artifacts under `docs/`.
- **Same shell as the app.** The rail, sticky search header, AI toggle, role
  tokens, card ladder and artwork mirrors the current React shell so a
  direction can graduate into `frontend/src/components/` without a redesign.

Start at [`index.html`](index.html) — it links all four variants, the state
demos and a feature matrix.

## Run it

Any static file server rooted at this folder (or at the repo root) works. The
pages also open directly from `file://` — there is no `fetch`, so no CORS
setup is needed.

```sh
# from the repo root, with a JDK 25 install
jwebserver -p 8642 -b 127.0.0.1 -d .
# then open http://127.0.0.1:8642/docs/prototypes/favorites/index.html
```

```sh
# or with Python
python -m http.server 8642
```

Use a real server when checking the sidebar's narrow behaviour and the sticky
header; `file://` works but some browsers treat it as a secure context
differently.

## The four directions

| # | File | Thesis | What is interactive |
|---|------|--------|---------------------|
| 01 | `v1-shelf.html` | **The default candidate.** One mixed shelf for all four forms — the closest thing to a shipping screen. | Form tabs, vibe chips, search, sort menu, grid/list density, bulk select with move/remove/undo, peek drawer, empty and loading states. |
| 02 | `v2-constellation.html` | **Curation first.** Favorites filed into named collections, with Unfiled as a first-class tray. | Drag a card between lanes, collection tiles and sticky index that jump to a lane, live counts, collection blurbs. |
| 03 | `v3-log.html` | **Memory first.** Favorites as a dated feed — what you kept, when, and why. | Domain filter, note/title search, saved-date groups, per-item notes, taste snapshot (form mix, top vibes, decades, signal strength). |
| 04 | `v4-mixer.html` | **Favorites feed the engine.** The product promise made visible. | Pick up to eight seeds, live taste profile, domain filter for the seed pool, Surprise me, Clear, simulated generation of four composition briefs. |

All four share `favorites.css` (tokens, shell, cards, rows, menus, peek, toasts,
responsive ladder) and `favorites.js` (`window.VG`: icons, card/row markup,
filtering, sorting, date grouping, peek drawer, toast + undo, shell init).

### State demos

- `v1-shelf.html?state=empty` — first run, zero favorites. The header copy
  switches to "Nothing saved yet" and the empty panel explains where favorites
  come from.
- `v1-shelf.html?state=loading` — skeleton cards for 1.6 s, then the library.
- Filtering/searching to nothing on Shelf or Signal Log shows the filtered-empty
  panel; Shelf's primary CTA resets form, vibe and query.
- Narrow widths (≤ 900 px) auto-collapse the rail, matching
  `NARROW_WIDTH = 900` in the app; the card ladder is 5 / 4 / 3 / 2 columns at
  1180 / 900 / 768 px, as in `base.css`.

## Data and artwork

`data.js` defines **35 favorites** (12 movies, 7 music, 8 books, 8 games) and
**4 collections** (Neon Noir, Late-Night Reads, Focus Loop, Long Haul). Every
record uses the fields the real cards already show (`title`, `sub`, `year`,
`meta`, `rating`, `img`) plus the library layer these pages are exploring:
`note`, `tags`, `saved` and `collection`.

- **Artwork is real.** Movie posters come from the app's committed
  `src/main/resources/movie-page/posters/` set; album covers, book covers and
  game boxart are the files the earlier music/book/game detail prototypes used.
  They are copied into `assets/` so the folder is self-contained.
- **Library metadata is invented.** Notes, tags, collection membership, saved
  dates, ratings where the source had none, and every number in the taste
  panels are plausible sample copy, not live API data.
- **"Today" is fixed at 2026-11-25.** `favorites.js` sets `TODAY` so the
  relative labels ("Today", "Yesterday", "2h ago") and the six date groups stay
  reproducible. Change the constant (and the sample dates) to demo another day.
- **Page memory only.** Removing, undoing and refiling mutate `window.FAVORITES`
  in the tab; a reload restores the sample library. The undo action in the toast
  restores items at their original index.

## Interaction notes

- Cards are keyboard reachable (`Tab`, `Enter` / `Space` opens the peek drawer);
  `Escape` closes the peek.
- The peek drawer, toast and menus are plain DOM, not native dialogs or
  `<select>` elements — the same constraint the app has on macOS.
- Live regions: `aria-live="polite"` on the grid, `role="status"` on the bulk
  bar, labelled icon buttons, `aria-pressed` on toggles and chips.
- `prefers-reduced-motion` disables hover lifts and the brand-mark pulse.
- The AI toggle and header search are shell chrome here: the toggle changes its
  pressed state and the form explains that search is not wired.

## What is intentionally missing

- No SQLite, no persistence, no accounts, no My Lists / My Searches pages.
- No model call. The Mixer's four briefs are hand-written copy per form; the
  "compose" delay is a 1.2 s timer.
- No real detail views. Peek's "Open detail" is a stub; the real pages already
  exist per form and would be the navigation target.
- No image proxy, no network failure states beyond the skeleton demo.

## Open questions for review

1. Does Favorites default to the **mixed shelf** (v1) or to **collections**
   (v2)?
2. Are **notes** part of the save flow, or a later "why did I keep this"
   affordance?
3. Does the **Mixer** live on Favorites, in the AI search panel, or in its own
   destination?
4. Should **saved dates** be visible on cards, or only in the log view?
5. Are **tags** user-authored, engine-suggested, or derived from the item?

## File map

```
docs/prototypes/favorites/
├── index.html              gallery, feature matrix, state links
├── favorites.css           tokens, shell, cards, collections, log, mixer, responsive ladder
├── favorites.js            shared window.VG helpers + shell behaviour
├── data.js                 sample library + collections
├── v1-shelf.html           variant 01 + page script
├── v2-constellation.html   variant 02 + drag/drop script
├── v3-log.html             variant 03 + snapshot script
├── v4-mixer.html           variant 04 + seed/generation script
└── assets/                 liquidneon backdrop, movie/music/book/game artwork
```
