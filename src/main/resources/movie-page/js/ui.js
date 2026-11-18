/* Search box and content filter.
 *
 * The filter is a custom DOM widget (a div — no native <select>), so it has no
 * .value: the active form lives in state.domain, and contentFilter.value must
 * never be read (the select-era read silently disabled the Game filter).
 */
import { searchInput, searchBtn, contentFilter, contentFilterMenu, contentFilterLabel } from "./dom.js";
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

// A native <select> opens a modal macOS NSMenu whose event loop fights the
// CEF/AWT message pump on the AppKit thread and freezes the whole window (menu
// never closes, red button dead) — hence the plain DOM widget.
function handleFilterChange(value) {
    if (!DOMAINS[value]) return;
    loadContent(value, "trending", "");
}

function closeFilterMenu() {
    contentFilterMenu.classList.add("hidden");
}

contentFilter.addEventListener("click", (e) => {
    const option = e.target.closest(".filter-option");
    if (option) {
        contentFilterMenu.querySelectorAll(".filter-option").forEach((el) =>
            el.classList.toggle("selected", el === option));
        contentFilterLabel.textContent = option.textContent;
        closeFilterMenu();
        handleFilterChange(option.dataset.value);
    } else {
        contentFilterMenu.classList.toggle("hidden");
    }
});

document.addEventListener("click", (e) => {
    if (!contentFilter.contains(e.target)) {
        closeFilterMenu();
    }
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
        closeFilterMenu();
    }
});
