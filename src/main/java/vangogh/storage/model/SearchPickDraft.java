package vangogh.storage.model;

import vangogh.storage.ContentForm;

/**
 * A pick to write. The caller resolves items first and passes their ids;
 * {@code itemId} stays null for a pick that has not resolved.
 */
public record SearchPickDraft(
        ContentForm form,
        String title,
        String reason,
        int position,
        Long itemId
) {
}
