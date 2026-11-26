package com.lit.fire.flame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReleaseFileStoreTest extends TestCase {

    public void testWeekNameUsesIsoWeekYear() {
        assertEquals("2026-W47", ReleaseFileStore.weekName(LocalDate.of(2026, 11, 20)));
        // 2027-01-01 is a Friday, still ISO week 53 of 2026.
        assertEquals("2026-W53", ReleaseFileStore.weekName(LocalDate.of(2027, 1, 1)));
    }

    public void testWriteThenReadRoundTripsAndKeepsManualAsWritten() throws Exception {
        Path dir = Files.createTempDirectory("releases");
        Path file = ReleaseFileStore.weekFile(dir, LocalDate.of(2026, 11, 20));
        TmdbReleaseClient.Release r = new TmdbReleaseClient.Release(42, "Mr. Lucky", "Mr. Lucky", "kannada",
                LocalDate.of(2026, 11, 21), 3.5, "/p.jpg", List.of("Actor One", "Dir Two"),
                new TmdbReleaseClient.Details("Action,Drama", 140, "Dir Two", "Some Co"));
        JsonObject manual = JsonParser.parseString(
                "{\"title\":\"Coolie\",\"release_date\":\"2026-12-05\",\"my_note\":\"kept\"}").getAsJsonObject();
        JsonObject broken = JsonParser.parseString("{\"title\":\"Typo\",\"release_date\":\"soon\"}").getAsJsonObject();
        List<ReleaseFileStore.Removed> removed = List.of(new ReleaseFileStore.Removed(7, "Mysaa"));
        ReleaseFileStore.write(file, "2026-W47", List.of(r), List.of(manual, broken), removed);

        ReleaseFileStore.WeekFile read = ReleaseFileStore.read(file);
        assertEquals(List.of(r), read.tmdb());
        assertEquals(1, read.manual().size());
        assertEquals("Coolie", read.manual().get(0).entityName());
        assertEquals(List.of(manual, broken), read.manualRaw());
        assertEquals(removed, read.removed());
        assertEquals(1, read.errors().size());
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        assertEquals(file, ReleaseFileStore.latest(dir));
    }

    public void testFileWithoutRemovedListReadsAsEmpty() throws Exception {
        Path file = Files.createTempDirectory("releases").resolve("releases-2026-W47.json");
        Files.writeString(file, "{\"tmdb\":[],\"manual\":[]}");
        assertEquals(List.of(), ReleaseFileStore.read(file).removed());
    }

    private static TmdbReleaseClient.Release release(int id, String title, LocalDate date) {
        return new TmdbReleaseClient.Release(id, title, title, "telugu", date, null, null, List.of(), null);
    }

    public void testRefreshReportsUndatedMoviesAsRemovedButNotFailures() {
        LocalDate day = LocalDate.of(2026, 11, 27);
        TmdbReleaseClient.Release postponedOld = release(2, "Postponed", day);
        TmdbReleaseClient.Release flakyOld = release(4, "Flaky", day);
        TmdbReleaseClient.Release moved = release(1, "Moved", day.plusDays(7));
        Map<Integer, String> known = new LinkedHashMap<>();
        known.put(1, "Moved");
        known.put(2, "Postponed");
        known.put(3, "Discovered");
        known.put(4, "Flaky");
        Map<Integer, TmdbReleaseClient.Release> out = new LinkedHashMap<>();
        out.put(3, release(3, "Discovered", day));

        ReleaseDiscoveryService.Refresh refresh = ReleaseDiscoveryService.refreshKnown(known, out,
                Map.of(2, postponedOld, 4, flakyOld), id -> {
                    if (id == 1) return moved;
                    if (id == 2) return null;  // TMDB has no India theatrical date for it anymore
                    if (id == 4) throw new Exception("TMDB HTTP 503");
                    throw new AssertionError("discovered movie re-fetched: " + id);
                });

        assertEquals(1, refresh.refreshed());
        assertEquals(1, refresh.failed());
        assertEquals(List.of(new ReleaseFileStore.Removed(2, "Postponed")), refresh.removed());
        // The un-dated movie leaves the tmdb list; a failed fetch keeps the last known entry.
        assertEquals(Map.of(1, moved, 3, release(3, "Discovered", day), 4, flakyOld), out);
    }

    public void testLatestPicksNewestWeek() throws Exception {
        Path dir = Files.createTempDirectory("releases");
        for (String n : List.of("releases-2026-W52.json", "releases-2027-W01.json", "releases-2027-W02.json.tmp", "x.json")) {
            Files.writeString(dir.resolve(n), "{}");
        }
        assertEquals("releases-2027-W01.json", ReleaseFileStore.latest(dir).getFileName().toString());
    }

    public void testNewWeekCarriesOverOnlyStillTrackedManualEntries() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        JsonObject ended = JsonParser.parseString("{\"title\":\"Old\",\"release_date\":\"2026-11-10\"}").getAsJsonObject();
        JsonObject lastDay = JsonParser.parseString("{\"title\":\"Edge\",\"release_date\":\"2026-11-16\"}").getAsJsonObject();
        JsonObject extended = JsonParser.parseString(
                "{\"title\":\"Long\",\"release_date\":\"2026-11-01\",\"track_until\":\"2026-11-30\"}").getAsJsonObject();
        JsonObject broken = JsonParser.parseString("{\"title\":\"Typo\"}").getAsJsonObject();
        assertEquals(List.of(lastDay, extended, broken),
                ReleaseDiscoveryService.stillTracked(List.of(ended, lastDay, extended, broken), today, 7));
    }
}
