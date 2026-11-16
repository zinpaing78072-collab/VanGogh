package vangogh.book;

/**
 * One book card: an Open Library work. {@code id} is the work key
 * (e.g. "/works/OL45804W"); {@code overview} is the first sentence when the
 * API has one (there is no plot summary in search results); {@code genre}
 * carries the first community subjects — Open Library has no curated genre
 * field, only folksonomy subject tags.
 */
public record Book(
        String id,
        String title,
        String author,
        int year,
        double rating,
        String overview,
        String genre,
        String posterUrl
) {
}
