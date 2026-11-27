package vangogh.storage;

import io.vavr.control.Option;

/**
 * The four content forms as the storage schema spells them. Deliberately not
 * {@link vangogh.llm.Form}: that enum is prompt vocabulary ({@code MUSIC}'s noun
 * is "song"), and the wire value stored in {@code item.form} is "music".
 *
 * <p>An adapter maps {@code llm.Form} to this enum when the recommendation
 * engine starts writing to storage.
 */
public enum ContentForm {

    MOVIE("movie"),
    MUSIC("music"),
    BOOK("book"),
    GAME("game");

    private final String wire;

    ContentForm(String wire) {
        this.wire = wire;
    }

    /** The value stored in {@code item.form} / {@code search_item.form}. */
    public String wire() {
        return wire;
    }

    /** Parses a stored value; empty when the database holds something unknown. */
    public static Option<ContentForm> fromWire(String value) {
        if (value == null) {
            return Option.none();
        }
        for (ContentForm form : values()) {
            if (form.wire.equals(value)) {
                return Option.some(form);
            }
        }
        return Option.none();
    }
}
