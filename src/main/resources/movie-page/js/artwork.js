/* Artwork routing: one subscriber per domain, one setter.
 *
 * Each payload is { id, dataUri }. setArtwork finds the card through the
 * data-<key>-id identity baseCard set; CSS.escape covers ids with slashes or
 * quotes (Open Library work keys, MusicBrainz MBIDs). Artwork updates touch
 * one card's image and never rebuild the grid.
 */
import { grid } from "./dom.js";
import { DOMAINS, DOMAIN_KEYS } from "./domains.js";

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
