/* Music row: MusicBrainz release groups, square album covers, artist as the
 * subtitle line, type/tags instead of a rating (MusicBrainz has no numeric
 * ratings). */
import { baseCard, wireCard } from "../cards.js";
import { escapeHtml } from "../util.js";

const COVER_PLACEHOLDER = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="3"></circle>
        </svg>`;

function albumSubtitle(album) {
    return [album.primaryType, album.genre].filter(Boolean).join(" · ");
}

function albumCard(album, idx, onOpen) {
    const card = baseCard(musicDomain, album, idx);

    const year = album.year || "–";
    const subtitle = albumSubtitle(album);

    card.innerHTML = `
            <div class="card-cover">
                <img class="poster-img hidden" alt="${escapeHtml(album.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${COVER_PLACEHOLDER}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(album.title || "Untitled")}</div>
                <div class="card-subtitle">${escapeHtml(album.artist || "Unknown Artist")}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    <span class="card-meta-item">${escapeHtml(subtitle)}</span>
                </div>
            </div>
        `;

    wireCard(card, onOpen);
    return card;
}

function albumDetail(album) {
    const year = album.year || "–";
    const subtitle = albumSubtitle(album);
    const body = [album.artist, subtitle].filter(Boolean).join(" — ");
    return { title: `Selected: ${album.title || "Untitled"} (${year})`, body: body };
}

export const musicDomain = {
    key: "music",
    request: "requestGeniusMusic",
    data: "pollGeniusMusicData",
    art: "pollGeniusMusicPosters",
    event: "musicPoster",
    plural: "Music",
    emptyLabel: "albums",
    cardClass: "movie-card music-card",
    artClass: "card-cover",
    idField: "id",
    card: albumCard,
    detail: albumDetail,
};
