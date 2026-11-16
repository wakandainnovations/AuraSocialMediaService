-- V9__add_tracked_release_details.sql
-- Date: 2026-11-16
--
-- TMDB movie details kept on each tracked release, refreshed by every discovery pass.
-- AuraService's "Add to platform" copies them onto the new managed_entities row so the movie's
-- snapshot (genre, runtime, director, production company) isn't blank. Idempotent.

BEGIN;

ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS genres             TEXT;    -- comma-separated TMDB genre names
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS runtime            INTEGER; -- minutes
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS director           TEXT;    -- comma-separated when several
ALTER TABLE tracked_releases ADD COLUMN IF NOT EXISTS production_company TEXT;    -- first listed on TMDB

COMMIT;
