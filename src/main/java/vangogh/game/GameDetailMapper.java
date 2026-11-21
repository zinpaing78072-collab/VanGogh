package vangogh.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.vavr.control.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Folds TheGamesDB's separate payloads into one {@link GameDetail}.
 *
 * <p>This is the one place that knows TheGamesDB's field names. Every collector
 * is lenient: the database is crowd-sourced, so a missing field is an empty
 * value, never an exception. Only a payload that is not a JSON object at all is
 * a structural error — and that check lives in the service, which owns the
 * {@code Either} spine.
 *
 * <p>Nothing is invented here. A record without a fanart leaves {@code heroUrl}
 * empty (the page then shows its gradient), a record without publishers leaves
 * the rail row out, and the {@code rating} string ("M - Mature 17+",
 * "Not Rated") is passed through untouched.
 */
final class GameDetailMapper {

    private static final int MAX_STILLS = 6;
    private static final String RECORD_BASE = "https://thegamesdb.net/game/";
    private static final String MEDIUM_BASE = "https://cdn.thegamesdb.net/images/medium/";
    private static final String ORIGINAL_BASE = "https://cdn.thegamesdb.net/images/original/";

    /** TheGamesDB uses this in place of "unknown" for some records. */
    private static final String UNKNOWN_RELEASE = "1970-01-01";

    private GameDetailMapper() {
    }

    static GameDetail compose(JsonObject root,
                              JsonObject game,
                              JsonObject imagesRoot,
                              String relatedSource,
                              java.util.List<GameDetail.Related> related,
                              Map<String, String> developerNames,
                              Map<String, String> publisherNames) {
        int id = optInt(game, "id");
        String title = optString(game, "game_title");
        String releaseRaw = optString(game, "release_date");
        String releaseDate = UNKNOWN_RELEASE.equals(releaseRaw) ? "" : releaseRaw;
        int year = yearOf(releaseDate);
        String rating = optString(game, "rating");
        int players = optInt(game, "players");
        String overview = optString(game, "overview");
        String youtube = youtubeUrl(optString(game, "youtube"));

        java.util.List<String> genres = names(game.getAsJsonArray("genres"), TgdbGameService::genreName);
        java.util.List<String> platforms = platformNames(root, game);
        java.util.List<String> publishers = names(game.getAsJsonArray("publishers"), publisherNames);
        java.util.List<String> developers = names(game.getAsJsonArray("developers"), developerNames);

        String boxart = boxartUrl(root, id);
        java.util.List<String> stills = new ArrayList<>();
        String hero = heroUrl(imagesRoot, id, stills);

        java.util.List<GameDetail.Fact> facts = facts(releaseDate, rating, players,
                platforms, genres, publishers, developers, id);

        return new GameDetail(
                id,
                title.isEmpty() ? "Untitled" : title,
                releaseDate,
                year,
                rating,
                players,
                List.copyOf(genres),
                List.copyOf(platforms),
                List.copyOf(publishers),
                List.copyOf(developers),
                overview,
                youtube,
                RECORD_BASE + id + "/",
                boxart,
                hero,
                List.copyOf(stills),
                List.copyOf(facts),
                relatedSource,
                List.copyOf(related));
    }

    /** Some records carry the bare video id ("8X2kIfS6fb8") instead of a URL. */
    private static String youtubeUrl(String value) {
        if (value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return "https://www.youtube.com/watch?v=" + value;
    }

    /** The rail's record sheet: only what the record carries, in a stable order. */
    private static java.util.List<GameDetail.Fact> facts(
            String releaseDate, String rating, int players,
            java.util.List<String> platforms, java.util.List<String> genres,
            java.util.List<String> publishers, java.util.List<String> developers, int id) {
        java.util.List<GameDetail.Fact> facts = new ArrayList<>();
        addFact(facts, "Release", releaseDate);
        addFact(facts, "Age rating", rating);
        addFact(facts, "Players", players > 0 ? String.valueOf(players) : "");
        addFact(facts, "Platform", join(platforms));
        addFact(facts, "Genres", join(genres));
        addFact(facts, "Publisher", join(publishers));
        addFact(facts, "Developer", join(developers));
        addFact(facts, "Game ID", id > 0 ? String.valueOf(id) : "");
        return facts;
    }

    private static void addFact(java.util.List<GameDetail.Fact> facts, String label, String value) {
        if (value != null && !value.isEmpty()) {
            facts.add(new GameDetail.Fact(label, value));
        }
    }

    private static String join(java.util.List<String> values) {
        return String.join(" · ", values);
    }

    // ------------------------------------------------------------------ art

    /** Fanart when the record has it, else its first screenshot. */
    private static String heroUrl(JsonObject imagesRoot, int id, java.util.List<String> stills) {
        String base = mediumBase(imagesRoot);
        String hero = "";
        JsonArray row = imageRow(imagesRoot, id);
        for (JsonElement element : row) {
            JsonObject image = element.getAsJsonObject();
            String filename = optString(image, "filename");
            if (filename.isEmpty()) {
                continue;
            }
            String type = optString(image, "type");
            if ("fanart".equals(type) && hero.isEmpty()) {
                hero = base + filename;
            } else if ("screenshot".equals(type) && stills.size() < MAX_STILLS) {
                stills.add(base + filename);
            }
        }
        return hero.isEmpty() && !stills.isEmpty() ? stills.get(0) : hero;
    }

    /** Front boxart at original resolution, or empty when the record has none. */
    private static String boxartUrl(JsonObject root, int id) {
        JsonObject boxart = Option.of(root)
                .flatMap(r -> Option.of(r.getAsJsonObject("include")))
                .flatMap(include -> Option.of(include.getAsJsonObject("boxart")))
                .getOrNull();
        if (boxart == null) {
            return "";
        }
        String base = Option.of(boxart.getAsJsonObject("base_url"))
                .map(urls -> optString(urls, "original"))
                .filter(url -> !url.isEmpty())
                .getOrElse(ORIGINAL_BASE);

        JsonArray row = Option.of(boxart.getAsJsonObject("data"))
                .flatMap(data -> Option.of(data.get(String.valueOf(id))))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .getOrElse(new JsonArray());
        for (JsonElement element : row) {
            JsonObject image = element.getAsJsonObject();
            if ("boxart".equals(optString(image, "type")) && "front".equals(optString(image, "side"))) {
                String filename = optString(image, "filename");
                if (!filename.isEmpty()) {
                    return base + filename;
                }
            }
        }
        return "";
    }

    private static String mediumBase(JsonObject imagesRoot) {
        return Option.of(imagesRoot)
                .flatMap(r -> Option.of(r.getAsJsonObject("data")))
                .flatMap(data -> Option.of(data.getAsJsonObject("base_url")))
                .map(urls -> optString(urls, "medium"))
                .filter(url -> !url.isEmpty())
                .getOrElse(MEDIUM_BASE);
    }

    private static JsonArray imageRow(JsonObject imagesRoot, int id) {
        return Option.of(imagesRoot)
                .flatMap(r -> Option.of(r.getAsJsonObject("data")))
                .flatMap(data -> Option.of(data.getAsJsonObject("images")))
                .flatMap(images -> Option.of(images.get(String.valueOf(id))))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .getOrElse(new JsonArray());
    }

    // -------------------------------------------------------------- lookups

    private static java.util.List<String> platformNames(JsonObject root, JsonObject game) {
        int platformId = optInt(game, "platform");
        return Option.of(root)
                .flatMap(r -> Option.of(r.getAsJsonObject("include")))
                .flatMap(include -> Option.of(include.getAsJsonObject("platform")))
                .flatMap(platform -> Option.of(platform.getAsJsonObject("data")))
                .flatMap(data -> Option.of(data.get(String.valueOf(platformId))))
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(platform -> optString(platform, "name"))
                .filter(name -> !name.isEmpty())
                .map(name -> java.util.List.of(name))
                .getOrElse(java.util.List.of());
    }

    /** Resolves an id array through a name map (publishers, developers). */
    private static java.util.List<String> names(JsonArray ids, Map<String, String> byId) {
        if (ids == null || ids.size() == 0) {
            return java.util.List.of();
        }
        java.util.List<String> names = new ArrayList<>();
        for (JsonElement element : ids) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String name = byId.get(element.getAsString());
            if (name != null && !name.isBlank() && !names.contains(name.trim())) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /** Resolves a genre id array through the genre map both services share. */
    private static java.util.List<String> names(JsonArray ids,
                                                java.util.function.IntFunction<String> label) {
        if (ids == null || ids.size() == 0) {
            return java.util.List.of();
        }
        List<String> names = new ArrayList<String>();
        for (JsonElement element : ids) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String name = label.apply(element.getAsInt());
            if (name != null && !name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- atoms

    private static int yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return 0;
        }
        String year = releaseDate.substring(0, 4);
        return year.chars().allMatch(Character::isDigit) ? Integer.parseInt(year) : 0;
    }

    private static int optInt(JsonObject object, String key) {
        return Option.of(object.get(key))
                .filter(element -> !element.isJsonNull())
                .map(JsonElement::getAsInt)
                .getOrElse(0);
    }

    private static String optString(JsonObject object, String key) {
        return Option.of(object.get(key))
                .filter(element -> !element.isJsonNull())
                .map(JsonElement::getAsString)
                .getOrElse("");
    }
}
