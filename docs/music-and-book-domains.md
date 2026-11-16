---
title: "Music & Book domains — upstream constraints and design decisions"
date: 2026-11-16
project: "[[VanGogh]]"
tags: [music, book, musicbrainz, openlibrary, covers, rate-limiting]
status: current
---

# Music & Book domains — upstream constraints and design decisions

The music and book slices follow the movie/game architecture exactly (kickoff
command returning a generation, `poll<Domain>Data` drain, `poll<Domain>Posters`
drain emitting domain events). This note records only what is *non-obvious*
about the two upstream APIs — things a future refactor or slice would trip on.
Client code: `vangogh/net/MusicBrainzClient`, `vangogh/net/OpenLibraryClient`.

## Music — MusicBrainz WS2 + Cover Art Archive

**No popularity data exists.** MusicBrainz has no "trending", no play counts,
no ratings in search results. "Trending Music" is therefore a *curated
showcase*: `MusicBrainzService.TRENDING_RGIDS` is a hand-picked list of
canonical album release-group MBIDs, fetched through one search query
(`rgid:<id> OR rgid:<id> …`) — the same approach the games slice uses with its
fixed TheGamesDB IDs. The parsed results are re-sorted to the seed order (the
rgid query returns hits in arbitrary relevance order). To change the showcase,
edit the seed list; every MBID must be a real release-group MBID.

**A User-Agent is mandatory.** Requests without one are answered 503. The
client sends `VanGogh/0.1 (<repo url>)` on every call — keep the header if the
client is ever refactored.

**Rate limiting: ~1 request/second average.** Bursts answer 503. The app can
legitimately fire requests faster (rapid filter toggles / search clicks), so
`MusicBrainzClient` serializes MusicBrainz requests through a pace gate
(`MIN_REQUEST_INTERVAL_MS`, slightly over 1s) and, on a 503, backs off once
(`THROTTLE_RETRY_BACKOFF_MS`) and retries before surfacing the error. Both
waits run on IO-pool threads; the UI thread is never blocked. Any new
MusicBrainz call site must go through `send()` to keep this. Cover fetches do
NOT go through the gate — they hit Cover Art Archive, not MusicBrainz.

**MusicBrainz search responses are huge.** Each release-group row carries the
artist's full alias list (dozens of KB of localized names). The client requests
`limit=12` and the service parses only id/title/primary-type/first-release-
date/artist-credit[0]/tags — do not widen the fields parsed casually.

**Granularity: release-groups, not recordings.** A recording search returns
the same track once per release; release-groups (albums/EPs/singles) map 1:1
to a card. The `Album` record's `id` is the release-group MBID (a String —
unlike movie/game ids, which are ints).

## Covers — Cover Art Archive & Open Library Covers

**Both cover endpoints redirect.** `coverartarchive.org/release-group/<mbid>/front-250`
answers 307 and `covers.openlibrary.org/b/id/<id>-L.jpg` answers 302, both to
`archive.org/download/...` (which serves the JPEG out of a .zip path —
legitimately). Both clients therefore build their `HttpClient` with
`followRedirects(ALWAYS)`. A shared/global HttpClient built without redirects
silently breaks every cover (the plain clients in `TmdbClient`/`TgdbClient`
never needed redirect following).

**Failover shares the machine-wide proxy preference.** On this machine/ISP
`image.tmdb.org` is TLS-blocked while its API host works; archive.org is not
blocked but the same condition could change per network. The music/book
clients reuse `~/.van-gogh/poster-proxy.conf` (the same marker as
TMDB/TheGamesDB) with the wsrv.nl proxy as failover source — see
"Debug Record — TMDB posters blocked" in the vault.

## Books — Open Library

**Real trending exists**: `openlibrary.org/trending/now.json` (reading-log
activity). Search uses `search.json` with an explicit `fields=` list — keep
the field list minimal, the unfiltered docs are wide. `subject` is in the
list: Open Library has no curated genre field, only community subject tags,
so the card genre line takes the first two subjects (trending rows carry no
subjects and show an empty genre).

**Two wrapper keys, one row shape.** Trending rows live under `"works"`,
search rows under `"docs"`; the service parses both with the same row mapper.
`author_name` and `first_sentence` are arrays (take [0]); `cover_i` of 0 means
"no cover" → empty posterUrl → placeholder card, not an error.

## Dev environment (this machine)

JDK 25 is at `C:\Program Files\Java\latest\jdk-25` (Maven uses it); the PATH
`javac` may be older — compile verifiers against the JDK 25 `javac`/`java`
explicitly. The in-process compiler failure mode is recorded separately in
`docs/debug-records/2026-11-16-compile-errors-masked-as-concurrentmodificationexception.md`.

## Verification (2026-11-16, live from this machine)

- MBZ release-group search + rgid trending: HTTP 200, 12/11 rows parsed.
- CAA `front-250` → data URI (redirect chain followed).
- OL trending + search: HTTP 200, 12 rows each.
- OL cover `-L.jpg` → data URI (302 → archive.org followed).
