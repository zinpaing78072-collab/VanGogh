/* The click-to-open seam. Music, movie, book and game cards open their real
 * detail views; the DOMAINS row's `detail(item)` stays as the generic fallback
 * for any future form that has not grown a view yet.
 *
 * The card builders keep calling this through the renderer and never touch it.
 */
import { DOMAINS } from "./domains.js";
import { openSongDetail } from "./song-detail.js";
import { openMovieDetail, resumeMovieArt } from "./movie-detail.js";
import { openBookDetail, resumeBookArt } from "./book-detail.js";
import { openGameDetail, resumeGameArt } from "./game-detail.js";

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

/* A session restores a ready detail instantly from state; this resumes the
 * backend drain for any artwork slot that had not arrived when the user left.
 * Songs carry their artwork in the detail reply, so they have nothing to resume. */
export function resumeDetailArt(domainKey, seq) {
    if (domainKey === "movie") {
        resumeMovieArt(seq);
    } else if (domainKey === "book") {
        resumeBookArt(seq);
    } else if (domainKey === "game") {
        resumeGameArt(seq);
    }
}
