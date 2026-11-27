package vangogh.storage;

import io.vavr.collection.List;
import io.vavr.control.Either;
import org.jooq.Condition;
import org.jooq.Record;
import org.jooq.impl.DSL;
import vangogh.error.FetchError;
import vangogh.storage.model.QueueFilter;
import vangogh.storage.model.SavedEntry;
import vangogh.storage.model.StoredItem;

import java.util.Map;
import java.util.Set;

import static vangogh.db.Tables.ITEM;
import static vangogh.db.Tables.SAVED;

/**
 * The library row. One row per {@code (user_id, item_id)}; {@code favorite} is
 * the Favorites flag and {@code status} is the Queue's {@code want/progress/done}.
 * Removing the row is the only delete path.
 */
public final class SavedRepository {

    private static final Set<String> STATUSES = Set.of("want", "progress", "done");

    private final Database database;

    public SavedRepository(Database database) {
        this.database = database;
    }

    /** Creates the library row if absent and returns its id either way. */
    public Either<FetchError, Long> saveOrGet(long userId, long itemId, long now) {
        return Database.attempt("saved.saveOrGet", () -> database.write(context -> {
            Integer inserted = context.insertInto(SAVED)
                    .set(SAVED.USER_ID, (int) userId)
                    .set(SAVED.ITEM_ID, (int) itemId)
                    .set(SAVED.SAVED_AT, (int) now)
                    .onConflict(SAVED.USER_ID, SAVED.ITEM_ID)
                    .doNothing()
                    .returning(SAVED.ID)
                    .fetchOne(SAVED.ID);
            if (inserted != null) {
                return (long) inserted;
            }
            Integer existing = context.select(SAVED.ID)
                    .from(SAVED)
                    .where(SAVED.USER_ID.eq((int) userId))
                    .and(SAVED.ITEM_ID.eq((int) itemId))
                    .fetchOne(SAVED.ID);
            if (existing == null) {
                throw new IllegalStateException("saved row missing after upsert");
            }
            return (long) existing;
        }));
    }

    public Either<FetchError, Boolean> setStatus(long savedId, String status) {
        if (!STATUSES.contains(status)) {
            return Either.left(new FetchError.BadInput("status", String.valueOf(status)));
        }
        return Database.attempt("saved.setStatus", () -> database.write(context ->
                context.update(SAVED)
                        .set(SAVED.STATUS, status)
                        .where(SAVED.ID.eq((int) savedId))
                        .execute() > 0));
    }

    public Either<FetchError, Boolean> setProgress(long savedId, int progress) {
        if (progress < 0 || progress > 100) {
            return Either.left(new FetchError.BadInput("progress", String.valueOf(progress)));
        }
        return Database.attempt("saved.setProgress", () -> database.write(context ->
                context.update(SAVED)
                        .set(SAVED.PROGRESS, progress)
                        .where(SAVED.ID.eq((int) savedId))
                        .execute() > 0));
    }

    public Either<FetchError, Boolean> setNote(long savedId, String note) {
        return Database.attempt("saved.setNote", () -> database.write(context ->
                context.update(SAVED)
                        .set(SAVED.NOTE, note)
                        .where(SAVED.ID.eq((int) savedId))
                        .execute() > 0));
    }

    /** Writes {@code favorite} and {@code favorited_at} together, as the CHECK requires. */
    public Either<FetchError, Boolean> setFavorite(long savedId, boolean favorite, long now) {
        return Database.attempt("saved.setFavorite", () -> database.write(context ->
                context.update(SAVED)
                        .set(SAVED.FAVORITE, favorite ? 1 : 0)
                        .set(SAVED.FAVORITED_AT, favorite ? Integer.valueOf((int) now) : null)
                        .where(SAVED.ID.eq((int) savedId))
                        .execute() > 0));
    }

    public Either<FetchError, Boolean> remove(long savedId) {
        return Database.attempt("saved.remove", () -> database.write(context ->
                context.deleteFrom(SAVED)
                        .where(SAVED.ID.eq((int) savedId))
                        .execute() > 0));
    }

    public Either<FetchError, List<SavedEntry>> queue(long userId, QueueFilter filter) {
        return Database.attempt("saved.queue", () -> database.read(context -> {
            Condition condition = SAVED.USER_ID.eq((int) userId);
            if (filter.form() != null) {
                condition = condition.and(ITEM.FORM.eq(filter.form().wire()));
            }
            if (filter.status() != null) {
                condition = condition.and(SAVED.STATUS.eq(filter.status()));
            }
            if (filter.favoritesOnly()) {
                condition = condition.and(SAVED.FAVORITE.eq(1));
            }
            return List.ofAll(context.select(
                                    SAVED.ID, SAVED.USER_ID, SAVED.FAVORITE, SAVED.FAVORITED_AT,
                                    SAVED.STATUS, SAVED.PROGRESS, SAVED.NOTE, SAVED.SAVED_AT,
                                    ITEM.ID, ITEM.SOURCE, ITEM.FORM, ITEM.TITLE, ITEM.METADATA,
                                    ITEM.POSTER_MIME, ITEM.FETCHED_AT)
                            .from(SAVED)
                            .join(ITEM).on(ITEM.ID.eq(SAVED.ITEM_ID))
                            .where(condition)
                            .orderBy(SAVED.SAVED_AT.desc(), SAVED.ID.desc())
                            .fetch())
                    .map(SavedRepository::toEntry);
        }));
    }

    public Either<FetchError, List<SavedEntry>> favorites(long userId) {
        return queue(userId, new QueueFilter(null, null, true));
    }

    public Either<FetchError, Map<String, Integer>> statusCounts(long userId) {
        return Database.attempt("saved.statusCounts", () -> database.read(context ->
                context.select(SAVED.STATUS, DSL.count())
                        .from(SAVED)
                        .where(SAVED.USER_ID.eq((int) userId))
                        .groupBy(SAVED.STATUS)
                        .fetchMap(SAVED.STATUS, DSL.count())));
    }

    public Either<FetchError, Map<String, Integer>> formCounts(long userId) {
        return Database.attempt("saved.formCounts", () -> database.read(context ->
                context.select(ITEM.FORM, DSL.count())
                        .from(SAVED)
                        .join(ITEM).on(ITEM.ID.eq(SAVED.ITEM_ID))
                        .where(SAVED.USER_ID.eq((int) userId))
                        .groupBy(ITEM.FORM)
                        .fetchMap(ITEM.FORM, DSL.count())));
    }

    private static SavedEntry toEntry(Record row) {
        StoredItem item = new StoredItem(
                row.get(ITEM.ID),
                row.get(ITEM.SOURCE),
                form(row.get(ITEM.FORM)),
                row.get(ITEM.TITLE),
                row.get(ITEM.METADATA),
                row.get(ITEM.POSTER_MIME),
                row.get(ITEM.FETCHED_AT));
        Integer favorite = row.get(SAVED.FAVORITE);
        return new SavedEntry(
                row.get(SAVED.ID),
                row.get(SAVED.USER_ID),
                item,
                favorite != null && favorite == 1,
                epoch(row.get(SAVED.FAVORITED_AT)),
                row.get(SAVED.STATUS),
                row.get(SAVED.PROGRESS),
                row.get(SAVED.NOTE),
                row.get(SAVED.SAVED_AT));
    }

    private static ContentForm form(String wire) {
        return ContentForm.fromWire(wire)
                .getOrElseThrow(() -> new IllegalStateException("unknown item.form: " + wire));
    }

    private static Long epoch(Integer value) {
        return value == null ? null : value.longValue();
    }
}
