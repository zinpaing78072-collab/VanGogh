/* Artwork routing: one subscriber per domain, one setter.
 *
 * Each payload is { id, dataUri }. setArtwork finds the card through the
 * data-<key>-id identity baseCard set; CSS.escape covers ids with slashes or
 * quotes (Open Library work keys, MusicBrainz MBIDs). Artwork updates touch
 * one card's image and never rebuild the grid.
 */
import { grid } from "./dom.js";
import { DOMAINS, DOMAIN_KEYS } from "./domains.js";
import { state } from "./state.js";

export function subscribeArtwork() {
    if (!window.krema || typeof window.krema.on !== "function") return;
    DOMAIN_KEYS.forEach((domainKey) => {
        window.krema.on(DOMAINS[domainKey].event, function (data) {
            if (data && data.payload) {
                setArtwork(domainKey, data.payload.id, data.payload.dataUri);
            }
        });
    });
}

export function setArtwork(domainKey, id, dataUri) {
    if (!dataUri) return;
    // Only the active form's map: an artwork event from a superseded form must
    // not leak into the grid the page is painting now. Keeping the URI in state
    // is what lets a restored session repaint its posters without a refetch.
    if (domainKey === state.domain) {
        state.artwork[String(id)] = dataUri;
    }
    paintCard(domainKey, id, dataUri);
}

// Reapply the active form's saved artwork after renderCards() rebuilt the grid.
export function applyArtwork(domainKey) {
    if (domainKey !== state.domain) return;
    Object.keys(state.artwork).forEach((id) => paintCard(domainKey, id, state.artwork[id]));
}

function paintCard(domainKey, id, dataUri) {
    const card = grid.querySelector(`[data-${domainKey}-id="${CSS.escape(String(id))}"]`);
    if (!card) return;
    const img = card.querySelector(".poster-img");
    const placeholder = card.querySelector(".poster-placeholder");
    if (!img) return;
    img.src = dataUri;
    img.classList.remove("hidden");
    if (placeholder) {
        placeholder.classList.add("hidden");
    }
}
