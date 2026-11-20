package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The weekly release files between {@link ReleaseDiscoveryService} (writer) and
 * {@link ReleaseFileListener} (reader, which loads the latest file into tracked_releases).
 *
 * One file per ISO week (IST), {@code <release.files.dir>/releases-2026-W47.json}:
 * <pre>
 * {
 *   "week": "2026-W47",
 *   "generated_at": "2026-11-20T09:00:00+05:30",
 *   "tmdb":   [ ...written by discovery, rewritten on every pass - don't edit... ],
 *   "manual": [ ...yours: movies TMDB doesn't list, kept as-is by discovery... ]
 * }
 * </pre>
 * A manual entry needs only {@code title} and {@code release_date}; see {@link ManualRelease}
 * for the optional fields. When discovery starts a new week's file it carries over the previous
 * file's manual entries whose tracking window hasn't ended, so they never need re-adding.
 */
public class ReleaseFileStore {

    static final String DEFAULT_DIR = "releases";
    private static final Pattern FILE_NAME = Pattern.compile("releases-(\\d{4})-W(\\d{2})\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /**
     * A hand-added movie. {@code entityName} defaults to the title, {@code keywords} to the
     * hashtag + phrase forms of the title, and the tracking window to release_date -/+
     * tracking.days.before/after unless track_from / track_until are given.
     */
    public record ManualRelease(String entityName, String title, String originalTitle, String language,
                                LocalDate releaseDate, LocalDate trackFrom, LocalDate trackUntil,
                                String keywords, List<String> contextTerms, String posterPath,
                                TmdbReleaseClient.Details details) {
    }

    /**
     * Parsed contents of one weekly file. {@code manualRaw} holds every manual entry as written,
     * including ones that failed to parse (reported in {@code errors}), so rewriting the file
     * never drops a hand-written entry that just has a typo.
     */
    public record WeekFile(Path path, List<TmdbReleaseClient.Release> tmdb, List<ManualRelease> manual,
                           List<JsonObject> manualRaw, List<String> errors) {
    }

    private ReleaseFileStore() {
    }

    static Path dir() {
        String d = AppProperties.getProperty("release.files.dir");
        return Paths.get(d == null || d.isBlank() ? DEFAULT_DIR : d.trim());
    }

    static String weekName(LocalDate day) {
        return String.format("%d-W%02d", day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    static Path weekFile(Path dir, LocalDate day) {
        return dir.resolve("releases-" + weekName(day) + ".json");
    }

    /** The newest weekly file in {@code dir} (by week in the name), or null when there is none. */
    static Path latest(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .orElse(null);
        }
    }

    // ---- reading ----

    static WeekFile read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<String> errors = new ArrayList<>();
        List<TmdbReleaseClient.Release> tmdb = new ArrayList<>();
        for (JsonObject o : objects(root, "tmdb")) {
            try {
                tmdb.add(tmdbFromJson(o));
            } catch (RuntimeException e) {
                errors.add("tmdb entry " + o.get("title") + ": " + e.getMessage());
            }
        }
        List<ManualRelease> manual = new ArrayList<>();
        List<JsonObject> manualRaw = objects(root, "manual");
        for (JsonObject o : manualRaw) {
            try {
                manual.add(manualFromJson(o));
            } catch (RuntimeException e) {
                errors.add("manual entry " + o.get("title") + ": " + e.getMessage());
            }
        }
        return new WeekFile(path, tmdb, manual, manualRaw, errors);
    }

    private static List<JsonObject> objects(JsonObject root, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray(key)) {
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }
        }
        return out;
    }

    static TmdbReleaseClient.Release tmdbFromJson(JsonObject o) {
        if (!o.has("tmdb_id") || o.get("tmdb_id").isJsonNull()) throw new IllegalArgumentException("missing tmdb_id");
        return new TmdbReleaseClient.Release(o.get("tmdb_id").getAsInt(), required(o, "title"), str(o, "original_title"),
                lower(str(o, "language")), date(o, "release_date", true), dbl(o, "popularity"), str(o, "poster_path"),
                list(o, "context_terms"), details(o));
    }

    static ManualRelease manualFromJson(JsonObject o) {
        String title = required(o, "title");
        LocalDate release = date(o, "release_date", true);
        LocalDate from = date(o, "track_from", false);
        LocalDate until = date(o, "track_until", false);
        if (from != null && until != null && until.isBefore(from)) {
            throw new IllegalArgumentException("track_until is before track_from");
        }
        List<String> keywords = list(o, "keywords");
        return new ManualRelease(str(o, "entity_name") == null ? title : str(o, "entity_name"), title,
                str(o, "original_title"), lower(str(o, "language")), release, from, until,
                keywords.isEmpty() ? null : String.join(",", keywords), list(o, "context_terms"),
                str(o, "poster_path"), details(o));
    }

    private static TmdbReleaseClient.Details details(JsonObject o) {
        List<String> genres = list(o, "genres");
        Double runtime = dbl(o, "runtime");
        return new TmdbReleaseClient.Details(genres.isEmpty() ? null : String.join(",", genres),
                runtime == null || runtime <= 0 ? null : runtime.intValue(), str(o, "director"),
                str(o, "production_company"));
    }

    // ---- writing ----

    static JsonObject tmdbToJson(TmdbReleaseClient.Release r) {
        JsonObject o = new JsonObject();
        o.addProperty("tmdb_id", r.tmdbId());
        o.addProperty("title", r.title());
        o.addProperty("original_title", r.originalTitle());
        o.addProperty("language", r.language());
        o.addProperty("release_date", r.releaseDate().toString());
        o.addProperty("popularity", r.popularity());
        o.addProperty("poster_path", r.posterPath());
        o.add("context_terms", array(r.contextTerms()));
        TmdbReleaseClient.Details d = r.details();
        o.add("genres", array(d == null || d.genres() == null ? List.of() : TrackedReleaseSource.splitKeywords(d.genres())));
        o.addProperty("runtime", d == null ? null : d.runtime());
        o.addProperty("director", d == null ? null : d.directors());
        o.addProperty("production_company", d == null ? null : d.productionCompany());
        return o;
    }

    /**
     * Writes the file via a temp file + rename, so the listener never reads a half-written file.
     * Manual entries are written back as the original JSON objects, unchanged.
     */
    static void write(Path path, String week, List<TmdbReleaseClient.Release> tmdb, List<JsonObject> manual)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("week", week);
        root.addProperty("generated_at", OffsetDateTime.now(TrackedReleaseSource.IST).withNano(0).toString());
        root.addProperty("note", "\"tmdb\" is rewritten by release discovery on every pass - edit \"manual\" instead. "
                + "A manual entry needs title and release_date (YYYY-MM-DD); optional: entity_name, original_title, "
                + "language, keywords, context_terms, track_from, track_until, poster_path, genres, runtime, director, "
                + "production_company.");
        JsonArray t = new JsonArray();
        tmdb.forEach(r -> t.add(tmdbToJson(r)));
        root.add("tmdb", t);
        JsonArray m = new JsonArray();
        manual.forEach(m::add);
        root.add("manual", m);

        Files.createDirectories(path.getParent() == null ? Paths.get(".") : path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- JSON helpers ----

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        if (values != null) values.forEach(a::add);
        return a;
    }

    /** A list field given either as a JSON array or a comma-separated string. */
    static List<String> list(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || o.get(key).isJsonNull()) return out;
        JsonElement el = o.get(key);
        if (el.isJsonArray()) {
            for (JsonElement v : el.getAsJsonArray()) {
                if (v.isJsonNull()) continue;
                String s = v.getAsString().replace(",", " ").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else {
            out.addAll(TrackedReleaseSource.splitKeywords(el.getAsString()));
        }
        return out;
    }

    private static String required(JsonObject o, String key) {
        String v = str(o, key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return v;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        String s = o.get(key).getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static Double dbl(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsDouble();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number");
        }
    }

    private static LocalDate date(JsonObject o, String key, boolean required) {
        String v = str(o, key);
        if (v == null) {
            if (required) throw new IllegalArgumentException("missing " + key);
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " '" + v + "' is not YYYY-MM-DD");
        }
    }
}
