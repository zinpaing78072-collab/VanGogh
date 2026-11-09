---
status: active
project: van-gogh
type: kb
tags: [project, description]
---

# VanGogh — Entertainment Content Recommendation Desktop App

## Overview

VanGogh is a desktop application (Java 25 + Krema 0.3.2 system webview) that recommends entertainment content (movies, music, books, games) from a single user query. The app displays results in a dark-themed card grid with poster artwork, and streams posters into the UI as native `"poster"` events from the Java backend via Krema's IPC system.

The application is **not** a JavaFX standalone bundle — it runs inside Krema's system webview (Panama FFM), which means:

- The JVM forks with `-XstartOnFirstThread` and `--enable-native-access=ALL-UNNAMED` so macOS AppKit's event loop works.
- UI is rendered by a system webview (WebKit on macOS), not a bundled Chromium.
- Frontend is a single-page app (HTML/CSS/JS) served from the classpath.
- IPC: `window.krema.invoke("requestMovies", {kind, query})` → Java `MovieController` → `CompletableFuture<List<Movie>>`; posters stream as `"poster"` events.
- No `exec:java` on macOS (use `mvn compile exec:exec`).

## Core behavior

- **Input**: A keyword or content name (e.g. `"Inception"`, `"Taylor Swift"`, `"Minecraft"`)
- **Output**: Related content names, titles, and references across Movies, Music, Books, and Games (currently only Movies are fully wired; other domains show a stub)
- **Mechanism**: Frontend calls `await window.krema.invoke("requestMovies", {kind, query})`; Java `MovieController.requestMovies()` dispatches to `TmdbMovieService` → `TmdbClient` (TMDB API + wsrv.nl poster proxy); posters stream as `"poster"` events into the webview
- **Filter**: Dropdown offers Movie / Music / Book / Game — only Movie connects to a real API (TMDB); others display a "not yet connected" message

## Tech stack

- **Language**: Java 25 (LTS), compiled with `--release 25`
- **UI framework**: Krema 0.3.2 (system webview via Panama FFM, WebKit on macOS)
- **Frontend**: HTML/CSS/JS served from classpath (`src/main/resources/movie-page/`)
- **HTTP client**: Java 17+ `java.net.http.HttpClient` (Gson for JSON parsing)
- **Data source**: TMDB (The Movie Database) for movies; other domains not yet wired
- **Poster proxy**: `wsrv.nl` with persistent user preference stored in `~/.van-gogh/poster-proxy.conf`
- **Build**: Maven (`pom.xml`), compile+run via `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn compile exec:exec`
- **Tests**: JUnit 5; all network calls stubbed (no real TMDB API keys needed)
- **IPC**: `@KremaCommand` methods on controllers; `<parameters>true</parameters>` in maven-compiler-plugin for arg binding by name

## Project state

- **Branch**: `feat/krema-main-ui` (current working branch)
- **Uncommitted change**: `MovieController.java` removes unused `Gson` field (ready to commit)
- **Features wired**:
  - `VanGoghApp` — Krema window builder (title, identifier, size, debug mode, assets, events, commands)
  - `MovieController` — `@KremaCommand requestMovies(kind, query)`, generation-counted poster streaming via `events.emit("poster", ...)`
  - `TmdbMovieService` — trending + search, genre label map, poster data-URI cache (bounded at 512 entries)
  - `TmdbClient` — TMDB API calls, poster proxy with auto failover + preference persistence
  - Frontend: card grid, search bar, content filter dropdown, fullscreen toggle, poster rendering
- **Features pending**: Music / Book / Game domain wiring (filter dropdown options exist but show stub); movie detail view; loading/retry states; local persistence (watchlist, settings)

## Git conventions

- Remote: `https://github.com/zinpaing78072-collab/VanGogh.git` (origin)
- Default branch: `main` (protected — never commit directly)
- Workflow: `feat/<short-name>` / `fix/<short-name>` branches, PR, merge after review
- Commit messages: short, imperative, lowercase first word (e.g. `add .pen file`, `Fix JavaFX runtime missing error and add run wrapper`)
- Build artifacts (`target/`, `.class`, `.log`, `.DS_Store`) are gitignored
- Run `mvn -q clean compile` (or `mvn test`) before committing; keep the build green
- Push branches to `origin`; merge into `main` via PR only

## Related notes

- `AGENTS.md` — IPC contract, build/run rules, Krema 0.3.2, macOS launch flags
- `architecture.md` — how to add new content domains (Music, Book, Game, TV)
- `design-system.pen` — Figma design system source
- `repos/krema/` — vendored Krema source (read-only reference)
- Vault: `10 Projects/VanGogh/description.md`, `00 Meta/conventions.md`