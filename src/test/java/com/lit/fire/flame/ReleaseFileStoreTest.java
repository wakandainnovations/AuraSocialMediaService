package com.lit.fire.flame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

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
        ReleaseFileStore.write(file, "2026-W47", List.of(r), List.of(manual, broken));

        ReleaseFileStore.WeekFile read = ReleaseFileStore.read(file);
        assertEquals(List.of(r), read.tmdb());
        assertEquals(1, read.manual().size());
        assertEquals("Coolie", read.manual().get(0).entityName());
        assertEquals(List.of(manual, broken), read.manualRaw());
        assertEquals(1, read.errors().size());
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        assertEquals(file, ReleaseFileStore.latest(dir));
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
