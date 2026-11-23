import { useSyncExternalStore } from "react";

/**
 * The demo song transport: a UI-only timer until an audio source is wired.
 *
 * Module state, not React state, because the transport is rendered by the
 * player bar (a sibling of the detail view) while the detail page's buttons
 * drive it. One snapshot, one interval.
 */
export interface PlayerSnapshot {
  visible: boolean;
  playing: boolean;
  seconds: number;
  duration: number;
  title: string;
  artist: string;
  art: string;
}

let snapshot: PlayerSnapshot = {
  visible: false,
  playing: false,
  seconds: 0,
  duration: 0,
  title: "",
  artist: "",
  art: "",
};

let timer: number | null = null;
const listeners = new Set<() => void>();

function emit(patch: Partial<PlayerSnapshot>): void {
  snapshot = { ...snapshot, ...patch };
  listeners.forEach((listener) => listener());
}

export function usePlayer(): PlayerSnapshot {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    () => snapshot,
    () => snapshot
  );
}

function stopTimer(): void {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
}

export function stopPlayer(): void {
  stopTimer();
  if (snapshot.playing) emit({ playing: false });
}

export function togglePlayer(): void {
  if (snapshot.playing) {
    stopPlayer();
    return;
  }
  if (snapshot.duration <= 0) return;
  if (snapshot.seconds >= snapshot.duration) emit({ seconds: 0 });
  emit({ playing: true });
  timer = window.setInterval(() => {
    const next = Math.min(snapshot.duration, snapshot.seconds + 1);
    emit({ seconds: next });
    if (next >= snapshot.duration) stopPlayer();
  }, 1000);
}

export function restartPlayer(): void {
  emit({ seconds: 0 });
}

export function seekPlayer(seconds: number): void {
  const target = Math.min(snapshot.duration, Math.max(0, Math.round(seconds)));
  emit({ seconds: target });
}

export function seekPlayerRatio(ratio: number): void {
  if (snapshot.duration <= 0) return;
  seekPlayer(Math.min(1, Math.max(0, ratio)) * snapshot.duration);
}

/** Show the bar for one song and start from zero. */
export function resetPlayer(
  title: string,
  artist: string,
  durationSeconds: number,
  art: string
): void {
  stopTimer();
  emit({
    visible: true,
    playing: false,
    seconds: 0,
    duration: Math.max(0, Number(durationSeconds) || 0),
    title,
    artist,
    art,
  });
}

/** Hide the bar and clear its contents. */
export function hidePlayer(): void {
  stopTimer();
  if (!snapshot.visible && snapshot.seconds === 0) return;
  emit({
    visible: false,
    playing: false,
    seconds: 0,
    duration: 0,
    title: "",
    artist: "",
    art: "",
  });
}

/** Test seam. */
export function resetPlayerStore(): void {
  stopTimer();
  snapshot = {
    visible: false,
    playing: false,
    seconds: 0,
    duration: 0,
    title: "",
    artist: "",
    art: "",
  };
  listeners.forEach((listener) => listener());
}
