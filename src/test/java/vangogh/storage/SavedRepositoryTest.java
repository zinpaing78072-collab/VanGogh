package vangogh.storage;

import io.vavr.collection.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.error.FetchError;
import vangogh.storage.model.QueueFilter;
import vangogh.storage.model.SavedEntry;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SavedRepositoryTest {

    @TempDir
    Path temp;

    private Database database;
    private ItemRepository items;
    private SavedRepository saved;
    private long movieId;
    private long bookId;

    @BeforeEach
    void setUp() {
        database = Database.open(temp.resolve("saved.db"));
        items = new ItemRepository(database);
        saved = new SavedRepository(database);
        movieId = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        bookId = items.upsert(TestData.item("openlibrary:OL1W", ContentForm.BOOK, "Dune")).get();
    }

    @AfterEach
    void tearDrop() {
        database.close();
    }

    @Test
    void saveOrGetInsertsOnceAndIsIdempotent() {
        long first = saved.saveOrGet(1L, movieId, TestData.NOW).get();
        long second = saved.saveOrGet(1L, movieId, TestData.NOW + 60).get();

        assertEquals(first, second);
        assertEquals(1, saved.queue(1L, QueueFilter.all()).get().size());
    }

    @Test
    void aNewRowStartsOnTheWatchlistAndUnfavorited() {
        long id = saved.saveOrGet(1L, movieId, TestData.NOW).get();

        SavedEntry entry = find(id);
        assertFalse(entry.favorite());
        assertNull(entry.favoritedAt());
        assertEquals("want", entry.status());
        assertEquals(0, entry.progress());
        assertNull(entry.note());
        assertEquals(TestData.NOW, entry.savedAt());
        assertEquals(movieId, entry.item().id());
    }

    @Test
    void favoriteAndFavoritedAtMoveTogether() {
        long id = saved.saveOrGet(1L, movieId, TestData.NOW).get();

        assertTrue(saved.setFavorite(id, true, TestData.NOW + 5).get());
        SavedEntry favorited = find(id);
        assertTrue(favorited.favorite());
        assertEquals(TestData.NOW + 5, favorited.favoritedAt());

        assertTrue(saved.setFavorite(id, false, TestData.NOW + 10).get());
        SavedEntry unfavorited = find(id);
        assertFalse(unfavorited.favorite());
        assertNull(unfavorited.favoritedAt());
    }

    @Test
    void progressAndNoteAreUpdatedIndependently() {
        long id = saved.saveOrGet(1L, movieId, TestData.NOW).get();

        assertTrue(saved.setStatus(id, "progress").get());
        assertTrue(saved.setProgress(id, 42).get());
        assertTrue(saved.setNote(id, "half way").get());

        SavedEntry entry = find(id);
        assertEquals("progress", entry.status());
        assertEquals(42, entry.progress());
        assertEquals("half way", entry.note());
    }

    @Test
    void anUnknownStatusIsRejectedAsBadInput() {
        long id = saved.saveOrGet(1L, movieId, TestData.NOW).get();

        var result = saved.setStatus(id, "finished");

        assertTrue(result.isLeft(), result.toString());
        assertInstanceOf(FetchError.BadInput.class, result.getLeft());
        assertEquals("want", find(id).status());
    }

    @Test
    void progressOutsideZeroToHundredIsRejected() {
        long id = saved.saveOrGet(1L, movieId, TestData.NOW).get();

        assertTrue(saved.setProgress(id, -1).isLeft());
        assertTrue(saved.setProgress(id, 101).isLeft());
        assertEquals(0, find(id).progress());
    }

    @Test
    void removeDeletesOnlyThatRow() {
        long movieSaved = saved.saveOrGet(1L, movieId, TestData.NOW).get();
        saved.saveOrGet(1L, bookId, TestData.NOW).get();

        assertTrue(saved.remove(movieSaved).get());

        assertEquals(1, saved.queue(1L, QueueFilter.all()).get().size());
        assertEquals(bookId, saved.queue(1L, QueueFilter.all()).get().head().item().id());
    }

    @Test
    void queueFiltersByFormStatusAndFavorites() {
        long movie = saved.saveOrGet(1L, movieId, TestData.NOW).get();
        long book = saved.saveOrGet(1L, bookId, TestData.NOW + 10).get();
        saved.setStatus(book, "progress").get();
        saved.setFavorite(movie, true, TestData.NOW + 20).get();

        assertEquals(2, saved.queue(1L, new QueueFilter(null, null, false)).get().size());
        assertEquals(List.of(movieId),
                saved.queue(1L, new QueueFilter(ContentForm.MOVIE, null, false)).get()
                        .map(entry -> entry.item().id()));
        assertEquals(List.of(bookId),
                saved.queue(1L, new QueueFilter(null, "progress", false)).get()
                        .map(entry -> entry.item().id()));
        assertEquals(List.of(movieId),
                saved.queue(1L, new QueueFilter(null, null, true)).get()
                        .map(entry -> entry.item().id()));
    }

    @Test
    void queueOrdersNewestFirst() {
        saved.saveOrGet(1L, movieId, TestData.NOW).get();
        saved.saveOrGet(1L, bookId, TestData.NOW + 10).get();

        assertEquals(List.of(bookId, movieId),
                saved.queue(1L, QueueFilter.all()).get().map(entry -> entry.item().id()));
    }

    @Test
    void queueIsScopedToTheUser() {
        saved.saveOrGet(1L, movieId, TestData.NOW).get();
        database.write(context -> {
            context.execute("insert into \"user\" (id, name, created_at) values (2, 'guest', 1)");
            return null;
        });
        saved.saveOrGet(2L, bookId, TestData.NOW).get();

        assertEquals(List.of(movieId),
                saved.queue(1L, QueueFilter.all()).get().map(entry -> entry.item().id()));
        assertEquals(List.of(bookId),
                saved.queue(2L, QueueFilter.all()).get().map(entry -> entry.item().id()));
    }

    @Test
    void favoritesIsTheFavoriteQueue() {
        long movie = saved.saveOrGet(1L, movieId, TestData.NOW).get();
        saved.saveOrGet(1L, bookId, TestData.NOW + 10).get();
        saved.setFavorite(movie, true, TestData.NOW + 20).get();

        assertEquals(List.of(movieId),
                saved.favorites(1L).get().map(entry -> entry.item().id()));
    }

    @Test
    void statusCountsAndFormCountsReflectTheLibrary() {
        long movie = saved.saveOrGet(1L, movieId, TestData.NOW).get();
        long book = saved.saveOrGet(1L, bookId, TestData.NOW).get();
        saved.setStatus(book, "progress").get();
        saved.setFavorite(movie, true, TestData.NOW).get();

        assertEquals(Map.of("want", 1, "progress", 1), saved.statusCounts(1L).get());
        assertEquals(Map.of("movie", 1, "book", 1), saved.formCounts(1L).get());
    }

    private SavedEntry find(long savedId) {
        return saved.queue(1L, QueueFilter.all()).get()
                .find(entry -> entry.savedId() == savedId)
                .getOrElseThrow(() -> new AssertionError("no saved row " + savedId));
    }
}
