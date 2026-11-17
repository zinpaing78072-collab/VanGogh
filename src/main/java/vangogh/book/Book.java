package vangogh.book;

/**
 * A book work as resolved from Open Library. Unlike Movie/Game, the id is the
 * Open Library work key (e.g. {@code "OL27482W"}) — a string, because that is
 * the stable identifier Open Library exposes; the frontend uses it only to
 * route "bookCover" events, so no int is forced.
 */
public record Book(
        String workKey,
        String title,
        String author,
        int year,
        double rating,
        String subjects,
        String firstSentence,
        int pages,
        int editionCount,
        String coverUrl
) {}
