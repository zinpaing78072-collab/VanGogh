package vangogh.book;

import org.junit.jupiter.api.Test;

import vangogh.error.FetchError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vavr.control.Either;
import io.vavr.control.Try;

import vangogh.net.ApiResponse;
import vangogh.net.OpenLibraryClient;

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

    // ------------------------------------------------------------- detail

    private static final String WORK_JSON = """
        {
          "key": "/works/OL2172356W",
          "title": "Do Androids Dream of Electric Sheep?",
          "authors": [{"author": {"key": "/authors/OL274606A"}}],
          "first_publish_date": "1968",
          "covers": [207515, 6917227],
          "description": {"type": "/type/text", "value": "It was January 2021, and Rick Deckard had a license to kill."},
          "first_sentence": {"type": "/type/text", "value": "A merry little surge of electricity piped by automatic alarm."},
          "subjects": ["Androids", "Science fiction", "Androids", "Robotic pets"],
          "subject_times": ["2021"]
        }
        """;

    private static final String EDITIONS_JSON = """
        {
          "size": 90,
          "entries": [
            {"covers": [123], "publish_date": "1969", "number_of_pages": 210,
             "isbn_13": ["9780451038005"], "publishers": ["Signet"]},
            {"covers": [456], "publish_date": "2017", "number_of_pages": 224,
             "isbn_13": ["9780547572345"], "publishers": ["Mariner"]},
            {"covers": [789], "publish_date": "2022", "number_of_pages": 221,
             "publishers": ["Mondadori"]},
            {"publish_date": "1990", "number_of_pages": 200}
          ]
        }
        """;

    private static final String RATINGS_JSON = """
        {"summary": {"average": 3.96, "count": 164}}
        """;

    private static final String AUTHOR_JSON = """
        {
          "key": "/authors/OL274606A",
          "name": "Philip K. Dick",
          "birth_date": "1928-12-16",
          "death_date": "1982-03-02",
          "bio": {"type": "/type/text", "value": "Philip Kindred Dick was an American novelist."},
          "photos": [12345],
          "links": [{"title": "Wikipedia", "url": "https://en.wikipedia.org/wiki/Philip_K._Dick"}]
        }
        """;

    private static final String SIMILAR_JSON = """
        {"docs": [
          {"key": "/works/OL2172356W", "title": "Do Androids Dream of Electric Sheep?"},
          {"key": "/works/OL1W", "title": "Neuromancer", "author_name": ["William Gibson"],
           "first_publish_year": 1984, "cover_i": 11, "ratings_average": 4.0},
          {"key": "/works/OL2W", "title": "Snow Crash", "author_name": ["Neal Stephenson"],
           "first_publish_year": 1992, "cover_i": 12},
          {"key": "/works/OL3W", "title": "The Left Hand of Darkness",
           "first_publish_year": 1969, "cover_i": 13},
          {"key": "/works/OL4W", "title": "Fahrenheit 451", "first_publish_year": 1953},
          {"key": "/works/OL5W", "title": "Nineteen Eighty-Four", "first_publish_year": 1949},
          {"key": "/works/OL6W", "title": "A Scanner Darkly", "first_publish_year": 1977},
          {"key": "/works/OL7W", "title": "Ubik", "first_publish_year": 1969}
        ]}
        """;

    /** Client with canned detail payloads (no network). */
    static class DetailClient extends OpenLibraryClient {
        boolean failSupplements;

        private Try<ApiResponse> reply(String body) {
            return Try.success(new ApiResponse(200, body, null));
        }

        @Override
        public Try<ApiResponse> workDetail(String workKey) {
            return reply(WORK_JSON);
        }

        @Override
        public Try<ApiResponse> authorDetail(String authorKey) {
            return failSupplements ? Try.failure(new RuntimeException("offline")) : reply(AUTHOR_JSON);
        }

        @Override
        public Try<ApiResponse> editions(String workKey) {
            return failSupplements ? Try.failure(new RuntimeException("offline")) : reply(EDITIONS_JSON);
        }

        @Override
        public Try<ApiResponse> ratings(String workKey) {
            return failSupplements ? Try.failure(new RuntimeException("offline")) : reply(RATINGS_JSON);
        }

        @Override
        public Try<ApiResponse> searchBySubject(String subject) {
            return failSupplements ? Try.failure(new RuntimeException("offline")) : reply(SIMILAR_JSON);
        }

        @Override
        public Try<ApiResponse> searchByAuthor(String author) {
            return failSupplements ? Try.failure(new RuntimeException("offline")) : reply(SIMILAR_JSON);
        }
    }

    @Test
    void bookDetailFoldsTheOpenLibraryPayloadsIntoOneRecord() {
        var detail = new OpenLibraryBookService(new DetailClient())
                .bookDetail("/works/OL2172356W")
                .get();

        assertEquals("OL2172356W", detail.workKey());
        assertEquals("Do Androids Dream of Electric Sheep?", detail.title());
        assertEquals(1968, detail.year());
        // The edition median, not the first printing's count.
        assertEquals(221, detail.pages());
        assertEquals(90, detail.editionCount());
        // Raw average; the page rounds it to one decimal for display.
        assertEquals(3.96, detail.rating(), 0.001);
        assertEquals(164, detail.ratingCount());
        assertEquals("It was January 2021, and Rick Deckard had a license to kill.", detail.description());
        assertEquals("A merry little surge of electricity piped by automatic alarm.", detail.firstSentence());
        assertEquals(java.util.List.of("Androids", "Science fiction", "Robotic pets"), detail.subjects());
        assertEquals("2021", detail.setting());
        assertEquals("https://covers.openlibrary.org/b/id/207515-L.jpg", detail.coverUrl());
        assertEquals("https://openlibrary.org/works/OL2172356W", detail.openLibraryUrl());
    }

    @Test
    void bookDetailBuildsTheRecordLedgerAndEditions() {
        var detail = new OpenLibraryBookService(new DetailClient())
                .bookDetail("OL2172356W").get();

        // Four of the eight prototype rows survive: the sparse record has no
        // adaptation/status, so the ledger is only what Open Library knows.
        assertEquals(8, detail.facts().size());
        assertEquals("First published", detail.facts().get(0).label());
        assertEquals("1968", detail.facts().get(0).value());
        assertEquals("Featured", detail.facts().get(6).label());
        assertEquals("Signet, 1969", detail.facts().get(6).value());
        assertTrue(detail.facts().get(6).spanAll());

        assertEquals(4, detail.editions().size());
        assertEquals("1969", detail.editions().get(0).year());
        assertEquals("210 pp", detail.editions().get(0).pages());
        assertEquals("9780451038005", detail.editions().get(0).isbn());
        assertEquals("https://covers.openlibrary.org/b/id/123-L.jpg",
                detail.editions().get(0).coverUrl());
        // The coverless edition is kept, so the page still shows its year.
        assertEquals("", detail.editions().get(3).coverUrl());
    }

    @Test
    void bookDetailBuildsTheAuthorCard() {
        var author = new OpenLibraryBookService(new DetailClient())
                .bookDetail("OL2172356W").get().author();

        assertEquals("Philip K. Dick", author.name());
        assertEquals("1928 \u2013 1982", author.dates());
        assertEquals("Philip Kindred Dick was an American novelist.", author.bio());
        assertEquals("https://covers.openlibrary.org/a/id/12345-L.jpg", author.photoUrl());
        assertEquals("https://en.wikipedia.org/wiki/Philip_K._Dick", author.wikipediaUrl());
        assertEquals("https://openlibrary.org/authors/OL274606A", author.openLibraryUrl());
    }

    @Test
    void bookDetailSimilarRailDropsTheCurrentWorkAndCapsAtSix() {
        var similar = new OpenLibraryBookService(new DetailClient())
                .bookDetail("OL2172356W").get().similar();

        assertEquals(6, similar.size());
        assertEquals("Neuromancer", similar.get(0).title());
        assertTrue(similar.stream().noneMatch(book -> book.workKey().equals("OL2172356W")));
    }

    @Test
    void supplementaryFailuresLeaveEmptyTilesInsteadOfFailingThePage() {
        DetailClient client = new DetailClient();
        client.failSupplements = true;

        var detail = new OpenLibraryBookService(client).bookDetail("OL2172356W").get();

        assertEquals("Do Androids Dream of Electric Sheep?", detail.title());
        assertEquals(0, detail.rating(), 0.001);
        assertEquals(0, detail.editionCount());
        assertTrue(detail.editions().isEmpty());
        assertTrue(detail.similar().isEmpty());
        assertEquals("", detail.author().name());
        // The work's own record still paints.
        assertEquals("2021", detail.setting());
    }

    @Test
    void unreadableWorkPayloadIsAnErrorNotAnEmptyDetail() {
        OpenLibraryClient client = new OpenLibraryClient() {
            @Override
            public Try<ApiResponse> workDetail(String workKey) {
                return Try.success(new ApiResponse(200, "[]", null));
            }
        };

        Either<FetchError, BookDetail> result = new OpenLibraryBookService(client)
                .bookDetail("OL2172356W");

        assertTrue(result.isLeft());
        assertTrue(FetchError.uiMessage(result.getLeft()).contains("payload unreadable"));
    }

    @Test
    void aBlankWorkKeyIsBadInputAndNeverHitsTheNetwork() {
        Either<FetchError, BookDetail> result = new OpenLibraryBookService().bookDetail("  ");

        assertTrue(result.isLeft());
        assertTrue(result.getLeft() instanceof FetchError.BadInput);
    }
}
