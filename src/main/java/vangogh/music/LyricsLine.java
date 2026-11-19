package vangogh.music;

/**
 * One line of the song-detail lyrics panel: the timestamp shown in the gutter
 * and the line itself. Real lyrics delivery is a later slice (Genius' API
 * serves no lyrics text), so today every line comes from {@link DummyMedia}.
 */
public record LyricsLine(String time, String text) {
}
