import { DOMAINS } from "../bridge/domains";
import { getState, setState } from "./store";
import type {
  AnySession,
  AppState,
  BrowseState,
  DetailState,
  DomainKey,
  QueryKind,
  Sessions,
} from "./types";

/**
 * Named transitions. Async work lives in hooks and calls these; nothing here
 * touches the bridge, so the store stays testable in isolation.
 */

function patchSession(
  state: AppState,
  key: DomainKey,
  patch: (session: AnySession) => AnySession
): AppState {
  const current = state.sessions[key];
  const next = patch(current);
  // Identity-preserving: a drain that repeats the same value must not wake
  // every subscriber. The patch functions below already return their slice
  // unchanged in that case.
  if (next === current) return state;
  const sessions = { ...state.sessions, [key]: next };
  return { ...state, sessions: sessions as unknown as Sessions };
}

export function patchBrowse(
  state: AppState,
  key: DomainKey,
  patch: (browse: BrowseState<never>) => BrowseState<never>
): AppState {
  return patchSession(state, key, (session) => {
    const browse = patch(session.browse as BrowseState<never>) as AnySession["browse"];
    return browse === session.browse ? session : ({ ...session, browse } as AnySession);
  });
}

export function patchDetail(
  state: AppState,
  key: DomainKey,
  patch: (detail: DetailState<never> | null) => DetailState<never> | null
): AppState {
  return patchSession(state, key, (session) => {
    const detail = patch(session.detail as DetailState<never> | null) as AnySession["detail"];
    return detail === session.detail ? session : ({ ...session, detail } as AnySession);
  });
}

export function browseTitle(key: DomainKey, kind: QueryKind): string {
  if (kind === "recommend") return "AI Recommendations";
  if (kind === "search") return "Search Results";
  return `Trending ${DOMAINS[key].plural}`;
}

/** Switch the visible form. The session's own view decides browse vs detail. */
export function activateDomain(key: DomainKey): void {
  setState((state) => {
    // From Settings/Profile the same form's button must still leave the
    // overlay, so only Browse and Detail are "already there" no-ops.
    if (
      state.activeDomain === key &&
      state.view !== "settings" &&
      state.view !== "profile"
    ) {
      return state;
    }
    const target = state.sessions[key];
    const reopenDetail = target.view === "detail" && target.detail !== null;
    return {
      ...state,
      activeDomain: key,
      view: reopenDetail ? "detail" : "browse",
    };
  });
}

export function saveScroll(key: DomainKey, scrollY: number): void {
  setState((state) => {
    const session = state.sessions[key];
    if (session.scrollY === scrollY) return state;
    return patchSession(state, key, (current) => ({ ...current, scrollY }));
  });
}

export function rememberView(key: DomainKey, view: "browse" | "detail"): void {
  setState((state) =>
    patchSession(state, key, (session) =>
      session.view === view ? session : { ...session, view }
    )
  );
}

/** Start a browse load: title, skeleton, empty artwork, no open detail. */
export function startBrowse(key: DomainKey, kind: QueryKind, query: string): void {
  setState((state) => {
    const next = patchBrowse(state, key, (browse) => ({
      ...browse,
      status: "loading",
      title: browseTitle(key, kind),
      items: [],
      error: null,
      query,
      kind,
      artwork: {},
    }));
    const withDetail = patchDetail(next, key, () => null);
    return {
      ...withDetail,
      activeDomain: key,
      view: "browse",
      sessions: {
        ...withDetail.sessions,
        [key]: { ...withDetail.sessions[key], view: "browse" },
      },
    };
  });
}

export function finishBrowse(key: DomainKey, items: unknown[]): void {
  setState((state) =>
    patchBrowse(state, key, (browse) => ({
      ...browse,
      status: "ready",
      items: items as never[],
      error: null,
    }))
  );
}

export function failBrowse(key: DomainKey, error: string): void {
  setState((state) =>
    patchBrowse(state, key, (browse) => ({
      ...browse,
      status: "error",
      items: [],
      error,
    }))
  );
}

export function setBrowseArtwork(key: DomainKey, id: string, dataUri: string): void {
  if (!dataUri) return;
  setState((state) => {
    // Only the active form's map: a late event for a form the user left is
    // dropped, exactly like the vanilla page's state.domain guard.
    if (state.activeDomain !== key) return state;
    const browse = state.sessions[key].browse;
    if (browse.artwork[id] === dataUri) return state;
    return patchBrowse(state, key, (current) => ({
      ...current,
      artwork: { ...current.artwork, [id]: dataUri },
    }));
  });
}

/** Open one item's detail: loading shell now, the hook fires the request. */
export function openDetail(key: DomainKey, item: unknown): void {
  setState((state) => {
    const next = patchDetail(state, key, (current) => ({
      status: "loading",
      nonce: (current?.nonce ?? 0) + 1,
      item,
      detail: null,
      art: {},
      error: null,
    }));
    return {
      ...next,
      activeDomain: key,
      view: "detail",
      sessions: {
        ...next.sessions,
        [key]: { ...next.sessions[key], view: "detail" },
      },
    };
  });
}

export function finishDetail(
  key: DomainKey,
  detail: unknown,
  art?: Record<string, string>
): void {
  setState((state) =>
    patchDetail(state, key, (current) => ({
      status: "ready",
      nonce: current?.nonce ?? 0,
      item: current?.item ?? null,
      detail: detail as never,
      art: { ...(current?.art ?? {}), ...(art ?? {}) },
      error: null,
    }))
  );
}

export function failDetail(key: DomainKey, error: string): void {
  setState((state) =>
    patchDetail(state, key, (current) => ({
      status: "error",
      nonce: current?.nonce ?? 0,
      item: current?.item ?? null,
      detail: null,
      art: current?.art ?? {},
      error,
    }))
  );
}

export function setDetailArt(key: DomainKey, slot: string, dataUri: string): void {
  if (!dataUri) return;
  setState((state) =>
    patchDetail(state, key, (current) => {
      if (!current || current.art[slot] === dataUri) return current;
      return { ...current, art: { ...current.art, [slot]: dataUri } };
    })
  );
}

export function backToBrowse(key: DomainKey): void {
  setState((state) => ({
    ...state,
    view: "browse",
    sessions: {
      ...state.sessions,
      [key]: { ...state.sessions[key], view: "browse" },
    },
  }));
}

export function openSettings(): void {
  setState((state) => ({
    ...state,
    settingsReturn: state.view === "detail" ? "detail" : "browse",
    view: "settings",
  }));
}

export function closeSettings(): void {
  setState((state) =>
    state.view !== "settings" ? state : { ...state, view: state.settingsReturn }
  );
}

/** Profile is an overlay page like Settings: remember what it covered. */
export function openProfile(): void {
  setState((state) => {
    if (state.view === "profile") return state;
    return {
      ...state,
      profileReturn: state.view,
      view: "profile",
    };
  });
}

export function closeProfile(): void {
  setState((state) => {
    if (state.view !== "profile") return state;
    const profile = state.profile.editing
      ? { ...state.profile, editing: false }
      : state.profile;
    const view = state.profileReturn === "profile" ? "browse" : state.profileReturn;
    return { ...state, view, profile };
  });
}

export function startProfileEdit(): void {
  setState((state) =>
    state.profile.editing
      ? state
      : { ...state, profile: { ...state.profile, editing: true } }
  );
}

export function cancelProfileEdit(): void {
  setState((state) =>
    state.profile.editing
      ? { ...state, profile: { ...state.profile, editing: false } }
      : state
  );
}

/**
 * Save the page-local identity. A blank field keeps the stored value, the same
 * forgiving rule the Settings form uses for an untouched API key.
 */
export function saveProfileEdit(name: string, memberSince: string): void {
  setState((state) => {
    if (!state.profile.editing) return state;
    return {
      ...state,
      profile: {
        ...state.profile,
        name: name.trim() || state.profile.name,
        memberSince: memberSince.trim() || state.profile.memberSince,
        editing: false,
      },
    };
  });
}

/** Escape closes the editor first, then the page. */
export function escapeProfile(): void {
  const state = getState();
  if (state.view !== "profile") return;
  if (state.profile.editing) cancelProfileEdit();
  else closeProfile();
}

export function toggleAi(): void {
  setState((state) => ({ ...state, ai: !state.ai }));
}

/** Current session snapshot, for hooks that need the active form's data. */
export function activeSession(): AnySession {
  const state = getState();
  return state.sessions[state.activeDomain];
}
