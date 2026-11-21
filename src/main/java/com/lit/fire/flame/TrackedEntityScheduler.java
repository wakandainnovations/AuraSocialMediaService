package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one adaptive poll per actively tracked entity on a scanner's scheduler. Every per-entity
 * scanner (XService, XApifyService, InstagramApifyService, RedditApifyService, YouTubeMain) uses
 * it, so all platforms poll on the same policy:
 *
 * <ul>
 *   <li>An entity starts at {@code polling.base.interval.hours} (default 4h).</li>
 *   <li>A quiet poll (fewer than {@code polling.busy.min_new_items} new items) doubles its
 *       interval, up to {@code polling.max.interval.hours} (default 24h). Paid scrapers charge per
 *       run whatever they find, so quiet movies are polled less.</li>
 *   <li>A busy poll resets it to the base interval.</li>
 *   <li>A failed poll keeps the interval and retries after the base interval.</li>
 *   <li>A skipped poll ({@link #SKIPPED}, e.g. awaiting the relevance check; costs nothing)
 *       re-checks after {@code polling.skip.retry.minutes} (default 60).</li>
 * </ul>
 *
 * <p>The tracked list changes on its own (movies enter/leave their ±7-day window, admins exclude
 * movies or edit keywords), so each {@link #sync} reconciles against
 * {@link TrackedReleaseSource#loadInputQueries()}: new entities are scheduled, entities no longer
 * active are cancelled, and an entity whose keywords changed is rescheduled (its interval resets).
 * An entity already scheduled with the same keywords is left alone, so calling sync repeatedly
 * (Main re-invokes scan() hourly) never stacks duplicate polls. Interval state is in memory, so a
 * restart puts every entity back on the base interval.
 */
class TrackedEntityScheduler {

    /** Returned by a task that deliberately didn't poll (nothing was spent). */
    static final int SKIPPED = -1;

    interface EntityTask {
        /**
         * Polls one entity and returns how many new items it found, or {@link #SKIPPED}.
         * {@code previousPollAt} is when this entity's last completed poll started (null on the
         * first poll since startup); tasks without their own cursor use it to tell new from old.
         */
        int run(String entity, List<String> keywords, String category, Instant previousPollAt) throws Exception;
    }

    private final String label;
    private final long maxInitialDelaySeconds;
    private final long baseIntervalSeconds;
    private final long maxIntervalSeconds;
    private final long skipRetrySeconds;
    private final int busyMinNewItems;
    private final Map<String, EntityPoll> scheduled = new HashMap<>();

    TrackedEntityScheduler(String label, long maxInitialDelaySeconds) {
        this.label = label;
        this.maxInitialDelaySeconds = maxInitialDelaySeconds;
        this.baseIntervalSeconds = Math.max(1, AppProperties.getIntProperty("polling.base.interval.hours", 4)) * 3600L;
        this.maxIntervalSeconds = Math.max(baseIntervalSeconds,
                AppProperties.getIntProperty("polling.max.interval.hours", 24) * 3600L);
        this.skipRetrySeconds = Math.max(1, AppProperties.getIntProperty("polling.skip.retry.minutes", 60)) * 60L;
        this.busyMinNewItems = Math.max(1, AppProperties.getIntProperty("polling.busy.min_new_items", 10));
    }

    synchronized void sync(ScheduledExecutorService scheduler, EntityTask task) {
        Map<String, List<JsonObject>> byEntity = new LinkedHashMap<>();
        for (JsonObject q : TrackedReleaseSource.loadInputQueries()) {
            byEntity.computeIfAbsent(q.get("entity").getAsString(), k -> new ArrayList<>()).add(q);
        }

        // Cancel entities that are no longer active (excluded, window ended) or whose keywords changed.
        scheduled.entrySet().removeIf(e -> {
            List<JsonObject> current = byEntity.get(e.getKey());
            boolean keep = current != null && keywordsOf(current).equals(e.getValue().keywords);
            if (!keep) {
                e.getValue().cancel();
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
            EntityPoll poll = new EntityPoll(scheduler, task, entity, keywords, category);
            scheduled.put(entity, poll);
            poll.scheduleIn(initialDelay);
            System.out.println("[" + label + "] Queued '" + entity + "' " + keywords + " (initial delay " + initialDelay
                    + "s, then every " + hours(baseIntervalSeconds) + "-" + hours(maxIntervalSeconds) + " depending on activity).");
        }
    }

    /** Next interval after a poll that found {@code newItems} new items, given the current one. */
    long nextInterval(long currentSeconds, int newItems) {
        if (newItems >= busyMinNewItems) return baseIntervalSeconds;
        return Math.min(maxIntervalSeconds, currentSeconds * 2);
    }

    private final class EntityPoll {
        final ScheduledExecutorService scheduler;
        final EntityTask task;
        final String entity;
        final List<String> keywords;
        final String category;
        long intervalSeconds = baseIntervalSeconds;
        Instant previousPollAt;
        private volatile boolean cancelled;
        private ScheduledFuture<?> future;

        EntityPoll(ScheduledExecutorService scheduler, EntityTask task, String entity, List<String> keywords, String category) {
            this.scheduler = scheduler;
            this.task = task;
            this.entity = entity;
            this.keywords = keywords;
            this.category = category;
        }

        synchronized void scheduleIn(long delaySeconds) {
            if (cancelled) return;
            try {
                future = scheduler.schedule(this::runOnce, delaySeconds, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                // Scheduler shut down (e.g. YouTube quota exhausted); this entity stops polling.
                cancelled = true;
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
        }

        private void runOnce() {
            if (cancelled) return;
            // Re-check right before spending API quota: an admin exclusion takes effect at once,
            // not only at the next sync (which cancels this poll).
            if (!TrackedReleaseSource.isActive(entity)) {
                scheduleIn(skipRetrySeconds);
                return;
            }
            Instant started = Instant.now();
            long next;
            try {
                System.out.println("\n[" + label + "] Polling entity: " + entity);
                int newItems = task.run(entity, keywords, category, previousPollAt);
                if (newItems == SKIPPED) {
                    next = skipRetrySeconds;
                } else {
                    previousPollAt = started;
                    intervalSeconds = nextInterval(intervalSeconds, newItems);
                    next = intervalSeconds;
                    System.out.println("[" + label + "] '" + entity + "': " + newItems + " new item(s) -> "
                            + (newItems >= busyMinNewItems ? "busy" : "quiet") + ", next poll in " + hours(next) + ".");
                }
            } catch (Exception e) {
                next = baseIntervalSeconds;
                System.err.println("[" + label + "] Poll failed for " + entity + " (retrying in " + hours(next) + "): " + e.getMessage());
            }
            scheduleIn(next);
        }
    }

    private static String hours(long seconds) {
        return seconds % 3600 == 0 ? (seconds / 3600) + "h" : (seconds / 60) + "m";
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
