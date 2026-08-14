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
import java.util.stream.Collectors;

/**
 * Collects Instagram hashtag posts via the official Apify Actor "apify/instagram-hashtag-scraper"
 * and stores them through the same DatabaseService.saveInstagramPosts(...) path used by the native
 * Instagram Graph API integration (InstagramService), which this class does not touch.
 */
public class InstagramApifyService implements SocialMediaScanner {

    private static final String ACTOR_ID = "apify/instagram-hashtag-scraper";
    // Matches the format DatabaseService.saveInstagramPosts(...) parses its "timestamp" field with.
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    // Platform key used for DatabaseService.getLastFetchTime/updateLastFetchTime(...)'s per-entity
    // fetch cursor.
    private static final String PLATFORM = "instagram";
    // Fixed delay between consecutive Apify calls (one per entity), so calls aren't back-to-back.
    private static final long ENTITY_DELAY_MS = 60 * 60 * 1000; // 1 hour

    private static int numberOfPosts;

    private static List<JsonObject> loadInputQueries() throws Exception {
        List<JsonObject> inputQueries = new ArrayList<>();
        try (InputStream input = InstagramApifyService.class.getClassLoader().getResourceAsStream("search_queries.txt")) {
            if (input == null) {
                System.out.println("Sorry, unable to find search_queries.txt");
                return inputQueries;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                inputQueries = reader.lines()
                        .map(line -> JsonParser.parseString(line).getAsJsonObject())
                        .collect(Collectors.toList());
            }
        }
        return inputQueries;
    }

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
    public static void search(String entity, List<String> keywords, String category, int resultsLimit, Instant notOlderThan) throws Exception {
        List<String> hashtagTerms = normalizeHashtags(keywords);
        if (hashtagTerms.isEmpty()) {
            return;
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

        if (entityPosts.size() > 0) {
            DatabaseService.saveInstagramPosts(entityPosts, entity, keywords, category);
        }
    }

    /**
     * Strips spaces and de-dupes case-insensitively (e.g. "Ayogya 2" and "Ayogya2" both normalize
     * to the same hashtag), preserving first-seen form. Instagram hashtags can't contain spaces, so
     * without this a "pretty" keyword variant would just waste one of the Actor's billed hashtag
     * slots re-scraping the same tag as its space-free sibling.
     */
    static List<String> normalizeHashtags(List<String> keywords) {
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (String kw : keywords) {
            String compact = kw.replaceAll("\\s+", "");
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
        try {
            numberOfPosts = AppProperties.getIntProperty("number.of.posts", 10);
            System.out.println("Initializing Instagram Apify Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            // Group keyword lines by entity and process once per entity so a post shared across the
            // entity's hashtags is collected and stored only once.
            Map<String, List<JsonObject>> queriesByEntity = new LinkedHashMap<>();
            for (JsonObject inputQuery : inputQueries) {
                String entity = inputQuery.get("entity").getAsString();
                queriesByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).add(inputQuery);
            }

            for (Map.Entry<String, List<JsonObject>> entry : queriesByEntity.entrySet()) {
                String entity = entry.getKey();
                List<JsonObject> queries = entry.getValue();
                List<String> keywords = queries.stream()
                        .map(q -> q.get("keyword").getAsString())
                        .collect(Collectors.toList());
                String category = queries.get(0).get("category").getAsString();
                System.out.println("\n[Apify/Instagram] Processing entity: " + entity + " -> keywords " + keywords);

                // This Actor has no server-side date filter (unlike Reddit's), so it always returns
                // up to numberOfPosts items regardless of what's new - the notOlderThan filter below
                // only avoids re-saving posts already stored under this entity's last fetch, it does
                // not reduce what's billed for the call itself. Frequency (see Main.APIFY_SUCCESS_DELAY_MS)
                // is the actual cost lever for this scanner.
                Instant lastFetch = DatabaseService.getLastFetchTime(PLATFORM, entity);
                Instant scanStart = Instant.now();
                search(entity, keywords, category, numberOfPosts, lastFetch);
                DatabaseService.updateLastFetchTime(PLATFORM, entity, scanStart);

                System.out.println(System.currentTimeMillis() + ": Waiting 60 minutes before the next entity...");
                Thread.sleep(ENTITY_DELAY_MS);
            }
        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the Instagram Apify scan.");
            e.printStackTrace();
        }
    }
}
