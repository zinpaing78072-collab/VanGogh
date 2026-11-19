package vangogh.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The comparison key every resolver shares: if two titles differ only by case,
 * punctuation, typography, accents or a leading article, they are the same work.
 */
class NormalizeTest {

    @Test
    void caseAndLeadingArticleDoNotMatter() {
        assertEquals(Normalize.key("dark knight"), Normalize.key("The Dark Knight"));
        assertEquals("dark knight", Normalize.key("The Dark Knight"));
    }

    @Test
    void accentsAndTypographyAreFolded() {
        assertEquals("amelie", Normalize.key("Amélie"));
        // A typographic apostrophe becomes a separator, exactly as in the probe:
        // both spellings produce the same key, which is what matching needs.
        assertEquals(Normalize.key("Don t Look Up"), Normalize.key("Don’t Look Up"));
        assertEquals("don t look up", Normalize.key("Don’t Look Up"));
        assertEquals("spider man no way home", Normalize.key("Spider-Man: No Way Home"));
        assertEquals("fast and furious", Normalize.key("Fast & Furious"));
    }

    @Test
    void extraWhitespaceAndPunctuationCollapse() {
        assertEquals("blade runner 2049", Normalize.key("  Blade   Runner 2049!! "));
    }

    @Test
    void nullIsTheEmptyKey() {
        assertEquals("", Normalize.key(null));
        assertEquals("", Normalize.key("!!!"));
    }
}
