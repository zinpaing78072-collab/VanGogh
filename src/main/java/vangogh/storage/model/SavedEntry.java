package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * A library row joined to its content. One row carries both list axes:
 * {@code favorite} is the Favorites flag, {@code status} is the Queue's
 * {@code want/progress/done}.
 */
public record SavedEntry(
        long savedId,
        long userId,
        StoredItem item,
        boolean favorite,
        Long favoritedAt,
        String status,
        int progress,
        String note,
        long savedAt
) {
}
