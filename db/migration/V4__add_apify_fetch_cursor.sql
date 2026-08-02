-- V4__add_apify_fetch_cursor.sql
-- Date: 2026-08-02
--
-- Tracks, per (platform, entity), the timestamp of that entity's last successful Apify fetch.
-- Lets InstagramApifyService/RedditApifyService ask for only content newer than what was already
-- fetched instead of re-paying to re-scrape the same window every scan cycle.
--
-- Required by: DatabaseService.getLastFetchTime(...)/updateLastFetchTime(...), called from
-- InstagramApifyService.scan(...) and RedditApifyService.scan(...).
--
-- Apply once against the `aura` database. Statements are idempotent (safe to re-run).

BEGIN;

CREATE TABLE IF NOT EXISTS apify_fetch_cursor (
    platform          TEXT NOT NULL,
    entity            TEXT NOT NULL,
    last_fetched_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (platform, entity)
);

COMMIT;
