package vangogh.controller;

import build.krema.core.KremaCommand;
import vangogh.error.FetchError;
import vangogh.theme.ThemeConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * The Appearance card's backend: report the stored theme (plus the theme list the
 * page renders chips from), and write a new one.
 *
 * <p>Synchronous and tiny (one small file), so it runs on the UI thread like
 * every other command that must reply to the page — the Windows Krema constraint
 * (engine.eval is dropped off the UI thread) rules out a kickoff/drain dance
 * here, and file IO needs none of it.
 *
 * <p>{@code themes} travels with every reply so the page never carries its own
 * copy of the registry: adding a theme is one row in {@link ThemeConfig#KNOWN}
 * and one CSS file.
 */
public class ThemeController {

    private final Path file;

    public ThemeController() {
        this(ThemeConfig.CONFIG_PATH);
    }

    /** Test seam: point at a temp file instead of {@code ~/.van-gogh/theme.conf}. */
    ThemeController(Path file) {
        this.file = file;
    }

    /** What the page paints. {@code theme} is always a known id. */
    public record ThemeReply(
            String status, String error,
            String theme, List<ThemeConfig.Choice> themes) {}

    @KremaCommand
    public ThemeReply loadTheme() {
        return ok(ThemeConfig.read(file));
    }

    /** Saves one id. An unknown id is a caller bug the page shows as an error. */
    @KremaCommand
    public ThemeReply saveTheme(String theme) {
        return ThemeConfig.of(theme).save(file).fold(this::failed, this::ok);
    }

    private ThemeReply ok(ThemeConfig config) {
        return new ThemeReply("ok", null, config.id(), ThemeConfig.known());
    }

    private ThemeReply failed(FetchError error) {
        return new ThemeReply("error", FetchError.uiMessage(error),
                ThemeConfig.read(file).id(), ThemeConfig.known());
    }
}
