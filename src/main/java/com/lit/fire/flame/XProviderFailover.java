package com.lit.fire.flame;

import com.lit.fire.api.SocialMediaScanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The X scanner for x.provider=apify: collects through the Apify scraper (XApifyService) and moves
 * to the official X API (XService) while the scraper can't search, then back once it can.
 *
 * <ul>
 *   <li>Outage: a scraper run that comes back empty with an ERROR in its log (how the scraper
 *       reports e.g. "Search is temporarily unavailable") marks the scraper down. Within
 *       x.failover.check.minutes the Apify polls stop and XService polls every movie instead.</li>
 *   <li>Recovery: while on the X API, one small probe search runs through the scraper every
 *       x.failover.probe.minutes. The first probe that returns tweets moves X back to Apify.</li>
 * </ul>
 * Both providers resume from the same saved tweet id per movie, so a switch neither skips nor
 * re-reads tweets. The switch state is in memory; after a restart X starts on Apify again and
 * fails over after the first empty-with-error run.
 */
public class XProviderFailover implements SocialMediaScanner {

    private final XApifyService apify = new XApifyService();
    private XService api; // created on the first failover: it needs x.access_token
    private final ScheduledExecutorService checker = Executors.newSingleThreadScheduledExecutor();
    private boolean started;
    private boolean usingApi;
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
        if (usingApi) api.scan();
        else apify.scan();
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
        if (!usingApi) {
            String reason = XApifyService.outageReason();
            if (reason == null) return;
            System.out.println("[X failover] Apify X scraper is down (" + reason + "); switching X to the official X API.");
            apify.pause();
            if (api == null) api = new XService();
            usingApi = true;
            nextProbeAt = now + probeIntervalMs();
            api.scan();
            return;
        }
        if (now < nextProbeAt) return;
        nextProbeAt = now + probeIntervalMs();
        System.out.println("[X failover] Checking whether the Apify X scraper can search again...");
        if (!XApifyService.probe()) {
            System.out.println("[X failover] Still down (" + XApifyService.outageReason() + "); staying on the X API.");
            return;
        }
        System.out.println("[X failover] Apify X scraper is working again; switching X back to Apify.");
        api.pause();
        usingApi = false;
        apify.scan();
    }

    private static long probeIntervalMs() {
        return Math.max(10, AppProperties.getIntProperty("x.failover.probe.minutes", 60)) * 60_000L;
    }
}
