package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * What an upsert writes into {@code item}. Poster bytes, MIME and detail JSON
 * are absent on purpose: they arrive asynchronously and must survive a
 * metadata refresh. {@code fetchedAt} always advances.
 */
public record NewItem(
        String source,
        ContentForm form,
        String title,
        String metadata,
        long fetchedAt
) {
}
