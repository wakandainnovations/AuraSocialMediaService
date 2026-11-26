package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The X scanner for x.provider=api-apify: collects through the official X API (XService) and moves
 * to the Apify scraper (XApifyService) while the X developer account has no credits, then back once
 * it has. The reverse of {@link XProviderFailover}.
 *
 * <ul>
 *   <li>Outage: an X API request answered 402 ("credits depleted") marks the API down. Within
 *       x.failover.check.minutes the X API polls and refresh stop and XApifyService polls every
 *       movie instead.</li>
 *   <li>Recovery: while on Apify, one small X API search (10 posts) runs every
 *       x.failover.probe.minutes. The first one that succeeds moves X back to the X API.</li>
 * </ul>
 * Both providers resume from the same saved tweet id per movie, so a switch neither skips nor
 * re-reads tweets. The switch state is in memory; after a restart X starts on the X API again and
 * fails over after the first 402.
 */
public class XApiFirstFailover implements SocialMediaScanner {

    private final XService api = new XService();
    private XApifyService apify; // created on the first failover
    private final ScheduledExecutorService checker = Executors.newSingleThreadScheduledExecutor();
    private boolean started;
    private boolean usingApify;
    private long nextProbeAt;

    @Override
    public synchronized void scan() {
        if (!started) {
            started = true;
            long every = Math.max(1, AppProperties.getIntProperty("x.failover.check.minutes", 10));
            checker.scheduleWithFixedDelay(this::checkSafely, every, every, TimeUnit.MINUTES);
        }
        check();
        // Hourly resync of the active provider's tracked-movie list.
        if (usingApify) apify.scan();
        else api.scan();
    }

    private void checkSafely() {
        try {
            check();
        } catch (Exception e) {
            System.err.println("[X failover] Check failed: " + e.getMessage());
        }
    }

    private synchronized void check() {
        long now = System.currentTimeMillis();
        if (!usingApify) {
            String reason = XService.creditOutage();
            if (reason == null) return;
            System.out.println("[X failover] X API can't be used (" + reason + "); switching X to the Apify scraper.");
            api.pause();
            if (apify == null) apify = new XApifyService();
            usingApify = true;
            nextProbeAt = now + probeIntervalMs();
            apify.scan();
            return;
        }
        if (now < nextProbeAt) return;
        nextProbeAt = now + probeIntervalMs();
        System.out.println("[X failover] Checking whether the X API has credits again...");
        if (!XService.probeCredits()) {
            System.out.println("[X failover] Still no credits (" + XService.creditOutage() + "); staying on Apify.");
            return;
        }
        System.out.println("[X failover] X API is working again; switching X back to the X API.");
        apify.pause();
        usingApify = false;
        api.scan();
    }

    private static long probeIntervalMs() {
        return Math.max(10, AppProperties.getIntProperty("x.failover.probe.minutes", 60)) * 60_000L;
    }
}
