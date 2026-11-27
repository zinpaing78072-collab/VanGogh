package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * One shared content row: the card metadata as stored. {@code metadata} is the
 * raw JSON document (creator, year, rating, genre, overview, poster_url); this
 * layer does not parse it. Poster bytes and detail JSON have their own reads.
 */
public record StoredItem(
        long id,
        String source,
        ContentForm form,
        String title,
        String metadata,
        String posterMime,
        long fetchedAt
) {
}
