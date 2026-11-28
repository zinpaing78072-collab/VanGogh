/**
 * Typed mirror of the Java bridge contract.
 *
 * There is no schema export on the Java side, so these interfaces are
 * hand-written. Each group names the record it mirrors; when a record changes,
 * change it here in the same commit. `null` is spelled `| null` because Gson
 * emits JSON null for absent fields (never `undefined`).
 *
 * Java sources:
 *   vangogh/movie/Movie.java, MovieDetail.java
 *   vangogh/game/Game.java, GameDetail.java
 *   vangogh/music/Song.java, SongDetail.java, Credit.java, MediaLink.java,
 *           LyricsLine.java, RelatedSong.java
 *   vangogh/book/Book.java, BookDetail.java
 *   vangogh/controller/*Controller.java (the reply wrappers)
 *   vangogh/llm/LlmConfig.java, vangogh/theme/ThemeConfig.java
 */

// --------------------------------------------------------------- browse items

/** vangogh/movie/Movie.java */
export interface Movie {
  id: number;
  title: string;
  year: number;
  rating: number;
  genre: string;
  overview: string;
  posterUrl: string;
}

/** vangogh/game/Game.java — the API's own snake_case field names. */
export interface Game {
  id: number;
  game_title: string;
  release_date: string;
  /** TheGamesDB's age rating string, e.g. "E10+ - Everyone 10+". */
  ageRating: string;
  genre: string;
  overview: string;
  posterUrl: string;
}

/** vangogh/music/Song.java */
export interface Song {
  id: string;
  title: string;
  artist: string;
  year: number;
  primaryType: string;
  genre: string;
  posterUrl: string;
}

/** vangogh/book/Book.java — the id is the Open Library work key. */
export interface Book {
  workKey: string;
  title: string;
  author: string;
  year: number;
  rating: number;
  subjects: string;
  firstSentence: string;
  pages: number;
  editionCount: number;
  coverUrl: string;
}

// ------------------------------------------------------------------- details

/** vangogh/movie/MovieDetail.java */
export interface MovieDetail {
  id: number;
  title: string;
  tagline: string;
  overview: string;
  releaseDate: string;
  year: number;
  runtimeMinutes: number;
  certification: string;
  rating: number;
  voteCount: number;
  genres: string[];
  keywords: string[];
  posterUrl: string;
  tmdbUrl: string;
  trailerUrl: string;
  facts: MovieFact[];
  cast: MovieCredit[];
  stills: MovieStill[];
  similar: SimilarMovie[];
}

export interface MovieFact {
  label: string;
  value: string;
  muted: boolean;
}

export interface MovieCredit {
  name: string;
  role: string;
  crew: boolean;
  imageUrl: string;
}

export interface MovieStill {
  caption: string;
  imageUrl: string;
}

export interface SimilarMovie {
  movie: Movie;
  posterUrl: string;
}

/** vangogh/game/GameDetail.java */
export interface GameDetail {
  id: number;
  title: string;
  releaseDate: string;
  year: number;
  rating: string;
  players: number;
  genres: string[];
  platforms: string[];
  publishers: string[];
  developers: string[];
  overview: string;
  youtubeUrl: string;
  recordUrl: string;
  boxartUrl: string;
  heroUrl: string;
  stillUrls: string[];
  facts: GameFact[];
  relatedSource: string;
  related: RelatedGame[];
}

export interface GameFact {
  label: string;
  value: string;
}

export interface RelatedGame {
  id: number;
  title: string;
  year: number;
  rating: string;
  genre: string;
  posterUrl: string;
}

/** vangogh/music/SongDetail.java */
export interface SongDetail {
  id: string;
  title: string;
  artist: string;
  album: string;
  releaseDate: string;
  year: number;
  writers: string[];
  producers: string[];
  featuredArtists: string[];
  geniusUrl: string;
  pageviews: number;
  lyricsState: string;
  artUrl: string;
  lyrics: LyricsLine[];
  durationSeconds: number;
  audioUrl: string;
  related: RelatedSong[];
  releaseDateDisplay: string;
  language: string;
  translationCount: number;
  relationshipsIndexUrl: string;
  credits: Credit[];
  media: MediaLink[];
  artPrimaryColor: string;
  artSecondaryColor: string;
  artTextColor: string;
  about: string;
  albumUrl: string;
  albumCoverUrl: string;
  artistImageUrl: string;
  artistUrl: string;
  artistVerified: boolean;
  recordingLocation: string;
  contributors: number;
  transcribers: number;
  acceptedAnnotations: number;
  annotationCount: number;
  pyongsCount: number;
}

export interface Credit {
  label: string;
  names: string[];
}

export interface MediaLink {
  provider: string;
  type: string;
  url: string;
  startSeconds: number;
}

export interface LyricsLine {
  time: string;
  text: string;
}

export interface RelatedSong {
  id: string;
  title: string;
  artist: string;
  relation: string;
  posterUrl: string;
  durationSeconds: number;
}

/** vangogh/book/BookDetail.java */
export interface BookDetail {
  workKey: string;
  title: string;
  year: number;
  pages: number;
  editionCount: number;
  rating: number;
  ratingCount: number;
  description: string;
  sampleUrl: string;
  subjects: string[];
  setting: string;
  coverUrl: string;
  openLibraryUrl: string;
  facts: BookFact[];
  editions: Edition[];
  author: BookAuthor;
  similar: Book[];
}

export interface BookFact {
  label: string;
  value: string;
  muted: boolean;
  spanAll: boolean;
}

export interface Edition {
  year: string;
  pages: string;
  isbn: string;
  coverUrl: string;
}

export interface BookAuthor {
  name: string;
  dates: string;
  bio: string;
  photoUrl: string;
  wikipediaUrl: string;
  openLibraryUrl: string;
}

// -------------------------------------------------------------------- replies

export type DrainStatus = "pending" | "done" | "error" | "superseded";

export interface DataReply<I> {
  generation: number;
  status: DrainStatus;
  error: string | null;
  items: I[] | null;
}

export interface DetailReply<D> {
  generation: number;
  status: DrainStatus;
  error: string | null;
  detail: D | null;
}

/** vangogh/controller/GeniusController.java SongDetailReply */
export interface SongDetailReply extends DetailReply<SongDetail> {
  artDataUri: string | null;
}

/** vangogh/controller/SettingsController.java LlmSettingsReply */
export interface LlmSettingsReply {
  status: string;
  error: string | null;
  baseUrl: string;
  model: string;
  timeoutSeconds: number;
  titles: number;
  /** 0.0–2.0, the range every OpenAI-compatible endpoint accepts. */
  temperature: number;
  hasApiKey: boolean;
  configPath: string;
}

/** vangogh/theme/ThemeConfig.java Choice */
export interface ThemeChoice {
  id: string;
  label: string;
}

/** vangogh/controller/ThemeController.java ThemeReply */
export interface ThemeReply {
  status: string;
  error: string | null;
  theme: string;
  themes: ThemeChoice[];
}

// --------------------------------------------------------------------- events

/** Artwork drain events (browse grids and the detail streams). */
export interface ArtworkPayload {
  id: string | number;
  dataUri: string;
}

export interface DetailArtPayload {
  slot: string;
  dataUri: string;
}

// ------------------------------------------------------- command/event tables

export type QueryKind = "trending" | "search" | "recommend";

export interface CommandMap {
  // movies
  requestMovies: { args: { kind: QueryKind; query: string }; reply: number };
  pollMovieData: { args: { generation: number }; reply: DataReply<Movie> };
  pollMoviePosters: { args: Record<string, never>; reply: number };
  requestMovieDetail: { args: { id: number }; reply: number };
  pollMovieDetail: { args: { generation: number }; reply: DetailReply<MovieDetail> };
  pollMovieDetailArt: { args: Record<string, never>; reply: number };

  // games
  requestGames: { args: { kind: QueryKind; query: string }; reply: number };
  pollGameData: { args: { generation: number }; reply: DataReply<Game> };
  pollGamePosters: { args: Record<string, never>; reply: number };
  requestGameDetail: { args: { id: number }; reply: number };
  pollGameDetail: { args: { generation: number }; reply: DetailReply<GameDetail> };
  pollGameDetailArt: { args: Record<string, never>; reply: number };

  // music
  requestGeniusMusic: { args: { kind: QueryKind; query: string }; reply: number };
  pollGeniusMusicData: { args: { generation: number }; reply: DataReply<Song> };
  pollGeniusMusicPosters: { args: Record<string, never>; reply: number };
  requestSongDetail: { args: { id: string }; reply: number };
  pollSongDetail: { args: { generation: number }; reply: SongDetailReply };

  // books
  requestBooks: { args: { kind: QueryKind; query: string }; reply: number };
  pollBookData: { args: { generation: number }; reply: DataReply<Book> };
  pollBookCovers: { args: Record<string, never>; reply: number };
  requestBookDetail: { args: { workKey: string }; reply: number };
  pollBookDetail: { args: { generation: number }; reply: DetailReply<BookDetail> };
  pollBookDetailArt: { args: Record<string, never>; reply: number };

  // settings + theme
  loadLlmSettings: { args: Record<string, never>; reply: LlmSettingsReply };
  saveLlmSettings: {
    args: {
      baseUrl: string;
      model: string;
      apiKey: string;
      timeoutSeconds: number;
      titles: number;
      temperature: number;
    };
    reply: LlmSettingsReply;
  };
  loadTheme: { args: Record<string, never>; reply: ThemeReply };
  saveTheme: { args: { theme: string }; reply: ThemeReply };
}

export type CommandName = keyof CommandMap;
export type CommandArgs<K extends CommandName> = CommandMap[K]["args"];
export type CommandReply<K extends CommandName> = CommandMap[K]["reply"];

export interface EventMap {
  poster: ArtworkPayload;
  gamePoster: ArtworkPayload;
  musicPoster: ArtworkPayload;
  bookCover: ArtworkPayload;
  movieDetailArt: DetailArtPayload;
  bookDetailArt: DetailArtPayload;
  gameDetailArt: DetailArtPayload;
}

export type EventName = keyof EventMap;
