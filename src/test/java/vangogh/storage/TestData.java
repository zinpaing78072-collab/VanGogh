package vangogh.storage;

import vangogh.storage.model.NewItem;
import vangogh.storage.model.QueueFilter;

/** Shared fixtures for the storage tests. */
final class TestData {

    static final long NOW = 1_700_000_000L;

    private TestData() {
    }

    static NewItem item(String source, ContentForm form, String title) {
        return new NewItem(source, form, title, "{\"creator\":\"test\"}", NOW);
    }

    static QueueFilter all() {
        return QueueFilter.all();
    }
}
