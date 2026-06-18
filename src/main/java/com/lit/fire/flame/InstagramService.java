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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * A client for searching Instagram posts using the Instagram Graph API.
 * Requires an Instagram Business Account and a User Access Token.
 */
public class InstagramService implements SocialMediaScanner {

    private static String ACCESS_TOKEN;
    private static String INSTAGRAM_BUSINESS_ID;
    private static final String GRAPH_API_URL = "https://graph.facebook.com/v24.0";
    private static int numberOfPosts;

    private static void loadConfig() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = InstagramService.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (input == null) {
                System.err.println("Error: Unable to find secrets.properties. Please ensure the file exists and contains the required API credentials.");
                System.exit(1);
            }
            properties.load(input);
        }

        ACCESS_TOKEN = properties.getProperty("instagram.access_token");
        INSTAGRAM_BUSINESS_ID = properties.getProperty("instagram.business_id");
        numberOfPosts = AppProperties.getIntProperty("number.of.posts", 10);

        if (ACCESS_TOKEN == null || INSTAGRAM_BUSINESS_ID == null || ACCESS_TOKEN.equals("YOUR_USER_ACCESS_TOKEN") || INSTAGRAM_BUSINESS_ID.equals("YOUR_INSTAGRAM_BUSINESS_ID")) {
            System.err.println("Error: Please configure your Instagram API credentials in the secrets.properties file.");
            System.exit(1);
        }
    }

    private static List<JsonObject> loadInputQueries() throws Exception {
        List<JsonObject> inputQueries = new ArrayList<>();
        try (InputStream input = InstagramService.class.getClassLoader().getResourceAsStream("search_queries.txt")) {
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
     * Searches Instagram for a single entity. Hashtag search is one API call per hashtag (the Graph
     * API can't OR hashtags into one call), but a post tagged with several of the entity's hashtags is
     * collected only once: we track seen post ids across the entity's hashtags and store per entity
     * (composite id postId_entity in saveInstagramPosts(...)), so it isn't stored or re-analyzed twice.
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        JsonArray entityPosts = new JsonArray();
        Set<String> seenPostIds = new HashSet<>();

        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            String hashtagId = getHashtagId(keyword);
            if (hashtagId != null) {
                System.out.println("Found Hashtag ID for '" + keyword + "': " + hashtagId);
                JsonArray media = getHashtagMedia(hashtagId, keyword);
                for (JsonElement el : media) {
                    JsonObject post = el.getAsJsonObject();
                    // Dedupe within the entity so a post under several of its hashtags is kept once.
                    if (post.has("id") && seenPostIds.add(post.get("id").getAsString())) {
                        entityPosts.add(post);
                    }
                }
            }
            // Space out hashtag lookups within an entity to respect rate limits.
            if (i < keywords.size() - 1) {
                long delay = ThreadLocalRandom.current().nextLong(300000, 600001);
                System.out.println(System.currentTimeMillis() + ": Waiting for " + (delay / 60000) + " minutes before the next hashtag...");
                Thread.sleep(delay);
            }
        }

        if (entityPosts.size() > 0) {
            DatabaseService.saveInstagramPosts(entityPosts, entity, keywords, category);
        }
    }

    private static String getHashtagId(String query) throws Exception {
        System.out.println("Searching for hashtag ID for '" + query + "'...");

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = String.format("%s/ig_hashtag_search?user_id=%s&q=%s&access_token=%s",
                GRAPH_API_URL, INSTAGRAM_BUSINESS_ID, encodedQuery, ACCESS_TOKEN);

        JsonObject response = sendRequest(searchUrl);

        if (response == null) {
            return null;
        }

        if (response.has("data")) {
            JsonArray data = response.getAsJsonArray("data");
            if (data.size() > 0) {
                return data.get(0).getAsJsonObject().get("id").getAsString();
            }
        }
        return null;
    }

    private static JsonArray getHashtagMedia(String hashtagId, String query) throws Exception {
        System.out.println("\nRetrieving latest " + numberOfPosts + " posts for '" + query + "'...");

        String fields = "id,caption,media_type,media_url,permalink,timestamp,username,like_count,comments_count";
        String mediaUrl = String.format("%s/%s/recent_media?user_id=%s&fields=%s&limit=%d&access_token=%s",
                GRAPH_API_URL, hashtagId, INSTAGRAM_BUSINESS_ID, fields, numberOfPosts, ACCESS_TOKEN);

        JsonObject response = sendRequest(mediaUrl);

        System.out.println("Search successful. Found posts:");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(response));

        if (response != null && response.has("data")) {
            return response.getAsJsonArray("data");
        }
        return new JsonArray();
    }

    private static JsonObject sendRequest(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }

        if (response.statusCode() == 400) {
            try {
                JsonObject errorResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                if (errorResponse.has("error")) {
                    JsonObject errorObject = errorResponse.getAsJsonObject("error");
                    if (errorObject.has("error_subcode") && errorObject.get("error_subcode").getAsInt() == 2207024) {
                        System.err.println("API Error: " + errorObject.get("error_user_msg").getAsString());
                        return null;
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        throw new RuntimeException("API Request failed. Status: " + response.statusCode() + ", Body: " + response.body());
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Initializing Instagram Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            // Group keyword lines by entity and process once per entity so a post shared across the
            // entity's hashtags is collected and stored only once.
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
                System.out.println("\nProcessing entity: " + entity + " -> keywords " + keywords);
                search(entity, keywords, category);
                long delay = ThreadLocalRandom.current().nextLong(300000, 600001);
                System.out.println(System.currentTimeMillis() + ": Waiting for " + (delay / 60000) + " minutes before the next entity...");
                Thread.sleep(delay);
            }

        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}