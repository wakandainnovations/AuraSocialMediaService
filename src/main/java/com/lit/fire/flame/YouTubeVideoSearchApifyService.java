package com.lit.fire.flame;

import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.model.ResourceId;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.SearchResultSnippet;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoStatistics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback video search used when the YouTube Data API search fails (quota, rate limit or any
 * other error). Runs the Apify Actor "streamers/youtube-scraper" with the entity's keywords as
 * search terms and reshapes its items into the Data API's SearchResult / Video model classes, so
 * YouTubeMain's comment fetching and video upsert work unchanged on either source.
 *
 * Billed per video returned (~$0.004 each on the Free plan), so only regular videos are requested
 * (no Shorts or streams) and the per-keyword cap is the same number.of.videos the API path uses.
 */
public class YouTubeVideoSearchApifyService {

    private static final String ACTOR_ID = "streamers/youtube-scraper";

    /** Videos found, plus the view/like/comment counts the Actor returned for them, keyed by video id. */
    public record Result(List<SearchResult> videos, Map<String, Video> stats) {}

    /**
     * Searches YouTube via Apify for each keyword (up to {@code maxVideosPerKeyword} each) and
     * returns the deduped videos. {@code publishedAfter} (nullable) drops videos known to be older.
     * Throws if the Actor run fails, so the caller's poll counts as failed rather than quiet.
     */
    public static Result search(List<String> keywords, int maxVideosPerKeyword, Instant publishedAfter) throws Exception {
        JsonArray queries = new JsonArray();
        keywords.forEach(queries::add);

        JsonObject input = new JsonObject();
        input.add("searchQueries", queries);
        input.addProperty("maxResults", maxVideosPerKeyword);
        input.addProperty("maxResultsShorts", 0);
        input.addProperty("maxResultStreams", 0);
        if (publishedAfter != null) {
            input.addProperty("oldestPostDate", LocalDate.ofInstant(publishedAfter, ZoneOffset.UTC).toString());
        }

        JsonArray items = ApifyClient.runActorAndGetDatasetItems(ACTOR_ID, input);
        System.out.println("[Apify/YouTube search] " + keywords + " returned " + items.size() + " item(s).");

        Instant now = Instant.now();
        Map<String, SearchResult> videos = new LinkedHashMap<>();
        Map<String, Video> stats = new LinkedHashMap<>();
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            String id = str(item, "id");
            if (item.has("error") || id == null || videos.containsKey(id)) continue;

            Instant published = parseDate(str(item, "date"), now);
            if (publishedAfter != null && published != null && published.isBefore(publishedAfter)) continue;

            SearchResultSnippet snippet = new SearchResultSnippet()
                    .setTitle(str(item, "title"))
                    .setChannelTitle(str(item, "channelName"))
                    // Null when the Actor's date can't be read: YouTubeMain then neither counts the
                    // video as new nor upserts it, rather than guessing a date.
                    .setPublishedAt(published != null ? new DateTime(published.toEpochMilli()) : null);
            videos.put(id, new SearchResult()
                    .setId(new ResourceId().setKind("youtube#video").setVideoId(id))
                    .setSnippet(snippet));

            VideoStatistics statistics = new VideoStatistics()
                    .setViewCount(big(item, "viewCount"))
                    .setLikeCount(big(item, "likes"))
                    .setCommentCount(big(item, "commentsCount"));
            stats.put(id, new Video().setId(id).setStatistics(statistics));
        }
        return new Result(new ArrayList<>(videos.values()), stats);
    }

    private static final Pattern RELATIVE = Pattern.compile(
            "(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);

    /**
     * The Actor's {@code date} is an ISO date/time on some items and relative text ("5 days ago",
     * "Streamed 2 weeks ago") on search results. Relative text is approximated from {@code now}
     * (months as 30 days, years as 365). Returns null when neither form parses.
     */
    static Instant parseDate(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // not a full ISO instant
        }
        try {
            return LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            // not an ISO date
        }
        Matcher m = RELATIVE.matcher(v);
        if (!m.find()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase()) {
            case "second" -> now.minus(n, ChronoUnit.SECONDS);
            case "minute" -> now.minus(n, ChronoUnit.MINUTES);
            case "hour" -> now.minus(n, ChronoUnit.HOURS);
            case "day" -> now.minus(n, ChronoUnit.DAYS);
            case "week" -> now.minus(n * 7, ChronoUnit.DAYS);
            case "month" -> now.minus(n * 30, ChronoUnit.DAYS);
            default -> now.minus(n * 365, ChronoUnit.DAYS);
        };
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static BigInteger big(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? BigInteger.valueOf(obj.get(key).getAsLong()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
