package vangogh.llm;

/**
 * Configuration for the OpenAI-compatible LLM engine, read from environment variables.
 *
 * <p>Recognized variables:</p>
 * <ul>
 *   <li>{@code LLM_API_KEY} — bearer token for the LLM API (required)</li>
 *   <li>{@code LLM_BASE_URL} — base URL of the OpenAI-compatible API (default {@code https://api.openai.com/v1})</li>
 *   <li>{@code LLM_MODEL} — chat model name (default {@code gpt-3.5-turbo})</li>
 *   <li>{@code LLM_SYSTEM_PROMPT} — system prompt (default built-in recommendation prompt)</li>
 *   <li>{@code LLM_TIMEOUT_SECONDS} — HTTP timeout (default 30)</li>
 * </ul>
 *
 * <p>When {@code LLM_API_KEY} is unset the engine is considered unconfigured and degrades
 * gracefully to empty results instead of failing.</p>
 */
public record LlmConfig(
        String apiKey,
        String baseUrl,
        String model,
        String systemPrompt,
        int timeoutSeconds
) {

    public static LlmConfig fromEnv() {
        String apiKey = System.getenv("LLM_API_KEY");

        String baseUrl = System.getenv("LLM_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.replaceAll("/+$", ""); // strip trailing slashes

        String model = System.getenv("LLM_MODEL");
        if (model == null || model.isEmpty()) {
            model = "gpt-3.5-turbo";
        }

        String systemPrompt = System.getenv("LLM_SYSTEM_PROMPT");
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }

        int timeoutSeconds = 30;
        String timeout = System.getenv("LLM_TIMEOUT_SECONDS");
        if (timeout != null && !timeout.isEmpty()) {
            try {
                timeoutSeconds = Integer.parseInt(timeout.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        return new LlmConfig(apiKey, baseUrl, model, systemPrompt, timeoutSeconds);
    }

    /** {@code true} when an API key is present; the client will short-circuit otherwise. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }

    private static final String DEFAULT_SYSTEM_PROMPT = """
        You are an entertainment recommendation engine. Given a user query about a movie, book,
        music artist, or game, return related entertainment content across movies, books, music,
        and games. Respond with a JSON array of objects, each with exactly these fields:
        "kind" (one of: movie, book, music, game), "title" (the content title), and "reason"
        (a one-sentence reason why it relates to the query). Return only the JSON array, no
        markdown fences, no extra text. Example:
        [{"kind":"movie","title":"Blade Runner 2049","reason":"A visually stunning sci-fi sequel."}]
        """;
}
