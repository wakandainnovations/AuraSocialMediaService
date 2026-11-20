package com.lit.fire.flame;

import com.google.gson.JsonObject;
import com.lit.fire.api.SocialMediaScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds every India-origin release TMDB knows about and writes it to this week's release file
 * (see {@link ReleaseFileStore}). It no longer writes tracked_releases itself:
 * {@link ReleaseFileListener} loads the latest weekly file into the table, so movies added by
 * hand to the file's "manual" list are tracked exactly like TMDB's.
 *
 * Each pass discovers Indian-language releases opening in Indian theatres (no shorts, festival-only
 * or OTT-only titles) dated [today - tracking.days.after, today + release.discovery.lookahead.days]:
 * the lower bound keeps just-released movies (still inside their post-release window) fresh, and the
 * look-ahead lists movies a week or so before their tracking starts, so admins can review and
 * exclude them in aura-radix before any budget is spent. Known, not-yet-ended movies (in the table
 * or the previous file) that TMDB no longer returns for that range - typically a postponed
 * release - are re-fetched by id so their release date moves with it.
 *
 * The first pass of an ISO week (IST) creates a new file; later passes that week rewrite it. Either
 * way the "tmdb" list is regenerated and the "manual" list is kept: from this week's file as-is,
 * or - for a new week - carried over from the latest older file, minus entries whose tracking
 * window has ended.
 */
public class ReleaseDiscoveryService implements SocialMediaScanner {

    // Fallback for tracking.indian.languages (application.properties). New releases in any other
    // original language (English, foreign co-productions) are skipped, not added.
    static final String DEFAULT_INDIAN_LANGUAGES = "hindi,tamil,telugu,kannada,malayalam,marathi,bengali,punjabi,"
            + "gujarati,odia,assamese,bhojpuri,urdu,sanskrit,konkani,tulu,maithili,rajasthani";

    // Fallback for tracking.auto.track.languages: the only languages scanned by default. Other
    // Indian-language releases are stored excluded, for an admin to restore if worth tracking.
    static final String DEFAULT_AUTO_TRACK_LANGUAGES = "tamil,telugu,kannada,malayalam";

    static int daysBefore() {
        return AppProperties.getIntProperty("tracking.days.before", 7);
    }

    static int daysAfter() {
        return AppProperties.getIntProperty("tracking.days.after", 7);
    }

    static Set<String> indianLanguages() {
        return languages("tracking.indian.languages", DEFAULT_INDIAN_LANGUAGES);
    }

    static Set<String> autoTrackLanguages() {
        return languages("tracking.auto.track.languages", DEFAULT_AUTO_TRACK_LANGUAGES);
    }

    private static Set<String> languages(String key, String fallback) {
        String v = AppProperties.getProperty(key);
        return new HashSet<>(TrackedReleaseSource.splitKeywords(v == null ? fallback : v.toLowerCase()));
    }

    @Override
    public void scan() {
        int daysBefore = daysBefore();
        int daysAfter = daysAfter();
        int lookahead = Math.max(daysBefore, AppProperties.getIntProperty("release.discovery.lookahead.days", 14));
        LocalDate today = TrackedReleaseSource.today();
        Set<String> indianLanguages = indianLanguages();
        Path dir = ReleaseFileStore.dir();
        Path target = ReleaseFileStore.weekFile(dir, today);

        // The previous file is read up front (for its known TMDB ids) and again right before writing
        // (for its manual list), so a hand edit made while TMDB is being queried isn't lost.
        // A file that doesn't parse aborts the pass rather than being overwritten.
        ReleaseFileStore.WeekFile base = readBase(dir, target);

        TmdbReleaseClient tmdb;
        try {
            tmdb = new TmdbReleaseClient();
        } catch (Exception e) {
            throw new RuntimeException("Release discovery disabled: " + e.getMessage(), e);
        }

        LocalDate from = today.minusDays(daysAfter);
        LocalDate to = today.plusDays(lookahead);
        List<TmdbReleaseClient.Release> discovered;
        try {
            discovered = tmdb.discoverIndianReleases(from, to);
        } catch (Exception e) {
            throw new RuntimeException("TMDB discover failed: " + e.getMessage(), e);
        }

        // Movies already known (table or previous file) are kept whatever their language - the
        // listener refreshes existing rows regardless - while new ones must be Indian-language.
        Map<Integer, TmdbReleaseClient.Release> previous = new LinkedHashMap<>();
        if (base != null) {
            for (TmdbReleaseClient.Release r : base.tmdb()) {
                if (!r.releaseDate().plusDays(daysAfter).isBefore(today)) previous.put(r.tmdbId(), r);
            }
        }
        Set<Integer> known = new HashSet<>(previous.keySet());
        known.addAll(TrackedReleaseSource.tmdbIdsStillRelevant(today));

        Map<Integer, TmdbReleaseClient.Release> out = new LinkedHashMap<>();
        int skippedLanguage = 0;
        for (TmdbReleaseClient.Release r : discovered) {
            if (known.contains(r.tmdbId()) || TrackedReleaseSource.isIndianLanguage(r.language(), indianLanguages)) {
                out.put(r.tmdbId(), r);
            } else {
                skippedLanguage++;
            }
        }

        int refreshed = 0;
        int failed = 0;
        for (Integer id : known) {
            if (out.containsKey(id)) continue;
            try {
                TmdbReleaseClient.Release r = tmdb.fetch(id, from);
                if (r != null) {
                    out.put(id, r);
                    refreshed++;
                }
            } catch (Exception e) {
                failed++;
                System.err.println("[Discovery] Failed to refresh tmdb " + id + ": " + e.getMessage());
                // Keep the last known details rather than dropping the movie from the file.
                if (previous.containsKey(id)) out.put(id, previous.get(id));
            }
        }

        List<TmdbReleaseClient.Release> tmdbEntries = new ArrayList<>(out.values());
        tmdbEntries.sort(Comparator.comparing(TmdbReleaseClient.Release::releaseDate)
                .thenComparing(TmdbReleaseClient.Release::title));

        base = readBase(dir, target);
        List<JsonObject> manual = base == null ? List.of()
                : base.path().equals(target) ? base.manualRaw()
                : stillTracked(base.manualRaw(), today, daysAfter);
        try {
            ReleaseFileStore.write(target, ReleaseFileStore.weekName(today), tmdbEntries, manual);
        } catch (Exception e) {
            throw new RuntimeException("Could not write " + target + ": " + e.getMessage(), e);
        }

        System.out.println("[Discovery] " + discovered.size() + " India theatrical release(s) dated " + from + ".." + to
                + " (" + skippedLanguage + " non-Indian-language skipped), " + refreshed + " out-of-range refreshed, "
                + failed + " failed; wrote " + tmdbEntries.size() + " TMDB + " + manual.size() + " manual to " + target
                + (base != null && !base.path().equals(target) ? " (new week; manual carried over from "
                + base.path().getFileName() + ")" : "") + ".");
    }

    /** This week's file if it exists, else the latest older one, else null. Throws if it can't be parsed. */
    private static ReleaseFileStore.WeekFile readBase(Path dir, Path target) {
        Path path;
        try {
            path = Files.exists(target) ? target : ReleaseFileStore.latest(dir);
        } catch (Exception e) {
            throw new RuntimeException("Could not list " + dir + ": " + e.getMessage(), e);
        }
        if (path == null) return null;
        try {
            return ReleaseFileStore.read(path);
        } catch (Exception e) {
            throw new RuntimeException("Not overwriting " + path + ", it doesn't parse (fix it by hand): " + e.getMessage(), e);
        }
    }

    /**
     * Manual entries worth carrying into a new week: those whose tracking window hasn't ended.
     * An entry that doesn't parse is carried over too, so it can still be fixed.
     */
    static List<JsonObject> stillTracked(List<JsonObject> manualRaw, LocalDate today, int daysAfter) {
        List<JsonObject> keep = new ArrayList<>();
        for (JsonObject o : manualRaw) {
            try {
                ReleaseFileStore.ManualRelease m = ReleaseFileStore.manualFromJson(o);
                LocalDate until = m.trackUntil() != null ? m.trackUntil() : m.releaseDate().plusDays(daysAfter);
                if (!until.isBefore(today)) keep.add(o);
            } catch (RuntimeException e) {
                keep.add(o);
            }
        }
        return keep;
    }
}
