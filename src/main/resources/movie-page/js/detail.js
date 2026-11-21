/* The click-to-open seam. Music, movie, book and game cards open their real
 * detail views; the DOMAINS row's `detail(item)` stays as the generic fallback
 * for any future form that has not grown a view yet.
 *
 * The card builders keep calling this through the renderer and never touch it.
 */
import { DOMAINS } from "./domains.js";
import { openSongDetail } from "./song-detail.js";
import { openMovieDetail } from "./movie-detail.js";
import { openBookDetail } from "./book-detail.js";
import { openGameDetail } from "./game-detail.js";

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
    if (domainKey === "game") {
        openGameDetail(item);
        return;
    }
    const detail = DOMAINS[domainKey].detail(item);
    alert(`${detail.title}\n\n${detail.body || ""}`);
}
