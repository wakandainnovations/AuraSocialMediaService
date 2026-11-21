package com.lit.fire.flame;

import junit.framework.TestCase;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

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

    public void testSplitsGroupsWhenReReadsCostMoreThanAQuery() {
        // Re-reads counted from a since_id: A has 10 tweets saved after B's, 50 after C's.
        XApifyService.ReReads reReads = (m, from) -> {
            if (!m.entity().equals("A")) return 0;
            return from.equals(idAtHour(18)) ? 10 : 50;
        };
        List<List<XApifyService.Member>> groups = XApifyService.packMembers(List.of(
                member("A", idAtHour(20), "Alpha"),
                member("B", idAtHour(18), "Bravo"),
                member("C", idAtHour(2), "Charlie")), reReads, 40);
        // B costs 10 re-reads (< 40) and joins A; C would cost 50 and goes on its own.
        assertEquals(2, groups.size());
        assertEquals(List.of("A", "B"), groups.get(0).stream().map(XApifyService.Member::entity).toList());
        assertEquals(List.of("C"), groups.get(1).stream().map(XApifyService.Member::entity).toList());
    }

    public void testQuietMoviesShareEvenWhenFarApart() {
        // No re-reads (the newer movie saved nothing in between), so an 18h gap doesn't matter.
        List<List<XApifyService.Member>> groups = XApifyService.packMembers(List.of(
                member("A", idAtHour(20), "Alpha"),
                member("C", idAtHour(2), "Charlie")), (m, from) -> 0, 40);
        assertEquals(1, groups.size());
    }

    public void testSplitsGroupsAtQueryLengthLimit() {
        String longKeyword = "x".repeat(300);
        List<List<XApifyService.Member>> groups = XApifyService.packMembers(List.of(
                member("A", idAtHour(10), longKeyword + "a"),
                member("B", idAtHour(10), longKeyword + "b")), (m, from) -> 0, 40);
        assertEquals(2, groups.size());
    }

    private static Map<String, String> due(long id) {
        return Map.of("x_id", String.valueOf(id), "composite_id", id + "_E", "entity", "E");
    }

    public void testRefreshMergesDueTweetsWithFewSavedTweetsBetween() {
        // Saved ids 100..199; due 100, 130, 160: 29 non-due tweets between each pair (< 40).
        long[] saved = LongStream.range(100, 200).toArray();
        List<XApifyService.Slice> slices = XApifyService.slices(List.of(due(160), due(100), due(130)), saved, 40, 200);
        assertEquals(1, slices.size());
        assertEquals(3, slices.get(0).rows().size());
        assertEquals(61, slices.get(0).expectedItems()); // ids 100..160
    }

    public void testRefreshSplitsWhenTooManySavedTweetsBetween() {
        // 59 non-due tweets between 100 and 160: re-reading them costs more than a new query.
        long[] saved = LongStream.range(100, 200).toArray();
        List<XApifyService.Slice> slices = XApifyService.slices(List.of(due(100), due(160)), saved, 40, 200);
        assertEquals(2, slices.size());
        assertEquals(1, slices.get(0).expectedItems());
    }

    public void testRefreshSplitsAtMaxItemsPerQuery() {
        long[] saved = LongStream.range(0, 1000).toArray();
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (long id = 0; id < 1000; id += 20) rows.add(due(id)); // 19 between each, so all would merge
        List<XApifyService.Slice> slices = XApifyService.slices(rows, saved, 40, 200);
        assertTrue(slices.size() > 1);
        for (XApifyService.Slice slice : slices) assertTrue(slice.expectedItems() + 20 <= 200);
    }

    public void testQueryParenthesisesMultiWordKeywords() {
        assertEquals("(MinnumOarIravu OR (Minnum Oar Iravu) OR Bhogi)",
                XApifyService.coreQuery(List.of("MinnumOarIravu", "Minnum Oar Iravu", "Bhogi")));
        assertEquals("Minnum Oar Iravu", XApifyService.coreQuery(List.of("Minnum Oar Iravu")));
    }

    public void testLogErrorFindsTheScraperOutage() {
        String log = "2026-11-21T15:10:36.123Z ACTOR: Pulling container image\n"
                + "2026-11-21T15:10:40.456Z ERROR Following a major Twitter update on Nov 21, Search is temporarily unavailable.\n"
                + "2026-11-21T15:10:41.000Z Done.";
        assertEquals("Following a major Twitter update on Nov 21, Search is temporarily unavailable.",
                XApifyService.logError(log));
        assertNull(XApifyService.logError("2026-11-21T15:10:36Z INFO Scraped 0 tweets\n2026-11-21T15:10:37Z Done."));
        assertNull(XApifyService.logError(""));
    }
}
