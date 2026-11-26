package vangogh.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.vavr.control.Option;

import vangogh.book.BookDetail.Edition;
import vangogh.net.OpenLibraryClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds Open Library's separate payloads into one {@link BookDetail}.
 *
 * <p>This is the one place that knows Open Library's field names. Every
 * collector is lenient: crowd-sourced work records are sparse, so a missing
 * field is an empty value, never an exception. Only a payload that is not a
 * JSON object at all is a structural error — and that check lives in the
 * service, which owns the {@code Either} spine.
 */
public final class BookDetailMapper {

    private static final int MAX_SUBJECTS = 18;
    private static final int MAX_EDITIONS = 4;
    private static final int MAX_DESCRIPTION = 1400;
    private static final int MAX_BIO = 1100;
    private static final int MAX_SUBJECT_LENGTH = 60;
    private static final Pattern YEAR = Pattern.compile("\\b(\\d{4})\\b");
    /** Internet Archive identifiers are URL-path safe; anything else is dropped. */
    private static final Pattern IA_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private BookDetailMapper() {}

    // --------------------------------------------------------------- work

    /** The first author's key, normalised to "OL…A" (empty when absent). */
    public static String authorKey(JsonObject work) {
        for (JsonElement element : array(work, "authors")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement author = element.getAsJsonObject().get("author");
            if (author == null || !author.isJsonObject()) {
                continue;
            }
            String key = text(author.getAsJsonObject(), "key");
            if (!key.isEmpty()) {
                return normaliseKey(key);
            }
        }
        return "";
    }

    /** The subject list, deduped and capped — also the similar-search seed. */
    public static List<String> subjects(JsonObject work) {
        return cappedSubjects(array(work, "subjects"));
    }

    /** The author record's display name (empty when absent). */
    public static String authorName(JsonObject author) {
        return text(author, "name");
    }

    /**
     * Composes the detail record. {@code editions}/{@code ratings} are the raw
     * supplementary payloads (null when the fetch failed); {@code similar} is
     * already resolved into grid cards by the service.
     */
    public static BookDetail compose(
            JsonObject work,
            String workKey,
            JsonObject editions,
            JsonObject ratings,
            JsonObject authorJson,
            List<Book> similar) {

        String title = Option.of(text(work, "title"))
                .filter(s -> !s.isEmpty())
                .getOrElse("Untitled");

        List<Edition> editionList = editions(editions);
        int editionCount = Option.of(editions)
                .map(e -> intValue(e, "size"))
                .filter(n -> n > 0)
                .getOrElse(editionList.size());

        // The edition median is a better page count than any single printing.
        int pages = medianPages(editions);
        if (pages == 0) {
            pages = editionList.stream()
                    .map(Edition::pages)
                    .filter(p -> !p.isEmpty())
                    .mapToInt(BookDetailMapper::leadingInt)
                    .filter(n -> n > 0)
                    .findFirst()
                    .orElse(0);
        }

        int year = yearOf(text(work, "first_publish_date"));
        if (year == 0) {
            year = editionList.stream()
                    .map(Edition::year)
                    .mapToInt(BookDetailMapper::leadingInt)
                    .filter(n -> n > 0)
                    .findFirst()
                    .orElse(0);
        }

        double rating = Option.of(ratings)
                .map(r -> doubleValue(r.getAsJsonObject("summary"), "average"))
                .getOrElse(0.0);
        int ratingCount = Option.of(ratings)
                .map(r -> intValue(r.getAsJsonObject("summary"), "count"))
                .getOrElse(0);

        String description = cap(text(work, "description"), MAX_DESCRIPTION);
        String sampleUrl = sampleUrl(editions);

        String setting = array(work, "subject_times").stream()
                .map(BookDetailMapper::asString)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");

        String coverUrl = firstCover(array(work, "covers"));
        if (coverUrl.isEmpty()) {
            coverUrl = editionList.stream()
                    .map(Edition::coverUrl)
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse("");
        }

        String isbn = editionList.stream()
                .map(Edition::isbn)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        String featured = featuredEdition(editions);

        List<String> subjectList = subjects(work);
        List<BookDetail.Fact> facts = facts(year, pages, editionCount, setting,
                rating, ratingCount, featured, isbn);

        return new BookDetail(
                workKey,
                title,
                year,
                pages,
                editionCount,
                rating,
                ratingCount,
                description,
                sampleUrl,
                subjectList,
                setting,
                coverUrl,
                OpenLibraryClient.workPage(workKey),
                facts,
                editionList,
                author(authorJson),
                similar == null ? List.of() : similar);
    }

    // -------------------------------------------------------------- facts

    private static List<BookDetail.Fact> facts(
            int year, int pages, int editionCount, String setting,
            double rating, int ratingCount, String featured, String isbn) {

        List<BookDetail.Fact> facts = new ArrayList<>();
        addFact(facts, "First published", year > 0 ? String.valueOf(year) : "", false, false);
        addFact(facts, "Pages", pages > 0 ? String.valueOf(pages) : "", false, false);
        addFact(facts, "Editions", editionCount > 0 ? String.valueOf(editionCount) : "", false, false);
        addFact(facts, "Setting", setting, false, false);
        if (rating > 0) {
            addFact(facts, "Rating", String.format(java.util.Locale.ROOT, "%.1f / 5", rating), false, false);
        }
        if (ratingCount > 0) {
            addFact(facts, "Ratings", String.valueOf(ratingCount), false, false);
        }
        addFact(facts, "Featured", featured, true, true);
        addFact(facts, "ISBN", isbn, true, true);
        return facts;
    }

    private static void addFact(List<BookDetail.Fact> facts, String label,
                                String value, boolean muted, boolean spanAll) {
        if (value != null && !value.isBlank()) {
            facts.add(new BookDetail.Fact(label, value, muted, spanAll));
        }
    }

    private static String featuredEdition(JsonObject editions) {
        return array(editions, "entries").stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(entry -> {
                    String publisher = array(entry, "publishers").stream()
                            .map(BookDetailMapper::asString)
                            .filter(s -> !s.isEmpty())
                            .findFirst()
                            .orElse("");
                    int year = yearOf(text(entry, "publish_date"));
                    if (publisher.isEmpty() && year == 0) {
                        return "";
                    }
                    return (publisher.isEmpty() ? "" : publisher)
                            + (publisher.isEmpty() || year == 0 ? "" : ", ")
                            + (year == 0 ? "" : String.valueOf(year));
                })
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
    }

    // ----------------------------------------------------------- editions

    /**
     * The Internet Archive reader URL for the first edition carrying a scan
     * ({@code ocaid}), or empty. That is the page's "Browse sample" source:
     * full pages for public-domain works, the lending preview for the rest.
     */
    private static String sampleUrl(JsonObject editions) {
        for (JsonElement element : array(editions, "entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            String ocaid = text(element.getAsJsonObject(), "ocaid");
            if (IA_ID.matcher(ocaid).matches()) {
                return "https://archive.org/embed/" + ocaid;
            }
        }
        return "";
    }

    private static List<Edition> editions(JsonObject editions) {
        List<Edition> covered = new ArrayList<>();
        List<Edition> uncovered = new ArrayList<>();

        for (JsonElement element : array(editions, "entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int pages = intValue(entry, "number_of_pages");
            String isbn = firstString(entry, "isbn_13", "isbn_10");
            String cover = firstCover(array(entry, "covers"));
            String year = yearOf(text(entry, "publish_date")) > 0
                    ? String.valueOf(yearOf(text(entry, "publish_date")))
                    : "";

            if (year.isEmpty() && pages == 0 && isbn.isEmpty() && cover.isEmpty()) {
                continue;
            }
            Edition edition = new Edition(
                    year,
                    pages > 0 ? pages + " pp" : "",
                    isbn,
                    cover);
            (cover.isEmpty() ? uncovered : covered).add(edition);
        }

        List<Edition> result = new ArrayList<>(covered);
        result.addAll(uncovered);
        return result.size() > MAX_EDITIONS ? result.subList(0, MAX_EDITIONS) : result;
    }

    private static int medianPages(JsonObject editions) {
        List<Integer> counts = new ArrayList<>();
        for (JsonElement element : array(editions, "entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            int pages = intValue(element.getAsJsonObject(), "number_of_pages");
            if (pages > 0) {
                counts.add(pages);
            }
        }
        if (counts.isEmpty()) {
            return 0;
        }
        counts.sort(Integer::compareTo);
        return counts.get(counts.size() / 2);
    }

    // ------------------------------------------------------------- author

    private static BookDetail.Author author(JsonObject author) {
        if (author == null) {
            return BookDetail.Author.empty();
        }
        String name = text(author, "name");
        String dates = dates(text(author, "birth_date"), text(author, "death_date"));
        String bio = cap(text(author, "bio"), MAX_BIO);
        String photo = firstAuthorPhoto(array(author, "photos"));
        String wikipedia = array(author, "links").stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(link -> text(link, "url"))
                .filter(url -> url.contains("wikipedia.org"))
                .findFirst()
                .orElseGet(() -> array(author, "links").stream()
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject)
                        .map(link -> text(link, "url"))
                        .filter(url -> !url.isEmpty())
                        .findFirst()
                        .orElse(""));

        String key = normaliseKey(text(author, "key"));
        String openLibraryUrl = key.isEmpty() ? "" : OpenLibraryClient.authorPage(key);
        return new BookDetail.Author(name, dates, bio, photo, wikipedia, openLibraryUrl);
    }

    private static String dates(String birth, String death) {
        String born = yearOf(birth) > 0 ? String.valueOf(yearOf(birth)) : "";
        String died = yearOf(death) > 0 ? String.valueOf(yearOf(death)) : "";
        if (!born.isEmpty() && !died.isEmpty()) {
            return born + " \u2013 " + died;
        }
        if (!born.isEmpty()) {
            return "born " + born;
        }
        if (!died.isEmpty()) {
            return "died " + died;
        }
        return "";
    }

    // ------------------------------------------------------------ helpers

    static String normaliseKey(String key) {
        return Option.of(key)
                .getOrElse("")
                .replaceFirst("^/works/", "")
                .replaceFirst("^works/", "")
                .replaceFirst("^/authors/", "")
                .replaceFirst("^authors/", "")
                .trim();
    }

    static int yearOf(String date) {
        if (date == null || date.isEmpty()) {
            return 0;
        }
        Matcher matcher = YEAR.matcher(date);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int leadingInt(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static String firstCover(List<JsonElement> covers) {
        return covers.stream()
                .map(BookDetailMapper::asLong)
                .filter(id -> id > 0)
                .findFirst()
                .map(OpenLibraryClient::coverPath)
                .orElse("");
    }

    /** Work covers live under {@code /b/id/}, author photos under {@code /a/id/}. */
    private static String firstAuthorPhoto(List<JsonElement> photos) {
        return photos.stream()
                .map(BookDetailMapper::asLong)
                .filter(id -> id > 0)
                .findFirst()
                .map(OpenLibraryClient::authorPhotoPath)
                .orElse("");
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            for (JsonElement element : array(object, key)) {
                String value = asString(element);
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static List<String> cappedSubjects(List<JsonElement> raw) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : raw) {
            String subject = asString(element).trim();
            if (subject.isEmpty() || subject.length() > MAX_SUBJECT_LENGTH) {
                continue;
            }
            seen.add(subject);
            if (seen.size() >= MAX_SUBJECTS) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    private static List<JsonElement> array(JsonObject object, String key) {
        if (object == null) {
            return List.of();
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<JsonElement> values = new ArrayList<>(array.size());
        array.forEach(values::add);
        return values;
    }

    private static String text(JsonObject object, String key) {
        if (object == null) {
            return "";
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            JsonElement value = element.getAsJsonObject().get("value");
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static String asString(JsonElement element) {
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsString()
                : "";
    }

    private static long asLong(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int intValue(JsonObject object, String key) {
        if (object == null) {
            return 0;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double doubleValue(JsonObject object, String key) {
        if (object == null) {
            return 0.0;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.replace("\r\n", "\n").trim();
        if (clean.length() <= max) {
            return clean;
        }
        int cut = clean.lastIndexOf(' ', max);
        if (cut < max / 2) {
            cut = max;
        }
        return clean.substring(0, cut).trim() + "\u2026";
    }
}
