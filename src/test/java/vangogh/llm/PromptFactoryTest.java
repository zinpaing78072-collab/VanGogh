package vangogh.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prompt is a measured contract, not copy: the parser and every resolver
 * were tuned against exactly this text. These tests pin the parts that matter
 * (line format, count, per-form noun) so a "harmless" rewording fails here
 * instead of silently dropping the resolution rate.
 */
class PromptFactoryTest {

    @Test
    void systemPromptPinsTheLineFormat() {
        assertTrue(PromptFactory.SYSTEM.contains("title | disambiguator"));
        assertTrue(PromptFactory.SYSTEM.contains("one item per line"));
        assertTrue(PromptFactory.SYSTEM.contains("No numbering"));
    }

    @Test
    void userPromptCarriesCountQueryAndDisambiguator() {
        String prompt = PromptFactory.user(Form.MOVIE, "dreams", 10);

        assertTrue(prompt.startsWith("List 10 movie titles related to: dreams"),
                prompt);
        assertTrue(prompt.contains("Format each line as: title | release year"), prompt);
    }

    @Test
    void musicAsksForSongsBecauseGeniusSearchesSongs() {
        // Music resolves against Genius (songs), not MusicBrainz albums; asking
        // the model for albums would feed the resolver the wrong noun.
        assertEquals("song", Form.MUSIC.promptNoun());
        assertTrue(PromptFactory.user(Form.MUSIC, "sad nights", 10).contains("song titles"));
        assertTrue(PromptFactory.user(Form.MUSIC, "sad nights", 10).contains("title | artist"));
    }

    @Test
    void everyFormHasItsOwnNounAndDisambiguator() {
        assertTrue(PromptFactory.user(Form.BOOK, "space", 10).contains("title | author"));
        assertTrue(PromptFactory.user(Form.GAME, "cozy", 10).contains("video game titles"));
        assertTrue(PromptFactory.user(Form.GAME, "cozy", 10).contains("title | release year"));
    }

    @Test
    void theQueryTravelsVerbatim() {
        // Vibes, not just titles: no quoting, escaping or rewriting.
        String prompt = PromptFactory.user(Form.MOVIE, "movies that feel like rain on a window", 10);
        assertTrue(prompt.contains("related to: movies that feel like rain on a window"));
    }
}
