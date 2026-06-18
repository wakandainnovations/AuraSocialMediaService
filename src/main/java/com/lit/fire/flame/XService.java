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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A client for searching X/Twitter posts.
 */
public class XService implements SocialMediaScanner {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static String ACCESS_TOKEN;
//    private static final String API_URL = "https://api.twitter.com/2";
    private static final String API_URL = "https://api.x.com/2";
    private static int numberOfPosts;

    // X API hard cap for batched tweet lookups
    private static final int LOOKUP_BATCH_SIZE = 100;
    // Ceiling on post-reads spent per hourly refresh cycle
    private static final int MAX_REFRESH_PER_CYCLE = 100;
    // Max length of the recent-search `query` operator string (Basic access caps at 512 chars).
    // An entity with more keywords than fit is split across multiple OR'd search calls.
    private static final int MAX_QUERY_CHARS = 512;

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

    /**
     * Searches X/Twitter for a single entity. The entity's keywords are combined into OR'd
     * recent-search calls (e.g. "(GDN OR GDNaidu)"). X de-duplicates its own results, so a tweet
     * that matches several of the entity's keywords is read (and billed) exactly once instead of
     * once per keyword. since_id is tracked per entity. When the keyword list is too long to fit in
     * a single query (MAX_QUERY_CHARS), it is split across multiple OR'd calls.
     */
    public static void search(String entity, List<String> keywords, String category) throws Exception {
        System.out.println("\nRetrieving latest " + numberOfPosts + " posts for entity '" + entity
                + "' (keywords: " + String.join(", ", keywords) + ")...");

        // Split into chunks that each keep the OR'd query within the API's length cap.
        List<List<String>> chunks = chunkKeywords(keywords, MAX_QUERY_CHARS);
        if (chunks.size() > 1) {
            System.out.println("Entity '" + entity + "' has " + keywords.size()
                    + " keywords; split into " + chunks.size() + " query chunk(s) to stay under "
                    + MAX_QUERY_CHARS + " chars.");
        }

        String fields = "id,text,created_at,author_id,public_metrics";
        String expansions = "author_id";
        String userFields = "username,name";
        // SME Recommendation: Pass since_id to avoid duplicate ingestion (tracked per entity).
        // Read the baseline once and apply it to every chunk so no chunk misses tweets.
        String lastPostId = DatabaseService.getLastXPostId(entity);

        // Advance the entity's since_id only after all chunks are processed (using the max newest_id
        // seen), so a crash mid-cycle can't skip tweets the remaining chunks would have caught.
        String maxNewestId = null;

        for (List<String> chunk : chunks) {
            String encodedQuery = URLEncoder.encode(buildQuery(chunk), StandardCharsets.UTF_8);
            String searchUrl = String.format("%s/tweets/search/recent?query=%s&tweet.fields=%s&expansions=%s&user.fields=%s&max_results=%d",
                    API_URL, encodedQuery, fields, expansions, userFields, numberOfPosts);

            if (lastPostId != null && !lastPostId.trim().isEmpty() && !lastPostId.equalsIgnoreCase("null")) {
                searchUrl = String.format("%s/tweets/search/recent?query=%s&since_id=%s&tweet.fields=%s&expansions=%s&user.fields=%s&max_results=%d",
                        API_URL, encodedQuery, lastPostId, fields, expansions, userFields, numberOfPosts);
            }
            JsonObject response;
            try {
                response = sendRequest(searchUrl);
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("since_id")) {
                    String correctedId = extractCorrectedSinceId(e.getMessage());
                    if (correctedId != null) {
                        System.err.println("Stale since_id for '" + entity + "'. Updated to " + correctedId + " (30-min buffer) for next poll.");
                        DatabaseService.saveLastXPostId(entity, correctedId);
                    }
                    return;
                }
                throw e;
            }

            if (response != null && response.has("meta")) {
                JsonObject meta = response.getAsJsonObject("meta");
                if (meta.has("newest_id")) {
                    maxNewestId = maxId(maxNewestId, meta.get("newest_id").getAsString());
                }
            }

            // Guard against empty result sets
            if (response != null && response.has("meta")
                    && response.getAsJsonObject("meta").get("result_count").getAsInt() == 0) {
                System.out.println("No new posts for entity: " + entity + " (chunk: " + buildQuery(chunk) + "). Skipping processing.");
                continue; // Exit early to save CPU on your server
            }

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

                    String username = (user != null) ? user.get("username").getAsString() : "unknown_user";
                    String authorName = (user != null) ? user.get("name").getAsString() : "Unknown";

                    post.addProperty("author", authorName);
                    String permalink = "https://twitter.com/" + username + "/status/" + post.get("id").getAsString();
                    post.addProperty("permalink", permalink);

                    if (post.has("public_metrics")) {
                        JsonObject publicMetrics = post.getAsJsonObject("public_metrics");
                        post.addProperty("likes_count", publicMetrics.get("like_count").getAsInt());
                        post.addProperty("comment_count", publicMetrics.get("reply_count").getAsInt());
                        // ADDED: Extracting impression_count (Views)
                        // Note: impression_count might be null for very old tweets, but for search/recent it's usually present.
                        int views = publicMetrics.has("impression_count") ? publicMetrics.get("impression_count").getAsInt() : 0;
                        post.addProperty("views_count", views);
                    } else {
                        post.addProperty("likes_count", 0);
                        post.addProperty("comment_count", 0);
                        post.addProperty("views_count", 0);
                    }
                }
                // Pass only this chunk's keywords so matched-keyword detection stays accurate.
                DatabaseService.saveXPosts(posts, entity, chunk, category);
            }
        }

        if (maxNewestId != null) {
            DatabaseService.saveLastXPostId(entity, maxNewestId);
        }
    }

    // Builds the OR'd recent-search query operator string for a set of keywords.
    private static String buildQuery(List<String> keywords) {
        return keywords.size() == 1 ? keywords.get(0) : "(" + String.join(" OR ", keywords) + ")";
    }

    /**
     * Splits an entity's keywords into chunks whose OR'd query string stays within maxChars.
     * A single keyword longer than maxChars is placed in its own chunk (the API will reject it,
     * but it never silently drops other keywords).
     */
    static List<List<String>> chunkKeywords(List<String> keywords, int maxChars) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String kw : keywords) {
            if (current.isEmpty()) {
                current.add(kw);
                continue;
            }
            List<String> candidate = new ArrayList<>(current);
            candidate.add(kw);
            if (buildQuery(candidate).length() > maxChars) {
                chunks.add(current);
                current = new ArrayList<>();
            }
            current.add(kw);
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    // Returns the larger of two numeric snowflake id strings (compared by length, then lexically),
    // null-safe so it can fold across chunks.
    private static String maxId(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.length() != b.length()) return a.length() > b.length() ? a : b;
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Refreshes likes/comments/views on previously-collected posts using the batched
     * /2/tweets?ids= lookup (up to 100 IDs per HTTP call). Candidate posts come from
     * DatabaseService.getXPostsDueForRefresh(...), which enforces the tiered policy:
     *  - Discovery (< 2h old):        refreshed up to once per hour, regardless of traction.
     *  - Traction (>= 2h, > 0 score): refreshed once per 12h, up to 7 days.
     *  - No traction past 2h:         never refreshed (zero cost).
     */
    public static void refreshMetrics() throws Exception {
        List<Map<String, String>> due = DatabaseService.getXPostsDueForRefresh(MAX_REFRESH_PER_CYCLE);
        if (due.isEmpty()) {
            System.out.println("[Aura-X Refresh] Nothing due.");
            return;
        }

        // A single X tweet may live under several entity composite rows; dedupe for the API call.
        Set<String> distinctXIds = new LinkedHashSet<>();
        List<String> compositeIds = new ArrayList<>();
        for (Map<String, String> row : due) {
            distinctXIds.add(row.get("x_id"));
            compositeIds.add(row.get("composite_id"));
        }
        System.out.println("[Aura-X Refresh] candidates: " + compositeIds.size()
                + " rows / " + distinctXIds.size() + " distinct tweets.");

        Map<String, int[]> metricsByXId = new HashMap<>();
        List<String> xIdList = new ArrayList<>(distinctXIds);
        for (int i = 0; i < xIdList.size(); i += LOOKUP_BATCH_SIZE) {
            List<String> chunk = xIdList.subList(i, Math.min(i + LOOKUP_BATCH_SIZE, xIdList.size()));
            String url = String.format("%s/tweets?ids=%s&tweet.fields=public_metrics",
                    API_URL, String.join(",", chunk));

            JsonObject resp = sendRequest(url);
            if (resp == null || !resp.has("data")) continue;

            for (JsonElement el : resp.getAsJsonArray("data")) {
                JsonObject t = el.getAsJsonObject();
                if (!t.has("public_metrics")) continue;
                JsonObject pm = t.getAsJsonObject("public_metrics");
                int likes    = pm.has("like_count")       ? pm.get("like_count").getAsInt()       : 0;
                int comments = pm.has("reply_count")      ? pm.get("reply_count").getAsInt()      : 0;
                int views    = pm.has("impression_count") ? pm.get("impression_count").getAsInt() : 0;
                metricsByXId.put(t.get("id").getAsString(), new int[]{likes, comments, views});
            }
            // Deleted/private IDs come back in `errors[]` and are simply absent from `data`.
        }

        // Mark all candidates as attempted first, so dead IDs don't re-enter next cycle.
        DatabaseService.markRefreshAttempted(compositeIds);
        DatabaseService.updateXPostMetrics(metricsByXId);
    }

    private static String extractCorrectedSinceId(String errorMessage) {
        try {
            String marker = "larger than ";
            int idx = errorMessage.indexOf(marker);
            if (idx == -1) return null;
            int start = idx + marker.length();
            int end = start;
            while (end < errorMessage.length() && Character.isDigit(errorMessage.charAt(end))) end++;
            long minSinceId = Long.parseLong(errorMessage.substring(start, end));
            // Twitter snowflake: timestamp_ms occupies bits 63-22.
            // 30 minutes = 1,800,000 ms; shift left 22 to get the ID-space offset.
            long thirtyMinOffset = 30L * 60 * 1000 * (1L << 22);
            return String.valueOf(minSinceId + thirtyMinOffset);
        } catch (Exception e) {
            return null;
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
        // 2. Handle Rate Limiting (X API 429)
        if (response.statusCode() == 429) {
            // Extract the reset time from headers
            Optional<String> resetHeader = response.headers().firstValue("x-rate-limit-reset");

            if (resetHeader.isPresent()) {
                long resetTimeUnix = Long.parseLong(resetHeader.get());
                long currentTimeUnix = System.currentTimeMillis() / 1000;
                long waitTimeSeconds = Math.max(resetTimeUnix - currentTimeUnix, 0) + 1;

                System.err.println("X API Throttled. Waiting " + waitTimeSeconds + " seconds until reset...");

                // SME Tip: In a multi-threaded 8-core environment,
                // sleeping here is okay IF you use a dedicated thread pool for X.
                Thread.sleep(waitTimeSeconds * 1000);

                // Recursive call to retry once
                return sendRequest(url);
            }
        }

        throw new RuntimeException("API Request failed. Status: " + response.statusCode() + ", Body: " + response.body());
    }

    @Override
    public void scan() {
        try {
            loadConfig();
            System.out.println("Initializing X Search...");
            List<JsonObject> inputQueries = loadInputQueries();

            // Group keyword lines by entity. A single entity can own multiple keywords, but each
            // line carries a unique keyword. We poll once per entity (not per keyword), combining
            // its keywords into one OR'd query so a shared tweet is read once.
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

                // SME Recommendation: Stagger initial starts (0-5 mins)
                // to avoid hitting X API rate limits for all entities at once.
                long initialDelay = ThreadLocalRandom.current().nextLong(0, 301);

                // Schedule the task to run every 1 hour (3600 seconds)
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        System.out.println("\n[Aura-X] Polling for entity: " + entity);
                        search(entity, keywords, category);
                    } catch (Exception e) {
                        System.err.println("Search failed for entity " + entity + ": " + e.getMessage());
                    }
                }, initialDelay, 3600, TimeUnit.SECONDS);

                System.out.println("Queued entity '" + entity + "' with initial delay of " + (initialDelay / 60) + " mins.");
            }

            // Hourly metrics refresh across all previously-collected posts (tiered policy in SQL).
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    refreshMetrics();
                } catch (Exception e) {
                    System.err.println("refreshMetrics failed: " + e.getMessage());
                }
            }, 600, 3600, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("An unrecoverable error occurred during the process.");
            e.printStackTrace();
        }
    }
}