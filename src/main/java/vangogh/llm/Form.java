package vangogh.llm;

/**
 * The four content forms the recommendation prompt knows about. One row per
 * form: the noun the model is asked for and what the pipe-separated
 * disambiguator means to that form's resolver.
 *
 * <p>Music asks for {@code song}, not album: the music form resolves against
 * Genius, which searches songs. Changing a noun invalidates the probe's
 * measured gates — see docs/superpowers/specs/2026-11-19-llm-recommendation-engine-design.md.
 */
public enum Form {

    MOVIE("movie", "release year"),
    MUSIC("song", "artist"),
    BOOK("book", "author"),
    GAME("video game", "release year");

    private final String promptNoun;
    private final String disambiguatorLabel;

    Form(String promptNoun, String disambiguatorLabel) {
        this.promptNoun = promptNoun;
        this.disambiguatorLabel = disambiguatorLabel;
    }

    /** Noun inserted into the per-form prompt, e.g. "List 10 song titles related to: …". */
    public String promptNoun() {
        return promptNoun;
    }

    /** What the second pipe-separated field means for this form. */
    public String disambiguatorLabel() {
        return disambiguatorLabel;
    }
}
