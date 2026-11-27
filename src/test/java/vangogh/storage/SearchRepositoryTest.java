package vangogh.storage;

import io.vavr.collection.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vangogh.storage.model.SearchEntry;
import vangogh.storage.model.SearchPick;
import vangogh.storage.model.SearchPickDraft;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SearchRepositoryTest {

    @TempDir
    Path temp;

    private Database database;
    private SearchRepository searches;
    private SearchItemRepository picks;

    @BeforeEach
    void setUp() {
        database = Database.open(temp.resolve("searches.db"));
        searches = new SearchRepository(database);
        picks = new SearchItemRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void recordWritesTheSearchAndItsPicksInOneGo() {
        long id = searches.record(1L, "heist films", "movie,book", TestData.NOW,
                List.of(draft(0, "Heat", ContentForm.MOVIE),
                        draft(1, "Dune", ContentForm.BOOK))).get();

        SearchEntry entry = byId(id);
        assertEquals("heist films", entry.query());
        assertEquals(TestData.NOW, entry.ranAt());
        assertNull(entry.savedAt());
        assertEquals("movie,book", entry.loadedForms());

        List<SearchPick> stored = picks.bySearch(id).get();
        assertEquals(List.of("Heat", "Dune"), stored.map(SearchPick::title));
        assertEquals(List.of(ContentForm.MOVIE, ContentForm.BOOK), stored.map(SearchPick::form));
    }

    @Test
    void picksCarryTheResolvedItemAndItsReason() {
        long itemId = new ItemRepository(database)
                .upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        long id = searches.record(1L, "heat", "movie", TestData.NOW,
                List.of(new SearchPickDraft(ContentForm.MOVIE, "Heat", "same mood", 0, itemId))).get();

        SearchPick pick = picks.bySearch(id).get().head();
        assertEquals(itemId, pick.itemId());
        assertEquals("same mood", pick.reason());
        assertEquals(0, pick.position());
    }

    @Test
    void replaceSwapsThePickListAndKeepsTheSearchRow() {
        long id = searches.record(1L, "q", "movie", TestData.NOW,
                List.of(draft(0, "Heat", ContentForm.MOVIE),
                        draft(1, "Dune", ContentForm.BOOK))).get();

        assertEquals(1, picks.replace(id, List.of(draft(0, "Alien", ContentForm.MOVIE))).get());

        assertEquals(List.of("Alien"), picks.bySearch(id).get().map(SearchPick::title));
        assertEquals(id, byId(id).id());
    }

    @Test
    void linkItemAttachesALateResolution() {
        long id = searches.record(1L, "q", "movie", TestData.NOW,
                List.of(draft(0, "Heat", ContentForm.MOVIE))).get();
        SearchPick before = picks.bySearch(id).get().head();
        assertNull(before.itemId());

        long itemId = new ItemRepository(database)
                .upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        assertTrue(picks.linkItem(before.id(), itemId).get());

        assertEquals(itemId, picks.bySearch(id).get().head().itemId());
    }

    @Test
    void deletingAnItemClearsThePickLinkButKeepsTheRow() {
        long itemId = new ItemRepository(database)
                .upsert(TestData.item("tmdb:1", ContentForm.MOVIE, "Heat")).get();
        long id = searches.record(1L, "q", "movie", TestData.NOW,
                List.of(new SearchPickDraft(ContentForm.MOVIE, "Heat", null, 0, itemId))).get();

        database.write(context -> context.execute("delete from item where id = " + itemId));

        assertEquals(1, picks.bySearch(id).get().size());
        assertNull(picks.bySearch(id).get().head().itemId());
    }

    @Test
    void recentIsNewestFirstAndLimited() {
        searches.record(1L, "first", "movie", TestData.NOW, List.empty()).get();
        searches.record(1L, "second", "movie", TestData.NOW + 10, List.empty()).get();
        searches.record(1L, "third", "movie", TestData.NOW + 20, List.empty()).get();

        assertEquals(List.of("third", "second", "first"),
                searches.recent(1L, 10).get().map(SearchEntry::query));
        assertEquals(List.of("third"), searches.recent(1L, 1).get().map(SearchEntry::query));
    }

    @Test
    void savedHoldsOnlyMarkedSearches() {
        long kept = searches.record(1L, "kept", "movie", TestData.NOW, List.empty()).get();
        searches.record(1L, "dropped", "movie", TestData.NOW + 10, List.empty()).get();

        assertTrue(searches.markSaved(kept, TestData.NOW + 20).get());

        assertEquals(List.of("kept"), searches.saved(1L).get().map(SearchEntry::query));
        assertEquals(TestData.NOW + 20, byId(kept).savedAt());
    }

    @Test
    void duplicatesPositionsAreRejected() {
        long id = searches.record(1L, "q", "movie", TestData.NOW, List.empty()).get();

        assertTrue(picks.replace(id, List.of(
                draft(0, "Heat", ContentForm.MOVIE),
                draft(0, "Dune", ContentForm.BOOK))).isLeft());
    }

    private SearchEntry byId(long id) {
        return searches.recent(1L, 100).get()
                .find(entry -> entry.id() == id)
                .getOrElseThrow(() -> new AssertionError("no search " + id));
    }

    private static SearchPickDraft draft(int position, String title, ContentForm form) {
        return new SearchPickDraft(form, title, null, position, null);
    }
}
