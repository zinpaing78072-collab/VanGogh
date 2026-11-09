package vangogh.llm;

/** A single piece of entertainment content recommended by the LLM engine. */
public record Recommendation(
        String kind,    // movie | book | music | game
        String title,
        String reason
) {
}
