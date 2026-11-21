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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Collects Reddit posts via the Apify Actor "fatihtahta/reddit-scraper-search-fast" and stores
 * them through the same DatabaseService.saveRedditPosts(...) path used by the native Reddit OAuth
 * integration (RedditAuthClientWithSearch), which this class does not touch.
 *
 * Chosen over harshmaur/reddit-scraper (identical real-world output) for a ~25% lower per-result
 * rate and no per-run base fee, and over trudax/reddit-scraper-lite, which mixed comments into
 * results even with comments disabled.
 */
public class RedditApifyService implements SocialMediaScanner {

    private static final String ACTOR_ID = "fatihtahta/reddit-scraper-search-fast";
    // Platform key used for DatabaseService.getLastFetchTime/updateLastFetchTime(...)'s per-entity
    // fetch cursor.
    private static final String PLATFORM = "reddit";
    // A single thread, so this scanner's Actor runs never overlap.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TrackedEntityScheduler entitySchedule = new TrackedEntityScheduler("Apify/Reddit", 300);
    // The Actor rejects anything but a strict YYYY-MM-DD for dateFrom/dateTo (400 invalid-input),
    // so Instant.toString()'s full ISO-8601 timestamp (e.g. "2026-08-04T10:15:30.123Z") can't be
    // passed through as-is.
    private static final java.time.format.DateTimeFormatter DATE_FORMATTER =
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private static int numberOfPosts;

    /**
     * Scrapes a single entity in one Actor run, passing all of the entity's keywords as `queries`
     * so a post surfaced by several of the entity's keywords is still one dataset item, mirroring
     * the single OR'd-query-per-entity approach used elsewhere in this project.
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        search(entity, keywords, category, numberOfPosts, null, null);
    }

    /**
     * Same as {@link #search(String, List, String)} but lets the caller override the post cap and
     * bound results to a [dateFrom, dateTo] window (strict YYYY-MM-DD per the Actor's input schema;
     * either may be null). Used by HistoricalBackfillService, which runs concurrently with the
     * regular scheduled scan
     * and so can't share the mutable {@code numberOfPosts} field. Unlike Instagram's Actor, this
     * one supports server-side date bounds, so no client-side post-filtering is needed.
     */
    // Returns the posts it saved.
    public static JsonArray search(String entity, List<String> keywords, String category, int maxPosts, String dateFrom, String dateTo) throws Exception {
        System.out.println("[Apify/Reddit] Searching entity '" + entity + "' -> keywords " + keywords);

        JsonArray queries = new JsonArray();
        keywords.forEach(queries::add);

        JsonObject actorInput = new JsonObject();
        actorInput.add("queries", queries);
        actorInput.addProperty("sort", "new");
        actorInput.addProperty("maxPosts", maxPosts);
        actorInput.addProperty("scrapeComments", false);
        if (dateFrom != null) {
            actorInput.addProperty("dateFrom", dateFrom);
            actorInput.addProperty("timeframe", resolveTimeframe(dateFrom));
            // Without this, the Actor's default relevance-ranked traversal can under-cover the
            // tail of a wide date window; forcing chronological order + wider traversal is the
            // Actor author's documented way to get full coverage over the whole [dateFrom, now]
            // window (whether that's the ~365-day historical backfill or a short incremental gap
            // since the last scan).
            actorInput.addProperty("forceSortNewForTimeFilteredRuns", true);
            actorInput.addProperty("maximize_coverage", true);
        }
        if (dateTo != null) {
            actorInput.addProperty("dateTo", dateTo);
        }

        JsonArray items = ApifyClient.runActorAndGetDatasetItems(ACTOR_ID, actorInput);
        System.out.println("[Apify/Reddit] '" + entity + "' returned " + items.size() + " item(s).");

        JsonArray posts = new JsonArray();
        for (JsonElement el : items) {
            JsonObject item = el.getAsJsonObject();
            String kind = stringOrNull(item, "kind");
            if (kind != null && !kind.equals("post")) {
                continue; // Only posts map to DatabaseService.saveRedditPosts(...)'s schema.
            }
            JsonObject post = toRedditPost(item);
            if (post != null) {
                posts.add(post);
            }
        }

        if (posts.size() > 0) {
            DatabaseService.saveRedditPosts(posts, entity, keywords, category);
        }
        return posts;
    }

    /**
     * Reshapes an Apify dataset item into the field set DatabaseService.saveRedditPosts(...)
     * requires (id, title, text, created_utc, permalink, author, score, num_comments) so that
     * method can be used unmodified.
     */
    private static JsonObject toRedditPost(JsonObject item) {
        String id = stringOrNull(item, "id");
        if (id == null) {
            return null;
        }

        JsonObject post = new JsonObject();
        post.addProperty("id", id);

        String title = stringOrNull(item, "title");
        if (title != null) {
            post.addProperty("title", title);
        }
        String body = stringOrNull(item, "body");
        if (body != null) {
            post.addProperty("text", body);
        }

        post.addProperty("created_utc", resolveCreatedUtc(item));

        String permalink = stringOrNull(item, "url");
        if (permalink == null) {
            String relPermalink = stringOrNull(item, "permalink");
            if (relPermalink != null) {
                permalink = relPermalink.startsWith("http") ? relPermalink : "https://www.reddit.com" + relPermalink;
            }
        }
        if (permalink != null) {
            post.addProperty("permalink", permalink);
        }

        String author = stringOrNull(item, "author");
        if (author != null) {
            post.addProperty("author", author);
        }

        post.addProperty("score", intOrDefault(item, "score", 0));
        post.addProperty("num_comments", intOrDefault(item, "num_comments", 0));

        // Apify-only extras: DatabaseService.saveRedditPosts stores these in dedicated nullable
        // columns; native OAuth-search posts never set these keys, so they stay SQL NULL there.
        copyIfPresent(post, "flair", item, "flair");
        copyIfPresent(post, "post_type", item, "post_hint");
        String communityName = firstNonNull(item, "subreddit_name_prefixed", "subreddit");
        if (communityName != null) {
            post.addProperty("community_name", communityName);
        }
        copyIfPresent(post, "subreddit_subscribers", item, "subreddit_subscribers");
        copyIfPresent(post, "upvote_ratio", item, "upvote_ratio");
        copyIfPresent(post, "nsfw", item, "over_18");
        copyIfPresent(post, "spoiler", item, "spoiler");
        copyIfPresent(post, "locked", item, "locked");
        copyIfPresent(post, "stickied", item, "stickied");
        copyIfPresent(post, "total_awards_received", item, "total_awards_received");
        copyIfPresent(post, "gilded", item, "gilded");
        copyIfPresent(post, "domain", item, "domain");
        copyIfPresent(post, "word_count", item, "word_count");
        copyIfPresent(post, "author_flair_text", item, "author_flair_text");
        return post;
    }

    // Picks the narrowest Reddit search timeframe ("day"/"week"/"month"/"year") that still covers
    // [dateFrom, now]. This is used both by the ~365-day one-time backfill (-> "year", the previous
    // hardcoded value) and by the regular scan's short incremental cursor (typically <1 day old
    // -> "day"), instead of always requesting a full year of search coverage for a gap of a few hours.
    private static String resolveTimeframe(String dateFrom) {
        Instant from = parseDateFromAsInstant(dateFrom);
        if (from == null) {
            return "year";
        }
        long daysAgo = ChronoUnit.DAYS.between(from, Instant.now());
        if (daysAgo <= 1) {
            return "day";
        } else if (daysAgo <= 7) {
            return "week";
        } else if (daysAgo <= 31) {
            return "month";
        }
        return "year";
    }

    private static Instant parseDateFromAsInstant(String dateFrom) {
        try {
            return Instant.parse(dateFrom);
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateFrom).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    // Copies a field through as-is (preserving its JSON type) only when present and non-null, so
    // DatabaseService's has()-check writes a real SQL NULL for anything the Actor didn't return.
    private static void copyIfPresent(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        if (source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey));
        }
    }

    // Despite the name, this Actor's "created_utc" is an ISO-8601 string (e.g.
    // "2026-08-01T08:04:40.000Z"), not a raw epoch number - confirmed against a live run.
    private static long resolveCreatedUtc(JsonObject item) {
        String raw = stringOrNull(item, "created_utc");
        if (raw != null) {
            try {
                return Instant.parse(raw).getEpochSecond();
            } catch (Exception e) {
                try {
                    return Long.parseLong(raw);
                } catch (Exception ignored) {
                    // fall through to "now"
                }
            }
        }
        return Instant.now().getEpochSecond();
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String firstNonNull(JsonObject obj, String... keys) {
        for (String key : keys) {
            String value = stringOrNull(obj, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int intOrDefault(JsonObject obj, String key, int defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    @Override
    public void scan() {
        numberOfPosts = AppProperties.getIntProperty("number.of.posts", 10);
        // Runs on every scan() (Main calls it hourly) to pick up tracking changes; each entity is
        // polled on TrackedEntityScheduler's adaptive interval (4h, backing off to 24h when quiet).
        entitySchedule.sync(scheduler, RedditApifyService::poll);
    }

    // One entity's scheduled poll: how many posts newer than the last fetch came back, or SKIPPED.
    private static TrackedEntityScheduler.PollResult poll(String entity, List<String> keywords, String category, Instant previousPollAt) throws Exception {
        // Paid run: wait until the relevance check has cleared this movie from its X posts.
        if (!TrackedReleaseSource.isApifyEligible(entity)) {
            System.out.println("[Apify/Reddit] Skipping '" + entity + "' (awaiting relevance check).");
            return TrackedEntityScheduler.PollResult.SKIPPED;
        }
        // Only ask the Actor for posts since this entity's last successful fetch, so a repeat scan
        // isn't billed again for content already saved. First-ever fetch has no lower bound. On
        // failure the cursor is left unchanged, so nothing is skipped.
        Instant lastFetch = DatabaseService.getLastFetchTime(PLATFORM, entity);
        Instant scanStart = Instant.now();
        JsonArray posts = search(entity, keywords, category, numberOfPosts,
                lastFetch != null ? DATE_FORMATTER.format(lastFetch) : null, null);
        DatabaseService.updateLastFetchTime(PLATFORM, entity, scanStart);
        // dateFrom is day-granular, so the run also returns earlier posts from that day; only
        // posts after the last fetch count towards this movie's activity.
        if (lastFetch == null) return TrackedEntityScheduler.PollResult.of(posts.size());
        int newPosts = 0;
        for (JsonElement el : posts) {
            JsonObject post = el.getAsJsonObject();
            if (post.has("created_utc") && post.get("created_utc").getAsLong() >= lastFetch.getEpochSecond()) newPosts++;
        }
        return TrackedEntityScheduler.PollResult.of(newPosts);
    }
}
