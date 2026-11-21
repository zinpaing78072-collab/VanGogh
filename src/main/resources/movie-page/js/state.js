/* Page state and the request sequence.
 *
 * state is the single source the renderer paints from. requestSeq is a
 * monotonic counter: a load captures its number and every asynchronous
 * continuation checks it before touching state or the DOM, so a reply or
 * artwork drain from a superseded domain/query cannot reach the page.
 */

export const state = {
    view: "browse",   // "browse" | "song" | "movie" | "book" | "game" | "settings" — render() dispatches on it
    domain: "movie",
    title: "Trending Movies",
    status: "idle",   // "idle" | "loading" | "ready" | "error"
    ai: false,        // AI recommendation mode (the header toggle)
    query: "",        // the term the current grid answers
    kind: "trending", // "trending" | "search" | "recommend"
    items: [],
    error: null,
    // Browse artwork for the active form: id → data URI, filled by artwork.js.
    // Kept in state so a restored session (or a Settings round-trip) repaints
    // its posters without asking Java for them again.
    artwork: {},
    // The open song detail: { status, id, title, artist, detail, artDataUri, error }
    song: null,
    // The open movie detail: { status, id, title, year, detail, error }
    movie: null,
    // The open book detail: { status, workKey, title, year, detail, error }
    book: null,
    // The open game detail: { status, id, title, year, detail, error }
    game: null,
};

// One saved session per content form, in page memory only. The active form
// lives in `state`; content.js snapshots it here when the user navigates away
// and copies it back when they return. Closing the app clears it.
export const sessions = Object.create(null);

let requestSeq = 0;
let detailSeq = 0;

export function nextRequest() {
    return ++requestSeq;
}

export function isCurrent(seq) {
    return seq === requestSeq;
}

// The detail view has its own sequence so opening a song never cancels the
// browse grid (or its artwork stream), and vice versa.
export function nextDetailRequest() {
    return ++detailSeq;
}

export function isCurrentDetail(seq) {
    return seq === detailSeq;
}

export function cancelDetailRequest() {
    detailSeq += 1;
}
