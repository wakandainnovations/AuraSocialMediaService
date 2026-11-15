package com.lit.fire.flame;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One-time, on-demand backfill for a single movie across X, Reddit, Instagram, and YouTube, over a
 * caller-supplied trailing window (e.g. "last 3 months"). Unlike HistoricalBackfillService (which
 * backfills a fixed 365-day window for every DB-managed Tamil/Kannada MOVIE entity), this takes an
 * arbitrary entity/keyword pair directly on the command line, so it isn't limited to entities
 * already tracked in search_queries.txt / managed_entities.
 *
 * Cost-conscious by construction:
 *  - Posts only, not comments, on X/Reddit/Instagram (comment collection isn't built for those
 *    platforms yet - see YouTube below - and would mean extra Apify Actor calls per platform).
 *  - YouTube comments ARE collected, at no extra cost: YouTubeMain.search(...) already fetches
 *    comments for every video it finds as part of the native Data API call, which is free within
 *    Google's daily quota.
 *  - X and Instagram go through Apify (X's official API only covers the last 7 days; Instagram's
 *    Graph API only reaches the connected business account's own posts, not hashtag search), at
 *    ~$0.40/1,000 tweets and ~$2.30/1,000 posts respectively. Reddit and YouTube use the
 *    already-integrated low/no-cost paths (Apify Reddit actor with server-side date bounds; free
 *    YouTube Data API quota).
 *  - Each platform's result cap is a tunable ceiling (see application.properties), not a target -
 *    a low-volume campaign hashtag will return far fewer results and cost far less.
 */
public class MovieBackfillService {

    private static final DateTimeFormatter REDDIT_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    // Spacing between platform calls so four back-to-back Apify/API calls for one entity don't
    // land in the same instant; deliberately much shorter than HistoricalBackfillService's 5-10 min
    // inter-ENTITY delay, which exists to stay under Apify's concurrent-run cap across many entities -
    // not a concern here with a single entity run once.
    private static final long INTER_PLATFORM_DELAY_MS = 30_000; // 30 seconds

    public static void run(String entity, List<String> keywords, String category, int months) {
        int instagramLimit = AppProperties.getIntProperty("backfill.instagram.results_limit", 300);
        int redditLimit = AppProperties.getIntProperty("backfill.reddit.max_posts", 300);
        int xLimit = AppProperties.getIntProperty("backfill.x.max_items", 300);
        int youtubeLimit = AppProperties.getIntProperty("backfill.youtube.max_videos", 25);

        Instant cutoff = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(months).toInstant();
        String redditDateFrom = REDDIT_DATE_FORMATTER.format(cutoff);

        System.out.println("[MovieBackfill] Starting one-time backfill for '" + entity + "' -> keywords " + keywords
                + ", past " + months + " month(s) (since " + cutoff + ")...");

        try {
            InstagramApifyService.search(entity, keywords, category, instagramLimit, cutoff);
        } catch (Exception e) {
            System.err.println("[MovieBackfill] Instagram failed for '" + entity + "': " + e.getMessage());
            e.printStackTrace();
        }
        sleep();

        try {
            RedditApifyService.search(entity, keywords, category, redditLimit, redditDateFrom, null);
        } catch (Exception e) {
            System.err.println("[MovieBackfill] Reddit failed for '" + entity + "': " + e.getMessage());
            e.printStackTrace();
        }
        sleep();

        try {
            XApifyService.search(entity, keywords, category, xLimit, cutoff, null);
        } catch (Exception e) {
            System.err.println("[MovieBackfill] X failed for '" + entity + "': " + e.getMessage());
            e.printStackTrace();
        }
        sleep();

        try {
            YouTubeMain.loadConfig();
            YouTubeMain.search(entity, keywords, category, youtubeLimit, cutoff);
        } catch (Exception e) {
            System.err.println("[MovieBackfill] YouTube failed for '" + entity + "': " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[MovieBackfill] Backfill complete for '" + entity + "'.");
    }

    private static void sleep() {
        try {
            Thread.sleep(INTER_PLATFORM_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CLI entry point: entity name, comma-separated keywords, optional category
     * (default "media.movie"), optional months-back window (default 3).
     * Example: Toxic "ToxicTheMovie" media.movie 3
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: MovieBackfillService <entity> <comma,separated,keywords> [category] [months]");
            System.exit(1);
        }
        String entity = args[0];
        List<String> keywords = Arrays.stream(args[1].split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toList());
        String category = args.length > 2 ? args[2] : "media.movie";
        int months = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        run(entity, keywords, category, months);
    }
}
