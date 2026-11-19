# Book detail prototypes (HTML)

Three static browser mock-ups of the page a Book card opens, following the
app's current Stargazer / Starry Night design (frosted midnight glass, gold
accents, starry backdrop, collapsible sidebar). Nothing here is wired to the
app: no `window.krema`, no IPC, no backend. The pages exist to compare layout
directions before the real detail-view slice starts.

| File | Layout | Sample record |
|---|---|---|
| `index.html` | Gallery / entry point | all samples |
| `v1-spotlight.html` | **01 Spotlight** — large cover, facts rail, quote panel | *The Left Hand of Darkness*, Ursula K. Le Guin |
| `v2-editorial.html` | **02 Editorial** — blurred cover atmosphere, sticky rail, magazine article | *Project Hail Mary*, Andy Weir |
| `v3-dossier.html` | **03 Dossier** — file header, numbered memo, record sheet | *Beloved*, Toni Morrison |

Shared: `book-detail.css` (tokens copied from
`src/main/resources/movie-page/style.css`), `icons.js` (Lucide icons inlined
for offline use), `assets/starry.png` (app backdrop) and
`assets/covers/*.jpg` (Open Library Covers).

## Run

Any static server rooted at the repository works. With a JDK 25 install:

```powershell
& "C:\Program Files\Java\latest\jdk-25\bin\jwebserver.exe" -p 8642 -b 127.0.0.1 -d "C:\Users\Hp\Documents\VanGogh-new"
```

Then open
`http://127.0.0.1:8642/docs/prototypes/book-detail/index.html`.

Fonts are linked from Google Fonts (Cormorant Garamond + Inter); offline the
pages fall back to Georgia / system UI.

## Data provenance

Sample values are real Open Library work records, fetched from
`search.json` with `fields=key,title,author_name,first_publish_year,ratings_average,subject,first_sentence,number_of_pages_median,edition_count,cover_i`.
Covers are downloaded from `covers.openlibrary.org/b/id/<cover_i>-L.jpg`.

The one-sentence blurbs in v1/v2 are prototype copy, **not** Open Library
descriptions — the app does not fetch a description field. Every number, name,
tag and opening line is from the source record.

Every field on the pages maps to the app's `Book` record
(`src/main/java/vangogh/book/Book.java`):

| Page value | `Book` field / source |
|---|---|
| Title, author | `title`, `author` |
| First published | `year` (`first_publish_year`) |
| Median pages | `pages` (`number_of_pages_median`) |
| Editions catalogued | `editionCount` (`edition_count`) |
| Rating / 5 | `rating` (`ratings_average`) |
| Subject chips | `subjects` (`subject`, first 3 on the card) |
| Opening line | `firstSentence` (`first_sentence`) |
| Work key | `workKey` (`key` without `/works/`) |
| Cover | `coverUrl` (`cover_i` → cover path) |

Deliberately **not** used: description, availability, edition list, language
counts — the app does not fetch them yet, so the detail page must not promise
them. Rating *vote counts* are shown on two prototypes for context even though
`Book` does not carry `ratings_count`; drop them or add the field when the
slice is built.

The subject chips are hand-picked from each work's real `subject` array (the
array has 25–50 tags and the card only takes the first three, which are often
`form:` / `award:` / `nyt:` noise). If the detail view wants full subject
display, the service should filter those prefixed tags.

### Known source snag to decide before implementation

`OpenLibraryBookService.pickEnglish` picks the **first ASCII-printable**
`first_sentence`, not the first English one. For *Project Hail Mary* that
selects the German line `"Was ist zwei plus zwei?"` (the English entry uses
curly quotes and is not ASCII). The prototype shows the English line, so the
eventual slice should either match `language == eng` on the edition or prefer
an ASCII variant containing the title-language marker — a product decision,
not a CSS one.

## Fidelity notes

- Shell, sidebar, header, cards, colors and the starry veil mirror the current
  app page; the detail content is new.
- Buttons, sidebar items and the search box are inert (prototype only).
- Layouts are fluid; the sidebar collapses at 1024 px and each layout stacks
  at 860 px.
- `prefers-reduced-motion` disables hover movement and transitions.
