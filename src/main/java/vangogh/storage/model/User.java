package vangogh.storage.model;

/** The single local identity row. Accounts/login are a later slice. */
public record User(long id, String name, long createdAt) {
}
