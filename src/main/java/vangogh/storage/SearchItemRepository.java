package vangogh.storage;

import io.vavr.collection.List;
import io.vavr.control.Either;
import org.jooq.DSLContext;
import vangogh.db.tables.records.SearchItemRecord;
import vangogh.error.FetchError;
import vangogh.storage.model.SearchPick;
import vangogh.storage.model.SearchPickDraft;

import static vangogh.db.Tables.SEARCH_ITEM;

/** The picks belonging to one stored search. */
public final class SearchItemRepository {

    private final Database database;

    public SearchItemRepository(Database database) {
        this.database = database;
    }

    /** Replaces all picks for a search (the re-run path). */
    public Either<FetchError, Integer> replace(long searchId, List<SearchPickDraft> picks) {
        return Database.attempt("searchItem.replace", () ->
                database.write(context -> replaceIn(context, searchId, picks)));
    }

    /** Participates in an existing transaction; never opens its own. */
    int replaceIn(DSLContext context, long searchId, List<SearchPickDraft> picks) {
        context.deleteFrom(SEARCH_ITEM)
                .where(SEARCH_ITEM.SEARCH_ID.eq((int) searchId))
                .execute();
        for (SearchPickDraft pick : picks) {
            context.insertInto(SEARCH_ITEM)
                    .set(SEARCH_ITEM.SEARCH_ID, (int) searchId)
                    .set(SEARCH_ITEM.ITEM_ID, pick.itemId() == null ? null : pick.itemId().intValue())
                    .set(SEARCH_ITEM.FORM, pick.form().wire())
                    .set(SEARCH_ITEM.TITLE, pick.title())
                    .set(SEARCH_ITEM.REASON, pick.reason())
                    .set(SEARCH_ITEM.POSITION, pick.position())
                    .execute();
        }
        return picks.size();
    }

    /** Attaches a catalog row to a pick that resolved after the search was written. */
    public Either<FetchError, Boolean> linkItem(long pickId, long itemId) {
        return Database.attempt("searchItem.linkItem", () -> database.write(context ->
                context.update(SEARCH_ITEM)
                        .set(SEARCH_ITEM.ITEM_ID, (int) itemId)
                        .where(SEARCH_ITEM.ID.eq((int) pickId))
                        .execute() > 0));
    }

    public Either<FetchError, List<SearchPick>> bySearch(long searchId) {
        return Database.attempt("searchItem.bySearch", () -> database.read(context ->
                List.ofAll(context.selectFrom(SEARCH_ITEM)
                                .where(SEARCH_ITEM.SEARCH_ID.eq((int) searchId))
                                .orderBy(SEARCH_ITEM.POSITION.asc())
                                .fetch())
                        .map(SearchItemRepository::toPick)));
    }

    private static SearchPick toPick(SearchItemRecord row) {
        return new SearchPick(
                row.get(SEARCH_ITEM.ID),
                row.get(SEARCH_ITEM.SEARCH_ID),
                row.get(SEARCH_ITEM.ITEM_ID) == null
                        ? null
                        : row.get(SEARCH_ITEM.ITEM_ID).longValue(),
                ContentForm.fromWire(row.get(SEARCH_ITEM.FORM))
                        .getOrElseThrow(() -> new IllegalStateException(
                                "unknown search_item.form: " + row.get(SEARCH_ITEM.FORM))),
                row.get(SEARCH_ITEM.TITLE),
                row.get(SEARCH_ITEM.REASON),
                row.get(SEARCH_ITEM.POSITION));
    }
}
