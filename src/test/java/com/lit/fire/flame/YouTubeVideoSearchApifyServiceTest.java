package com.lit.fire.flame;

import junit.framework.TestCase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class YouTubeVideoSearchApifyServiceTest extends TestCase {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    public void testParsesIsoInstantAndDate() {
        assertEquals(Instant.parse("2026-11-20T08:30:00Z"),
                YouTubeVideoSearchApifyService.parseDate("2026-11-20T08:30:00Z", NOW));
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"),
                YouTubeVideoSearchApifyService.parseDate("2026-11-19", NOW));
    }

    public void testParsesRelativeText() {
        assertEquals(NOW.minus(5, ChronoUnit.DAYS), YouTubeVideoSearchApifyService.parseDate("5 days ago", NOW));
        assertEquals(NOW.minus(1, ChronoUnit.HOURS), YouTubeVideoSearchApifyService.parseDate("1 hour ago", NOW));
        assertEquals(NOW.minus(14, ChronoUnit.DAYS), YouTubeVideoSearchApifyService.parseDate("Streamed 2 weeks ago", NOW));
        assertEquals(NOW.minus(60, ChronoUnit.DAYS), YouTubeVideoSearchApifyService.parseDate("2 months ago", NOW));
    }

    public void testUnreadableDateIsNull() {
        assertNull(YouTubeVideoSearchApifyService.parseDate(null, NOW));
        assertNull(YouTubeVideoSearchApifyService.parseDate("", NOW));
        assertNull(YouTubeVideoSearchApifyService.parseDate("Premiered recently", NOW));
    }
}
