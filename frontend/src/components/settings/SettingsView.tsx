import { useEffect, useRef, useState } from "react";
import { invoke } from "../../bridge/bridge";
import type { LlmSettingsReply } from "../../bridge/types";
import { closeSettings } from "../../app/actions";
import { useStore } from "../../app/store";
import { cn } from "../../lib/cn";
import { messageOf } from "../../lib/format";
import { chooseTheme, openThemeCard, useTheme } from "../../theme/theme";

/**
 * Settings: Appearance (theme chips from the Java registry) and the AI
 * recommendations form backed by ~/.van-gogh/llm.conf.
 *
 * The inputs are deliberately uncontrolled (refs + defaultValue). The preview
 * harness drives them the way a user's browser does — assign `.value`, dispatch
 * `input`, click Save in the same task — and a controlled field would hand the
 * submit handler the previous render's value. Reading the live DOM at submit
 * also matches the vanilla form, which never held the key in page state.
 *
 * The stored key never crosses the bridge: the load reply only says whether one
 * exists, and a blank save keeps it.
 */

const PRESETS: Record<string, string> = {
  openrouter: "https://openrouter.ai/api/v1",
  openai: "https://api.openai.com/v1",
};

const PLACEHOLDER_EMPTY = "sk-…";
const PLACEHOLDER_STORED = "Key saved — type a new one to replace it";

function providerOf(value: string): string {
  const normalized = value.trim().replace(/\/+$/, "");
  return Object.keys(PRESETS).find((key) => PRESETS[key] === normalized) || "custom";
}

function clampNumber(value: string, fallback: number, min: number, max: number): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

interface Tone {
  text: string;
  tone: "" | "ok" | "error";
}

function AppearanceCard() {
  const { themes, theme, status, tone } = useTheme();

  return (
    <section className="settings-card" id="themeCard" aria-labelledby="themeCardTitle">
      <div className="settings-card-head">
        <div>
          <h2 className="settings-section-title" id="themeCardTitle">
            Appearance
          </h2>
          <p className="settings-section-sub">
            One skin for the whole app — grid, song detail and this page.
          </p>
        </div>
      </div>

      <div className="settings-field">
        <span className="settings-label" id="themeLabel">
          Theme
        </span>
        <div
          className="provider-chips"
          id="themeChips"
          role="group"
          aria-labelledby="themeLabel"
        >
          {themes.map((choice) => (
            <button
              key={choice.id}
              type="button"
              className="provider-chip theme-chip"
              data-theme-id={choice.id}
              aria-pressed={choice.id === theme}
              onClick={() => void chooseTheme(choice.id)}
            >
              {choice.label}
            </button>
          ))}
        </div>
        <p className="settings-hint">
          Saved as soon as you pick one, and remembered on the next launch.
        </p>
      </div>

      <p
        className={cn(
          "settings-status",
          tone === "error" && "is-error",
          tone === "ok" && "is-ok"
        )}
        id="themeStatus"
        role="status"
        aria-live="polite"
      >
        {status}
      </p>
    </section>
  );
}

function LlmForm() {
  const view = useStore((state) => state.view);
  const baseUrlRef = useRef<HTMLInputElement>(null);
  const modelRef = useRef<HTMLInputElement>(null);
  const apiKeyRef = useRef<HTMLInputElement>(null);
  const timeoutRef = useRef<HTMLInputElement>(null);
  const titlesRef = useRef<HTMLInputElement>(null);

  const [provider, setProvider] = useState("openrouter");
  const [hasStoredKey, setHasStoredKey] = useState(false);
  const storedKeyRef = useRef(false);
  const [revealed, setRevealed] = useState(false);
  const [canReveal, setCanReveal] = useState(false);
  const [configPath, setConfigPath] = useState("~/.van-gogh/llm.conf");
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<Tone>({ text: "", tone: "" });
  const loadToken = useRef(0);

  const paint = (reply: LlmSettingsReply | null | undefined) => {
    if (!reply) return;
    if (baseUrlRef.current) baseUrlRef.current.value = reply.baseUrl || "";
    if (modelRef.current) modelRef.current.value = reply.model || "";
    if (timeoutRef.current) timeoutRef.current.value = String(reply.timeoutSeconds || "");
    if (titlesRef.current) titlesRef.current.value = String(reply.titles || "");
    if (reply.configPath) setConfigPath(reply.configPath);

    setHasStoredKey(reply.hasApiKey === true);
    storedKeyRef.current = reply.hasApiKey === true;
    setRevealed(false);
    if (apiKeyRef.current) {
      apiKeyRef.current.value = "";
      apiKeyRef.current.placeholder =
        reply.hasApiKey === true ? PLACEHOLDER_STORED : PLACEHOLDER_EMPTY;
    }
    setCanReveal(reply.hasApiKey === true);
    setProvider(providerOf(reply.baseUrl || ""));
  };

  useEffect(() => {
    if (view !== "settings") return;
    const token = ++loadToken.current;
    void openThemeCard();

    void (async () => {
      setStatus({ text: "", tone: "" });
      try {
        const reply = await invoke("loadLlmSettings", {});
        if (token !== loadToken.current) return;
        if (reply && reply.status === "error") {
          setStatus({ text: reply.error || "Could not read the settings.", tone: "error" });
          return;
        }
        paint(reply);
      } catch (error) {
        if (token !== loadToken.current) return;
        setStatus({
          text: "Could not read the settings: " + messageOf(error),
          tone: "error",
        });
      }
    })();
    // paint is stable enough: it only writes refs and setters.
  }, [view]);

  // Native listeners, not React's synthetic onInput: the harness assigns
  // `.value` and dispatches a bare `input` event, and these derived flags must
  // follow the live DOM rather than a render that has not happened yet.
  useEffect(() => {
    const baseUrl = baseUrlRef.current;
    const apiKey = apiKeyRef.current;
    if (!baseUrl || !apiKey) return;

    const onBaseUrl = () => setProvider(providerOf(baseUrl.value));
    const onKey = () =>
      setCanReveal(apiKey.value.length > 0 || storedKeyRef.current);

    baseUrl.addEventListener("input", onBaseUrl);
    apiKey.addEventListener("input", onKey);
    return () => {
      baseUrl.removeEventListener("input", onBaseUrl);
      apiKey.removeEventListener("input", onKey);
    };
  }, []);

  const chooseProvider = (key: string) => {
    const preset = PRESETS[key];
    if (!preset) {
      // Custom is a state, not a value: focus the field so the next keystroke
      // is what makes the endpoint custom.
      baseUrlRef.current?.focus();
      baseUrlRef.current?.select();
      return;
    }
    if (baseUrlRef.current) baseUrlRef.current.value = preset;
    setProvider(key);
    baseUrlRef.current?.focus();
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (saving) return;

    setSaving(true);
    setStatus({ text: "Saving…", tone: "" });

    try {
      const reply = await invoke("saveLlmSettings", {
        baseUrl: (baseUrlRef.current?.value ?? "").trim(),
        model: (modelRef.current?.value ?? "").trim(),
        apiKey: apiKeyRef.current?.value ?? "",
        timeoutSeconds: clampNumber(timeoutRef.current?.value ?? "", 60, 10, 300),
        titles: clampNumber(titlesRef.current?.value ?? "", 10, 1, 50),
      });

      if (reply && reply.status === "error") {
        setStatus({ text: reply.error || "Could not save the settings.", tone: "error" });
        return;
      }

      paint(reply);
      setStatus({
        text: "Saved. The next AI search uses these settings.",
        tone: "ok",
      });
    } catch (error) {
      setStatus({
        text: "Could not save the settings: " + messageOf(error),
        tone: "error",
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="settings-card" id="settingsForm" noValidate onSubmit={submit}>
      <div className="settings-card-head">
        <div>
          <h2 className="settings-section-title">AI recommendations</h2>
          <p className="settings-section-sub">
            The model composes the picks; the content sources resolve them into cards.
          </p>
        </div>
      </div>

      <div className="settings-field">
        <span className="settings-label" id="settingsProviderLabel">
          Provider
        </span>
        <div
          className="provider-chips"
          role="group"
          aria-labelledby="settingsProviderLabel"
        >
          {(["openrouter", "openai", "custom"] as const).map((key) => (
            <button
              key={key}
              type="button"
              className="provider-chip"
              data-provider={key}
              aria-pressed={provider === key}
              onClick={() => chooseProvider(key)}
            >
              {key === "openrouter" ? "OpenRouter" : key === "openai" ? "OpenAI" : "Custom"}
            </button>
          ))}
        </div>
        <p className="settings-hint">
          Any OpenAI-compatible endpoint works — a preset only fills in the base URL.
        </p>
      </div>

      <div className="settings-field">
        <label className="settings-label" htmlFor="settingsBaseUrl">
          Base URL
        </label>
        <input
          className="settings-input mono"
          id="settingsBaseUrl"
          name="baseUrl"
          type="url"
          ref={baseUrlRef}
          defaultValue="https://openrouter.ai/api/v1"
          spellCheck={false}
          autoComplete="off"
        />
      </div>

      <div className="settings-field">
        <label className="settings-label" htmlFor="settingsModel">
          Model
        </label>
        <input
          className="settings-input mono"
          id="settingsModel"
          name="model"
          type="text"
          ref={modelRef}
          defaultValue="baidu/ernie-5.5-flash-preview:free"
          spellCheck={false}
          autoComplete="off"
        />
        <p className="settings-hint">
          The provider's model id. Free tiers rotate often, so a stale id fails on the
          first AI search.
        </p>
      </div>

      <div className="settings-field">
        <label className="settings-label" htmlFor="settingsApiKey">
          API key
        </label>
        <div className="settings-secret">
          <input
            className="settings-input mono"
            id="settingsApiKey"
            name="apiKey"
            type={revealed ? "text" : "password"}
            ref={apiKeyRef}
            placeholder={hasStoredKey ? PLACEHOLDER_STORED : PLACEHOLDER_EMPTY}
            autoComplete="off"
            spellCheck={false}
            aria-describedby="settingsKeyHint"
          />
          <button
            type="button"
            className="settings-reveal"
            id="settingsReveal"
            aria-pressed={revealed}
            aria-label={revealed ? "Hide API key" : "Show API key"}
            title={revealed ? "Hide API key" : "Show API key"}
            disabled={!canReveal}
            onClick={() => setRevealed((current) => !current)}
          >
            {revealed ? "Hide" : "Show"}
          </button>
        </div>
        <p className="settings-hint" id="settingsKeyHint">
          Stored in <code id="settingsPath">{configPath}</code>, never in the repository.
          Leave blank to keep the saved key.
        </p>
      </div>

      <div className="settings-row">
        <div className="settings-field">
          <label className="settings-label" htmlFor="settingsTimeout">
            Timeout (seconds)
          </label>
          <input
            className="settings-input"
            id="settingsTimeout"
            name="timeoutSeconds"
            type="number"
            min="10"
            max="300"
            step="5"
            ref={timeoutRef}
            defaultValue="60"
          />
          <p className="settings-hint">10–300. Free tiers can take 20 s.</p>
        </div>
        <div className="settings-field">
          <label className="settings-label" htmlFor="settingsTitles">
            Titles per search
          </label>
          <input
            className="settings-input"
            id="settingsTitles"
            name="titles"
            type="number"
            min="1"
            max="50"
            step="1"
            ref={titlesRef}
            defaultValue="10"
          />
          <p className="settings-hint">1–50 picks, one catalog lookup each.</p>
        </div>
      </div>

      <div className="settings-note">
        <h3 className="settings-note-title">One source of truth</h3>
        <p>
          Saved here, read on the next AI search — no restart, and no environment
          variable quietly overriding it.
        </p>
      </div>

      <p
        className={cn(
          "settings-status",
          status.tone === "error" && "is-error",
          status.tone === "ok" && "is-ok"
        )}
        id="settingsStatus"
        role="status"
        aria-live="polite"
      >
        {status.text}
      </p>

      <div className="settings-actions">
        <div className="settings-buttons">
          <button
            type="button"
            className="settings-btn"
            id="settingsCancel"
            onClick={closeSettings}
          >
            Cancel
          </button>
          <button
            type="submit"
            className="settings-btn primary"
            id="settingsSave"
            disabled={saving}
          >
            Save
          </button>
        </div>
      </div>
    </form>
  );
}

export function SettingsView({ hidden }: { hidden: boolean }) {
  return (
    <section
      className={cn("settings-view", hidden && "hidden")}
      id="settingsView"
      aria-labelledby="settingsTitle"
    >
      <div className="settings-inner">
        <header className="settings-head">
          <h1 className="settings-title" id="settingsTitle">
            Settings
          </h1>
          <p className="settings-subtitle">
            How Van Gogh looks and how it turns a search into recommendations.
          </p>
        </header>

        <AppearanceCard />
        <LlmForm />
      </div>
    </section>
  );
}
