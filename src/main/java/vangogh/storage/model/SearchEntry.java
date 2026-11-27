package vangogh.storage.model;

/** One stored search. {@code savedAt} is null until the user keeps it. */
public record SearchEntry(
        long id,
        long userId,
        String query,
        long ranAt,
        Long savedAt,
        String loadedForms
) {
}
