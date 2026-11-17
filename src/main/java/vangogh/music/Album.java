package vangogh.music;

/**
 * One music card: a MusicBrainz release-group (an album, EP, or single).
 * {@code id} is the release-group MBID — the Cover Art Archive serves the
 * cover art by it. There is no numeric rating in MusicBrainz search results;
 * {@code genre} carries the community tags instead.
 */
public record Album(
        String id,
        String title,
        String artist,
        int year,
        String primaryType,
        String genre,
        String posterUrl
) {
}
