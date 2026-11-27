-- VanGogh storage schema (SQLite).
--
-- This file is the single source of truth: the build applies it to a throwaway
-- database and jOOQ generates the typed classes from the result. It is also
-- applied at runtime on first open. One statement per line group, and comments
-- always on their own line: Database.executeScript strips comment lines before
-- splitting on ";" so a ";" inside a comment cannot break the script.
--
-- PRAGMA foreign_keys is deliberately absent: it is per-connection, so Database
-- sets it on every connection instead.
--
-- "user" is quoted because USER is reserved in standard SQL / H2; SQLite
-- accepts the quoted identifier without changing its meaning.

-- The single local identity row. Accounts/login are a later slice.
CREATE TABLE "user" (
  id         INTEGER PRIMARY KEY,
  name       TEXT    NOT NULL,
  created_at INTEGER NOT NULL
);

-- One row per piece of content, shared across users. source is the provider
-- identity ("tmdb:27205", "openlibrary:OL27482W").
CREATE TABLE item (
  id          INTEGER PRIMARY KEY,
  source      TEXT    NOT NULL UNIQUE,
  form        TEXT    NOT NULL CHECK (form IN ('movie','music','book','game')),
  title       TEXT    NOT NULL,
  -- metadata: JSON card fields. poster: offline card art. extra: detail JSON,
  -- written on first open. All three have their own writers so a metadata
  -- refresh cannot erase them.
  metadata    TEXT    NOT NULL DEFAULT '{}',
  poster      BLOB,
  poster_mime TEXT,
  extra       TEXT,
  fetched_at  INTEGER NOT NULL
);

-- The library row: favorite is the Favorites flag, status is the Queue's.
-- favorite and favorited_at move together, which the trailing CHECK enforces.
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

-- One AI run. saved_at marks the runs kept in My Searches. loaded_forms is a
-- comma-separated list of the forms the run produced.
CREATE TABLE search (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  query        TEXT    NOT NULL,
  ran_at       INTEGER NOT NULL,
  saved_at     INTEGER,
  loaded_forms TEXT
);

-- The picks belonging to one search, in display order. item_id stays null
-- until the engine's title resolves to a catalog row.
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
