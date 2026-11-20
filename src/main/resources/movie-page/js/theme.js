/* Theme switching — the one place that knows a theme exists.
 *
 * A theme is CSS only: this module moves two attributes and nothing else.
 *   1. <html data-theme="…">  — the readable marker tests and scripts use
 *   2. <link id="themeSkin" href="theme-<id>.css"> — the sheet that repaints
 *
 * The theme list is NOT kept here. `loadTheme` returns it (ThemeConfig.KNOWN in
 * Java is the single registry), so a new skin needs one CSS file and one Java
 * row — no JS edit, no second list that can disagree and offer a chip that can
 * never save.
 *
 * Applying is immediate and saving is immediate: a theme is not form state, so
 * it does not wait for the AI card's Save button. A failed save reverts the
 * repaint and reports the backend's message.
 */
import { themeChips, themeStatus } from "./dom.js";

const DEFAULT_THEME = "starry";

let current = DEFAULT_THEME;
let themes = [];          // [{ id, label }] as reported by the backend
let saving = false;

/** Point the page at one theme. Safe to call before the reply arrives. */
function applyTheme(id) {
    if (!id) return;
    current = id;
    document.documentElement.dataset.theme = id;

    const skin = document.getElementById("themeSkin");
    if (skin) {
        // Compare the authored value, not the resolved URL: assigning the same
        // href again would make the browser refetch the sheet it already has.
        const href = "theme-" + id + ".css";
        if (skin.getAttribute("href") !== href) {
            skin.setAttribute("href", href);
        }
    }
    paintChips();
}

/** Load the stored theme and the theme list. Never throws: a bridge without
 *  the command (an older shell, the preview harness) just keeps the default. */
export async function initTheme() {
    try {
        adopt(await window.krema.invoke("loadTheme", {}));
    } catch (error) {
        console.warn("[theme] loadTheme failed, keeping", current, error);
    }
    return current;
}

/** Settings opened: if the startup load failed, try again, then repaint. */
export async function openThemeCard() {
    if (themes.length === 0) {
        await initTheme();
    }
    paintChips();
}

function adopt(reply) {
    if (!reply || reply.status !== "ok") return;
    if (Array.isArray(reply.themes)) {
        themes = reply.themes;
    }
    applyTheme(reply.theme || DEFAULT_THEME);
}

function paintChips() {
    if (!themeChips) return;
    themeChips.replaceChildren(...themes.map((theme) => {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.className = "provider-chip theme-chip";
        chip.dataset.themeId = theme.id;
        chip.setAttribute("aria-pressed", String(theme.id === current));
        chip.textContent = theme.label;
        return chip;
    }));
}

function setStatus(text, kind) {
    if (!themeStatus) return;
    themeStatus.textContent = text || "";
    themeStatus.classList.toggle("is-error", kind === "error");
    themeStatus.classList.toggle("is-ok", kind === "ok");
}

async function choose(id) {
    if (saving || id === current) return;
    const previous = current;
    applyTheme(id);
    setStatus("");

    saving = true;
    try {
        const reply = await window.krema.invoke("saveTheme", { theme: id });
        if (!reply || reply.status === "error") {
            applyTheme(previous);
            setStatus((reply && reply.error) || "Could not save the theme.", "error");
            return;
        }
        adopt(reply);
        setStatus("Theme saved.", "ok");
    } catch (error) {
        applyTheme(previous);
        setStatus("Could not save the theme: " + (error && error.message ? error.message : error), "error");
    } finally {
        saving = false;
    }
}

if (themeChips) {
    themeChips.addEventListener("click", (event) => {
        const chip = event.target.closest(".theme-chip");
        if (chip) {
            void choose(chip.dataset.themeId);
        }
    });
}
