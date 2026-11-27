package vangogh.storage;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageSchemaTest {

    @TempDir
    Path temp;

    private Database database;

    @BeforeEach
    void setUp() {
        database = Database.open(temp.resolve("schema.db"));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void theFiveTablesExistWithTheirColumns() {
        List<String> tables = database.read(context -> context
                .fetch("select name from sqlite_master where type = 'table' and name not like 'sqlite_%'")
                .getValues("name", String.class));

        assertTrue(tables.containsAll(List.of("user", "item", "saved", "search", "search_item")),
                tables.toString());

        List<String> itemColumns = database.read(context -> context
                .fetch("pragma table_info(item)")
                .getValues("name", String.class));
        assertTrue(itemColumns.containsAll(List.of(
                "id", "source", "form", "title", "metadata", "poster", "poster_mime",
                "extra", "fetched_at")), itemColumns.toString());
    }

    @Test
    void itemSourceIsUnique() {
        ItemRepository items = new ItemRepository(database);
        long first = items.upsert(TestData.item("tmdb:603", ContentForm.MOVIE, "The Matrix")).get();
        long second = items.upsert(TestData.item("tmdb:603", ContentForm.MOVIE, "The Matrix")).get();

        assertEquals(first, second);
        assertEquals(1, count("item"));
    }

    @Test
    void favoritingWithoutADateViolatesTheCheck() {
        ItemRepository items = new ItemRepository(database);
        SavedRepository saved = new SavedRepository(database);
        long itemId = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        long savedId = saved.saveOrGet(1L, itemId, TestData.NOW).get();

        assertThrows(RuntimeException.class, () -> database.write(context ->
                context.execute("update saved set favorite = 1 where id = " + savedId)));
    }

    @Test
    void deletingAUserCascadesToSavedRowsAndSearches() {
        ItemRepository items = new ItemRepository(database);
        long itemId = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();

        database.write(context -> {
            context.execute("insert into \"user\" (id, name, created_at) values (2, 'guest', 1)");
            return null;
        });
        long searchId = new SearchRepository(database)
                .record(2L, "heat", "movie", TestData.NOW, io.vavr.collection.List.empty()).get();
        database.write(context -> {
            context.execute("insert into saved (user_id, item_id, saved_at) values (2, " + itemId + ", 1)");
            context.execute("insert into search_item (search_id, form, title, position) values ("
                    + searchId + ", 'movie', 'Heat', 0)");
            return null;
        });

        database.write(context -> context.execute("delete from \"user\" where id = 2"));

        assertEquals(0, count("saved"));
        assertEquals(0, count("search"));
        assertEquals(0, count("search_item"));
        assertEquals(1, count("item"), "content rows are shared, not owned by a user");
    }

    private int count(String table) {
        return database.read(context -> context
                .selectCount().from(DSL.table(table)).fetchOne(0, Integer.class));
    }
}
