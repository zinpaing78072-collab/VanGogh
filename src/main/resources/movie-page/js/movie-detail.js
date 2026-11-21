/* Movie detail view (the page behind a movie card click).
 *
 * Assembled as the bento layout from prototypes/movie-detail-layouts/bento.html:
 * a 12-column dashboard of tiles (poster / identity / details on top, synopsis
 * beside the similar rail, cast beside the artwork). Every component is
 * visible at once; there are no tabs.
 *
 * Data arrives from TMDB through MovieController's detail pair — the request
 * kicks off the IO-pool fetch, this module polls `pollMovieDetail`, paints the
 * tiles, then polls `pollMovieDetailArt` while the artwork streams in as
 * `movieDetailArt` events (poster, headshots, stills, similar posters). TMDB
 * image hosts are unreachable on some networks, so the backend proxies each
 * one and sends a data URI instead of a URL the page could load itself.
 *
 * No render.js import: this module paints itself, and render.js calls
 * paintMovieView()/closeMovieView() to keep browse and detail in sync.
 */
import { browseView, movieView } from "./dom.js";
import { state, nextDetailRequest, isCurrentDetail, cancelDetailRequest } from "./state.js";
import { escapeHtml, messageOf } from "./util.js";

const FILM_ICON = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="2" y="4" width="20" height="16" rx="2"></rect>
            <path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5"></path>
        </svg>`;

// ------------------------------------------------------------------ helpers

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function initialsOf(name) {
    const words = String(name || "").split(/\s+/).filter(Boolean);
    return words.slice(0, 2).map((w) => w.charAt(0).toUpperCase()).join("") || "?";
}

function formatRuntime(minutes) {
    const total = Math.max(0, Math.round(Number(minutes) || 0));
    if (!total) return "";
    const hours = Math.floor(total / 60);
    const rest = total % 60;
    if (!hours) return rest + "m";
    return rest ? hours + "h " + rest + "m" : hours + "h";
}

function formatVotes(value) {
    const n = Number(value) || 0;
    return n > 0 ? n.toLocaleString("en-US") : "";
}

function secureLink(href) {
    return /^https?:\/\//i.test(String(href || "")) ? escapeHtml(href) : "";
}

// ------------------------------------------------------------------ markup

function backRow() {
    return `<div class="movie-back-row">
                <button type="button" class="movie-back" data-action="back">
                    <span aria-hidden="true">&#8592;</span> Back
                </button>
                <span class="movie-source-note">Data and artwork from TMDB</span>
            </div>`;
}

function loadingMarkup(movie) {
    return `${backRow()}
        <div class="movie-bento">
            <div class="movie-tile t-poster">
                <div class="movie-poster-frame">
                    <div class="poster-placeholder">${FILM_ICON}</div>
                </div>
            </div>
            <div class="movie-tile t-ident">
                <div class="movie-kicker"><span>Film</span>${movie.year ? `<span class="chip">${escapeHtml(String(movie.year))}</span>` : ""}</div>
                <h1 class="movie-title">${escapeHtml(movie.title || "Untitled")}</h1>
                <p class="movie-loading-note">Loading film details&hellip;</p>
            </div>
            <div class="movie-tile t-facts">
                <div class="movie-panel-head"><h2>Details</h2><span class="movie-panel-sub">TMDB</span></div>
                <div class="movie-skeleton">
                    ${Array.from({ length: 6 }, (_, i) => `<div class="skeleton-line" style="width:${78 - i * 8}%"></div>`).join("")}
                </div>
            </div>
            <section class="movie-tile t-story">
                <div class="movie-panel-head"><h2>Synopsis</h2><span class="movie-panel-sub">TMDB overview</span></div>
                <div class="movie-skeleton">
                    ${Array.from({ length: 4 }, (_, i) => `<div class="skeleton-line" style="width:${94 - i * 9}%"></div>`).join("")}
                </div>
            </section>
        </div>`;
}

function errorMarkup(movie) {
    return `${backRow()}
        <div class="movie-error">
            <h1 class="movie-title">${escapeHtml(movie.title || "Untitled")}</h1>
            <p>Could not load this film's details: ${escapeHtml(movie.error || "unknown error")}</p>
            <button type="button" class="movie-back" data-action="retry">Try again</button>
        </div>`;
}

function posterTile(detail) {
    return `<div class="movie-tile t-poster" data-slot="poster">
                <div class="movie-poster-frame">
                    <img class="movie-poster-img hidden" alt="${escapeHtml(detail.title)} poster">
                    <div class="poster-placeholder">${FILM_ICON}</div>
                </div>
            </div>`;
}

function identity(detail) {
    const runtime = formatRuntime(detail.runtimeMinutes);
    const chips = [detail.year, detail.certification, runtime]
        .filter(Boolean)
        .map((value) => `<span class="chip">${escapeHtml(String(value))}</span>`)
        .join("");
    const score = Number(detail.rating) > 0
        ? `<div class="movie-score-row">
                <span class="movie-score"><span class="star" aria-hidden="true">&#9733;</span>${Number(detail.rating).toFixed(1)}<span class="max">/10</span></span>
                ${formatVotes(detail.voteCount) ? `<span class="movie-votes">${escapeHtml(formatVotes(detail.voteCount))} votes &middot; TMDB</span>` : ""}
            </div>`
        : "";
    const genres = (detail.genres || []).length
        ? `<ul class="movie-genres">${detail.genres.map((name) => `<li>${escapeHtml(name)}</li>`).join("")}</ul>`
        : "";
    const trailer = secureLink(detail.trailerUrl);
    const tmdb = secureLink(detail.tmdbUrl);
    return `<div class="movie-kicker"><span>Film</span>${chips}</div>
            <h1 class="movie-title">${escapeHtml(detail.title)}</h1>
            ${detail.tagline ? `<p class="movie-tagline">${escapeHtml(detail.tagline)}</p>` : ""}
            ${score}
            ${genres}
            <div class="movie-actions">
                ${trailer ? `<a class="movie-action movie-action-primary" href="${trailer}" target="_blank" rel="noopener">
                    <span aria-hidden="true">&#9654;</span> Watch trailer
                </a>` : ""}
                <button type="button" class="movie-action" data-action="watchlist" aria-pressed="false">+ Watchlist</button>
                <button type="button" class="movie-action" data-action="favorite" aria-pressed="false"><span aria-hidden="true">&#9825;</span> Favorite</button>
                ${tmdb ? `<a class="movie-action" href="${tmdb}" target="_blank" rel="noopener">TMDB <span aria-hidden="true">&#8599;</span></a>` : ""}
            </div>`;
}

function factsTile(detail) {
    const facts = detail.facts || [];
    return `<div class="movie-tile t-facts">
                <div class="movie-panel-head"><h2>Details</h2><span class="movie-panel-sub">TMDB</span></div>
                <dl class="movie-facts">
                    ${facts.map((fact) => `<div>
                        <dt>${escapeHtml(fact.label)}</dt>
                        <dd${fact.muted ? ' class="muted"' : ""}>${escapeHtml(fact.value)}</dd>
                    </div>`).join("")}
                </dl>
            </div>`;
}

function storyTile(detail) {
    const keywords = (detail.keywords || []).length
        ? `<ul class="movie-keywords" aria-label="TMDB keywords">
                ${detail.keywords.map((word) => `<li>${escapeHtml(word)}</li>`).join("")}
            </ul>`
        : "";
    return `<section class="movie-tile t-story" aria-labelledby="movieStoryHeading">
                <div class="movie-panel-head"><h2 id="movieStoryHeading">Synopsis</h2><span class="movie-panel-sub">TMDB overview</span></div>
                <p class="movie-lede">${escapeHtml(detail.overview || "TMDB has no overview for this film yet.")}</p>
                ${keywords}
            </section>`;
}

function castTile(detail) {
    const cast = detail.cast || [];
    if (!cast.length) return "";
    return `<section class="movie-tile t-cast" aria-labelledby="movieCastHeading">
                <div class="movie-panel-head">
                    <h2 id="movieCastHeading">Cast &amp; crew</h2>
                    <span class="movie-panel-sub">${cast.length} from TMDB</span>
                </div>
                <div class="movie-cast-grid">
                    ${cast.map((credit, index) => `
                        <figure class="movie-cast-card" data-slot="cast:${index}">
                            <div class="movie-cast-crop">
                                <img class="movie-cast-img hidden" alt="${escapeHtml(credit.name)}">
                                <div class="poster-placeholder movie-cast-placeholder">${escapeHtml(initialsOf(credit.name))}</div>
                            </div>
                            <figcaption class="movie-cast-meta">
                                <span class="name">${escapeHtml(credit.name)}</span>
                                <span class="role${credit.crew ? " crew" : ""}">${escapeHtml(credit.role || (credit.crew ? "Crew" : "Cast"))}</span>
                            </figcaption>
                        </figure>`).join("")}
                </div>
            </section>`;
}

function galleryTile(detail) {
    const stills = detail.stills || [];
    if (!stills.length) return "";
    return `<section class="movie-tile t-gallery" aria-labelledby="movieArtHeading">
                <div class="movie-panel-head">
                    <h2 id="movieArtHeading">Artwork</h2>
                    <span class="movie-panel-sub">${stills.length} stills</span>
                </div>
                <div class="movie-gallery">
                    ${stills.map((still, index) => `
                        <figure class="movie-shot" data-slot="still:${index}">
                            <img class="movie-shot-img hidden" alt="${escapeHtml(still.caption || "Film still " + (index + 1))}">
                            <div class="poster-placeholder movie-shot-placeholder">${FILM_ICON}</div>
                            ${still.caption ? `<figcaption>${escapeHtml(still.caption)}</figcaption>` : ""}
                        </figure>`).join("")}
                </div>
            </section>`;
}

function similarTile(detail) {
    const similar = detail.similar || [];
    if (!similar.length) return "";
    return `<section class="movie-tile t-sim" aria-labelledby="movieSimHeading">
                <div class="movie-panel-head">
                    <h2 id="movieSimHeading">More like this</h2>
                    <span class="movie-panel-sub">${similar.length} films</span>
                </div>
                <div class="movie-sim-row">
                    ${similar.map((entry) => {
                        const movie = entry.movie || {};
                        const score = Number(movie.rating) > 0
                            ? `<span class="movie-sim-score">${Number(movie.rating).toFixed(1)}</span>`
                            : "";
                        return `<button type="button" class="movie-sim-card"
                                    data-action="similar"
                                    data-id="${escapeHtml(String(movie.id))}"
                                    data-title="${escapeHtml(movie.title || "")}"
                                    data-year="${escapeHtml(String(movie.year || ""))}">
                            <span class="movie-sim-poster" data-slot="similar:${escapeHtml(String(movie.id))}">
                                <img class="movie-sim-img hidden" alt="${escapeHtml(movie.title || "")} poster">
                                <span class="poster-placeholder movie-sim-placeholder">${FILM_ICON}</span>
                                ${score}
                            </span>
                            <span class="movie-sim-title">${escapeHtml(movie.title || "Untitled")}</span>
                            <span class="movie-sim-year">${escapeHtml(String(movie.year || "\u2013"))}</span>
                        </button>`;
                    }).join("")}
                </div>
            </section>`;
}

function detailMarkup(detail) {
    return `${backRow()}
        <div class="movie-bento">
            ${posterTile(detail)}
            <div class="movie-tile t-ident">${identity(detail)}</div>
            ${factsTile(detail)}
            ${storyTile(detail)}
            ${castTile(detail)}
            ${galleryTile(detail)}
            ${similarTile(detail)}
        </div>`;
}

// ------------------------------------------------------------------- paint

export function paintMovieView() {
    const movie = state.movie;
    if (!movie) {
        movieView.innerHTML = "";
        movieView.classList.add("hidden");
        return;
    }

    movieView.classList.remove("hidden");
    if (movie.status === "loading") {
        movieView.innerHTML = loadingMarkup(movie);
        return;
    }
    if (movie.status === "error") {
        movieView.innerHTML = errorMarkup(movie);
        return;
    }
    movieView.innerHTML = detailMarkup(movie.detail);
}

/* Artwork arrives by slot: poster, cast:<i>, still:<i>, similar:<id>. The
 * slot is matched against data-slot (never a CSS selector: similar ids are
 * numbers today but the page should not depend on that). */
function paintArt(slot, dataUri) {
    if (!dataUri || !state.movie || state.movie.status !== "ready") return;
    const node = Array.from(movieView.querySelectorAll("[data-slot]"))
        .find((element) => element.dataset.slot === slot);
    if (!node) return;
    const img = node.querySelector("img");
    const placeholder = node.querySelector(".poster-placeholder");
    if (!img) return;
    img.addEventListener("error", () => {
        img.classList.add("hidden");
        if (placeholder) placeholder.classList.remove("hidden");
    }, { once: true });
    img.src = dataUri;
    img.classList.remove("hidden");
    if (placeholder) placeholder.classList.add("hidden");
}

export function closeMovieView() {
    movieView.classList.add("hidden");
    movieView.innerHTML = "";
}

function closeMovieDetail() {
    cancelDetailRequest();
    state.view = "browse";
    state.movie = null;
    closeMovieView();
    browseView.classList.remove("hidden");
}

// ----------------------------------------------------------------- loading

export async function openMovieDetail(item) {
    if (!item || item.id == null || item.id === "") return;
    const seq = nextDetailRequest();
    state.view = "movie";
    state.movie = {
        status: "loading",
        id: String(item.id),
        title: item.title || "Untitled",
        year: item.year || "",
        detail: null,
        error: null,
    };
    browseView.classList.add("hidden");
    paintMovieView();

    if (!window.krema) {
        state.movie.status = "error";
        state.movie.error = "API bridge not ready yet. Please wait a moment and try again.";
        paintMovieView();
        return;
    }

    try {
        const generation = await window.krema.invoke("requestMovieDetail", { id: Number(item.id) });
        if (!isCurrentDetail(seq)) return;

        while (window.krema && isCurrentDetail(seq)) {
            const reply = await window.krema.invoke("pollMovieDetail", { generation: generation });
            if (!reply || !isCurrentDetail(seq)) return;
            if (reply.status === "done" && reply.detail) {
                state.movie = {
                    status: "ready",
                    id: String(reply.detail.id || state.movie.id),
                    title: reply.detail.title,
                    year: reply.detail.year,
                    detail: reply.detail,
                    error: null,
                };
                paintMovieView();
                pollArtwork(seq);
                return;
            }
            if (reply.status === "error") {
                state.movie.status = "error";
                state.movie.error = reply.error || "Failed to load film details.";
                paintMovieView();
                return;
            }
            if (reply.status === "superseded") return;
            await delay(100);
        }
    } catch (e) {
        if (!isCurrentDetail(seq) || !state.movie) return;
        state.movie.status = "error";
        state.movie.error = messageOf(e);
        paintMovieView();
    }
}

// The artwork drain doubles as the completion signal (−1 when done).
async function pollArtwork(seq) {
    while (window.krema && state.view === "movie" && isCurrentDetail(seq)) {
        let pending;
        try {
            pending = await window.krema.invoke("pollMovieDetailArt", {});
        } catch (e) {
            return;
        }
        if (pending == null || pending < 0) return;
        await delay(120);
    }
}

// ------------------------------------------------------------------ events

if (window.krema && typeof window.krema.on === "function") {
    window.krema.on("movieDetailArt", (data) => {
        if (data && data.payload) {
            paintArt(data.payload.slot, data.payload.dataUri);
        }
    });
}

movieView.addEventListener("click", (e) => {
    const action = e.target.closest("[data-action]");
    if (!action) return;
    switch (action.dataset.action) {
        case "back":
            closeMovieDetail();
            break;
        case "retry":
            openMovieDetail({ id: state.movie && state.movie.id, title: state.movie && state.movie.title, year: state.movie && state.movie.year });
            break;
        case "watchlist":
        case "favorite": {
            const on = action.getAttribute("aria-pressed") !== "true";
            action.setAttribute("aria-pressed", String(on));
            action.classList.toggle("active", on);
            if (action.dataset.action === "favorite") {
                action.innerHTML = on
                    ? '<span aria-hidden="true">&#9829;</span> Favorite'
                    : '<span aria-hidden="true">&#9825;</span> Favorite';
            } else {
                action.innerHTML = on ? "&#10003; In watchlist" : "+ Watchlist";
            }
            break;
        }
        case "similar":
            openMovieDetail({
                id: action.dataset.id,
                title: action.dataset.title,
                year: action.dataset.year,
            });
            break;
        default:
            break;
    }
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view === "movie") {
        closeMovieDetail();
    }
});
