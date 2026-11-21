/* Request lifecycle for the browse view.
 *
 * Delivery is drained, never pushed: the request command kicks off the
 * IO-pool fetch and returns the request generation immediately (the UI thread
 * never blocks on network), then the data drain is polled until this
 * generation resolves. Artwork drains double as the completion signal.
 */
import { state, sessions, nextRequest, nextDetailRequest, isCurrent, cancelDetailRequest } from "./state.js";
import { render } from "./render.js";
import { DOMAINS } from "./domains.js";
import { openDetail, resumeDetailArt } from "./detail.js";
import { searchInput } from "./dom.js";
import { messageOf } from "./util.js";

/* Sessions that survive navigation.
 *
 * The active form lives in `state`; this module snapshots it into
 * `sessions[domain]` when the user leaves and copies it back when they return,
 * so a form's last query, cards, artwork, open detail and scroll position come
 * back without an IPC call. Page memory only — closing the app clears it.
 */
export function rememberCurrent() {
    if (!state.domain || state.view === "settings" || state.status === "idle") return;
    const domain = DOMAINS[state.domain];
    if (!domain) return;

    const view = state.view === domain.view ? domain.view : "browse";
    sessions[state.domain] = {
        view: view,
        title: state.title,
        status: state.status,
        items: state.items,
        error: state.error,
        query: state.query,
        kind: state.kind,
        artwork: state.artwork,
        detail: view === "browse" ? null : state[view],
        scrollY: window.scrollY,
    };
}

/** Restore a form's saved session. Returns false when there is none. */
export function restoreSession(domainKey) {
    const saved = sessions[domainKey];
    if (!saved || !DOMAINS[domainKey]) return false;

    // Save the form we are leaving before its live state is overwritten, and
    // stop its browse pollers: restoring a session fires no request of its own,
    // so nothing else would supersede them.
    rememberCurrent();
    nextRequest();

    // A browse fetch that was still in flight has no poller left, so run it
    // again instead of restoring a skeleton forever.
    if (saved.view === "browse" && saved.status === "loading") {
        loadContent(domainKey, saved.kind || "trending", saved.query || "");
        return true;
    }

    cancelDetailRequest();
    state.domain = domainKey;
    state.title = saved.title;
    state.status = saved.status;
    state.items = saved.items || [];
    state.error = saved.error || null;
    state.query = saved.query || "";
    state.kind = saved.kind || "trending";
    state.artwork = saved.artwork || {};
    state.song = null;
    state.movie = null;
    state.book = null;
    state.game = null;
    searchInput.value = state.query;

    // Paint the form's grid even when a detail is what reopens: Back has to
    // land on this form's cards, not on whatever the previous form left behind.
    state.view = "browse";
    render();

    if (saved.view !== "browse" && saved.detail) {
        state.view = saved.view;
        state[saved.view] = saved.detail;
        if (saved.detail.status === "ready") {
            render();
            restoreScroll(saved.scrollY);
            // Any slot that had not streamed in before the user left resumes
            // its drain; a complete stream answers −1 at once.
            resumeDetailArt(domainKey, nextDetailRequest());
        } else {
            // Loading or failed when we left: request it again through the
            // normal detail seam (its state carries the id/workKey it needs).
            window.scrollTo(0, 0);
            openDetail(domainKey, saved.detail);
        }
    } else {
        restoreScroll(saved.scrollY);
        // A poster stream that was still running when the user left resumes
        // here; a complete one answers −1 at once.
        if (saved.status === "ready") {
            void pollPostersUntilDone(DOMAINS[domainKey].art, () => state.domain === domainKey);
        }
    }
    return true;
}

// Only the latest restore may move the page: a click that lands before the
// animation frame must not be scrolled by the form it just left.
let scrollToken = 0;

function restoreScroll(y) {
    const token = ++scrollToken;
    const target = Number(y) || 0;
    requestAnimationFrame(() => {
        if (token === scrollToken) window.scrollTo(0, target);
    });
}

export async function loadContent(domainKey, kind, term) {
    const domain = DOMAINS[domainKey];
    if (!domain) return;

    // Snapshot the form we are leaving before this load overwrites state.
    rememberCurrent();

    const seq = nextRequest();

    // A browse load always leaves a detail view: drop any pending detail
    // reply so it cannot paint over the newly requested grid.
    cancelDetailRequest();
    state.view = "browse";
    state.song = null;
    state.movie = null;
    state.book = null;
    state.game = null;
    state.domain = domainKey;
    state.title = kind === "recommend" ? "AI Recommendations"
        : kind === "search" ? "Search Results"
        : `Trending ${domain.plural}`;
    state.status = "loading";
    state.items = [];
    state.error = null;
    state.query = term || "";
    state.kind = kind;
    state.artwork = {};
    searchInput.value = state.query;
    scrollToken += 1;
    window.scrollTo(0, 0);
    render();

    if (!window.krema) {
        state.status = "error";
        state.error = "API bridge not ready yet. Please wait a moment and try again.";
        render();
        return;
    }

    const current = () => isCurrent(seq);

    try {
        const generation = await window.krema.invoke(domain.request, { kind: kind, query: term });
        if (!current()) return;   // superseded while the kickoff resolved

        const reply = await pollDataUntilDone(domain.data, generation, current);
        if (!reply || !current()) return;   // superseded, stale, or stopped

        if (reply.status === "error") {
            state.status = "error";
            state.error = reply.error || "Failed to load content.";
            render();
            return;
        }

        state.items = reply.items || [];
        state.status = "ready";
        render();
        pollPostersUntilDone(domain.art, current);
    } catch (e) {
        if (!current()) return;
        state.status = "error";
        state.error = "Failed to load content: " + messageOf(e);
        render();
    }
}

// Poll the data drain until this generation resolves. Returns the done or
// error reply, or null when the caller no longer owns the page (a newer
// request superseded it) or the bridge disappeared.
async function pollDataUntilDone(command, generation, current) {
    while (window.krema && current()) {
        let reply;
        try {
            reply = await window.krema.invoke(command, { generation: generation });
        } catch (e) {
            return { status: "error", error: "Failed to load content: " + messageOf(e) };
        }
        if (reply && reply.status === "done") {
            return reply;
        }
        if (reply && reply.status === "error") {
            return reply;
        }
        if (reply && reply.status === "superseded") {
            return null;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));   // "pending"
    }
    return null;
}

// Resolves -1 when nothing is pending. A superseded request stops polling.
async function pollPostersUntilDone(command, current) {
    while (window.krema && current()) {
        let pending;
        try {
            pending = await window.krema.invoke(command, {});
        } catch (e) {
            return;
        }
        if (pending == null || pending < 0) {
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 150));
    }
}
