/**
 * Profile fixtures — the hand-drawn screen's headline numbers and collection
 * counts. This is a presentation slice: nothing here reads My Lists, Favorites
 * or a database, so the page can ship before those stores exist. Swapping these
 * arrays for real queries later changes this file only.
 */

export type ProfileIcon = "heart" | "playlist" | "activity" | "film" | "music" | "tv" | "book";

export interface ProfileStat {
  key: string;
  label: string;
  value: number;
  icon: ProfileIcon;
}

export interface ProfileCollection {
  key: string;
  title: string;
  count: number;
  icon: ProfileIcon;
}

export const PROFILE_STATS: ProfileStat[] = [
  { key: "favorite", label: "Favorite", value: 230, icon: "heart" },
  { key: "playlist", label: "Total Playlist", value: 12, icon: "playlist" },
  { key: "action", label: "Entertainment Action", value: 1034, icon: "activity" },
];

export const PROFILE_COLLECTIONS: ProfileCollection[] = [
  { key: "movies", title: "Movies Collection", count: 112, icon: "film" },
  { key: "music", title: "Music Collection", count: 45, icon: "music" },
  { key: "tv", title: "TV Shows Collection", count: 50, icon: "tv" },
  { key: "books", title: "Books Collection", count: 94, icon: "book" },
];
