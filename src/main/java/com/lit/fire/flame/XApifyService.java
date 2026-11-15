package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Collects X/Twitter posts via the Apify Actor "apidojo/tweet-scraper" and stores them through the
 * same DatabaseService.saveXPosts(...) path used by the native X API v2 integration (XService),
 * which this class does not touch.
 *
 * XService's official recent-search endpoint only covers the last 7 days (Basic/Pro access), so it
 * can't serve a historical backfill; full-archive access requires an Enterprise-tier subscription.
 * This Actor scrapes X's own advanced search (same "since:"/"until:" operators X's search UI uses),
 * so it can reach back arbitrarily far, at ~$0.40/1,000 tweets.
 */
public class XApifyService {

    private static final String ACTOR_ID = "apidojo/tweet-scraper";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    // X's classic tweet timestamp format, e.g. "Wed Oct 10 20:19:24 +0000 2018".
    private static final DateTimeFormatter TWEET_CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);

    /**
     * Scrapes a single entity in one Actor run, combining all of the entity's keywords into one
     * OR'd search query (mirroring XService's approach), optionally bounded to [dateFrom, dateTo]
     * via X's own "since:"/"until:" search operators (either may be null for no bound).
     */
    public static void search(String entity, List<String> keywords, String category, int maxItems, Instant dateFrom, Instant dateTo) throws Exception {
        String query = buildQuery(keywords, dateFrom, dateTo);
        System.out.println("[Apify/X] Searching entity '" + entity + "' -> query: " + query);

        JsonArray searchTerms = new JsonArray();
        searchTerms.add(query);

        JsonObject actorInput = new JsonObject();
        actorInput.add("searchTerms", searchTerms);
        actorInput.addProperty("maxItems", maxItems);
        actorInput.addProperty("sort", "Latest");

        JsonArray items = ApifyClient.runActorAndGetDatasetItems(ACTOR_ID, actorInput);
        System.out.println("[Apify/X] '" + entity + "' returned " + items.size() + " item(s).");

        JsonArray posts = new JsonArray();
        for (JsonElement el : items) {
            JsonObject post = toXPost(el.getAsJsonObject());
            if (post != null) {
                posts.add(post);
            }
        }

        if (posts.size() > 0) {
            DatabaseService.saveXPosts(posts, entity, keywords, category);
        }
    }

    // Builds the OR'd query with X's native since:/until: date operators appended, e.g.
    // "(ToxicTheMovie) since:2026-06-07 until:2026-09-07".
    private static String buildQuery(List<String> keywords, Instant dateFrom, Instant dateTo) {
        String core = keywords.size() == 1 ? keywords.get(0) : "(" + String.join(" OR ", keywords) + ")";
        StringBuilder query = new StringBuilder(core);
        if (dateFrom != null) {
            query.append(" since:").append(DATE_FORMATTER.format(dateFrom));
        }
        if (dateTo != null) {
            query.append(" until:").append(DATE_FORMATTER.format(dateTo));
        }
        return query.toString();
    }

    /**
     * Reshapes an Apify dataset item into the field set DatabaseService.saveXPosts(...) requires
     * (id, text, created_at, permalink, author, likes_count, comment_count, views_count) so that
     * method can be used unmodified.
     */
    private static JsonObject toXPost(JsonObject item) {
        if (!item.has("id") || item.get("id").isJsonNull()) {
            return null;
        }

        JsonObject post = new JsonObject();
        post.addProperty("id", item.get("id").getAsString());

        String text = stringOrNull(item, "text");
        if (text == null) {
            text = stringOrNull(item, "fullText");
        }
        post.addProperty("text", text != null ? text : "");

        Instant createdAt = parseCreatedAt(item);
        post.addProperty("created_at", (createdAt != null ? createdAt : Instant.now()).toString());

        String permalink = stringOrNull(item, "url");
        if (permalink == null) {
            permalink = stringOrNull(item, "twitterUrl");
        }
        if (permalink != null) {
            post.addProperty("permalink", permalink);
        }

        post.addProperty("author", resolveAuthor(item));
        post.addProperty("likes_count", intOrDefault(item, "likeCount", 0));
        post.addProperty("comment_count", intOrDefault(item, "replyCount", 0));
        post.addProperty("views_count", intOrDefault(item, "viewCount", intOrDefault(item, "views", 0)));
        return post;
    }

    // Author info may come back nested under an "author" object (userName/name) or as top-level
    // fields, depending on the Actor's output shape; try both rather than assume one.
    private static String resolveAuthor(JsonObject item) {
        if (item.has("author") && item.get("author").isJsonObject()) {
            JsonObject author = item.getAsJsonObject("author");
            String name = stringOrNull(author, "name");
            if (name != null) {
                return name;
            }
            String userName = stringOrNull(author, "userName");
            if (userName != null) {
                return userName;
            }
        }
        String name = stringOrNull(item, "name");
        if (name != null) {
            return name;
        }
        String userName = stringOrNull(item, "userName");
        return userName != null ? userName : "unknown_user";
    }

    // "createdAt" comes back in X's classic format ("Wed Oct 10 20:19:24 +0000 2018"), but parse
    // defensively in case the Actor ever switches to plain ISO-8601.
    private static Instant parseCreatedAt(JsonObject item) {
        String raw = stringOrNull(item, "createdAt");
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(raw, TWEET_CREATED_AT_FORMATTER).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int intOrDefault(JsonObject obj, String key, int defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }
}
