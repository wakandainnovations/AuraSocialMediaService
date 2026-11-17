package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one recurring poll per actively tracked entity on a scanner's scheduler (used by the
 * per-entity scheduled scanners XService, XApifyService and YouTubeMain). The tracked list changes on its own
 * (movies enter/leave their ±7-day window, admins exclude movies or edit keywords), so each
 * {@link #sync} reconciles against {@link TrackedReleaseSource#loadInputQueries()}: new entities
 * are scheduled, entities no longer active are cancelled, and an entity whose keywords changed is
 * rescheduled. An entity already scheduled with the same keywords is left alone, so calling sync
 * repeatedly (Main re-invokes scan() hourly) never stacks duplicate polls.
 */
class TrackedEntityScheduler {

    interface EntityTask {
        void run(String entity, List<String> keywords, String category) throws Exception;
    }

    private record Scheduled(List<String> keywords, ScheduledFuture<?> future) {
    }

    private final String label;
    private final long periodSeconds;
    private final long maxInitialDelaySeconds;
    private final Map<String, Scheduled> scheduled = new HashMap<>();

    TrackedEntityScheduler(String label, long periodSeconds, long maxInitialDelaySeconds) {
        this.label = label;
        this.periodSeconds = periodSeconds;
        this.maxInitialDelaySeconds = maxInitialDelaySeconds;
    }

    synchronized void sync(ScheduledExecutorService scheduler, EntityTask task) {
        Map<String, List<JsonObject>> byEntity = new LinkedHashMap<>();
        for (JsonObject q : TrackedReleaseSource.loadInputQueries()) {
            byEntity.computeIfAbsent(q.get("entity").getAsString(), k -> new ArrayList<>()).add(q);
        }

        // Cancel entities that are no longer active (excluded, window ended) or whose keywords changed.
        scheduled.entrySet().removeIf(e -> {
            List<JsonObject> current = byEntity.get(e.getKey());
            boolean keep = current != null && keywordsOf(current).equals(e.getValue().keywords());
            if (!keep) {
                e.getValue().future().cancel(false);
                System.out.println("[" + label + "] Stopped polling '" + e.getKey() + "'"
                        + (current == null ? " (no longer tracked)." : " (keywords changed; rescheduling)."));
            }
            return !keep;
        });

        for (Map.Entry<String, List<JsonObject>> entry : byEntity.entrySet()) {
            String entity = entry.getKey();
            if (scheduled.containsKey(entity)) continue;
            List<String> keywords = keywordsOf(entry.getValue());
            if (keywords.isEmpty()) continue;
            String category = entry.getValue().get(0).get("category").getAsString();
            long initialDelay = ThreadLocalRandom.current().nextLong(0, maxInitialDelaySeconds + 1);
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                // Re-check right before spending API quota: an admin exclusion takes effect at once,
                // not only at the next sync.
                if (!TrackedReleaseSource.isActive(entity)) return;
                try {
                    System.out.println("\n[" + label + "] Polling entity: " + entity);
                    task.run(entity, keywords, category);
                } catch (Exception e) {
                    System.err.println("[" + label + "] Poll failed for " + entity + ": " + e.getMessage());
                }
            }, initialDelay, periodSeconds, TimeUnit.SECONDS);
            scheduled.put(entity, new Scheduled(keywords, future));
            System.out.println("[" + label + "] Queued '" + entity + "' " + keywords + " (initial delay " + initialDelay + "s).");
        }
    }

    private static List<String> keywordsOf(List<JsonObject> queries) {
        List<String> out = new ArrayList<>();
        for (JsonObject q : queries) {
            String k = q.get("keyword").getAsString().trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }
}
