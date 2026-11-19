---
title: "LLM recommendation engine — slice design"
date: 2026-11-19
project: "[[VanGogh]]"
tags: [recommendation, llm, architecture, design]
status: draft
---

# LLM recommendation engine — slice design

## Intent

The presentation build's headline feature is the recommendation engine: the
model *composes* the picks for a query, and the already-wired content sources
*resolve* each pick into a real card. Today the search box calls the source
directly (`TmdbClient.searchMovie`), which is a database lookup, not a
recommendation.

A throwaway probe in `~/Documents/school-project/connection-tester` already
measured this design end to end (query → four per-form LLM calls →
`title | disambiguator` → resolved against TMDB, MusicBrainz, Open Library,
TheGamesDB). This document turns the probe into a staged VanGogh slice plan.
No app code changes land with this document.

## What the probe established

Read from `connection-tester/PROBE.md`, `src/main/java/.../probe/*` and
`.probe-cache/` (24 cached real replies, one JSON per prompt):

- **Contract.** One system message (format rules) + one user message per form:
  `List <N> <noun> titles related to: <query>` and
  `Format each line as: title | <disambiguator>`. The disambiguator is a
  release year (movies, games) or an artist/author (music, books).
- **Tolerance.** `TitleParser` accepts bullets, numbering, quotes, and
  pipe-less lines, but counts each as a *format violation* separately from
  *resolution*. Commentary (long lines, lines ending in `:`) is dropped.
- **Resolution.** Per-form rules with explicit tiebreaks (TMDB top-5 +
  `vote_count` + ≥10 votes; MusicBrainz release-groups, Album/EP, score ≥70,
  1.1 s throttle; Open Library strict title+author then a guarded containment
  fallback ranked by `edition_count`; TheGamesDB exact name + year, deduped by
  id).
- **Gates** measured on the cached runs: movies ≥80 %, books ≥70 %, games
  ≥60 %, music ≥50 %. Album resolution is the weak one — the `Inception`
  music reply contained track titles ("Time", "Dream Within a Dream") and a
  malformed pick ("M83 Hurry Up, We're Dreaming"), which is exactly why the
  music bar is low.
- **Vibe queries work.** "albums that sound like rain on a car roof, but for
  heartbreak" returned Bon Iver, Sufjan Stevens, Nick Drake, Jeff Buckley —
  all catalog-resolvable. This is the demo moment, and it does not depend on
  the query looking like a title.
- **Provider-neutral.** Any OpenAI-compatible endpoint: `baseUrl` + `apiKey` +
  `model`. No JSON mode (many compatible servers lack it). 429/5xx retried at
  3/8/20/45 s, honoring `Retry-After`.

The probe's reply cache is the test-fixture source for this slice: it holds
real model output, including the messy cases (pipe-less lines, track titles,
a comma blob), with zero further quota cost.

## Review decisions (2026-11-19)

Settled with the team before implementation:

1. **AI is opt-in, not a replacement.** A toggle in the header switches the
   search box between normal search and AI recommendation. Existing direct
   search and trending stay exactly as they are.
2. **Music recommends from Genius** (songs), matching the shipped music form.
   The probe's MusicBrainz album resolver is not ported.
3. **10 titles per form**, all four forms. The probe's 20-title replies prove
   the prompt holds at that count.
4. **Config file now, Settings UI later.** `~/.van-gogh/llm.conf` is the only
   config path in this plan; the Settings page remains shell chrome.
5. **One form per slice**, as AGENTS.md requires.
6. **No cross-form search.** An AI search fills the active form only;
   switching the sidebar loads trending for the newly selected form.

## Goals

- With AI on, one query → a set of real cards for the active form, composed by
  the LLM and resolved by the source VanGogh already ships for that form.
- Re-running an AI search produces a genuinely fresh set (no reply reused).
- No change to the `window.krema` command set, the drains, the card grid, the
  artwork pipeline, or the shells. Only a new `kind` value.
- Failures are values: missing config, HTTP status, unreadable reply and
  quota exhaustion all reach the page as one readable error state.

## Non-goals

- Cross-form search sessions, one query filling all four forms.
- Tag chips, fill-in templates, favorites feeding the engine, My Lists /
  My Searches, SQLite storage — later slices.
- Trending on launch, recent-search chips (backlog).
- Streaming cards as picks resolve. One list per request, delivered through
  the existing drain.
- Settings UI for the key. A file for now.
- Reworking `MusicController` / MusicBrainz. Music stays on Genius.

## Constraints

1. **UI thread never blocks.** Krema 0.3.2 on Windows drops `engine.eval` off
   the UI thread, so every command is synchronous on that thread; network work
   stays on the controller's IO pool. The engine is one more IO-pool task.
2. **Free-tier quota.** ~50 model requests/day at $0 credit. One AI search on
   one form = one request. Anything that can spend a request without a user
   action is a bug.
3. **Secrets never enter the repo.** The key lives outside the working tree.
4. **Resolution cost is per pick.** 10 picks = up to 10 catalog searches.
   TMDB, Open Library, TheGamesDB and Genius tolerate that; MusicBrainz would
   not, which is one more reason it is not the music source.
5. **Search results can shrink.** A reply yields 10 picks; some are not in the
   catalog. Fewer than 10 cards is normal, not an error.

## Decisions

**D1 — Provider-neutral client, ported as-is.** `LlmClient` is the probe's
`OpenAiCompatClient` (plain chat-completions, backoff, no JSON mode) behind
`Try`. Model/base URL/key come from config; no provider SDK.

**D2 — Prompt contract is the measured one.** System message verbatim from
`PromptFactory.SYSTEM`; per-form user message verbatim from
`PromptFactory.user`. The parser and resolvers were tuned against exactly this
text, so changing wording invalidates the measurements. Prompt text lives in
one class, asserted by a test.

**D3 — A third `kind`: `recommend`.** Normal search and trending are untouched:

| UI action | `kind` | Backend path |
|---|---|---|
| sidebar click | `trending` | existing source trending |
| search, AI off | `search` | existing direct source search |
| search, AI on, query non-empty | `recommend` | LLM → parse → resolve |
| search, AI on, query empty | `trending` | no model call, no error |

The AI toggle is a header button; its state is page state, not shell state.
AI mode never silently falls back to direct search when the engine fails
(AGENTS.md: do not build a deterministic API search as the result source). The
error card names the cause.

Forms whose engine slice has not landed yet reject `kind=recommend` with
`FetchError.BadInput("kind", "AI recommendations are not wired for this form yet")`
rather than quietly running a direct search behind an AI-looking toggle.

**D4 — Engine sits inside each controller; no new IPC.** The engine is an
implementation detail of the existing per-form request command:

```java
@KremaCommand
public int requestMovies(String kind, String query) {
    if ("recommend".equals(kind)) {
        Future.of(executor, () -> recommendation.recommendMovies(query))
              .onComplete(result -> deliverData(gen, result));
    } else {
        ... // today's search / trending path
    }
    return gen;
}
```

Consequences: the frontend's `DOMAINS` rows, `content.js` polling, poster
drains, `preview/mock-krema.js` command map and `JcefBridge.validCommand`
all stay untouched. The only frontend change is the toggle and the `kind` it
sends.

**D5 — Resolution reuses the existing row mappers.** Each resolver picks the
winning catalog row and hands it to the same `toMovie` / `toBook` / `toSong` /
`toGame` mapper the service already uses, so cards keep identical fields
(rating, genre, subjects, poster URL). Mappers that are currently private gain
package-private visibility next to their service; no parallel row-building
code.

**D6 — Partial lists are success.** Per-pick resolution is
`Either<FetchError, Option<T>>`; an unresolved pick or a per-pick transport
error drops that pick. The request is `Left` only when the LLM call fails, or
when zero picks resolved **and** at least one resolver errored (so "the
network ate everything" is not rendered as "no recommendations").

**D7 — Config file outside the tree, env wins.** `~/.van-gogh/llm.conf`
(`key=value`: `baseUrl`, `apiKey`, `model`; optional `timeoutSeconds`) with
`OPENAI_BASE_URL` / `OPENAI_API_KEY` / `LLM_MODEL` overriding it, mirroring
the probe's env names. Written by the user, chmod 600, never committed.
Unconfigured is a normal state: AI search reports "AI recommendations are not
configured — add ~/.van-gogh/llm.conf"; normal search and trending still
work. A Settings UI can write the same file later without moving the reader.

**D8 — No reply cache in the app.** Every AI search is one model request. A
re-run must produce a fresh set, and there is no cross-form session to reuse a
reply for, so caching would only spend quota elsewhere. The probe keeps its
own `.probe-cache/` in its project.

**D9 — 10 titles per form.** One constant for all four forms, configurable
(`titles=10`). Movies/books/games resolve in parallel; Genius search is paced
by its own API, not a hard 1 req/s. MusicBrainz's throttle no longer
applies.

## Architecture

```
Request  →  <Form>Controller.request<Form>(kind="recommend", query)
              │  (UI thread: bumps generation, returns at once)
              ▼
         RecommendationService.recommend(form, query, resolver)   [IO pool]
              │
              ├── LlmClient.complete(system, user)          1 model request
              ├── TitleParser.parse(reply, titlesPerForm)   → List<Pick>, violations
              └── Future.traverse(picks → resolver.resolve(pick))
                    │ per pick: 1 catalog request, rules per form
                    ▼
              Either<FetchError, List<T>>   (unresolved picks dropped)
                    │
                    ▼
         pendingData one-slot holder  →  poll<Form>Data drain  →  page
                    │
                    └── existing artwork drain streams posterUrl/coverUrl
```

New packages:

| Class | Package | Responsibility |
|---|---|---|
| `LlmConfig` | `vangogh.llm` | record + `fromEnvOrFile()`; no secrets in logs |
| `LlmClient` | `vangogh.llm` | `Try<String> complete(String system, String user)`; 429/5xx backoff |
| `PromptFactory` | `vangogh.llm` | system + per-form user text, title count |
| `Pick` / `TitleParser` / `Normalize` | `vangogh.llm` | probe types, ported with the same semantics |
| `PickResolver<T>` | `vangogh.recommend` | `Either<FetchError, Option<T>> resolve(Pick)` |
| `RecommendationService` | `vangogh.recommend` | LLM → parse → traverse → collapse |
| `MovieResolver` / `BookResolver` / `GameResolver` / `MusicResolver` | form packages | per-form match rules, reuse service row mappers |

`FetchError` gains no variant: LLM HTTP status → `HttpStatus("LLM", …)`,
unreadable reply → `Payload("LLM", "chat completions", …)`, missing config →
`BadInput("llm", …)`. `uiMessage` keeps rendering every failure.

### Prompt contract (verbatim from the probe)

```
system: You recommend entertainment titles. Reply with one item per line, two
        fields separated by a pipe character: title | disambiguator. No
        numbering, no quotes, no explanations, no commentary.

user:   List 10 <noun> titles related to: <query>
        Format each line as: title | <label>

        noun/label: movie / release year · song / artist ·
                    book / author · video game / release year
```

The music noun is **song**, not album, because Genius resolves songs (review
decision 2). The query passes through verbatim — title, vibe, or broken
grammar. Model temperature 0.7, exactly as measured.

### Resolver rules

| Form | Source | Rule | Row mapper reused |
|---|---|---|---|
| Movie | TMDB `/search/movie` | exact `Normalize.key` title among top 5; year must match when supplied; highest `vote_count`; reject < 10 votes | `TmdbMovieService.toMovie` |
| Book | Open Library `search.json` | pass 1 structured `title=` + `author=`; pass 2 free-text `q=` with containment (candidate key contained in model key, ≥6 chars or ≥2 words); highest `edition_count` | `OpenLibraryBookService.toBook` |
| Game | TheGamesDB `Games/ByGameName` | exact normalized name, year when supplied, dedupe by id, no popularity floor (source has none) | `TgdbGameService.toGame` |
| Music | Genius `/search` | query `title + " " + artist` when supplied; exact normalized song title; artist must match when supplied; dedupe by song id; prefer a hit with a release year matching the pick's year, never reject on a missing year | `GeniusMusicService.toSong` |

Client gaps to close in the wiring slices (transport methods, no new
dependencies):

- `OpenLibraryClient` needs a structured `title=`/`author=` search (today
  only `q=`) plus a configurable limit; probe fields match `WORK_FIELDS`
  except `edition_count`, which `WORK_FIELDS` already has.
- `TmdbClient` and `TgdbClient` search payloads already carry every field the
  rules need (`vote_count`, `boxart` via `include=boxart`); nothing to add.
- `GeniusClient.search` already returns title + `primary_artist.name`; the
  song resolver needs no new call.
- `GeniusMusicService.parseSearch` currently maps *every* hit to a `Song`;
  the resolver should skip hits whose `result` is not a song type or lacks a
  title rather than render an "Untitled" card.

### IPC and frontend

Command names, events and payload shapes do not change. `kind` gains one
value; the per-form drains carry the resolved list exactly as they carry
trending today, and the poster/cover drains stream artwork for resolved cards.

The toggle is the only page change:

- `index.html`: a `button#aiToggle.ai-toggle` inside `.search-bar`, next to
  `#searchBtn`, with an `aria-pressed` state and an `aria-label`/`title`
  ("AI recommendations on/off"). No native `<select>` and no popup menu — the
  macOS NSMenu trap.
- `dom.js`: export `aiToggle`.
- `state.js`: `state.ai = false`.
- `ui.js`: click flips `state.ai`, sets `aria-pressed` and the `.on` class;
  submit sends `kind = state.ai && term ? "recommend" : term ? "search" : "trending"`.
  Sidebar clicks stay `trending` (no cross-form session); the toggle state
  persists across forms.
- `content.js`: heading becomes "AI Recommendations" for `recommend`; the rest
  of the lifecycle is unchanged.
- `style.css`: `.ai-toggle` / `.ai-toggle[aria-pressed="true"]`, using the
  existing palette and the icon-button size so the header height does not
  move.

With AI on and an empty box, submit behaves as trending — no request, no
error, matching what an empty normal search does today.

Music card follow-up in its slice: the prompt now yields songs, so the music
domain's `emptyLabel` changes from "albums" to "songs". The card already
renders `Song` (subtitle = artist, meta = year · "Song").

## Quota and latency

- One model request per AI search. No background calls.
- Backoff mirrors the probe. A final 429 renders: "AI recommendations are
  rate-limited — try again in a minute."
- LLM timeout 60 s (configurable). Catalog calls time out at 20 s; each pick
  is an independent `Future`, so one slow pick cannot hold the drain past its
  own timeout — it drops.
- Expected latency, free tier: 3–20 s model + ~1 s for 10 parallel TMDB / Open
  Library / TheGamesDB / Genius searches. Skeleton cards already cover this;
  no new loading UI.

## Testing plan

All network calls stubbed. Fixtures come from the probe cache (real model
output) and hand-written catalog payloads.

| Test | What it pins |
|---|---|
| `PromptFactoryTest` | exact system/user text, per-form nouns/labels, 10 titles |
| `TitleParserTest` | piped lines, bullets/numbering/quotes, pipe-less lines, comma blob, commentary, cap |
| `NormalizeTest` | case, punctuation, typography (`’ – &`), accents, leading "the" |
| `LlmClientTest` | 200 parse, 429→200 backoff, 5xx exhaustion, missing `choices`, key never logged |
| `LlmConfigTest` | file parsing, env override, missing key → `BadInput`, blank model → default |
| `MovieResolverTest` | exact title among top 5, year mismatch, <10 votes, highest vote_count |
| `BookResolverTest` | strict match, author mismatch, containment guard for short titles, `edition_count` tiebreak |
| `GameResolverTest` | exact name, year mismatch, duplicate ids collapsed |
| `MusicResolverTest` | exact song title, artist mismatch, non-song hit skipped, duplicate ids collapsed, missing year tolerated |
| `RecommendationServiceTest` | cached reply parse, partial resolution, zero-resolved-with-error → Left, title cap |
| `<Form>ControllerTest` (extend) | `kind=recommend` goes through the engine; `search` / `trending` unchanged; an unwired form rejects `recommend` with `BadInput`; kickoff returns immediately; superseded request drops; LLM failure → error status with readable text |
| `preview/check.js` (extend) | toggle defaults off, flips `aria-pressed`, sends `kind=recommend`, stays on across a form switch, empty query sends `trending` |

Controller tests inject a stubbed `LlmClient` and catalog client through the
existing package-private constructors, so `mvn -q test` stays offline.

## Verification

- `mvn -q clean test` green.
- `node preview/check.js` green (update the assertion count in
  `docs/preview-harness.md`).
- One live run per wired form with a real key and a vibe query
  ("albums that sound like rain on a car roof…"), checking that cards carry
  artwork, that the toggle changes the result shape, and that re-submitting
  changes the set.
- Probe gate run before each form's slice, with the app's prompt noun and
  title count (`PROBE_TITLES=10`, `PROBE_FORMS=<FORM>`). Music needs the new
  `GeniusResolver` added to the probe project first.

## Delivery plan (one slice per branch/PR, one form per slice)

| Slice | Branch | Scope | Demoable |
|---|---|---|---|
| S1 | `feat/llm-core` | `vangogh.llm` package (config, client, prompt, parser, normalize) + tests. No app wiring, no behavior change. | `mvn test` |
| S2 | `feat/llm-movies` | `PickResolver`, `RecommendationService`, `MovieResolver`; `requestMovies(kind=recommend)`; **AI toggle** + `state.ai` + preview-harness assertions; `docs/llm-engine.md`. | Toggle on → recommended movies |
| S3 | `feat/llm-music` | Probe `GeniusResolver` + gate run; `MusicResolver`; `requestGeniusMusic(kind=recommend)`; music label "songs". | Recommended songs |
| S4 | `feat/llm-books` | `OpenLibraryClient` structured search; `BookResolver`; `requestBooks(kind=recommend)`. | Recommended books |
| S5 | `feat/llm-games` | `GameResolver`; `requestGames(kind=recommend)`. | Recommended games |

The toggle ships in S2 because the feature is unreachable without it; it is
generic, so S3–S5 need no page changes. S3–S5 are independent of each other
and can reorder. The Settings page, tag chips/templates, and favorites-fed
recommendations stay out of this plan.

## Risks

- **Free-tier quota is the demo's single point of failure.** 429s on a second
  query are documented in the probe. Mitigation: one request per search, a
  swappable model, a clear rate-limit error, and a paid key as the fallback
  for presentation day. A canned reply is explicitly rejected.
- **Resolution rate below the gate in app use.** The gates were measured on
  canned queries; a live demo query may resolve 4/10. Fewer cards is the
  honest outcome; the empty state and a 4-card grid must still look finished —
  presentation polish, not this spec.
- **Genius is unmeasured.** The probe never resolved against it. S3 starts
  with a probe run, and the genre/vibe demo query may resolve worse there
  than MusicBrainz albums did, because the model is asked for songs.
- **Book containment can over-match** ("Dune" inside "Dune Messiah"). The
  probe's length/word guard is ported; `BookResolverTest` covers exactly that
  pair.
- **The toggle adds a second result mode** for every form, including the three
  whose backends are not wired yet. Until S3–S5, AI search on those forms
  returns an explicit "AI recommendations are not wired for this form yet"
  error card (D3), not a silent direct search. That is acceptable but must be
  demonstrated and copy-checked, not discovered on stage.
- **Latency above the skeleton's patience.** A 30 s free-tier reply is a long
  stare. Acceptable for a demo that is about "it thinks"; a timeout error is
  the worst case.

## Open questions

None blocking. Two small calls left to the implementer of the named slice:

- Toggle copy: "AI" with a sparkle icon, or "Recommend"? The spec assumes a
  compact `AI` label plus a tooltip; S2 can adjust without touching behavior.
- Exact Genius artist-match strictness in `MusicResolver` (contains vs token
  equality) — decide against a real probe run in S3.
