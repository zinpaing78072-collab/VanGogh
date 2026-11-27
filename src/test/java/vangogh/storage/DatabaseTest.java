package vangogh.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.error.FetchError;
import vangogh.storage.model.User;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @TempDir
    Path temp;

    private Path file;
    private Database database;

    @BeforeEach
    void setUp() {
        file = temp.resolve("vangogh.db");
        database = Database.open(file);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void bootstrapCreatesTheSchemaAndSeedsTheLocalUser() {
        User user = new UserRepository(database).local().get();

        assertEquals(1L, user.id());
        assertEquals("local", user.name());
        assertTrue(user.createdAt() > 0);
    }

    @Test
    void reopeningTheSameFileIsIdempotent() {
        long createdAt = new UserRepository(database).local().get().createdAt();
        database.close();

        database = Database.open(file);

        assertEquals(createdAt, new UserRepository(database).local().get().createdAt());
        assertEquals(1, new UserRepository(database).local().get().id());
    }

    @Test
    void aNewerSchemaVersionIsRefused() throws Exception {
        database.close();
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Database.open(file));

        assertTrue(error.getMessage().contains("schema is version 99"), error.getMessage());
    }

    @Test
    void foreignKeysAreEnforced() {
        ItemRepository items = new ItemRepository(database);
        long itemId = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();

        var result = new SavedRepository(database).saveOrGet(999L, itemId, TestData.NOW);

        assertTrue(result.isLeft(), result.toString());
        assertInstanceOf(FetchError.Storage.class, result.getLeft());
    }

    @Test
    void parallelReadsAndWritesShareTheConnectionSafely() throws Exception {
        ItemRepository items = new ItemRepository(database);
        SavedRepository saved = new SavedRepository(database);
        long itemId = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                work.add(pool.submit(() -> new UserRepository(database).local().get()));
                work.add(pool.submit(() -> items.byId(itemId).get()));
            }
            work.add(pool.submit(() -> saved.saveOrGet(1L, itemId, TestData.NOW).get()));
            work.add(pool.submit(() -> saved.setNote(
                    saved.saveOrGet(1L, itemId, TestData.NOW).get(), "queued").get()));

            for (Future<?> future : work) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, saved.queue(1L, TestData.all()).get().size());
    }
}
