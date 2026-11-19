package vangogh.music;

/**
 * One labeled credits row from Genius' {@code custom_performances} — the full
 * production bill ("Mixing Engineer", "Label", "Video Director", ...). The
 * detail page shows writers/producers up front and keeps the rest behind the
 * "all credits" expander.
 */
public record Credit(String label, java.util.List<String> names) {
}
