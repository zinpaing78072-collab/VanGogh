package vangogh.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Tolerant parse of the {@code title | disambiguator} contract. A decorated
 * line ("1. X | 2010", "- X | 2010", "\"X\" | 2010") still yields a pick and
 * counts as one violation; a line without a pipe yields a title-only pick and
 * counts as another; commentary is dropped and counted.
 *
 * <p>Violations are reported separately from resolutions on purpose: a
 * violation is the model misbehaving, an unresolved title is the catalog
 * disagreeing. Mixing them would hide which side to fix.
 *
 * <p>Ported verbatim from the connection-tester probe; the cached replies in
 * that project include every branch this parser handles.
 */
public final class TitleParser {

    public record Parsed(List<Pick> picks, int violations) {
    }

    private static final int MAX_TITLE_LENGTH = 120;

    private TitleParser() {
    }

    public static Parsed parse(String raw, int max) {
        List<Pick> picks = new ArrayList<>();
        int violations = 0;

        if (raw == null || raw.isBlank()) {
            return new Parsed(picks, violations);
        }

        List<String> lines = raw.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();

        // One comma-separated blob instead of one pick per line: a format
        // violation, but the titles in it are still usable.
        if (lines.size() == 1 && lines.get(0).contains(", ") && !lines.get(0).contains("|")) {
            for (String part : lines.get(0).split("\\s*,\\s*")) {
                if (!part.isBlank()) {
                    picks.add(new Pick(part.strip(), null));
                }
            }
            return new Parsed(cap(picks, max), violations + 1);
        }

        for (String line : lines) {
            String title = line;
            boolean decorated = false;

            String bulletStripped = title.replaceFirst("^\\s*(?:[-*•]+|\\d+[.)])\\s+", "");
            if (!bulletStripped.equals(title)) {
                decorated = true;
                title = bulletStripped.strip();
            }

            String unquoted = title.replaceFirst("^[\"'“”‘’](.*)[\"'“”‘’]$", "$1");
            if (!unquoted.equals(title)) {
                decorated = true;
                title = unquoted.strip();
            }

            if (title.isEmpty()) {
                continue;
            }

            // Commentary: too long to be a pick, or an announcement ending in a colon.
            if (title.length() > MAX_TITLE_LENGTH || title.endsWith(":")) {
                violations++;
                continue;
            }

            int pipe = title.lastIndexOf('|');
            String name = pipe >= 0 ? title.substring(0, pipe).strip() : title;
            String disambiguator = pipe >= 0 ? title.substring(pipe + 1).strip() : null;
            if (disambiguator != null) {
                disambiguator = disambiguator.replaceFirst("^[\"'“”‘’](.*)[\"'“”‘’]$", "$1").strip();
                if (disambiguator.isEmpty()) {
                    disambiguator = null;
                }
            }

            if (name.isEmpty()) {
                violations++;
                continue;
            }

            if (decorated) {
                violations++;
            }
            if (pipe < 0) {
                violations++;
            }
            picks.add(new Pick(name, disambiguator));
        }

        return new Parsed(cap(picks, max), violations);
    }

    private static List<Pick> cap(List<Pick> picks, int max) {
        return picks.size() <= max ? picks : new ArrayList<>(picks.subList(0, max));
    }
}
