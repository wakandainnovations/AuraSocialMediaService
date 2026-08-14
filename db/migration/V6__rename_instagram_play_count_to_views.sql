-- V6__rename_instagram_play_count_to_views.sql
-- Date: 2026-08-14
--
-- instagram_posts.play_count was Instagram's video/Reel "view" surrogate (Apify's videoPlayCount /
-- igPlayCount), but the column name didn't make that clear. Renamed to `views` for clarity; the
-- column semantics are unchanged (Apify-sourced rows only, NULL for native Graph API rows).
--
-- Apply once against the `aura` database. Statement is idempotent (safe to re-run).

BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'instagram_posts' AND column_name = 'play_count'
    ) THEN
        ALTER TABLE instagram_posts RENAME COLUMN play_count TO views;
    END IF;
END $$;

COMMIT;
