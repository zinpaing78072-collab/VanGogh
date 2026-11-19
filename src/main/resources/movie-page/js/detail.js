/* The click-to-open seam. Music cards open the real song-detail view; the
 * other forms still show the placeholder alert until their detail-view slices
 * land (one content form at a time, see AGENTS.md).
 *
 * The card builders keep calling this through the renderer and never touch it.
 */
import { DOMAINS } from "./domains.js";
import { openSongDetail } from "./song-detail.js";

export function openDetail(domainKey, item) {
    if (domainKey === "music") {
        openSongDetail(item);
        return;
    }
    const detail = DOMAINS[domainKey].detail(item);
    alert(`${detail.title}\n\n${detail.body || ""}`);
}
