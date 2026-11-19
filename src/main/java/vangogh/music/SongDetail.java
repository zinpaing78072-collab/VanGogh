package vangogh.music;

/**
 * Everything the song-detail page shows for one song.
 *
 * <p>Real data comes from Genius' {@code /songs/:id}: title, artist, album,
 * release date, writers, producers, featured artists, pageviews, the Genius
 * URL and the related-song relationships. Nothing on this page is fetched
 * synchronously — the controller resolves this on the IO pool and hands it to
 * the page through the {@code pollSongDetail} drain.
 *
 * <p>Lyrics, duration and audio are <b>dummy</b> for now ({@link DummyMedia}):
 * the Genius API serves no lyrics text, no track length and no stream. Swapping
 * in a real source later means replacing those three fields only.
 */
public record SongDetail(
        String id,
        String title,
        String artist,
        String album,
        String releaseDate,
        int year,
        java.util.List<String> writers,
        java.util.List<String> producers,
        java.util.List<String> featuredArtists,
        String geniusUrl,
        long pageviews,
        String lyricsState,
        String artUrl,
        java.util.List<LyricsLine> lyrics,
        int durationSeconds,
        String audioUrl,
        java.util.List<RelatedSong> related
) {
}
