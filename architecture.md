---
status: active
project: van-gogh
type: kb
tags: [architecture, content-domains, ipc, java, krema]
---

# Architecture — New Content Domains

## Purpose

Defines how VanGogh adds new content domains (Music, Book, Game, TV, …) beyond the existing Movie domain, while reusing the established Krema IPC, service, and frontend patterns.

## Current state

- **Movie domain is fully wired**: `MovieController.requestMovies(kind, query)`, `TmdbMovieService`, `TmdbClient`, TMDB API, poster proxy/cache, frontend card grid.
- **Game domain is wired**: `GameController.requestGames(kind, query)`, `TgdbGameService`, `TgdbClient`, TheGamesDB, and front-boxart streaming.
- **Book domain is wired**: `BookController.requestBooks(kind, query)`, `OpenLibraryBookService`, `OpenLibraryClient`, Open Library (search + weekly trending, no API key), and cover streaming via the `pollBookCovers` drain / `"bookCover"` events. The Book Card follows the design system's (`design-system.pen`): poster-card layout with a `year  Author · N pages` meta row; the click detail is book-specific (author, pages, editions, subjects, first line).
- **Sidebar switcher** in `src/main/resources/movie-page/index.html` offers Music / Movies / Books / Games as separate buttons; all four call their APIs. The same sidebar is collapsible (cards reflow into the freed width), `Menu` and `Library` are section labels, and Home / My Lists / Favorites / Settings plus the header notification/profile buttons are placeholders with no backend yet.
- **IPC contract**: `requestMovies` / `requestGames` / `requestBooks` kick off an IO-pool fetch and return the request generation immediately; card data arrives through the `pollMovieData` / `pollGameData` / `pollBookData` drains and artwork through the `pollMoviePosters` / `pollGamePosters` / `pollBookCovers` drains (see AGENTS.md — the Windows Krema engine.eval constraint makes every page-facing command a synchronous UI-thread drain).
- **Build rules**: `pom.xml` compiles with `--release 25` and `<parameters>true</parameters>` (required so `@KremaCommand` arguments bind by parameter name). Run with `JAVA_HOME=jdk-25 mvn compile exec:exec` (never `exec:java` on macOS).

## Guiding principles

1. **One `@KremaCommand` per domain action** — search, trending, detail. Keep command names explicit (`searchMovies`, `searchMusic`, …) or use a single dispatcher; decide once (see Open decisions).
2. **Service layer per domain** — `TmdbMovieService` → `MusicService`, `BookService`, `GameService`; each constructed with an injected client so tests stub the network.
3. **Client per upstream API** — `TmdbClient` talks TMDB; new clients wrap Open Library (books), MusicBrainz/iTunes (music), RAWG/IGDB (games), etc. No real network calls in unit tests.
4. **Reuse the record + poster pattern** — `Movie` record → domain records (`MusicTrack`, `Book`, `Game`); movie posters use `"poster"` events, while game box art uses `"gamePoster"` to keep the separate controllers' commands and events unambiguous.
5. **Generation counter for stale-render protection** — same `AtomicInteger` pattern as `MovieController` so a slow fetch never overwrites a newer render.
6. **`<parameters>true</parameters>`** — mandatory in `maven-compiler-plugin` for IPC arg binding by name.
7. **Stub-first testing** — inject stub clients via package-private constructors; assert dispatch, mapping, and event emission without touching the network.

## Java package layout (proposed)

```
vangogh/
  app/          VanGoghApp          — Krema window builder, registers all controllers
  controller/   MovieController     — existing
                  MusicController   — new
                  BookController    — new
                  GameController    — new
  movie/        Movie               — existing record
                  TmdbMovieService  — existing
                  TmdbClient        — existing (TMDB API + wsrv.nl poster proxy)
  music/        MusicTrack          — new record
                  MusicService      — new
                  MusicClient       — new (MusicBrainz / iTunes / …)
  book/         Book                — new record
                  BookService       — new
                  BookClient        — new (Open Library / …)
  game/         Game                — new record
                  GameService       — new
                  GameClient        — new (RAWG / IGDB / …)
  net/          ApiResponse         — shared (statusCode, body, error, isSuccess())
```

## IPC contract per domain

Java handler (example, Music):

```java
package vangogh.controller;

import build.krema.core.KremaCommand;
import build.krema.core.event.EventEmitter;
import vangogh.music.MusicTrack;
import vangogh.music.MusicService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MusicController {

    private volatile EventEmitter events;

    public void setEventEmitter(EventEmitter events) {
        this.events = events;
    }

    @KremaCommand
    public CompletableFuture<List<MusicTrack>> searchMusic(String query) {
        CompletableFuture<List<MusicTrack>> future = new CompletableFuture<>();
        io.submit(() -> {
            List<MusicTrack> tracks = service.searchMusic(query);
            future.complete(tracks);
            streamArt(tracks, generation.incrementAndGet());
        });
        return future;
    }
}
```

JS caller:

```js
const tracks = await window.krema.invoke("searchMusic", { query });
window.krema.on("poster", data => {
    if (data && data.payload) {
        window.setPoster(data.payload.id, data.payload.dataUri);
    }
});
```

Registration in `VanGoghApp.main()`:

```java
MovieController movie = new MovieController();
MusicController music = new MusicController();
// …
Krema.app()
    .title("Van Gogh")
    .identifier("van-gogh.krema")
    .size(1200, 800)
    .events(movie::setEventEmitter)   // one emitter shared, or per-controller
    .commands(movie, music, book, game)  // Krema registers all @KremaCommand methods
    .run();
```

## Frontend integration

- `frontend/` — the React + TypeScript source. `vite build` writes the shipped
  page into `src/main/resources/movie-page/` (committed), which both shells serve
  over loopback HTTP. `mvn` never runs Node.
- `src/main/resources/movie-page/index.html` — the built shell: a `flex` row,
  a collapsible `aside.sidebar` (brand, menu, `.nav-item[data-domain]` form
  buttons, Settings at the foot) plus the content column (search header, card
  grid). A native `<select>` is avoided on purpose (its macOS popup freezes the
  window). It loads `krema-shim.js` plus the hashed module bundle.
- `frontend/src/bridge/` is the only place the IPC contract lives:
  `domains.ts` holds one `DomainIpc` row per form (request/data/art/event names,
  the detail request/poll/art triple, id field), `commands.ts` switches on the
  row to call the typed `invoke`, and `types.ts` mirrors the Java records.
- `frontend/src/app/` holds the store (the sessions map) and the named
  transitions; `frontend/src/hooks/` runs the request → data drain → artwork
  drain lifecycle behind a request-sequence guard, plus the detail drains.
- Cards carry `data-<domain>-id`; artwork arrives per domain via
  `window.krema.on(<domain event>, { payload: { id, dataUri } })` and lands in
  the session's `artwork` map through `hooks/useArtwork.ts`.
- Reuse `lib/format.ts` (`messageOf`, `secureLink`), the placeholder fallback in
  `components/cards/CardShell.tsx`, and the skeleton/empty states in
  `components/browse/BrowseView.tsx`.

## Adding a domain (checklist)

1. **Record** — `vangogh/<domain>/<DomainItem>.java` (id, title, year, rating, genre, overview, posterUrl, plus domain-specific fields).
2. **Client** — `vangogh/<domain>/<Domain>Client.java` wrapping the public API; injectable constructor, no hard-coded network in tests.
3. **Service** — `vangogh/<domain>/<Domain>Service.java` with search/trending + poster data-URI cache (mirror `TmdbMovieService.posterCache`, capped at 512).
4. **Controller** — `vangogh/controller/<Domain>Controller.java` with `@KremaCommand` methods, `setEventEmitter`, IO thread pool, generation counter.
5. **Register** — add controller instance to `.commands(...)` in `VanGoghApp.main()`.
6. **Frontend** — add a `DomainIpc` row to `frontend/src/bridge/domains.ts`, a card in `frontend/src/components/cards/`, a detail view under `frontend/src/components/detail/<domain>/`, and the form button (`data-domain="<domain>"`) in `components/shell/Sidebar.tsx`. Nothing else needs to know the form exists. Then `npm run build` and commit the output.
7. **Tests** — stub client + controller test following `MovieControllerTest` / `TmdbClientTest` patterns.

## Example: Music domain (sketch)

```java
package vangogh.music;

public record MusicTrack(
        int id,
        String title,
        String artist,
        int year,
        double rating,
        String genre,
        String overview,
        String posterUrl
) {}
```

Controller command:

```java
@KremaCommand
public CompletableFuture<List<MusicTrack>> searchMusic(String query) { … }
```

Frontend:

```ts
const items = await invoke("requestMusic", { kind, query });
// poll the data drain, then listen for "poster" events for cover art
```

## Shared concerns

| Concern | Current pattern | Extension guidance |
|---|---|---|
| Poster proxy/cache | `TmdbClient` tries direct + wsrv.nl, remembers preference in `~/.van-gogh/poster-proxy.conf`; `TmdbMovieService.posterCache` capped at 512 | Generalize into a shared `PosterClient` or duplicate per client; keep the file-based preference |
| API keys | `System.getenv("TMDB_API_KEY")` + built-in fallback (security warning in code) | Each domain reads its own env var; surface key status in a settings panel |
| Errors | `ApiResponse.isSuccess()`; frontend `showError(message)` | Reuse `ApiResponse`; domain services throw `RuntimeException` on non-2xx |
| Threading | `Executors.newFixedThreadPool(6)`, daemon threads named `van-gogh-io` | Reuse or per-domain pools; all blocking IO off the JavaFX/webview thread |
| Stale renders | `AtomicInteger generation` in controller; poster pushes check `generation.get() == gen` | Copy the pattern into every domain controller |

## Open decisions

- **Single dispatcher vs per-domain commands**: one `searchContent(kind, query)` that routes internally, vs explicit `searchMusic`, `searchBooks`, `searchGames`. Explicit commands are easier to type and document; a dispatcher reduces surface area.
- **Shared record vs per-domain records**: a common `ContentItem` interface/record simplifies the grid, but domains have different fields (artist vs author vs developer). Per-domain records with a shared renderer is the safer first step.
- **`"poster"` event domain field**: add `domain` to the poster payload so the frontend can route art to the right grid, or keep it domain-agnostic since grids are single-domain at a time.
- **Frontend asset layout**: keep one `movie-page/` shared page vs `music-page/`, `book-page/`, etc. A single page with a domain param is simpler.

## Related files

- `AGENTS.md` — IPC contract, build/run rules, Krema 0.3.2, macOS launch flags
- `src/main/java/vangogh/controller/MovieController.java` — reference controller implementation
- `src/main/java/vangogh/controller/BookController.java` — newest controller (copy-paste its drain skeleton for Music)
- `src/main/java/vangogh/movie/TmdbMovieService.java` — service + poster cache pattern
- `src/main/java/vangogh/book/OpenLibraryBookService.java` — Either spine over a second upstream API
- `src/main/java/vangogh/net/TmdbClient.java` — client + proxy pattern
- `src/main/java/vangogh/net/OpenLibraryClient.java` — keyless client + cover failover
- `src/main/resources/movie-page/index.html` — the built app shell (generated; edit `frontend/index.html` / the React shell instead)
- `frontend/src/` — the frontend source (bridge contract, store, hooks, views); `src/main/resources/movie-page/` is its committed build output
- `src/test/java/vangogh/controller/MovieControllerTest.java` — test pattern with stubbed client/emitter
- Vendored Krema source (read-only reference): `repos/krema/krema/krema-core/` — `KremaCommand`, `EventEmitter`, `KremaEvent`

