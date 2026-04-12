package com.lit.fire.flame;

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
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class YouTubeMain implements SocialMediaScanner {

    private static String API_KEY;
    private static int numberOfVideos;
    private static int numberOfComments;

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

    public static void search(String query, String category) throws Exception {
        System.out.println("\nRetrieving latest " + numberOfVideos + " videos for '" + query + "'...");

        YouTubeService service = new YouTubeService("YouTubeSearchApp", API_KEY);
        List<SearchResult> videos = service.searchVideos(query, numberOfVideos);

        if (videos.isEmpty()) {
            System.out.println("No videos found for the query: '" + query + "'");
            return;
        }

        System.out.println("Search successful. Found " + videos.size() + " videos.");
        JsonArray videoComments = new JsonArray();

        for (SearchResult video : videos) {
            String videoId = video.getId().getVideoId();
            System.out.println("\nFetching " + numberOfComments + " comments for video: " + video.getSnippet().getTitle() + " (ID: " + videoId + ")");
            List<CommentThread> comments = service.getComments(videoId, numberOfComments);

            if (comments.isEmpty()) {
                System.out.println("No comments found for video ID: " + videoId);
                continue;
            }

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
            DatabaseService.saveYouTubeComments(videoComments, query, category);
        }
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Initializing YouTube Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            for (JsonObject inputQuery : inputQueries) {
                String keyword = inputQuery.get("keyword").getAsString();
                String category = inputQuery.get("category").getAsString();
                System.out.println("\nProcessing keyword: " + keyword);
                search(keyword, category);
            }

        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}