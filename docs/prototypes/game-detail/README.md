# Game detail prototypes (HTML)

Three static browser mock-ups of the page a Game card opens, following the
app's current Stargazer / Starry Night design (frosted midnight glass, gold
accents, starry backdrop, collapsible sidebar). Nothing here is wired to the
app: no `window.krema`, no IPC, no backend. The pages exist to compare layout
directions before the real game detail-view slice starts.

| File | Layout | Sample record |
|---|---|---|
| `index.html` | Gallery / entry point | all samples |
| `v1-spotlight.html` | **01 Spotlight** — boxart, ESRB plate, overview, six-shot gallery | *Elden Ring* (TheGamesDB 65101) |
| `v2-immersive.html` | **02 Immersive** — fanart hero, stat band, sticky rail, field report | *Cyberpunk 2077* (TheGamesDB 14517) |
| `v3-dossier.html` | **03 Dossier** — file header, ESRB stamp, numbered memos, specs, contact sheet | *Alan Wake II* (TheGamesDB 114899) |

Shared: `game-detail.css` (tokens copied from
`src/main/resources/movie-page/style.css`), `icons.js` (Lucide icons inlined
for offline use), `prototype.js` (sidebar collapse, screenshot lightbox,
read-more and save-button state), `assets/starry.png` (app backdrop),
`assets/boxart/*.jpg` (TheGamesDB front boxart) and `assets/art/*` (TheGamesDB
fanart and screenshots).

## Run

Any static server rooted at the repository works. With a JDK 25 install:

```powershell
& "C:\Program Files\Java\latest\jdk-25\bin\jwebserver.exe" -p 8642 -b 127.0.0.1 -d "C:\Users\Hp\Documents\VanGogh-new"
```

Then open
`http://127.0.0.1:8642/docs/prototypes/game-detail/index.html`.

Fonts are linked from Google Fonts (Cormorant Garamond + Inter); offline the
pages fall back to Georgia / system UI.

## Data provenance

Every sample value is a real TheGamesDB game record, fetched with the same
request the app uses:

```
/v1/Games/ByGameID?apikey=…&id=139623,65101,…&fields=players,publishers,genres,overview,rating,platform&include=boxart,platform
```

Images come from `cdn.thegamesdb.net/images/original/` (fetched through the
app's `wsrv.nl` poster proxy on this machine), and genre names come from
`/v1/Genres`. The eight records used across the gallery are:
Elden Ring, Cyberpunk 2077, Alan Wake II, Yakuza 0, Subnautica,
Forza Horizon 6, 007 First Light, God of War Ragnarök.

Every field on the pages maps to the app's `Game` record
(`src/main/java/vangogh/game/Game.java`):

| Page value | `Game` field / source |
|---|---|
| Title | `game_title` |
| Release date | `release_date` (ISO date; the page formats it) |
| ESRB rating | `rating` (string, e.g. `M - Mature 17+`) |
| Genre chips | `genre` (joined genre labels, ` · `) |
| Overview / premises / specs | `overview` |
| Boxart / fanart / screenshots | `posterUrl` (front boxart); fanart and screenshots are extra `Games/Images` records |
| TheGamesDB game ID | `id` |
| Players, publishers, platform names | API fields `players`, `publishers`, `platform` — **not on `Game` today** |
| Boxart / fanart / screenshot IDs | `include.boxart` and `Games/Images` — **not on `Game` today** |

### Source snags to decide before implementation

1. **`rating` is not numeric.** The API returns strings like
   `M - Mature 17+`, `AO - Adult Only 18+`, `T - Teen` or `Not Rated`.
   `TgdbGameService.toGame` currently hard-codes `double rating = 0.0` because
   the old parser expected a score. A game detail page should either change
   `Game.rating` to `String` (and rename it to something like `ageRating`) or
   add a separate string field; showing `0.0 / 5` would be wrong. The
   prototypes use the ESRB stamp and never a star score.
2. **No developer field.** The payload has publishers, not developers. The
   pages say "Publisher" and do not invent a studio credit.
3. **No store, price, playtime or review data.** TheGamesDB does not provide
   them here, so the layouts leave those slots out rather than mock them.
4. **Overview doubles as marketing copy and system requirements.** For
   *Alan Wake II* the overview embeds three PC presets; the dossier renders
   them as a structured technical record. The source GPU name `GeForce RT 3070`
   is corrected to `RTX 3070`.
5. **Platform lists are sparse.** Most records carry one platform ID (usually
   PC), so v2 shows a single platform tile instead of a fake console spread.
6. **Rating values are unverified.** `AO - Adult Only 18+` for *Alan Wake II*
   is what the record says, not what a rating board necessarily assigned.

### Extra data the production slice would need

To ship the richer sections the prototypes show, add to the fetch pipeline:

- `players` (int) — already requested in `fields`.
- `publishers` (list of names, resolved from `/v1/Publishers`).
- `platforms` (list of names, resolved from `include.platform`).
- `ageRating` (string) — replace or supplement `rating`.
- `screenshots` / `fanart` (list of URLs) — one extra `Games/Images` call per
  opened game, or an on-demand detail request like `requestSongDetail`.

The existing `TgdbGameService` already asks for `players`, `publishers`,
`platform` and `include=platform`; it simply discards those fields when it
builds `Game`.

## Fidelity notes

- Shell, sidebar, header, search bar, glass panels, gold accents and the starry
  veil mirror the current app page; the detail content is new.
- The sidebar collapses, screenshots open in a lightbox, overviews can clamp,
  and the save/favorite buttons toggle locally. Search, nav items and the
  notification/profile buttons are inert (prototype only).
- Layouts are fluid; the sidebar collapses at 1024 px and each layout stacks at
  860 px.
- `prefers-reduced-motion` disables hover movement and transitions.
