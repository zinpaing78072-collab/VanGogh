/* Request lifecycle for the browse view.
 *
 * Delivery is drained, never pushed: the request command kicks off the
 * IO-pool fetch and returns the request generation immediately (the UI thread
 * never blocks on network), then the data drain is polled until this
 * generation resolves. Artwork drains double as the completion signal.
 */
import { state, nextRequest, isCurrent, cancelDetailRequest } from "./state.js";
import { render } from "./render.js";
import { DOMAINS } from "./domains.js";
import { messageOf } from "./util.js";

export async function loadContent(domainKey, kind, term) {
    const domain = DOMAINS[domainKey];
    const seq = nextRequest();

    // A browse load always leaves the song detail: drop any pending detail
    // reply so it cannot paint over the newly requested grid.
    cancelDetailRequest();
    state.view = "browse";
    state.song = null;
    state.domain = domainKey;
    state.title = kind === "recommend" ? "AI Recommendations"
        : kind === "search" ? "Search Results"
        : `Trending ${domain.plural}`;
    state.status = "loading";
    state.items = [];
    state.error = null;
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
