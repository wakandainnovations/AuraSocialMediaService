-- V5__add_youtube_comment_dedup_and_backoff.sql
-- Date: 2026-08-06
--
-- Two additions supporting the Apify fallback for YouTube comment collection
-- (YouTubeCommentsApifyService), used once the YouTube Data API's quota is exhausted mid-run:
--
-- 1) youtube_comments.content_hash: the Apify actor and the native Data API don't share a
--    comment-id scheme, so the same physical comment surfaced by both sources would otherwise be
--    stored twice under two different `id` values (commentId_entity). content_hash is an
--    md5(video_id|entity|author|text) computed in DatabaseService.saveYouTubeComments(...); its
--    unique index makes ON CONFLICT DO NOTHING also catch this cross-source duplicate, not just a
--    literal id collision. Existing rows are left with a NULL content_hash rather than backfilled
--    (harmless: NULLs never conflict with each other in a unique index).
--
-- 2) youtube_video_comment_cursor: per-video backoff cursor. Every Apify comments fetch is a paid
--    Actor run, so if a video's latest-comments set comes back unchanged from last time, its next
--    fetch is pushed back with exponential backoff (capped) instead of being retried on every scan.
--    A video whose comment set does change resets to the 1-hour base interval.
--
-- Required by: DatabaseService.saveYouTubeComments(...), getYoutubeCommentCursor(...),
-- upsertYoutubeCommentCursor(...), called from YouTubeCommentsApifyService.
--
-- Apply once against the `aura` database. Statements are idempotent (safe to re-run).

BEGIN;

ALTER TABLE youtube_comments ADD COLUMN IF NOT EXISTS content_hash TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_youtube_comments_content_hash ON youtube_comments (content_hash);

CREATE TABLE IF NOT EXISTS youtube_video_comment_cursor (
    video_id          TEXT PRIMARY KEY,
    comments_hash     TEXT,
    next_fetch_at     TIMESTAMPTZ,
    backoff_seconds   BIGINT NOT NULL DEFAULT 3600,
    updated_at        TIMESTAMPTZ DEFAULT NOW()
);

COMMIT;
