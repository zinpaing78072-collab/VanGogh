/* Shell interactions: the search box, the sidebar's content-form buttons and
 * the sidebar collapse toggle.
 *
 * The content-form buttons replaced the old custom dropdown. They carry
 * data-domain (the same keys as js/domains.js); everything else in the menu is
 * shell chrome with no backend yet and stays inert. The active button is
 * painted by render.js from state.domain, so this module never guesses which
 * form is on screen.
 *
 * No native <select> anywhere: its macOS NSMenu popup traps the AppKit thread
 * and freezes the window. Plain buttons carry no such risk.
 */
import { appShell, searchInput, searchBtn, sidebar, sidebarToggle, sidebarNav } from "./dom.js";
import { state } from "./state.js";
import { DOMAINS } from "./domains.js";
import { loadContent } from "./content.js";

function submitSearch() {
    const term = searchInput.value.trim();
    loadContent(state.domain, term === "" ? "trending" : "search", term);
}

searchBtn.addEventListener("click", submitSearch);

// keydown, not keypress: keypress is deprecated and synthetic Enter presses
// (and some webview IME paths) never fire it.
searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
        submitSearch();
    }
});

// One listener for the whole menu. Buttons without data-domain are the
// not-yet-wired areas (Home, Library, My Lists, Favorites, Settings): they are
// deliberately inert, so a click falls through without changing the page.
sidebarNav.addEventListener("click", (e) => {
    const item = e.target.closest(".nav-item[data-domain]");
    if (!item) return;
    const key = item.dataset.domain;
    if (!DOMAINS[key]) return;
    loadContent(key, "trending", "");
});

function setCollapsed(collapsed) {
    const changed = sidebar.classList.contains("collapsed") !== collapsed;
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

    if (changed) {
        markResizing();
    }
}

// The rail animates its width, so the sticky header's width changes too. A
// backdrop-filter on a box whose geometry changes is recomputed on every frame
// of that animation, and two full-height blur passes per frame is what made the
// toggle feel sluggish in the system webview. js/style.css drops those blurs
// while .resizing is on the shell; the class comes off once the width has
// settled (see RESIZE_MS). Reduced motion means no animation at all, so there
// is nothing to make cheaper — and the momentary tint change would only flicker.
const RESIZE_MS = 200;
const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
let resizeTimer = 0;

function markResizing() {
    if (prefersReducedMotion.matches) return;
    appShell.classList.add("resizing");
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => appShell.classList.remove("resizing"), RESIZE_MS);
}

// Below this width the 240px rail would leave the card grid too little room, so
// the sidebar starts collapsed there and follows later resizes — until the user
// picks a state with the toggle, which wins from then on. A ResizeObserver is
// needed as well as the resize event: Krema can size the webview after the
// module has run, and no resize event always reaches the page.
const NARROW_WIDTH = 900;
let userChoseSidebarState = false;

// Only touch the rail when the width crosses the threshold. The observer also
// fires when the page grows or shrinks vertically (collapsing the rail makes the
// cards taller), and re-applying the default there churned the class — and the
// animation — for nothing.
function applyNarrowDefault() {
    if (userChoseSidebarState) return;
    const narrow = window.innerWidth < NARROW_WIDTH;
    if (sidebar.classList.contains("collapsed") !== narrow) {
        setCollapsed(narrow);
    }
}

sidebarToggle.addEventListener("click", () => {
    userChoseSidebarState = true;
    setCollapsed(!sidebar.classList.contains("collapsed"));
});

window.addEventListener("resize", applyNarrowDefault);
new ResizeObserver(applyNarrowDefault).observe(document.documentElement);
applyNarrowDefault();
