package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * Filters for a Queue read. Null form/status means "any"; {@code favoritesOnly}
 * adds {@code saved.favorite = 1}.
 */
public record QueueFilter(ContentForm form, String status, boolean favoritesOnly) {

    public static QueueFilter all() {
        return new QueueFilter(null, null, false);
    }
}
