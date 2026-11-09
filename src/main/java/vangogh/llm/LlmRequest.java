package vangogh.llm;

import java.util.List;

/** Request body for an OpenAI-compatible {@code /v1/chat/completions} endpoint. */
public record LlmRequest(
        String model,
        List<Message> messages,
        double temperature,
        int maxTokens
) {

    /** A single chat message. */
    public record Message(String role, String content) {
    }
}
