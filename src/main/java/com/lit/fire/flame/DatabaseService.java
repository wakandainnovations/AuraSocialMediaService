package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DatabaseService {

    // Tunable: weight comments higher than likes in the traction score.
    private static final int COMMENT_WEIGHT = 3;
    // Tunable: a post is considered to "have traction" if score strictly exceeds this.
    private static final int TRACTION_THRESHOLD = 0;

    private static Connection getConnection() throws SQLException {
        Properties dbProperties = null;
        try {
            dbProperties = loadDbProperties();
        } catch (Exception e) {
            throw new SQLException("Failed to load db properties", e);
        }
        if (dbProperties == null) {
            throw new SQLException("db properties are null");
        }

        String dbUrl = dbProperties.getProperty("db.url", "jdbc:postgresql://localhost:5432/aura");
        String dbUser = dbProperties.getProperty("db.user", "postgres");
        String dbPassword = dbProperties.getProperty("db.password", "postgres");
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    private static Properties loadDbProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = DatabaseService.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (input == null) {
                System.out.println("Sorry, unable to find secrets.properties");
                return null;
            }
            properties.load(input);
        }
        return properties;
    }

    // Records which of the entity's keywords literally appear in the given text (comma-joined),
    // falling back to the entity name when none match (e.g. the keyword matched a hashtag/video
    // rather than the body text). Mirrors the matched-keyword recording in saveXPosts(...).
    private static String matchedKeywords(String text, String entity, List<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return entity;
        }
        String lowerText = text.toLowerCase();
        String matched = keywords.stream()
                .filter(kw -> lowerText.contains(kw.toLowerCase()))
                .collect(java.util.stream.Collectors.joining(","));
        return matched.isEmpty() ? entity : matched;
    }

    // Nullable setters for source-specific columns (e.g. Apify-only fields) that native scanners'
    // post objects never populate. Missing/null JSON keys become a real SQL NULL rather than a
    // synthetic 0/false, since "not provided by this source" and "confirmed zero/false" differ.
    private static void setNullableString(PreparedStatement pstmt, int index, JsonObject obj, String key) throws SQLException {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            pstmt.setString(index, obj.get(key).getAsString());
        } else {
            pstmt.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableInt(PreparedStatement pstmt, int index, JsonObject obj, String key) throws SQLException {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            pstmt.setInt(index, obj.get(key).getAsInt());
        } else {
            pstmt.setNull(index, Types.INTEGER);
        }
    }

    private static void setNullableDouble(PreparedStatement pstmt, int index, JsonObject obj, String key) throws SQLException {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            pstmt.setDouble(index, obj.get(key).getAsDouble());
        } else {
            pstmt.setNull(index, Types.DOUBLE);
        }
    }

    private static void setNullableBoolean(PreparedStatement pstmt, int index, JsonObject obj, String key) throws SQLException {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            pstmt.setBoolean(index, obj.get(key).getAsBoolean());
        } else {
            pstmt.setNull(index, Types.BOOLEAN);
        }
    }

    // Posts are keyed/deduped per entity: the composite id is postId_entity, so a post tagged with
    // several of the entity's hashtags is stored once. The `keyword` column records which of the
    // entity's keywords actually appear in the caption (comma-joined), falling back to the entity.
    // Columns after comments_count are Apify-sourced extras; native (Graph API) posts leave them NULL.
    public static void saveInstagramPosts(JsonArray posts, String entity, List<String> keywords, String category) throws Exception {
        String sql = "INSERT INTO instagram_posts (id, text, media_type, media_url, permalink, timestamp, entity, keyword, sentiment_category, author, like_count, comments_count, " +
                "shortcode, product_type, author_id, author_full_name, author_is_verified, author_follower_count, video_url, duration_seconds, hashtags, mentions, is_paid_partnership, play_count, reshare_count, location_name) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET like_count = EXCLUDED.like_count, comments_count = EXCLUDED.comments_count, " +
                "play_count = EXCLUDED.play_count, reshare_count = EXCLUDED.reshare_count";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                String caption = post.has("caption") ? post.get("caption").getAsString() : null;
                pstmt.setString(1, post.get("id").getAsString() + "_" + entity);
                pstmt.setString(2, caption);
                pstmt.setString(3, post.get("media_type").getAsString());
                pstmt.setString(4, post.has("media_url") ? post.get("media_url").getAsString() : null);
                pstmt.setString(5, post.get("permalink").getAsString());

                String timestampString = post.get("timestamp").getAsString();
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(timestampString, formatter);
                pstmt.setTimestamp(6, Timestamp.from(zonedDateTime.toInstant()));
                pstmt.setString(7, entity);
                pstmt.setString(8, matchedKeywords(caption, entity, keywords));
                pstmt.setString(9, category);
                pstmt.setString(10, post.has("username") ? post.get("username").getAsString() : null);
                pstmt.setInt(11, post.has("like_count") ? post.get("like_count").getAsInt() : 0);
                pstmt.setInt(12, post.has("comments_count") ? post.get("comments_count").getAsInt() : 0);

                setNullableString(pstmt, 13, post, "shortcode");
                setNullableString(pstmt, 14, post, "product_type");
                setNullableString(pstmt, 15, post, "author_id");
                setNullableString(pstmt, 16, post, "author_full_name");
                setNullableBoolean(pstmt, 17, post, "author_is_verified");
                setNullableInt(pstmt, 18, post, "author_follower_count");
                setNullableString(pstmt, 19, post, "video_url");
                setNullableDouble(pstmt, 20, post, "duration_seconds");
                setNullableString(pstmt, 21, post, "hashtags");
                setNullableString(pstmt, 22, post, "mentions");
                setNullableBoolean(pstmt, 23, post, "is_paid_partnership");
                setNullableInt(pstmt, 24, post, "play_count");
                setNullableInt(pstmt, 25, post, "reshare_count");
                setNullableString(pstmt, 26, post, "location_name");

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + posts.size() + " posts to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Posts are now keyed/deduped per entity: the composite id is tweetId_entity, so a tweet that
    // matched several of the entity's keywords is stored once. The `keyword` column records which of
    // the entity's keywords actually appear in the tweet text (comma-joined), falling back to the
    // entity name if none match literally.
    public static void saveXPosts(JsonArray posts, String entity, List<String> keywords, String category) throws Exception {
        String sql = "INSERT INTO x_posts (id, text, created_at, entity, keyword, sentiment_category, permalink, author, likes_count, comment_count, views_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int savedPosts = 0;
            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                String text = post.has("text") ? post.get("text").getAsString() : null;
                // Drop posts that merely @-mention a handle containing one of the entity's keywords.
                if (text != null && keywords != null && !keywords.isEmpty()) {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("@[\\w_]+");
                    java.util.regex.Matcher matcher = pattern.matcher(text);
                    boolean foundBadHandle = false;
                    while (matcher.find() && !foundBadHandle) {
                        String handle = matcher.group().toLowerCase();
                        for (String kw : keywords) {
                            if (handle.contains(kw.toLowerCase())) {
                                foundBadHandle = true;
                                break;
                            }
                        }
                    }
                    if (foundBadHandle) {
                        continue; // Skip this post
                    }
                }

                // Record which of the entity's keywords are present in the text.
                String matchedKeywords = entity;
                if (text != null && keywords != null && !keywords.isEmpty()) {
                    String lowerText = text.toLowerCase();
                    String matched = keywords.stream()
                            .filter(kw -> lowerText.contains(kw.toLowerCase()))
                            .collect(java.util.stream.Collectors.joining(","));
                    if (!matched.isEmpty()) {
                        matchedKeywords = matched;
                    }
                }

                pstmt.setString(1, post.get("id").getAsString() + "_" + entity);
                pstmt.setString(2, text);

                String timestampString = post.get("created_at").getAsString();
                Instant instant = Instant.parse(timestampString);
                pstmt.setTimestamp(3, Timestamp.from(instant));
                pstmt.setString(4, entity);
                pstmt.setString(5, matchedKeywords);
                pstmt.setString(6, category);
                pstmt.setString(7, post.has("permalink") ? post.get("permalink").getAsString() : null);
                pstmt.setString(8, post.has("author") ? post.get("author").getAsString() : null);
                pstmt.setInt(9, post.has("likes_count") ? post.get("likes_count").getAsInt() : 0);
                pstmt.setInt(10, post.has("comment_count") ? post.get("comment_count").getAsInt() : 0);
                pstmt.setInt(11, post.has("views_count") ? post.get("views_count").getAsInt() : 0);

                pstmt.addBatch();
                savedPosts++;
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + savedPosts + " posts to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Comments are keyed/deduped per entity: the composite id is commentId_entity, so a comment on a
    // video shared by several of the entity's keywords is stored once instead of once per keyword.
    // The `keyword` column records which of the entity's keywords appear in the comment text
    // (comma-joined), falling back to the entity name (the keyword usually matched the video, not
    // the comment body).
    public static void saveYouTubeComments(JsonArray comments, String entity, List<String> keywords, String category) throws Exception {
        String sql = "INSERT INTO youtube_comments (id, video_id, video_title, text, author, published_at, permalink, entity, keyword, sentiment_category) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (JsonElement commentElement : comments) {
                JsonObject comment = commentElement.getAsJsonObject();

                String text = comment.get("text").getAsString();
                pstmt.setString(1, comment.get("comment_id").getAsString() + "_" + entity);
                pstmt.setString(2, comment.get("video_id").getAsString());
                pstmt.setString(3, comment.get("video_title").getAsString());
                pstmt.setString(4, text);
                pstmt.setString(5, comment.get("author").getAsString());

                String timestampString = comment.get("published_at").getAsString();
                Instant instant = Instant.parse(timestampString);
                pstmt.setTimestamp(6, Timestamp.from(instant));
                pstmt.setString(7, comment.get("permalink").getAsString());
                pstmt.setString(8, entity);
                pstmt.setString(9, matchedKeywords(text, entity, keywords));
                pstmt.setString(10, category);

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + comments.size() + " comments to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Videos are keyed/deduped per entity: the composite id is videoId_entity, so a video matching
    // several of the entity's keywords is stored once. The `keyword` column records which of the
    // entity's keywords appear in the title (comma-joined), falling back to entity. Unlike comments,
    // view/like/comment counts keep changing after a video is first seen, so re-scans refresh them
    // via ON CONFLICT DO UPDATE instead of being skipped.
    public static void saveYouTubeVideos(JsonArray videos, String entity, List<String> keywords, String category) throws Exception {
        String sql = "INSERT INTO youtube_videos (id, video_id, title, channel_title, published_at, permalink, entity, keyword, sentiment_category, view_count, like_count, comment_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET view_count = EXCLUDED.view_count, like_count = EXCLUDED.like_count, " +
                "comment_count = EXCLUDED.comment_count, last_refreshed_at = NOW()";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (JsonElement videoElement : videos) {
                JsonObject video = videoElement.getAsJsonObject();

                String videoId = video.get("id").getAsString();
                String title = video.has("title") ? video.get("title").getAsString() : null;

                pstmt.setString(1, videoId + "_" + entity);
                pstmt.setString(2, videoId);
                pstmt.setString(3, title);
                pstmt.setString(4, video.has("channel_title") ? video.get("channel_title").getAsString() : null);

                String timestampString = video.get("published_at").getAsString();
                Instant instant = Instant.parse(timestampString);
                pstmt.setTimestamp(5, Timestamp.from(instant));
                pstmt.setString(6, video.get("permalink").getAsString());
                pstmt.setString(7, entity);
                pstmt.setString(8, matchedKeywords(title, entity, keywords));
                pstmt.setString(9, category);
                pstmt.setLong(10, video.has("view_count") ? video.get("view_count").getAsLong() : 0L);
                pstmt.setLong(11, video.has("like_count") ? video.get("like_count").getAsLong() : 0L);
                pstmt.setLong(12, video.has("comment_count") ? video.get("comment_count").getAsLong() : 0L);

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved/refreshed " + videos.size() + " YouTube videos to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Posts are keyed/deduped per entity: the composite id is postId_entity, so a post matching
    // several of the entity's keywords (one OR'd search) is stored once. The `keyword` column records
    // which of the entity's keywords appear in the title/body (comma-joined), falling back to entity.
    // Columns after num_comments are Apify-sourced extras; native (OAuth search) posts leave them NULL.
    public static void saveRedditPosts(JsonArray posts, String entity, List<String> keywords, String category) throws Exception {
        String sql = "INSERT INTO reddit_posts (id, title, text, created_at, entity, keyword, sentiment_category, permalink, author, score, num_comments, " +
                "flair, post_type, community_name, subreddit_subscribers, upvote_ratio, nsfw, spoiler, locked, stickied, total_awards_received, gilded, domain, word_count, author_flair_text) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET score = EXCLUDED.score, num_comments = EXCLUDED.num_comments, " +
                "upvote_ratio = EXCLUDED.upvote_ratio, total_awards_received = EXCLUDED.total_awards_received, gilded = EXCLUDED.gilded";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                String title = post.has("title") ? post.get("title").getAsString() : null;
                String body = post.has("text") ? post.get("text").getAsString() : null;
                String matchText = ((title == null ? "" : title) + " " + (body == null ? "" : body)).trim();

                pstmt.setString(1, post.get("id").getAsString() + "_" + entity);
                pstmt.setString(2, title);
                pstmt.setString(3, body);

                long createdUtc = post.get("created_utc").getAsLong();
                pstmt.setTimestamp(4, Timestamp.from(Instant.ofEpochSecond(createdUtc)));
                pstmt.setString(5, entity);
                pstmt.setString(6, matchedKeywords(matchText.isEmpty() ? null : matchText, entity, keywords));
                pstmt.setString(7, category);
                pstmt.setString(8, post.has("permalink") ? post.get("permalink").getAsString() : null);
                pstmt.setString(9, post.has("author") ? post.get("author").getAsString() : null);
                pstmt.setInt(10, post.has("score") ? post.get("score").getAsInt() : 0);
                pstmt.setInt(11, post.has("num_comments") ? post.get("num_comments").getAsInt() : 0);

                setNullableString(pstmt, 12, post, "flair");
                setNullableString(pstmt, 13, post, "post_type");
                setNullableString(pstmt, 14, post, "community_name");
                setNullableInt(pstmt, 15, post, "subreddit_subscribers");
                setNullableDouble(pstmt, 16, post, "upvote_ratio");
                setNullableBoolean(pstmt, 17, post, "nsfw");
                setNullableBoolean(pstmt, 18, post, "spoiler");
                setNullableBoolean(pstmt, 19, post, "locked");
                setNullableBoolean(pstmt, 20, post, "stickied");
                setNullableInt(pstmt, 21, post, "total_awards_received");
                setNullableInt(pstmt, 22, post, "gilded");
                setNullableString(pstmt, 23, post, "domain");
                setNullableInt(pstmt, 24, post, "word_count");
                setNullableString(pstmt, 25, post, "author_flair_text");

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + posts.size() + " Reddit posts to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static String getVideoETag(String videoId) {
        String sql = "SELECT etag FROM video_etags WHERE video_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, videoId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("etag");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public static void saveVideoETag(String videoId, String newETag) {
        String sql = "INSERT INTO video_etags (video_id, etag) VALUES (?, ?) ON CONFLICT (video_id) DO UPDATE SET etag = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, videoId);
            pstmt.setString(2, newETag);
            pstmt.setString(3, newETag);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // since_id is tracked per entity now (one OR'd search per entity). The x_post_ids.keyword
    // column stores the entity value.
    public static String getLastXPostId(String entity) {
        String sql = "SELECT post_id FROM x_post_ids WHERE keyword = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, entity);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("post_id");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public static void upsertEntityKeyword(JsonObject inputQuery) {
//        String entity = inputQuery.get("entity").getAsString();
//        String keyword = inputQuery.get("keyword").getAsString();
//        String category = inputQuery.has("category") ? inputQuery.get("category").getAsString() : null;
//        String language = inputQuery.has("language") ? inputQuery.get("language").getAsString() : null;
//        String state = inputQuery.has("state") ? inputQuery.get("state").getAsString() : null;
//        String industry = inputQuery.has("industry") ? inputQuery.get("industry").getAsString() : null;
//
//        String sql = "INSERT INTO entity_keywords (entity, keyword, category, language, state, industry) VALUES (?, ?, ?, ?, ?, ?) " +
//                "ON CONFLICT (keyword) DO UPDATE SET entity = EXCLUDED.entity, category = EXCLUDED.category, language = EXCLUDED.language, state = EXCLUDED.state, industry = EXCLUDED.industry";
//        try (Connection conn = getConnection();
//             PreparedStatement pstmt = conn.prepareStatement(sql)) {
//            pstmt.setString(1, entity);
//            pstmt.setString(2, keyword);
//            pstmt.setString(3, category);
//            pstmt.setString(4, language);
//            pstmt.setString(5, state);
//            pstmt.setString(6, industry);
//            pstmt.executeUpdate();
//        } catch (SQLException e) {
//            System.err.println("Database error upserting entity_keyword '" + keyword + "': " + e.getMessage());
//            e.printStackTrace();
//        }
    }

    // Backs the historical backfill (HistoricalBackfillService): pulls every keyword owned by a
    // managed_entities row of the given type whose language is (case-insensitively) one of
    // `languages`, joined through entity_keywords.entity_id. Rows with a null/blank keyword are
    // skipped since they can't drive a hashtag/search query. Shaped like the search_queries.txt
    // JsonObjects (entity/keyword/category) so callers can group-by-entity the same way the
    // *ApifyService scanners do.
    public static List<JsonObject> getManagedEntityKeywords(String type, List<String> languages) {
        List<JsonObject> result = new ArrayList<>();
        if (languages == null || languages.isEmpty()) {
            return result;
        }
        String sql = "SELECT me.name AS entity, ek.keyword, ek.category " +
                "FROM managed_entities me " +
                "JOIN entity_keywords ek ON ek.entity_id = me.id " +
                "WHERE me.type = ? AND ek.keyword IS NOT NULL AND trim(ek.keyword) <> '' " +
                "AND LOWER(me.language) = ANY (?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, type);
            String[] lowerLanguages = languages.stream()
                    .map(String::toLowerCase)
                    .toArray(String[]::new);
            pstmt.setArray(2, conn.createArrayOf("text", lowerLanguages));

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("entity", rs.getString("entity"));
                    row.addProperty("keyword", rs.getString("keyword"));
                    row.addProperty("category", rs.getString("category"));
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error in getManagedEntityKeywords: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Returns rows due for a metrics refresh, tiered by age + existing traction:
     *  - Tier A (created within last 2h): refresh up to once per hour, regardless of traction.
     *  - Tier B (older than 2h, has traction, within 7d): refresh once per 12h.
     *  - Tier C (older than 2h, no traction): excluded — never refreshed again.
     * Each row gives both the composite DB id and the underlying X tweet id.
     */
    public static List<Map<String, String>> getXPostsDueForRefresh(int maxRows) {
        String sql =
            "SELECT id, split_part(id, '_', 1) AS x_id " +
            "FROM x_posts " +
            "WHERE " +
            // Tier A — discovery window
            "  (created_at > NOW() - INTERVAL '2 hours' " +
            "   AND (last_refreshed_at IS NULL " +
            "        OR last_refreshed_at < NOW() - INTERVAL '55 minutes')) " +
            "OR " +
            // Tier B — proven traction, mature posts (cap at 7d)
            "  (created_at <= NOW() - INTERVAL '2 hours' " +
            "   AND created_at > NOW() - INTERVAL '7 days' " +
            "   AND (likes_count + comment_count * " + COMMENT_WEIGHT + ") > " + TRACTION_THRESHOLD + " " +
            "   AND (last_refreshed_at IS NULL " +
            "        OR last_refreshed_at < NOW() - INTERVAL '12 hours')) " +
            "LIMIT ?";

        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, maxRows);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new HashMap<>();
                    row.put("composite_id", rs.getString("id"));
                    row.put("x_id", rs.getString("x_id"));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error in getXPostsDueForRefresh: " + e.getMessage());
            e.printStackTrace();
        }
        return rows;
    }

    // Bumps last_refreshed_at on every candidate row, including those X dropped (deleted/private),
    // so they don't re-enter the candidate set on the next cycle.
    public static void markRefreshAttempted(List<String> compositeIds) {
        if (compositeIds.isEmpty()) return;
        String sql = "UPDATE x_posts SET last_refreshed_at = NOW() WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (String id : compositeIds) {
                pstmt.setString(1, id);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        } catch (SQLException e) {
            System.err.println("Database error in markRefreshAttempted: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // metricsByXId: x_id -> [likes, comments, views]. Same X tweet may map to multiple rows
    // (one per keyword), so we match by the X-side prefix of the composite id.
    public static void updateXPostMetrics(Map<String, int[]> metricsByXId) {
        if (metricsByXId.isEmpty()) return;
        String sql = "UPDATE x_posts SET likes_count = ?, comment_count = ?, views_count = ?, " +
                     "last_refreshed_at = NOW() WHERE split_part(id, '_', 1) = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (Map.Entry<String, int[]> e : metricsByXId.entrySet()) {
                int[] m = e.getValue();
                pstmt.setInt(1, m[0]);
                pstmt.setInt(2, m[1]);
                pstmt.setInt(3, m[2]);
                pstmt.setString(4, e.getKey());
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            System.out.println("Metrics refreshed for " + metricsByXId.size() + " distinct X posts.");
        } catch (SQLException e) {
            System.err.println("Database error in updateXPostMetrics: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void saveLastXPostId(String entity, String newestId) {
        String sql = "INSERT INTO x_post_ids (keyword, post_id) VALUES (?, ?) ON CONFLICT (keyword) DO UPDATE SET post_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, entity);
            pstmt.setString(2, newestId);
            pstmt.setString(3, newestId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
