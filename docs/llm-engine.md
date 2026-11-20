---
title: "LLM recommendation engine — configuration and operation"
date: 2026-11-19
project: "[[VanGogh]]"
tags: [recommendation, llm, configuration, ai]
status: current
---

# LLM recommendation engine — configuration and operation

The **AI** button in the search bar switches the box from a catalog lookup to a
recommendation. The model *composes* the picks for the query; each pick is then
*resolved* against the content source the active form already uses (TMDB for
movies, and one source per later slice). Re-running a search gives a fresh set —
there is no reply cache.

Design and the measured probe data:
`docs/superpowers/specs/2026-11-19-llm-recommendation-engine-design.md`.

## Quick start

1. Get a key from any OpenAI-compatible provider. The default model is
   OpenRouter's free `baidu/ernie-5.5-flash-preview:free`.
2. Open **Settings** in the sidebar foot, paste the key, press **Save** — that
   is the whole setup. The page writes `~/.van-gogh/llm.conf`
   (`%USERPROFILE%\.van-gogh\llm.conf` on Windows) with owner-only permissions,
   and the next AI search picks the change up without a restart.

   Headless setup works too, with one command per OS:

   | OS | Command |
   |---|---|
   | macOS / Linux | `scripts/setup-llm.sh` |
   | Windows (PowerShell) | `powershell -ExecutionPolicy Bypass -File scripts\setup-llm.ps1` |

   Both prompt for the key with the input hidden. For scripted setup, pass the
   key as the first argument (`sh`) or `-Key` (`ps1`). A manual setup is fine
   too: copy `scripts/llm.conf.example` to that path and fill in `apiKey`. The
   example is the only tracked copy — a real `llm.conf` is gitignored. The
   reader accepts CRLF line endings and a UTF-8 BOM, because Notepad writes
   both.

3. Press **AI**, type a title or a vibe ("movies that feel like rain on a
   window"), press **Search**.

Without a key nothing breaks: normal search and trending still work, and an AI
search reports `llm: no API key: add apiKey to ~/.van-gogh/llm.conf or save one
in Settings`.

## Config file — the only source of truth

`LlmConfig` reads `~/.van-gogh/llm.conf` and falls back to the defaults below.
The file is `key=value` lines with `#`/`;` comments. **Environment variables are
not consulted**: `OPENAI_API_KEY` and friends no longer exist in the code, so a
key typed into Settings is always the key the engine uses. The Settings page
(sidebar foot) reads the file through `loadLlmSettings` and writes it through
`saveLlmSettings`.

| File key | Default | Meaning |
|---|---|---|
| `baseUrl` | `https://openrouter.ai/api/v1` | OpenAI-compatible API root (trailing slash stripped) |
| `apiKey` | — | Required; never logged (`toString` prints `***`) and never returned to the page |
| `model` | `baidu/ernie-5.5-flash-preview:free` | Chat model id |
| `timeoutSeconds` | `60` | Per-request timeout, clamped to 10–300 |
| `titles` | `10` | Titles asked of the model, clamped to 1–50 |

The key lives outside the working tree and is never committed. Any
OpenAI-compatible endpoint works: transport is the official
`com.openai:openai-java` SDK, pointed at the configured `baseUrl` (so no
provider lock-in and no hand-rolled HTTP). JSON mode / structured outputs stay
off — the contract is plain text, and many compatible servers lack them.

Writing is atomic (temp file + `ATOMIC_MOVE`) and re-applies owner-only
permissions, so a crash mid-save cannot truncate the file and a half-written
config never becomes current.

## Settings page

`SettingsController` is the page's backend — two synchronous `@KremaCommand`
methods, no drains (one small file read/write needs no IO pool):

| Command | Args | Reply |
|---|---|---|
| `loadLlmSettings` | — | `{ status, error, baseUrl, model, timeoutSeconds, titles, hasApiKey, configPath }` |
| `saveLlmSettings` | `{ baseUrl, model, apiKey, timeoutSeconds, titles }` | the same shape, painted from the stored, normalized values |

The stored key never crosses the bridge: load replies with `hasApiKey` only, so
the field starts empty with the hint "Key saved — type a new one to replace
it". A blank `apiKey` on save keeps the stored secret, which is what makes
"change the model, keep the key" work. Failing validation returns
`status: "error"` with `FetchError.uiMessage` text; nothing is written.

`RecommendationService` calls `LlmConfig.load()` per request, so a save applies
to the next AI search — no app restart.

## The three request kinds

The page sends one `kind` to the form's existing request command. There are no
new IPC commands, events or payload shapes.

| UI action | `kind` | Backend path |
|---|---|---|
| sidebar click | `trending` | existing source trending |
| search, AI off | `search` | existing direct source search |
| search, AI on, non-empty query | `recommend` | LLM → parser → per-pick resolution |
| search, AI on, empty box | `trending` | no model request, no error |

AI mode is opt-in and page-local (`state.ai`), and it survives a form switch.
Sidebar clicks always load trending. A failed AI search never silently falls
back to a direct search — the error names the cause.

## What is wired

| Form | AI recommendations | Notes |
|---|---|---|
| Movies | wired | TMDB `/search/movie` resolution (`MovieResolver`) |
| Music | wired | Genius `/search` song resolution (`SongResolver`) through `requestGeniusMusic` |
| Books | wired | Open Library structured + containment resolution (`BookResolver`) |
| Games | wired | TheGamesDB exact-name resolution (`GameResolver`) |

All four content forms now run the engine. Music is wired on
`GeniusController` — the music form's live controller. `MusicController` (the
earlier MusicBrainz album slice) is not the page's music path and keeps its
`recommend` rejection until it is retired or rewired.

## Pipeline

```
request<Form>(kind="recommend", query)        UI thread, returns generation at once
  └─ RecommendationService.recommend(...)     IO pool
       ├─ LlmClient.complete(...)             exactly one model request
       ├─ TitleParser.parse(reply, titles)    title | disambiguator, violations counted
       └─ Future.traverse(picks → resolver)   one catalog search per pick, in parallel
            └─ Either<FetchError, List<T>>    unresolved picks dropped
  └─ pendingData → poll<Form>Data → page      existing drain, skeleton cards meanwhile
```

Classes: `vangogh.llm` (`LlmConfig`, `LlmClient`, `Form`, `PromptFactory`,
`Pick`, `TitleParser`, `Normalize`), `vangogh.recommend`
(`PickResolver`, `RecommendationService`), `vangogh.movie.MovieResolver`,
`vangogh.music.SongResolver`, `vangogh.book.BookResolver`, and
`vangogh.game.GameResolver`.

`LlmClient` owns the wire: one cached `OpenAIClient` per (baseUrl, key,
timeout), `maxRetries(4)`, and the SDK's typed errors mapped onto `FetchError`
values (`RateLimitException` → the friendly message, other
`OpenAIServiceException`s → `HttpStatus`, IO errors → `Transport`, unreadable
2xx replies → `Payload`). It returns the assistant text as
`Either<FetchError, String>`, so the service never touches HTTP types.

The prompt is a measured contract, pinned by `PromptFactoryTest`: format rules
in the system message, then `List <N> <noun> titles related to: <query>` and
`Format each line as: title | <label>`. The music noun is **song** (Genius
resolves songs), not album. Model temperature is 0.7.

Movie resolution keeps the probe's rule: exact normalized title among the top 5
results, the release year must match when the model supplied one, the highest
`vote_count` wins, and rows under 10 votes are rejected. Recommended cards are
built by the same `TmdbMovieService.toMovie` as trending/search cards.

Song resolution is the music form's equivalent: exact normalized title among
the top 5 **song** hits (Genius' mixed hit list also carries albums and
artists), the model-supplied artist must match when present, and the first hit
in Genius' relevance order wins. Presentation-only suffixes ("(feat. …)",
"[Remastered 2011]", "feat. …") are ignored for matching, so a clean title and
a featured version resolve to the same song; a bare year in the disambiguator
slot is treated as a format slip, not an artist. Recommended cards use the same
`GeniusMusicService.toSong` as trending/search cards.

Book resolution has two passes. First a structured Open Library search with
`title=` and, when the model supplied one, `author=`; the normalized title must
match exactly and the highest `edition_count` wins. If that misses, a free-text
`q=` search is tried, accepting a candidate title contained in the model's
title (so "The Hobbit" can resolve "The Hobbit, or There and Back Again") only
when the candidate has at least 6 characters or 2 words — "Dune" must not
swallow "Dune Messiah" — again ranked by `edition_count`. Recommended cards use
`OpenLibraryBookService.toBook`.

Game resolution is exact normalized `game_title`, the release year must match
when the model supplied one, duplicate ids collapse, and the first matching row
wins. TheGamesDB has no popularity data, so there is no vote/edition floor.
Recommended cards use `TgdbGameService.toGame`, including the front-boxart URL.
A bare year in the disambiguator slot is accepted for games and ignored for
books (where the disambiguator is an author).

## Error messages

Every failure is a `FetchError` value rendered by `FetchError.uiMessage`, so
the page shows one readable line instead of a stack trace.

| Situation | Text |
|---|---|
| No key / config | `llm: no API key: add apiKey to …/llm.conf or save one in Settings` |
| Final 429 after backoff | `AI recommendations: rate-limited — try again in a minute` |
| Other HTTP status | `LLM failed: HTTP <status> <body excerpt>` |
| Transport failure / timeout | `LLM request failed: <cause>` |
| Reply is not chat-completions | `LLM payload unreadable (chat completions)` |
| Reply has no usable titles | `LLM payload unreadable (reply contained no titles)` |
| Every pick failed to resolve | the first catalog error (e.g. `TMDB search failed: HTTP 503 …`, `Genius search failed: HTTP 503 …`, `OpenLibrary search failed: HTTP 503 …` or `TheGamesDB search failed: HTTP 503 …`) |
| Blank AI query | `query: AI recommendations need a query` (the page sends `trending` instead) |
| Unwired form | `kind: AI recommendations are not wired for this form yet` (only the legacy MusicBrainz `MusicController` path remains) |

A reply with some unresolvable titles is still a success: those picks are
dropped, and fewer than 10 cards is normal. The request is an error only when
the model call fails, or when zero picks resolved **and** at least one lookup
errored.

## Quota and latency

- **One model request per AI search.** Nothing calls the model in the
  background; an empty box never spends quota.
- Free providers throttle hard: the SDK retries 429/5xx four times with
  exponential backoff, honoring `Retry-After`; a final 429 gets the friendly
  message above. Tests inject a no-wait `Sleeper`, so the suite never sleeps.
- Expected latency on the free tier: 3–20 s for the model plus about a second
  for 10 parallel catalog searches. Skeleton cards already cover the wait.
- Resolution cost scales with picks (10 picks = up to 10 catalog searches).
  TMDB, Open Library, TheGamesDB and Genius tolerate that; MusicBrainz would
  not — one more reason the music form uses Genius.

## Tests

`mvn -q test` is fully offline: the model and every catalog are stubbed.

- `vangogh.llm`: prompt pinning, parser tolerance/violations, normalization,
  config read/clamp/save (atomic replace, owner-only permissions, validation,
  Windows BOM/CRLF), chat-completions transport against a local
  `com.sun.net.httpserver.HttpServer` (200/401/500/429, retry count and the
  `Retry-After` delay recorded by a fake `Sleeper`, key never logged).
- `vangogh.controller.SettingsControllerTest`: load without a file paints
  defaults and no key, the reply never contains the stored key, save writes and
  returns the normalized values, a blank key keeps the stored one, missing
  input is an error value and writes nothing.
- `vangogh.movie.MovieResolverTest`: top-5, year mismatch, vote floor,
  normalization, HTTP/transport/payload errors.
- `vangogh.music.SongResolverTest`: exact title + artist, feature/remaster
  suffix handling, artist mismatch, mixed non-song hits, top-5 window,
  HTTP/transport/payload errors.
- `vangogh.book.BookResolverTest`: strict title + author, edition-count
  tiebreak, guarded containment fallback (short titles rejected), sparse rows
  without authors, bare-year disambiguator, HTTP/transport/payload errors.
- `vangogh.game.GameResolverTest`: exact name + year, year mismatch, duplicate
  ids, missing release dates, HTTP/transport/payload errors.
- `vangogh.recommend.RecommendationServiceTest`: one model call, partial
  lists, zero-resolved-with-error → error, blank query and missing config
  never call the model, a throwing resolver is contained.
- `MovieControllerTest`: `kind=recommend` end to end through the drain, blank
  query, model failure and HTTP failure.
- `GeniusControllerTest`: `kind=recommend` end to end through the music drain,
  blank query, model failure and HTTP failure.
- `BookControllerTest` and `GameControllerTest`: `kind=recommend` end to end
  through their drains (partial resolution, blank query, model failure, model
  HTTP failure, and a total catalog failure surfacing its own error).
- `preview/check.js`: the toggle defaults off, flips `aria-pressed`, sends
  `kind=recommend`, stays on across a form switch, and an empty AI search
  sends `trending`; the Settings page loads/saves through the mocked commands
  and never lets the stored key into the field.

## Adding a new form

The engine is generic over `PickResolver<T>`; a new form (e.g. TV) is one
resolver class plus its controller branch (the slice plan in the spec). Reuse
the service's row mapper (`toSong` / `toBook` / `toGame` / `toMovie`) so a
recommended card is identical to a trending card, and delete any `recommend`
rejection block that form still has.
