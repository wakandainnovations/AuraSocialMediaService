package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static class ScannableService implements Runnable {
        private final SocialMediaScanner scanner;
        private final String name;
        private long nextScanTime;

        public ScannableService(SocialMediaScanner scanner, String name) {
            this.scanner = scanner;
            this.name = name;
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
                    nextScanTime = System.currentTimeMillis() + 60 * 60 * 1000; // 1 hour
                } catch (Exception e) {
                    System.err.println("An error occurred during " + name + " scanning: " + e.getMessage());
                    e.printStackTrace();
                    nextScanTime = System.currentTimeMillis() + 6 * 60 * 60 * 1000; // 6 hours
                }
            }
        }
    }

    public static void main(String[] args) {
        List<ScannableService> services = new ArrayList<>();
        // Instagram/Reddit are collected via Apify Actors (InstagramApifyService/RedditApifyService),
        // not the native Graph API / Reddit OAuth clients (InstagramService/RedditAuthClientWithSearch).
        services.add(new ScannableService(new InstagramApifyService(), "Instagram"));
        services.add(new ScannableService(new RedditApifyService(), "Reddit"));
        services.add(new ScannableService(new XService(), "X"));
        services.add(new ScannableService(new YouTubeMain(), "YouTube"));

        for (ScannableService service : services) {
            new Thread(service).start();
        }
    }
}
