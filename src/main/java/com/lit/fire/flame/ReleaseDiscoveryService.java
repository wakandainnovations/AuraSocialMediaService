package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps tracked_releases populated with every India-origin release TMDB knows about, so the
 * scanners no longer depend on a hand-picked list.
 *
 * Each pass discovers Indian-language releases opening in Indian theatres (no shorts, festival-only
 * or OTT-only titles) dated [today - tracking.days.after, today + release.discovery.lookahead.days]:
 * the lower bound keeps just-released movies (still inside their post-release window) fresh, and the
 * look-ahead lists movies a week or so before their tracking starts, so admins can review and
 * exclude them in aura-radix before any budget is spent. Known, not-yet-ended rows that TMDB no
 * longer returns for that range (typically a postponed release) are re-fetched by id so their
 * release date and tracking window move with it.
 */
public class ReleaseDiscoveryService implements SocialMediaScanner {

    // Fallback for tracking.indian.languages (application.properties). New releases in any other
    // original language (English, foreign co-productions) are skipped, not added.
    static final String DEFAULT_INDIAN_LANGUAGES = "hindi,tamil,telugu,kannada,malayalam,marathi,bengali,punjabi,"
            + "gujarati,odia,assamese,bhojpuri,urdu,sanskrit,konkani,tulu,maithili,rajasthani";

    // Fallback for tracking.auto.track.languages: the only languages scanned by default. Other
    // Indian-language releases are stored excluded, for an admin to restore if worth tracking.
    static final String DEFAULT_AUTO_TRACK_LANGUAGES = "tamil,telugu,kannada,malayalam";

    @Override
    public void scan() {
        int daysBefore = AppProperties.getIntProperty("tracking.days.before", 7);
        int daysAfter = AppProperties.getIntProperty("tracking.days.after", 7);
        int lookahead = Math.max(daysBefore, AppProperties.getIntProperty("release.discovery.lookahead.days", 14));
        LocalDate today = TrackedReleaseSource.today();
        Set<String> indianLanguages = new HashSet<>(TrackedReleaseSource.splitKeywords(
                AppProperties.getProperty("tracking.indian.languages") == null ? DEFAULT_INDIAN_LANGUAGES
                        : AppProperties.getProperty("tracking.indian.languages").toLowerCase()));
        Set<String> autoTrackLanguages = new HashSet<>(TrackedReleaseSource.splitKeywords(
                AppProperties.getProperty("tracking.auto.track.languages") == null ? DEFAULT_AUTO_TRACK_LANGUAGES
                        : AppProperties.getProperty("tracking.auto.track.languages").toLowerCase()));

        TmdbReleaseClient tmdb;
        try {
            tmdb = new TmdbReleaseClient();
        } catch (Exception e) {
            throw new RuntimeException("Release discovery disabled: " + e.getMessage(), e);
        }

        LocalDate from = today.minusDays(daysAfter);
        LocalDate to = today.plusDays(lookahead);
        List<TmdbReleaseClient.Release> releases;
        try {
            releases = tmdb.discoverIndianReleases(from, to);
        } catch (Exception e) {
            throw new RuntimeException("TMDB discover failed: " + e.getMessage(), e);
        }

        int inserted = 0;
        int failed = 0;
        Set<Integer> seen = new HashSet<>();
        for (TmdbReleaseClient.Release r : releases) {
            seen.add(r.tmdbId());
            try {
                if (TrackedReleaseSource.upsertDiscovered(r, daysBefore, daysAfter, indianLanguages, autoTrackLanguages)) inserted++;
            } catch (Exception e) {
                failed++;
                System.err.println("[Discovery] Failed to save '" + r.title() + "' (tmdb " + r.tmdbId() + "): " + e.getMessage());
            }
        }

        int refreshed = 0;
        for (Integer id : TrackedReleaseSource.tmdbIdsStillRelevant(today)) {
            if (seen.contains(id)) continue;
            try {
                TmdbReleaseClient.Release r = tmdb.fetch(id, from);
                if (r != null) {
                    TrackedReleaseSource.upsertDiscovered(r, daysBefore, daysAfter, indianLanguages, autoTrackLanguages);
                    refreshed++;
                }
            } catch (Exception e) {
                System.err.println("[Discovery] Failed to refresh tmdb " + id + ": " + e.getMessage());
            }
        }

        System.out.println("[Discovery] " + releases.size() + " India theatrical release(s) dated " + from + ".." + to
                + ": " + inserted + " new, " + refreshed + " out-of-range refreshed, " + failed + " failed.");
    }
}
