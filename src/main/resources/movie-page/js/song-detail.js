/* Song detail view (the page behind a music card click).
 *
 * One content form for now: the design's Music Details screen (left column
 * artwork + info + credits, middle lyrics, right related rail) and the demo
 * player bar. Data arrives from the Genius detail command — the request kicks
 * off the IO-pool fetch, this module polls `pollSongDetail`, and the view
 * paints once the reply is done. Lyrics, duration and audio are dummy data
 * (the backend marks them so); the player is a UI-only timer.
 *
 * No render.js import: this module paints itself, and render.js calls
 * paintSongView()/closeSongView() to keep browse and detail in sync.
 */
import {
    browseView, songView, playerBar, playerArt, playerTitle, playerArtist,
    playerToggle, playerRestart, playerElapsed, playerDuration, playerTrack, playerFill,
} from "./dom.js";
import { state, nextDetailRequest, isCurrentDetail, cancelDetailRequest } from "./state.js";
import { escapeHtml, messageOf } from "./util.js";

const DISC = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="3"></circle>
        </svg>`;

const PLAY_ICON = "&#9654;";
const PAUSE_ICON = "&#10073;&#10073;";

// ------------------------------------------------------------------ player

const player = { seconds: 0, duration: 0, timer: null };

function formatTime(total) {
    const seconds = Math.max(0, Math.round(Number(total) || 0));
    return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
}

function formatCount(value) {
    const n = Number(value) || 0;
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
    if (n >= 1_000) return Math.round(n / 1_000) + "K";
    return n > 0 ? String(n) : "–";
}

function syncPlayer() {
    const pct = player.duration > 0 ? Math.min(100, (player.seconds / player.duration) * 100) : 0;
    playerFill.style.width = pct + "%";
    playerElapsed.textContent = formatTime(player.seconds);
    playerDuration.textContent = formatTime(player.duration);
    playerTrack.setAttribute("aria-valuemax", String(player.duration));
    playerTrack.setAttribute("aria-valuenow", String(Math.round(player.seconds)));
}

function stopPlayer() {
    if (player.timer) {
        clearInterval(player.timer);
        player.timer = null;
    }
    playerToggle.innerHTML = PLAY_ICON;
    playerToggle.setAttribute("aria-label", "Play");
}

function togglePlayer() {
    if (player.timer) {
        stopPlayer();
        return;
    }
    if (player.duration <= 0) return;
    if (player.seconds >= player.duration) player.seconds = 0;
    playerToggle.innerHTML = PAUSE_ICON;
    playerToggle.setAttribute("aria-label", "Pause");
    player.timer = setInterval(() => {
        player.seconds = Math.min(player.duration, player.seconds + 1);
        syncPlayer();
        if (player.seconds >= player.duration) stopPlayer();
    }, 1000);
}

function resetPlayer(detail, artUri) {
    stopPlayer();
    player.seconds = 0;
    player.duration = Math.max(0, Number(detail && detail.durationSeconds) || 0);
    playerBar.classList.remove("hidden");
    playerTitle.textContent = (detail && detail.title) || "";
    playerArtist.textContent = (detail && detail.artist) || "";
    playerArt.innerHTML = artUri
        ? `<img src="${escapeHtml(artUri)}" alt="">`
        : "";
    playerArt.classList.toggle("has-art", Boolean(artUri));
    syncPlayer();
}

function hidePlayer() {
    stopPlayer();
    player.seconds = 0;
    player.duration = 0;
    playerBar.classList.add("hidden");
    playerArt.innerHTML = "";
    playerArt.classList.remove("has-art");
    playerTitle.textContent = "";
    playerArtist.textContent = "";
}

// ------------------------------------------------------------------ markup

function backRow() {
    return `<div class="song-back-row">
                <button type="button" class="song-back" data-action="back">
                    <span aria-hidden="true">&#8592;</span> Back
                </button>
                <span class="song-demo-note">Demo lyrics, duration and audio</span>
            </div>`;
}

function loadingMarkup(song) {
    return `${backRow()}
        <div class="song-layout">
            <div class="song-column">
                <div class="song-artwork loading">
                    <div class="poster-placeholder">${DISC}</div>
                </div>
                <div class="song-info">
                    <h1 class="song-title">${escapeHtml(song.title || "Untitled")}</h1>
                    <div class="song-artist">${escapeHtml(song.artist || "")}</div>
                    <p class="song-loading-note">Loading song details&hellip;</p>
                </div>
            </div>
            <section class="song-panel lyrics-panel">
                <div class="song-panel-head"><h2>Lyrics</h2></div>
                <div class="lyrics-skeleton">
                    ${Array.from({ length: 8 }, (_, i) => `<div class="skeleton-line" style="width:${88 - i * 6}%"></div>`).join("")}
                </div>
            </section>
            <aside class="song-panel related-panel">
                <div class="song-panel-head"><h2>Related Songs</h2></div>
                <div class="related-skeleton">
                    ${Array.from({ length: 5 }, () => `<div class="related-skeleton-row"><div class="skeleton-thumb"></div><div class="skeleton-line" style="width:60%"></div></div>`).join("")}
                </div>
            </aside>
        </div>`;
}

function errorMarkup(song) {
    return `${backRow()}
        <div class="song-error">
            <h1 class="song-title">${escapeHtml(song.title || "Untitled")}</h1>
            <p>Could not load this song's details: ${escapeHtml(song.error || "unknown error")}</p>
            <button type="button" class="song-back" data-action="retry">Try again</button>
        </div>`;
}

function creditNames(list) {
    return (list || []).length ? list.join(", ") : "–";
}

function lyricsMarkup(detail) {
    const lines = detail.lyrics || [];
    if (!lines.length) {
        return `<p class="lyrics-empty">No lyrics for this song yet.</p>`;
    }
    return lines.map((line) => `
        <button type="button" class="lyrics-line" data-time="${escapeHtml(line.time || "0:00")}">
            <span class="lyrics-time">${escapeHtml(line.time || "")}</span>
            <span class="lyrics-text">${escapeHtml(line.text || "")}</span>
        </button>`).join("");
}

function relatedMarkup(related) {
    if (!related || !related.length) {
        return `<p class="related-empty">No related songs in the Genius set.</p>`;
    }
    return related.map((song) => `
        <button type="button" class="related-row" data-action="related"
                data-id="${escapeHtml(song.id)}"
                data-title="${escapeHtml(song.title || "")}"
                data-artist="${escapeHtml(song.artist || "")}">
            <span class="related-art">
                <img class="related-img" alt="" loading="lazy" decoding="async">
                <span class="related-placeholder">${DISC}</span>
            </span>
            <span class="related-copy">
                <span class="related-title">${escapeHtml(song.title || "Untitled")}</span>
                <span class="related-sub">${escapeHtml(song.artist || "Unknown Artist")}</span>
                <span class="related-meta">${escapeHtml(song.relation || "Related")} &middot; ${formatTime(song.durationSeconds)}</span>
            </span>
        </button>`).join("");
}

function detailMarkup(s) {
    const d = s.detail;
    const featured = creditNames(d.featuredArtists);
    return `${backRow()}
        <div class="song-layout">
            <div class="song-column">
                <div class="song-artwork">
                    <img class="song-art-img hidden" alt="${escapeHtml(d.title)} cover">
                    <div class="poster-placeholder">${DISC}</div>
                    <div class="song-art-shade" aria-hidden="true"></div>
                    <span class="song-tag">SONG${d.year ? " &middot; " + d.year : ""}</span>
                    <button type="button" class="song-play" data-action="play" aria-label="Play">${PLAY_ICON}</button>
                </div>
                <div class="song-info">
                    <h1 class="song-title">${escapeHtml(d.title)}</h1>
                    <div class="song-artist-row">
                        <span class="song-artist">${escapeHtml(d.artist)}</span>
                        ${d.lyricsState === "complete" ? `<span class="song-chip">Lyrics on Genius</span>` : ""}
                    </div>
                    <div class="song-meta-grid">
                        <div class="song-meta-cell"><span class="k">Album</span><span class="v">${escapeHtml(d.album || "–")}</span></div>
                        <div class="song-meta-cell"><span class="k">Released</span><span class="v">${escapeHtml(d.releaseDate || "–")}</span></div>
                        <div class="song-meta-cell"><span class="k">Pageviews</span><span class="v">${formatCount(d.pageviews)}</span></div>
                        <div class="song-meta-cell"><span class="k">Duration</span><span class="v">${formatTime(d.durationSeconds)} <span class="dummy-mark">demo</span></span></div>
                    </div>
                    <div class="song-actions">
                        <button type="button" class="song-action" data-action="favorite">
                            <span aria-hidden="true">&#9825;</span> Favorite
                        </button>
                        <button type="button" class="song-action" data-action="playlist">
                            <span aria-hidden="true">+</span> Add to playlist
                        </button>
                    </div>
                </div>
                <div class="song-credits">
                    <div class="credit-row"><span class="k">WRITTEN BY</span><span class="v">${escapeHtml(creditNames(d.writers))}</span></div>
                    <div class="credit-row"><span class="k">PRODUCED BY</span><span class="v">${escapeHtml(creditNames(d.producers))}</span></div>
                    ${featured !== "–" ? `<div class="credit-row"><span class="k">FEATURING</span><span class="v">${escapeHtml(featured)}</span></div>` : ""}
                </div>
            </div>

            <section class="song-panel lyrics-panel" aria-labelledby="lyricsHeading">
                <div class="song-panel-head">
                    <h2 id="lyricsHeading">Lyrics</h2>
                    <span class="song-panel-sub">Demo copy &middot; placeholder timings</span>
                </div>
                <div class="lyrics-list">${lyricsMarkup(d)}</div>
            </section>

            <aside class="song-panel related-panel" aria-labelledby="relatedHeading">
                <div class="song-panel-head">
                    <h2 id="relatedHeading">Related Songs</h2>
                    <span class="song-panel-sub">${(d.related || []).length} from Genius</span>
                </div>
                <div class="related-list">${relatedMarkup(d.related)}</div>
            </aside>
        </div>`;
}

// ------------------------------------------------------------------ paint

export function paintSongView() {
    const song = state.song;
    if (!song) {
        songView.innerHTML = "";
        songView.classList.add("hidden");
        return;
    }

    songView.classList.remove("hidden");
    if (song.status === "loading") {
        songView.innerHTML = loadingMarkup(song);
        hidePlayer();
        return;
    }
    if (song.status === "error") {
        songView.innerHTML = errorMarkup(song);
        hidePlayer();
        return;
    }

    songView.innerHTML = detailMarkup(song);
    paintArtwork(song);
    paintRelatedArt(song.detail.related);
    resetPlayer(song.detail, song.artDataUri || song.detail.artUrl || "");
}

function paintArtwork(song) {
    const img = songView.querySelector(".song-art-img");
    if (!img) return;
    const uri = song.artDataUri || (song.detail && song.detail.artUrl) || "";
    if (!uri) return;
    img.addEventListener("error", () => {
        img.classList.add("hidden");
        const placeholder = songView.querySelector(".song-artwork .poster-placeholder");
        if (placeholder) placeholder.classList.remove("hidden");
    }, { once: true });
    img.src = uri;
    img.classList.remove("hidden");
    const placeholder = songView.querySelector(".song-artwork .poster-placeholder");
    if (placeholder) placeholder.classList.add("hidden");
}

// Related rows carry direct thumbnails (Genius CDN); a failed load falls back
// to the placeholder disc, so the rail never shows a broken image.
function paintRelatedArt(related) {
    if (!related) return;
    songView.querySelectorAll(".related-row").forEach((row) => {
        const song = related.find((item) => String(item.id) === row.dataset.id);
        const img = row.querySelector(".related-img");
        if (!song || !song.posterUrl || !img) return;
        img.addEventListener("error", () => img.classList.add("hidden"), { once: true });
        img.src = song.posterUrl;
        img.addEventListener("load", () => {
            row.querySelector(".related-placeholder").classList.add("hidden");
        }, { once: true });
    });
}

export function closeSongView() {
    hidePlayer();
    songView.classList.add("hidden");
    songView.innerHTML = "";
}

function closeSongDetail() {
    cancelDetailRequest();
    state.view = "browse";
    state.song = null;
    closeSongView();
    browseView.classList.remove("hidden");
}

// ------------------------------------------------------------------ loading

export async function openSongDetail(item) {
    if (!item || item.id == null || item.id === "") return;
    const seq = nextDetailRequest();
    state.view = "song";
    state.song = {
        status: "loading",
        id: String(item.id),
        title: item.title || "Untitled",
        artist: item.artist || "",
        detail: null,
        artDataUri: "",
        error: null,
    };
    browseView.classList.add("hidden");
    paintSongView();

    if (!window.krema) {
        state.song.status = "error";
        state.song.error = "API bridge not ready yet. Please wait a moment and try again.";
        paintSongView();
        return;
    }

    try {
        const generation = await window.krema.invoke("requestSongDetail", { id: String(item.id) });
        if (!isCurrentDetail(seq)) return;

        while (window.krema && isCurrentDetail(seq)) {
            const reply = await window.krema.invoke("pollSongDetail", { generation: generation });
            if (!reply || !isCurrentDetail(seq)) return;
            if (reply.status === "done" && reply.detail) {
                state.song = {
                    status: "ready",
                    id: reply.detail.id || state.song.id,
                    title: reply.detail.title,
                    artist: reply.detail.artist,
                    detail: reply.detail,
                    artDataUri: reply.artDataUri || "",
                    error: null,
                };
                paintSongView();
                return;
            }
            if (reply.status === "error") {
                state.song.status = "error";
                state.song.error = reply.error || "Failed to load song details.";
                paintSongView();
                return;
            }
            if (reply.status === "superseded") return;
            await new Promise((resolve) => setTimeout(resolve, 100));
        }
    } catch (e) {
        if (!isCurrentDetail(seq) || !state.song) return;
        state.song.status = "error";
        state.song.error = messageOf(e);
        paintSongView();
    }
}

// ------------------------------------------------------------------ events

playerToggle.addEventListener("click", togglePlayer);

playerRestart.addEventListener("click", () => {
    player.seconds = 0;
    syncPlayer();
});

playerTrack.addEventListener("click", (e) => {
    if (player.duration <= 0) return;
    const rect = playerTrack.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    player.seconds = Math.round(ratio * player.duration);
    syncPlayer();
});

songView.addEventListener("click", (e) => {
    const action = e.target.closest("[data-action]");
    if (!action) {
        const line = e.target.closest(".lyrics-line");
        if (line) {
            const [m, s] = String(line.dataset.time || "0:00").split(":").map(Number);
            player.seconds = (m || 0) * 60 + (s || 0);
            syncPlayer();
        }
        return;
    }
    switch (action.dataset.action) {
        case "back":
            closeSongDetail();
            break;
        case "retry":
            openSongDetail({ id: state.song && state.song.id, title: state.song && state.song.title, artist: state.song && state.song.artist });
            break;
        case "play":
            togglePlayer();
            break;
        case "favorite":
            action.classList.toggle("active");
            action.innerHTML = action.classList.contains("active")
                ? '<span aria-hidden="true">&#9829;</span> Favorite'
                : '<span aria-hidden="true">&#9825;</span> Favorite';
            break;
        case "playlist":
            action.classList.toggle("active");
            break;
        case "related":
            openSongDetail({
                id: action.dataset.id,
                title: action.dataset.title,
                artist: action.dataset.artist,
            });
            break;
        default:
            break;
    }
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view === "song") {
        closeSongDetail();
    }
});
