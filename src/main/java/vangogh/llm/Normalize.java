package vangogh.llm;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The title comparison key shared by every resolver: case, punctuation,
 * typography, accents and a leading article do not make two works different.
 *
 * <p>{"The Dark Knight"} and {"dark knight"} are the same work; so are
 * {"Amélie"} / {"amelie"} and {"Fast & Furious"} / {"fast and furious"}.
 * Ported verbatim from the probe.
 */
public final class Normalize {

    private Normalize() {
    }

    public static String key(String value) {
        if (value == null) {
            return "";
        }
        String text = value
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace("&", " and ");
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        text = text.toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9]+", " ").trim();
        text = text.replaceFirst("^the ", "");
        return text;
    }
}
