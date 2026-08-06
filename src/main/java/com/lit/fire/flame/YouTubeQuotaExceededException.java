package com.lit.fire.flame;

/**
 * Thrown by YouTubeService when the YouTube Data API reports its quota (or daily limit) as
 * exceeded, as opposed to other 403s (e.g. comments disabled on a specific video) or a 304 (no new
 * comments). Callers use this to switch the rest of the current run to the Apify fallback instead
 * of continuing to burn already-exhausted quota on every remaining video.
 */
public class YouTubeQuotaExceededException extends RuntimeException {
    public YouTubeQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
