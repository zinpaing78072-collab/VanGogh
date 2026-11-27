package vangogh.storage;

import io.vavr.collection.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.storage.model.NewItem;
import vangogh.storage.model.Poster;
import vangogh.storage.model.StoredItem;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ItemRepositoryTest {

    @TempDir
    Path temp;

    private Database database;
    private ItemRepository items;

    @BeforeEach
    void setUp() {
        database = Database.open(temp.resolve("items.db"));
        items = new ItemRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void upsertInsertsAndThenReadsBackByIdAndSource() {
        long id = items.upsert(TestData.item("tmdb:27205", ContentForm.MOVIE, "Inception")).get();

        StoredItem byId = items.byId(id).get().get();
        StoredItem bySource = items.bySource("tmdb:27205").get().get();

        assertEquals(byId, bySource);
        assertEquals("tmdb:27205", byId.source());
        assertEquals(ContentForm.MOVIE, byId.form());
        assertEquals("Inception", byId.title());
        assertEquals(TestData.NOW, byId.fetchedAt());
        assertNull(byId.posterMime());
    }

    @Test
    void missesAreEmptyNotErrors() {
        assertTrue(items.byId(404L).get().isEmpty());
        assertTrue(items.bySource("nope").get().isEmpty());
    }

    @Test
    void upsertRefreshesMetadataButKeepsThePosterAndExtra() {
        long id = items.upsert(TestData.item("tmdb:27205", ContentForm.MOVIE, "Inception")).get();
        byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);
        assertTrue(items.writePoster(id, bytes, "image/png").get());
        assertTrue(items.writeExtra(id, "{\"runtime\":148}").get());

        long later = TestData.NOW + 60;
        long again = items.upsert(new NewItem(
                "tmdb:27205", ContentForm.MOVIE, "Inception (2010)", "{\"year\":2010}", later)).get();

        assertEquals(id, again);
        StoredItem refreshed = items.byId(id).get().get();
        assertEquals("Inception (2010)", refreshed.title());
        assertEquals("{\"year\":2010}", refreshed.metadata());
        assertEquals(later, refreshed.fetchedAt());
        assertEquals("image/png", refreshed.posterMime());
        assertArrayEquals(bytes, items.poster(id).get().get().bytes());
        assertEquals("{\"runtime\":148}", items.extra(id).get().get());
    }

    @Test
    void posterRoundTripsWithItsMime() {
        long id = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        byte[] bytes = {1, 2, 3, 4};

        assertTrue(items.writePoster(id, bytes, "image/jpeg").get());

        Poster poster = items.poster(id).get().get();
        assertArrayEquals(bytes, poster.bytes());
        assertEquals("image/jpeg", poster.mime());
    }

    @Test
    void extraRoundTrips() {
        long id = items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();

        assertTrue(items.writeExtra(id, "{\"overview\":\"heist\"}").get());

        assertEquals("{\"overview\":\"heist\"}", items.extra(id).get().get());
    }

    @Test
    void byFormFiltersAndOrdersByTitle() {
        items.upsert(TestData.item("tmdb:2", ContentForm.MOVIE, "Zodiac")).get();
        items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Alien")).get();
        items.upsert(TestData.item("openlibrary:OL1W", ContentForm.BOOK, "Dune")).get();

        List<StoredItem> movies = items.byForm(ContentForm.MOVIE, 10).get();
        List<StoredItem> books = items.byForm(ContentForm.BOOK, 10).get();

        assertEquals(List.of("Alien", "Zodiac"), movies.map(StoredItem::title));
        assertEquals(List.of("Dune"), books.map(StoredItem::title));
    }

    @Test
    void byFormLimitIsApplied() {
        items.upsert(TestData.item("tmdb:2", ContentForm.MOVIE, "Zodiac")).get();
        items.upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Alien")).get();

        assertEquals(1, items.byForm(ContentForm.MOVIE, 1).get().size());
    }

    @Test
    void writePosterOnAMissingRowReportsFalse() {
        assertFalse(items.writePoster(999L, new byte[]{1}, "image/png").get());
    }
}
