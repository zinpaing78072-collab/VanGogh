/* Settings view — the AI recommendations form.
 *
 * Reads and writes ~/.van-gogh/llm.conf: loadLlmSettings on open, saveLlmSettings
 * on Save. The file is the only source of truth — the app consults no
 * environment variables for these values, so what this page shows is what the
 * engine uses.
 *
 * The stored key never crosses the bridge. loadLlmSettings replies with
 * hasApiKey only, so the field starts empty and a blank save keeps the secret
 * (the backend fills it back in). RecommendationService re-reads the config on
 * every AI search, so a key saved here applies without a restart.
 *
 * render.js decides visibility from state.view; this module only opens and
 * closes the view and wires the controls.
 */
import {
    settingsView, settingsForm, settingsBaseUrl, settingsModel, settingsApiKey,
    settingsReveal, settingsTimeout, settingsTitles, settingsStatus, settingsPath,
    settingsSave, settingsCancel,
} from "./dom.js";
import { state } from "./state.js";
import { render } from "./render.js";
import { rememberCurrent } from "./content.js";
import { messageOf } from "./util.js";
import { openThemeCard } from "./theme.js";

// A preset only fills in the base URL: model ids rotate too often to pin one
// per provider, and any OpenAI-compatible endpoint is allowed anyway.
const PRESETS = {
    openrouter: "https://openrouter.ai/api/v1",
    openai: "https://api.openai.com/v1",
};

const PLACEHOLDER_EMPTY = "sk-…";
const PLACEHOLDER_STORED = "Key saved — type a new one to replace it";

// Kept out of the DOM so a page refresh cannot resurrect a secret.
let storedKey = false;
let saving = false;
// Only the newest load may paint: opening Settings, leaving, and reopening can
// overlap two replies, and the stale one must not overwrite the fresh one.
let loadToken = 0;

// The view Settings covered, so Cancel/Escape can put it back (a detail stays
// a detail). Kept here rather than in state: it is a property of this page.
let returnView = "browse";

export function openSettings() {
    // Snapshot the form first: if the user leaves Settings for another form,
    // this one still has a session to come back to. The live state is kept, so
    // a fetch already in flight can finish behind the page.
    rememberCurrent();
    if (state.view !== "settings") {
        returnView = state.view;
    }
    state.view = "settings";
    setStatus("");
    render();
    void load();
    void openThemeCard();
}

export function closeSettings() {
    state.view = returnView || "browse";
    returnView = "browse";
    render();
}

async function load() {
    const token = ++loadToken;
    try {
        const reply = await window.krema.invoke("loadLlmSettings", {});
        if (token !== loadToken) return;
        if (reply && reply.status === "error") {
            setStatus(reply.error || "Could not read the settings.", "error");
            return;
        }
        paint(reply);
    } catch (error) {
        if (token !== loadToken) return;
        setStatus("Could not read the settings: " + messageOf(error), "error");
    }
}

// Paint exactly what the backend reports, including the normalized numbers, so
// the form never shows a value the engine would not use.
function paint(reply) {
    if (!reply) return;

    settingsBaseUrl.value = reply.baseUrl || "";
    settingsModel.value = reply.model || "";
    if (reply.configPath) {
        settingsPath.textContent = reply.configPath;
    }
    settingsTimeout.value = String(reply.timeoutSeconds || "");
    settingsTitles.value = String(reply.titles || "");

    storedKey = reply.hasApiKey === true;
    maskKey();
    settingsApiKey.placeholder = storedKey ? PLACEHOLDER_STORED : PLACEHOLDER_EMPTY;
    paintReveal();
    paintProvider();
}

// The key is gone from the field (never loaded, or just saved), so the field
// goes back to masked and Reveal back to its resting label.
function maskKey() {
    settingsApiKey.value = "";
    settingsApiKey.type = "password";
    settingsReveal.setAttribute("aria-pressed", "false");
    settingsReveal.textContent = "Show";
    settingsReveal.setAttribute("aria-label", "Show API key");
    settingsReveal.title = "Show API key";
}

function setStatus(text, tone) {
    settingsStatus.textContent = text;
    settingsStatus.classList.toggle("is-error", tone === "error");
    settingsStatus.classList.toggle("is-ok", tone === "ok");
}

// Which chip is pressed is derived from the URL field, never from the last
// click, so typing a different endpoint moves the marker to Custom by itself.
function paintProvider() {
    const value = settingsBaseUrl.value.trim().replace(/\/+$/, "");
    const active = Object.keys(PRESETS).find((key) => PRESETS[key] === value) || "custom";
    // Only the endpoint chips: the Appearance card reuses the chip styling for
    // its theme picker, and those chips have no data-provider to compare.
    settingsView.querySelectorAll(".provider-chip[data-provider]").forEach((chip) => {
        chip.setAttribute("aria-pressed", String(chip.dataset.provider === active));
    });
}

// Reveal is pointless with nothing in the field and no stored key.
function paintReveal() {
    const hasTyped = settingsApiKey.value.length > 0;
    settingsReveal.disabled = !hasTyped && !storedKey;
}

function numberIn(input, fallback, min, max) {
    const parsed = Number.parseInt(input.value, 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(max, Math.max(min, parsed));
}

settingsView.addEventListener("click", (e) => {
    const chip = e.target.closest(".provider-chip[data-provider]");
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

settingsReveal.addEventListener("click", () => {
    const revealed = settingsApiKey.type === "text";
    settingsApiKey.type = revealed ? "password" : "text";
    settingsReveal.setAttribute("aria-pressed", String(!revealed));
    settingsReveal.textContent = revealed ? "Show" : "Hide";
    const label = revealed ? "Show API key" : "Hide API key";
    settingsReveal.setAttribute("aria-label", label);
    settingsReveal.title = label;
});

settingsApiKey.addEventListener("input", paintReveal);

settingsForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (saving) return;

    saving = true;
    settingsSave.disabled = true;
    setStatus("Saving…");

    try {
        const reply = await window.krema.invoke("saveLlmSettings", {
            baseUrl: settingsBaseUrl.value.trim(),
            model: settingsModel.value.trim(),
            apiKey: settingsApiKey.value,
            timeoutSeconds: numberIn(settingsTimeout, 60, 10, 300),
            titles: numberIn(settingsTitles, 10, 1, 50),
        });

        if (reply && reply.status === "error") {
            setStatus(reply.error || "Could not save the settings.", "error");
            saving = false;
            settingsSave.disabled = false;
            return;
        }

        // Re-paint from the reply (the stored, normalized values) and drop the
        // key from the field: it is now in the file, not in the page.
        paint(reply);
        setStatus("Saved. The next AI search uses these settings.", "ok");
    } catch (error) {
        setStatus("Could not save the settings: " + messageOf(error), "error");
    }

    saving = false;
    settingsSave.disabled = false;
});

settingsCancel.addEventListener("click", closeSettings);

// Escape leaves the page, the same way the song detail's back button does.
document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view === "settings") {
        closeSettings();
    }
});

paintProvider();
paintReveal();
