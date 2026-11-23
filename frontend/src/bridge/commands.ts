import { invoke } from "./bridge";
import { DOMAINS } from "./domains";
import type {
  Book,
  DataReply,
  DetailReply,
  Game,
  Movie,
  QueryKind,
  Song,
} from "./types";

/**
 * Domain → command switches, in one place.
 *
 * The command maps are keyed by exact command name, so a generic
 * `invoke(domain.data, …)` would not type-check; these four switches keep the
 * hooks free of IPC detail and give TypeScript one narrow call per form.
 */

export function requestBrowse(
  domain: keyof typeof DOMAINS,
  kind: QueryKind,
  query: string
): Promise<number> {
  switch (domain) {
    case "movie":
      return invoke("requestMovies", { kind, query });
    case "game":
      return invoke("requestGames", { kind, query });
    case "music":
      return invoke("requestGeniusMusic", { kind, query });
    case "book":
      return invoke("requestBooks", { kind, query });
  }
}

export function pollBrowse(
  domain: keyof typeof DOMAINS,
  generation: number
): Promise<DataReply<unknown>> {
  switch (domain) {
    case "movie":
      return invoke("pollMovieData", { generation });
    case "game":
      return invoke("pollGameData", { generation });
    case "music":
      return invoke("pollGeniusMusicData", { generation });
    case "book":
      return invoke("pollBookData", { generation });
  }
}

export function pollArt(domain: keyof typeof DOMAINS): Promise<number> {
  switch (domain) {
    case "movie":
      return invoke("pollMoviePosters", {});
    case "game":
      return invoke("pollGamePosters", {});
    case "music":
      return invoke("pollGeniusMusicPosters", {});
    case "book":
      return invoke("pollBookCovers", {});
  }
}

export function requestDetail(
  domain: keyof typeof DOMAINS,
  item: unknown
): Promise<number> {
  switch (domain) {
    case "movie":
      return invoke("requestMovieDetail", { id: Number((item as Movie).id) });
    case "game":
      return invoke("requestGameDetail", { id: Number((item as Game).id) });
    case "music":
      return invoke("requestSongDetail", { id: String((item as Song).id) });
    case "book":
      return invoke("requestBookDetail", { workKey: String((item as Book).workKey) });
  }
}

/** SongDetailReply is the only reply that also carries the artwork data URI. */
export function pollDetail(
  domain: keyof typeof DOMAINS,
  generation: number
): Promise<DetailReply<unknown> & { artDataUri?: string | null }> {
  switch (domain) {
    case "movie":
      return invoke("pollMovieDetail", { generation });
    case "game":
      return invoke("pollGameDetail", { generation });
    case "music":
      return invoke("pollSongDetail", { generation });
    case "book":
      return invoke("pollBookDetail", { generation });
  }
}

export function pollDetailArt(domain: keyof typeof DOMAINS): Promise<number> {
  switch (domain) {
    case "movie":
      return invoke("pollMovieDetailArt", {});
    case "game":
      return invoke("pollGameDetailArt", {});
    case "book":
      return invoke("pollBookDetailArt", {});
    case "music":
      return Promise.resolve(-1);
  }
}
