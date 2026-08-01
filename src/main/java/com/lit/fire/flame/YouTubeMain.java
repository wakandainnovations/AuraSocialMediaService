package com.lit.fire.flame;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.youtube.model.CommentSnippet;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.SearchResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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

    private static void loadConfig() throws Exception {
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

    private static List<JsonObject> loadInputQueries() throws Exception {
        List<JsonObject> inputQueries = new ArrayList<>();
        try (InputStream input = YouTubeMain.class.getClassLoader().getResourceAsStream("search_queries.txt")) {
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
     * Searches YouTube for a single entity. The entity's keywords are combined into one OR'd video
     * search (e.g. "GDN|GDNaidu"), so a video matching several keywords is found once and its comments
     * are fetched (and stored) once instead of once per keyword. Comments are deduped per entity via
     * the composite id commentId_entity in saveYouTubeComments(...).
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        String combinedQuery = buildQuery(keywords);
        System.out.println("\nRetrieving latest " + numberOfVideos + " videos for entity '" + entity
                + "' (keywords: " + String.join(", ", keywords) + ")...");

        YouTubeService service = new YouTubeService("YouTubeSearchApp", API_KEY);
        List<SearchResult> videos = service.searchVideos(combinedQuery, numberOfVideos);

        if (videos.isEmpty()) {
            System.out.println("No videos found for entity: '" + entity + "'");
            return;
        }

        System.out.println("Search successful. Found " + videos.size() + " videos.");
        JsonArray videoComments = new JsonArray();
        // Guard against the same video appearing more than once so we fetch its comments only once.
        Set<String> seenVideoIds = new HashSet<>();

        for (SearchResult video : videos) {
            String videoId = video.getId().getVideoId();
            if (!seenVideoIds.add(videoId)) {
                continue;
            }
            System.out.println("\nFetching " + numberOfComments + " comments for video: " + video.getSnippet().getTitle() + " (ID: " + videoId + ")");

            // *******************************************
            // 1. Retrieve the last ETag from your DB for this specific video
            String lastETag = DatabaseService.getVideoETag(videoId);
            // *******************************************
            List<CommentThread> comments = service.getComments(videoId, numberOfComments, lastETag);

            if (comments.isEmpty()) {
                System.out.println("No comments found for video ID: " + videoId);
                continue;
            }

            // Capture the NEW ETag from the first item to use for the next hourly poll
            String newETag = comments.get(0).getEtag();
            DatabaseService.saveVideoETag(videoId, newETag);

            for (CommentThread commentThread : comments) {
                CommentSnippet snippet = commentThread.getSnippet().getTopLevelComment().getSnippet();
                JsonObject commentJson = new JsonObject();
                commentJson.addProperty("video_id", videoId);
                commentJson.addProperty("video_title", video.getSnippet().getTitle());
                commentJson.addProperty("comment_id", commentThread.getId());
                commentJson.addProperty("text", snippet.getTextDisplay());
                commentJson.addProperty("author", snippet.getAuthorDisplayName());
                commentJson.addProperty("likes_count", snippet.getLikeCount());
                commentJson.addProperty("reply_count", commentThread.getSnippet().getTotalReplyCount());
                commentJson.addProperty("published_at", snippet.getPublishedAt().toString());
                commentJson.addProperty("permalink", "https://www.youtube.com/watch?v=" + videoId + "&lc=" + commentThread.getId());
                videoComments.add(commentJson);
            }
        }

        if (videoComments.size() > 0) {
            DatabaseService.saveYouTubeComments(videoComments, entity, keywords, category);
        }
    }

    // Builds the OR'd video-search query for a set of keywords. YouTube's `q` uses '|' for OR.
    private static String buildQuery(List<String> keywords) {
        return keywords.size() == 1 ? keywords.get(0) : String.join("|", keywords);
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Initializing YouTube Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            // Group keyword lines by entity and poll once per entity (not per keyword), combining its
            // keywords into one OR'd video search so a shared video's comments are read/stored once.
            Map<String, List<JsonObject>> queriesByEntity = new LinkedHashMap<>();
            for (JsonObject inputQuery : inputQueries) {
                String entity = inputQuery.get("entity").getAsString();
                queriesByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).add(inputQuery);
                DatabaseService.upsertEntityKeyword(inputQuery);
            }

            for (Map.Entry<String, List<JsonObject>> entry : queriesByEntity.entrySet()) {
                String entity = entry.getKey();
                List<JsonObject> queries = entry.getValue();
                List<String> keywords = queries.stream()
                        .map(q -> q.get("keyword").getAsString())
                        .collect(Collectors.toList());
                // Keyword lines of one entity share its category.
                String category = queries.get(0).get("category").getAsString();
                // SME Recommendation: Randomize initial delay so entities don't all hit the API at once
                long initialDelay = ThreadLocalRandom.current().nextLong(0, 60);
                System.out.println("\nProcessing entity: " + entity + " -> keywords " + keywords);
                // Schedule the task to run every 4 hours (14400 seconds)
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        System.out.println("\n[Scheduled Task] Starting: " + entity);
                        search(entity, keywords, category);
                    } catch (GoogleJsonResponseException e) {
                        if (e.getDetails() != null && e.getDetails().getCode() == 403 && e.getDetails().getMessage().contains("quota")) {
                            System.err.println("YouTube API quota exceeded. Shutting down all tasks.");
                            scheduler.shutdown();
                        } else {
                            System.err.println("Google API error for " + entity + ": " + e.getMessage());
                        }
                    } catch (Exception e) {
                        System.err.println("Error in scheduled task for " + entity + ": " + e.getMessage());
                    }
                }, initialDelay, 14400, TimeUnit.SECONDS);            }

        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}