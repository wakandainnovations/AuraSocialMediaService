package com.lit.fire.flame;

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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * A client for searching X/Twitter posts.
 */
public class XService implements SocialMediaScanner {

    private static String ACCESS_TOKEN;
    private static final String API_URL = "https://api.twitter.com/2";
    private static int numberOfPosts;

    private static void loadConfig() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = XService.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (input == null) {
                System.err.println("Error: Unable to find secrets.properties. Please ensure the file exists and contains the required API credentials.");
                System.exit(1);
            }
            properties.load(input);
        }

        ACCESS_TOKEN = properties.getProperty("x.access_token");
        numberOfPosts = AppProperties.getIntProperty("number.of.posts", 10);

        if (ACCESS_TOKEN == null || ACCESS_TOKEN.equals("YOUR_ACCESS_TOKEN")) {
            System.err.println("Error: Please configure your X API credentials in the secrets.properties file.");
            System.exit(1);
        }
    }

    private static List<JsonObject> loadInputQueries() throws Exception {
        List<JsonObject> inputQueries = new ArrayList<>();
        try (InputStream input = XService.class.getClassLoader().getResourceAsStream("search_queries.txt")) {
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

    public static void search(String keyword, String category) throws Exception {
        System.out.println("\nRetrieving latest " + numberOfPosts + " posts for '" + keyword + "'...");

        String encodedQuery = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String fields = "id,text,created_at,author_id,public_metrics";
        String expansions = "author_id";
        String userFields = "username,name";
        String searchUrl = String.format("%s/tweets/search/recent?query=%s&tweet.fields=%s&expansions=%s&user.fields=%s&max_results=%d",
                API_URL, encodedQuery, fields, expansions, userFields, numberOfPosts);

        JsonObject response = sendRequest(searchUrl);

        System.out.println("Search successful. Found posts:");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(response));

        if (response != null && response.has("data")) {
            JsonArray posts = response.getAsJsonArray("data");
            JsonObject includes = response.getAsJsonObject("includes");
            Map<String, JsonObject> users = new HashMap<>();
            if (includes != null && includes.has("users")) {
                for (JsonElement userElement : includes.getAsJsonArray("users")) {
                    JsonObject user = userElement.getAsJsonObject();
                    users.put(user.get("id").getAsString(), user);
                }
            }

            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();
                String authorId = post.get("author_id").getAsString();
                JsonObject user = users.get(authorId);
                if (user != null) {
                    String username = user.get("username").getAsString();
                    post.addProperty("author", user.get("name").getAsString());
                    String permalink = "https://twitter.com/" + username + "/status/" + post.get("id").getAsString();
                    post.addProperty("permalink", permalink);
                }

                if (post.has("public_metrics")) {
                    JsonObject publicMetrics = post.getAsJsonObject("public_metrics");
                    post.addProperty("likes_count", publicMetrics.get("like_count").getAsInt());
                    post.addProperty("comment_count", publicMetrics.get("reply_count").getAsInt());
                } else {
                    post.addProperty("likes_count", 0);
                    post.addProperty("comment_count", 0);
                }
            }
            DatabaseService.saveXPosts(posts, keyword, category);
        }
    }

    private static JsonObject sendRequest(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
        
        throw new RuntimeException("API Request failed. Status: " + response.statusCode() + ", Body: " + response.body());
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Initializing X Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            for (JsonObject inputQuery : inputQueries) {
                String keyword = inputQuery.get("keyword").getAsString();
                String category = inputQuery.get("category").getAsString();
                System.out.println("\nProcessing keyword: " + keyword);
                search(keyword, category);
                long delay = ThreadLocalRandom.current().nextLong(300000, 600001);
                System.out.println(System.currentTimeMillis() + ": Waiting for " + (delay / 60000) + " minutes before the next keyword...");
                Thread.sleep(delay);
            }

        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}