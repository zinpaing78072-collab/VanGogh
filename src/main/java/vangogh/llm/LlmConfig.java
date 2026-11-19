package vangogh.llm;

import io.vavr.control.Either;
import io.vavr.control.Try;
import vangogh.error.FetchError;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Provider-neutral LLM settings: any OpenAI-compatible endpoint needs only a
 * base URL, a key and a model id. Nothing here is OpenRouter-specific.
 *
 * <p>Resolution order, highest first: environment
 * ({@code OPENAI_BASE_URL}, {@code OPENAI_API_KEY} / {@code OPENROUTER_API_KEY},
 * {@code LLM_MODEL}, {@code LLM_TIMEOUT_SECONDS}, {@code LLM_TITLES}) then
 * {@code ~/.van-gogh/llm.conf} ({@code key=value} lines, {@code #} comments).
 * The same file a later Settings page would write, so that slice only adds an
 * editor, not a second reader.
 *
 * <p>Missing key is a normal state, not an exception: callers get a
 * {@link Either} {@code Left} and the page shows setup instructions.
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int titles
) {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "baidu/ernie-5.5-flash-preview:free";
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_TITLES = 10;

    public static final Path CONFIG_PATH =
            Path.of(System.getProperty("user.home"), ".van-gogh", "llm.conf");

    /** Never let a key reach a log line: records print every component by default. */
    @Override
    public String toString() {
        return "LlmConfig[baseUrl=" + baseUrl
                + ", model=" + model
                + ", timeoutSeconds=" + timeoutSeconds
                + ", titles=" + titles
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<missing>" : "***") + "]";
    }

    public static Either<FetchError, LlmConfig> load() {
        return load(CONFIG_PATH, System.getenv());
    }

    /** Package-private seam so tests can point at a temp file and a fixed env. */
    static Either<FetchError, LlmConfig> load(Path file, Map<String, String> env) {
        Map<String, String> values = Try.of(() -> readConfig(file)).getOrElse(Map.of());

        String baseUrl = firstNonBlank(
                env.get("OPENAI_BASE_URL"), values.get("baseUrl"), DEFAULT_BASE_URL);
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String apiKey = firstNonBlank(
                env.get("OPENAI_API_KEY"), env.get("OPENROUTER_API_KEY"), values.get("apiKey"));
        String model = firstNonBlank(
                env.get("LLM_MODEL"), values.get("model"), DEFAULT_MODEL);
        int timeoutSeconds = clamp(parseInt(
                firstNonBlank(env.get("LLM_TIMEOUT_SECONDS"), values.get("timeoutSeconds")),
                DEFAULT_TIMEOUT_SECONDS), 10, 300);
        int titles = clamp(parseInt(
                firstNonBlank(env.get("LLM_TITLES"), values.get("titles")),
                DEFAULT_TITLES), 1, 50);

        if (apiKey == null) {
            return Either.left(new FetchError.BadInput("llm",
                    "no API key: set OPENAI_API_KEY or add apiKey to " + file));
        }
        return Either.right(new LlmConfig(baseUrl, apiKey, model, timeoutSeconds, titles));
    }

    private static Map<String, String> readConfig(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> values = new java.util.HashMap<>();
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
}
