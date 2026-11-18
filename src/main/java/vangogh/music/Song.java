package vangogh.music;

/**
 * One music card backed by Genius: a song. Mirrors {@link Album}'s shape so the
 * music card renderer keeps working.
 */
public record Song(
        String id,
        String title,
        String artist,
        int year,
        String primaryType,
        String genre,
        String posterUrl
) {
}