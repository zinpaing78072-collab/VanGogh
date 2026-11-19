package vangogh.llm;

/**
 * One parsed line of a model reply: the searchable title plus the
 * disambiguator that tells the resolver which work was meant (release year,
 * artist or author).
 *
 * <p>The disambiguator is nullable — a line without a pipe still yields a
 * title and counts as a format violation. {@link #year()} extracts a four-digit
 * year when the disambiguator carries one (the resolver decides whether it
 * matters for its form).
 */
public record Pick(String title, String disambiguator) {

    public Integer year() {
        if (disambiguator == null) {
            return null;
        }
        String digits = disambiguator.replaceAll("[^0-9]", "");
        if (digits.length() != 4) {
            return null;
        }
        return Integer.valueOf(digits);
    }
}
