package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The list of movies the scanners collect for, backed by the {@code tracked_releases} table (see
 * db/migration/V7__add_tracked_releases.sql). Replaces search_queries.txt: rows are inserted
 * automatically by {@link ReleaseDiscoveryService} for every India-origin TMDB release, and edited
 * (excluded / keywords changed / added by hand) from the aura-radix admin UI via AuraService.
 *
 * A row is "active" when today (IST) falls in [track_from, track_until] and it isn't excluded.
 */
public class TrackedReleaseSource {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern LATIN_TEXT = Pattern.compile("^[\\p{IsLatin}\\p{N}\\p{P}\\p{Zs}]+$");

    private TrackedReleaseSource() {
    }

    static LocalDate today() {
        return LocalDate.now(IST);
    }

    /**
     * Active rows in the same one-object-per-keyword shape search_queries.txt produced
     * ({entity, keyword, category, language}), so the scanners' existing group-by-entity loops are
     * unchanged. Ordered by release date so the soonest/just-released movies are scanned first.
     */
    public static List<JsonObject> loadInputQueries() {
        List<JsonObject> result = new ArrayList<>();
        String sql = "SELECT entity_name, keywords, category, language FROM tracked_releases " +
                "WHERE NOT excluded AND ? BETWEEN track_from AND track_until " +
                "ORDER BY release_date, entity_name";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, Date.valueOf(today()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String entity = rs.getString("entity_name");
                    for (String keyword : splitKeywords(rs.getString("keywords"))) {
                        JsonObject row = new JsonObject();
                        row.addProperty("entity", entity);
                        row.addProperty("keyword", keyword);
                        row.addProperty("category", rs.getString("category"));
                        row.addProperty("language", rs.getString("language"));
                        result.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error loading tracked releases: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Loaded " + result.stream().map(o -> o.get("entity").getAsString()).distinct().count()
                + " actively tracked release(s).");
        return result;
    }

    /**
     * Re-checked by the scanners right before each entity: a scan pass can take hours (Apify
     * scanners wait 1h between entities), so an admin's exclusion - or the window closing - must
     * take effect for the rest of the pass, not only on the next one.
     */
    public static boolean isActive(String entity) {
        String sql = "SELECT 1 FROM tracked_releases WHERE entity_name = ? " +
                "AND NOT excluded AND ? BETWEEN track_from AND track_until";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, entity);
            pstmt.setDate(2, Date.valueOf(today()));
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // Fail open: a transient DB blip shouldn't silently drop an entity the pass already loaded.
            System.err.println("Database error checking tracked release '" + entity + "': " + e.getMessage());
            return true;
        }
    }

    static List<String> splitKeywords(String keywords) {
        List<String> out = new ArrayList<>();
        if (keywords == null) return out;
        Arrays.stream(keywords.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .forEach(out::add);
        return out;
    }

    /**
     * Default search keywords for a newly discovered title: the hashtag form (letters/digits only,
     * e.g. "Mr. Lucky" -> "MrLucky") plus the plain spaced title for phrase search on
     * X/YouTube/Reddit. A non-Latin original title (native script) is skipped - it would mostly add
     * noise to the OR'd queries; admins can add it from the UI where it's worth it.
     */
    static String defaultKeywords(String title, String originalTitle) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String t : new String[]{title, originalTitle}) {
            if (t == null || t.isBlank() || !LATIN_TEXT.matcher(t).matches()) continue;
            String compact = t.replaceAll("[^\\p{L}\\p{N}]", "");
            if (!compact.isEmpty()) byLower.putIfAbsent(compact.toLowerCase(), compact);
            // Phrase form keeps only letters/digits/spaces and . ' & - : a ':' would be read as a
            // search operator by X, and ',' is the keywords column's separator.
            String spaced = t.replaceAll("[^\\p{L}\\p{N}\\s.'&-]", " ").replaceAll("\\s+", " ").trim();
            if (spaced.contains(" ")) byLower.putIfAbsent(spaced.toLowerCase(), spaced);
        }
        if (byLower.isEmpty() && title != null && !title.isBlank()) {
            byLower.put(title.toLowerCase(), title.replaceAll("[,:]", " ").replaceAll("\\s+", " ").trim());
        }
        return String.join(",", byLower.values());
    }

    // ---- Writes used by ReleaseDiscoveryService ----

    /** Existing TMDB ids among non-ended rows, so discovery can refresh ones TMDB no longer returns in range. */
    static List<Integer> tmdbIdsStillRelevant(LocalDate today) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT tmdb_id FROM tracked_releases WHERE tmdb_id IS NOT NULL AND track_until >= ?";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, Date.valueOf(today));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            System.err.println("Database error in tmdbIdsStillRelevant: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Inserts a newly discovered release, or refreshes an existing one's TMDB-owned fields (title,
     * release date and the derived tracking window, popularity, poster). Never touches
     * entity_name, keywords or the exclusion flag - those belong to the admin once the row exists,
     * so a removed movie is not resurrected by the next discovery pass.
     *
     * A release not yet in the table whose original language isn't in {@code indianLanguages}
     * (e.g. an English-language film with Indian origin) is skipped entirely. Rows already in the
     * table (e.g. added before this rule) are still refreshed.
     *
     * @return true if a new row was inserted
     */
    static boolean upsertDiscovered(TmdbReleaseClient.Release r, int daysBefore, int daysAfter,
                                    Set<String> indianLanguages, Set<String> autoTrackLanguages) throws SQLException {
        LocalDate from = r.releaseDate().minusDays(daysBefore);
        LocalDate until = r.releaseDate().plusDays(daysAfter);
        try (Connection conn = DatabaseService.getConnection()) {
            String update = "UPDATE tracked_releases SET title = ?, original_title = ?, language = ?, " +
                    "release_date = ?, track_from = ?, track_until = ?, popularity = ?, poster_path = ?, " +
                    "context_terms = ?, updated_at = NOW() WHERE tmdb_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(update)) {
                pstmt.setString(1, r.title());
                pstmt.setString(2, r.originalTitle());
                pstmt.setString(3, r.language());
                pstmt.setDate(4, Date.valueOf(r.releaseDate()));
                pstmt.setDate(5, Date.valueOf(from));
                pstmt.setDate(6, Date.valueOf(until));
                setNullableDouble(pstmt, 7, r.popularity());
                pstmt.setString(8, r.posterPath());
                pstmt.setString(9, joinTerms(r.contextTerms()));
                pstmt.setInt(10, r.tmdbId());
                if (pstmt.executeUpdate() > 0) {
                    saveDetails(conn, r);
                    return false;
                }
            }

            // Not tracked yet: only Indian-language releases are added at all. English / foreign-
            // language films with Indian origin are skipped (admins add them by hand if wanted).
            if (!isIndianLanguage(r.language(), indianLanguages)) return false;

            String entityName = uniqueEntityName(conn, r);
            String insert = "INSERT INTO tracked_releases (tmdb_id, entity_name, title, original_title, language, " +
                    "release_date, track_from, track_until, keywords, category, source, popularity, poster_path, " +
                    "excluded, excluded_reason, excluded_at, context_terms) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'media.movie', 'TMDB', ?, ?, ?, ?, " +
                    "CASE WHEN ? THEN NOW() END, ?) ON CONFLICT DO NOTHING";
            // Indian-language releases outside tracking.auto.track.languages (by default anything
            // but Tamil/Telugu/Kannada/Malayalam) are stored but start excluded - visible in the
            // admin UI and restorable there, never scanned until then. Insert-only, so a restored
            // row stays restored.
            boolean autoExclude = !isIndianLanguage(r.language(), autoTrackLanguages);
            try (PreparedStatement pstmt = conn.prepareStatement(insert)) {
                pstmt.setInt(1, r.tmdbId());
                pstmt.setString(2, entityName);
                pstmt.setString(3, r.title());
                pstmt.setString(4, r.originalTitle());
                pstmt.setString(5, r.language());
                pstmt.setDate(6, Date.valueOf(r.releaseDate()));
                pstmt.setDate(7, Date.valueOf(from));
                pstmt.setDate(8, Date.valueOf(until));
                pstmt.setString(9, defaultKeywords(r.title(), r.originalTitle()));
                setNullableDouble(pstmt, 10, r.popularity());
                pstmt.setString(11, r.posterPath());
                pstmt.setBoolean(12, autoExclude);
                pstmt.setString(13, autoExclude ? autoExcludeReason(r.language()) : null);
                pstmt.setBoolean(14, autoExclude);
                pstmt.setString(15, joinTerms(r.contextTerms()));
                boolean inserted = pstmt.executeUpdate() > 0;
                if (inserted) saveDetails(conn, r);
                return inserted;
            }
        }
    }

    /** TMDB-owned movie details, refreshed on every discovery pass (see V9). */
    private static void saveDetails(Connection conn, TmdbReleaseClient.Release r) throws SQLException {
        TmdbReleaseClient.Details d = r.details();
        if (d == null) return;
        try (PreparedStatement pstmt = conn.prepareStatement("UPDATE tracked_releases SET genres = ?, runtime = ?, "
                + "director = ?, production_company = ? WHERE tmdb_id = ?")) {
            pstmt.setString(1, d.genres());
            if (d.runtime() == null) pstmt.setNull(2, java.sql.Types.INTEGER); else pstmt.setInt(2, d.runtime());
            pstmt.setString(3, d.directors());
            pstmt.setString(4, d.productionCompany());
            pstmt.setInt(5, r.tmdbId());
            pstmt.executeUpdate();
        }
    }

    /**
     * entity_name must be unique (it keys every collected post). Two releases can share a title
     * (e.g. same-named Tamil and Telugu films), so collisions get " (Language)", then " (Language YYYY)",
     * then the TMDB id appended.
     */
    private static String uniqueEntityName(Connection conn, TmdbReleaseClient.Release r) throws SQLException {
        String base = r.title().trim();
        String lang = r.language() == null ? "" : capitalize(r.language());
        List<String> candidates = new ArrayList<>(List.of(base));
        if (!lang.isEmpty()) {
            candidates.add(base + " (" + lang + ")");
            candidates.add(base + " (" + lang + " " + r.releaseDate().getYear() + ")");
        }
        candidates.add(base + " (tmdb " + r.tmdbId() + ")");
        Set<String> taken = new java.util.HashSet<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT entity_name FROM tracked_releases WHERE entity_name = ANY (?)")) {
            pstmt.setArray(1, conn.createArrayOf("text", candidates.toArray()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) taken.add(rs.getString(1));
            }
        }
        for (String c : candidates) {
            if (!taken.contains(c)) return c;
        }
        return candidates.get(candidates.size() - 1);
    }

    private static String joinTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) return null;
        return String.join(",", terms.stream().map(t -> t.replace(",", " ").trim()).filter(t -> !t.isEmpty()).toList());
    }

    /**
     * Gate for the paid Apify scanners (Instagram/Reddit): active AND the relevance check has
     * evaluated the movie from its cheap X posts without failing it. A movie that just entered its
     * window waits (typically ~1-2h) for that first evaluation, so a junk title is excluded before
     * any Apify run is spent on it. An admin-restored movie (relevance_override) is always eligible.
     */
    public static boolean isApifyEligible(String entity) {
        String sql = "SELECT relevance_override OR relevance_checked_at IS NOT NULL FROM tracked_releases " +
                "WHERE entity_name = ? AND NOT excluded AND ? BETWEEN track_from AND track_until";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, entity);
            pstmt.setDate(2, Date.valueOf(today()));
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            // Fail closed here (unlike isActive): skipping one paid run is cheaper than paying for junk.
            System.err.println("Database error checking Apify eligibility for '" + entity + "': " + e.getMessage());
            return false;
        }
    }

    static String autoExcludeReason(String language) {
        return "Auto-excluded: " + (language == null ? "unknown" : language) + " is not auto-tracked (restore to track)";
    }

    static boolean isIndianLanguage(String language, Set<String> indianLanguages) {
        return language != null && indianLanguages.contains(language.trim().toLowerCase());
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void setNullableDouble(PreparedStatement pstmt, int idx, Double value) throws SQLException {
        if (value == null) pstmt.setNull(idx, Types.DOUBLE);
        else pstmt.setDouble(idx, value);
    }
}
