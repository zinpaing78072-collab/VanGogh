package vangogh.llm;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client for OpenAI-compatible chat completions APIs.
 *
 * <p>{@link #complete(String)} makes a blocking POST to {@code {baseUrl}/chat/completions}
 * and returns the assistant's message content. It is intended to be invoked off the UI thread
 * (e.g., from a Krema IO pool thread). When the engine is not configured ({@link LlmConfig#isConfigured()}
 * is false) it returns {@code null} without touching the network so the app degrades gracefully.
 */
public class LlmClient {

    private final LlmConfig config;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public LlmClient(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    /**
     * Sends {@code userPrompt} to the LLM and returns the assistant's message content, or
     * {@code null} when the LLM is unconfigured or the request fails.
     */
    public String complete(String userPrompt) {
        if (!config.isConfigured()) {
            return null;
        }
        try {
            LlmRequest request = new LlmRequest(
                    config.model(),
                    List.of(
                            new LlmRequest.Message("system", config.systemPrompt()),
                            new LlmRequest.Message("user", userPrompt)
                    ),
                    0.7,
                    500
            );
            String body = gson.toJson(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("LLM API error: HTTP " + response.statusCode() + " " + truncate(response.body(), 200));
                return null;
            }
            LlmResponse llmResponse = gson.fromJson(response.body(), LlmResponse.class);
            return llmResponse != null ? llmResponse.firstContent() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("LLM request failed: " + e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
