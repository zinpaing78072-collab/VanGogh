/* The click-to-open seam. Music, movie and book cards open their real detail
 * views; games still show the placeholder alert until that detail-view slice
 * lands (one content form at a time, see AGENTS.md).
 *
 * The card builders keep calling this through the renderer and never touch it.
 */
import { DOMAINS } from "./domains.js";
import { openSongDetail } from "./song-detail.js";
import { openMovieDetail } from "./movie-detail.js";
import { openBookDetail } from "./book-detail.js";

export function openDetail(domainKey, item) {
    if (domainKey === "music") {
        openSongDetail(item);
        return;
    }
    if (domainKey === "movie") {
        openMovieDetail(item);
        return;
    }
    if (domainKey === "book") {
        openBookDetail(item);
        return;
    }
    const detail = DOMAINS[domainKey].detail(item);
    alert(`${detail.title}\n\n${detail.body || ""}`);
}
