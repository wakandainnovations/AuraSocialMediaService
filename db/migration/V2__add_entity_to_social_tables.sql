-- V2__add_entity_to_social_tables.sql
-- Date: 2026-06-17
--
-- Extends the per-entity de-duplication used for X/Twitter (see V1) to YouTube, Instagram and Reddit.
-- An entity can own multiple keywords; each platform now collects per entity and stores/dedupes per
-- entity, so the SAME comment/post matched by several of the entity's keywords is stored once:
--   youtube_comments.id = "<commentId>_<entity>"
--   instagram_posts.id  = "<postId>_<entity>"
--   reddit_posts.id     = "<postId>_<entity>"
--
-- Required by: DatabaseService.saveYouTubeComments(...), saveInstagramPosts(...), saveRedditPosts(...),
-- which now write an `entity` column. The `keyword` column on each table is retained and now stores
-- the entity's keyword(s) that actually appear in the text (comma-joined), falling back to the entity.
--
-- Apply once against the `aura` database. Statements are idempotent (safe to re-run).

BEGIN;

-- youtube_comments
ALTER TABLE youtube_comments ADD COLUMN IF NOT EXISTS entity TEXT;
CREATE INDEX IF NOT EXISTS idx_youtube_comments_entity ON youtube_comments (entity);

-- instagram_posts
ALTER TABLE instagram_posts ADD COLUMN IF NOT EXISTS entity TEXT;
CREATE INDEX IF NOT EXISTS idx_instagram_posts_entity ON instagram_posts (entity);

-- reddit_posts
ALTER TABLE reddit_posts ADD COLUMN IF NOT EXISTS entity TEXT;
CREATE INDEX IF NOT EXISTS idx_reddit_posts_entity ON reddit_posts (entity);

COMMIT;

-- ----------------------------------------------------------------------------
-- NOTE
-- ----------------------------------------------------------------------------
-- The composite primary key column `id` simply changes shape (now "_<entity>" instead of "_<keyword>").
-- No PK/type change is required. Existing per-keyword rows remain valid but stale; they are harmless
-- and will not collide with the new per-entity ids. To start clean you may optionally clear old rows,
-- e.g.:
--     -- DELETE FROM youtube_comments;  -- (and instagram_posts / reddit_posts)
