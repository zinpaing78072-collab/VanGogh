/* Shared card plumbing: the element shell, the entrance stagger, the
 * placeholder fallback and the click wiring.
 *
 * Builders take the domain row as a parameter instead of importing DOMAINS —
 * a row passes itself. That keeps this module free of any knowledge about
 * which content forms exist, and keeps the dependency graph acyclic.
 */
import { escapeHtml, posterFailed } from "./util.js";

const POSTER_PLACEHOLDER = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
            <line x1="3" y1="12" x2="21" y2="12"></line>
        </svg>`;

// CSS class, the data-<domain>-id identity the artwork router targets, and the
// stagger index for the entrance.
export function baseCard(domain, item, idx) {
    const card = document.createElement("div");
    card.className = domain.cardClass;
    card.dataset[domain.key + "Id"] = String(item[domain.idField] || "");
    card.style.setProperty("--i", idx);
    return card;
}

// Every card fails back to its placeholder and opens the same way; onOpen is
// the one click policy, handed in by the renderer.
export function wireCard(card, onOpen) {
    card.querySelector(".poster-img").addEventListener("error", function () {
        posterFailed(this);
    });
    card.addEventListener("click", onOpen);
}

// The portrait-poster shape shared by movies and games: title, then
// year / genre / rating.
export function posterCard(domain, item, idx, onOpen) {
    const card = baseCard(domain, item, idx);

    const title = item.title || item.game_title || "Untitled";
    const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
    const rating = item.rating ? item.rating.toFixed(1) : "–";
    const genre = item.genre || "";

    card.innerHTML = `
            <div class="card-poster">
                <img class="poster-img hidden" alt="${escapeHtml(title)} poster"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${POSTER_PLACEHOLDER}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(title)}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    <span class="card-meta-item">${escapeHtml(genre)}</span>
                    <span class="card-meta-item card-rating">★ ${rating}</span>
                </div>
            </div>
        `;

    wireCard(card, onOpen);
    return card;
}
