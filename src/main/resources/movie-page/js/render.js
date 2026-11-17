/* Rendering: state goes in, DOM comes out. Everything that paints the browse
 * view lives here; content.js only changes state and asks for a render.
 */
import { grid, sectionTitle } from "./dom.js";
import { state } from "./state.js";
import { DOMAINS } from "./domains.js";
import { openDetail } from "./detail.js";
import { escapeHtml } from "./util.js";

export function render() {
    if (state.view === "browse") {
        renderBrowse();
    }
}

function renderBrowse() {
    sectionTitle.textContent = state.title;
    if (state.status === "loading") {
        renderSkeletonGrid();
    } else if (state.status === "error") {
        renderEmptyGrid(state.error || "Failed to load content.");
    } else if (state.status === "ready") {
        renderCards(state.domain, state.items);
    }
}

function renderCards(domainKey, items) {
    const domain = DOMAINS[domainKey];
    grid.innerHTML = "";
    if (!items || items.length === 0) {
        grid.innerHTML = emptyStateMarkup(`No ${domain.emptyLabel} found.`);
        return;
    }
    items.forEach((item, idx) => grid.appendChild(domain.card(item, idx, () => openDetail(domainKey, item))));
}

function renderEmptyGrid(message) {
    grid.innerHTML = emptyStateMarkup(message);
}

// Error text is escaped here, once — content.js passes the raw message.
function emptyStateMarkup(message) {
    return `
            <div class="empty-state">
                <p style="color:#ff6b6b; margin:0;">${escapeHtml(message)}</p>
            </div>`;
}

// Skeleton cards shown the moment a request starts — the grid should never sit
// empty or stale while the fetch is in flight. The skeleton artwork area
// matches the active domain's card shape.
function renderSkeletonGrid() {
    const artClass = DOMAINS[state.domain].artClass;
    grid.innerHTML = "";
    for (let i = 0; i < 8; i++) {
        const card = document.createElement("div");
        card.className = "movie-card skeleton-card";
        card.style.setProperty("--i", i);   // stagger the entrance
        card.innerHTML = `
                <div class="${artClass} skeleton-poster"></div>
                <div class="card-info">
                    <div class="skeleton-line skeleton-title"></div>
                    <div class="skeleton-line skeleton-meta"></div>
                </div>
            `;
        grid.appendChild(card);
    }
}
