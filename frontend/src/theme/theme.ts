import { useSyncExternalStore } from "react";
import { invoke } from "../bridge/bridge";
import type { ThemeChoice, ThemeReply } from "../bridge/types";

/**
 * Theme switching — the one place that knows a theme exists.
 *
 * A theme is CSS only: move <html data-theme> and the #themeSkin href, nothing
 * else. The list is never kept here as a source of truth; `loadTheme` returns
 * it from ThemeConfig.KNOWN in Java, so a new skin is one CSS file plus one
 * Java row.
 */

const DEFAULT_THEME = "starry";

export interface ThemeState {
  theme: string;
  themes: ThemeChoice[];
  status: string;
  tone: "" | "ok" | "error";
  saving: boolean;
}

let themeState: ThemeState = {
  theme: DEFAULT_THEME,
  themes: [],
  status: "",
  tone: "",
  saving: false,
};

const listeners = new Set<() => void>();

function setThemeState(next: Partial<ThemeState>): void {
  themeState = { ...themeState, ...next };
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useTheme(): ThemeState {
  return useSyncExternalStore(
    subscribe,
    () => themeState,
    () => themeState
  );
}

/** Point the page at one theme. Safe before the reply arrives. */
export function applyTheme(id: string): void {
  if (!id) return;
  setThemeState({ theme: id });
  document.documentElement.dataset.theme = id;

  const skin = document.getElementById("themeSkin");
  if (skin) {
    // Compare the authored value, not the resolved URL: assigning the same
    // href again would refetch the sheet the browser already has.
    const href = "theme-" + id + ".css";
    if (skin.getAttribute("href") !== href) skin.setAttribute("href", href);
  }
}

function adopt(reply: ThemeReply | null | undefined): void {
  if (!reply || reply.status !== "ok") return;
  if (Array.isArray(reply.themes)) setThemeState({ themes: reply.themes });
  applyTheme(reply.theme || DEFAULT_THEME);
}

/** Load the stored theme and the list. Never throws. */
export async function initTheme(): Promise<string> {
  try {
    adopt(await invoke("loadTheme", {}));
  } catch (error) {
    console.warn("[theme] loadTheme failed, keeping", themeState.theme, error);
  }
  return themeState.theme;
}

/** Settings opened: retry a failed startup load, then repaint. */
export async function openThemeCard(): Promise<void> {
  if (themeState.themes.length === 0) {
    await initTheme();
  }
}

export async function chooseTheme(id: string): Promise<void> {
  if (themeState.saving || id === themeState.theme) return;
  const previous = themeState.theme;
  applyTheme(id);
  setThemeState({ status: "", tone: "", saving: true });

  try {
    const reply = await invoke("saveTheme", { theme: id });
    if (!reply || reply.status === "error") {
      applyTheme(previous);
      setThemeState({
        status: (reply && reply.error) || "Could not save the theme.",
        tone: "error",
      });
      return;
    }
    adopt(reply);
    setThemeState({ status: "Theme saved.", tone: "ok" });
  } catch (error) {
    applyTheme(previous);
    setThemeState({
      status: "Could not save the theme: " + String(error),
      tone: "error",
    });
  } finally {
    setThemeState({ saving: false });
  }
}

/** Test seam. */
export function resetTheme(): void {
  themeState = {
    theme: DEFAULT_THEME,
    themes: [],
    status: "",
    tone: "",
    saving: false,
  };
  listeners.forEach((listener) => listener());
}
