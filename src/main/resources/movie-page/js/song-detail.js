/* Song detail view (the page behind a music card click).
 *
 * One content form for now: the design's Music Details screen (left column
 * artwork + info + credits, middle lyrics, right related rail) and the demo
 * player bar. Data arrives from the Genius detail command — the request kicks
 * off the IO-pool fetch, this module polls `pollSongDetail`, and the view
 * paints once the reply is done. Lyrics, duration and audio are dummy data
 * (the backend marks them so); the player is a UI-only timer.
 *
 * What Genius fills beyond the basics: the full credit bill (behind an
 * expander), the editorial "About this song" text, listen/watch links, the
 * language + translation count, the artwork palette, the artist and album
 * identity, the recording location and the community stats.
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

// Only a real hex colour is written into the style attribute (the backend
// already validates, this is the page-side half of the same rule).
const HEX_COLOR = /^#[0-9a-fA-F]{3,8}$/;

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

function formatStat(value) {
    const n = Number(value) || 0;
    return n > 0 ? n.toLocaleString("en-US") : "";
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

function secureLink(href) {
    return /^https?:\/\//i.test(String(href || "")) ? escapeHtml(href) : "";
}

/* Style attribute carrying the artwork palette. Missing colours fall back to
 * the theme's gold in CSS, so a song without a palette keeps today's look. */
function paletteStyle(detail) {
    const vars = [
        ["--song-primary", detail.artPrimaryColor],
        ["--song-secondary", detail.artSecondaryColor],
        ["--song-text", detail.artTextColor],
    ].filter(([, value]) => HEX_COLOR.test(String(value || "")));
    if (!vars.length) return "";
    return ` style="${vars.map(([name, value]) => `${name}:${value}`).join(";")}"`;
}

function artistMarkup(detail) {
    const name = escapeHtml(detail.artist || "Unknown Artist");
    const avatar = detail.artistImageUrl
        ? `<img class="artist-avatar" src="${escapeHtml(detail.artistImageUrl)}" alt="" loading="lazy" decoding="async">`
        : "";
    const href = secureLink(detail.artistUrl);
    const label = href
        ? `<a class="song-artist" href="${href}" target="_blank" rel="noopener">${name}</a>`
        : `<span class="song-artist">${name}</span>`;
    const verified = detail.artistVerified
        ? `<span class="artist-verified" title="Verified artist on Genius"><span aria-hidden="true">&#10003;</span> Verified</span>`
        : "";
    return avatar + label + verified;
}

function albumCell(detail) {
    const name = escapeHtml(detail.album || "–");
    const href = secureLink(detail.albumUrl);
    const cover = detail.albumCoverUrl
        ? `<img class="meta-cover" src="${escapeHtml(detail.albumCoverUrl)}" alt="" loading="lazy" decoding="async">`
        : "";
    const label = href
        ? `<a class="meta-link" href="${href}" target="_blank" rel="noopener">${name}</a>`
        : name;
    return `<div class="song-meta-cell album-cell">
                <span class="k">Album</span>
                <span class="v">${cover}${label}</span>
            </div>`;
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

/* The rail only shows Genius' first handful; their own page has the rest. */
function relatedAllMarkup(detail) {
    const href = secureLink(detail.relationshipsIndexUrl);
    if (!href) return "";
    return `<a class="related-all" href="${href}" target="_blank" rel="noopener">
                View all related on Genius <span aria-hidden="true">&#8599;</span>
            </a>`;
}

/* ---- Tier 1: full credits, listen links, language, palette ---- */

function creditsMarkup(credits) {
    if (!credits || !credits.length) return "";
    return `<button type="button" class="credits-toggle" data-action="credits" aria-expanded="false">
                All credits <span class="credits-count">${credits.length}</span>
                <span class="chev" aria-hidden="true">&#8964;</span>
            </button>
            <div class="credit-all hidden">
                ${credits.map((credit) => `
                    <div class="credit-all-row">
                        <span class="k">${escapeHtml(credit.label)}</span>
                        <span class="v">${escapeHtml(creditNames(credit.names))}</span>
                    </div>`).join("")}
            </div>`;
}

function mediaHref(media) {
    const url = String(media.url || "");
    const start = Number(media.startSeconds) || 0;
    if (start <= 0 || !/youtube/i.test(media.provider || "")) return url;
    return url + (url.includes("?") ? "&" : "?") + "t=" + start + "s";
}

function mediaType(media) {
    return media.type === "video" ? "Video" : media.type === "audio" ? "Audio" : "Link";
}

function listenMarkup(media) {
    const links = (media || []).filter((item) => secureLink(item.url));
    if (!links.length) return "";
    return `<div class="song-listen" aria-label="Listen and watch">
        ${links.map((item) => `
            <a class="song-listen-link" href="${secureLink(mediaHref(item))}"
               target="_blank" rel="noopener">
                <span class="listen-provider">${escapeHtml(item.provider || "Link")}</span>
                <span class="listen-type">${mediaType(item)}</span>
            </a>`).join("")}
    </div>`;
}

function languageMarkup(detail) {
    if (!detail.language) return "";
    const translations = Number(detail.translationCount) || 0;
    const count = translations > 0
        ? `<span class="lang-count">&middot; ${translations} translations</span>`
        : "";
    return `<span class="song-lang" title="Genius' language for this lyric page">
                ${escapeHtml(detail.language)} ${count}
            </span>`;
}

/* ---- Tier 2: community stats, About this song ---- */

function statsMarkup(detail) {
    const stats = [
        [detail.contributors, "contributors"],
        [detail.annotationCount, "annotations"],
        [detail.pyongsCount, "pyongs"],
        [detail.transcribers, "transcribers"],
    ].map(([value, label]) => [formatStat(value), label])
        .filter(([value]) => value !== "");
    if (!stats.length) return "";
    return `<div class="song-stats" aria-label="Genius community">
        ${stats.map(([value, label]) => `<span class="song-stat"><strong>${value}</strong> ${label}</span>`).join("")}
    </div>`;
}

function aboutMarkup(detail) {
    const paragraphs = String(detail.about || "")
        .split(/\n{2,}/)
        .map((text) => text.trim())
        .filter(Boolean);
    if (!paragraphs.length) return "";
    // Collapse only when there is actually something hidden behind the fade.
    const needsToggle = paragraphs.length > 2 || paragraphs[0].length > 420;
    return `<section class="song-panel song-about" aria-labelledby="aboutHeading">
                <div class="song-panel-head">
                    <h2 id="aboutHeading">About this song</h2>
                    <span class="song-panel-sub">Genius editorial</span>
                </div>
                <div class="about-body${needsToggle ? "" : " expanded"}">
                    ${paragraphs.map((text) => `<p>${escapeHtml(text)}</p>`).join("")}
                </div>
                ${needsToggle ? `<button type="button" class="about-toggle" data-action="about">Read more</button>` : ""}
            </section>`;
}

function detailMarkup(s) {
    const d = s.detail;
    const featured = creditNames(d.featuredArtists);
    return `${backRow()}
        <div class="song-layout">
            <div class="song-column">
                <div class="song-artwork"${paletteStyle(d)}>
                    <img class="song-art-img hidden" alt="${escapeHtml(d.title)} cover">
                    <div class="poster-placeholder">${DISC}</div>
                    <div class="song-art-shade" aria-hidden="true"></div>
                    <span class="song-tag">SONG${d.year ? " &middot; " + d.year : ""}</span>
                    <button type="button" class="song-play" data-action="play" aria-label="Play">${PLAY_ICON}</button>
                </div>
                <div class="song-info">
                    <h1 class="song-title">${escapeHtml(d.title)}</h1>
                    <div class="song-artist-row">
                        ${artistMarkup(d)}
                        ${d.lyricsState === "complete" ? `<span class="song-chip">Lyrics on Genius</span>` : ""}
                    </div>
                    <div class="song-meta-grid">
                        ${albumCell(d)}
                        <div class="song-meta-cell"><span class="k">Released</span><span class="v">${escapeHtml(d.releaseDateDisplay || d.releaseDate || "–")}</span></div>
                        <div class="song-meta-cell"><span class="k">Pageviews</span><span class="v">${formatCount(d.pageviews)}</span></div>
                        <div class="song-meta-cell"><span class="k">Duration</span><span class="v">${formatTime(d.durationSeconds)} <span class="dummy-mark">demo</span></span></div>
                        ${d.recordingLocation ? `<div class="song-meta-cell wide"><span class="k">Recorded at</span><span class="v">${escapeHtml(d.recordingLocation)}</span></div>` : ""}
                    </div>
                    <div class="song-actions">
                        <button type="button" class="song-action" data-action="favorite">
                            <span aria-hidden="true">&#9825;</span> Favorite
                        </button>
                        <button type="button" class="song-action" data-action="playlist">
                            <span aria-hidden="true">+</span> Add to playlist
                        </button>
                    </div>
                    ${listenMarkup(d.media)}
                    ${statsMarkup(d)}
                </div>
                <div class="song-credits">
                    <div class="credit-row"><span class="k">WRITTEN BY</span><span class="v">${escapeHtml(creditNames(d.writers))}</span></div>
                    <div class="credit-row"><span class="k">PRODUCED BY</span><span class="v">${escapeHtml(creditNames(d.producers))}</span></div>
                    ${featured !== "–" ? `<div class="credit-row"><span class="k">FEATURING</span><span class="v">${escapeHtml(featured)}</span></div>` : ""}
                    ${creditsMarkup(d.credits)}
                </div>
            </div>

            <section class="song-panel lyrics-panel" aria-labelledby="lyricsHeading">
                <div class="song-panel-head">
                    <h2 id="lyricsHeading">Lyrics</h2>
                    <span class="song-panel-meta">
                        ${languageMarkup(d)}
                        <span class="song-panel-sub">Demo copy &middot; placeholder timings</span>
                    </span>
                </div>
                <div class="lyrics-list">${lyricsMarkup(d)}</div>
            </section>

            <aside class="song-panel related-panel" aria-labelledby="relatedHeading">
                <div class="song-panel-head">
                    <h2 id="relatedHeading">Related Songs</h2>
                    <span class="song-panel-sub">${(d.related || []).length} from Genius</span>
                </div>
                <div class="related-list">${relatedMarkup(d.related)}</div>
                ${relatedAllMarkup(d)}
            </aside>

            ${aboutMarkup(d)}
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
    paintExtras(song.detail);
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

// Artist avatar and album thumb are direct Genius CDN URLs: if either fails,
// drop the image and keep the text, never a broken icon.
function paintExtras(detail) {
    songView.querySelectorAll(".artist-avatar, .meta-cover").forEach((img) => {
        img.addEventListener("error", () => img.remove(), { once: true });
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
        case "credits": {
            const open = action.getAttribute("aria-expanded") === "true";
            action.setAttribute("aria-expanded", String(!open));
            const panel = songView.querySelector(".credit-all");
            if (panel) panel.classList.toggle("hidden", open);
            const chev = action.querySelector(".chev");
            if (chev) chev.style.transform = open ? "" : "rotate(180deg)";
            break;
        }
        case "about": {
            const body = songView.querySelector(".about-body");
            if (!body) break;
            const expanded = body.classList.toggle("expanded");
            action.textContent = expanded ? "Show less" : "Read more";
            break;
        }
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
