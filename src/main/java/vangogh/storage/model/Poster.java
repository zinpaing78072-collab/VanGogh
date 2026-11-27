package vangogh.storage.model;

/** Offline card artwork read from {@code item.poster}, with its MIME label. */
public record Poster(byte[] bytes, String mime) {
}
