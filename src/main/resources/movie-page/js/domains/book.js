/* Book row: Open Library works, the design-system Book Card shape (portrait
 * poster, "year  Author · N pages" meta, rating on its own row), and a
 * book-specific detail (author, editions, subjects, first line). */
import { baseCard, wireCard } from "../cards.js";
import { escapeHtml } from "../util.js";

const COVER_PLACEHOLDER = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
        </svg>`;

function bookCard(book, idx, onOpen) {
    const card = baseCard(bookDomain, book, idx);

    const rating = book.rating ? book.rating.toFixed(1) : "";
    const year = book.year || "–";
    const authorPages = [book.author, book.pages ? book.pages + " pages" : ""]
        .filter((part) => part).join(" · ");

    card.innerHTML = `
            <div class="card-poster">
                <img class="poster-img hidden" alt="${escapeHtml(book.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${COVER_PLACEHOLDER}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(book.title)}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    ${authorPages ? `<span class="card-meta-item">${escapeHtml(authorPages)}</span>` : ""}
                </div>
                ${rating ? `<div class="card-meta"><span class="card-meta-item card-rating">★ ${rating}</span></div>` : ""}
            </div>
        `;

    wireCard(card, onOpen);
    return card;
}

function bookDetail(book) {
    const lines = [];
    if (book.author) lines.push(`Author: ${book.author}`);
    if (book.year) lines.push(`First published: ${book.year}`);
    if (book.rating) lines.push(`Rating: ${book.rating.toFixed(1)} / 5`);
    if (book.pages) lines.push(`Pages: ${book.pages}`);
    if (book.editionCount) lines.push(`Editions catalogued: ${book.editionCount}`);
    if (book.subjects) lines.push(`Subjects: ${book.subjects}`);
    if (book.firstSentence) lines.push("", `“${book.firstSentence}”`);
    return { title: book.title, body: lines.join("\n") };
}

export const bookDomain = {
    key: "book",
    view: "book",
    request: "requestBooks",
    data: "pollBookData",
    art: "pollBookCovers",
    event: "bookCover",
    plural: "Books",
    emptyLabel: "books",
    cardClass: "movie-card book-card",
    artClass: "card-poster",
    idField: "workKey",
    card: bookCard,
    detail: bookDetail,
};
