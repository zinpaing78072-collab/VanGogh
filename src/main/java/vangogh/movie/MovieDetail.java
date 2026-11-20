package vangogh.movie;

/**
 * Everything the movie-detail page shows for one film.
 *
 * <p>Real data comes from TMDB's {@code /movie/:id} with
 * {@code append_to_response=credits,keywords,similar,images,videos,release_dates}:
 * the identity block (title, tagline, year, runtime, certification, score),
 * the facts ledger, the keywords, the cast &amp; crew headshots, the artwork
 * stills and the "more like this" rail. Nothing on this page is fetched
 * synchronously — the controller resolves this on the IO pool and hands it to
 * the page through the {@code pollMovieDetail} drain.
 *
 * <p>Headshots, stills and posters cannot be inline TMDB URLs: {@code
 * image.tmdb.org} is unreachable from some networks (see AGENTS.md "Poster
 * proxy"), so the controller streams each one as a data URI through the
 * {@code movieDetailArt} event. The page paints them by slot — {@code poster},
 * {@code cast:<i>}, {@code still:<i>}, {@code similar:<id>} — where the index
 * is the entry's position in these lists.
 */
public record MovieDetail(
        int id,
        String title,
        String tagline,
        String overview,
        /** TMDB's raw ISO date ("2017-10-04"); the facts ledger carries the display form. */
        String releaseDate,
        int year,
        int runtimeMinutes,
        /** US certification from {@code release_dates} (e.g. "R"); empty when TMDB has none. */
        String certification,
        double rating,
        int voteCount,
        java.util.List<String> genres,
        java.util.List<String> keywords,
        /** TMDB poster URL (w500 path); streamed as a data URI in slot "poster". */
        String posterUrl,
        String tmdbUrl,
        /** YouTube trailer/watch page when TMDB carries one, else empty. */
        String trailerUrl,
        /** The Details ledger, in display order. */
        java.util.List<Fact> facts,
        /** Up to eight cast cards plus the director and cinematographer, in display order. */
        java.util.List<MovieCredit> cast,
        /** Up to four stills from {@code images.backdrops}, in display order. */
        java.util.List<MovieStill> stills,
        /** Up to six {@code similar.results}, in TMDB's order. */
        java.util.List<SimilarMovie> similar
) {
    /** One row of the Details ledger. {@code muted} is the prototype's secondary row. */
    public record Fact(String label, String value, boolean muted) {}

    /** A cast or crew card: headshot slot, name and role. */
    public record MovieCredit(String name, String role, boolean crew, String imageUrl) {}

    /** One artwork still; the caption is empty when TMDB supplies none. */
    public record MovieStill(String caption, String imageUrl) {}

    /** A related film plus the poster URL its card streams. */
    public record SimilarMovie(Movie movie, String posterUrl) {}
}
