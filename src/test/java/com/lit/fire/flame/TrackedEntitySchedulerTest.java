package com.lit.fire.flame;

import junit.framework.TestCase;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static com.lit.fire.flame.TrackedEntityScheduler.PollResult;

public class TrackedEntitySchedulerTest extends TestCase {

    private static final long H = 3600;
    // Defaults from application.properties: base 4h, max 24h, busy at >= 10 new items,
    // Monday-Thursday polled every 24h.
    private final TrackedEntityScheduler scheduler = new TrackedEntityScheduler("test", 0);

    private static ZonedDateTime ist(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(TrackedReleaseSource.IST);
    }

    public void testQuietPollsBackOffUpToMax() {
        assertEquals(8 * H, scheduler.nextInterval(4 * H, PollResult.of(0)));
        assertEquals(16 * H, scheduler.nextInterval(8 * H, PollResult.of(9)));
        assertEquals(24 * H, scheduler.nextInterval(16 * H, PollResult.of(3)));
        assertEquals(24 * H, scheduler.nextInterval(24 * H, PollResult.of(0)));
    }

    public void testBusyPollResetsToBase() {
        assertEquals(4 * H, scheduler.nextInterval(24 * H, PollResult.of(10)));
        assertEquals(4 * H, scheduler.nextInterval(4 * H, PollResult.of(500)));
    }

    public void testCappedPollBacksOff() {
        assertEquals(8 * H, scheduler.nextInterval(4 * H, PollResult.of(1000, true)));
    }

    public void testWeekendKeepsAdaptiveInterval() {
        // 2026-11-21 is a Saturday.
        assertEquals(4 * H, scheduler.nextDelay(4 * H, null, ist("2026-11-21T10:00")));
    }

    public void testMondayToThursdayPollsDaily() {
        // Tuesday 10:00 -> Wednesday 10:00.
        assertEquals(24 * H, scheduler.nextDelay(4 * H, null, ist("2026-11-24T10:00")));
    }

    public void testThursdayPollStopsAtFridayMidnight() {
        // Thursday 10:00 -> Friday 00:00 rather than Friday 10:00.
        assertEquals(14 * H, scheduler.nextDelay(4 * H, null, ist("2026-11-26T10:00")));
    }

    public void testReleaseDayIsAdaptive() {
        LocalDate wednesdayRelease = LocalDate.parse("2026-11-25");
        assertEquals(4 * H, scheduler.nextDelay(4 * H, wednesdayRelease, ist("2026-11-25T10:00")));
        // The day before it waits only until release-day midnight.
        assertEquals(14 * H, scheduler.nextDelay(4 * H, wednesdayRelease, ist("2026-11-24T10:00")));
    }
}
