package com.lit.fire.flame;

import com.google.gson.JsonObject;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   <li>A poll that hit the scraper's result cap ({@link PollResult#capped}, e.g. X's
 *       x.apify.max_items) also backs off: such polls are the most expensive ones.</li>
 *   <li>A busy poll resets it to the base interval.</li>
 *   <li>On {@code polling.daily.days} (default Monday-Thursday, IST) other than the movie's own
 *       release date, the movie is polled once every {@code polling.daily.interval.hours} (24h)
 *       instead - but never past the start (IST midnight) of its next non-daily day, so Friday and
 *       release-day polling begins on time.</li>
 *   <li>A failed poll keeps the interval and retries after the base interval.</li>
 *   <li>A skipped poll ({@link PollResult#SKIPPED}, e.g. awaiting the relevance check; costs
 *       nothing) re-checks after {@code polling.skip.retry.minutes} (default 60).</li>
 * </ul>
 *
 * <p>A scanner that can poll several entities in one paid call (XApifyService) syncs with a
 * {@link BatchTask}: when one entity's poll fires, every other entity due within
 * {@code polling.batch.window.minutes} is handed to the same call, and each entity's schedule then
 * continues from that shared poll.
 *
 * <p>The tracked list changes on its own (movies enter/leave their ±2-day window, admins exclude
 * movies or edit keywords), so each {@code sync} reconciles against
 * {@link TrackedReleaseSource#loadInputQueries()}: new entities are scheduled, entities no longer
 * active are cancelled, and an entity whose keywords changed is rescheduled (its interval resets).
 * An entity already scheduled with the same keywords is left alone, so calling sync repeatedly
 * (Main re-invokes scan() hourly) never stacks duplicate polls. Interval state is in memory, so a
 * restart puts every entity back on the base interval.
 */
class TrackedEntityScheduler {

    /** What one entity's poll found. */
    record PollResult(int newItems, boolean capped, boolean skipped, boolean failed) {
        /** The task deliberately didn't poll (nothing was spent). */
        static final PollResult SKIPPED = new PollResult(0, false, true, false);
        /** The poll failed; retried after the base interval. */
        static final PollResult FAILED = new PollResult(0, false, false, true);

        static PollResult of(int newItems) {
            return new PollResult(newItems, false, false, false);
        }

        static PollResult of(int newItems, boolean capped) {
            return new PollResult(newItems, capped, false, false);
        }
    }

    /** One entity handed to a poll. {@code previousPollAt} is null on the first poll since startup. */
    record Due(String entity, List<String> keywords, String category, Instant previousPollAt) {
    }

    interface EntityTask {
        /**
         * Polls one entity. {@code previousPollAt} is when this entity's last completed poll
         * started (null on the first poll since startup); tasks without their own cursor use it to
         * tell new from old.
         */
        PollResult run(String entity, List<String> keywords, String category, Instant previousPollAt) throws Exception;
    }

    interface BatchTask {
        /**
         * Polls the given entities (the first is the one whose timer fired) and returns a result for
         * each one it polled. An entity missing from the result keeps its own schedule.
         */
        Map<String, PollResult> run(List<Due> due) throws Exception;
    }

    private static final Set<DayOfWeek> DEFAULT_DAILY_DAYS =
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);

    private final String label;
    private final long maxInitialDelaySeconds;
    private final long baseIntervalSeconds;
    private final long maxIntervalSeconds;
    private final long skipRetrySeconds;
    private final int busyMinNewItems;
    private final Set<DayOfWeek> dailyDays;
    private final long dailyIntervalSeconds;
    private final long batchWindowSeconds;
    private final Map<String, EntityPoll> scheduled = new HashMap<>();

    TrackedEntityScheduler(String label, long maxInitialDelaySeconds) {
        this.label = label;
        this.maxInitialDelaySeconds = maxInitialDelaySeconds;
        this.baseIntervalSeconds = Math.max(1, AppProperties.getIntProperty("polling.base.interval.hours", 4)) * 3600L;
        this.maxIntervalSeconds = Math.max(baseIntervalSeconds,
                AppProperties.getIntProperty("polling.max.interval.hours", 24) * 3600L);
        this.skipRetrySeconds = Math.max(1, AppProperties.getIntProperty("polling.skip.retry.minutes", 60)) * 60L;
        this.busyMinNewItems = Math.max(1, AppProperties.getIntProperty("polling.busy.min_new_items", 10));
        this.dailyDays = parseDays(AppProperties.getProperty("polling.daily.days"));
        this.dailyIntervalSeconds = Math.max(1, AppProperties.getIntProperty("polling.daily.interval.hours", 24)) * 3600L;
        this.batchWindowSeconds = Math.max(0, AppProperties.getIntProperty("polling.batch.window.minutes", 120)) * 60L;
    }

    /** One entity per poll. */
    void sync(ScheduledExecutorService scheduler, EntityTask task) {
        sync(scheduler, due -> {
            Due d = due.get(0);
            return Map.of(d.entity(), task.run(d.entity(), d.keywords(), d.category(), d.previousPollAt()));
        }, false);
    }

    /** Several due entities per poll (see the class comment). */
    void sync(ScheduledExecutorService scheduler, BatchTask task) {
        sync(scheduler, task, true);
    }

    private synchronized void sync(ScheduledExecutorService scheduler, BatchTask task, boolean batching) {
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
            } else {
                e.getValue().releaseDate = releaseDateOf(current); // an admin may have moved it
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
            EntityPoll poll = new EntityPoll(scheduler, task, batching, entity, keywords, category, releaseDateOf(entry.getValue()));
            scheduled.put(entity, poll);
            poll.scheduleIn(initialDelay);
            System.out.println("[" + label + "] Queued '" + entity + "' " + keywords + " (initial delay " + initialDelay + "s).");
        }
    }

    /** Stops polling every entity (the next sync queues them again). */
    synchronized void cancelAll() {
        if (scheduled.isEmpty()) return;
        scheduled.values().forEach(EntityPoll::cancel);
        System.out.println("[" + label + "] Stopped polling " + scheduled.size() + " entit(ies).");
        scheduled.clear();
    }

    /** Next adaptive interval after a poll, given the current one. */
    long nextInterval(long currentSeconds, PollResult result) {
        if (result.newItems() >= busyMinNewItems && !result.capped()) return baseIntervalSeconds;
        return Math.min(maxIntervalSeconds, currentSeconds * 2);
    }

    /** Delay until the next poll, applying the daily-days rule to the adaptive interval. */
    long nextDelay(long adaptiveSeconds, LocalDate releaseDate, ZonedDateTime nowIst) {
        LocalDate today = nowIst.toLocalDate();
        if (!isDailyDay(today, releaseDate)) return adaptiveSeconds;
        // Never sleep past the start of the next day that isn't a daily day (within 7 days).
        LocalDate open = today.plusDays(1);
        while (isDailyDay(open, releaseDate) && open.isBefore(today.plusDays(8))) open = open.plusDays(1);
        long untilOpen = Duration.between(nowIst, open.atStartOfDay(nowIst.getZone())).getSeconds();
        return Math.max(60, Math.min(dailyIntervalSeconds, untilOpen));
    }

    private boolean isDailyDay(LocalDate date, LocalDate releaseDate) {
        return dailyDays.contains(date.getDayOfWeek()) && !date.equals(releaseDate);
    }

    private final class EntityPoll {
        final ScheduledExecutorService scheduler;
        final BatchTask task;
        final boolean batching;
        final String entity;
        final List<String> keywords;
        final String category;
        volatile LocalDate releaseDate;
        // Below: guarded by TrackedEntityScheduler.this.
        long intervalSeconds = baseIntervalSeconds;
        Instant previousPollAt;
        Instant nextRunAt;
        boolean running;
        boolean cancelled;
        ScheduledFuture<?> future;

        EntityPoll(ScheduledExecutorService scheduler, BatchTask task, boolean batching, String entity,
                   List<String> keywords, String category, LocalDate releaseDate) {
            this.scheduler = scheduler;
            this.task = task;
            this.batching = batching;
            this.entity = entity;
            this.keywords = keywords;
            this.category = category;
            this.releaseDate = releaseDate;
        }

        void scheduleIn(long delaySeconds) {
            synchronized (TrackedEntityScheduler.this) {
                if (cancelled) return;
                nextRunAt = Instant.now().plusSeconds(delaySeconds);
                try {
                    future = scheduler.schedule(this::fire, delaySeconds, TimeUnit.SECONDS);
                } catch (RejectedExecutionException e) {
                    // Scheduler shut down (e.g. YouTube quota exhausted); this entity stops polling.
                    cancelled = true;
                }
            }
        }

        void cancel() {
            synchronized (TrackedEntityScheduler.this) {
                cancelled = true;
                if (future != null) future.cancel(false);
            }
        }

        private void fire() {
            Instant started = Instant.now();
            // Claim this entity plus (batching) every other one due within the window.
            List<EntityPoll> batch = new ArrayList<>();
            synchronized (TrackedEntityScheduler.this) {
                if (cancelled || running) return; // already polled as part of another batch
                running = true;
                batch.add(this);
                if (batching && batchWindowSeconds > 0) {
                    Instant horizon = started.plusSeconds(batchWindowSeconds);
                    for (EntityPoll other : scheduled.values()) {
                        if (other == this || other.running || other.cancelled) continue;
                        if (other.nextRunAt == null || other.nextRunAt.isAfter(horizon)) continue;
                        other.running = true;
                        if (other.future != null) other.future.cancel(false);
                        batch.add(other);
                    }
                }
            }

            // Re-check right before spending API quota: an admin exclusion takes effect at once,
            // not only at the next sync (which cancels the poll).
            List<Due> due = new ArrayList<>();
            List<EntityPoll> polled = new ArrayList<>();
            for (EntityPoll p : batch) {
                if (!TrackedReleaseSource.isActive(p.entity)) {
                    p.finish(skipRetrySeconds);
                    continue;
                }
                due.add(new Due(p.entity, p.keywords, p.category, p.previousPollAt));
                polled.add(p);
            }
            if (polled.isEmpty()) return;
            if (polled.size() > 1) {
                System.out.println("\n[" + label + "] Polling " + polled.size() + " entities together: "
                        + due.stream().map(Due::entity).toList());
            } else {
                System.out.println("\n[" + label + "] Polling entity: " + polled.get(0).entity);
            }

            Map<String, PollResult> results;
            try {
                results = task.run(due);
            } catch (Exception e) {
                System.err.println("[" + label + "] Poll failed for " + due.stream().map(Due::entity).toList()
                        + " (retrying in " + hours(baseIntervalSeconds) + "): " + e.getMessage());
                for (EntityPoll p : polled) p.finish(baseIntervalSeconds);
                return;
            }

            ZonedDateTime nowIst = ZonedDateTime.now(TrackedReleaseSource.IST);
            for (EntityPoll p : polled) {
                PollResult r = results.get(p.entity);
                if (r == null) {
                    // Not polled after all: back on its own schedule (or base if it was this timer's own).
                    long remaining = p == this || p.nextRunAt == null ? baseIntervalSeconds
                            : Math.max(0, Duration.between(Instant.now(), p.nextRunAt).getSeconds());
                    p.finish(remaining);
                } else if (r.skipped()) {
                    p.finish(skipRetrySeconds);
                } else if (r.failed()) {
                    System.err.println("[" + label + "] Poll failed for " + p.entity + " (retrying in " + hours(baseIntervalSeconds) + ").");
                    p.finish(baseIntervalSeconds);
                } else {
                    p.previousPollAt = started;
                    p.intervalSeconds = nextInterval(p.intervalSeconds, r);
                    long delay = nextDelay(p.intervalSeconds, p.releaseDate, nowIst);
                    String why = r.capped() ? "hit the result cap" : r.newItems() >= busyMinNewItems ? "busy" : "quiet";
                    System.out.println("[" + label + "] '" + p.entity + "': " + r.newItems() + " new item(s) -> " + why
                            + ", next poll in " + hours(delay) + (delay != p.intervalSeconds ? " (weekday schedule)" : "") + ".");
                    p.finish(delay);
                }
            }
        }

        void finish(long delaySeconds) {
            synchronized (TrackedEntityScheduler.this) {
                running = false;
            }
            scheduleIn(delaySeconds);
        }
    }

    private static String hours(long seconds) {
        return seconds % 3600 == 0 ? (seconds / 3600) + "h" : (seconds / 3600) + "h" + (seconds % 3600 / 60) + "m";
    }

    private static Set<DayOfWeek> parseDays(String value) {
        if (value == null) return DEFAULT_DAILY_DAYS;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String d : value.split(",")) {
            if (!d.isBlank()) days.add(DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)));
        }
        return days;
    }

    private static LocalDate releaseDateOf(List<JsonObject> queries) {
        JsonObject q = queries.get(0);
        if (!q.has("release_date") || q.get("release_date").isJsonNull()) return null;
        return LocalDate.parse(q.get("release_date").getAsString());
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
