# Book detail prototypes (starry night theme)

Standalone HTML prototypes for the **Book detail view** — the page a card
opens after a click, replacing the current `alert()` placeholder in
`renderBooks()` (`src/main/resources/movie-page/app.js`).

They are **not wired to the app**: no Krema IPC, no backend, no build step.
They share the app's visual language (the Stargazer skin ported from
`src/main/resources/movie-page/style.css` — frosted midnight glass, gold
accents, `starry.png` behind a blurred veil) and its **Book Card** shape.

## How to view

Open `index.html` in any browser (double-click, or serve the folder with
`jwebserver -p 8641 -d docs/prototypes/book-detail`).

- `index.html` — the results grid: six real Open Library trending books as
  Book Cards. Clicking a card opens its detail page in the selected layout
  (A / B / C switch in the header; the choice persists in `localStorage`).
- Each detail page takes `?book=<workKey>` (e.g.
  `layout-open-page.html?book=OL1168083W`) and has the A/B/C switch in the
  header to compare layouts for the same book.

## The three layouts

| File | Idea | Distinctive pieces |
|---|---|---|
| `layout-open-page.html` | **A · Open Page** — editorial two-column hero | 3D-tilted cover with spine + page edges, serif title, gold-bordered pull-quote of the opening line, subject chips, Watchlist/Favorite actions, "About this record" fact grid |
| `layout-first-light.html` | **B · First Light** — quote-first, cinematic | Cover floats over a glow, the *first line* is the hero typography, facts compress into a bordered stat strip, centered actions |
| `layout-catalog-card.html` | **C · Catalog Card** — library-catalog reading | Sticky left rail (cover, stars, actions), ruled catalog sections with dotted-leader fact rows, a big-numbers **Editions** panel, subject cloud |

All three end with a "More from this trending week" row whose cards link
into the same layout for the other books — the detail-from-a-card-click loop.

## Sample data — real Open Library rows

`books-data.js` holds six rows **pulled verbatim** from

```
https://openlibrary.org/trending/weekly.json?limit=20&fields=
  key,title,author_name,first_publish_year,cover_i,first_sentence,subject,
  edition_count,number_of_pages_median,ratings_average
```

on 2026-11-17 — exactly the `WORK_FIELDS` list `OpenLibraryClient` requests.
Only `subject` / `first_sentence` arrays were truncated (to 10 / ~6 entries)
to keep the file small; the app renders the first 3 subjects anyway.

`toVanGoghBook()` mirrors `OpenLibraryBookService.toBook()` field-for-field:

- `key` → `workKey` (`/works/OL1168083W` → `OL1168083W`)
- first `author_name`, `first_publish_year`, `ratings_average`, `edition_count`, `number_of_pages_median` pass through
- `subject` → first **3** joined with ` · `
- `first_sentence` → the **ASCII-only (English) entry** (`pickEnglish`), else the first entry — 1984's row starts with the Catalan translation, and the prototypes pick the English line exactly like the service does
- `cover_i` → `https://covers.openlibrary.org/b/id/<i>-L.jpg`

Cover loading mirrors the app's failover: direct covers.openlibrary.org
first, then the `wsrv.nl` proxy (`coverSources()`), then the gradient
placeholder — so the prototypes behave the same on this machine, where
artwork hosts sometimes need the proxy.

The six books were chosen to exercise the data's real variety:

| Book | workKey | Exercises |
|---|---|---|
| Nineteen Eighty-Four | `OL1168083W` | non-English `first_sentence` pool, 727 editions |
| Pride and Prejudice | `OL66554W` | 4 042 editions (crowdsourced classics tower) |
| The Hunger Games | `OL5735363W` | single-entry `first_sentence` |
| Atomic Habits | `OL17930368W` | **no `first_sentence`** — the sparse-row fallback |
| Metamorphosis | `OL498556W` | short work (88 pp.) with multilingual subjects |
| Animal Farm | `OL1168007W` | English-first-of-many translations, 657 editions |

## Notes for the wiring slice

- The detail pages read only the fields the `Book` record carries today
  (`BookController.pollBookData` payload) — no extra API call is required
  to implement layout A or B. The descriptions/prose shown are composed
  from those same fields.
- Covers arrive as data URIs through the `bookCover` drain in the app; the
  prototypes hotlink the same URLs with the same failover chain.
- The Watchlist / Favorite buttons are visual only (My Lists is a separate
  slice). "Open in Open Library" links to the real work page.
