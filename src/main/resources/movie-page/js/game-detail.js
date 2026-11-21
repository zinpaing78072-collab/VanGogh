/* Game detail view (the page behind a game card click).
 *
 * Assembled as the immersive layout from prototypes/game-detail/v2-immersive.html:
 * a fanart hero holding the boxart, title, lede and actions; a four-cell stat
 * band; then a sticky record rail beside an editorial article (opening quote,
 * the full overview, the screenshot gallery, platforms and play, provenance
 * note); and finally the related-games rail.
 *
 * Data arrives from TheGamesDB through GameController's detail pair — the
 * request kicks off the IO-pool fetch, this module polls `pollGameDetail`,
 * paints the page, then polls `pollGameDetailArt` while the artwork streams in
 * as `gameDetailArt` events. TheGamesDB image hosts are unreachable on some
 * networks, so the backend proxies each one and sends a data URI instead of a
 * URL the page could load itself.
 *
 * Only record data is painted: no developer, price, playtime or score is
 * invented, and the age rating is the API's string, never a star score.
 *
 * No render.js import: this module paints itself, and render.js calls
 * paintGameView()/closeGameView() to keep browse and detail in sync.
 */
import { browseView, gameView } from "./dom.js";
import { state, nextDetailRequest, isCurrentDetail, cancelDetailRequest } from "./state.js";
import { escapeHtml, messageOf } from "./util.js";

const GAME_ICON = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <line x1="6" y1="11" x2="10" y2="11"></line>
            <line x1="8" y1="9" x2="8" y2="13"></line>
            <line x1="15" y1="12" x2="15.01" y2="12"></line>
            <line x1="18" y1="10" x2="18.01" y2="10"></line>
            <path d="M17.32 5H6.68a4 4 0 0 0-3.98 3.59l-.9 8A4 4 0 0 0 5.78 21h1.2a2 2 0 0 0 1.79-1.1l.87-1.9h4.72l.87 1.9A2 2 0 0 0 17.02 21h1.2a4 4 0 0 0 3.98-4.41l-.9-8A4 4 0 0 0 17.32 5z"></path>
        </svg>`;

// ------------------------------------------------------------------ helpers

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function secureLink(href) {
    return /^https?:\/\//i.test(String(href || "")) ? escapeHtml(href) : "";
}

/** "2018-08-01" → { year: "2018", day: "August 1", full: "Aug 1, 2018" }. */
function released(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ""));
    if (!match) {
        return { year: "", day: "", full: value ? String(value) : "" };
    }
    const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
    return {
        year: match[1],
        day: date.toLocaleDateString("en-US", { month: "long", day: "numeric", timeZone: "UTC" }),
        full: date.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" }),
    };
}

/** The first sentence of the overview, used as the opening pull quote. */
function firstSentence(text) {
    const value = String(text || "").trim();
    const match = /^[\s\S]*?[.!?](?=\s|$)/.exec(value);
    return match ? match[0].trim() : value;
}

/**
 * TheGamesDB overviews are often one long block. Keep authored paragraphs when
 * the payload has them; otherwise group sentences into readable paragraphs —
 * no headings or rewording, the copy stays the record's own.
 */
function paragraphsOf(text) {
    const value = String(text || "").trim();
    if (!value) return [];
    const authored = value.split(/\r?\n\s*\r?\n/).map((part) => part.trim()).filter(Boolean);
    if (authored.length > 1) return authored;

    const sentences = value.match(/[^.!?]+[.!?]+["')\]]*\s*/g) || [value];
    const chunks = [];
    let current = "";
    sentences.forEach((sentence) => {
        if (current && (current + sentence).length > 430) {
            chunks.push(current.trim());
            current = "";
        }
        current += sentence;
    });
    if (current.trim()) chunks.push(current.trim());
    return chunks;
}

/** "M - Mature 17+" → { big: "M", small: "Mature 17+" }. */
function ratingParts(rating) {
    const value = String(rating || "").trim();
    if (!value) {
        return { big: "–", small: "not on the record" };
    }
    const match = /^([A-Z0-9+]{1,3})\s*-\s*(.+)$/.exec(value);
    if (match) {
        return { big: match[1], small: match[2] };
    }
    return { big: value, small: "as listed" };
}

function playersText(players) {
    const n = Number(players) || 0;
    if (n <= 0) return "";
    return n === 1 ? "1 player" : n + " players";
}

function metaLine(detail) {
    const parts = [];
    const release = released(detail.releaseDate);
    if (release.year) parts.push("Released " + release.full);
    if (detail.players > 0) parts.push(playersText(detail.players));
    if ((detail.platforms || []).length) parts.push(detail.platforms.join(", "));
    return parts;
}

// ------------------------------------------------------------------ markup

function backRow() {
    return `<div class="game-back-row">
                <button type="button" class="game-back" data-action="back">
                    <span aria-hidden="true">&#8592;</span> Back
                </button>
                <span class="game-source-note">Data and artwork from TheGamesDB</span>
            </div>`;
}

function chip(value, quiet) {
    return value
        ? `<span class="game-chip${quiet ? " quiet" : ""}">${escapeHtml(String(value))}</span>`
        : "";
}

function heroSection(detail) {
    const release = released(detail.releaseDate);
    const rating = ratingParts(detail.rating);
    const genres = (detail.genres || []).map((name) => chip(name)).join("");
    const meta = metaLine(detail)
        .map((part) => `<span>${escapeHtml(part)}</span>`)
        .join("");
    const studio = (detail.developers && detail.developers.length
        ? detail.developers
        : detail.publishers || []).join(" \u00b7 ");
    const record = secureLink(detail.recordUrl);
    const youtube = secureLink(detail.youtubeUrl);

    return `<section class="game-hero" aria-labelledby="gameTitle">
            <img class="game-hero-bg hidden" data-slot="hero" alt="">
            <div class="game-hero-cover" data-slot="boxart">
                <img class="game-cover-img hidden" alt="Boxart of ${escapeHtml(detail.title)}">
                <span class="poster-placeholder game-cover-placeholder">${GAME_ICON}</span>
            </div>
            <div class="game-hero-copy">
                <div class="game-hero-title">
                    <p class="game-kicker"><span>Game</span>${release.year ? chip(release.year) : ""}</p>
                    ${studio ? `<p class="game-eyebrow">${escapeHtml(studio)}</p>` : ""}
                    <h1 class="game-title" id="gameTitle">${escapeHtml(detail.title)}</h1>
                </div>
                <p class="game-lede">${escapeHtml(firstSentence(detail.overview) || "The record carries no overview for this game.")}</p>
                ${meta ? `<div class="game-meta-row">${meta}</div>` : ""}
                <div class="game-chips">
                    ${genres}
                    ${rating.big !== "–" ? chip(detail.rating, true) : ""}
                </div>
                <div class="game-actions">
                    <button type="button" class="game-action game-action-primary" data-action="watchlist" aria-pressed="false">
                        <span aria-hidden="true">&#43;</span> Add to My Lists
                    </button>
                    <button type="button" class="game-action" data-action="favorite" aria-pressed="false">
                        <span aria-hidden="true">&#9825;</span> Favorite
                    </button>
                    ${youtube ? `<a class="game-action" href="${youtube}" target="_blank" rel="noopener">Watch trailer <span aria-hidden="true">&#8599;</span></a>` : ""}
                    ${record ? `<a class="game-action" href="${record}" target="_blank" rel="noopener">TheGamesDB <span aria-hidden="true">&#8599;</span></a>` : ""}
                </div>
            </div>
        </section>`;
}

function stat(value, small) {
    return `<div class="game-stat">
                <dt>${escapeHtml(value.label)}</dt>
                <dd>${escapeHtml(value.big)}${small ? `<small>${escapeHtml(small)}</small>` : ""}</dd>
            </div>`;
}

function statBand(detail) {
    const release = released(detail.releaseDate);
    const rating = ratingParts(detail.rating);
    const platforms = (detail.platforms || []).join(", ");
    return `<dl class="game-stat-band" aria-label="Key facts">
                ${stat({ label: "Released", big: release.year || "–" }, release.day || "not on the record")}
                ${stat({ label: "Age rating", big: rating.big }, rating.small)}
                ${stat({ label: "Players", big: detail.players > 0 ? String(detail.players) : "–" }, detail.players > 0 ? "from the record" : "not on the record")}
                ${stat({ label: "Platform", big: platforms || "–" }, platforms ? "as listed on the record" : "not on the record")}
            </dl>`;
}

function rail(detail) {
    const facts = detail.facts || [];
    const record = secureLink(detail.recordUrl);
    const stills = (detail.stillUrls || []).length;

    return `<aside class="game-rail" aria-label="The record">
                <div class="game-panel">
                    <div class="game-panel-head">
                        <h2>The record</h2>
                        <span class="game-panel-sub">TheGamesDB</span>
                    </div>
                    <dl class="game-facts">
                        ${facts.length
                            ? facts.map((fact) => `<div>
                                <dt>${escapeHtml(fact.label)}</dt>
                                <dd${/^(Game ID)$/.test(fact.label) ? ' class="mono"' : ""}>${escapeHtml(fact.value)}</dd>
                            </div>`).join("")
                            : `<div><dt>Record</dt><dd class="muted">TheGamesDB has no structured fields for this game.</dd></div>`}
                    </dl>
                </div>
                <div class="game-rail-actions">
                    ${record ? `<a class="game-rail-link" href="${record}" target="_blank" rel="noopener"><span aria-hidden="true">&#8599;</span>TheGamesDB record</a>` : ""}
                    ${stills ? `<a class="game-rail-link" href="#gameStills" data-action="jump-stills"><span aria-hidden="true">&#9633;</span>Jump to the stills</a>` : ""}
                </div>
                <p class="game-rail-note">
                    Every field above is the TheGamesDB record. The page adds no price,
                    playtime, store link or review score; the age rating is the record's
                    own string.
                </p>
            </aside>`;
}

function overviewSection(detail) {
    const paragraphs = paragraphsOf(detail.overview);
    const quote = firstSentence(detail.overview);
    return `<section class="game-section" aria-labelledby="gameOverviewHeading">
                ${quote ? `<blockquote class="game-opening"><p>&ldquo;${escapeHtml(quote)}&rdquo;</p></blockquote>` : ""}
                <header class="game-section-head">
                    <div>
                        <p class="game-kicker">The overview</p>
                        <h2 class="game-section-title" id="gameOverviewHeading">From the record</h2>
                    </div>
                    <span class="game-count">${paragraphs.length ? paragraphs.length + (paragraphs.length === 1 ? " paragraph" : " paragraphs") : "no overview"}</span>
                </header>
                <div class="game-prose">
                    ${paragraphs.length
                        ? paragraphs.map((part) => `<p>${escapeHtml(part)}</p>`).join("")
                        : `<p class="muted">This record has no overview text yet.</p>`}
                </div>
            </section>`;
}

function stillsSection(detail) {
    const stills = detail.stillUrls || [];
    if (!stills.length) return "";
    return `<section class="game-section" id="gameStills" aria-labelledby="gameStillsHeading">
                <header class="game-section-head">
                    <div>
                        <p class="game-kicker">Field footage</p>
                        <h2 class="game-section-title" id="gameStillsHeading">Screenshots</h2>
                    </div>
                    <span class="game-count">${stills.length} on the record</span>
                </header>
                <div class="game-stills${stills.length === 1 ? " single" : ""}">
                    ${stills.map((url, index) => `
                        <figure class="game-still" data-slot="still:${index}">
                            <button type="button" class="game-still-open" data-action="still" data-index="${index}"
                                    aria-label="Enlarge screenshot ${index + 1} of ${stills.length}">
                                <img class="game-still-img hidden" alt="${escapeHtml(detail.title)} screenshot ${index + 1}">
                                <span class="poster-placeholder game-still-placeholder">${GAME_ICON}</span>
                            </button>
                            <figcaption>Screenshot ${index + 1} &middot; TheGamesDB</figcaption>
                        </figure>`).join("")}
                </div>
            </section>`;
}

function playSection(detail) {
    const platforms = detail.platforms || [];
    const spec = [
        ["Players", detail.players > 0 ? playersText(detail.players) : ""],
        ["Release", detail.releaseDate || ""],
        ["Genres", (detail.genres || []).join(" · ")],
        ["Age rating", detail.rating || ""],
    ].filter((pair) => pair[1]);
    if (!platforms.length && !spec.length) return "";

    return `<section class="game-section" aria-labelledby="gamePlayHeading">
                <header class="game-section-head">
                    <div>
                        <p class="game-kicker">Platforms &amp; play</p>
                        <h2 class="game-section-title" id="gamePlayHeading">Where the record says it runs</h2>
                    </div>
                </header>
                ${platforms.length ? `<div class="game-platform-grid">
                    ${platforms.map((name) => `<div class="game-platform-tile">
                        <span class="game-platform-mark" aria-hidden="true">&#9635;</span>
                        <span><b>${escapeHtml(name)}</b><span>listed on the record</span></span>
                    </div>`).join("")}
                </div>` : ""}
                ${spec.length ? `<dl class="game-spec-grid">
                    ${spec.map(([label, value]) => `<div class="game-spec-row">
                        <dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd>
                    </div>`).join("")}
                </dl>` : ""}
            </section>`;
}

function relatedSection(detail) {
    const related = detail.related || [];
    if (!related.length) return "";
    const kicker = detail.relatedSource === "series" ? "Same series" : "From the catalogue";
    const note = detail.relatedSource === "series"
        ? "Other records whose title shares this series name."
        : "The record has no series siblings, so these come from the catalogue.";

    return `<section class="game-related" aria-labelledby="gameRelatedHeading">
                <header class="game-section-head">
                    <div>
                        <p class="game-kicker">${escapeHtml(kicker)}</p>
                        <h2 class="game-section-title" id="gameRelatedHeading">More games</h2>
                    </div>
                    <span class="game-count">${related.length}</span>
                </header>
                <div class="game-related-row">
                    ${related.map((game) => {
                        const meta = [game.year ? String(game.year) : "", game.rating, game.genre]
                            .filter(Boolean).join(" · ");
                        return `<button type="button" class="game-rel-card" data-action="related"
                                    data-id="${escapeHtml(String(game.id))}"
                                    data-title="${escapeHtml(game.title || "")}">
                            <span class="game-rel-poster" data-slot="related:${escapeHtml(String(game.id))}">
                                <img class="game-rel-img hidden" alt="${escapeHtml(game.title || "")} boxart">
                                <span class="poster-placeholder game-rel-placeholder">${GAME_ICON}</span>
                            </span>
                            <span class="game-rel-title">${escapeHtml(game.title || "Untitled")}</span>
                            <span class="game-rel-meta">${escapeHtml(meta || "\u2013")}</span>
                        </button>`;
                    }).join("")}
                </div>
            </section>`;
}

function sourceNote(detail) {
    return `<div class="game-source-card">
                <span class="game-source-mark" aria-hidden="true">i</span>
                <span><b>Record data.</b> The copy above is TheGamesDB's overview for game
                ${escapeHtml(String(detail.id))}, shown unedited apart from paragraph breaks.
                Artwork comes from the same API through the image proxy, so a missing image
                leaves its placeholder instead of a broken icon.</span>
            </div>`;
}

function detailMarkup(detail) {
    return `${backRow()}
        ${heroSection(detail)}
        ${statBand(detail)}
        <div class="game-body">
            ${rail(detail)}
            <article class="game-article">
                ${overviewSection(detail)}
                ${stillsSection(detail)}
                ${playSection(detail)}
                ${sourceNote(detail)}
            </article>
        </div>
        ${relatedSection(detail)}
        <div class="game-lightbox hidden" role="dialog" aria-modal="true" aria-label="Screenshot">
            <button type="button" class="game-lightbox-close" data-action="close-lightbox" aria-label="Close screenshot">&times;</button>
            <figure>
                <img class="game-lightbox-img" alt="">
                <figcaption class="game-lightbox-caption"></figcaption>
            </figure>
        </div>`;
}

function loadingMarkup(game) {
    return `${backRow()}
        <section class="game-hero loading" aria-busy="true">
            <div class="game-hero-cover"><span class="poster-placeholder game-cover-placeholder">${GAME_ICON}</span></div>
            <div class="game-hero-copy">
                <div class="game-hero-title">
                    <p class="game-kicker"><span>Game</span>${game.year ? chip(game.year) : ""}</p>
                    <h1 class="game-title">${escapeHtml(game.title || "Loading")}</h1>
                </div>
                <p class="game-lede">Loading the record&hellip;</p>
                <div class="game-loading-lines">
                    <div class="skeleton-line" style="width:72%"></div>
                    <div class="skeleton-line" style="width:48%"></div>
                </div>
            </div>
        </section>
        <div class="game-body">
            <aside class="game-rail">
                <div class="game-panel">
                    <div class="game-panel-head"><h2>The record</h2><span class="game-panel-sub">TheGamesDB</span></div>
                    <div class="game-skeleton">
                        ${Array.from({ length: 6 }, (_, i) => `<div class="skeleton-line" style="width:${76 - i * 7}%"></div>`).join("")}
                    </div>
                </div>
            </aside>
            <article class="game-article">
                <section class="game-section">
                    <div class="game-skeleton">
                        ${Array.from({ length: 5 }, (_, i) => `<div class="skeleton-line" style="width:${94 - i * 9}%"></div>`).join("")}
                    </div>
                </section>
            </article>
        </div>`;
}

function errorMarkup(game) {
    return `${backRow()}
        <div class="game-error">
            <h1 class="game-title">${escapeHtml(game.title || "Untitled")}</h1>
            <p>Could not load this game's details: ${escapeHtml(game.error || "unknown error")}</p>
            <button type="button" class="game-back" data-action="retry">Try again</button>
        </div>`;
}

// ------------------------------------------------------------------- paint

export function paintGameView() {
    // A reply can land while Settings covers the detail; painting here would
    // unhide it over the Settings page. Artwork is still recorded in state.
    if (state.view === "settings") return;
    const game = state.game;
    if (!game) {
        gameView.innerHTML = "";
        gameView.classList.add("hidden");
        return;
    }

    gameView.classList.remove("hidden");
    if (game.status === "loading") {
        gameView.innerHTML = loadingMarkup(game);
        return;
    }
    if (game.status === "error") {
        gameView.innerHTML = errorMarkup(game);
        return;
    }
    gameView.innerHTML = detailMarkup(game.detail);
}

/* Artwork arrives by slot: boxart, hero, still:<i>, related:<id>. The slot is
 * matched against data-slot, never a CSS selector. The hero slot IS the image,
 * every other slot is a frame the image sits in. */
function paintArt(slot, dataUri) {
    if (!dataUri || !state.game || state.game.status !== "ready") return;
    gameView.querySelectorAll("[data-slot]").forEach((node) => {
        if (node.dataset.slot !== slot) return;
        const img = node.tagName === "IMG" ? node : node.querySelector("img");
        if (!img) return;
        const placeholder = node.querySelector(".poster-placeholder");
        img.addEventListener("error", () => {
            img.classList.add("hidden");
            if (placeholder) placeholder.classList.remove("hidden");
        }, { once: true });
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) placeholder.classList.add("hidden");
    });
}

export function closeGameView() {
    gameView.classList.add("hidden");
    gameView.innerHTML = "";
}

function closeGameDetail() {
    cancelDetailRequest();
    state.view = "browse";
    state.game = null;
    closeGameView();
    browseView.classList.remove("hidden");
}

// ----------------------------------------------------------------- loading

export async function openGameDetail(item) {
    const id = Number(item && item.id);
    if (!Number.isFinite(id) || id <= 0) return;

    const seq = nextDetailRequest();
    state.view = "game";
    state.game = {
        status: "loading",
        id: id,
        title: (item && (item.game_title || item.title)) || "Untitled",
        year: released(item && item.release_date).year,
        detail: null,
        error: null,
    };
    browseView.classList.add("hidden");
    paintGameView();

    if (!window.krema) {
        state.game.status = "error";
        state.game.error = "API bridge not ready yet. Please wait a moment and try again.";
        paintGameView();
        return;
    }

    try {
        const generation = await window.krema.invoke("requestGameDetail", { id: id });
        if (!isCurrentDetail(seq)) return;

        while (window.krema && isCurrentDetail(seq)) {
            const reply = await window.krema.invoke("pollGameDetail", { generation: generation });
            if (!reply || !isCurrentDetail(seq)) return;
            if (reply.status === "done" && reply.detail) {
                state.game = {
                    status: "ready",
                    id: reply.detail.id,
                    title: reply.detail.title,
                    year: reply.detail.year,
                    detail: reply.detail,
                    error: null,
                };
                paintGameView();
                pollArtwork(seq);
                return;
            }
            if (reply.status === "error") {
                state.game.status = "error";
                state.game.error = reply.error || "Failed to load game details.";
                paintGameView();
                return;
            }
            if (reply.status === "superseded") return;
            await delay(100);
        }
    } catch (e) {
        if (!isCurrentDetail(seq) || !state.game) return;
        state.game.status = "error";
        state.game.error = messageOf(e);
        paintGameView();
    }
}

// The artwork drain doubles as the completion signal (−1 when done).
async function pollArtwork(seq) {
    while (window.krema && isCurrentDetail(seq)) {
        let pending;
        try {
            pending = await window.krema.invoke("pollGameDetailArt", {});
        } catch (e) {
            return;
        }
        if (pending == null || pending < 0) return;
        await delay(120);
    }
}

/* Resume the artwork drain for a detail restored from a session. The backend's
 * queue is still keyed to its own generation; only the page-side stop token is
 * renewed, so the remaining slots stream into the repainted view. */
export function resumeGameArt(seq) {
    void pollArtwork(seq);
}

// ------------------------------------------------------------------ events

if (window.krema && typeof window.krema.on === "function") {
    window.krema.on("gameDetailArt", (data) => {
        if (data && data.payload) {
            paintArt(data.payload.slot, data.payload.dataUri);
        }
    });
}

function closeLightbox() {
    const lightbox = gameView.querySelector(".game-lightbox");
    if (lightbox) lightbox.classList.add("hidden");
}

function openLightbox(still) {
    const lightbox = gameView.querySelector(".game-lightbox");
    const image = still.querySelector("img");
    if (!lightbox || !image || !image.src) return;
    const img = lightbox.querySelector(".game-lightbox-img");
    const caption = lightbox.querySelector(".game-lightbox-caption");
    img.src = image.src;
    img.alt = image.alt;
    if (caption) caption.textContent = still.querySelector("figcaption")?.textContent || "";
    lightbox.classList.remove("hidden");
}

gameView.addEventListener("click", (e) => {
    const action = e.target.closest("[data-action]");
    if (!action) {
        // Clicking the dark backdrop of the lightbox itself closes it.
        if (e.target.classList && e.target.classList.contains("game-lightbox")) {
            closeLightbox();
        }
        return;
    }
    switch (action.dataset.action) {
        case "back":
            closeGameDetail();
            break;
        case "retry":
            openGameDetail({ id: state.game && state.game.id, title: state.game && state.game.title });
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
                action.classList.toggle("game-action-primary", false);
            } else {
                action.innerHTML = on
                    ? '<span aria-hidden="true">&#10003;</span> In My Lists'
                    : '<span aria-hidden="true">&#43;</span> Add to My Lists';
            }
            break;
        }
        case "jump-stills": {
            e.preventDefault();
            const stills = gameView.querySelector("#gameStills");
            if (stills) stills.scrollIntoView({ behavior: "smooth", block: "start" });
            break;
        }
        case "still": {
            const still = action.closest(".game-still");
            if (still) openLightbox(still);
            break;
        }
        case "close-lightbox":
            closeLightbox();
            break;
        case "related":
            openGameDetail({
                id: Number(action.dataset.id),
                title: action.dataset.title,
            });
            break;
        default:
            break;
    }
});

document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || state.view !== "game") return;
    const lightbox = gameView.querySelector(".game-lightbox:not(.hidden)");
    if (lightbox) {
        lightbox.classList.add("hidden");
        return;
    }
    closeGameDetail();
});
