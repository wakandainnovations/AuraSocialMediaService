package com.lit.fire.flame;

import junit.framework.TestCase;

public class TrackedEntitySchedulerTest extends TestCase {

    private static final long H = 3600;
    // Defaults from application.properties: base 4h, max 24h, busy at >= 10 new items.
    private final TrackedEntityScheduler scheduler = new TrackedEntityScheduler("test", 0);

    public void testQuietPollsBackOffUpToMax() {
        assertEquals(8 * H, scheduler.nextInterval(4 * H, 0));
        assertEquals(16 * H, scheduler.nextInterval(8 * H, 9));
        assertEquals(24 * H, scheduler.nextInterval(16 * H, 3));
        assertEquals(24 * H, scheduler.nextInterval(24 * H, 0));
    }

    public void testBusyPollResetsToBase() {
        assertEquals(4 * H, scheduler.nextInterval(24 * H, 10));
        assertEquals(4 * H, scheduler.nextInterval(4 * H, 500));
    }
}
