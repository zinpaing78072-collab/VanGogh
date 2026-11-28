package vangogh.llm;

import io.vavr.control.Either;
import io.vavr.control.Try;
import vangogh.error.FetchError;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider-neutral LLM settings: any OpenAI-compatible endpoint needs only a
 * base URL, a key and a model id. Nothing here is OpenRouter-specific.
 *
 * <p>One source of truth: {@code ~/.van-gogh/llm.conf} ({@code key=value}
 * lines, {@code #}/{@code ;} comments). Environment variables are deliberately
 * not consulted — a key typed into the Settings page must be the key the
 * engine uses, and an invisible env override made a saved key look ignored.
 * The Settings page writes this same file through {@link #save(Path)}, and the
 * engine re-reads it on every AI search, so a new key applies without a
 * restart.
 *
 * <p>Missing key is a normal state, not an exception: callers get an
 * {@link Either} {@code Left} and the page shows setup instructions.
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int titles,
        double temperature
) {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "baidu/ernie-5.5-flash-preview:free";
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_TITLES = 10;

    /** OpenAI's documented range; 0.7 is the value the engine shipped with. */
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;

    public static final Path CONFIG_PATH =
            Path.of(System.getProperty("user.home"), ".van-gogh", "llm.conf");

    /** Never let a key reach a log line: records print every component by default. */
    @Override
    public String toString() {
        return "LlmConfig[baseUrl=" + baseUrl
                + ", model=" + model
                + ", timeoutSeconds=" + timeoutSeconds
                + ", titles=" + titles
                + ", temperature=" + temperature
                + ", apiKey=" + (hasApiKey() ? "***" : "<missing>") + "]";
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The settings file's values, defaults filled in; {@code apiKey} may be null. */
    public static LlmConfig read() {
        return read(CONFIG_PATH);
    }

    public static LlmConfig read(Path file) {
        Map<String, String> values = Try.of(() -> readConfig(file)).getOrElse(Map.of());

        return new LlmConfig(
                stripTrailingSlash(firstNonBlank(values.get("baseUrl"), DEFAULT_BASE_URL)),
                firstNonBlank(values.get("apiKey")),
                firstNonBlank(values.get("model"), DEFAULT_MODEL),
                clamp(parseInt(values.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS), 10, 300),
                clamp(parseInt(values.get("titles"), DEFAULT_TITLES), 1, 50),
                clampTemperature(parseDouble(values.get("temperature"), DEFAULT_TEMPERATURE)));
    }

    public static Either<FetchError, LlmConfig> load() {
        return load(CONFIG_PATH);
    }

    /** {@code read} plus the one requirement the engine has: a usable key. */
    public static Either<FetchError, LlmConfig> load(Path file) {
        LlmConfig config = read(file);
        if (!config.hasApiKey()) {
            return Either.left(new FetchError.BadInput("llm",
                    "no API key: add apiKey to " + file + " or save one in Settings"));
        }
        return Either.right(config);
    }

    /**
     * Writes this config to the user's settings file. The normalized values
     * (trailing slash stripped, numbers clamped) are the {@code Right}, so the
     * page can paint exactly what was stored.
     */
    public Either<FetchError, LlmConfig> save() {
        return save(CONFIG_PATH);
    }

    public Either<FetchError, LlmConfig> save(Path file) {
        LlmConfig next = normalized();
        if (!next.hasApiKey()) {
            return Either.left(new FetchError.BadInput("apiKey", "required"));
        }
        if (next.baseUrl() == null || next.baseUrl().isBlank()) {
            return Either.left(new FetchError.BadInput("baseUrl", "required"));
        }
        if (next.model() == null || next.model().isBlank()) {
            return Either.left(new FetchError.BadInput("model", "required"));
        }
        return write(next, file).map(ignored -> next);
    }

    private LlmConfig normalized() {
        return new LlmConfig(
                stripTrailingSlash(baseUrl),
                apiKey == null ? null : apiKey.trim(),
                model == null ? null : model.trim(),
                clamp(timeoutSeconds, 10, 300),
                clamp(titles, 1, 50),
                clampTemperature(temperature));
    }

    /**
     * Atomic replace in the same directory, so a crash mid-write cannot leave a
     * half-written config behind. Owner-only permissions on POSIX: the file
     * holds a secret.
     */
    private static Either<FetchError, Path> write(LlmConfig config, Path file) {
        return Try.of(() -> {
            Path target = file.toAbsolutePath();
            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }

            Path temp = Files.createTempFile(dir, "llm.conf.", ".tmp");
            Files.writeString(temp, render(config), StandardCharsets.UTF_8);
            restrictToOwner(temp);
            move(temp, target);
            restrictToOwner(target);
            return target;
        }).toEither().mapLeft(error -> (FetchError) new FetchError.Transport("llm.conf", error));
    }

    private static String render(LlmConfig config) {
        return """
                # VanGogh LLM settings — written by the Settings page.
                # This file is the only source of truth; the app does not read
                # environment variables for these values.
                apiKey = %s
                baseUrl = %s
                model = %s
                timeoutSeconds = %d
                titles = %d
                temperature = %s
                """.formatted(
                config.apiKey(),
                config.baseUrl(),
                config.model(),
                config.timeoutSeconds(),
                config.titles(),
                config.temperature());
    }

    private static void move(Path from, Path to) throws java.io.IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictToOwner(Path file) {
        Try.run(() -> {
            if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
        });
    }

    private static Map<String, String> readConfig(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String stripped = line.strip();
            // Windows editors (Notepad) like to prepend a UTF-8 BOM. Left in
            // place it becomes part of the first key name and the config
            // silently reads as empty.
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
            String key = stripped.substring(0, equals).strip();
            String value = stripped.substring(equals + 1).strip();
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Temperature is a decimal in a fixed range. A malformed value falls back to
     * the default, and NaN/Infinity are treated as malformed: {@code Math.min}
     * and {@code Math.max} propagate NaN, so a bare clamp would store it.
     */
    private static double clampTemperature(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_TEMPERATURE;
        }
        return Math.max(MIN_TEMPERATURE, Math.min(MAX_TEMPERATURE, value));
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
