/* Book detail view (the page behind a book card click).
 *
 * Assembled as the bento layout from prototypes/book-detail-layouts/bento.html:
 * a 12-column dashboard of tiles (cover / identity / record on top, story
 * beside the similar rail, editions beside the author card). Every component
 * is visible at once; there are no tabs.
 *
 * Data arrives from Open Library through BookController's detail pair — the
 * request kicks off the IO-pool fetch, this module polls `pollBookDetail`,
 * paints the tiles, then polls `pollBookDetailArt` while the artwork streams in
 * as `bookDetailArt` events (cover, author photo, edition covers, similar
 * covers). Open Library image hosts are unreachable on some networks, so the
 * backend proxies each one and sends a data URI instead of a URL the page
 * could load itself.
 *
 * No render.js import: this module paints itself, and render.js calls
 * paintBookView()/closeBookView() to keep browse and detail in sync.
 */
import { browseView, bookView } from "./dom.js";
import { state, nextDetailRequest, isCurrentDetail, cancelDetailRequest } from "./state.js";
import { escapeHtml, messageOf } from "./util.js";

const BOOK_ICON = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
        </svg>`;

// ------------------------------------------------------------------ helpers

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function initialsOf(name) {
    const words = String(name || "").split(/\s+/).filter(Boolean);
    return words.slice(0, 2).map((w) => w.charAt(0).toUpperCase()).join("") || "?";
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
    return `<div class="book-back-row">
                <button type="button" class="book-back" data-action="back">
                    <span aria-hidden="true">&#8592;</span> Back
                </button>
                <span class="book-source-note">Data and artwork from Open Library</span>
            </div>`;
}

function coverPlaceholder() {
    return `<div class="poster-placeholder">${BOOK_ICON}</div>`;
}

function loadingMarkup(book) {
    return `${backRow()}
        <div class="book-bento">
            <div class="book-tile t-cover">
                <div class="book-cover-frame">${coverPlaceholder()}</div>
            </div>
            <div class="book-tile t-ident">
                <div class="book-kicker"><span>Novel</span>${book.year ? `<span class="chip">${escapeHtml(String(book.year))}</span>` : ""}</div>
                <h1 class="book-title">${escapeHtml(book.title || "Untitled")}</h1>
                <p class="book-loading-note">Loading book details&hellip;</p>
            </div>
            <div class="book-tile t-facts">
                <div class="book-panel-head"><h2>Record</h2><span class="book-panel-sub">Open Library</span></div>
                <div class="book-skeleton">
                    ${Array.from({ length: 6 }, (_, i) => `<div class="skeleton-line" style="width:${78 - i * 8}%"></div>`).join("")}
                </div>
            </div>
            <section class="book-tile t-story">
                <div class="book-panel-head"><h2>The book</h2><span class="book-panel-sub">Open Library description</span></div>
                <div class="book-skeleton">
                    ${Array.from({ length: 4 }, (_, i) => `<div class="skeleton-line" style="width:${94 - i * 9}%"></div>`).join("")}
                </div>
            </section>
        </div>`;
}

function errorMarkup(book) {
    return `${backRow()}
        <div class="book-error">
            <h1 class="book-title">${escapeHtml(book.title || "Untitled")}</h1>
            <p>Could not load this book's details: ${escapeHtml(book.error || "unknown error")}</p>
            <button type="button" class="book-back" data-action="retry">Try again</button>
        </div>`;
}

function coverTile(detail) {
    return `<div class="book-tile t-cover" data-slot="cover">
                <div class="book-cover-frame">
                    <img class="book-cover-img hidden" alt="${escapeHtml(detail.title)} cover">
                    ${coverPlaceholder()}
                </div>
            </div>`;
}

function authorByline(detail) {
    const author = detail.author || {};
    const name = author.name || "Unknown author";
    const dates = author.dates || "";
    return `<div class="book-byline" data-slot="author">
                <span class="book-byline-photo">
                    <img class="book-author-img hidden" alt="${escapeHtml(name)}">
                    <span class="poster-placeholder book-author-placeholder">${escapeHtml(initialsOf(name))}</span>
                </span>
                <span class="book-byline-who">
                    <b>${escapeHtml(name)}</b>
                    ${dates ? `<span>${escapeHtml(dates)}</span>` : ""}
                </span>
            </div>`;
}

function identity(detail) {
    const chips = [
        detail.year ? String(detail.year) : "",
        detail.pages ? detail.pages + " pages" : "",
        detail.editionCount ? detail.editionCount + " editions" : "",
    ]
        .filter(Boolean)
        .map((value) => `<span class="chip">${escapeHtml(value)}</span>`)
        .join("");

    const rating = Number(detail.rating) > 0
        ? `<div class="book-score-row">
                <span class="book-score"><span class="star" aria-hidden="true">&#9733;</span>${Number(detail.rating).toFixed(1)}<span class="max">/5</span></span>
                ${formatVotes(detail.ratingCount) ? `<span class="book-votes">${escapeHtml(formatVotes(detail.ratingCount))} ratings &middot; Open Library</span>` : ""}
            </div>`
        : "";

    const openLibrary = secureLink(detail.openLibraryUrl);
    const sample = detail.firstSentence
        ? `<button type="button" class="book-action book-action-primary" data-action="sample" aria-pressed="false">
                <span aria-hidden="true">&#128214;</span> Read sample
            </button>`
        : "";
    const samplePanel = detail.firstSentence
        ? `<p class="book-sample hidden">${escapeHtml(detail.firstSentence)}</p>`
        : "";

    return `<div class="book-kicker"><span>Novel</span>${chips}</div>
            <h1 class="book-title">${escapeHtml(detail.title)}</h1>
            ${authorByline(detail)}
            ${rating}
            <div class="book-actions">
                ${sample}
                <button type="button" class="book-action" data-action="watchlist" aria-pressed="false">+ Want to read</button>
                <button type="button" class="book-action" data-action="favorite" aria-pressed="false"><span aria-hidden="true">&#9825;</span> Favorite</button>
                ${openLibrary ? `<a class="book-action" href="${openLibrary}" target="_blank" rel="noopener">Open Library <span aria-hidden="true">&#8599;</span></a>` : ""}
            </div>
            ${samplePanel}`;
}

function factsTile(detail) {
    const facts = detail.facts || [];
    return `<div class="book-tile t-facts">
                <div class="book-panel-head"><h2>Record</h2><span class="book-panel-sub">Open Library</span></div>
                <dl class="book-facts">
                    ${facts.length ? facts.map((fact) => `<div${fact.spanAll ? ' class="span-all"' : ""}>
                        <dt>${escapeHtml(fact.label)}</dt>
                        <dd${fact.muted ? ' class="muted"' : ""}>${escapeHtml(fact.value)}</dd>
                    </div>`).join("") : `<div class="span-all"><dt>Record</dt><dd class="muted">Open Library has no structured fields for this work.</dd></div>`}
                </dl>
            </div>`;
}

function storyTile(detail) {
    const subjects = (detail.subjects || []).length
        ? `<ul class="book-keywords" aria-label="Open Library subjects">
                ${detail.subjects.map((subject) => `<li>${escapeHtml(subject)}</li>`).join("")}
            </ul>`
        : "";
    return `<section class="book-tile t-story" aria-labelledby="bookStoryHeading">
                <div class="book-panel-head"><h2 id="bookStoryHeading">The book</h2><span class="book-panel-sub">Open Library description</span></div>
                <p class="book-lede">${escapeHtml(detail.description || "Open Library has no description for this work yet.")}</p>
                ${subjects}
            </section>`;
}

function similarTile(detail) {
    const similar = detail.similar || [];
    if (!similar.length) return "";
    return `<section class="book-tile t-sim" aria-labelledby="bookSimHeading">
                <div class="book-panel-head">
                    <h2 id="bookSimHeading">More like this</h2>
                    <span class="book-panel-sub">${similar.length} works</span>
                </div>
                <div class="book-sim-row">
                    ${similar.map((book) => {
                        const scoreText = Number(book.rating) > 0
                            ? `<span class="book-sim-score">${Number(book.rating).toFixed(1)}</span>`
                            : "";
                        const meta = [book.author, book.year ? String(book.year) : ""]
                            .filter(Boolean).join(" \u00b7 ");
                        return `<button type="button" class="book-sim-card"
                                    data-action="similar"
                                    data-work-key="${escapeHtml(String(book.workKey))}"
                                    data-title="${escapeHtml(book.title || "")}"
                                    data-year="${escapeHtml(String(book.year || ""))}">
                            <span class="book-sim-poster" data-slot="similar:${escapeHtml(String(book.workKey))}">
                                <img class="book-sim-img hidden" alt="${escapeHtml(book.title || "")} cover">
                                <span class="poster-placeholder book-sim-placeholder">${BOOK_ICON}</span>
                                ${scoreText}
                            </span>
                            <span class="book-sim-title">${escapeHtml(book.title || "Untitled")}</span>
                            <span class="book-sim-meta">${escapeHtml(meta || "\u2013")}</span>
                        </button>`;
                    }).join("")}
                </div>
            </section>`;
}

function editionsTile(detail) {
    const editions = detail.editions || [];
    if (!editions.length) return "";
    return `<section class="book-tile t-editions" aria-labelledby="bookEditionsHeading">
                <div class="book-panel-head">
                    <h2 id="bookEditionsHeading">Other editions</h2>
                    <span class="book-panel-sub">${editions.length}${detail.editionCount > editions.length ? " of " + detail.editionCount : ""}</span>
                </div>
                <div class="book-editions-row">
                    ${editions.map((edition, index) => `
                        <div class="book-edition" data-slot="edition:${index}">
                            <span class="book-edition-art">
                                <img class="book-edition-img hidden" alt="${escapeHtml(edition.year || "Edition")} cover">
                                <span class="poster-placeholder book-edition-placeholder">${BOOK_ICON}</span>
                            </span>
                            <span class="book-edition-meta">
                                <b>${escapeHtml(edition.year || "Edition")}</b>
                                <span>${escapeHtml(edition.isbn || edition.pages || "")}</span>
                            </span>
                        </div>`).join("")}
                </div>
            </section>`;
}

function authorTile(detail) {
    const author = detail.author || {};
    if (!author.name) return "";
    const wikipedia = secureLink(author.wikipediaUrl);
    const openLibrary = secureLink(author.openLibraryUrl);
    const link = wikipedia
        ? `<a class="book-link-quiet" href="${wikipedia}" target="_blank" rel="noopener">Wikipedia <span aria-hidden="true">&#8599;</span></a>`
        : openLibrary
            ? `<a class="book-link-quiet" href="${openLibrary}" target="_blank" rel="noopener">Open Library <span aria-hidden="true">&#8599;</span></a>`
            : "";
    return `<section class="book-tile t-author" aria-labelledby="bookAuthorHeading">
                <div class="book-panel-head"><h2 id="bookAuthorHeading">The author</h2><span class="book-panel-sub">Open Library</span></div>
                <div class="book-author-card">
                    <div class="book-author-photo" data-slot="author">
                        <img class="book-author-img hidden" alt="${escapeHtml(author.name)}">
                        <span class="poster-placeholder book-author-placeholder">${escapeHtml(initialsOf(author.name))}</span>
                    </div>
                    <div class="book-author-who">
                        <span class="name">${escapeHtml(author.name)}</span>
                        ${author.dates ? `<span class="dates">${escapeHtml(author.dates)}</span>` : ""}
                        ${author.bio ? `<p>${escapeHtml(author.bio)}</p>` : ""}
                        ${link}
                    </div>
                </div>
            </section>`;
}

function detailMarkup(detail) {
    return `${backRow()}
        <div class="book-bento">
            ${coverTile(detail)}
            <div class="book-tile t-ident">${identity(detail)}</div>
            ${factsTile(detail)}
            ${storyTile(detail)}
            ${similarTile(detail)}
            ${editionsTile(detail)}
            ${authorTile(detail)}
        </div>`;
}

// ------------------------------------------------------------------- paint

export function paintBookView() {
    const book = state.book;
    if (!book) {
        bookView.innerHTML = "";
        bookView.classList.add("hidden");
        return;
    }

    bookView.classList.remove("hidden");
    if (book.status === "loading") {
        bookView.innerHTML = loadingMarkup(book);
        return;
    }
    if (book.status === "error") {
        bookView.innerHTML = errorMarkup(book);
        return;
    }
    bookView.innerHTML = detailMarkup(book.detail);
}

/* Artwork arrives by slot: cover, author, edition:<i>, similar:<workKey>. The
 * slot is matched against data-slot, never a CSS selector. The author slot
 * appears twice (identity byline + author tile), so every match paints. */
function paintArt(slot, dataUri) {
    if (!dataUri || !state.book || state.book.status !== "ready") return;
    bookView.querySelectorAll("[data-slot]").forEach((node) => {
        if (node.dataset.slot !== slot) return;
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
    });
}

export function closeBookView() {
    bookView.classList.add("hidden");
    bookView.innerHTML = "";
}

function closeBookDetail() {
    cancelDetailRequest();
    state.view = "browse";
    state.book = null;
    closeBookView();
    browseView.classList.remove("hidden");
}

// ----------------------------------------------------------------- loading

export async function openBookDetail(item) {
    if (!item || item.workKey == null || item.workKey === "") return;
    const seq = nextDetailRequest();
    state.view = "book";
    state.book = {
        status: "loading",
        workKey: String(item.workKey),
        title: item.title || "Untitled",
        year: item.year || "",
        detail: null,
        error: null,
    };
    browseView.classList.add("hidden");
    paintBookView();

    if (!window.krema) {
        state.book.status = "error";
        state.book.error = "API bridge not ready yet. Please wait a moment and try again.";
        paintBookView();
        return;
    }

    try {
        const generation = await window.krema.invoke("requestBookDetail", { workKey: String(item.workKey) });
        if (!isCurrentDetail(seq)) return;

        while (window.krema && isCurrentDetail(seq)) {
            const reply = await window.krema.invoke("pollBookDetail", { generation: generation });
            if (!reply || !isCurrentDetail(seq)) return;
            if (reply.status === "done" && reply.detail) {
                state.book = {
                    status: "ready",
                    workKey: String(reply.detail.workKey || state.book.workKey),
                    title: reply.detail.title,
                    year: reply.detail.year,
                    detail: reply.detail,
                    error: null,
                };
                paintBookView();
                pollArtwork(seq);
                return;
            }
            if (reply.status === "error") {
                state.book.status = "error";
                state.book.error = reply.error || "Failed to load book details.";
                paintBookView();
                return;
            }
            if (reply.status === "superseded") return;
            await delay(100);
        }
    } catch (e) {
        if (!isCurrentDetail(seq) || !state.book) return;
        state.book.status = "error";
        state.book.error = messageOf(e);
        paintBookView();
    }
}

// The artwork drain doubles as the completion signal (−1 when done).
async function pollArtwork(seq) {
    while (window.krema && state.view === "book" && isCurrentDetail(seq)) {
        let pending;
        try {
            pending = await window.krema.invoke("pollBookDetailArt", {});
        } catch (e) {
            return;
        }
        if (pending == null || pending < 0) return;
        await delay(120);
    }
}

// ------------------------------------------------------------------ events

if (window.krema && typeof window.krema.on === "function") {
    window.krema.on("bookDetailArt", (data) => {
        if (data && data.payload) {
            paintArt(data.payload.slot, data.payload.dataUri);
        }
    });
}

bookView.addEventListener("click", (e) => {
    const action = e.target.closest("[data-action]");
    if (!action) return;
    switch (action.dataset.action) {
        case "back":
            closeBookDetail();
            break;
        case "retry":
            openBookDetail({
                workKey: state.book && state.book.workKey,
                title: state.book && state.book.title,
                year: state.book && state.book.year,
            });
            break;
        case "sample": {
            const on = action.getAttribute("aria-pressed") !== "true";
            action.setAttribute("aria-pressed", String(on));
            action.classList.toggle("active", on);
            action.innerHTML = on
                ? '<span aria-hidden="true">&#128214;</span> Hide sample'
                : '<span aria-hidden="true">&#128214;</span> Read sample';
            const panel = bookView.querySelector(".book-sample");
            if (panel) panel.classList.toggle("hidden", !on);
            break;
        }
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
                action.innerHTML = on ? "&#10003; Want to read" : "+ Want to read";
            }
            break;
        }
        case "similar":
            openBookDetail({
                workKey: action.dataset.workKey,
                title: action.dataset.title,
                year: action.dataset.year,
            });
            break;
        default:
            break;
    }
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view === "book") {
        closeBookDetail();
    }
});
