package vangogh.game;

/**
 * Everything the game-detail page shows for one TheGamesDB record.
 *
 * <p>TheGamesDB has no single rich detail endpoint: the identity, record and
 * stat band come from {@code Games/ByGameID}, the fanart / screenshots / boxart
 * from {@code Games/Images}, the publisher and developer names from the
 * {@code Publishers} / {@code Developers} dictionaries (the game payload only
 * carries their ids), and the "more in this series" rail from a
 * {@code Games/ByGameName} search seeded with the title. {@link
 * GameDetailMapper} folds those payloads into this record, and the controller
 * resolves the whole thing on the IO pool so the page never blocks.
 *
 * <p>Only what the record actually carries is exposed here: no developer is
 * invented, {@code rating} stays the API's string ({@code "M - Mature 17+"},
 * {@code "Not Rated"}) rather than a score, and there is no price, playtime or
 * store link to show.
 *
 * <p>Images live on {@code cdn.thegamesdb.net}, which is not reachable from
 * every network (see AGENTS.md "Poster proxy"), so they stream as data URIs
 * through the {@code gameDetailArt} event. The page paints them by slot —
 * {@code boxart}, {@code hero}, {@code still:<i>}, {@code related:<id>} — where
 * the index is the entry's position in the lists below.
 */
public record GameDetail(
        int id,
        String title,
        /** Raw TheGamesDB release date ({@code "2018-08-01"}), empty when unknown. */
        String releaseDate,
        /** Release year parsed from {@link #releaseDate}, 0 when unknown. */
        int year,
        /** The API's rating string, never parsed into a score. May be empty. */
        String rating,
        /** Players from the record, 0 when it does not say. */
        int players,
        java.util.List<String> genres,
        java.util.List<String> platforms,
        java.util.List<String> publishers,
        java.util.List<String> developers,
        String overview,
        String youtubeUrl,
        /** The record's public TheGamesDB page. */
        String recordUrl,
        /** Front boxart URL (original resolution); empty when the record has none. */
        String boxartUrl,
        /** Fanart when the record has it, else its first screenshot; may be empty. */
        String heroUrl,
        /** Up to six screenshots, in record order, at medium resolution. */
        java.util.List<String> stillUrls,
        /** The rail's record sheet, in display order. */
        java.util.List<Fact> facts,
        /** Where the rail came from: {@code "series"} or {@code "catalogue"}. */
        String relatedSource,
        /** Up to six related-game cards; the rail hides itself when empty. */
        java.util.List<Related> related
) {
    /** One row of the record sheet. */
    public record Fact(String label, String value) {}

    /**
     * A related-game card. {@code posterUrl} is the medium boxart; it may be
     * empty, in which case the page keeps its placeholder.
     */
    public record Related(
            int id,
            String title,
            int year,
            String rating,
            String genre,
            String posterUrl
    ) {}
}
