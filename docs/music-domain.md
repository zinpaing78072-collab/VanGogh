---
title: "Music domain — MusicBrainz upstream constraints and design decisions"
date: 2026-11-17
project: "[[VanGogh]]"
tags: [music, musicbrainz, covers, rate-limiting]
status: current
---

# Music domain — MusicBrainz upstream constraints and design decisions

> **Status:** the page's live music form is Genius (`GeniusController` /
> `GeniusMusicService`, songs). This note records the earlier MusicBrainz
> release-group slice's upstream constraints; it still documents
> `MusicController` and `MusicBrainzClient`, but it is not the active music
> path. AI music recommendations also resolve through Genius — see
> `docs/llm-engine.md`.

The music slice follows the movie/game architecture exactly: `requestMusic`
kicks off the IO-pool fetch and returns a generation, `pollMusicData` drains the
card list, and `pollMusicPosters` drains cover art while emitting
`"musicPoster"` events. This note records only what is *non-obvious* about
MusicBrainz WS2 + Cover Art Archive — things a future refactor or slice would
trip on. Client code: `vangogh/net/MusicBrainzClient`.

## MusicBrainz WS2

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

## Covers — Cover Art Archive

**The cover endpoint redirects.**
`coverartarchive.org/release-group/<mbid>/front-250` answers 307 to
`archive.org/download/...` (which serves the JPEG out of a .zip path —
legitimately). `MusicBrainzClient` therefore builds its `HttpClient` with
`followRedirects(ALWAYS)`. A shared/global HttpClient built without redirects
silently breaks every cover (the plain clients in `TmdbClient`/`TgdbClient`
never needed redirect following).

**Failover shares the machine-wide proxy preference.** On this machine/ISP
`image.tmdb.org` is TLS-blocked while its API host works; archive.org is not
blocked but the same condition could change per network. The music client
reuses `~/.van-gogh/poster-proxy.conf` (the same marker as TMDB/TheGamesDB)
with the wsrv.nl proxy as failover source — see "Debug Record — TMDB posters
blocked" in the vault.

## Verification (2026-11-16, live from the builder's machine)

- MBZ release-group search + rgid trending: HTTP 200, 12/11 rows parsed.
- CAA `front-250` → data URI (redirect chain followed).
