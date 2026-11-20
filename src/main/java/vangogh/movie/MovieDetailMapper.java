package vangogh.movie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.vavr.control.Either;
import io.vavr.control.Try;

import vangogh.error.FetchError;
import vangogh.net.TmdbClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps TMDB's {@code /movie/:id} payload (with the appended credits, keywords,
 * similar, images, videos and release_dates blocks) onto {@link MovieDetail}.
 *
 * <p>This is the one place that knows TMDB's field names; the record stays a
 * plain data carrier and the controller never parses JSON. A payload that is
 * not a JSON object at all becomes {@link FetchError.Payload}; a payload with
 * missing or null fields still maps — every collector skips what it cannot
 * read, so a sparse movie renders a shorter page rather than an error.
 */
final class MovieDetailMapper {

    private static final DateTimeFormatter RELEASE_DATE =
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
    private static final int MAX_CAST = 8;
    private static final int MAX_STILLS = 4;
    private static final int MAX_SIMILAR = 6;
    private static final int MAX_GROUPS = 3;

    private MovieDetailMapper() {
    }

    static Either<FetchError, MovieDetail> parse(String body, int fallbackId) {
        return Try.of(() -> map(JsonParser.parseString(body).getAsJsonObject(), fallbackId))
                .toEither()
                .mapLeft(e -> new FetchError.Payload("TMDB", "movie detail shape", e));
    }

    private static MovieDetail map(JsonObject root, int fallbackId) {
        int id = intAt(root, "id").getOrElse(fallbackId);
        String release = str(root, "release_date");

        JsonObject credits = objectAt(root, "credits");
        JsonArray castRows = arrayAt(credits, "cast");
        JsonArray crewRows = arrayAt(credits, "crew");

        List<String> directors = crewNames(crewRows, "Director");
        List<String> writers = crewNames(crewRows, "Screenplay", "Writer");
        List<String> cinematographers = crewNames(crewRows, "Director of Photography");
        List<String> composers = crewNames(crewRows, "Original Music Composer");
        List<String> studios = groupNames(root, "production_companies", MAX_GROUPS);
        List<String> countries = groupNames(root, "production_countries", MAX_GROUPS);

        int runtime = intAt(root, "runtime").getOrElse(0);
        String certification = certification(root);
        String posterPath = str(root, "poster_path");

        return new MovieDetail(
                id,
                titleOf(root),
                str(root, "tagline"),
                str(root, "overview"),
                release,
                yearOf(release),
                runtime,
                certification,
                doubleAt(root, "vote_average").getOrElse(0.0),
                intAt(root, "vote_count").getOrElse(0),
                genreNames(root),
                keywordNames(root),
                imageUrl(posterPath),
                "https://www.themoviedb.org/movie/" + id,
                trailerUrl(root),
                facts(release, runtime, certification, str(root, "status"),
                        directors, writers, cinematographers, composers, studios, countries,
                        longAt(root, "budget").getOrElse(0L),
                        longAt(root, "revenue").getOrElse(0L)),
                castCards(castRows, crewRows, directors, cinematographers),
                stills(root),
                similar(root, id));
    }

    // --------------------------------------------------------------- identity

    private static String titleOf(JsonObject root) {
        String title = str(root, "title");
        return title.isEmpty() ? "Untitled" : title;
    }

    private static List<String> genreNames(JsonObject root) {
        List<String> names = new ArrayList<>();
        JsonArray rows = arrayAt(root, "genres");
        for (int i = 0; i < rows.size(); i++) {
            String name = str(rows.get(i).getAsJsonObject(), "name");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> keywordNames(JsonObject root) {
        List<String> names = new ArrayList<>();
        JsonArray rows = arrayAt(objectAt(root, "keywords"), "keywords");
        for (int i = 0; i < rows.size() && names.size() < 8; i++) {
            String name = str(rows.get(i).getAsJsonObject(), "name");
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ facts

    private static List<MovieDetail.Fact> facts(
            String release, int runtime, String certification, String status,
            List<String> directors, List<String> writers, List<String> cinematographers,
            List<String> composers, List<String> studios, List<String> countries,
            long budget, long revenue) {

        List<MovieDetail.Fact> facts = new ArrayList<>();
        addFact(facts, "Release", releaseDisplay(release), false);
        addFact(facts, "Runtime", runtimeDisplay(runtime), false);
        addFact(facts, "Director", join(directors), false);
        addFact(facts, "Screenplay", join(writers), true);
        addFact(facts, "Cinematography", join(cinematographers), true);
        addFact(facts, "Music", join(composers), true);
        addFact(facts, "Studio", join(studios), true);
        addFact(facts, "Country", join(countries), true);
        addFact(facts, "Budget", money(budget), false);
        addFact(facts, "Box office", money(revenue), false);
        addFact(facts, "Certification", certification, false);
        addFact(facts, "Status", status, false);
        return facts;
    }

    private static void addFact(List<MovieDetail.Fact> facts, String label, String value, boolean muted) {
        if (value != null && !value.isEmpty()) {
            facts.add(new MovieDetail.Fact(label, value, muted));
        }
    }

    private static String releaseDisplay(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        try {
            return LocalDate.parse(iso).format(RELEASE_DATE);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static int yearOf(String iso) {
        if (iso == null || iso.length() < 4) {
            return 0;
        }
        String head = iso.substring(0, 4);
        return head.chars().allMatch(Character::isDigit) ? Integer.parseInt(head) : 0;
    }

    private static String runtimeDisplay(int minutes) {
        if (minutes <= 0) {
            return "";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    private static String money(long amount) {
        if (amount <= 0) {
            return "";
        }
        if (amount >= 1_000_000_000L) {
            return "$" + trimZero(amount / 1_000_000_000.0) + "B";
        }
        if (amount >= 1_000_000L) {
            return "$" + trimZero(amount / 1_000_000.0) + "M";
        }
        if (amount >= 1_000L) {
            return "$" + trimZero(amount / 1_000.0) + "K";
        }
        return "$" + amount;
    }

    private static String trimZero(double value) {
        String text = String.format(Locale.US, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String certification(JsonObject root) {
        JsonArray rows = arrayAt(objectAt(root, "release_dates"), "results");
        String usAny = "";
        String any = "";
        for (int i = 0; i < rows.size(); i++) {
            JsonObject country = rows.get(i).getAsJsonObject();
            String countryCode = str(country, "iso_3166_1");
            JsonArray dates = arrayAt(country, "release_dates");
            for (int j = 0; j < dates.size(); j++) {
                JsonObject entry = dates.get(j).getAsJsonObject();
                String cert = str(entry, "certification");
                if (cert.isEmpty()) {
                    continue;
                }
                if ("US".equals(countryCode)) {
                    if (intAt(entry, "type").getOrElse(0) == 3) {
                        return cert;
                    }
                    if (usAny.isEmpty()) {
                        usAny = cert;
                    }
                } else if (any.isEmpty()) {
                    any = cert;
                }
            }
        }
        return usAny.isEmpty() ? any : usAny;
    }

    private static String trailerUrl(JsonObject root) {
        JsonArray rows = arrayAt(objectAt(root, "videos"), "results");
        String teaser = "";
        for (int i = 0; i < rows.size(); i++) {
            JsonObject entry = rows.get(i).getAsJsonObject();
            if (!"YouTube".equalsIgnoreCase(str(entry, "site"))) {
                continue;
            }
            String key = str(entry, "key");
            if (key.isEmpty()) {
                continue;
            }
            String type = str(entry, "type");
            if ("Trailer".equalsIgnoreCase(type)) {
                return "https://www.youtube.com/watch?v=" + key;
            }
            if (teaser.isEmpty() && "Teaser".equalsIgnoreCase(type)) {
                teaser = "https://www.youtube.com/watch?v=" + key;
            }
        }
        return teaser;
    }

    // ------------------------------------------------------------ cast & crew

    private static List<MovieDetail.MovieCredit> castCards(
            JsonArray castRows, JsonArray crewRows,
            List<String> directors, List<String> cinematographers) {

        List<MovieDetail.MovieCredit> cards = new ArrayList<>();
        for (int i = 0; i < castRows.size() && cards.size() < MAX_CAST; i++) {
            JsonObject row = castRows.get(i).getAsJsonObject();
            String name = str(row, "name");
            if (name.isEmpty()) {
                continue;
            }
            cards.add(new MovieDetail.MovieCredit(
                    name, str(row, "character"), false, imageUrl(str(row, "profile_path"))));
        }
        if (!directors.isEmpty()) {
            cards.add(new MovieDetail.MovieCredit(
                    join(directors), "Director", true, crewImage(crewRows, "Director")));
        }
        if (!cinematographers.isEmpty()) {
            cards.add(new MovieDetail.MovieCredit(
                    join(cinematographers), "Cinematography", true,
                    crewImage(crewRows, "Director of Photography")));
        }
        return cards;
    }

    private static String crewImage(JsonArray crewRows, String job) {
        for (int i = 0; i < crewRows.size(); i++) {
            JsonObject row = crewRows.get(i).getAsJsonObject();
            if (job.equals(str(row, "job"))) {
                return imageUrl(str(row, "profile_path"));
            }
        }
        return "";
    }

    private static List<String> crewNames(JsonArray crewRows, String... jobs) {
        Set<String> wanted = Set.of(jobs);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int i = 0; i < crewRows.size(); i++) {
            JsonObject row = crewRows.get(i).getAsJsonObject();
            String name = str(row, "name");
            if (!name.isEmpty() && wanted.contains(str(row, "job"))) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static List<String> groupNames(JsonObject root, String key, int limit) {
        List<String> names = new ArrayList<>();
        JsonArray rows = arrayAt(root, key);
        for (int i = 0; i < rows.size() && names.size() < limit; i++) {
            String name = str(rows.get(i).getAsJsonObject(), "name");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    // ------------------------------------------------------- stills & similar

    private static List<MovieDetail.MovieStill> stills(JsonObject root) {
        List<MovieDetail.MovieStill> stills = new ArrayList<>();
        JsonArray rows = arrayAt(objectAt(root, "images"), "backdrops");
        for (int i = 0; i < rows.size() && stills.size() < MAX_STILLS; i++) {
            String path = str(rows.get(i).getAsJsonObject(), "file_path");
            if (!path.isEmpty()) {
                stills.add(new MovieDetail.MovieStill("", imageUrl(path)));
            }
        }
        return stills;
    }

    private static List<MovieDetail.SimilarMovie> similar(JsonObject root, int id) {
        List<MovieDetail.SimilarMovie> similar = new ArrayList<>();
        JsonArray rows = arrayAt(objectAt(root, "similar"), "results");
        for (int i = 0; i < rows.size() && similar.size() < MAX_SIMILAR; i++) {
            Movie movie = TmdbMovieService.toMovie(rows.get(i).getAsJsonObject());
            if (movie.id() != 0 && movie.id() != id) {
                similar.add(new MovieDetail.SimilarMovie(movie, movie.posterUrl()));
            }
        }
        return similar;
    }

    // ------------------------------------------------------------------ json

    private static String imageUrl(String path) {
        String url = TmdbClient.posterPath(path);
        return url == null ? "" : url;
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }

    private static String str(JsonObject object, String key) {
        if (object == null) {
            return "";
        }
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static io.vavr.control.Option<Integer> intAt(JsonObject object, String key) {
        return number(object, key).map(Number::intValue);
    }

    private static io.vavr.control.Option<Long> longAt(JsonObject object, String key) {
        return number(object, key).map(Number::longValue);
    }

    private static io.vavr.control.Option<Double> doubleAt(JsonObject object, String key) {
        return number(object, key).map(Number::doubleValue);
    }

    private static io.vavr.control.Option<Number> number(JsonObject object, String key) {
        if (object == null) {
            return io.vavr.control.Option.none();
        }
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? io.vavr.control.Option.none()
                : io.vavr.control.Option.some(element.getAsDouble());
    }

    private static JsonObject objectAt(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray arrayAt(JsonObject object, String key) {
        if (object == null) {
            return new JsonArray();
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
