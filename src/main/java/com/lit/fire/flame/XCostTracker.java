package com.lit.fire.flame;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Map;

/**
 * Records what every X/Twitter call costs into x_cost_log (see V10__add_x_cost_log.sql), for both
 * providers, so x.provider=apify and x.provider=api can be compared on real numbers.
 *
 * <p>Prices come from application.properties (x.apify.cost.* / x.api.cost.*), giving
 * {@code estimated_usd}. For Apify runs the charge Apify itself reports for the run is also stored
 * ({@code charged_usd}) when it can be read, along with the run id for reconciling in the console.
 * Logging never throws: a cost-log failure must not stop collection.
 */
final class XCostTracker {

    static final String PROVIDER_APIFY = "apify";
    static final String PROVIDER_X_API = "x_api";

    private XCostTracker() {
    }

    private static double price(String key, double defaultValue) {
        String value = AppProperties.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** One run of apidojo/twitter-scraper-lite with a single search query (event-priced). */
    static void recordApifyScanRun(String operation, String entity, ApifyClient.RunResult run) {
        double perQuery = price("x.apify.cost.per_query_usd", 0.016);
        int included = (int) price("x.apify.cost.included_items_per_query", 40);
        double perItem = price("x.apify.cost.per_item_usd", 0.0004);
        int items = run.items().size();
        double estimated = perQuery + Math.max(0, items - included) * perItem;
        record(PROVIDER_APIFY, operation, entity, 1, items, 0, estimated, chargedUsd(run.run(), items), run.runId(), null);
    }

    /** One run of apidojo/tweet-scraper (priced per returned tweet). */
    static void recordApifyBackfillRun(String entity, ApifyClient.RunResult run) {
        int items = run.items().size();
        double estimated = items * price("x.apify.cost.backfill_per_item_usd", 0.0004);
        record(PROVIDER_APIFY, "backfill", entity, 1, items, 0, estimated, chargedUsd(run.run(), items), run.runId(), null);
    }

    /** One successful official X API v2 request that returned {@code posts} posts and {@code users} users. */
    static void recordXApiRequest(String operation, String entity, int posts, int users) {
        double estimated = posts * price("x.api.cost.per_post_read_usd", 0.005)
                + users * price("x.api.cost.per_user_read_usd", 0.0);
        record(PROVIDER_X_API, operation, entity, 1, posts, users, estimated, null, null, null);
    }

    /**
     * Reads what Apify charged for a run: for pay-per-event Actors, sum of chargedEventCounts x each
     * event's price from the run's pricingInfo; for pay-per-result Actors, the dataset item count x
     * pricePerUnitUsd. Returns null when the run object doesn't carry enough to be sure (e.g. prices
     * that differ by plan tier), in which case only estimated_usd is meaningful.
     */
    static Double chargedUsd(JsonObject run, int itemCount) {
        try {
            if (run == null || !run.has("pricingInfo") || !run.get("pricingInfo").isJsonObject()) return null;
            JsonObject pricing = run.getAsJsonObject("pricingInfo");
            String model = pricing.has("pricingModel") ? pricing.get("pricingModel").getAsString() : "";

            if (model.equals("PAY_PER_EVENT")) {
                if (!run.has("chargedEventCounts") || !run.get("chargedEventCounts").isJsonObject()) return null;
                JsonObject events = pricing.getAsJsonObject("pricingPerEvent").getAsJsonObject("actorChargeEvents");
                double total = 0;
                for (Map.Entry<String, JsonElement> e : run.getAsJsonObject("chargedEventCounts").entrySet()) {
                    long count = e.getValue().getAsLong();
                    if (count == 0) continue;
                    if (!events.has(e.getKey())) return null;
                    Double unit = unitPrice(events.getAsJsonObject(e.getKey()), "eventPriceUsd",
                            "eventTieredPricingUsd", "tieredEventPriceUsd");
                    if (unit == null) return null;
                    total += count * unit;
                }
                return total;
            }
            if (model.equals("PRICE_PER_DATASET_ITEM")) {
                // Billed per item written to the run's default dataset, i.e. the items we read back.
                Double unit = unitPrice(pricing, "pricePerUnitUsd", "tieredPricing", "tieredPricePerUnitUsd");
                return unit == null ? null : itemCount * unit;
            }
        } catch (Exception e) {
            // Unexpected run shape - fall back to estimated_usd only.
        }
        return null;
    }

    // A flat price field, or a tier map whose tiers all share one price (otherwise ambiguous -> null).
    private static Double unitPrice(JsonObject obj, String flatKey, String tierMapKey, String tierPriceKey) {
        if (obj.has(flatKey) && !obj.get(flatKey).isJsonNull()) {
            return obj.get(flatKey).getAsDouble();
        }
        if (!obj.has(tierMapKey) || !obj.get(tierMapKey).isJsonObject()) return null;
        Double price = null;
        for (Map.Entry<String, JsonElement> tier : obj.getAsJsonObject(tierMapKey).entrySet()) {
            if (tier.getKey().equals("FREE")) continue; // API use needs a paid plan anyway
            double p = tier.getValue().getAsJsonObject().get(tierPriceKey).getAsDouble();
            if (price != null && Math.abs(price - p) > 1e-12) return null;
            price = p;
        }
        return price;
    }

    private static void record(String provider, String operation, String entity, int queries, int items, int users,
                               double estimatedUsd, Double chargedUsd, String runId, String details) {
        String sql = "INSERT INTO x_cost_log (provider, operation, entity, queries, items, users, estimated_usd, "
                + "charged_usd, apify_run_id, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, provider);
            ps.setString(2, operation);
            if (entity != null) ps.setString(3, entity); else ps.setNull(3, Types.VARCHAR);
            ps.setInt(4, queries);
            ps.setInt(5, items);
            ps.setInt(6, users);
            ps.setBigDecimal(7, BigDecimal.valueOf(estimatedUsd));
            if (chargedUsd != null) ps.setBigDecimal(8, BigDecimal.valueOf(chargedUsd)); else ps.setNull(8, Types.NUMERIC);
            if (runId != null) ps.setString(9, runId); else ps.setNull(9, Types.VARCHAR);
            if (details != null) ps.setString(10, details); else ps.setNull(10, Types.VARCHAR);
            ps.executeUpdate();
            System.out.printf("[X cost] %s/%s %s: %d item(s), est $%.4f%s%n", provider, operation,
                    entity != null ? "'" + entity + "'" : "-", items, estimatedUsd,
                    chargedUsd != null ? String.format(", charged $%.4f", chargedUsd) : "");
        } catch (Exception e) {
            System.err.println("[X cost] Failed to record cost: " + e.getMessage());
        }
    }
}
