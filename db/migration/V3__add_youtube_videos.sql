-- V3__add_youtube_videos.sql
-- Date: 2026-08-02
--
-- Adds like/view/comment count tracking for YouTube videos themselves (distinct from
-- youtube_comments, which stores individual comment text). A video can match several of an
-- entity's keywords, so it is deduped per entity: youtube_videos.id = "<videoId>_<entity>",
-- mirroring the composite-id convention used by x_posts / instagram_posts / reddit_posts.
--
-- Required by: DatabaseService.saveYouTubeVideos(...), called from YouTubeMain.search(...).
-- Re-scraping the same video refreshes view_count/like_count/comment_count via
-- ON CONFLICT (id) DO UPDATE instead of being skipped, since those numbers change over time.
--
-- Apply once against the `aura` database. Statements are idempotent (safe to re-run).

BEGIN;

CREATE TABLE IF NOT EXISTS youtube_videos (
    id                  TEXT PRIMARY KEY,
    video_id            TEXT NOT NULL,
    title               TEXT,
    channel_title       TEXT,
    published_at        TIMESTAMPTZ,
    permalink           TEXT,
    entity              TEXT,
    keyword             TEXT,
    sentiment_category  TEXT,
    view_count          BIGINT DEFAULT 0,
    like_count          BIGINT DEFAULT 0,
    comment_count       BIGINT DEFAULT 0,
    last_refreshed_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_youtube_videos_entity ON youtube_videos (entity);
CREATE INDEX IF NOT EXISTS idx_youtube_videos_video_id ON youtube_videos (video_id);

COMMIT;
