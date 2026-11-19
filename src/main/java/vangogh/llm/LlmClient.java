package vangogh.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vavr.control.Try;
import vangogh.net.ApiResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal OpenAI-compatible chat-completions client, ported from the
 * connection-tester probe. Deliberately no JSON mode / structured outputs:
 * many compatible servers lack them, and the contract is plain text
 * ({@code title | disambiguator} per line) anyway.
 *
 * <p>Transport failures are {@code Try.failure}; HTTP statuses are values
 * ({@link ApiResponse}) so the caller can classify expected failures (401, 429)
 * without exceptions. Free providers throttle hard, so 429/5xx are retried with
 * backoff instead of failing the request.
 *
 * <p>{@code InterruptedException} is handled here, not thrown: vavr's
 * {@code Try} treats it as fatal and rethrows it sneakily, which would escape
 * the value-based pipeline. The interrupt flag is restored and a normal
 * failure is returned.
 */
public class LlmClient {

    /** Free providers answer 429 on the second call; back off instead of failing. */
    private static final long[] BACKOFF_MS = {3000, 8000, 20000, 45000};

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final long[] backoffMs;

    public LlmClient() {
        this(BACKOFF_MS);
    }

    /** Package-private seam: tests retry with no real waiting. */
    LlmClient(long... backoffMs) {
        this.backoffMs = backoffMs;
    }

    public Try<ApiResponse> complete(LlmConfig config, String system, String user) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(config, system, user)))
                    .build();
        } catch (RuntimeException e) {
            return Try.failure(e);
        }

        try {
            for (int attempt = 0; ; attempt++) {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                boolean retryable = status == 429 || status >= 500;
                if (retryable && attempt < backoffMs.length) {
                    Thread.sleep(Math.max(backoffMs[attempt], retryAfterMs(response)));
                    continue;
                }
                return Try.success(new ApiResponse(status, response.body(), null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(new IllegalStateException("LLM call interrupted", e));
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * The assistant text of a chat-completions body. Public so the
     * recommendation service can classify a bad shape as {@code Payload}
     * instead of a transport error.
     */
    public static String contentOf(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                ? root.getAsJsonArray("choices")
                : new JsonArray();
        if (choices.size() == 0) {
            throw new IllegalStateException("LLM reply has no choices: " + truncate(body));
        }
        JsonObject message = choices.get(0).isJsonObject()
                ? choices.get(0).getAsJsonObject().getAsJsonObject("message")
                : null;
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new IllegalStateException("LLM reply has no content: " + truncate(body));
        }
        return message.get("content").getAsString();
    }

    private static String body(LlmConfig config, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("temperature", 0.7);

        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static long retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 400 ? value.substring(0, 400) + "…" : value;
    }
}
