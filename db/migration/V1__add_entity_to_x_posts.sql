-- V1__add_entity_to_x_posts.sql
-- Date: 2026-06-17
--
-- Supports the move from per-keyword to per-entity X/Twitter ingestion.
-- An entity can own multiple keywords; tweets are now searched with one OR'd query per entity
-- and stored/deduped per entity (x_posts.id = "<tweetId>_<entity>").
--
-- Required by: DatabaseService.saveXPosts(...), which now writes an `entity` column.
-- Apply once against the `aura` database. Statements are idempotent (safe to re-run).

BEGIN;

-- 1) x_posts: new column the application now inserts into.
ALTER TABLE x_posts ADD COLUMN IF NOT EXISTS entity TEXT;

-- 2) Helps the per-entity lookups / future reporting and keeps refresh scans cheap.
CREATE INDEX IF NOT EXISTS idx_x_posts_entity ON x_posts (entity);

COMMIT;

-- ----------------------------------------------------------------------------
-- OPTIONAL / NOT auto-applied
-- ----------------------------------------------------------------------------
-- x_post_ids: no schema change. Its `keyword` column now stores the ENTITY value
-- (since_id is tracked per entity). Existing per-keyword rows are harmless but stale;
-- each entity simply re-baselines its since_id on the first poll. To start clean:
--
--     TRUNCATE TABLE x_post_ids;
--
-- entity_keywords: only needed IF you later enable DatabaseService.upsertEntityKeyword(...),
-- which is currently commented out. When you do, add the matching column:
--
--     ALTER TABLE entity_keywords ADD COLUMN IF NOT EXISTS entity TEXT;
