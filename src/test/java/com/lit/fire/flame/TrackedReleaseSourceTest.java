package com.lit.fire.flame;

import junit.framework.TestCase;

import java.util.List;

public class TrackedReleaseSourceTest extends TestCase {

    public void testMultiWordTitleGetsHashtagAndPhrase() {
        assertEquals("MrLucky,Mr. Lucky", TrackedReleaseSource.defaultKeywords("Mr. Lucky", "Mr. Lucky"));
    }

    public void testSingleWordTitleGetsOneKeyword() {
        assertEquals("Chandrahaasa", TrackedReleaseSource.defaultKeywords("Chandrahaasa", null));
    }

    public void testNonLatinOriginalTitleIsSkipped() {
        assertEquals("JanaNayagan,Jana Nayagan", TrackedReleaseSource.defaultKeywords("Jana Nayagan", "ஜன நாயகன்"));
    }

    public void testDistinctLatinOriginalTitleIsAdded() {
        assertEquals("Toxic,ToxicAFairyTale,Toxic A Fairy Tale",
                TrackedReleaseSource.defaultKeywords("Toxic", "Toxic: A Fairy Tale"));
    }

    public void testCommaInTitleNeverSplitsIntoTwoKeywords() {
        String kw = TrackedReleaseSource.defaultKeywords("Hello, World", null);
        assertEquals(List.of("HelloWorld", "Hello World"), TrackedReleaseSource.splitKeywords(kw));
    }

    public void testSplitKeywordsTrimsAndDropsBlanks() {
        assertEquals(List.of("a", "b c"), TrackedReleaseSource.splitKeywords(" a, ,b c ,"));
    }

    public void testIndianLanguageCheck() {
        java.util.Set<String> langs = new java.util.HashSet<>(TrackedReleaseSource.splitKeywords(ReleaseDiscoveryService.DEFAULT_INDIAN_LANGUAGES));
        assertTrue(TrackedReleaseSource.isIndianLanguage("Kannada ", langs));
        assertFalse(TrackedReleaseSource.isIndianLanguage("english", langs));
        assertFalse(TrackedReleaseSource.isIndianLanguage("fr", langs));
        assertFalse(TrackedReleaseSource.isIndianLanguage(null, langs));
    }

    public void testIndiaTheatricalDateUsesEarliestInTheatricalEntry() {
        com.google.gson.JsonObject movie = com.google.gson.JsonParser.parseString("{\"release_dates\":{\"results\":["
                + "{\"iso_3166_1\":\"US\",\"release_dates\":[{\"type\":3,\"release_date\":\"2026-10-01T00:00:00.000Z\"}]},"
                + "{\"iso_3166_1\":\"IN\",\"release_dates\":["
                + "{\"type\":1,\"release_date\":\"2026-09-01T00:00:00.000Z\"},"
                + "{\"type\":3,\"release_date\":\"2026-11-21T00:00:00.000Z\"},"
                + "{\"type\":2,\"release_date\":\"2026-11-20T00:00:00.000Z\"},"
                + "{\"type\":4,\"release_date\":\"2026-11-01T00:00:00.000Z\"}]}]}}").getAsJsonObject();
        assertEquals(java.time.LocalDate.of(2026, 11, 20), TmdbReleaseClient.indiaTheatricalDate(movie, java.time.LocalDate.of(2026, 11, 9)));
    }

    public void testNoIndiaTheatricalReleaseMeansNotTracked() {
        com.google.gson.JsonObject shortFilm = com.google.gson.JsonParser.parseString("{\"release_dates\":{\"results\":["
                + "{\"iso_3166_1\":\"IN\",\"release_dates\":[{\"type\":4,\"release_date\":\"2026-11-14T00:00:00.000Z\"}]}]}}").getAsJsonObject();
        assertNull(TmdbReleaseClient.indiaTheatricalDate(shortFilm, java.time.LocalDate.of(2026, 11, 9)));
    }

    public void testReReleaseIsDatedByCurrentRunNotOriginal() {
        // Gondhal on TMDB: original IN theatrical 2025-11-14, nationwide re-release 2026-11-13.
        com.google.gson.JsonObject movie = com.google.gson.JsonParser.parseString("{\"release_dates\":{\"results\":["
                + "{\"iso_3166_1\":\"IN\",\"release_dates\":["
                + "{\"type\":3,\"release_date\":\"2025-11-14T00:00:00.000Z\"},"
                + "{\"type\":2,\"release_date\":\"2026-10-09T00:00:00.000Z\"},"
                + "{\"type\":3,\"release_date\":\"2026-11-13T00:00:00.000Z\"}]}]}}").getAsJsonObject();
        java.time.LocalDate from = java.time.LocalDate.of(2026, 11, 9);
        assertEquals(java.time.LocalDate.of(2026, 11, 13), TmdbReleaseClient.indiaTheatricalDate(movie, from));
        // With nothing on/after the bound, fall back to the most recent past run.
        assertEquals(java.time.LocalDate.of(2026, 11, 13), TmdbReleaseClient.indiaTheatricalDate(movie, java.time.LocalDate.of(2027, 1, 1)));
    }

    public void testOnlySouthIndianLanguagesAreAutoTrackedByDefault() {
        java.util.Set<String> auto = new java.util.HashSet<>(TrackedReleaseSource.splitKeywords(ReleaseDiscoveryService.DEFAULT_AUTO_TRACK_LANGUAGES));
        for (String l : new String[]{"tamil", "telugu", "kannada", "malayalam"}) {
            assertTrue(l, TrackedReleaseSource.isIndianLanguage(l, auto));
        }
        for (String l : new String[]{"hindi", "marathi", "bengali", "punjabi"}) {
            assertFalse(l, TrackedReleaseSource.isIndianLanguage(l, auto));
        }
    }
}
