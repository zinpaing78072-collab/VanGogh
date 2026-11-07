package vangogh.movie;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovieTest {

    @Test
    void movieRecordStoresAllFields() {
        Movie m = new Movie(27205, "Inception", 2010, 8.8, "Action · Sci-Fi", "A dream-heist story.", "https://image.tmdb.org/t/p/w500/x.jpg");

        assertEquals(27205, m.id());
        assertEquals("Inception", m.title());
        assertEquals(2010, m.year());
        assertEquals(8.8, m.rating());
        assertEquals("Action · Sci-Fi", m.genre());
        assertEquals("A dream-heist story.", m.overview());
        assertEquals("https://image.tmdb.org/t/p/w500/x.jpg", m.posterUrl());
    }

    @Test
    void movieRecordWithZeroValues() {
        Movie m = new Movie(0, "", 0, 0.0, "", "", null);

        assertEquals(0, m.id());
        assertEquals("", m.title());
        assertEquals(0, m.year());
        assertEquals(0.0, m.rating());
        assertEquals("", m.genre());
        assertEquals("", m.overview());
        assertNull(m.posterUrl());
    }

    @Test
    void movieRecordEqualsAndHashCode() {
        Movie a = new Movie(1, "Test", 2020, 7.0, "Drama", "overview", "poster");
        Movie b = new Movie(1, "Test", 2020, 7.0, "Drama", "overview", "poster");
        Movie c = new Movie(2, "Other", 2021, 8.0, "Action", "overview2", "poster2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void movieRecordToString() {
        Movie m = new Movie(42, "Title", 1999, 9.0, "Sci-Fi", "overview", "url");
        String s = m.toString();

        assertTrue(s.contains("Title"));
        assertTrue(s.contains("1999"));
        assertTrue(s.contains("Sci-Fi"));
    }
}