import { useSyncExternalStore } from "react";
import type { AppState, BrowseState, DomainKey, Session } from "./types";

export function emptyBrowse(title: string): BrowseState<never> {
  return {
    status: "idle",
    title,
    items: [],
    error: null,
    query: "",
    kind: "trending",
    artwork: {},
  };
}

function emptySession<I, D>(title: string): Session<I, D> {
  return {
    browse: emptyBrowse(title) as BrowseState<I>,
    detail: null,
    view: "browse",
    scrollY: 0,
  };
}

export function initialState(): AppState {
  return {
    activeDomain: "movie",
    view: "browse",
    ai: false,
    settingsReturn: "browse",
    profileReturn: "browse",
    profile: {
      name: "John",
      memberSince: "March, 2024",
      editing: false,
    },
    sessions: {
      movie: emptySession("Trending Movies"),
      music: emptySession("Trending Music"),
      book: emptySession("Trending Books"),
      game: emptySession("Trending Games"),
    },
  };
}

let state: AppState = initialState();
const listeners = new Set<() => void>();

export function getState(): AppState {
  return state;
}

export function setState(updater: (current: AppState) => AppState): void {
  const next = updater(state);
  if (next === state) return;
  state = next;
  listeners.forEach((listener) => listener());
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Subscribe to one slice of the store. Select primitives or stable references
 * only — returning a fresh object every call makes useSyncExternalStore loop.
 */
export function useStore<T>(selector: (current: AppState) => T): T {
  return useSyncExternalStore(
    subscribe,
    () => selector(state),
    () => selector(state)
  );
}

/** Test seam: reset the module-level store between cases. */
export function resetStore(): void {
  state = initialState();
  listeners.forEach((listener) => listener());
}

export type { AppState, DomainKey };
