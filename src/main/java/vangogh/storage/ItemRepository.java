package vangogh.storage;

import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import org.jooq.impl.DSL;
import vangogh.db.tables.records.ItemRecord;
import vangogh.error.FetchError;
import vangogh.storage.model.NewItem;
import vangogh.storage.model.Poster;
import vangogh.storage.model.StoredItem;

import static vangogh.db.Tables.ITEM;

/**
 * The shared content table. Upsert keys on {@code source} and deliberately
 * leaves {@code poster}, {@code poster_mime} and {@code extra} alone: those
 * arrive asynchronously through their own writers, so refreshing metadata can
 * never erase cached artwork or detail JSON.
 */
public final class ItemRepository {

    private final Database database;

    public ItemRepository(Database database) {
        this.database = database;
    }

    /** Inserts or refreshes a content row; returns its id. */
    public Either<FetchError, Long> upsert(NewItem item) {
        return Database.attempt("item.upsert", () -> database.write(context -> {
            Integer id = context.insertInto(ITEM)
                    .set(ITEM.SOURCE, item.source())
                    .set(ITEM.FORM, item.form().wire())
                    .set(ITEM.TITLE, item.title())
                    .set(ITEM.METADATA, item.metadata())
                    .set(ITEM.FETCHED_AT, (int) item.fetchedAt())
                    .onConflict(ITEM.SOURCE)
                    .doUpdate()
                    .set(ITEM.FORM, DSL.excluded(ITEM.FORM))
                    .set(ITEM.TITLE, DSL.excluded(ITEM.TITLE))
                    .set(ITEM.METADATA, DSL.excluded(ITEM.METADATA))
                    .set(ITEM.FETCHED_AT, DSL.excluded(ITEM.FETCHED_AT))
                    .returning(ITEM.ID)
                    .fetchOne(ITEM.ID);
            if (id == null) {
                throw new IllegalStateException("upsert returned no item id");
            }
            return (long) id;
        }));
    }

    public Either<FetchError, Option<StoredItem>> byId(long id) {
        return Database.attempt("item.byId", () -> database.read(context ->
                Option.of(context.selectFrom(ITEM)
                                .where(ITEM.ID.eq((int) id))
                                .fetchOne())
                        .map(ItemRepository::toItem)));
    }

    public Either<FetchError, Option<StoredItem>> bySource(String source) {
        return Database.attempt("item.bySource", () -> database.read(context ->
                Option.of(context.selectFrom(ITEM)
                                .where(ITEM.SOURCE.eq(source))
                                .fetchOne())
                        .map(ItemRepository::toItem)));
    }

    public Either<FetchError, List<StoredItem>> byForm(ContentForm form, int limit) {
        return Database.attempt("item.byForm", () -> database.read(context ->
                List.ofAll(context.selectFrom(ITEM)
                                .where(ITEM.FORM.eq(form.wire()))
                                .orderBy(ITEM.TITLE.asc())
                                .limit(Math.max(0, limit))
                                .fetch())
                        .map(ItemRepository::toItem)));
    }

    public Either<FetchError, Boolean> writePoster(long itemId, byte[] bytes, String mime) {
        return Database.attempt("item.writePoster", () -> database.write(context ->
                context.update(ITEM)
                        .set(ITEM.POSTER, bytes)
                        .set(ITEM.POSTER_MIME, mime)
                        .where(ITEM.ID.eq((int) itemId))
                        .execute() > 0));
    }

    public Either<FetchError, Option<Poster>> poster(long itemId) {
        return Database.attempt("item.poster", () -> database.read(context -> {
            var row = context.select(ITEM.POSTER, ITEM.POSTER_MIME)
                    .from(ITEM)
                    .where(ITEM.ID.eq((int) itemId))
                    .fetchOne();
            return Option.of(row).flatMap(record ->
                    Option.of(record.get(ITEM.POSTER))
                            .map(bytes -> new Poster(bytes, record.get(ITEM.POSTER_MIME))));
        }));
    }

    public Either<FetchError, Boolean> writeExtra(long itemId, String json) {
        return Database.attempt("item.writeExtra", () -> database.write(context ->
                context.update(ITEM)
                        .set(ITEM.EXTRA, json)
                        .where(ITEM.ID.eq((int) itemId))
                        .execute() > 0));
    }

    public Either<FetchError, Option<String>> extra(long itemId) {
        return Database.attempt("item.extra", () -> database.read(context ->
                Option.of(context.select(ITEM.EXTRA)
                                .from(ITEM)
                                .where(ITEM.ID.eq((int) itemId))
                                .fetchOne(ITEM.EXTRA))));
    }

    private static StoredItem toItem(ItemRecord row) {
        return new StoredItem(
                row.get(ITEM.ID),
                row.get(ITEM.SOURCE),
                ContentForm.fromWire(row.get(ITEM.FORM))
                        .getOrElseThrow(() -> new IllegalStateException(
                                "unknown item.form: " + row.get(ITEM.FORM))),
                row.get(ITEM.TITLE),
                row.get(ITEM.METADATA),
                row.get(ITEM.POSTER_MIME),
                row.get(ITEM.FETCHED_AT));
    }
}
