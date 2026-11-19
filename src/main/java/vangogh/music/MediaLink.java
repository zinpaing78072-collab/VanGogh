package vangogh.music;

/**
 * An external listen/watch link from Genius' {@code media} array
 * (SoundCloud, YouTube, Spotify, ...). These are links out, not streams: the
 * Genius API serves no audio. {@code startSeconds} is the timestamp Genius
 * records for a video link (0 when absent).
 */
public record MediaLink(String provider, String type, String url, int startSeconds) {
}
