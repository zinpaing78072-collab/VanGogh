import { useEffect } from "react";
import { bridgeReady } from "../bridge/bridge";
import { pollArt, pollBrowse, requestBrowse } from "../bridge/commands";
import { failBrowse, finishBrowse, startBrowse } from "../app/actions";
import { getState } from "../app/store";
import type { DomainKey, QueryKind } from "../app/types";
import { messageOf } from "../lib/format";

/**
 * Browse request lifecycle: kickoff, data drain, artwork drain.
 *
 * Delivery is drained, never pushed. The kickoff returns a generation at once
 * (the UI thread never blocks on network); the data drain is polled until that
 * generation resolves, then the poster drain is polled to completion.
 *
 * `requestSeq` is the page-wide supersede guard, exactly like the vanilla
 * `state.js` counter: any newer load — a search, a form switch, a restore —
 * invalidates every continuation still in flight.
 */

const DATA_POLL_MS = 100;
const ART_POLL_MS = 150;

let requestSeq = 0;
let posterSeq = 0;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Invalidate every in-flight browse continuation. */
export function cancelRequests(): void {
  requestSeq += 1;
  posterSeq += 1;
}

export async function loadContent(
  domain: DomainKey,
  kind: QueryKind,
  query: string
): Promise<void> {
  const seq = ++requestSeq;
  posterSeq += 1;
  const current = () => seq === requestSeq;

  window.scrollTo(0, 0);
  startBrowse(domain, kind, query);

  if (!bridgeReady()) {
    failBrowse(domain, "API bridge not ready yet. Please wait a moment and try again.");
    return;
  }

  try {
    const generation = await requestBrowse(domain, kind, query);
    if (!current()) return;

    let reply;
    while (current()) {
      try {
        reply = await pollBrowse(domain, generation);
      } catch (error) {
        if (current()) failBrowse(domain, "Failed to load content: " + messageOf(error));
        return;
      }
      if (!reply || reply.status === "done") break;
      if (reply.status === "error") {
        if (current()) failBrowse(domain, reply.error || "Failed to load content.");
        return;
      }
      if (reply.status === "superseded") return;
      await sleep(DATA_POLL_MS);
    }
    if (!current() || !reply) return;

    finishBrowse(domain, reply.items ?? []);
    void drainPosters(domain);
  } catch (error) {
    if (!current()) return;
    failBrowse(domain, "Failed to load content: " + messageOf(error));
  }
}

async function drainPosters(domain: DomainKey): Promise<void> {
  const seq = ++posterSeq;
  while (seq === posterSeq && bridgeReady()) {
    let pending;
    try {
      pending = await pollArt(domain);
    } catch {
      return;
    }
    if (pending == null || pending < 0) return;
    await sleep(ART_POLL_MS);
  }
}

/** Resume a stream that was interrupted when the user left the form. */
export function resumePosters(domain: DomainKey): void {
  if (getState().activeDomain !== domain) return;
  void drainPosters(domain);
}

/**
 * Bootstrap only: the first page load seeds the default form's trending grid.
 * Every later switch goes through `switchDomain`, which fires its own request
 * synchronously so fast clicks open a generation each.
 */
export function useContent(domain: DomainKey): void {
  useEffect(() => {
    const session = getState().sessions[domain];
    if (session.view !== "detail" && session.browse.status === "idle") {
      void loadContent(domain, "trending", "");
    }
    return () => cancelRequests();
    // Intentionally mount-only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}
