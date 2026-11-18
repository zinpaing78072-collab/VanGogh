/* Page state and the request sequence.
 *
 * state is the single source the renderer paints from. requestSeq is a
 * monotonic counter: a load captures its number and every asynchronous
 * continuation checks it before touching state or the DOM, so a reply or
 * artwork drain from a superseded domain/query cannot reach the page.
 */

export const state = {
    view: "browse",   // future views dispatch in render(); only "browse" exists
    domain: "movie",
    title: "Trending Movies",
    status: "idle",   // "idle" | "loading" | "ready" | "error"
    items: [],
    error: null,
};

let requestSeq = 0;

export function nextRequest() {
    return ++requestSeq;
}

export function isCurrent(seq) {
    return seq === requestSeq;
}
