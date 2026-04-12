package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lit.fire.api.SocialMediaScanner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A client for authenticating with the Reddit API using the OAuth 2.0
 * Client Credentials Grant Flow and performing a basic search.
 */
public class RedditAuthClientWithSearch implements SocialMediaScanner {

    private static String CLIENT_ID;
    private static String CLIENT_SECRET;
    private static String REDDIT_USERNAME;
    private static String USER_AGENT;

    private static final String TOKEN_ENDPOINT = "https://www.reddit.com/api/v1/access_token";
    private static final String API_BASE_URL = "https://oauth.reddit.com";

    static {
        try {
            loadConfig();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void loadConfig() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = RedditAuthClientWithSearch.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (input == null) {
                System.err.println("Error: Unable to find secrets.properties. Please ensure the file exists and contains the required API credentials.");
                System.exit(1);
            }
            properties.load(input);
        }

        CLIENT_ID = properties.getProperty("reddit.client_id");
        CLIENT_SECRET = properties.getProperty("reddit.client_secret");
        REDDIT_USERNAME = properties.getProperty("reddit.username");

        if (CLIENT_ID == null || CLIENT_SECRET == null || REDDIT_USERNAME == null) {
            System.err.println("Error: Please configure your Reddit API credentials in the secrets.properties file.");
            System.exit(1);
        }
        USER_AGENT = String.format("java:com.example.redditauth:v1.0 (by /u/%s)", REDDIT_USERNAME);
    }

    /**
     * Authenticates with the Reddit API and retrieves an access token.
     * @return The access token string.
     * @throws Exception if the request fails or returns an error.
     */
    public static String getAccessToken() throws Exception {
        String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        String authHeaderValue = "Basic " + encodedCredentials;

        HttpClient client = HttpClient.newHttpClient();

        String requestBody = "grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Authorization", authHeaderValue)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        System.out.println("Sending token request to Reddit...");

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode()!= 200) {
            throw new RuntimeException("Token request failed. Status Code: " + response.statusCode() + ", Body: " + response.body());
        }

        System.out.println("Response received successfully. Parsing token...");

        Gson gson = new Gson();
        String responseBody = response.body();
        RedditToken token = gson.fromJson(responseBody, RedditToken.class);

        if (token == null || token.getAccessToken() == null) {
            throw new RuntimeException("Failed to parse access token from response body: " + responseBody);
        }

        System.out.println("Token expires in: " + token.getExpiresIn() + " seconds.");
        return token.getAccessToken();
    }

    /**
     * Searches for the latest 10 posts on Reddit matching a query.
     * @param accessToken The OAuth 2.0 access token.
     * @param query The search term.
     * @throws Exception if the request fails.
     */
    public static JsonArray searchPosts(String accessToken, String query) throws Exception {
        System.out.println("\nSearching for the latest 50 posts mentioning '" + query + "'...");

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        String searchUrl = String.format("%s/search.json?q=%s&limit=50&sort=new", API_BASE_URL, encodedQuery);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("Authorization", "bearer " + accessToken)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode()!= 200) {
            throw new RuntimeException("Search request failed. Status Code: " + response.statusCode() + ", Body: " + response.body());
        }

        System.out.println("Search successful. Found posts:");

        String responseBody = response.body();
        JsonArray posts = new JsonArray();

        if (responseBody != null && !responseBody.isEmpty()) {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            JsonArray children = data.getAsJsonArray("children");

            for (JsonElement childElement : children) {
                JsonObject postData = childElement.getAsJsonObject().getAsJsonObject("data");
                
                JsonObject postToSave = new JsonObject();
                postToSave.add("id", postData.get("id"));
                postToSave.add("title", postData.get("title"));
                postToSave.add("text", postData.get("selftext"));
                postToSave.add("created_utc", postData.get("created_utc"));
                postToSave.add("permalink", postData.get("permalink"));
                postToSave.add("author", postData.get("author"));
                postToSave.add("score", postData.get("score"));
                postToSave.add("num_comments", postData.get("num_comments"));

                posts.add(postToSave);
            }
        }
        return posts;
    }

    @Override
    public void scan() {
        String resourceName = "search_queries.txt";

        try {
            String accessToken = getAccessToken();
            System.out.println("Successfully retrieved Reddit API Access Token.");

            try (InputStream is = RedditAuthClientWithSearch.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (is == null) {
                    System.err.println("Resource not found: " + resourceName);
                    return;
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = br.readLine()) != null) {
                        JsonObject searchQuery = JsonParser.parseString(line).getAsJsonObject();
                        String keyword = searchQuery.get("keyword").getAsString();
                        String category = searchQuery.get("category").getAsString();

                        if (!keyword.isEmpty()) {
                            System.out.println("Searching for: " + keyword);
                            JsonArray posts = searchPosts(accessToken, keyword);
                            if (posts.size() > 0) {
                                DatabaseService.saveRedditPosts(posts, keyword, category);
                            }
                            long delay = ThreadLocalRandom.current().nextLong(300000, 600001);
                            System.out.println(System.currentTimeMillis() + ": Waiting for " + (delay / 60000) + " minutes before the next keyword...");
                            Thread.sleep(delay);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error reading the file: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("An error occurred during the process.");
            e.printStackTrace();
        }
    }

    /**
     * A Plain Old Java Object (POJO) to represent the JSON response from Reddit's token endpoint.
     * GSON uses this class to deserialize the JSON string into a Java object.
     */
    public static class RedditToken {
        @SerializedName("access_token")
        private String accessToken;

        @SerializedName("token_type")
        private String tokenType;

        @SerializedName("expires_in")
        private int expiresIn;

        @SerializedName("scope")
        private String scope;

        public String getAccessToken() {
            return accessToken;
        }

        public int getExpiresIn() {
            return expiresIn;
        }
    }
}