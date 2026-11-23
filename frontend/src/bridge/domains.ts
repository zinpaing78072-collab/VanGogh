import type { DomainKey } from "../app/types";

/**
 * One row per content form: the whole per-domain IPC contract.
 *
 * Components are not here on purpose — this module is data, so it can be
 * imported by the bridge, the hooks and the tests without pulling React views
 * into the dependency graph. `app/domainViews.tsx` maps a key to its Card and
 * Detail components.
 */
export interface DomainIpc {
  key: DomainKey;
  /** The detail view name used by the old state.view ("song" for music). */
  view: "movie" | "song" | "book" | "game";
  label: string;
  plural: string;
  emptyLabel: string;
  /** Noun used in the detail error text ("Failed to load film details."). */
  detailNoun: string;
  request:
    | "requestMovies"
    | "requestGames"
    | "requestGeniusMusic"
    | "requestBooks";
  data: "pollMovieData" | "pollGameData" | "pollGeniusMusicData" | "pollBookData";
  art: "pollMoviePosters" | "pollGamePosters" | "pollGeniusMusicPosters" | "pollBookCovers";
  event: "poster" | "gamePoster" | "musicPoster" | "bookCover";
  detailRequest: "requestMovieDetail" | "requestGameDetail" | "requestSongDetail" | "requestBookDetail";
  detailPoll: "pollMovieDetail" | "pollGameDetail" | "pollSongDetail" | "pollBookDetail";
  /** Songs carry their artwork in the detail reply and have no art drain. */
  detailArt: "pollMovieDetailArt" | "pollGameDetailArt" | "pollBookDetailArt" | null;
  detailArtEvent: "movieDetailArt" | "gameDetailArt" | "bookDetailArt" | null;
  /** The card's identity field, used for artwork routing. */
  idField: "id" | "workKey";
}

export const DOMAINS: Record<DomainKey, DomainIpc> = {
  movie: {
    key: "movie",
    view: "movie",
    label: "Movies",
    plural: "Movies",
    emptyLabel: "movies",
    detailNoun: "film",
    request: "requestMovies",
    data: "pollMovieData",
    art: "pollMoviePosters",
    event: "poster",
    detailRequest: "requestMovieDetail",
    detailPoll: "pollMovieDetail",
    detailArt: "pollMovieDetailArt",
    detailArtEvent: "movieDetailArt",
    idField: "id",
  },
  music: {
    key: "music",
    view: "song",
    label: "Music",
    plural: "Music",
    emptyLabel: "songs",
    detailNoun: "song",
    request: "requestGeniusMusic",
    data: "pollGeniusMusicData",
    art: "pollGeniusMusicPosters",
    event: "musicPoster",
    detailRequest: "requestSongDetail",
    detailPoll: "pollSongDetail",
    detailArt: null,
    detailArtEvent: null,
    idField: "id",
  },
  book: {
    key: "book",
    view: "book",
    label: "Books",
    plural: "Books",
    emptyLabel: "books",
    detailNoun: "book",
    request: "requestBooks",
    data: "pollBookData",
    art: "pollBookCovers",
    event: "bookCover",
    detailRequest: "requestBookDetail",
    detailPoll: "pollBookDetail",
    detailArt: "pollBookDetailArt",
    detailArtEvent: "bookDetailArt",
    idField: "workKey",
  },
  game: {
    key: "game",
    view: "game",
    label: "Games",
    plural: "Games",
    emptyLabel: "games",
    detailNoun: "game",
    request: "requestGames",
    data: "pollGameData",
    art: "pollGamePosters",
    event: "gamePoster",
    detailRequest: "requestGameDetail",
    detailPoll: "pollGameDetail",
    detailArt: "pollGameDetailArt",
    detailArtEvent: "gameDetailArt",
    idField: "id",
  },
};

export const DOMAIN_KEYS: DomainKey[] = ["music", "movie", "book", "game"];

/** Card identity for artwork routing, as a string. */
export function itemId(domain: DomainIpc, item: unknown): string {
  const record = item as Record<string, unknown>;
  return String(record[domain.idField] ?? "");
}
