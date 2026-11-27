# Storage schema + jOOQ repositories — design

Status: draft for approval
Written: 2026-11-27
Slice: storage foundation (data layer only)
Supersedes the vault's pending "Storage Schema v2" DDL section
(`10 Projects/VanGogh/Storage Schema v2.md`), with one naming change:
`profile` becomes `user` (decided 2026-11-27).

## Intent

Land the SQLite schema and a typed jOOQ repository layer so later slices
(My Lists, My Searches, Profile) write against real tables. This slice is the
data layer only: no Krema commands, no `@KremaCommand` handlers, no React, no
feature wiring.

## Goals

- The DDL is committed and is the single source of truth.
- jOOQ generates typed table/record classes at build time; generated code is
  never committed.
- One repository per table, with a narrow method surface.
- Generated jOOQ types do not escape `vangogh.storage`; repositories return
  plain records.
- Tests run against real SQLite with the real schema — no mocks, no network.
- `user` (renamed from `profile`) ships with one seeded `local` row.

## Non-goals

- My Lists / My Searches / Profile screens or commands.
- Poster BLOB streaming into the page; repositories store and return bytes only.
- Auth, login, multi-profile management.
- A migration framework. The schema is applied once, gated by
  `PRAGMA user_version`.
- Parsing `metadata` JSON into per-domain card records; the column is stored and
  returned as a string.

## Anti-goals

- Hand-written SQL strings inside repositories (the point of codegen).
- Committing generated sources, or requiring a developer's `~/.van-gogh` file to
  build.
- Repositories returning jOOQ-generated records to callers.
- Reading or writing the database from the UI thread.

## Constraints

- Java 25, Maven; `mvn test` must still require no Node.
- SQLite via `org.xerial:sqlite-jdbc` (bundled SQLite is well above the 3.40
  floor jOOQ's OSS edition requires).
- jOOQ OSS 3.21.x — SQLite is supported in the OSS edition since 3.19.
- Database file: `~/.van-gogh/vangogh.db`, injectable for tests.
- Settings stay in files (`llm.conf`, `theme.conf`, `poster-proxy.conf`); the
  database holds content and library state only.

## Approaches considered

- **A. DDL-first codegen (chosen).** `schema.sql` is committed; codegen builds a
  throwaway SQLite database in `target/`, then reverse-engineers it.
- **B. No codegen, hand-written `DSL.table(...)`/`DSL.field(...)`.** Rejected:
  loses compile-time column safety, the main reason to use jOOQ here.
- **C. Codegen from a live dev database.** Rejected: the build would depend on
  one developer's `~/.van-gogh/vangogh.db` and generated code would drift.

### Why codegen reads real SQLite, not `DDLDatabase`

The obvious implementation of A is `DDLDatabase`, which parses scripts and
applies them to in-memory H2. Two facts rule it out for this schema:

1. jOOQ 3.21.10 is the latest published OSS release, and `DDLDatabase`'s
   `parseDialect` property only exists from 3.21.11 — so the script would be
   parsed as the default dialect, not SQLite.
2. H2 treats `USER` as a reserved keyword. The renamed table would fail (or need
   quoting everywhere), and H2's replica would not match SQLite's metadata for
   `INTEGER PRIMARY KEY` identity or partial indexes.

Instead the build runs the real DDL against a temporary SQLite file and lets
jOOQ's `org.jooq.meta.sqlite.SQLiteDatabase` reverse-engineer that file. This
also means the build fails if the DDL is not valid SQLite — a feature.

## Schema

`src/main/resources/db/schema.sql`. `PRAGMA foreign_keys` is deliberately absent:
it is per-connection, so the runtime sets it on every connection instead of once
at schema creation.

```sql
CREATE TABLE "user" (
  id         INTEGER PRIMARY KEY,
  name       TEXT    NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE item (
  id          INTEGER PRIMARY KEY,
  source      TEXT    NOT NULL UNIQUE,  -- "tmdb:27205", "openlibrary:OL27482W"
  form        TEXT    NOT NULL CHECK (form IN ('movie','music','book','game')),
  title       TEXT    NOT NULL,
  metadata    TEXT    NOT NULL DEFAULT '{}',
  poster      BLOB,
  poster_mime TEXT,
  extra       TEXT,
  fetched_at  INTEGER NOT NULL
);

CREATE TABLE saved (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  item_id      INTEGER NOT NULL REFERENCES item(id),
  favorite     INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0,1)),
  favorited_at INTEGER,
  status       TEXT    NOT NULL DEFAULT 'want' CHECK (status IN ('want','progress','done')),
  progress     INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  note         TEXT,
  saved_at     INTEGER NOT NULL,
  UNIQUE (user_id, item_id),
  CHECK ((favorite = 1 AND favorited_at IS NOT NULL)
      OR (favorite = 0 AND favorited_at IS NULL))
);

CREATE TABLE search (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  query        TEXT    NOT NULL,
  ran_at       INTEGER NOT NULL,
  saved_at     INTEGER,
  loaded_forms TEXT
);

CREATE TABLE search_item (
  id        INTEGER PRIMARY KEY,
  search_id INTEGER NOT NULL REFERENCES search(id) ON DELETE CASCADE,
  item_id   INTEGER REFERENCES item(id) ON DELETE SET NULL,
  form      TEXT    NOT NULL,
  title     TEXT    NOT NULL,
  reason    TEXT,
  position  INTEGER NOT NULL,
  UNIQUE (search_id, position)
);

CREATE INDEX item_form        ON item (form);
CREATE INDEX saved_recent     ON saved (user_id, saved_at DESC);
CREATE INDEX saved_queue      ON saved (user_id, status, saved_at DESC);
CREATE INDEX saved_favorites  ON saved (user_id, favorite, saved_at DESC);
CREATE INDEX search_recent    ON search (user_id, ran_at DESC);
CREATE INDEX search_saved     ON search (user_id, saved_at DESC) WHERE saved_at IS NOT NULL;
CREATE INDEX search_item_item ON search_item (item_id);
```

`"user"` is quoted in the DDL because `USER` is reserved in H2/standard SQL and
the quoting costs nothing in SQLite. jOOQ generates a `User` class bound to the
quoted identifier, so runtime queries match.

All timestamps are Unix epoch seconds. The v2 behaviour rules are unchanged:
`status = 'want'` is the Watchlist, `favorite`/`favorited_at` move together, one
`saved` row per `(user_id, item_id)`, and only Remove deletes it.

## Build integration

1. `maven-antrun-plugin` at `initialize` runs Ant's `<sql>` task (SQLite driver on
   the plugin classpath) to execute `db/schema.sql` into `target/codegen.db`.
   (`sql-maven-plugin` was the first pick; Ant's task is the same idea with one
   less plugin — Ant requires `userid`/`password` attributes even for SQLite, so
   they are set to placeholder values.)
2. `jooq-codegen-maven` at `generate-sources` reads that file with
   `org.jooq.meta.sqlite.SQLiteDatabase` and writes to
   `target/generated-sources/jooq`, package `vangogh.db`.
3. Dependencies: `org.jooq:jooq` (compile), `org.xerial:sqlite-jdbc` (runtime),
   `org.jooq:jooq-codegen-maven` (build plugin only; it pulls `jooq-meta`).
   `slf4j-nop` is a test-scope dependency so jOOQ's SLF4J logging does not print
   a "no providers" warning on every test run.
4. One `jooq.version` property pins jooq and codegen together; one
   `sqlite.version` pins the driver everywhere it appears.
5. The generated directory is added as a source root by the plugin and stays
   under `target/`, already gitignored.
6. Surefire runs with `--enable-native-access=ALL-UNNAMED`, because sqlite-jdbc
   loads a native library and Java 25 warns (and will later refuse) otherwise.

## Runtime

`vangogh.storage.Database` owns the file and the connection:

- `open()` (default `~/.van-gogh/vangogh.db`) and `open(Path)` for tests.
- Creates the parent directory, opens `jdbc:sqlite:<file>`.
- On every connection: `PRAGMA foreign_keys = ON`,
  `PRAGMA journal_mode = WAL`, `PRAGMA busy_timeout = 5000`.
- Applies `schema.sql` when `PRAGMA user_version < 1`, then sets
  `user_version = 1` and seeds `user(id=1, name='local', created_at=now)`.
  Re-opening is a no-op.
- Holds one connection and one `ReentrantLock`. Every access — reads included —
  goes through `Database.read(...)` or `Database.write(...)`, both taking the
  lock. A single JDBC connection is not thread-safe, and a serialized writer
  plus unsynchronized readers on the same connection is a latent race; this
  slice chooses correctness over read parallelism. A single-user desktop app
  will not notice. If contention ever shows up, the seam allows a read
  connection pool later; nothing outside `Database` changes.
- `write(...)` runs inside a transaction and hands the callback a
  transaction-bound `DSLContext`, so several repository calls can share one
  unit of work.
- Refuses to open a database whose `PRAGMA user_version` is greater than the
  supported version instead of running against an unknown schema.
- Closes deterministically (`close()`); tests use try-with-resources. The first
  consumer closes it on app shutdown.

Error vocabulary: add one case to the sealed `FetchError`:

```java
record Storage(String op, Throwable cause) implements FetchError {}
```

`uiMessage` gains its arm (`op + " failed: " + cause.getMessage()`), and the
existing exhaustive switch makes this a compile-time checklist. Repository
methods return `Either<FetchError, T>`; "not found" is `Right(Option.none())`,
matching `PickResolver`'s clean-miss convention.

## Repository contracts

Package `vangogh.storage`, one class per table, constructed with `Database`.
Public methods open their own `Database.read` / `Database.write`; package-private
`…In(DSLContext, …)` variants let one transaction span several repositories.

| Repository | Methods (first cut) |
|---|---|
| `UserRepository` | `local()`, `find(long id)`, `ensureLocal()` |
| `ItemRepository` | `upsert(NewItem) -> long`, `bySource(String)`, `byId(long)`, `byForm(ContentForm, int limit)`, `writePoster(long, byte[], String)`, `poster(long)`, `writeExtra(long, String)`, `extra(long)` |
| `SavedRepository` | `saveOrGet(long userId, long itemId, long now)`, `setStatus`, `setProgress`, `setNote`, `setFavorite(long, boolean, long now)`, `remove`, `queue(userId, QueueFilter)`, `favorites(userId)`, `statusCounts(userId)`, `formCounts(userId)` |
| `SearchRepository` | `record(userId, query, loadedForms, now, List<SearchPickDraft>)`, `markSaved(long searchId, long now)`, `recent(userId, int limit)`, `saved(userId)` |
| `SearchItemRepository` | `replace(long searchId, List<SearchPickDraft>)`, `linkItem(long pickId, long itemId)`, `bySearch(long searchId)` |

### Content form vocabulary

Storage gets its own enum, `vangogh.storage.ContentForm`:
`MOVIE("movie")`, `MUSIC("music")`, `BOOK("book")`, `GAME("game")`, plus
`wire()` / `fromWire(String)`. The existing `vangogh.llm.Form` is a *prompt*
enum (`MUSIC`'s noun is `"song"`), so it must not leak into the schema; a future
adapter maps `llm.Form` → `ContentForm`. Repositories accept `ContentForm`, never
a raw string. `search_item.form` stores the same wire values.

### Item upsert rule

`NewItem(source, form, title, metadata, fetchedAt)` has no poster or extra
fields. `upsert` is `INSERT … ON CONFLICT(source) DO UPDATE` and updates only
`title`, `form`, `metadata`, `fetched_at` (which always advances). It preserves
`poster`, `poster_mime` and `extra`, so a metadata refresh cannot erase cached
artwork or detail JSON. Poster and extra have their own writers
(`writePoster`, `writeExtra`), matching the asynchronous way each arrives.

### Search + picks transaction

`SearchPickDraft(ContentForm form, String title, String reason, int position,
Long itemId)` — `itemId` is null for a pick the engine produced but the catalog
has not resolved. The caller upserts resolved items first and passes their ids;
`SearchItemRepository` never crosses into `item`.

- `SearchRepository.record(..., picks)` inserts the `search` row and its
  `search_item` rows in one `Database.write` (it delegates to the
  package-private `SearchItemRepository.replaceIn`).
- `SearchItemRepository.replace(searchId, picks)` is the re-run path: replace
  the pick rows in place and preserve the `search` row id.
- `linkItem(pickId, itemId)` handles a pick that resolved after the row was
  written, without replacing the whole list.

A queue read (`saved → item`) is one joined query inside `Database.read`.

Domain records (in `vangogh.storage.model`):

- `User(long id, String name, long createdAt)`
- `StoredItem(long id, String source, ContentForm form, String title,
  String metadata, String posterMime, long fetchedAt)` — no poster bytes, no
  extra.
- `Poster(byte[] bytes, String mime)`
- `SavedEntry(...)`, `QueueFilter(ContentForm form, String status, boolean favoritesOnly)`
- `SearchEntry(...)`, `SearchPick(...)`

## Testing

`src/test/java/vangogh/storage/`, all with JUnit 5 and `@TempDir`:

- `DatabaseTest` — bootstrap creates tables, seeds `local` once, re-open is
  idempotent, foreign keys are enforced, a newer `user_version` is refused,
  parallel reads plus a write through the shared connection stay consistent.
- `StorageSchemaTest` — `PRAGMA table_info` for the five tables, unique
  `item.source`, the favorite/favorited_at check, cascade deletes.
- `ItemRepositoryTest`, `SavedRepositoryTest`, `SearchRepositoryTest` — the
  per-table behaviour, including upsert preservation, queue filters and
  ordering, favorites, counts, pick replacement, late linking and duplicate
  position rejection.
- No network, no UI, no fixtures outside the test.

## App wiring (decided 2026-11-27)

**Test-only in this slice.** `Database` is constructed only by tests; neither
`VanGoghApp` nor `JcefVanGoghApp` changes, and launching the app does not create
the database file. The first consumer (My Lists) wires it and demonstrates it in
the real app. This slice still exercises the real file, schema, seed and close
paths in tests.

## What is deliberately left to the builder

- Exact plugin coordinates/versions and codegen settings, validated on the
  first `mvn generate-sources`.
- Method-name polish.
- Whether `SearchRepository.record` also accepts already-resolved ids or the
  caller links them afterwards (both paths exist via `linkItem`).
