package vangogh.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates cross-media entertainment recommendations from a user query using the LLM engine.
 *
 * <p>Gracefully degrades to an empty list when the LLM is not configured, unreachable, or returns
 * unparseable output, so the UI never breaks because of the AI layer.</p>
 */
public class LlmService {

    private final LlmClient client;

    public LlmService() {
        this(new LlmClient(LlmConfig.fromEnv()));
    }

    /** Package-private seam for tests: inject a stubbed {@link LlmClient}. */
    LlmService(LlmClient client) {
        this.client = client;
    }

    /**
     * Generates recommendations related to {@code query} across movies, books, music, and games.
     * Returns an empty list when the query is blank or the LLM is unavailable.
     */
    public List<Recommendation> recommend(String query) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        String content = client.complete(query);
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return parseRecommendations(content);
    }

    private List<Recommendation> parseRecommendations(String content) {
        List<Recommendation> recommendations = new ArrayList<>();
        try {
            String json = stripFences(content);
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonArray()) {
                return List.of();
            }
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String kind = optString(obj, "kind");
                String title = optString(obj, "title");
                String reason = optString(obj, "reason");
                if (title.isEmpty()) {
                    continue;
                }
                recommendations.add(new Recommendation(kind, title, reason));
            }
        } catch (Exception e) {
            System.err.println("LLM parse error: " + e.getMessage());
        }
        return recommendations;
    }

    /** Removes markdown code fences (```json ... ```) the LLM may have wrapped around the array. */
    private static String stripFences(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
