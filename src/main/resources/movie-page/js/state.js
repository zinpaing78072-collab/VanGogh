/* Page state and the request sequence.
 *
 * state is the single source the renderer paints from. requestSeq is a
 * monotonic counter: a load captures its number and every asynchronous
 * continuation checks it before touching state or the DOM, so a reply or
 * artwork drain from a superseded domain/query cannot reach the page.
 */

export const state = {
    view: "browse",   // "browse" | "song" | "settings" — render() dispatches on it
    domain: "movie",
    title: "Trending Movies",
    status: "idle",   // "idle" | "loading" | "ready" | "error"
    ai: false,        // AI recommendation mode (the header toggle)
    items: [],
    error: null,
    // The open song detail: { status, id, title, artist, detail, artDataUri, error }
    song: null,
};

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
