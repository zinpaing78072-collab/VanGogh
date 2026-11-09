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
- **Filter dropdown** in `src/main/resources/movie-page/index.html` offers Movie / Music / Book / Game, but only Movie calls a real API; the others render a stub "not yet connected" message.
- **IPC contract**: `await window.krema.invoke("requestMovies", { kind, query })` → `CompletableFuture<List<Movie>>`; posters stream as `"poster"` events via `EventEmitter`.
- **Build rules**: `pom.xml` compiles with `--release 25` and `<parameters>true</parameters>` (required so `@KremaCommand` arguments bind by parameter name). Run with `JAVA_HOME=jdk-25 mvn compile exec:exec` (never `exec:java` on macOS).

## Guiding principles

1. **One `@KremaCommand` per domain action** — search, trending, detail. Keep command names explicit (`searchMovies`, `searchMusic`, …) or use a single dispatcher; decide once (see Open decisions).
2. **Service layer per domain** — `TmdbMovieService` → `MusicService`, `BookService`, `GameService`; each constructed with an injected client so tests stub the network.
3. **Client per upstream API** — `TmdbClient` talks TMDB; new clients wrap Open Library (books), MusicBrainz/iTunes (music), RAWG/IGDB (games), etc. No real network calls in unit tests.
4. **Reuse the record + poster pattern** — `Movie` record → domain records (`MusicTrack`, `Book`, `Game`); cover art/posters stream as `"poster"` events via `events.emit("poster", Map.of("id", …, "dataUri", …))`.
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

- `src/main/resources/movie-page/index.html` — `<select id="contentFilter">` already lists movie/music/book/game.
- `src/main/resources/movie-page/app.js` — `contentFilter.change` handler currently only wires `movie`. Extend it to call the domain-specific command and render into the same `#movieGrid` card grid.
- Cards use `data-movie-id` for poster lookup via `window.setPoster(id, dataUri)`. For multi-domain, consider `data-content-id` + a `data-domain` attribute so posters from one domain don't clobber cards from another.
- Reuse `escapeHtml`, `renderMovies`, `showError`, poster placeholder, and fullscreen toggle patterns.

## Adding a domain (checklist)

1. **Record** — `vangogh/<domain>/<DomainItem>.java` (id, title, year, rating, genre, overview, posterUrl, plus domain-specific fields).
2. **Client** — `vangogh/<domain>/<Domain>Client.java` wrapping the public API; injectable constructor, no hard-coded network in tests.
3. **Service** — `vangogh/<domain>/<Domain>Service.java` with search/trending + poster data-URI cache (mirror `TmdbMovieService.posterCache`, capped at 512).
4. **Controller** — `vangogh/controller/<Domain>Controller.java` with `@KremaCommand` methods, `setEventEmitter`, IO thread pool, generation counter.
5. **Register** — add controller instance to `.commands(...)` in `VanGoghApp.main()`.
6. **Frontend** — wire the filter `<option>` in `app.js` → `window.krema.invoke("<domainCommand>", …)` → render cards → listen to `"poster"` events.
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

```js
const tracks = await window.krema.invoke("searchMusic", { query });
// render cards, listen to "poster" events for cover art
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
- `src/main/java/vangogh/movie/TmdbMovieService.java` — service + poster cache pattern
- `src/main/java/vangogh/net/TmdbClient.java` — client + proxy pattern
- `src/main/resources/movie-page/index.html` — filter dropdown markup
- `src/main/resources/movie-page/app.js` — frontend invoke + render patterns
- `src/test/java/vangogh/controller/MovieControllerTest.java` — test pattern with stubbed client/emitter
- Vendored Krema source (read-only reference): `repos/krema/krema/krema-core/` — `KremaCommand`, `EventEmitter`, `KremaEvent`

