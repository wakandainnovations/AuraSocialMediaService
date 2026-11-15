package com.lit.fire.flame;

import junit.framework.TestCase;

import java.util.Set;

public class RelevanceCheckServiceTest extends TestCase {

    private static RelevanceCheckService.Candidate movie(String keywords, String language, String originalTitle, String cast) {
        return new RelevanceCheckService.Candidate(1, "M", keywords, language, originalTitle, cast, false, java.time.Instant.EPOCH);
    }

    public void testGenericTitlePostsAreNotMovieRelated() {
        Set<String> terms = RelevanceCheckService.contextTerms(movie("DarkChocolate,Dark Chocolate", "telugu", "Dark Chocolate", null));
        assertFalse(RelevanceCheckService.isMovieRelated("dark chocolate tastes like an ash tray", terms));
        assertFalse(RelevanceCheckService.isMovieRelated("vanilla fudge or dark chocolate covered raisins", terms));
        assertTrue(RelevanceCheckService.isMovieRelated("#DarkChocolate trailer looks great", terms));
    }

    public void testCastNamesCountAsContext() {
        // Real O.M: Operation Mole post with no film vocabulary at all.
        Set<String> terms = RelevanceCheckService.contextTerms(movie("OMOperationMole,O.M Operation Mole", "tamil", null,
                "Dhanush,Mammootty,Sai Pallavi"));
        assertTrue(RelevanceCheckService.isMovieRelated("#OM from 20.11.26 O.M. “Operation Mole” @dhanushkraja #Mammootty", terms));
    }

    public void testWordBoundariesAvoidFalseHits() {
        Set<String> terms = RelevanceCheckService.contextTerms(movie("Pulse", "tamil", null, null));
        assertFalse(RelevanceCheckService.isMovieRelated("my pulse after a hot shower and a bottle of water", terms));
        assertTrue(RelevanceCheckService.isMovieRelated("Pulse movie FDFS today", terms));
    }

    public void testNativeScriptTitleCounts() {
        Set<String> terms = RelevanceCheckService.contextTerms(movie("Titanic", "tamil", "டைட்டானிக்", null));
        assertTrue(RelevanceCheckService.isMovieRelated("டைட்டானிக் 🔥🔥", terms));
        assertFalse(RelevanceCheckService.isMovieRelated("Titanic wreck dive footage", terms));
    }
}
