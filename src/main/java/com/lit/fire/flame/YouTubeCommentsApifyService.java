package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Fallback comment source used once YouTubeMain hits the YouTube Data API's quota mid-run. Runs
 * the Apify Actor "streamers/youtube-comments-scraper" against a single video's URL and reshapes
 * its dataset items into the same {video_id, video_title, comment_id, text, author, likes_count,
 * reply_count, published_at, permalink} schema YouTubeMain builds from the native API, so
 * DatabaseService.saveYouTubeComments(...) accepts either source unmodified.
 *
 * Every Actor run costs money regardless of whether a video has new comments, so a video whose
 * latest fetched comment set comes back identical to last time has its next fetch pushed back with
 * exponential backoff (see youtube_video_comment_cursor / DatabaseService.getYoutubeCommentCursor);
 * a video whose comment set does change resets to the base interval so active videos stay polled
 * promptly.
 */
public class YouTubeCommentsApifyService {

    private static final String ACTOR_ID = "streamers/youtube-comments-scraper";
    // Matches this project's other Apify-backed services' base cadence (RedditApifyService's
    // inter-entity delay).
    private static final long INITIAL_BACKOFF_SECONDS = 60 * 60; // 1 hour
    private static final long MAX_BACKOFF_SECONDS = 7 * 24 * 60 * 60; // 1 week cap

    /**
     * Fetches this video's latest comments via Apify, but only if its backoff cursor says it's
     * due; otherwise returns an empty array without spending an Actor run. Always updates the
     * cursor (comment-set hash + next-due time) after a real fetch.
     */
    public static JsonArray fetchCommentsIfDue(String videoId, String videoTitle, int maxComments) {
        DatabaseService.YoutubeCommentCursor cursor = DatabaseService.getYoutubeCommentCursor(videoId);
        Instant now = Instant.now();
        if (cursor != null && cursor.nextFetchAt != null && now.isBefore(cursor.nextFetchAt)) {
            System.out.println("[Apify/YouTube] Skipping video " + videoId + " (backed off until " + cursor.nextFetchAt + ")");
            return new JsonArray();
        }

        JsonArray commentDocs;
        try {
            commentDocs = fetchComments(videoId, videoTitle, maxComments);
        } catch (Exception e) {
            System.err.println("[Apify/YouTube] Failed to fetch comments for video " + videoId + ": " + e.getMessage());
            return new JsonArray();
        }

        String newHash = hashCommentIds(commentDocs);
        long backoffSeconds;
        if (cursor != null && newHash.equals(cursor.commentsHash)) {
            backoffSeconds = Math.min(cursor.backoffSeconds * 2, MAX_BACKOFF_SECONDS);
            System.out.println("[Apify/YouTube] No change in comments for video " + videoId + "; backing off " + backoffSeconds + "s.");
        } else {
            backoffSeconds = INITIAL_BACKOFF_SECONDS;
        }
        DatabaseService.upsertYoutubeCommentCursor(videoId, newHash, now.plusSeconds(backoffSeconds), backoffSeconds);

        return commentDocs;
    }

    private static JsonArray fetchComments(String videoId, String videoTitle, int maxComments) throws Exception {
        JsonObject urlEntry = new JsonObject();
        urlEntry.addProperty("url", "https://www.youtube.com/watch?v=" + videoId);
        JsonArray startUrls = new JsonArray();
        startUrls.add(urlEntry);

        JsonObject actorInput = new JsonObject();
        actorInput.add("startUrls", startUrls);
        actorInput.addProperty("maxComments", maxComments);
        actorInput.addProperty("sortCommentsBy", "NEWEST_FIRST");

        JsonArray items = ApifyClient.runActorAndGetDatasetItems(ACTOR_ID, actorInput);
        System.out.println("[Apify/YouTube] video '" + videoId + "' returned " + items.size() + " comment(s).");

        JsonArray docs = new JsonArray();
        for (JsonElement el : items) {
            JsonObject item = el.getAsJsonObject();
            String commentId = stringOrNull(item, "cid");
            String text = stringOrNull(item, "comment");
            if (commentId == null || text == null) {
                continue;
            }

            JsonObject doc = new JsonObject();
            doc.addProperty("video_id", videoId);
            doc.addProperty("video_title", videoTitle);
            doc.addProperty("comment_id", commentId);
            doc.addProperty("text", text);
            doc.addProperty("author", firstNonNull(item, "author", "authorText", "authorName"));
            doc.addProperty("likes_count", longOrDefault(item, "voteCount", 0));
            doc.addProperty("reply_count", longOrDefault(item, "replyCount", 0));
            doc.addProperty("published_at", resolvePublishedAt(item));
            doc.addProperty("permalink", "https://www.youtube.com/watch?v=" + videoId + "&lc=" + commentId);
            docs.add(doc);
        }
        return docs;
    }

    // The Actor's output doesn't document a reliable absolute publish timestamp (only relative
    // text like "2 days ago" in some runs), so a handful of likely absolute-timestamp keys are
    // tried and anything unparseable as ISO-8601 falls back to fetch time, rather than being passed
    // through to DatabaseService.saveYouTubeComments(...)'s Instant.parse(...), which would throw.
    private static String resolvePublishedAt(JsonObject item) {
        for (String key : new String[] {"publishedAt", "publishDate", "date"}) {
            if (item.has(key) && !item.get(key).isJsonNull()) {
                try {
                    return Instant.parse(item.get(key).getAsString()).toString();
                } catch (Exception ignored) {
                    // Not an ISO-8601 timestamp; try the next candidate.
                }
            }
        }
        return Instant.now().toString();
    }

    // Order-independent so a re-fetch returning the same comments in a different order isn't
    // mistaken for a changed comment set.
    private static String hashCommentIds(JsonArray commentDocs) {
        List<String> ids = StreamSupport.stream(commentDocs.spliterator(), false)
                .map(el -> el.getAsJsonObject().get("comment_id").getAsString())
                .sorted()
                .collect(Collectors.toList());
        return md5(String.join(",", ids));
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        return "unknown";
    }

    private static long longOrDefault(JsonObject obj, String key, long defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : defaultValue;
    }
}
