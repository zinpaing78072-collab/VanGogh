/* Shell interactions: the search box, the AI toggle, the sidebar's
 * content-form buttons and the sidebar collapse toggle.
 *
 * AI mode is opt-in: the toggle only changes the `kind` a search sends
 * ("recommend" instead of "search"), so direct search and trending keep their
 * existing behavior. An empty box is always trending — no model call.
 *
 * The content-form buttons carry data-domain (the same keys as js/domains.js);
 * everything else in the menu is shell chrome with no backend yet and stays
 * inert. The active button is painted by render.js from state.domain, so this
 * module never guesses which form is on screen.
 *
 * No native <select> anywhere: its macOS NSMenu popup traps the AppKit thread
 * and freezes the window. Plain buttons carry no such risk.
 */
import { searchInput, searchBtn, aiToggle, sidebar, sidebarToggle, sidebarNav, settingsNavItem } from "./dom.js";
import { state } from "./state.js";
import { DOMAINS } from "./domains.js";
import { loadContent, restoreSession } from "./content.js";
import { openSettings } from "./settings.js";

function submitSearch() {
    const term = searchInput.value.trim();
    const kind = term === "" ? "trending" : state.ai ? "recommend" : "search";
    loadContent(state.domain, kind, term);
}

function paintAiToggle() {
    aiToggle.setAttribute("aria-pressed", String(state.ai));
    // aria-pressed carries the state; the tooltip names it for pointer users.
    aiToggle.title = state.ai ? "AI recommendations on" : "AI recommendations off";
}

aiToggle.addEventListener("click", () => {
    state.ai = !state.ai;
    paintAiToggle();
});

paintAiToggle();

searchBtn.addEventListener("click", submitSearch);

// keydown, not keypress: keypress is deprecated and synthetic Enter presses
// (and some webview IME paths) never fire it.
searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
        submitSearch();
    }
});

// One listener for the whole menu. Buttons without data-domain are the
// not-yet-wired areas (Home, My Lists, Favorites): they are deliberately inert,
// so a click falls through without changing the page.
sidebarNav.addEventListener("click", (e) => {
    const item = e.target.closest(".nav-item[data-domain]");
    if (!item) return;
    const key = item.dataset.domain;
    if (!DOMAINS[key]) return;

    // The active form already shows its session; reloading trending would throw
    // it away. Settings is the exception — its form session is waiting behind it.
    if (key === state.domain && state.view !== "settings") return;

    if (!restoreSession(key)) {
        loadContent(key, "trending", "");
    }
});

// Settings sits in the sidebar foot, outside the form switcher: it opens the
// AI configuration page (UI only for now, see js/settings.js).
settingsNavItem.addEventListener("click", openSettings);

function setCollapsed(collapsed) {
    sidebar.classList.toggle("collapsed", collapsed);

    const action = collapsed ? "Expand sidebar" : "Collapse sidebar";
    sidebarToggle.setAttribute("aria-expanded", String(!collapsed));
    sidebarToggle.setAttribute("aria-label", action);
    sidebarToggle.title = action;

    // The labels are hidden when collapsed, so the native title tooltip is the
    // only affordance left. Clear it again when the labels are visible.
    sidebarNav.querySelectorAll(".nav-text").forEach((label) => {
        const item = label.closest(".nav-item");
        const text = label.textContent.trim();
        item.setAttribute("aria-label", text);
        item.title = collapsed ? text : "";
    });
}

// Below this width the 240px rail would leave the card grid too little room, so
// the sidebar starts collapsed there and follows later resizes — until the user
// picks a state with the toggle, which wins from then on. A ResizeObserver is
// needed as well as the resize event: Krema can size the webview after the
// module has run, and no resize event always reaches the page.
const NARROW_WIDTH = 900;
let userChoseSidebarState = false;

function applyNarrowDefault() {
    if (!userChoseSidebarState) {
        setCollapsed(window.innerWidth < NARROW_WIDTH);
    }
}

sidebarToggle.addEventListener("click", () => {
    userChoseSidebarState = true;
    setCollapsed(!sidebar.classList.contains("collapsed"));
});

window.addEventListener("resize", applyNarrowDefault);
new ResizeObserver(applyNarrowDefault).observe(document.documentElement);
applyNarrowDefault();
