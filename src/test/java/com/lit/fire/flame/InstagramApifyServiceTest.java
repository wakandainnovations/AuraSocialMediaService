package com.lit.fire.flame;

import junit.framework.TestCase;

import java.util.List;

public class InstagramApifyServiceTest extends TestCase {

    // Mirrors the Apify instagram-hashtag-scraper input validation pattern for each hashtag.
    private static final String ACTOR_HASHTAG_PATTERN =
            "^\\s*#?[^!?.,:;\\-+=*&%$#@/\\\\~^|<>()\\[\\]{}\"'`]+$";

    public void testStripsPunctuationTheActorRejects() {
        assertEquals(List.of("BachelorPartyDeux"),
                InstagramApifyService.normalizeHashtags(List.of("Bachelor Party D'eux")));
        assertEquals(List.of("SpiderMan", "MissionImpossible", "MrSmith", "WhatIf", "FastFurious"),
                InstagramApifyService.normalizeHashtags(List.of(
                        "Spider-Man", "Mission: Impossible", "Mr. Smith", "What If...?", "Fast & Furious")));
    }

    public void testEveryNormalizedHashtagPassesActorValidation() {
        List<String> tags = InstagramApifyService.normalizeHashtags(List.of(
                "Bachelor Party D'eux", "#Coolie", "K.G.F: Chapter 2", "Amélie", "கூலி", "RRR_2022", "(500) Days"));
        for (String tag : tags) {
            assertTrue("Rejected by actor: " + tag, tag.matches(ACTOR_HASHTAG_PATTERN));
        }
        assertTrue(tags.contains("Amélie"));
        assertTrue(tags.contains("RRR_2022"));
        assertTrue("Combining vowel signs must be kept", tags.contains("கூலி"));
    }

    public void testDedupesCaseInsensitivelyKeepingFirstSeenForm() {
        assertEquals(List.of("BachelorPartyDeux"),
                InstagramApifyService.normalizeHashtags(List.of(
                        "Bachelor Party D'eux", "bachelorpartydeux", "Bachelor Party Deux")));
    }

    public void testDropsKeywordsThatAreOnlyPunctuation() {
        assertEquals(List.of("Ayogya2"),
                InstagramApifyService.normalizeHashtags(List.of("Ayogya 2", "!!!", " ", "Ayogya2")));
    }
}
