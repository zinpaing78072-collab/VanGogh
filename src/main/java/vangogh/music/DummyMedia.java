package vangogh.music;

/**
 * Placeholder payloads for the three song-detail fields no current source can
 * fill: lyrics text (Genius' API only links to the lyric page), track duration
 * (not in the API) and audio (no stream). The page labels them as demo data;
 * replacing this class with real clients (LRCLIB for timed lyrics, iTunes for
 * duration/preview) is the follow-up slice.
 */
final class DummyMedia {

    /** 4:12 — the length the design uses; visibly a placeholder, not a guess. */
    static final int DURATION_SECONDS = 252;

    /** No audio source is wired, so playback is a UI-only timer. */
    static final String AUDIO_URL = "";

    static final java.util.List<LyricsLine> LYRICS = java.util.List.of(
            new LyricsLine("0:00", "Demo lyrics — the lyrics source is not connected yet."),
            new LyricsLine("0:12", "Starlight on the water, city lights below,"),
            new LyricsLine("0:20", "we were only passing through the afterglow."),
            new LyricsLine("0:28", "Every quiet corner keeps a little flame,"),
            new LyricsLine("0:36", "every open window calls me by my name."),
            new LyricsLine("0:46", "Hold the night a little longer,"),
            new LyricsLine("0:54", "let the morning wait its turn."),
            new LyricsLine("1:02", "We are made of borrowed light and slow-burning embers,"),
            new LyricsLine("1:12", "painted in the colours that the sky remembers."),
            new LyricsLine("1:24", "Chorus — demo copy only, timings are illustrative."),
            new LyricsLine("1:36", "Nothing here is the published lyric text."),
            new LyricsLine("1:52", "The real lyrics arrive with the lyrics slice."),
            new LyricsLine("2:08", "Until then the panel shows this placeholder set."),
            new LyricsLine("2:30", "Demo lyrics — end of placeholder.")
    );

    private DummyMedia() {
    }

    /** Stable per-related-song placeholder length, 2:30–4:29. */
    static int relatedDurationSeconds(String id) {
        return 150 + Math.floorMod(String.valueOf(id).hashCode(), 120);
    }
}
