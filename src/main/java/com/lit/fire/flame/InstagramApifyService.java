package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lit.fire.api.SocialMediaScanner;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Collects Instagram hashtag posts via the official Apify Actor "apify/instagram-hashtag-scraper"
 * and stores them through the same DatabaseService.saveInstagramPosts(...) path used by the native
 * Instagram Graph API integration (InstagramService), which this class does not touch.
 */
public class InstagramApifyService implements SocialMediaScanner {

    private static final String ACTOR_ID = "apify/instagram-hashtag-scraper";
    // The hashtag scraper never returns video play/view counts (that data isn't part of its output
    // schema); this dedicated Actor accepts direct reel/post URLs and does return them
    // (videoPlayCount/videoViewCount), so it's used as a follow-up call to backfill "views".
    private static final String REEL_ACTOR_ID = "apify/instagram-reel-scraper";
    // Reel Scraper is billed per item, unlike the flat-fee hashtag scraper, so view-count
    // enrichment is capped to the entity's top N hashtag-scraper results rather than run for all of them.
    private static final int VIEW_ENRICHMENT_LIMIT = 20;
    // Off until the Apify account is topped up; flip to true to resume backfilling "views" via
    // REEL_ACTOR_ID.
    private static final boolean VIEW_ENRICHMENT_ENABLED = false;
    // Matches the format DatabaseService.saveInstagramPosts(...) parses its "timestamp" field with.
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    // Platform key used for DatabaseService.getLastFetchTime/updateLastFetchTime(...)'s per-entity
    // fetch cursor.
    private static final String PLATFORM = "instagram";
    // A single thread, so this scanner's Actor runs never overlap.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TrackedEntityScheduler entitySchedule = new TrackedEntityScheduler("Apify/Instagram", 300);

    private static int numberOfPosts;

    /**
     * Scrapes all of an entity's hashtags in a single Actor run (this Actor's "hashtags" input is
     * an array, unlike the previous one-hashtag-per-run Actor), so there's no need for an
     * inter-hashtag delay anymore. Posts collected under several of the entity's hashtags are
     * de-duplicated before saving, mirroring InstagramService's behavior (composite id
     * postId_entity in DatabaseService.saveInstagramPosts(...)).
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        search(entity, keywords, category, numberOfPosts, null);
    }

    /**
     * Same as {@link #search(String, List, String)} but lets the caller override the per-hashtag
     * results limit and optionally drop posts older than {@code notOlderThan}. Used by
     * HistoricalBackfillService, which runs concurrently with the regular scheduled scan and so
     * can't share the mutable {@code numberOfPosts} field. This Actor has no server-side date
     * filter, so "not older than" is enforced by filtering the returned items client-side.
     */
    public static int search(String entity, List<String> keywords, String category, int resultsLimit, Instant notOlderThan) throws Exception {
        List<String> hashtagTerms = normalizeHashtags(keywords);
        if (hashtagTerms.isEmpty()) {
            return 0;
        }
        System.out.println("[Apify/Instagram] Scraping hashtags " + hashtagTerms + " for entity '" + entity + "'...");

        JsonArray hashtags = new JsonArray();
        hashtagTerms.forEach(hashtags::add);

        JsonObject actorInput = new JsonObject();
        actorInput.add("hashtags", hashtags);
        actorInput.addProperty("resultsType", "posts");
        actorInput.addProperty("resultsLimit", resultsLimit);

        JsonArray items = ApifyClient.runActorAndGetDatasetItems(ACTOR_ID, actorInput);
        System.out.println("[Apify/Instagram] '" + entity + "' returned " + items.size() + " item(s).");

        JsonArray entityPosts = new JsonArray();
        Set<String> seenPostIds = new HashSet<>();
        for (JsonElement el : items) {
            JsonObject post = toInstagramPost(el.getAsJsonObject());
            if (post == null || !seenPostIds.add(post.get("id").getAsString())) {
                // Dedupe within the entity so a post under several of its hashtags is kept once.
                continue;
            }
            if (notOlderThan != null) {
                ZonedDateTime postTime = ZonedDateTime.parse(post.get("timestamp").getAsString(), TIMESTAMP_FORMATTER);
                if (postTime.toInstant().isBefore(notOlderThan)) {
                    continue;
                }
            }
            entityPosts.add(post);
        }

        enrichViewCounts(entityPosts, entity);

        if (entityPosts.size() > 0) {
            DatabaseService.saveInstagramPosts(entityPosts, entity, keywords, category);
        }
        return entityPosts.size();
    }

    /**
     * Backfills "views" on video posts among the entity's top {@link #VIEW_ENRICHMENT_LIMIT}
     * hashtag-scraper results by looking them up individually via the Instagram Reel Scraper Actor,
     * which (unlike the hashtag scraper) returns videoPlayCount/videoViewCount. Image/Sidecar posts
     * are skipped since that data doesn't apply to them. Posts already known from a previous fetch
     * (same underlying Instagram post scraped under another entity's hashtags) are filled in from the
     * database instead of spending another Actor call on them - see
     * DatabaseService.getKnownInstagramViews(...). Runs best-effort: a failure here shouldn't stop
     * the entity's posts from being saved without view counts.
     */
    private static void enrichViewCounts(JsonArray entityPosts, String entity) {
        if (!VIEW_ENRICHMENT_ENABLED) {
            return;
        }
        Map<String, JsonObject> videoPostsById = new LinkedHashMap<>();
        int limit = Math.min(entityPosts.size(), VIEW_ENRICHMENT_LIMIT);
        for (int i = 0; i < limit; i++) {
            JsonObject post = entityPosts.get(i).getAsJsonObject();
            if ("VIDEO".equals(post.get("media_type").getAsString())) {
                videoPostsById.put(post.get("id").getAsString(), post);
            }
        }
        if (videoPostsById.isEmpty()) {
            return;
        }

        Map<String, Integer> knownViews = DatabaseService.getKnownInstagramViews(new ArrayList<>(videoPostsById.keySet()));
        for (Map.Entry<String, Integer> known : knownViews.entrySet()) {
            videoPostsById.remove(known.getKey()).addProperty("views", known.getValue());
        }
        if (videoPostsById.isEmpty()) {
            if (!knownViews.isEmpty()) {
                System.out.println("[Apify/Instagram] '" + entity + "': reused " + knownViews.size() + " already-known view count(s), no Reel Scraper call needed.");
            }
            return;
        }

        try {
            JsonArray permalinks = new JsonArray();
            for (JsonObject post : videoPostsById.values()) {
                permalinks.add(post.get("permalink").getAsString());
            }
            JsonObject actorInput = new JsonObject();
            actorInput.add("username", permalinks);

            JsonArray reelItems = ApifyClient.runActorAndGetDatasetItems(REEL_ACTOR_ID, actorInput);
            for (JsonElement el : reelItems) {
                JsonObject reelItem = el.getAsJsonObject();
                if (!reelItem.has("id") || reelItem.get("id").isJsonNull()) {
                    continue;
                }
                JsonObject post = videoPostsById.get(reelItem.get("id").getAsString());
                if (post == null) {
                    continue;
                }
                if (reelItem.has("videoPlayCount") && !reelItem.get("videoPlayCount").isJsonNull()) {
                    post.add("views", reelItem.get("videoPlayCount"));
                } else if (reelItem.has("videoViewCount") && !reelItem.get("videoViewCount").isJsonNull()) {
                    post.add("views", reelItem.get("videoViewCount"));
                }
            }
            System.out.println("[Apify/Instagram] '" + entity + "': fetched view counts for " + videoPostsById.size() +
                    " video post(s) via Reel Scraper" + (knownViews.isEmpty() ? "" : " (" + knownViews.size() + " more reused from an earlier fetch)") + ".");
        } catch (Exception e) {
            System.err.println("[Apify/Instagram] '" + entity + "': view-count enrichment failed, continuing without it. " + e.getMessage());
        }
    }

    /**
     * Strips everything but letters (incl. combining marks, so Indic scripts stay intact), digits and underscores, then de-dupes case-insensitively
     * (e.g. "Ayogya 2" and "Ayogya2" both normalize to the same hashtag), preserving first-seen
     * form. Instagram hashtags can't contain spaces or punctuation, and the Actor rejects the whole
     * run (HTTP 400 invalid-input) if any hashtag contains characters like ' - : . & etc., so a
     * title such as "Bachelor Party D'eux" must become "BachelorPartyDeux". De-duping also stops a
     * "pretty" keyword variant from wasting one of the Actor's billed hashtag slots.
     */
    static List<String> normalizeHashtags(List<String> keywords) {
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (String kw : keywords) {
            String compact = kw.replaceAll("[^\\p{L}\\p{M}\\p{N}_]", "");
            if (compact.isEmpty()) {
                continue;
            }
            byNormalized.putIfAbsent(compact.toLowerCase(), compact);
        }
        return new ArrayList<>(byNormalized.values());
    }

    /**
     * Reshapes an Apify dataset item into the field set DatabaseService.saveInstagramPosts(...)
     * requires (id, caption, media_type, media_url, permalink, timestamp, username, like_count,
     * comments_count) so that method can be used unmodified.
     */
    private static JsonObject toInstagramPost(JsonObject item) {
        if (!item.has("id") || item.get("id").isJsonNull()) {
            return null;
        }

        JsonObject post = new JsonObject();
        post.addProperty("id", item.get("id").getAsString());
        post.addProperty("caption", stringOrDefault(item, "caption", ""));
        post.addProperty("media_type", mapMediaType(item));

        String mediaUrl = stringOrNull(item, "displayUrl");
        if (mediaUrl == null) {
            mediaUrl = stringOrNull(item, "videoUrl");
        }
        if (mediaUrl != null) {
            post.addProperty("media_url", mediaUrl);
        }

        post.addProperty("permalink", stringOrDefault(item, "url", ""));
        post.addProperty("timestamp", formatTimestamp(item));

        String username = stringOrNull(item, "ownerUsername");
        if (username != null) {
            post.addProperty("username", username);
        }

        // Instagram reports a hidden/disabled like count as -1; that's "unknown", not "zero".
        post.addProperty("like_count", nonNegativeIntOrDefault(item, "likesCount", 0));
        post.addProperty("comments_count", intOrDefault(item, "commentsCount", 0));

        // Apify-only extras: DatabaseService.saveInstagramPosts stores these in dedicated nullable
        // columns; native Graph API posts never set these keys, so they stay SQL NULL there.
        // This Actor doesn't return follower count/verification status, so those two columns stay
        // NULL for Apify-sourced rows too (only the native Graph API path can populate them).
        copyIfPresent(post, "shortcode", item, "shortCode");
        copyIfPresent(post, "product_type", item, "productType");
        copyIfPresent(post, "author_id", item, "ownerId");
        copyIfPresent(post, "author_full_name", item, "ownerFullName");
        copyIfPresent(post, "video_url", item, "videoUrl");
        copyIfPresent(post, "duration_seconds", item, "videoDuration");
        String hashtags = joinArray(item, "hashtags");
        if (hashtags != null) {
            post.addProperty("hashtags", hashtags);
        }
        String mentions = joinArray(item, "mentions");
        if (mentions != null) {
            post.addProperty("mentions", mentions);
        }
        copyIfPresent(post, "is_paid_partnership", item, "paidPartnership");
        if (item.has("videoPlayCount") && !item.get("videoPlayCount").isJsonNull()) {
            post.add("views", item.get("videoPlayCount"));
        } else {
            copyIfPresent(post, "views", item, "igPlayCount");
        }
        copyIfPresent(post, "reshare_count", item, "reshareCount");
        copyIfPresent(post, "location_name", item, "locationName");
        return post;
    }

    // Copies a field through as-is (preserving its JSON type) only when present and non-null, so
    // DatabaseService's has()-check writes a real SQL NULL for anything the Actor didn't return.
    private static void copyIfPresent(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        if (source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey));
        }
    }

    private static String joinArray(JsonObject item, String key) {
        if (!item.has(key) || !item.get(key).isJsonArray()) {
            return null;
        }
        JsonArray arr = item.getAsJsonArray(key);
        List<String> values = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonNull()) {
                values.add(el.getAsString());
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    // This Actor reports "type" as "Image"/"Video"/"Sidecar", but InstagramService (native Graph
    // API path) writes the same column as "IMAGE"/"VIDEO"/"CAROUSEL_ALBUM" ("Sidecar" is
    // Instagram's internal name for a carousel post). Normalize so instagram_posts stays
    // consistent regardless of source.
    private static String mapMediaType(JsonObject item) {
        String type = stringOrNull(item, "type");
        if (type == null) {
            return "UNKNOWN";
        }
        switch (type) {
            case "Image": return "IMAGE";
            case "Video": return "VIDEO";
            case "Sidecar": return "CAROUSEL_ALBUM";
            default: return type.toUpperCase();
        }
    }

    private static String formatTimestamp(JsonObject item) {
        Instant instant = parseFlexibleInstant(stringOrNull(item, "timestamp"));
        if (instant == null) {
            instant = Instant.now();
        }
        return TIMESTAMP_FORMATTER.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant parseFlexibleInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(value));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String stringOrDefault(JsonObject obj, String key, String defaultValue) {
        String value = stringOrNull(obj, key);
        return value != null ? value : defaultValue;
    }

    private static int intOrDefault(JsonObject obj, String key, int defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static int nonNegativeIntOrDefault(JsonObject obj, String key, int defaultValue) {
        int value = intOrDefault(obj, key, defaultValue);
        return value < 0 ? defaultValue : value;
    }

    @Override
    public void scan() {
        numberOfPosts = AppProperties.getIntProperty("number.of.posts", 10);
        // Runs on every scan() (Main calls it hourly) to pick up tracking changes; each entity is
        // polled on TrackedEntityScheduler's adaptive interval (4h, backing off to 24h when quiet).
        entitySchedule.sync(scheduler, InstagramApifyService::poll);
    }

    // One entity's scheduled poll. Returns how many new posts were saved, or SKIPPED.
    private static int poll(String entity, List<String> keywords, String category, Instant previousPollAt) throws Exception {
        // Paid run: wait until the relevance check has cleared this movie from its X posts.
        if (!TrackedReleaseSource.isApifyEligible(entity)) {
            System.out.println("[Apify/Instagram] Skipping '" + entity + "' (awaiting relevance check).");
            return TrackedEntityScheduler.SKIPPED;
        }
        System.out.println("[Apify/Instagram] Processing entity: " + entity + " -> keywords " + keywords);
        // This Actor has no server-side date filter, so it always returns (and bills) up to
        // numberOfPosts items regardless of what's new - the notOlderThan filter only avoids
        // re-saving posts already stored. Polling quiet movies less often is the cost lever here.
        // On failure the cursor is left unchanged, so nothing is skipped.
        Instant lastFetch = DatabaseService.getLastFetchTime(PLATFORM, entity);
        Instant scanStart = Instant.now();
        int saved = search(entity, keywords, category, numberOfPosts, lastFetch);
        DatabaseService.updateLastFetchTime(PLATFORM, entity, scanStart);
        return saved;
    }
}
