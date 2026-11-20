package vangogh.theme;

import io.vavr.control.Either;
import io.vavr.control.Try;
import vangogh.error.FetchError;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The user's chosen skin, stored as {@code ~/.van-gogh/theme.conf} — the same
 * single-source-of-truth convention as {@code llm.conf} and
 * {@code poster-proxy.conf}.
 *
 * <p><strong>This class is the theme registry.</strong> A theme id is known only
 * if it is in {@link #KNOWN}; the Settings page renders its chips from that list
 * (through the {@code loadTheme} reply), so a theme can never appear as a chip
 * that cannot be saved. Adding a theme is one row here plus one
 * {@code theme-<id>.css} file — nothing in the page's JS changes.
 *
 * <p>An unknown or missing id on disk reads as {@link #DEFAULT_THEME} instead of
 * failing: a hand-edited or half-written file should not lock the user out of
 * the app. A save with an unknown id is rejected, because that is a caller bug
 * rather than user input.
 */
public record ThemeConfig(String id) {

    /** One theme the app can paint: the CSS file is {@code theme-<id>.css}. */
    public record Choice(String id, String label) {}

    public static final String DEFAULT_THEME = "starry";

    public static final List<Choice> KNOWN = List.of(
            new Choice("starry", "Starry Night"),
            new Choice("liquidneon", "Liquid Neon"));

    public static final Path CONFIG_PATH =
            Path.of(System.getProperty("user.home"), ".van-gogh", "theme.conf");

    public static List<Choice> known() {
        return KNOWN;
    }

    public static boolean isKnown(String id) {
        return KNOWN.stream().anyMatch(choice -> choice.id().equals(id));
    }

    /** The stored theme, defaulted and validated against {@link #KNOWN}. */
    public static ThemeConfig read() {
        return read(CONFIG_PATH);
    }

    public static ThemeConfig read(Path file) {
        String stored = Try.of(() -> readValue(file)).getOrElse((String) null);
        String id = stored == null ? null : stored.strip();
        return new ThemeConfig(isKnown(id) ? id : DEFAULT_THEME);
    }

    public static ThemeConfig of(String id) {
        return new ThemeConfig(id == null ? null : id.strip());
    }

    /**
     * Writes this theme to the user's file. Relies on {@link #isKnown(String)},
     * so a typo is an error the page can show rather than a silently ignored
     * save.
     */
    public Either<FetchError, ThemeConfig> save() {
        return save(CONFIG_PATH);
    }

    public Either<FetchError, ThemeConfig> save(Path file) {
        if (!isKnown(id)) {
            return Either.left(new FetchError.BadInput("theme", "unknown theme: " + id));
        }
        return write(this, file).map(ignored -> this);
    }

    /**
     * Atomic replace in the same directory, so a crash mid-write cannot leave a
     * half-written preference behind. No owner-only permissions: unlike
     * {@code llm.conf} this file holds no secret.
     */
    private static Either<FetchError, Path> write(ThemeConfig config, Path file) {
        return Try.of(() -> {
            Path target = file.toAbsolutePath();
            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path temp = Files.createTempFile(dir, "theme.conf.", ".tmp");
            Files.writeString(temp, render(config), StandardCharsets.UTF_8);
            move(temp, target);
            return target;
        }).toEither().mapLeft(error -> (FetchError) new FetchError.Transport("theme.conf", error));
    }

    private static String render(ThemeConfig config) {
        return """
                # VanGogh theme — written by the Settings page.
                # id must be one of: %s
                theme = %s
                """.formatted(
                KNOWN.stream().map(Choice::id).reduce((a, b) -> a + ", " + b).orElse(DEFAULT_THEME),
                config.id());
    }

    private static void move(Path from, Path to) throws java.io.IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code key = value} lines, {@code #}/{@code ;} comments — same shape as
     *  the other .conf files, including the Notepad BOM guard. */
    private static String readValue(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && stripped.charAt(0) == '\uFEFF') {
                stripped = stripped.substring(1).strip();
            }
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith(";")) {
                continue;
            }
            int equals = stripped.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if ("theme".equals(stripped.substring(0, equals).strip())) {
                String value = stripped.substring(equals + 1).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
