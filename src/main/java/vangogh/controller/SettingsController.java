package vangogh.controller;

import build.krema.core.KremaCommand;
import vangogh.error.FetchError;
import vangogh.llm.LlmConfig;

import java.nio.file.Path;

/**
 * The Settings page's backend: hand the form the current LLM settings, and
 * write them back.
 *
 * <p>Synchronous and tiny (one small file), so it runs on the UI thread like
 * every other command that must reply to the page — the Windows Krema
 * constraint (engine.eval is dropped off the UI thread) rules out the
 * kickoff/drain dance here, and file IO needs none of it.
 *
 * <p>The stored key never crosses the bridge: the load reply only says whether
 * one is present, and a save with a blank key keeps the stored one.
 */
public class SettingsController {

    private final Path file;

    public SettingsController() {
        this(LlmConfig.CONFIG_PATH);
    }

    /** Test seam: point at a temp file instead of {@code ~/.van-gogh/llm.conf}. */
    SettingsController(Path file) {
        this.file = file;
    }

    /** What the page paints. Deliberately has no apiKey field. */
    public record LlmSettingsReply(
            String status, String error,
            String baseUrl, String model, int timeoutSeconds, int titles,
            boolean hasApiKey, String configPath) {}

    @KremaCommand
    public LlmSettingsReply loadLlmSettings() {
        return ok(LlmConfig.read(file));
    }

    /**
     * Writes the settings file. A blank {@code apiKey} means "keep the stored
     * key" — the page cannot read it, so it must be able to save the other
     * fields without retyping the secret.
     */
    @KremaCommand
    public LlmSettingsReply saveLlmSettings(String baseUrl, String model, String apiKey,
                                            int timeoutSeconds, int titles) {
        String key = apiKey == null || apiKey.isBlank()
                ? LlmConfig.read(file).apiKey()
                : apiKey.trim();
        if (key == null || key.isBlank()) {
            return failed(new FetchError.BadInput("apiKey", "required — enter a key"));
        }

        LlmConfig next = new LlmConfig(baseUrl, key, model, timeoutSeconds, titles);
        return next.save(file).fold(this::failed, this::ok);
    }

    private LlmSettingsReply ok(LlmConfig config) {
        return new LlmSettingsReply("ok", null,
                config.baseUrl(), config.model(),
                config.timeoutSeconds(), config.titles(),
                config.hasApiKey(), file.toString());
    }

    private LlmSettingsReply failed(FetchError error) {
        return new LlmSettingsReply("error", FetchError.uiMessage(error),
                null, null, 0, 0, false, file.toString());
    }
}
