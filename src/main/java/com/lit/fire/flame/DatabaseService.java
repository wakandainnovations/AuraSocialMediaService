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
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class DatabaseService {

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

    public static void saveInstagramPosts(JsonArray posts, String keyword, String category) throws Exception {
        String sql = "INSERT INTO instagram_posts (id, text, media_type, media_url, permalink, timestamp, keyword, sentiment_category, author, like_count, comments_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                pstmt.setString(1, post.get("id").getAsString() + "_" + keyword);
                pstmt.setString(2, post.has("caption") ? post.get("caption").getAsString() : null);
                pstmt.setString(3, post.get("media_type").getAsString());
                pstmt.setString(4, post.has("media_url") ? post.get("media_url").getAsString() : null);
                pstmt.setString(5, post.get("permalink").getAsString());

                String timestampString = post.get("timestamp").getAsString();
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(timestampString, formatter);
                pstmt.setTimestamp(6, Timestamp.from(zonedDateTime.toInstant()));
                pstmt.setString(7, keyword);
                pstmt.setString(8, category);
                pstmt.setString(9, post.has("username") ? post.get("username").getAsString() : null);
                pstmt.setInt(10, post.has("like_count") ? post.get("like_count").getAsInt() : 0);
                pstmt.setInt(11, post.has("comments_count") ? post.get("comments_count").getAsInt() : 0);

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + posts.size() + " posts to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void saveXPosts(JsonArray posts, String keyword, String category) throws Exception {
        String sql = "INSERT INTO x_posts (id, text, created_at, keyword, sentiment_category, permalink, author, likes_count, comment_count, views_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int savedPosts = 0;
            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                String text = post.has("text") ? post.get("text").getAsString() : null;
                if (text != null && keyword != null && !keyword.isEmpty()) {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("@[\\w_]+");
                    java.util.regex.Matcher matcher = pattern.matcher(text);
                    boolean foundBadHandle = false;
                    while (matcher.find()) {
                        if (matcher.group().toLowerCase().contains(keyword.toLowerCase())) {
                            foundBadHandle = true;
                            break;
                        }
                    }
                    if (foundBadHandle) {
                        continue; // Skip this post
                    }
                }

                pstmt.setString(1, post.get("id").getAsString() + "_" + keyword);
                pstmt.setString(2, text);
                
                String timestampString = post.get("created_at").getAsString();
                Instant instant = Instant.parse(timestampString);
                pstmt.setTimestamp(3, Timestamp.from(instant));
                pstmt.setString(4, keyword);
                pstmt.setString(5, category);
                pstmt.setString(6, post.has("permalink") ? post.get("permalink").getAsString() : null);
                pstmt.setString(7, post.has("author") ? post.get("author").getAsString() : null);
                pstmt.setInt(8, post.has("likes_count") ? post.get("likes_count").getAsInt() : 0);
                pstmt.setInt(9, post.has("comment_count") ? post.get("comment_count").getAsInt() : 0);
                pstmt.setInt(10, post.has("views_count") ? post.get("views_count").getAsInt() : 0);

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

    public static void saveYouTubeComments(JsonArray comments, String keyword, String category) throws Exception {
        String sql = "INSERT INTO youtube_comments (id, video_id, video_title, text, author, published_at, permalink, keyword, sentiment_category) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (JsonElement commentElement : comments) {
                JsonObject comment = commentElement.getAsJsonObject();

                pstmt.setString(1, comment.get("comment_id").getAsString() + "_" + keyword);
                pstmt.setString(2, comment.get("video_id").getAsString());
                pstmt.setString(3, comment.get("video_title").getAsString());
                pstmt.setString(4, comment.get("text").getAsString());
                pstmt.setString(5, comment.get("author").getAsString());

                String timestampString = comment.get("published_at").getAsString();
                Instant instant = Instant.parse(timestampString);
                pstmt.setTimestamp(6, Timestamp.from(instant));
                pstmt.setString(7, comment.get("permalink").getAsString());
                pstmt.setString(8, keyword);
                pstmt.setString(9, category);

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("Successfully saved " + comments.size() + " comments to the database.");

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void saveRedditPosts(JsonArray posts, String keyword, String category) throws Exception {
        String sql = "INSERT INTO reddit_posts (id, title, text, created_at, keyword, sentiment_category, permalink, author, score, num_comments) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (JsonElement postElement : posts) {
                JsonObject post = postElement.getAsJsonObject();

                pstmt.setString(1, post.get("id").getAsString() + "_" + keyword);
                pstmt.setString(2, post.has("title") ? post.get("title").getAsString() : null);
                pstmt.setString(3, post.has("text") ? post.get("text").getAsString() : null);
                
                long createdUtc = post.get("created_utc").getAsLong();
                pstmt.setTimestamp(4, Timestamp.from(Instant.ofEpochSecond(createdUtc)));
                pstmt.setString(5, keyword);
                pstmt.setString(6, category);
                pstmt.setString(7, post.has("permalink") ? post.get("permalink").getAsString() : null);
                pstmt.setString(8, post.has("author") ? post.get("author").getAsString() : null);
                pstmt.setInt(9, post.has("score") ? post.get("score").getAsInt() : 0);
                pstmt.setInt(10, post.has("num_comments") ? post.get("num_comments").getAsInt() : 0);

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

    public static String getLastXPostId(String keyword) {
        String sql = "SELECT post_id FROM x_post_ids WHERE keyword = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, keyword);
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

    public static void saveLastXPostId(String keyword, String newestId) {
        String sql = "INSERT INTO x_post_ids (keyword, post_id) VALUES (?, ?) ON CONFLICT (keyword) DO UPDATE SET post_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, keyword);
            pstmt.setString(2, newestId);
            pstmt.setString(3, newestId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
