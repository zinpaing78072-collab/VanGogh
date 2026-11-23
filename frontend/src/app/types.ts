import type {
  Book,
  BookDetail,
  Game,
  GameDetail,
  Movie,
  MovieDetail,
  Song,
  SongDetail,
  QueryKind,
} from "../bridge/types";

export type { QueryKind };

export type DomainKey = "movie" | "music" | "book" | "game";

/** The global page view. "settings" sits outside the per-form sessions. */
export type ViewKind = "browse" | "detail" | "settings";

export type Status = "idle" | "loading" | "ready" | "error";

export interface BrowseState<I> {
  status: Status;
  title: string;
  items: I[];
  error: string | null;
  query: string;
  kind: QueryKind;
  /** item id -> data URI, filled by the artwork drains. */
  artwork: Record<string, string>;
}

export interface DetailState<D> {
  status: Status;
  /** Bumped on every open, so a retry re-fires the request. */
  nonce: number;
  /** The clicked card, kept for the loading shell. */
  item: unknown;
  detail: D | null;
  /** slot -> data URI ("poster", "cast:0", "still:1", …). */
  art: Record<string, string>;
  error: string | null;
}

export interface Session<I, D> {
  browse: BrowseState<I>;
  detail: DetailState<D> | null;
  /** What this form was showing when the user left it. */
  view: "browse" | "detail";
  scrollY: number;
}

/** Per-form sessions. The map *is* the store: no snapshot/copy step. */
export interface Sessions {
  movie: Session<Movie, MovieDetail>;
  music: Session<Song, SongDetail>;
  book: Session<Book, BookDetail>;
  game: Session<Game, GameDetail>;
}

export type AnySession = Sessions[DomainKey];

export interface AppState {
  activeDomain: DomainKey;
  view: ViewKind;
  ai: boolean;
  sessions: Sessions;
  /** Which view Settings was opened over, so Cancel/Escape can return. */
  settingsReturn: "browse" | "detail";
}
