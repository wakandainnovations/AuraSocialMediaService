package com.lit.fire.flame;

import com.google.api.services.youtube.model.CommentSnippet;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoStatistics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lit.fire.api.SocialMediaScanner;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class YouTubeMain implements SocialMediaScanner {

    private static String API_KEY;
    private static int numberOfVideos;
    private static int numberOfComments;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final TrackedEntityScheduler entitySchedule = new TrackedEntityScheduler("YouTube", 300);

    // Package-private (not private) so MovieBackfillService can populate API_KEY/numberOfComments
    // before calling search(...) directly, without going through scan()'s scheduler setup.
    static void loadConfig() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = YouTubeMain.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (input == null) {
                System.err.println("Error: Unable to find secrets.properties. Please ensure the file exists and contains the required API credentials.");
                System.exit(1);
            }
            properties.load(input);
        }

        API_KEY = properties.getProperty("youtube.api_key");
        numberOfVideos = AppProperties.getIntProperty("number.of.videos", 10);
        numberOfComments = AppProperties.getIntProperty("number.of.comments", 10);

        if (API_KEY == null || API_KEY.equals("YOUR_YOUTUBE_API_KEY")) {
            System.err.println("Error: Please configure your YouTube API key in the secrets.properties file.");
            System.exit(1);
        }
    }


    /**
     * Searches YouTube for a single entity. The entity's keywords are combined into one OR'd video
     * search (e.g. "GDN|GDNaidu"), so a video matching several keywords is found once and its comments
     * are fetched (and stored) once instead of once per keyword. Comments are deduped per entity via
     * the composite id commentId_entity, and cross-source (Data API vs. Apify fallback) via
     * content_hash, both in saveYouTubeComments(...). Once the Data API's quota is exhausted
     * mid-run, remaining videos fall back to YouTubeCommentsApifyService.
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        search(entity, keywords, category, numberOfVideos, null);
    }

    /**
     * Same as {@link #search(String, List, String)} but lets the caller override the video cap and
     * restrict results to videos published on/after {@code publishedAfter}. Used by
     * MovieBackfillService for a fixed-window historical run, which can't share the mutable
     * {@code numberOfVideos} field with the regular scheduled scan.
     */
    public static void search(String entity, List<String> keywords, String category, int maxVideos, java.time.Instant publishedAfter) throws Exception {
        search(entity, keywords, category, maxVideos, publishedAfter, null);
    }

    /**
     * The search itself. Returns how many of the comments and videos it read were published after
     * {@code newSince} (all of them when null) - the scheduled poll's activity measure.
     */
    private static int search(String entity, List<String> keywords, String category, int maxVideos,
                              java.time.Instant publishedAfter, java.time.Instant newSince) throws Exception {
        String combinedQuery = buildQuery(keywords);
        System.out.println("\nRetrieving latest " + maxVideos + " videos for entity '" + entity
                + "' (keywords: " + String.join(", ", keywords) + ")...");

        YouTubeService service = new YouTubeService("YouTubeSearchApp", API_KEY);
        // Data API first (free within quota); if its search fails for any reason, Apify's YouTube
        // search. If both fail the exception propagates, so the scheduler counts a failed poll
        // (retried at the base interval) instead of a quiet one that backs off.
        List<SearchResult> videos;
        Map<String, Video> apifyStats = Map.of();
        boolean youtubeApiQuotaExceeded = false;
        try {
            videos = service.searchVideos(combinedQuery, maxVideos, publishedAfter);
        } catch (RuntimeException apiError) {
            youtubeApiQuotaExceeded = apiError instanceof YouTubeQuotaExceededException;
            System.err.println(apiError.getMessage() + ". Falling back to Apify YouTube search.");
            YouTubeVideoSearchApifyService.Result fallback;
            try {
                fallback = YouTubeVideoSearchApifyService.search(keywords, maxVideos, publishedAfter);
            } catch (Exception apifyError) {
                throw new RuntimeException("YouTube search failed on the Data API (" + apiError.getMessage()
                        + ") and on Apify (" + apifyError.getMessage() + ")", apifyError);
            }
            videos = fallback.videos();
            apifyStats = fallback.stats();
        }

        if (videos.isEmpty()) {
            System.out.println("No videos found for entity: '" + entity + "'");
            return 0;
        }

        System.out.println("Search successful. Found " + videos.size() + " videos.");
        JsonArray videoComments = new JsonArray();
        // Guard against the same video appearing more than once so we fetch its comments only once.
        Set<String> seenVideoIds = new HashSet<>();
        // Keeps first-seen order/snippet for the like/view/comment-count upsert below.
        Map<String, SearchResult> uniqueVideos = new LinkedHashMap<>();
        // Once the Data API's quota is hit (by the search above or by one video's comments), every
        // other call in this run will fail the same way; the rest of this run's videos go straight
        // to the Apify fallback instead of wasting a doomed API call first.

        for (SearchResult video : videos) {
            String videoId = video.getId().getVideoId();
            if (!seenVideoIds.add(videoId)) {
                continue;
            }
            uniqueVideos.put(videoId, video);
            String videoTitle = video.getSnippet().getTitle();
            System.out.println("\nFetching " + numberOfComments + " comments for video: " + videoTitle + " (ID: " + videoId + ")");

            JsonArray commentDocs = null;

            if (!youtubeApiQuotaExceeded) {
                // Retrieve the last ETag from the DB for this specific video, so an unchanged video
                // costs 0 quota units (a 304) instead of a full comments read.
                String lastETag = DatabaseService.getVideoETag(videoId);
                try {
                    List<CommentThread> comments = service.getComments(videoId, numberOfComments, lastETag);
                    if (comments.isEmpty()) {
                        System.out.println("No comments found for video ID: " + videoId);
                    } else {
                        // Capture the NEW ETag from the first item to use for the next hourly poll
                        DatabaseService.saveVideoETag(videoId, comments.get(0).getEtag());
                        commentDocs = toCommentDocs(comments, videoId, videoTitle);
                    }
                } catch (YouTubeQuotaExceededException e) {
                    System.err.println(e.getMessage() + ". Falling back to Apify comments for the rest of this run.");
                    youtubeApiQuotaExceeded = true;
                }
            }

            if (youtubeApiQuotaExceeded) {
                commentDocs = YouTubeCommentsApifyService.fetchCommentsIfDue(videoId, videoTitle, numberOfComments);
            }

            if (commentDocs != null) {
                commentDocs.forEach(videoComments::add);
            }
        }

        int newItems = 0;
        for (JsonElement comment : videoComments) {
            if (isAfter(comment.getAsJsonObject().get("published_at"), newSince)) newItems++;
        }
        for (SearchResult video : uniqueVideos.values()) {
            com.google.api.client.util.DateTime published = video.getSnippet().getPublishedAt();
            if (newSince == null || (published != null && published.getValue() > newSince.toEpochMilli())) newItems++;
        }

        if (videoComments.size() > 0) {
            DatabaseService.saveYouTubeComments(videoComments, entity, keywords, category);
        }

        if (!uniqueVideos.isEmpty()) {
            // Without quota the stats call returns nothing; use the counts Apify's search returned,
            // so the upsert doesn't overwrite stored counts with blanks.
            Map<String, Video> statsByVideoId = youtubeApiQuotaExceeded ? new java.util.HashMap<>()
                    : service.getVideoStatistics(new ArrayList<>(uniqueVideos.keySet()));
            apifyStats.forEach(statsByVideoId::putIfAbsent);
            JsonArray videoDocs = new JsonArray();

            for (Map.Entry<String, SearchResult> entry : uniqueVideos.entrySet()) {
                String videoId = entry.getKey();
                SearchResult video = entry.getValue();

                if (video.getSnippet().getPublishedAt() == null) {
                    System.err.println("Skipping video stats for " + videoId + ": API returned no publishedAt.");
                    continue;
                }

                JsonObject videoJson = new JsonObject();
                videoJson.addProperty("id", videoId);
                videoJson.addProperty("title", video.getSnippet().getTitle());
                videoJson.addProperty("channel_title", video.getSnippet().getChannelTitle());
                videoJson.addProperty("published_at", video.getSnippet().getPublishedAt().toString());
                videoJson.addProperty("permalink", "https://www.youtube.com/watch?v=" + videoId);

                Video stats = statsByVideoId.get(videoId);
                VideoStatistics statistics = stats != null ? stats.getStatistics() : null;
                if (statistics != null) {
                    videoJson.addProperty("view_count", statistics.getViewCount() != null ? statistics.getViewCount().longValue() : 0L);
                    // Creators can hide like counts, so a null here is a real "unknown", not a zero.
                    videoJson.addProperty("like_count", statistics.getLikeCount() != null ? statistics.getLikeCount().longValue() : 0L);
                    videoJson.addProperty("comment_count", statistics.getCommentCount() != null ? statistics.getCommentCount().longValue() : 0L);
                }
                videoDocs.add(videoJson);
            }

            DatabaseService.saveYouTubeVideos(videoDocs, entity, keywords, category);
        }
        return newItems;
    }

    // Whether an ISO-8601 published_at is after {@code since} (always true when since is null;
    // an unparseable timestamp counts as new rather than hiding activity).
    private static boolean isAfter(JsonElement publishedAt, java.time.Instant since) {
        if (since == null) return true;
        if (publishedAt == null || publishedAt.isJsonNull()) return true;
        try {
            return java.time.OffsetDateTime.parse(publishedAt.getAsString()).toInstant().isAfter(since);
        } catch (Exception e) {
            return true;
        }
    }

    // Builds the OR'd video-search query for a set of keywords. YouTube's `q` uses '|' for OR.
    private static String buildQuery(List<String> keywords) {
        return keywords.size() == 1 ? keywords.get(0) : String.join("|", keywords);
    }

    // Reshapes native Data API comment threads into the schema shared with
    // YouTubeCommentsApifyService's Apify-sourced comments, so both feed DatabaseService.saveYouTubeComments(...) unmodified.
    private static JsonArray toCommentDocs(List<CommentThread> comments, String videoId, String videoTitle) {
        JsonArray docs = new JsonArray();
        for (CommentThread commentThread : comments) {
            CommentSnippet snippet = commentThread.getSnippet().getTopLevelComment().getSnippet();
            JsonObject commentJson = new JsonObject();
            commentJson.addProperty("video_id", videoId);
            commentJson.addProperty("video_title", videoTitle);
            commentJson.addProperty("comment_id", commentThread.getId());
            commentJson.addProperty("text", snippet.getTextDisplay());
            commentJson.addProperty("author", snippet.getAuthorDisplayName());
            commentJson.addProperty("likes_count", snippet.getLikeCount());
            commentJson.addProperty("reply_count", commentThread.getSnippet().getTotalReplyCount());
            commentJson.addProperty("published_at", snippet.getPublishedAt().toString());
            commentJson.addProperty("permalink", "https://www.youtube.com/watch?v=" + videoId + "&lc=" + commentThread.getId());
            docs.add(commentJson);
        }
        return docs;
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Syncing YouTube entity polls...");
            // Reconciles against the tracked-release list on every scan() (Main calls it hourly):
            // one adaptive poll per entity (TrackedEntityScheduler: 4h, backing off to 24h when
            // quiet), keywords combined into one OR'd video search so a shared video's comments are
            // read/stored once.
            // Data API quota/rate limits fall back to Apify inside search(); anything it still throws
            // is logged by the scheduler as a failed poll and retried after the base interval.
            entitySchedule.sync(scheduler, (entity, keywords, category, previousPollAt) ->
                    TrackedEntityScheduler.PollResult.of(search(entity, keywords, category, numberOfVideos, null, previousPollAt)));
        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}