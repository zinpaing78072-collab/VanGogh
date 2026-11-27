package vangogh.storage;

import io.vavr.collection.List;
import io.vavr.control.Either;
import vangogh.db.tables.records.SearchRecord;
import vangogh.error.FetchError;
import vangogh.storage.model.SearchEntry;
import vangogh.storage.model.SearchPickDraft;

import static vangogh.db.Tables.SEARCH;

/**
 * A stored search and, in the same transaction, its picks. Searches are
 * recorded on every AI run; {@code savedAt} marks the ones kept in My Searches.
 */
public final class SearchRepository {

    private final Database database;
    private final SearchItemRepository items;

    public SearchRepository(Database database) {
        this.database = database;
        this.items = new SearchItemRepository(database);
    }

    /** Inserts the search and its picks in one transaction; returns the search id. */
    public Either<FetchError, Long> record(long userId, String query, String loadedForms,
                                           long now, List<SearchPickDraft> picks) {
        return Database.attempt("search.record", () -> database.write(context -> {
            Integer id = context.insertInto(SEARCH)
                    .set(SEARCH.USER_ID, (int) userId)
                    .set(SEARCH.QUERY, query)
                    .set(SEARCH.RAN_AT, (int) now)
                    .set(SEARCH.LOADED_FORMS, loadedForms)
                    .returning(SEARCH.ID)
                    .fetchOne(SEARCH.ID);
            if (id == null) {
                throw new IllegalStateException("search insert returned no id");
            }
            items.replaceIn(context, id, picks);
            return (long) id;
        }));
    }

    public Either<FetchError, Boolean> markSaved(long searchId, long now) {
        return Database.attempt("search.markSaved", () -> database.write(context ->
                context.update(SEARCH)
                        .set(SEARCH.SAVED_AT, (int) now)
                        .where(SEARCH.ID.eq((int) searchId))
                        .execute() > 0));
    }

    public Either<FetchError, List<SearchEntry>> recent(long userId, int limit) {
        return Database.attempt("search.recent", () -> database.read(context ->
                List.ofAll(context.selectFrom(SEARCH)
                                .where(SEARCH.USER_ID.eq((int) userId))
                                .orderBy(SEARCH.RAN_AT.desc(), SEARCH.ID.desc())
                                .limit(Math.max(0, limit))
                                .fetch())
                        .map(SearchRepository::toEntry)));
    }

    public Either<FetchError, List<SearchEntry>> saved(long userId) {
        return Database.attempt("search.saved", () -> database.read(context ->
                List.ofAll(context.selectFrom(SEARCH)
                                .where(SEARCH.USER_ID.eq((int) userId))
                                .and(SEARCH.SAVED_AT.isNotNull())
                                .orderBy(SEARCH.SAVED_AT.desc(), SEARCH.ID.desc())
                                .fetch())
                        .map(SearchRepository::toEntry)));
    }

    private static SearchEntry toEntry(SearchRecord row) {
        Integer savedAt = row.get(SEARCH.SAVED_AT);
        return new SearchEntry(
                row.get(SEARCH.ID),
                row.get(SEARCH.USER_ID),
                row.get(SEARCH.QUERY),
                row.get(SEARCH.RAN_AT),
                savedAt == null ? null : savedAt.longValue(),
                row.get(SEARCH.LOADED_FORMS));
    }
}
