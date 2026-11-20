package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final long DEFAULT_SUCCESS_DELAY_MS = 60 * 60 * 1000; // 1 hour
    // Apify-billed scanners (Instagram/Reddit) additionally space out per-entity calls by 1 hour
    // (see InstagramApifyService/RedditApifyService), so a single scan() pass over ~8 entities
    // already takes ~7-8 hours; waiting another 12 hours after it completes keeps full-cycle cost
    // down (~19-20h cadence) instead of restarting an already-hours-long pass every hour.
    private static final long APIFY_SUCCESS_DELAY_MS = 12 * 60 * 60 * 1000; // 12 hours
    // TMDB release discovery (ReleaseDiscoveryService, writes the weekly release file) - release
    // dates move slowly, twice a day is plenty.
    private static final long DISCOVERY_SUCCESS_DELAY_MS = 12 * 60 * 60 * 1000; // 12 hours

    private static class ScannableService implements Runnable {
        private final SocialMediaScanner scanner;
        private final String name;
        private final long successDelayMs;
        private long nextScanTime;

        public ScannableService(SocialMediaScanner scanner, String name) {
            this(scanner, name, DEFAULT_SUCCESS_DELAY_MS);
        }

        public ScannableService(SocialMediaScanner scanner, String name, long successDelayMs) {
            this.scanner = scanner;
            this.name = name;
            this.successDelayMs = successDelayMs;
            this.nextScanTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            while (true) {
                long sleepTime = nextScanTime - System.currentTimeMillis();
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                try {
                    System.out.println("Scanning " + name + "...");
                    scanner.scan();
                    System.out.println("Successfully scanned " + name + ".");
                    nextScanTime = System.currentTimeMillis() + successDelayMs;
                } catch (Exception e) {
                    System.err.println("An error occurred during " + name + " scanning: " + e.getMessage());
                    e.printStackTrace();
                    nextScanTime = System.currentTimeMillis() + 6 * 60 * 60 * 1000; // 6 hours
                }
            }
        }
    }

    // x.provider picks the X/Twitter source: "apify" (default, XApifyService - Apify's tweet scraper,
    // much cheaper) or "api" (XService - the official X API v2, billed per post read). Only the
    // chosen scanner is created, so the other one makes no calls at all.
    private static SocialMediaScanner createXScanner() {
        String provider = AppProperties.getProperty("x.provider");
        provider = provider == null ? "apify" : provider.trim().toLowerCase();
        switch (provider) {
            case "api":
                System.out.println("X provider: official X API v2 (XService).");
                return new XService();
            case "apify":
                System.out.println("X provider: Apify tweet scraper (XApifyService).");
                return new XApifyService();
            default:
                System.err.println("Unknown x.provider '" + provider + "'; using Apify (XApifyService).");
                return new XApifyService();
        }
    }

    public static void main(String[] args) {
        // Write this week's release file (releases/releases-YYYY-Www.json). The service doesn't
        // load it into tracked_releases - the standalone scripts/release_file_listener.py does -
        // so scanners work off whatever is in the table. A failure (e.g. TMDB down / no
        // credential) isn't fatal; the scheduled discovery below retries.
        ReleaseDiscoveryService discovery = new ReleaseDiscoveryService();
        try {
            discovery.scan();
        } catch (Exception e) {
            System.err.println("Initial release discovery failed: " + e.getMessage());
        }

        // Same for the relevance check: movies whose X posts already look on-topic are cleared for
        // Instagram/Reddit before those scanners' first pass (they pass only every ~12h), instead of
        // being skipped as "awaiting relevance check" until the next one.
        RelevanceCheckService relevance = new RelevanceCheckService();
        try {
            relevance.scan();
        } catch (Exception e) {
            System.err.println("Initial relevance check failed: " + e.getMessage());
        }

        List<ScannableService> services = new ArrayList<>();
        ScannableService discoveryService = new ScannableService(discovery, "Release discovery", DISCOVERY_SUCCESS_DELAY_MS);
        discoveryService.nextScanTime = System.currentTimeMillis() + DISCOVERY_SUCCESS_DELAY_MS;
        services.add(discoveryService);
        // Hourly relevance check: auto-excludes movies whose keywords mostly match unrelated posts,
        // and clears movies for the paid Apify scanners once their cheap X posts look on-topic.
        ScannableService relevanceService = new ScannableService(relevance, "Relevance check");
        relevanceService.nextScanTime = System.currentTimeMillis() + DEFAULT_SUCCESS_DELAY_MS;
        services.add(relevanceService);
        // Instagram/Reddit are collected via Apify Actors (InstagramApifyService/RedditApifyService),
        // not the native Graph API / Reddit OAuth clients (InstagramService/RedditAuthClientWithSearch).
        services.add(new ScannableService(new InstagramApifyService(), "Instagram", APIFY_SUCCESS_DELAY_MS));
        services.add(new ScannableService(new RedditApifyService(), "Reddit", APIFY_SUCCESS_DELAY_MS));
        services.add(new ScannableService(createXScanner(), "X"));
        services.add(new ScannableService(new YouTubeMain(), "YouTube"));

        for (ScannableService service : services) {
            new Thread(service).start();
        }
    }
}
