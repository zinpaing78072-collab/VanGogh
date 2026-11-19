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
2. Create the config file outside the repo — one command per OS:

   | OS | Command |
   |---|---|
   | macOS / Linux | `scripts/setup-llm.sh` |
   | Windows (PowerShell) | `powershell -ExecutionPolicy Bypass -File scripts\setup-llm.ps1` |

   Both prompt for the key with the input hidden and write
   `~/.van-gogh/llm.conf` or `%USERPROFILE%\.van-gogh\llm.conf`. For scripted
   setup, pass the key as the first argument (`sh`) or `-Key` (`ps1`), or set
   `VANGOGH_LLM_KEY`. A manual setup is fine too: copy
   `scripts/llm.conf.example` to that path and fill in `apiKey`. The example is
   the only tracked copy — a real `llm.conf` is gitignored. The reader accepts
   CRLF line endings and a UTF-8 BOM, because Notepad writes both.

3. Restart the app, press **AI**, type a title or a vibe
   ("movies that feel like rain on a window"), press **Search**.

Without a key nothing breaks: normal search and trending still work, and an AI
search reports `llm: no API key: set OPENAI_API_KEY or add apiKey to
~/.van-gogh/llm.conf`.

## Config file and environment

`LlmConfig` resolves each setting from the environment first, then
`~/.van-gogh/llm.conf`, then the default. The file is `key=value` lines with
`#`/`;` comments. The Settings page (sidebar foot) renders these five values in
a form, but it is UI only for now: Save and Test connection report that the
openai-java integration owns those paths, so this file remains the source of
truth.

| File key | Environment | Default | Meaning |
|---|---|---|---|
| `baseUrl` | `OPENAI_BASE_URL` | `https://openrouter.ai/api/v1` | OpenAI-compatible API root (no trailing slash needed) |
| `apiKey` | `OPENAI_API_KEY` or `OPENROUTER_API_KEY` | — | Required; never logged (`toString` prints `***`) |
| `model` | `LLM_MODEL` | `baidu/ernie-5.5-flash-preview:free` | Chat model id |
| `timeoutSeconds` | `LLM_TIMEOUT_SECONDS` | `60` | Per-request timeout, clamped to 10–300 |
| `titles` | `LLM_TITLES` | `10` | Titles asked of the model, clamped to 1–50 |

The key lives outside the working tree and is never committed. Any
OpenAI-compatible endpoint works: transport is the official
`com.openai:openai-java` SDK, pointed at the configured `baseUrl` (so no
provider lock-in and no hand-rolled HTTP). JSON mode / structured outputs stay
off — the contract is plain text, and many compatible servers lack them.

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
| Movies | wired | TMDB `/search/movie` resolution |
| Music, Books, Games | explicitly rejected | `kind: AI recommendations are not wired for this form yet` until their slices land |

The rejection is deliberate: without it, an AI-looking toggle would quietly run
a database search on those forms and contradict the feature.

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
(`PickResolver`, `RecommendationService`), and `vangogh.movie.MovieResolver`.

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

## Error messages

Every failure is a `FetchError` value rendered by `FetchError.uiMessage`, so
the page shows one readable line instead of a stack trace.

| Situation | Text |
|---|---|
| No key / config | `llm: no API key: set OPENAI_API_KEY or add apiKey to …/llm.conf` |
| Final 429 after backoff | `AI recommendations: rate-limited — try again in a minute` |
| Other HTTP status | `LLM failed: HTTP <status> <body excerpt>` |
| Transport failure / timeout | `LLM request failed: <cause>` |
| Reply is not chat-completions | `LLM payload unreadable (chat completions)` |
| Reply has no usable titles | `LLM payload unreadable (reply contained no titles)` |
| Every pick failed to resolve | the first catalog error (e.g. `TMDB search failed: HTTP 503 …`) |
| Blank AI query | `query: AI recommendations need a query` (the page sends `trending` instead) |
| Unwired form | `kind: AI recommendations are not wired for this form yet` |

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
  config precedence and clamping, chat-completions transport against a local
  `com.sun.net.httpserver.HttpServer` (200/401/500/429, retry count and the
  `Retry-After` delay recorded by a fake `Sleeper`, key never logged).
- `vangogh.movie.MovieResolverTest`: top-5, year mismatch, vote floor,
  normalization, HTTP/transport/payload errors.
- `vangogh.recommend.RecommendationServiceTest`: one model call, partial
  lists, zero-resolved-with-error → error, blank query and missing config
  never call the model, a throwing resolver is contained.
- `MovieControllerTest`: `kind=recommend` end to end through the drain, blank
  query, model failure and HTTP failure.
- `GameControllerTest`: an unwired form rejects `recommend` with `BadInput`.
- `preview/check.js`: the toggle defaults off, flips `aria-pressed`, sends
  `kind=recommend`, stays on across a form switch, and an empty AI search
  sends `trending`.

## Adding the next form

The engine is generic over `PickResolver<T>`; a new form is one resolver class
plus its controller branch (slices S3–S5 in the spec). Reuse the service's row
mapper (`toSong` / `toBook` / `toGame`) so a recommended card is identical to a
trending card, and delete that form's `recommend` rejection block. Music must
also change its empty-state label from "albums" to "songs".
