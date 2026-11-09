package vangogh.llm;

import java.util.List;

/** Response body from an OpenAI-compatible {@code /v1/chat/completions} endpoint. */
public record LlmResponse(
        List<Choice> choices
) {

    /** A single choice returned by the chat completion. */
    public record Choice(Message message) {
    }

    /** A message within a choice. */
    public record Message(String content) {
    }

    /** The content of the first choice, or {@code null} when there are no choices. */
    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).message().content();
    }
}
