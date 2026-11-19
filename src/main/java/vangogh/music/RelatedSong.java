package vangogh.music;

/**
 * One row in the song-detail "Related Songs" rail. The id/title/artist/poster
 * come from Genius' {@code song_relationships} payload (covers, remixes,
 * samples); {@code durationSeconds} is dummy data like the song's own duration.
 */
public record RelatedSong(
        String id,
        String title,
        String artist,
        String relation,
        String posterUrl,
        int durationSeconds
) {
}
