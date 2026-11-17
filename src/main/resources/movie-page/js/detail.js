/* The click-to-open seam. The detail-view slice replaces this function; the
 * card builders keep calling it through the renderer and never touch it.
 */
import { DOMAINS } from "./domains.js";

export function openDetail(domainKey, item) {
    const detail = DOMAINS[domainKey].detail(item);
    alert(`${detail.title}\n\n${detail.body || ""}`);
}
