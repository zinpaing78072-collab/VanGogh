package vangogh.music;

/**
 * An external listen/watch link from Genius' {@code media} array
 * (SoundCloud, YouTube, Spotify, ...). Genius serves no audio, so these are
 * links out: the detail page keeps only the YouTube entries and plays them in
 * the shared in-page player; the other providers stay in the payload but are
 * not offered. {@code startSeconds} is the timestamp Genius records for a
 * video link (0 when absent).
 */
public record MediaLink(String provider, String type, String url, int startSeconds) {
}
