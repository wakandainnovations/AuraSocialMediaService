-- V8__add_tracked_release_relevance.sql
-- Date: 2026-11-16
--
-- Relevance check for tracked releases (RelevanceCheckService). Generic titles ("Titanic", "Ah",
-- "Dark Chocolate") match mostly unrelated posts. After a movie's first X polls, the share of its
-- collected posts that look movie-related is computed hourly:
--   relevance_pct / relevance_sample : last computed score (0-100) and how many posts it's based on
--   relevance_checked_at             : when the movie was first evaluated - Instagram/Reddit (paid
--                                      Apify runs) only scan a movie once this is set and the score
--                                      isn't failing, so junk titles never cost Apify money
--   context_terms                    : per-movie signals from TMDB (cast/director names, native
--                                      title) that count as movie context, comma-separated
--   relevance_override               : set when an admin restores a movie; the check then never
--                                      auto-excludes it again
-- A movie with >= relevance.min.posts posts and < relevance.min.pct % movie-related is auto-excluded.
--
-- Idempotent (safe to re-run).

BEGIN;

ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS relevance_pct DOUBLE PRECISION;
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS relevance_sample INTEGER;
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS relevance_checked_at TIMESTAMPTZ;
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS context_terms TEXT;
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS relevance_override BOOLEAN NOT NULL DEFAULT FALSE;

COMMIT;
