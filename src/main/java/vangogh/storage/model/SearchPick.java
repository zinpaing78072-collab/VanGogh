package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * One stored pick. {@code itemId} is null while the engine's title has not
 * resolved to a catalog row yet.
 */
public record SearchPick(
        long id,
        long searchId,
        Long itemId,
        ContentForm form,
        String title,
        String reason,
        int position
) {
}
