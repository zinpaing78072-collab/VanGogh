/* Settings view — the AI recommendations form.
 *
 * UI-only in this slice: the five fields mirror what LlmConfig reads today
 * (base URL, model, key, timeout, titles), but nothing is loaded from or
 * written to ~/.van-gogh/llm.conf. The openai-java integration owns both
 * commands; when it lands, the two notices at the bottom become the save and
 * test calls, and the defaults move out of index.html. Nothing here talks to
 * window.krema, so opening the page can never fire a request.
 *
 * render.js decides visibility from state.view; this module only opens and
 * closes the view and wires the controls.
 */
import {
    settingsView, settingsForm, settingsBaseUrl, settingsApiKey, settingsReveal,
    settingsStatus, settingsTest, settingsCancel,
} from "./dom.js";
import { state } from "./state.js";
import { render } from "./render.js";

// A preset only fills in the base URL: model ids rotate too often to pin one
// per provider, and any OpenAI-compatible endpoint is allowed anyway.
const PRESETS = {
    openrouter: "https://openrouter.ai/api/v1",
    openai: "https://api.openai.com/v1",
};

const NOT_WIRED_SAVE =
    "Not saved — the engine still reads ~/.van-gogh/llm.conf. Saving from this page "
    + "arrives with the openai-java integration.";
const NOT_WIRED_TEST =
    "Not wired yet — the openai-java integration adds the connection test.";

export function openSettings() {
    state.view = "settings";
    setStatus("");
    render();
}

export function closeSettings() {
    state.view = "browse";
    render();
}

function setStatus(text) {
    settingsStatus.textContent = text;
}

// Which chip is pressed is derived from the URL field, never from the last
// click, so typing a different endpoint moves the marker to Custom by itself.
function paintProvider() {
    const value = settingsBaseUrl.value.trim().replace(/\/+$/, "");
    const active = Object.keys(PRESETS).find((key) => PRESETS[key] === value) || "custom";
    settingsView.querySelectorAll(".provider-chip").forEach((chip) => {
        chip.setAttribute("aria-pressed", String(chip.dataset.provider === active));
    });
}

settingsView.addEventListener("click", (e) => {
    const chip = e.target.closest(".provider-chip");
    if (!chip) return;
    const preset = PRESETS[chip.dataset.provider];
    if (!preset) {
        // Custom is a state, not a value: put the caret in the field so the
        // next keystroke is what makes the endpoint custom.
        settingsBaseUrl.focus();
        settingsBaseUrl.select();
        return;
    }
    settingsBaseUrl.value = preset;
    paintProvider();
    settingsBaseUrl.focus();
});

settingsBaseUrl.addEventListener("input", paintProvider);

// Reveal is a UI affordance only — the key is never read by this module.
settingsReveal.addEventListener("click", () => {
    const revealed = settingsApiKey.type === "text";
    settingsApiKey.type = revealed ? "password" : "text";
    settingsReveal.setAttribute("aria-pressed", String(!revealed));
    settingsReveal.textContent = revealed ? "Show" : "Hide";
    const label = revealed ? "Show API key" : "Hide API key";
    settingsReveal.setAttribute("aria-label", label);
    settingsReveal.title = label;
});

settingsForm.addEventListener("submit", (e) => {
    e.preventDefault();
    // Wiring point: the openai-java slice validates and writes the file here.
    setStatus(NOT_WIRED_SAVE);
});

settingsTest.addEventListener("click", () => setStatus(NOT_WIRED_TEST));

settingsCancel.addEventListener("click", closeSettings);

// Escape leaves the page, the same way the song detail's back button does.
document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view === "settings") {
        closeSettings();
    }
});

paintProvider();
