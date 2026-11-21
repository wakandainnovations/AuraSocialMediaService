package com.lit.fire.flame;

import junit.framework.TestCase;

import java.util.List;

public class XApifyServiceTest extends TestCase {

    // A tweet id whose snowflake timestamp is the given number of hours after an arbitrary base.
    private static String idAtHour(long hour) {
        long ms = 1_760_000_000_000L + hour * 3_600_000L;
        return String.valueOf((ms - 1288834974657L) << 22);
    }

    private static XApifyService.Member member(String entity, String sinceId, String... keywords) {
        return new XApifyService.Member(entity, List.of(keywords), "MOVIE", sinceId);
    }

    public void testMentionsAnyMatchesHashtagsAndMultiWordKeywords() {
        assertTrue(XApifyService.mentionsAny("Loved #Kantara today", List.of("Kantara")));
        assertTrue(XApifyService.mentionsAny("Loved Kantara today", List.of("#kantara")));
        assertTrue(XApifyService.mentionsAny("the movie Toxic was great", List.of("Toxic Movie")));
        assertFalse(XApifyService.mentionsAny("Toxic people", List.of("Toxic Movie", "KGF")));
        assertFalse(XApifyService.mentionsAny(null, List.of("KGF")));
    }

    public void testPacksMembersWithCloseSinceIdsTogether() {
        List<List<XApifyService.Member>> groups = XApifyService.packMembers(List.of(
                member("A", idAtHour(20), "Alpha"),
                member("B", idAtHour(18), "Bravo"),
                member("C", idAtHour(2), "Charlie")));
        // A and B are 2h apart (within 6h); C is 18h older than A and goes on its own.
        assertEquals(2, groups.size());
        assertEquals(List.of("A", "B"), groups.get(0).stream().map(XApifyService.Member::entity).toList());
        assertEquals(List.of("C"), groups.get(1).stream().map(XApifyService.Member::entity).toList());
    }

    public void testSplitsGroupsAtQueryLengthLimit() {
        String longKeyword = "x".repeat(300);
        List<List<XApifyService.Member>> groups = XApifyService.packMembers(List.of(
                member("A", idAtHour(10), longKeyword + "a"),
                member("B", idAtHour(10), longKeyword + "b")));
        assertEquals(2, groups.size());
    }
}
