-- V7__add_tracked_releases.sql
-- Date: 2026-11-15
--
-- Replaces the hand-maintained search_queries.txt with an automatically populated, admin-editable
-- list of movies to scan. Every India-origin release TMDB knows about is discovered by
-- ReleaseDiscoveryService and inserted here with a tracking window of release_date - 7 days to
-- release_date + 7 days (track_from / track_until). The X/YouTube/Instagram/Reddit scanners only scan
-- rows whose window contains today and that are not excluded (TrackedReleaseSource).
--
-- Admins manage the list from aura-radix (Administration -> Tracked Releases), served by
-- AuraService's /api/admin/tracked-releases:
--   * excluded = TRUE stops scanning immediately (scanners re-check before every entity) and is
--     never undone by re-discovery, so a removed TMDB movie stays removed.
--   * keywords can be edited (comma-separated; first value is the primary hashtag form).
--   * source = 'MANUAL' rows are movies TMDB doesn't list, added by hand.
--
-- entity_name is the value written to x_posts.entity / instagram_posts.entity / etc. It is fixed at
-- insert time (a later TMDB title change only updates `title`) so collected data stays joined to it.
--
-- AuraService (spring.jpa.hibernate.ddl-auto=update) would also create this table from its
-- TrackedRelease entity; this script is the canonical definition. Idempotent (safe to re-run).

BEGIN;

CREATE TABLE IF NOT EXISTS tracked_releases (
    id                BIGSERIAL PRIMARY KEY,
    tmdb_id           INTEGER UNIQUE,
    entity_name       TEXT NOT NULL UNIQUE,
    title             TEXT NOT NULL,
    original_title    TEXT,
    language          TEXT,
    release_date      DATE NOT NULL,
    track_from        DATE NOT NULL,
    track_until       DATE NOT NULL,
    keywords          TEXT NOT NULL,
    category          TEXT NOT NULL DEFAULT 'media.movie',
    source            TEXT NOT NULL DEFAULT 'TMDB',
    popularity        DOUBLE PRECISION,
    poster_path       TEXT,
    excluded          BOOLEAN NOT NULL DEFAULT FALSE,
    excluded_reason   TEXT,
    excluded_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tracked_releases_window ON tracked_releases (track_from, track_until);

COMMIT;
