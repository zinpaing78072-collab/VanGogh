import { activateDomain, saveScroll } from "./actions";
import { getState } from "./store";
import type { DomainKey } from "./types";
import { loadContent, resumePosters } from "../hooks/useContent";

/**
 * The sidebar's form switch, in one place.
 *
 * The click fires a request synchronously — three fast clicks must open three
 * generations, as the vanilla page did — so this cannot be a side effect of the
 * render that follows. A session that was still loading when the user left is
 * re-fired; a ready one is restored with no content request, only its poster
 * stream resumed; a detail session reopens its detail.
 *
 * Scroll is captured before the switch (the load about to run scrolls to the
 * top) and restored after the repaint.
 */
export function switchDomain(key: DomainKey): void {
  const state = getState();
  if (
    key === state.activeDomain &&
    state.view !== "settings" &&
    state.view !== "profile"
  ) {
    return;
  }

  // Settings/Profile scroll their own page; the covered form keeps the scroll
  // it had, so leaving the overlay must not overwrite it with 0.
  if (state.view !== "settings" && state.view !== "profile") {
    saveScroll(state.activeDomain, window.scrollY);
  }

  const target = state.sessions[key];

  if (target.view === "detail" && target.detail) {
    activateDomain(key);
    restoreScroll(target.scrollY);
    return;
  }

  const refreshMusicChart =
    key === "music" &&
    target.browse.status === "ready" &&
    target.browse.kind === "trending";
  if (target.browse.status === "idle" || refreshMusicChart) {
    void loadContent(key, "trending", "");
    return;
  }

  if (target.browse.status === "loading") {
    void loadContent(key, target.browse.kind, target.browse.query);
    return;
  }

  activateDomain(key);
  resumePosters(key);
  restoreScroll(target.scrollY);
}

let scrollToken = 0;

/** Only the newest switch may move the page. */
export function restoreScroll(y: number): void {
  const token = ++scrollToken;
  const target = Number(y) || 0;
  requestAnimationFrame(() => {
    if (token === scrollToken) window.scrollTo(0, target);
  });
}
