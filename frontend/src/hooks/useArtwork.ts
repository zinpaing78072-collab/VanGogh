import { useEffect } from "react";
import { on } from "../bridge/bridge";
import { DOMAINS, DOMAIN_KEYS } from "../bridge/domains";
import { setBrowseArtwork } from "../app/actions";
import { recordDetailArt } from "./useDetail";

/**
 * Four page-lifetime artwork subscriptions (Krema has no unsubscribe). A late
 * event for a form the user already left is dropped by setBrowseArtwork, the
 * same guard the vanilla page applied with `state.domain`.
 *
 * The subscription is passive: the drains are polled by useContent, and these
 * handlers only record the data URIs they deliver.
 */
export function useArtwork(): void {
  useEffect(() => {
    DOMAIN_KEYS.forEach((key) => {
      on(DOMAINS[key].event, (payload) => {
        setBrowseArtwork(key, String(payload.id), payload.dataUri);
      });

      const detailEvent = DOMAINS[key].detailArtEvent;
      if (detailEvent) {
        on(detailEvent, (payload) => {
          recordDetailArt(key, payload.slot, payload.dataUri);
        });
      }
    });
  }, []);
}
