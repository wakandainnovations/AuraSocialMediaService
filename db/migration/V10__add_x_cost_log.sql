-- V10__add_x_cost_log.sql
-- Date: 2026-11-17
--
-- Per-call cost log for the two X/Twitter providers (x.provider=apify -> XApifyService,
-- x.provider=api -> XService), so their real spend can be compared side by side.
--
-- One row per Apify Actor run or X API request:
--   provider      : 'apify' or 'x_api'
--   operation     : 'poll' (new tweets), 'refresh' (likes/replies/views), 'backfill'
--   entity        : movie the call was for (NULL for XService's cross-movie metrics lookup)
--   queries       : search queries billed (Apify) / HTTP requests made (X API)
--   items         : tweets returned (= billed dataset items for Apify, post reads for X API)
--   users         : user objects returned (X API search expansion; 0 for Apify)
--   estimated_usd : cost from the configured prices in application.properties (x.*.cost.*)
--   charged_usd   : Apify only - what Apify reported charging for the run (from the run's
--                   chargedEventCounts x its pricing), NULL when it couldn't be read
--   apify_run_id  : Apify only - look the run up in the Apify console to reconcile
--
-- x_cost_daily sums it per UTC day. Idempotent (safe to re-run).

BEGIN;

CREATE TABLE IF NOT EXISTS x_cost_log (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    provider       TEXT NOT NULL,
    operation      TEXT NOT NULL,
    entity         TEXT,
    queries        INTEGER NOT NULL DEFAULT 0,
    items          INTEGER NOT NULL DEFAULT 0,
    users          INTEGER NOT NULL DEFAULT 0,
    estimated_usd  NUMERIC(12, 6) NOT NULL DEFAULT 0,
    charged_usd    NUMERIC(12, 6),
    apify_run_id   TEXT,
    details        TEXT
);

CREATE INDEX IF NOT EXISTS x_cost_log_created_at_idx ON x_cost_log (created_at);

CREATE OR REPLACE VIEW x_cost_daily AS
SELECT (created_at AT TIME ZONE 'UTC')::date AS day,
       provider,
       operation,
       COUNT(*)                   AS calls,
       SUM(queries)               AS queries,
       SUM(items)                 AS items,
       SUM(users)                 AS users,
       SUM(estimated_usd)         AS estimated_usd,
       SUM(charged_usd)           AS charged_usd,
       COUNT(DISTINCT entity)     AS movies
FROM x_cost_log
GROUP BY 1, 2, 3;

COMMIT;
