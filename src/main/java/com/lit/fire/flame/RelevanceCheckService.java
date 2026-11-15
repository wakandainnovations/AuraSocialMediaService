package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hourly check that an actively tracked movie's keywords are actually finding posts about the
 * movie. Generic titles ("Titanic", "Ah", "Dark Chocolate") pull in mostly unrelated posts; on
 * 2026-11-16's data such titles scored 1-5% movie-related while real releases scored 45-100%.
 *
 * <p>For each active, non-excluded movie the score is the share of its X posts (plus YouTube video
 * titles) collected since its tracking window opened that carry movie context: film vocabulary
 * (movie, trailer, review, FDFS, theatre...), the language / film industry, the movie's own hashtags,
 * its native-script title, or its cast/director names (TMDB credits, {@code context_terms}).
 *
 * <ul>
 *   <li>score computed on >= {@code relevance.min.posts} posts and below {@code relevance.min.pct}
 *       -> auto-excluded (unless an admin restored it: relevance_override), with the numbers in the
 *       reason;</li>
 *   <li>enough posts, or the window has been open {@code relevance.grace.hours} with too few posts
 *       to judge (a quiet small release, not junk) -> {@code relevance_checked_at} is set, which is
 *       what lets the paid Apify scanners (Instagram/Reddit) start on it.</li>
 * </ul>
 * X polls each movie hourly, so the X data a decision needs is cheap and arrives first.
 */
public class RelevanceCheckService implements SocialMediaScanner {

    // Film vocabulary that shows a post is about a movie, across English and common transliterations.
    private static final List<String> FILM_TERMS = List.of(
            "movie", "film", "cinema", "trailer", "teaser", "glimpse", "review", "box office", "boxoffice",
            "collection", "theatre", "theater", "theatrical", "release", "releasing", "ott", "song", "bgm",
            "first look", "firstlook", "first single", "blockbuster", "interval", "climax", "fdfs", "premiere",
            "director", "hero", "heroine", "cast", "shooting", "audio launch", "pre release", "pre-release",
            "censor", "screening", "show", "kollywood", "tollywood", "sandalwood", "mollywood", "bollywood",
            "tamil", "telugu", "kannada", "malayalam", "hindi", "marathi", "bengali", "punjabi");

    private static final Pattern LATIN_OR_DIGIT = Pattern.compile("^[\\p{IsLatin}\\p{N}\\p{P}\\p{Zs}]*$");

    @Override
    public void scan() {
        int minPosts = AppProperties.getIntProperty("relevance.min.posts", 30);
        int minPct = AppProperties.getIntProperty("relevance.min.pct", 15);
        int graceHours = AppProperties.getIntProperty("relevance.grace.hours", 3);
        boolean autoExclude = !"false".equalsIgnoreCase(AppProperties.getProperty("relevance.auto.exclude"));
        LocalDate today = TrackedReleaseSource.today();

        int evaluated = 0, excluded = 0;
        for (Candidate c : activeCandidates(today)) {
            List<String> texts = collectedTexts(c);
            if (texts.isEmpty() && !graceElapsed(c, graceHours)) continue;
            Set<String> terms = contextTerms(c);
            long relevant = texts.stream().filter(t -> isMovieRelated(t, terms)).count();
            double pct = texts.isEmpty() ? 0 : Math.round(1000.0 * relevant / texts.size()) / 10.0;
            boolean enough = texts.size() >= minPosts;
            boolean failing = enough && pct < minPct;
            boolean decided = enough || graceElapsed(c, graceHours);

            if (failing && autoExclude && !c.override) {
                exclude(c, String.format(Locale.ROOT,
                        "Auto-excluded: only %.0f%% of %d collected posts look movie-related (keywords match unrelated posts)",
                        pct, texts.size()), pct, texts.size());
                excluded++;
                System.out.println("[Relevance] Excluded '" + c.entity + "': " + pct + "% of " + texts.size() + " posts movie-related.");
            } else {
                record(c, pct, texts.size(), decided && !failing);
            }
            evaluated++;
        }
        System.out.println("[Relevance] Checked " + evaluated + " movie(s), auto-excluded " + excluded + ".");
    }

    /** True when the text mentions any movie-context term (case-insensitive, word-ish match). */
    static boolean isMovieRelated(String text, Set<String> terms) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (LATIN_OR_DIGIT.matcher(term).matches()) {
                // Word-boundary match so "show" doesn't hit "shower", "ott" doesn't hit "bottle".
                if (Pattern.compile("(^|[^\\p{L}\\p{N}])" + Pattern.quote(term) + "($|[^\\p{L}\\p{N}])").matcher(lower).find()) {
                    return true;
                }
            } else if (lower.contains(term)) { // native script: no reliable word boundaries
                return true;
            }
        }
        return false;
    }

    /**
     * Context terms for one movie: film vocabulary, its language, its keywords as #hashtags, its
     * native-script title, and its cast/director names - full names plus their longer name parts
     * (people are usually tagged by one name, e.g. "Dhanush", "#Prabhas").
     */
    static Set<String> contextTerms(Candidate c) {
        Set<String> terms = new LinkedHashSet<>(FILM_TERMS);
        if (c.language != null) terms.add(c.language.toLowerCase(Locale.ROOT));
        for (String k : TrackedReleaseSource.splitKeywords(c.keywords)) {
            String compact = k.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
            if (!compact.isEmpty()) terms.add("#" + compact);
        }
        if (c.originalTitle != null && !LATIN_OR_DIGIT.matcher(c.originalTitle).matches()) {
            terms.add(c.originalTitle.toLowerCase(Locale.ROOT).trim());
        }
        for (String name : TrackedReleaseSource.splitKeywords(c.contextTerms)) {
            String n = name.toLowerCase(Locale.ROOT).trim();
            terms.add(n);
            for (String part : n.split("[\\s.]+")) {
                if (part.length() >= 5) terms.add(part);
            }
        }
        return terms;
    }

    // ---- data access ----

    record Candidate(long id, String entity, String keywords, String language, String originalTitle,
                     String contextTerms, boolean override, Instant activeSince) {
    }

    private static List<Candidate> activeCandidates(LocalDate today) {
        List<Candidate> out = new ArrayList<>();
        String sql = "SELECT id, entity_name, keywords, language, original_title, context_terms, relevance_override, " +
                "GREATEST((track_from::timestamp AT TIME ZONE 'Asia/Kolkata'), created_at) AS active_since " +
                "FROM tracked_releases WHERE NOT excluded AND ? BETWEEN track_from AND track_until";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, Date.valueOf(today));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Candidate(rs.getLong("id"), rs.getString("entity_name"), rs.getString("keywords"),
                            rs.getString("language"), rs.getString("original_title"), rs.getString("context_terms"),
                            rs.getBoolean("relevance_override"), rs.getTimestamp("active_since").toInstant()));
                }
            }
        } catch (SQLException e) {
            System.err.println("[Relevance] Database error loading candidates: " + e.getMessage());
        }
        return out;
    }

    /**
     * X post texts and YouTube video titles stored for the entity since its window opened. Bounded
     * to the window so older same-named data (e.g. a 2026-07 "Black Gold" entity) isn't scored, and
     * capped to the most recent 500 so the check stays cheap.
     */
    private static List<String> collectedTexts(Candidate c) {
        List<String> texts = new ArrayList<>();
        String sql = "(SELECT text AS t, created_at AS ts FROM x_posts WHERE entity = ? AND created_at >= ? AND text IS NOT NULL) " +
                "UNION ALL (SELECT title, published_at::timestamp FROM youtube_videos WHERE entity = ? AND last_refreshed_at >= ? AND title IS NOT NULL) " +
                "ORDER BY ts DESC LIMIT 500";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // x_posts.created_at is stored as UTC "timestamp without time zone".
            Timestamp sinceUtc = Timestamp.valueOf(java.time.LocalDateTime.ofInstant(c.activeSince, java.time.ZoneOffset.UTC));
            pstmt.setString(1, c.entity);
            pstmt.setTimestamp(2, sinceUtc);
            pstmt.setString(3, c.entity);
            pstmt.setTimestamp(4, Timestamp.from(c.activeSince));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) texts.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.err.println("[Relevance] Database error reading posts for '" + c.entity + "': " + e.getMessage());
        }
        return texts;
    }

    private static boolean graceElapsed(Candidate c, int graceHours) {
        return Instant.now().isAfter(c.activeSince.plusSeconds(graceHours * 3600L));
    }

    private static void record(Candidate c, double pct, int sample, boolean markChecked) {
        String sql = "UPDATE tracked_releases SET relevance_pct = ?, relevance_sample = ?, " +
                "relevance_checked_at = CASE WHEN ? THEN COALESCE(relevance_checked_at, NOW()) ELSE relevance_checked_at END, " +
                "updated_at = NOW() WHERE id = ?";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, pct);
            pstmt.setInt(2, sample);
            pstmt.setBoolean(3, markChecked);
            pstmt.setLong(4, c.id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Relevance] Database error saving score for '" + c.entity + "': " + e.getMessage());
        }
    }

    private static void exclude(Candidate c, String reason, double pct, int sample) {
        String sql = "UPDATE tracked_releases SET excluded = TRUE, excluded_reason = ?, excluded_at = NOW(), " +
                "relevance_pct = ?, relevance_sample = ?, relevance_checked_at = COALESCE(relevance_checked_at, NOW()), " +
                "updated_at = NOW() WHERE id = ? AND NOT relevance_override";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, reason);
            pstmt.setDouble(2, pct);
            pstmt.setInt(3, sample);
            pstmt.setLong(4, c.id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Relevance] Database error excluding '" + c.entity + "': " + e.getMessage());
        }
    }
}
