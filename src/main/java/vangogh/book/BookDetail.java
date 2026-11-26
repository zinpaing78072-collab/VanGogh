package vangogh.book;

/**
 * Everything the book-detail page shows for one work.
 *
 * <p>Open Library has no single detail endpoint: the identity, record and
 * edition blocks come from {@code /works/:key.json}, {@code /works/:key/editions.json}
 * and {@code /works/:key/ratings.json}; the author card comes from
 * {@code /authors/:key.json}; and the "more like this" rail is a subject search.
 * {@link BookDetailMapper} folds those payloads into this record, and the
 * controller resolves the whole thing on the IO pool so the page never blocks.
 *
 * <p>Covers and author photos cannot be plain Open Library URLs on some
 * networks (see AGENTS.md "Poster proxy"), so they stream as data URIs through
 * the {@code bookDetailArt} event. The page paints them by slot — {@code cover},
 * {@code author}, {@code edition:<i>}, {@code similar:<workKey>} — where the
 * index is the entry's position in the lists below.
 */
public record BookDetail(
        String workKey,
        String title,
        int year,
        int pages,
        int editionCount,
        double rating,
        int ratingCount,
        /** Open Library description prose, trimmed for the story tile. */
        String description,
        /** Internet Archive reader URL for the first scanned edition, or empty.
         *  Opens as the page's in-app "Browse sample" overlay. */
        String sampleUrl,
        java.util.List<String> subjects,
        /** The first {@code subject_times} entry ("2021"), or empty. */
        String setting,
        /** Open Library cover URL for the work (empty when it has no cover). */
        String coverUrl,
        /** The work's public Open Library page. */
        String openLibraryUrl,
        /** The Record ledger, in display order. */
        java.util.List<Fact> facts,
        /** Up to four editions, in the order the editions endpoint returned them. */
        java.util.List<Edition> editions,
        /** The primary author card (never null; fields are empty when unknown). */
        Author author,
        /** Up to six related works, already shaped like grid cards. */
        java.util.List<Book> similar
) {
    /** One row of the Record ledger. {@code spanAll} runs the row full width. */
    public record Fact(String label, String value, boolean muted, boolean spanAll) {}

    /** One edition in the "Other editions" rail. {@code coverUrl} may be empty. */
    public record Edition(String year, String pages, String isbn, String coverUrl) {}

    /** The author tile: dates are pre-joined ("1928 – 1982"); links may be empty. */
    public record Author(
            String name,
            String dates,
            String bio,
            String photoUrl,
            String wikipediaUrl,
            String openLibraryUrl
    ) {
        static Author empty() {
            return new Author("", "", "", "", "", "");
        }
    }
}
