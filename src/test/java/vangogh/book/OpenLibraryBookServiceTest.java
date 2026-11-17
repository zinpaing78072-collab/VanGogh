package vangogh.book;

import org.junit.jupiter.api.Test;

import vangogh.error.FetchError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vavr.control.Either;

class OpenLibraryBookServiceTest {

    /** Real search.json shape (fields= trimmed to what the service requests). */
    private static final String SEARCH_JSON = """
        {
          "numFound": 2,
          "docs": [
            {
              "key": "/works/OL27482W",
              "title": "The Hobbit",
              "author_name": ["J.R.R. Tolkien"],
              "cover_i": 14627509,
              "first_publish_year": 1937,
              "first_sentence": ["Numa toca no chão vivia um hobbit.", "In a hole in the ground there lived a hobbit."],
              "subject": ["Fantasy", "Dragons", "Middle Earth", "Quests"],
              "edition_count": 482,
              "number_of_pages_median": 310,
              "ratings_average": 4.29
            },
            { "key": "/works/OL27513W", "title": "The Fellowship of the Ring" }
          ]
        }
        """;

    /** Real trending/weekly.json shape: same rows, under "works". */
    private static final String TRENDING_JSON = """
        {
          "query": "/trending/weekly",
          "works": [
            {
              "key": "/works/OL17930368W",
              "title": "Atomic Habits",
              "author_name": ["James Clear"],
              "cover_i": 12539702,
              "first_publish_year": 2016,
              "ratings_average": 3.96
            }
          ]
        }
        """;

    @Test
    void parsesSearchDocsIntoBooks() {
        var books = new OpenLibraryBookService().parseBooks(SEARCH_JSON);

        assertEquals(2, books.size());
        Book hobbit = books.get(0);
        assertEquals("OL27482W", hobbit.workKey());
        assertEquals("The Hobbit", hobbit.title());
        assertEquals("J.R.R. Tolkien", hobbit.author());
        assertEquals(1937, hobbit.year());
        assertEquals(4.29, hobbit.rating());
        // Subjects are capped at three, joined for the detail view; the card
        // itself shows year + "Author · N pages".
        assertEquals("Fantasy · Dragons · Middle Earth", hobbit.subjects());
        assertEquals(310, hobbit.pages());
        assertEquals("https://covers.openlibrary.org/b/id/14627509-L.jpg", hobbit.coverUrl());
        // The first line picks the English sentence out of the translation pool.
        assertEquals("In a hole in the ground there lived a hobbit.", hobbit.firstSentence());
    }

    @Test
    void parsesTrendingWorksRows() {
        var books = new OpenLibraryBookService().parseBooks(TRENDING_JSON);

        assertEquals(1, books.size());
        assertEquals("OL17930368W", books.get(0).workKey());
        assertEquals("Atomic Habits", books.get(0).title());
        assertEquals(2016, books.get(0).year());
    }

    @Test
    void sparseRowsBuildLeniently() {
        var books = new OpenLibraryBookService().parseBooks(SEARCH_JSON);

        Book sparse = books.get(1);
        assertEquals("OL27513W", sparse.workKey());
        assertEquals("The Fellowship of the Ring", sparse.title());
        assertEquals("", sparse.author());
        assertEquals(0, sparse.year());
        assertEquals(0.0, sparse.rating());
        assertEquals("", sparse.coverUrl());
        assertEquals("", sparse.firstSentence());
    }

    @Test
    void rowWithoutAWorkKeyFallsBackToAPositionalId() {
        String json = """
            {"docs": [{"title": "Keyless"}]}
            """;

        var books = new OpenLibraryBookService().parseBooks(json);

        assertEquals("book-0", books.get(0).workKey());
    }

    @Test
    void nonEnglishFirstSentenceFallsBackToTheFirstEntry() {
        String json = """
            {"docs": [{"key": "/works/OL1W", "title": "T",
                       "first_sentence": ["Numa toca no chão vivia um hobbit."]}]}
            """;

        var books = new OpenLibraryBookService().parseBooks(json);

        assertEquals("Numa toca no chão vivia um hobbit.", books.get(0).firstSentence());
    }

    @Test
    void unreadablePayloadStructureIsAnErrorNotAnEmptyList() {
        Either<FetchError, java.util.List<Book>> parsed =
                new OpenLibraryBookService().parseRows("{\"query\": \"/trending/weekly\"}");

        assertTrue(parsed.isLeft());
        String message = FetchError.uiMessage(parsed.getLeft());
        assertTrue(message.contains("docs/works"));
    }

    @Test
    void emptyDocsArrayIsAValidEmptyResult() {
        var books = new OpenLibraryBookService().parseBooks("{\"docs\": [], \"numFound\": 0}");

        assertTrue(books.isEmpty());
    }
}
