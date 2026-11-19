package vangogh.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parser is the tolerant half of the contract: a model that decorates a
 * line still yields a usable pick, but every deviation is counted so the
 * caller can tell "model misbehaved" from "catalog disagreed".
 */
class TitleParserTest {

    @Test
    void cleanLinesParseWithNoViolations() {
        TitleParser.Parsed parsed = TitleParser.parse(
                "Inception | 2010\nInterstellar | 2014", 10);

        assertEquals(0, parsed.violations());
        assertEquals(2, parsed.picks().size());
        assertEquals(new Pick("Inception", "2010"), parsed.picks().get(0));
        assertEquals(new Pick("Interstellar", "2014"), parsed.picks().get(1));
        assertEquals(2010, parsed.picks().get(0).year());
    }

    @Test
    void numberedAndBulletedLinesParseButCountAsViolations() {
        TitleParser.Parsed parsed = TitleParser.parse("1. Inception | 2010\n- Alien | 1979", 10);

        assertEquals(2, parsed.picks().size());
        assertEquals("Inception", parsed.picks().get(0).title());
        assertEquals("Alien", parsed.picks().get(1).title());
        assertEquals(2, parsed.violations());
    }

    @Test
    void aQuotedTitleStillParses() {
        TitleParser.Parsed parsed = TitleParser.parse("\"Inception\"", 10);

        assertEquals(1, parsed.picks().size());
        assertEquals("Inception", parsed.picks().get(0).title());
        // Quoted and pipe-less: two deviations.
        assertEquals(2, parsed.violations());
    }

    @Test
    void aLineWithoutAPipeYieldsATitleOnlyPick() {
        TitleParser.Parsed parsed = TitleParser.parse("Inception", 10);

        assertEquals(new Pick("Inception", null), parsed.picks().get(0));
        assertNull(parsed.picks().get(0).year());
        assertEquals(1, parsed.violations());
    }

    @Test
    void commentaryIsDroppedAndCounted() {
        TitleParser.Parsed parsed = TitleParser.parse(
                "Here are ten movies for a rainy night:\nInception | 2010", 10);

        assertEquals(1, parsed.picks().size());
        assertEquals("Inception", parsed.picks().get(0).title());
        assertEquals(1, parsed.violations());
    }

    @Test
    void anOverlongLineIsCommentary() {
        String longLine = "x".repeat(130) + " | 2010";
        TitleParser.Parsed parsed = TitleParser.parse(longLine, 10);

        assertTrue(parsed.picks().isEmpty());
        assertEquals(1, parsed.violations());
    }

    @Test
    void oneCommaBlobIsSplitButCountsAsOneViolation() {
        TitleParser.Parsed parsed = TitleParser.parse("Inception, Alien, Arrival", 10);

        assertEquals(3, parsed.picks().size());
        assertEquals("Arrival", parsed.picks().get(2).title());
        assertEquals(1, parsed.violations());
    }

    @Test
    void emptyInputParsesToNothing() {
        assertTrue(TitleParser.parse(null, 10).picks().isEmpty());
        assertTrue(TitleParser.parse("   \n\n ", 10).picks().isEmpty());
    }

    @Test
    void thePickListIsCapped() {
        String reply = String.join("\n", List.of(
                "A | 1", "B | 2", "C | 3", "D | 4", "E | 5", "F | 6"));

        TitleParser.Parsed parsed = TitleParser.parse(reply, 4);

        assertEquals(4, parsed.picks().size());
        assertEquals("D", parsed.picks().get(3).title());
    }

    @Test
    void anEmptyTitleFieldIsAViolationAndIsDropped() {
        TitleParser.Parsed parsed = TitleParser.parse("| 2010", 10);

        assertTrue(parsed.picks().isEmpty());
        assertEquals(1, parsed.violations());
    }

    @Test
    void aYearIsExtractedOnlyFromAFourDigitDisambiguator() {
        assertEquals(2010, new Pick("X", "2010").year());
        assertEquals(2010, new Pick("X", "released 2010").year());
        assertNull(new Pick("X", "director's cut").year());
        assertNull(new Pick("X", "20101").year());
        assertNull(new Pick("X", null).year());
    }
}
