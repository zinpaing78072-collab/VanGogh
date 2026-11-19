package vangogh.music;

/**
 * Everything the song-detail page shows for one song.
 *
 * <p>Real data comes from Genius' {@code /songs/:id}: title, artist, album,
 * release date, writers, producers, featured artists, pageviews, the Genius
 * URL and the related-song relationships, plus the full credit bill
 * ({@code custom_performances}), the editorial {@code description} (About this
 * song), the listen/watch {@code media} links, community stats, the language
 * and its translation count, and the artwork's own colour palette. Nothing on
 * this page is fetched synchronously — the controller resolves this on the IO
 * pool and hands it to the page through the {@code pollSongDetail} drain.
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
        java.util.List<RelatedSong> related,

        // Tier 1 — fields the designed page already had slots for.
        /** Genius' human date ("October 21, 2022"); falls back to {@link #releaseDate}. */
        String releaseDateDisplay,
        /** Display name for Genius' language code ("English"). */
        String language,
        /** Songs in the {@code translations} relationship — the count, not the texts. */
        int translationCount,
        /** Genius' "all related songs" page, behind the rail's View all link. */
        String relationshipsIndexUrl,
        /** Full label → names bill from {@code custom_performances}. */
        java.util.List<Credit> credits,
        /** Outbound listen/watch links ({@code media}); no audio is streamed here. */
        java.util.List<MediaLink> media,
        /** Artwork palette; primary and secondary tint the hero border and tag. */
        String artPrimaryColor,
        String artSecondaryColor,
        /** Text colour Genius pairs with that artwork (carried for overlay text). */
        String artTextColor,

        // Tier 2 — extra identity and context.
        /** Flattened {@code description} prose, paragraphs separated by blank lines. */
        String about,
        String albumUrl,
        String albumCoverUrl,
        String artistImageUrl,
        String artistUrl,
        boolean artistVerified,
        String recordingLocation,
        int contributors,
        int transcribers,
        int acceptedAnnotations,
        int annotationCount,
        int pyongsCount
) {
}
