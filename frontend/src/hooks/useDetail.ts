import { useEffect } from "react";
import { bridgeReady } from "../bridge/bridge";
import { pollDetail, pollDetailArt, requestDetail } from "../bridge/commands";
import { DOMAINS, itemId } from "../bridge/domains";
import { failDetail, finishDetail, setDetailArt } from "../app/actions";
import { useStore } from "../app/store";
import type { DomainKey } from "../app/types";
import { messageOf } from "../lib/format";

/**
 * Detail request lifecycle. Its own sequence, so opening a detail never
 * supersedes the browse grid (or its poster stream) and vice versa.
 *
 * The hook is mounted for the active form. It fires the request whenever the
 * visible detail is not ready — including a detail that was still loading when
 * the user left the form, which is re-requested on return — and resumes the
 * artwork drain once the detail is ready. The drains are idempotent: a
 * completed stream answers −1 at once.
 */

const DETAIL_POLL_MS = 100;
const DETAIL_ART_POLL_MS = 150;

let detailSeq = 0;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function cancelDetailRequests(): void {
  detailSeq += 1;
}

async function runDetail(domain: DomainKey, item: unknown, seq: number): Promise<void> {
  const info = DOMAINS[domain];
  const current = () => seq === detailSeq;

  if (!bridgeReady()) {
    failDetail(domain, "API bridge not ready yet. Please wait a moment and try again.");
    return;
  }

  try {
    const generation = await requestDetail(domain, item);
    if (!current()) return;

    let reply;
    while (current()) {
      try {
        reply = await pollDetail(domain, generation);
      } catch (error) {
        if (current()) {
          failDetail(domain, "Failed to load " + info.detailNoun + " details: " + messageOf(error));
        }
        return;
      }
      if (!reply || reply.status === "done") break;
      if (reply.status === "error") {
        if (current()) failDetail(domain, reply.error || `Failed to load ${info.detailNoun} details.`);
        return;
      }
      if (reply.status === "superseded") return;
      await sleep(DETAIL_POLL_MS);
    }
    if (!current()) return;
    if (!reply || !reply.detail) {
      failDetail(domain, `Failed to load ${info.detailNoun} details.`);
      return;
    }

    // Songs carry their artwork in the reply; everything else streams it.
    const art = reply.artDataUri ? { art: reply.artDataUri } : undefined;
    finishDetail(domain, reply.detail, art);
  } catch (error) {
    if (!current()) return;
    failDetail(domain, "Failed to load " + info.detailNoun + " details: " + messageOf(error));
  }
}

async function runDetailArt(domain: DomainKey, seq: number): Promise<void> {
  if (!DOMAINS[domain].detailArt) return;
  const current = () => seq === detailSeq;

  while (current() && bridgeReady()) {
    let pending;
    try {
      pending = await pollDetailArt(domain);
    } catch {
      return;
    }
    if (pending == null || pending < 0) return;
    await sleep(DETAIL_ART_POLL_MS);
  }
}

export function useDetail(domain: DomainKey): void {
  const view = useStore((state) => state.view);
  const detail = useStore((state) => state.sessions[domain].detail);
  const identity = detail ? itemId(DOMAINS[domain], detail.item) : null;
  const nonce = detail?.nonce ?? 0;
  const status = detail?.status ?? "idle";

  useEffect(() => {
    if (view !== "detail" || !detail || detail.status === "ready") return;
    const seq = ++detailSeq;
    void runDetail(domain, detail.item, seq);
    return () => {
      if (seq === detailSeq) detailSeq += 1;
    };
    // `detail.status` is deliberately not a dependency: a failed detail must
    // not retry itself in a loop, and a ready one is handled by the art effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [domain, view, identity, nonce]);

  useEffect(() => {
    if (view !== "detail" || !detail || detail.status !== "ready") return;
    if (!DOMAINS[domain].detailArt) return;
    const seq = ++detailSeq;
    void runDetailArt(domain, seq);
    return () => {
      if (seq === detailSeq) detailSeq += 1;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [domain, view, identity, status]);
}

/** Route one detail-art event into the right slot of the right session. */
export function recordDetailArt(
  domain: DomainKey,
  slot: string,
  dataUri: string
): void {
  setDetailArt(domain, slot, dataUri);
}
