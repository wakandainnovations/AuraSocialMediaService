package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * Minimal client for running an Apify Actor synchronously and reading back its dataset items.
 * Shared by the Apify-backed Instagram and Reddit scanners.
 */
public class ApifyClient {

    private static final String API_BASE_URL = "https://api.apify.com/v2";
    // An Actor run can legitimately take a while; the sync endpoint itself times out at 300s server-side.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    private static volatile String apiToken;

    private static String loadApiToken() {
        if (apiToken != null) {
            return apiToken;
        }
        synchronized (ApifyClient.class) {
            if (apiToken != null) {
                return apiToken;
            }
            Properties properties = new Properties();
            try (InputStream input = ApifyClient.class.getClassLoader().getResourceAsStream("secrets.properties")) {
                if (input == null) {
                    throw new IllegalStateException("Unable to find secrets.properties. Please ensure the file exists and contains apify.api_token.");
                }
                properties.load(input);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load secrets.properties", e);
            }

            String token = properties.getProperty("apify.api_token");
            if (token == null || token.isBlank() || token.equals("YOUR_APIFY_API_TOKEN")) {
                throw new IllegalStateException("Please configure your Apify API token (apify.api_token) in the secrets.properties file.");
            }
            apiToken = token;
            return apiToken;
        }
    }

    /**
     * Runs the given Actor (e.g. "breathtaking_anthem/instagram-hashtag-posts-scraper") synchronously
     * with the supplied input and returns the resulting dataset items as a JsonArray.
     */
    public static JsonArray runActorAndGetDatasetItems(String actorId, JsonObject input) throws Exception {
        String token = loadApiToken();
        // The REST API addresses an Actor as "owner~name" rather than "owner/name".
        String encodedActorId = actorId.replace("/", "~");
        String url = API_BASE_URL + "/actors/" + encodedActorId + "/run-sync-get-dataset-items?format=json";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(input.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Apify Actor run failed for '" + actorId + "'. Status: " + response.statusCode() + ", Body: " + response.body());
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return new JsonArray();
        }
        return JsonParser.parseString(body).getAsJsonArray();
    }
}
