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

    /** Dataset items of a finished run, plus the run object (id, status, pricingInfo, chargedEventCounts). */
    public record RunResult(JsonArray items, JsonObject run) {
        public String runId() {
            return run != null && run.has("id") ? run.get("id").getAsString() : null;
        }
    }

    // How long runActor waits for a run to finish before aborting it.
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(15);

    /**
     * Like {@link #runActorAndGetDatasetItems} but starts the run asynchronously and waits for it,
     * so the caller also gets the run object - its id and what Apify charged for it - which the
     * synchronous endpoint doesn't return. Used where spend is tracked (XApifyService).
     */
    public static RunResult runActor(String actorId, JsonObject input) throws Exception {
        String token = loadApiToken();
        String encodedActorId = actorId.replace("/", "~");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        // waitForFinish is capped at 60s by the API; keep re-waiting on the run until it's done.
        JsonObject run = sendJson(client, HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/acts/" + encodedActorId + "/runs?waitForFinish=60"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(input.toString(), StandardCharsets.UTF_8)), token, actorId);
        long deadline = System.currentTimeMillis() + RUN_DEADLINE.toMillis();
        while (isRunning(run)) {
            if (System.currentTimeMillis() > deadline) {
                String runId = run.get("id").getAsString();
                try {
                    sendJson(client, HttpRequest.newBuilder()
                            .uri(URI.create(API_BASE_URL + "/actor-runs/" + runId + "/abort"))
                            .POST(HttpRequest.BodyPublishers.noBody()), token, actorId);
                } catch (Exception ignored) {
                    // Best effort; the timeout below is what matters to the caller.
                }
                throw new RuntimeException("Apify Actor run " + runId + " for '" + actorId + "' did not finish within "
                        + RUN_DEADLINE.toMinutes() + " minutes; aborted.");
            }
            run = sendJson(client, HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL + "/actor-runs/" + run.get("id").getAsString() + "?waitForFinish=60"))
                    .GET(), token, actorId);
        }

        String status = run.has("status") ? run.get("status").getAsString() : "UNKNOWN";
        if (!status.equals("SUCCEEDED")) {
            throw new RuntimeException("Apify Actor run " + run.get("id").getAsString() + " for '" + actorId
                    + "' ended with status " + status);
        }

        HttpResponse<String> itemsResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/datasets/" + run.get("defaultDatasetId").getAsString()
                        + "/items?format=json&clean=true"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        if (itemsResponse.statusCode() < 200 || itemsResponse.statusCode() >= 300) {
            throw new RuntimeException("Reading dataset for Apify run " + run.get("id").getAsString() + " failed. Status: "
                    + itemsResponse.statusCode() + ", Body: " + itemsResponse.body());
        }
        String body = itemsResponse.body();
        JsonArray items = body == null || body.isBlank() ? new JsonArray() : JsonParser.parseString(body).getAsJsonArray();
        return new RunResult(items, run);
    }

    private static boolean isRunning(JsonObject run) {
        String status = run.has("status") ? run.get("status").getAsString() : "";
        return status.equals("READY") || status.equals("RUNNING");
    }

    // Sends a request and returns the "data" object of Apify's JSON envelope.
    private static JsonObject sendJson(HttpClient client, HttpRequest.Builder builder, String token, String actorId) throws Exception {
        HttpResponse<String> response = client.send(builder
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Apify request for '" + actorId + "' failed. Status: " + response.statusCode()
                    + ", Body: " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
    }
}
