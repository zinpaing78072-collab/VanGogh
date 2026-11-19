package vangogh.llm;

/**
 * The fixed prompt contract the probe measured. Kept in one place on purpose:
 * parser and resolvers were tuned against exactly this text, so rewording it
 * invalidates the measured resolution rates.
 *
 * <p>Format rules live in the system message; the query always travels in the
 * user message, verbatim, whatever shape it has (a title, a vibe, broken
 * grammar). No JSON mode, no few-shot examples.
 */
public final class PromptFactory {

    public static final String SYSTEM = "You recommend entertainment titles. "
            + "Reply with one item per line, two fields separated by a pipe character: "
            + "title | disambiguator. "
            + "No numbering, no quotes, no explanations, no commentary.";

    private PromptFactory() {
    }

    public static String user(Form form, String query, int titles) {
        return "List " + titles + " " + form.promptNoun() + " titles related to: " + query
                + "\nFormat each line as: title | " + form.disambiguatorLabel();
    }
}
