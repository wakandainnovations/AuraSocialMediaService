package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * One-time startup job that backfills the past year of Instagram and Reddit data (via
 * InstagramApifyService / RedditApifyService) for every managed_entities row of type MOVIE whose
 * language is Tamil or Kannada, using entity_keywords for the search terms. Unlike the
 * ScannableService loop in Main, this runs once and exits; it does not re-schedule itself.
 */
public class HistoricalBackfillService implements Runnable {

    private static final String ENTITY_TYPE = "MOVIE";
    private static final List<String> TARGET_LANGUAGES = List.of("Tamil", "Kannada");
    private static final int BACKFILL_WINDOW_DAYS = 365;
    private static final DateTimeFormatter REDDIT_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    @Override
    public void run() {
        System.out.println("[Backfill] Starting one-time historical backfill (Instagram + Reddit) for "
                + ENTITY_TYPE + " entities in " + TARGET_LANGUAGES + ", past " + BACKFILL_WINDOW_DAYS + " days...");

        int instagramResultsLimit = AppProperties.getIntProperty("backfill.instagram.results_limit", 300);
        int redditMaxPosts = AppProperties.getIntProperty("backfill.reddit.max_posts", 300);
        Instant cutoff = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS);
        String redditDateFrom = REDDIT_DATE_FORMATTER.format(cutoff);

        List<JsonObject> rows = DatabaseService.getManagedEntityKeywords(ENTITY_TYPE, TARGET_LANGUAGES);
        if (rows.isEmpty()) {
            System.out.println("[Backfill] No " + ENTITY_TYPE + " entities with keywords found for languages "
                    + TARGET_LANGUAGES + "; nothing to backfill.");
            return;
        }

        // Group keyword rows by entity so, like the regular scanners, all of an entity's keywords
        // are collected in a single Instagram/Reddit run instead of once per keyword.
        Map<String, List<JsonObject>> rowsByEntity = new LinkedHashMap<>();
        for (JsonObject row : rows) {
            rowsByEntity.computeIfAbsent(row.get("entity").getAsString(), k -> new ArrayList<>()).add(row);
        }
        System.out.println("[Backfill] " + rowsByEntity.size() + " entities queued.");

        for (Map.Entry<String, List<JsonObject>> entry : rowsByEntity.entrySet()) {
            String entity = entry.getKey();
            List<String> keywords = entry.getValue().stream()
                    .map(row -> row.get("keyword").getAsString())
                    .distinct()
                    .collect(Collectors.toList());
            String category = entry.getValue().get(0).get("category").getAsString();

            System.out.println("\n[Backfill] Processing entity '" + entity + "' -> keywords " + keywords);

            try {
                InstagramApifyService.search(entity, keywords, category, instagramResultsLimit, cutoff);
            } catch (Exception e) {
                System.err.println("[Backfill] Instagram backfill failed for '" + entity + "': " + e.getMessage());
                e.printStackTrace();
            }
            sleepRandomDelay();

            try {
                RedditApifyService.search(entity, keywords, category, redditMaxPosts, redditDateFrom, null);
            } catch (Exception e) {
                System.err.println("[Backfill] Reddit backfill failed for '" + entity + "': " + e.getMessage());
                e.printStackTrace();
            }
            sleepRandomDelay();
        }

        System.out.println("[Backfill] Historical backfill complete.");
    }

    // Matches the inter-entity delay used by InstagramApifyService/RedditApifyService's regular
    // scans, to stay under Apify's concurrent-run limits.
    private static void sleepRandomDelay() {
        long delay = ThreadLocalRandom.current().nextLong(300000, 600001);
        System.out.println("[Backfill] Waiting " + (delay / 60000) + " minutes before the next step...");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
