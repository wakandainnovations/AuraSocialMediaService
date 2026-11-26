package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The list of movies the scanners collect for, backed by the {@code tracked_releases} table (see
 * db/migration/V7__add_tracked_releases.sql). Replaces search_queries.txt. Rows are written by the
 * standalone scripts/release_file_listener.py from the weekly release files that
 * {@link ReleaseDiscoveryService} produces (TMDB + hand-added movies), and edited (excluded /
 * keywords changed / added by hand) from the aura-radix admin UI via AuraService. This service
 * only reads the table.
 *
 * A row is "active" when today (IST) falls in [track_from, track_until] and it isn't excluded.
 */
public class TrackedReleaseSource {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TrackedReleaseSource() {
    }

    static LocalDate today() {
        return LocalDate.now(IST);
    }

    /**
     * Active rows in the same one-object-per-keyword shape search_queries.txt produced
     * ({entity, keyword, category, language, release_date when known}), so the scanners' existing group-by-entity loops are
     * unchanged. Ordered by release date so the soonest/just-released movies are scanned first.
     */
    public static List<JsonObject> loadInputQueries() {
        List<JsonObject> result = new ArrayList<>();
        String sql = "SELECT entity_name, keywords, category, language, release_date FROM tracked_releases " +
                "WHERE NOT excluded AND " + activeWindowSql() + " " +
                "ORDER BY release_date, entity_name";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, Date.valueOf(today()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String entity = rs.getString("entity_name");
                    Date releaseDate = rs.getDate("release_date");
                    for (String keyword : splitKeywords(rs.getString("keywords"))) {
                        JsonObject row = new JsonObject();
                        row.addProperty("entity", entity);
                        row.addProperty("keyword", keyword);
                        row.addProperty("category", rs.getString("category"));
                        row.addProperty("language", rs.getString("language"));
                        if (releaseDate != null) row.addProperty("release_date", releaseDate.toLocalDate().toString());
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
                "AND NOT excluded AND " + activeWindowSql();
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

    /**
     * SQL condition "today (bound as ?) is inside the movie's tracking window". The stored
     * track_from/track_until are clamped to release_date -/+ tracking.days.before/after, so
     * narrowing those settings takes effect at once, including for rows stored under a wider window.
     */
    static String activeWindowSql() {
        int before = Math.max(0, AppProperties.getIntProperty("tracking.days.before", 2));
        int after = Math.max(0, AppProperties.getIntProperty("tracking.days.after", 2));
        // GREATEST/LEAST ignore NULLs, so a row without a release_date keeps its stored window.
        return "? BETWEEN GREATEST(track_from, release_date - " + before + ") "
                + "AND LEAST(track_until, release_date + " + after + ")";
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
     * TMDB id -> title of non-ended rows, so discovery keeps refreshing ones TMDB no longer returns
     * in range (and can name the ones TMDB has dropped).
     */
    static Map<Integer, String> tmdbIdsStillRelevant(LocalDate today) {
        Map<Integer, String> ids = new LinkedHashMap<>();
        String sql = "SELECT tmdb_id, title FROM tracked_releases WHERE tmdb_id IS NOT NULL AND track_until >= ?";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, Date.valueOf(today));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) ids.put(rs.getInt(1), rs.getString(2));
            }
        } catch (SQLException e) {
            System.err.println("Database error in tmdbIdsStillRelevant: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Gate for the paid Apify scanners (Instagram/Reddit): active AND the relevance check has
     * evaluated the movie from its cheap X posts without failing it. A movie that just entered its
     * window waits (typically ~1-2h) for that first evaluation, so a junk title is excluded before
     * any Apify run is spent on it. An admin-restored movie (relevance_override) is always eligible.
     */
    public static boolean isApifyEligible(String entity) {
        String sql = "SELECT relevance_override OR relevance_checked_at IS NOT NULL FROM tracked_releases " +
                "WHERE entity_name = ? AND NOT excluded AND " + activeWindowSql();
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

    static boolean isIndianLanguage(String language, Set<String> indianLanguages) {
        return language != null && indianLanguages.contains(language.trim().toLowerCase());
    }
}
