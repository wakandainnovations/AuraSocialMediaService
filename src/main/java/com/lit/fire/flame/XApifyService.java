package com.lit.fire.flame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lit.fire.api.SocialMediaScanner;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects X/Twitter posts via Apify and stores them through the same DatabaseService paths as the
 * native X API v2 integration (XService). It is the default scheduled X scanner (x.provider=apify in
 * application.properties); set x.provider=api to fall back to XService.
 *
 * <p>The scheduled scan is a copy of XService's behaviour, built on X's own advanced-search
 * operators through the Actor "apidojo/twitter-scraper-lite":
 * <ul>
 *   <li><b>New tweets</b>: each tracked entity is polled on TrackedEntityScheduler's adaptive
 *       interval (4h, backing off to 24h for quiet movies) with its keywords OR'd into one query
 *       (split into chunks past {@link #MAX_QUERY_CHARS}, as XService does) plus
 *       {@code since_id:<last saved tweet id>}, so only tweets newer than the last one seen are
 *       fetched. The since_id lives in the same x_post_ids row XService uses, so switching
 *       x.provider either way resumes from the same tweet. Movies due within the same window share
 *       searches ({@link #pollDue}): several movies' keywords in one query, each tweet saved under
 *       the movies whose keywords its text contains.</li>
 *   <li><b>Likes / replies / views refresh</b>: every x.refresh.interval.hours (8h), using the same tiered candidate set and the
 *       same 100-posts-per-cycle cap as XService.refreshMetrics. Instead of a per-tweet lookup (the
 *       Actor bills $0.05 per tweet URL), due tweets are grouped per entity into id-range slices
 *       and each slice's search is re-run over just its range ({@code since_id:/max_id:}), which
 *       returns those tweets with current counts for one query fee. Slices are as wide as is
 *       cheaper ({@link #slices}): two due tweets share a search unless re-reading the saved
 *       non-due tweets between them would cost more than another query.</li>
 * </ul>
 * Every Actor run is logged with its cost to x_cost_log (XCostTracker).
 * The lite Actor has no minimum-results or repeated-query restrictions (unlike tweet-scraper), but
 * allows one concurrent run per account, so all of this class's runs of it are serialized.
 *
 * <p>The on-demand historical backfill ({@link #search(String, List, String, int, Instant, Instant)},
 * used by MovieBackfillService) stays on "apidojo/tweet-scraper": one large date-bounded query per
 * movie, which that Actor is built for, and which XService can't serve (its recent search only
 * covers the last 7 days).
 */
public class XApifyService implements SocialMediaScanner {

    // One-off, large, date-bounded backfill queries (needs >= 50 results per query; no monitoring).
    private static final String BACKFILL_ACTOR_ID = "apidojo/tweet-scraper";
    // Polling + metric refresh (event-priced: $0.016/query incl. ~40 tweets, then per tweet).
    private static final String SCAN_ACTOR_ID = "apidojo/twitter-scraper-lite";
    // The lite Actor allows only one concurrent run per account.
    private static final Object SCAN_ACTOR_LOCK = new Object();
    // Same cap XService uses, so keyword chunking (and therefore query count) matches it.
    private static final int MAX_QUERY_CHARS = 512;
    // A shared search's keyword part, leaving room for " since_id:<19-digit id>".
    private static final int SHARED_QUERY_CHARS = MAX_QUERY_CHARS - 30;
    // Each query's fee already covers ~40 tweets, so never ask for fewer.
    private static final int MIN_QUERY_ITEMS = 40;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    // X's classic tweet timestamp format, e.g. "Wed Oct 10 20:19:24 +0000 2018".
    private static final DateTimeFormatter TWEET_CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);

    private static int numberOfPosts;
    private static int refreshMaxRows;
    private static int refreshMaxItems;
    private static int refreshMaxQueries;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    // See XService: Main re-invokes scan() hourly, so one-time setup must not re-register the refresh.
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final TrackedEntityScheduler entitySchedule = new TrackedEntityScheduler("Aura-X/Apify", 300);
    // Set by XProviderFailover while the X API covers for the scraper: no polls, no refresh.
    private volatile boolean paused;

    // Why the scraper can't search right now (null when it can). Set when a run comes back empty
    // with an error in its log (e.g. "Search is temporarily unavailable"), cleared by a working probe().
    private static volatile String outageReason;

    /** Thrown when the scraper's run "succeeded" but its log shows it couldn't search. */
    static final class ScraperUnavailableException extends Exception {
        ScraperUnavailableException(String message) {
            super(message);
        }
    }

    private static void loadConfig() {
        numberOfPosts = AppProperties.getIntProperty("x.apify.max_items",
                AppProperties.getIntProperty("number.of.posts", 100));
        // Same per-cycle cap as XService.MAX_REFRESH_PER_CYCLE.
        refreshMaxRows = AppProperties.getIntProperty("x.apify.refresh.max_rows", 100);
        refreshMaxItems = AppProperties.getIntProperty("x.apify.refresh.max_items", 200);
        refreshMaxQueries = AppProperties.getIntProperty("x.apify.refresh.max_queries", 10);
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduled scan: new tweets since the last saved tweet id
    // ---------------------------------------------------------------------------------------------

    /**
     * Scheduled poll for every entity due together (TrackedEntityScheduler.BatchTask). Entities that
     * already have a saved tweet id are packed into shared searches: their keywords OR'd into one
     * query of at most {@link #MAX_QUERY_CHARS}, bounded by the oldest member's since_id, and each
     * returned tweet is saved under every member whose keywords it contains. A first poll (no
     * since_id yet) or a keyword list too long to share a query is polled on its own.
     */
    static Map<String, TrackedEntityScheduler.PollResult> pollDue(List<TrackedEntityScheduler.Due> due) {
        Map<String, TrackedEntityScheduler.PollResult> results = new LinkedHashMap<>();
        if (outageReason != null) {
            // Don't pay for more runs that can't search; XProviderFailover moves X to the X API.
            System.out.println("[Apify/X] Scraper unavailable (" + outageReason + "); not polling "
                    + due.stream().map(TrackedEntityScheduler.Due::entity).toList() + ".");
            for (TrackedEntityScheduler.Due d : due) results.put(d.entity(), TrackedEntityScheduler.PollResult.FAILED);
            return results;
        }
        List<Member> shareable = new ArrayList<>();
        for (TrackedEntityScheduler.Due d : due) {
            String sinceId = validSinceId(DatabaseService.getLastXPostId(d.entity()));
            List<String> keywords = distinct(d.keywords());
            if (sinceId == null || coreQuery(keywords).length() > SHARED_QUERY_CHARS || due.size() == 1) {
                try {
                    results.put(d.entity(), poll(d.entity(), d.keywords(), d.category()));
                } catch (Exception e) {
                    System.err.println("[Apify/X] Poll failed for '" + d.entity() + "': " + e.getMessage());
                    results.put(d.entity(), TrackedEntityScheduler.PollResult.FAILED);
                }
                continue;
            }
            shareable.add(new Member(d.entity(), keywords, d.category(), sinceId));
        }
        for (List<Member> group : packMembers(shareable)) {
            try {
                results.putAll(group.size() == 1
                        ? Map.of(group.get(0).entity(), poll(group.get(0).entity(), group.get(0).keywords(), group.get(0).category()))
                        : pollGroup(group));
            } catch (Exception e) {
                System.err.println("[Apify/X] Shared poll failed for " + group.stream().map(Member::entity).toList() + ": " + e.getMessage());
                for (Member m : group) results.put(m.entity(), TrackedEntityScheduler.PollResult.FAILED);
            }
        }
        return results;
    }

    // An entity in a shared search, with its last saved tweet id.
    record Member(String entity, List<String> keywords, String category, String sinceId) {
    }

    /** How many tweets a member already has saved after {@code fromSinceId} (up to its own since_id). */
    interface ReReads {
        int count(Member member, String fromSinceId);
    }

    static List<List<Member>> packMembers(List<Member> members) {
        return packMembers(members, XApifyService::savedTweetsSince, mergeBreakEvenItems());
    }

    // Saved tweets of the member with fromSinceId < id <= member.sinceId(): what a shared query
    // starting at fromSinceId re-reads (and bills) for it.
    private static int savedTweetsSince(Member m, String fromSinceId) {
        if (compareIds(fromSinceId, m.sinceId()) >= 0) return 0;
        long from = new BigInteger(fromSinceId).longValue();
        long to = new BigInteger(m.sinceId()).longValue();
        int n = 0;
        for (long id : DatabaseService.getXPostIdsBetween(m.entity(),
                tweetTimeMs(fromSinceId) / 1000 - 1, tweetTimeMs(m.sinceId()) / 1000 + 1)) {
            if (id > from && id <= to) n++;
        }
        return n;
    }

    /**
     * Greedily packs members into shared searches, newest since_id first. A query is bounded by its
     * oldest member's since_id, so every tweet the newer members already saved after it is read and
     * billed again. A member joins the current group only when its keywords fit in the query and
     * the group's re-reads, counting from that member's since_id, stay below {@code breakEven}
     * tweets (the price of one more query, 40 at $0.016/query and $0.0004/tweet). Otherwise
     * it starts a new group.
     */
    static List<List<Member>> packMembers(List<Member> members, ReReads reReads, int breakEven) {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> compareIds(b.sinceId(), a.sinceId()));
        List<List<Member>> groups = new ArrayList<>();
        List<Member> current = new ArrayList<>();
        Set<String> currentKeywords = new LinkedHashSet<>();
        for (Member m : sorted) {
            Set<String> candidate = new LinkedHashSet<>(currentKeywords);
            candidate.addAll(m.keywords());
            boolean fits = coreQuery(new ArrayList<>(candidate)).length() <= SHARED_QUERY_CHARS;
            boolean cheap = true;
            if (fits && !current.isEmpty()) {
                int extra = 0;
                for (Member c : current) extra += reReads.count(c, m.sinceId());
                cheap = extra < breakEven;
            }
            if (!current.isEmpty() && (!fits || !cheap)) {
                groups.add(current);
                current = new ArrayList<>();
                candidate = new LinkedHashSet<>(m.keywords());
            }
            current.add(m);
            currentKeywords = candidate;
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static Map<String, TrackedEntityScheduler.PollResult> pollGroup(List<Member> group) throws Exception {
        Set<String> allKeywords = new LinkedHashSet<>();
        String oldestSinceId = null;
        for (Member m : group) {
            allKeywords.addAll(m.keywords());
            oldestSinceId = oldestSinceId == null || compareIds(m.sinceId(), oldestSinceId) < 0 ? m.sinceId() : oldestSinceId;
        }
        String label = String.join(", ", group.stream().map(Member::entity).toList());
        System.out.println("\n[Apify/X] Shared search for " + group.size() + " entities: " + label);
        String query = coreQuery(new ArrayList<>(allKeywords)) + " since_id:" + oldestSinceId;
        JsonArray items = runScanActor("poll", label, query, numberOfPosts);
        boolean capped = items.size() >= numberOfPosts;

        List<JsonObject> tweets = new ArrayList<>();
        String newestReturned = null;
        for (JsonElement el : items) {
            JsonObject post = toXPost(el.getAsJsonObject());
            if (post == null) continue;
            tweets.add(post);
            newestReturned = XService.maxId(newestReturned, post.get("id").getAsString());
        }

        Map<String, TrackedEntityScheduler.PollResult> results = new LinkedHashMap<>();
        Set<String> matchedIds = new HashSet<>();
        Set<String> mentionedIds = new HashSet<>();
        for (Member m : group) {
            JsonArray posts = new JsonArray();
            for (JsonObject post : tweets) {
                String id = post.get("id").getAsString();
                if (!mentionsAny(post.get("text").getAsString(), m.keywords())) continue;
                mentionedIds.add(id);
                if (compareIds(id, m.sinceId()) <= 0) continue; // already saved by an earlier poll
                posts.add(post);
                matchedIds.add(id);
            }
            if (posts.size() > 0) {
                DatabaseService.saveXPosts(posts, m.entity(), m.keywords(), m.category());
            }
            // The search covered every member's keywords up to the newest tweet it returned, so each
            // member resumes from there - a quiet member doesn't hold every shared query back.
            if (newestReturned != null && compareIds(newestReturned, m.sinceId()) > 0) {
                DatabaseService.saveLastXPostId(m.entity(), newestReturned);
            }
            System.out.println("[Apify/X] '" + m.entity() + "': " + posts.size() + " new tweet(s) from the shared search.");
            results.put(m.entity(), TrackedEntityScheduler.PollResult.of(posts.size(), capped));
        }
        long alreadySaved = mentionedIds.stream().filter(id -> !matchedIds.contains(id)).count();
        long unmatched = tweets.stream().filter(t -> !mentionedIds.contains(t.get("id").getAsString())).count();
        if (alreadySaved > 0) {
            System.out.println("[Apify/X] " + alreadySaved + " tweet(s) were already saved (re-read because the search starts at the oldest movie's last tweet); skipped.");
        }
        if (unmatched > 0) {
            System.out.println("[Apify/X] " + unmatched + " tweet(s) don't name any movie in their text (e.g. replies X matched by conversation); dropped.");
        }
        return results;
    }

    /**
     * Whether the tweet text contains one of the keywords: case-insensitive, ignoring '#' and
     * quotes, and for a multi-word keyword every word anywhere in the text (as X's own search
     * treats an unquoted multi-word term).
     */
    static boolean mentionsAny(String text, List<String> keywords) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            String[] words = keyword.toLowerCase(Locale.ROOT).replace("#", "").replace("\"", "").trim().split("\\s+");
            boolean all = words.length > 0 && !words[0].isEmpty();
            for (String w : words) {
                if (!lower.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    // Milliseconds since the epoch encoded in a tweet (snowflake) id.
    private static long tweetTimeMs(String id) {
        return new BigInteger(id).shiftRight(22).longValue() + 1288834974657L;
    }

    private static String validSinceId(String id) {
        if (id == null) return null;
        id = id.trim();
        return id.isEmpty() || id.equalsIgnoreCase("null") || !id.chars().allMatch(Character::isDigit) ? null : id;
    }

    private static List<String> distinct(List<String> keywords) {
        return new ArrayList<>(new LinkedHashSet<>(keywords));
    }

    /**
     * Polls one entity for tweets newer than its last saved tweet id (XService.search's equivalent).
     * With no saved id yet (first poll), fetches the latest {@code numberOfPosts} with no lower bound,
     * as XService does. The since_id is only advanced after every chunk has been saved. The result
     * is "capped" when a chunk returned the full {@code numberOfPosts}.
     */
    static TrackedEntityScheduler.PollResult poll(String entity, List<String> keywords, String category) throws Exception {
        String lastPostId = DatabaseService.getLastXPostId(entity);
        boolean hasSinceId = lastPostId != null && !lastPostId.trim().isEmpty()
                && !lastPostId.equalsIgnoreCase("null");
        System.out.println("\n[Apify/X] Retrieving up to " + numberOfPosts + " new posts for entity '" + entity
                + "' (keywords: " + String.join(", ", keywords) + ")"
                + (hasSinceId ? " since tweet " + lastPostId : "") + "...");

        String maxNewestId = null;
        int newPosts = 0;
        boolean capped = false;
        for (List<String> chunk : XService.chunkKeywords(keywords, MAX_QUERY_CHARS)) {
            String query = coreQuery(chunk) + (hasSinceId ? " since_id:" + lastPostId.trim() : "");
            JsonArray items = runScanActor("poll", entity, query, numberOfPosts);
            capped |= items.size() >= numberOfPosts;

            JsonArray posts = new JsonArray();
            for (JsonElement el : items) {
                JsonObject post = toXPost(el.getAsJsonObject());
                if (post == null) continue;
                String id = post.get("id").getAsString();
                // Guarantee "newer than the last seen tweet" even if the search returned an edge item.
                if (hasSinceId && compareIds(id, lastPostId.trim()) <= 0) continue;
                posts.add(post);
                maxNewestId = XService.maxId(maxNewestId, id);
            }

            if (posts.size() == 0) {
                System.out.println("[Apify/X] No new posts for entity: " + entity + " (chunk: " + coreQuery(chunk) + ").");
                continue;
            }
            // Pass only this chunk's keywords so matched-keyword detection stays accurate.
            DatabaseService.saveXPosts(posts, entity, chunk, category);
            newPosts += posts.size();
        }

        if (maxNewestId != null) {
            DatabaseService.saveLastXPostId(entity, maxNewestId);
        }
        return TrackedEntityScheduler.PollResult.of(newPosts, capped);
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduled scan: likes / replies / views refresh
    // ---------------------------------------------------------------------------------------------

    /**
     * Refreshes likes/replies/views on previously-collected posts (XService.refreshMetrics's
     * equivalent): the same tiered candidate set, capped at {@code refreshMaxRows} (100, like
     * XService) per hourly cycle. Due posts are grouped by entity, split into cost-sized id-range
     * slices ({@link #slices}), and each slice is refreshed by re-running the entity's search bounded to its tweet-id range.
     * At most {@code refreshMaxQueries} searches run per cycle; slices beyond that stay due.
     */
    public static void refreshMetrics() throws Exception {
        List<Map<String, String>> due = DatabaseService.getXPostsDueForRefresh(refreshMaxRows);
        if (due.isEmpty()) {
            System.out.println("[Aura-X/Apify Refresh] Nothing due.");
            return;
        }

        // Group by entity, keeping the SQL's priority order (an entity with Tier A rows - fresh
        // posts - comes first, then whoever has waited longest).
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> row : due) {
            groups.computeIfAbsent(row.get("entity"), k -> new ArrayList<>()).add(row);
        }

        Map<String, List<String>> trackedKeywords = loadTrackedKeywords();
        int breakEven = mergeBreakEvenItems();
        int queriesUsed = 0;
        int slicesDone = 0;
        int slicesSkipped = 0;
        for (Map.Entry<String, List<Map<String, String>>> group : groups.entrySet()) {
            String entity = group.getKey();
            List<String> keywords = keywordsFor(entity, trackedKeywords);
            if (keywords.isEmpty()) {
                DatabaseService.markRefreshAttempted(compositeIds(group.getValue()));
                continue;
            }
            long from = Long.MAX_VALUE;
            long to = Long.MIN_VALUE;
            for (Map<String, String> row : group.getValue()) {
                from = Math.min(from, createdAt(row));
                to = Math.max(to, createdAt(row));
            }
            long[] savedIds = DatabaseService.getXPostIdsBetween(entity, from, to);
            for (Slice slice : slices(group.getValue(), savedIds, breakEven, refreshMaxItems)) {
                List<List<String>> chunks = XService.chunkKeywords(keywords, MAX_QUERY_CHARS);
                if (queriesUsed + chunks.size() > refreshMaxQueries) {
                    slicesSkipped++;
                    continue; // stays due for the next cycle
                }
                queriesUsed += chunks.size();
                try {
                    refreshSlice(entity, slice, chunks);
                    slicesDone++;
                } catch (Exception e) {
                    // Rows stay due, so the next hourly cycle retries them.
                    System.err.println("[Aura-X/Apify Refresh] Failed for '" + entity + "': " + e.getMessage());
                }
            }
        }
        System.out.println("[Aura-X/Apify Refresh] " + due.size() + " due row(s): " + slicesDone + " slice(s) refreshed with "
                + queriesUsed + " query(ies)" + (slicesSkipped > 0 ? ", " + slicesSkipped + " left for next cycle (query cap)" : "") + ".");
    }

    /**
     * One refresh search: due rows plus how many tweets its id range is expected to return (the
     * entity's saved tweets in [first due id, last due id], due or not - the search returns them all).
     */
    record Slice(List<Map<String, String>> rows, int expectedItems) {
    }

    // Extra items requested on top of the expected count (tweets the search finds that weren't saved,
    // e.g. dropped by the @-handle filter).
    private static final int REFRESH_ITEM_MARGIN = 20;

    // A new query's fee buys this many tweets' worth of per-item charges (0.016 / 0.0004 = 40), so
    // widening a slice over fewer non-due tweets than this is cheaper than starting another query.
    private static int mergeBreakEvenItems() {
        double perQuery = doubleProperty("x.apify.cost.per_query_usd", 0.016);
        double perItem = doubleProperty("x.apify.cost.per_item_usd", 0.0004);
        return perItem <= 0 ? Integer.MAX_VALUE : (int) Math.round(perQuery / perItem);
    }

    private static double doubleProperty(String key, double defaultValue) {
        String v = AppProperties.getProperty(key);
        try {
            return v == null ? defaultValue : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Splits one entity's due rows (in tweet-id order) into as few id-range searches as is cheaper:
     * the next due tweet joins the current slice when fewer than {@code breakEvenItems} saved
     * non-due tweets lie between it and the slice's last due tweet (re-reading those costs less than
     * another query) and the widened range still fits {@code maxItemsPerQuery}.
     */
    static List<Slice> slices(List<Map<String, String>> rows, long[] savedIds, int breakEvenItems, int maxItemsPerQuery) {
        List<Map<String, String>> sorted = new ArrayList<>(rows);
        sorted.sort((x, y) -> compareIds(x.get("x_id"), y.get("x_id")));
        List<Slice> slices = new ArrayList<>();
        List<Map<String, String>> current = new ArrayList<>();
        long start = 0;
        long last = 0;
        for (Map<String, String> row : sorted) {
            long id = Long.parseLong(row.get("x_id"));
            if (!current.isEmpty()) {
                int between = countIdsIn(savedIds, last + 1, id - 1);
                int widened = Math.max(countIdsIn(savedIds, start, id), current.size() + 1);
                if (between >= breakEvenItems || widened + REFRESH_ITEM_MARGIN > maxItemsPerQuery) {
                    slices.add(new Slice(current, Math.max(countIdsIn(savedIds, start, last), current.size())));
                    current = new ArrayList<>();
                }
            }
            if (current.isEmpty()) start = id;
            current.add(row);
            last = id;
        }
        if (!current.isEmpty()) slices.add(new Slice(current, Math.max(countIdsIn(savedIds, start, last), current.size())));
        return slices;
    }

    // Number of ids in the sorted array within [from, to].
    private static int countIdsIn(long[] sortedIds, long from, long to) {
        if (to < from) return 0;
        return lowerBound(sortedIds, to + 1) - lowerBound(sortedIds, from);
    }

    private static int lowerBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static void refreshSlice(String entity, Slice slice, List<List<String>> chunks) throws Exception {
        List<Map<String, String>> rows = slice.rows();
        String minId = null;
        String maxId = null;
        for (Map<String, String> row : rows) {
            String id = row.get("x_id");
            maxId = XService.maxId(maxId, id);
            minId = minId == null || compareIds(id, minId) < 0 ? id : minId;
        }
        // since_id is exclusive, max_id inclusive: [minId, maxId] covers exactly the slice.
        String range = " since_id:" + new BigInteger(minId).subtract(BigInteger.ONE) + " max_id:" + maxId;
        int maxItems = Math.min(refreshMaxItems, Math.max(MIN_QUERY_ITEMS, slice.expectedItems() + REFRESH_ITEM_MARGIN));

        Map<String, int[]> metricsByXId = new HashMap<>();
        // Tweets older than this weren't reached because a chunk's results hit maxItems; they stay due
        // so the next cycle (with a narrower range) picks them up.
        String coveredFromId = null;
        for (List<String> chunk : chunks) {
            JsonArray items = runScanActor("refresh", entity, coreQuery(chunk) + range, maxItems);
            String oldestReturned = null;
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                String id = stringOrNull(item, "id");
                if (id == null || !isTweet(item)) continue;
                metricsByXId.put(id, new int[]{
                        intOrDefault(item, "likeCount", 0),
                        intOrDefault(item, "replyCount", 0),
                        viewCount(item)});
                oldestReturned = oldestReturned == null || compareIds(id, oldestReturned) < 0 ? id : oldestReturned;
            }
            if (items.size() >= maxItems && oldestReturned != null) {
                coveredFromId = XService.maxId(coveredFromId, oldestReturned);
            }
        }

        List<String> attempted = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (coveredFromId == null || compareIds(row.get("x_id"), coveredFromId) >= 0) {
                attempted.add(row.get("composite_id"));
            }
        }
        System.out.println("[Aura-X/Apify Refresh] '" + entity + "': " + rows.size()
                + " due, " + metricsByXId.size() + " tweet(s) returned with fresh counts.");
        // As in XService: mark every covered candidate first (deleted/private tweets simply don't come
        // back and shouldn't re-enter next cycle), then write the fresh counts.
        DatabaseService.markRefreshAttempted(attempted);
        DatabaseService.updateXPostMetrics(metricsByXId);
    }

    private static List<String> keywordsFor(String entity, Map<String, List<String>> trackedKeywords) {
        List<String> keywords = trackedKeywords.get(entity);
        if (keywords != null && !keywords.isEmpty()) return keywords;
        // Entity left the tracked list (window ended / excluded) but still has posts in the 7-day
        // refresh policy: rebuild its search from the keywords its posts were saved under.
        return DatabaseService.getXPostKeywords(entity);
    }

    private static List<String> compositeIds(List<Map<String, String>> rows) {
        List<String> ids = new ArrayList<>();
        for (Map<String, String> row : rows) ids.add(row.get("composite_id"));
        return ids;
    }

    private static long createdAt(Map<String, String> row) {
        String v = row.get("created_at");
        return v == null ? 0 : Long.parseLong(v);
    }

    private static Map<String, List<String>> loadTrackedKeywords() {
        Map<String, List<String>> byEntity = new HashMap<>();
        for (JsonObject q : TrackedReleaseSource.loadInputQueries()) {
            String keyword = q.get("keyword").getAsString().trim();
            if (keyword.isEmpty()) continue;
            byEntity.computeIfAbsent(q.get("entity").getAsString(), k -> new ArrayList<>()).add(keyword);
        }
        return byEntity;
    }

    private static JsonArray runScanActor(String operation, String entity, String query, int maxItems) throws Exception {
        System.out.println("[Apify/X] Query: " + query + " (maxItems " + maxItems + ")");
        JsonArray searchTerms = new JsonArray();
        searchTerms.add(query);
        JsonObject actorInput = new JsonObject();
        actorInput.add("searchTerms", searchTerms);
        actorInput.addProperty("maxItems", maxItems);
        actorInput.addProperty("sort", "Latest");
        ApifyClient.RunResult run;
        synchronized (SCAN_ACTOR_LOCK) {
            run = ApifyClient.runActor(SCAN_ACTOR_ID, actorInput);
        }
        System.out.println("[Apify/X] Returned " + run.items().size() + " item(s).");
        XCostTracker.recordApifyScanRun(operation, entity, run);
        if (run.items().isEmpty()) {
            // An empty result is normal for a quiet movie, but the scraper also reports an outage as a
            // "successful" empty run, with the reason only in its log.
            String error = logError(ApifyClient.fetchRunLog(run.runId()));
            if (error != null) {
                outageReason = error;
                throw new ScraperUnavailableException("Apify X scraper can't search: " + error);
            }
        }
        return run.items();
    }

    /** The first ERROR line of a run log (without the timestamp), or null when there is none. */
    static String logError(String log) {
        if (log == null) return null;
        for (String line : log.split("\\R")) {
            int at = line.indexOf("ERROR");
            if (at >= 0) {
                String message = line.substring(at + "ERROR".length()).trim();
                return message.isEmpty() ? "ERROR" : message;
            }
        }
        return null;
    }

    static String outageReason() {
        return outageReason;
    }

    /**
     * Whether the scraper can search again: one small search for {@code x.failover.probe.query}
     * (a term that always has fresh tweets). Returns true, and clears the outage, when it returns
     * tweets. Costs one query ($0.016) per call.
     */
    static boolean probe() {
        String query = AppProperties.getProperty("x.failover.probe.query");
        if (query == null || query.isBlank()) query = "cinema";
        try {
            JsonArray items = runScanActor("probe", "(health check)", query.trim(), 40);
            if (items.isEmpty()) {
                if (outageReason == null) outageReason = "probe search returned no tweets";
                return false;
            }
            outageReason = null;
            return true;
        } catch (Exception e) {
            if (outageReason == null) outageReason = e.getMessage();
            System.err.println("[Apify/X] Probe failed: " + e.getMessage());
            return false;
        }
    }

    /** Stops polling and refreshing until the next scan(). */
    void pause() {
        paused = true;
        entitySchedule.cancelAll();
    }

    @Override
    public void scan() {
        paused = false;
        if (initialized.compareAndSet(false, true)) {
            loadConfig();
            System.out.println("Initializing X Apify Search (up to " + numberOfPosts + " new tweets per entity per poll)...");
            // Metrics refresh across previously-collected posts (tiered policy in SQL), every
            // x.refresh.interval.hours (8 by default), on the same schedule as XService.
            long refreshEvery = Math.max(1, AppProperties.getIntProperty("x.refresh.interval.hours", 8)) * 3600L;
            scheduler.scheduleAtFixedRate(() -> {
                if (paused) return;
                try {
                    refreshMetrics();
                } catch (Exception e) {
                    System.err.println("[Aura-X/Apify] refreshMetrics failed: " + e.getMessage());
                }
            }, 600, refreshEvery, TimeUnit.SECONDS);
        }
        // Not gated on TrackedReleaseSource.isApifyEligible: RelevanceCheckService needs these X
        // posts to make that decision in the first place.
        entitySchedule.sync(scheduler, XApifyService::pollDue);
    }

    // ---------------------------------------------------------------------------------------------
    // On-demand historical backfill (MovieBackfillService)
    // ---------------------------------------------------------------------------------------------

    /**
     * Scrapes a single entity in one Actor run, combining all of the entity's keywords into one
     * OR'd search query, optionally bounded to [dateFrom, dateTo] via X's own "since:"/"until:"
     * search operators (either may be null for no bound).
     */
    public static void search(String entity, List<String> keywords, String category, int maxItems, Instant dateFrom, Instant dateTo) throws Exception {
        String query = buildQuery(keywords, dateFrom, dateTo);
        System.out.println("[Apify/X] Backfill for entity '" + entity + "' -> query: " + query);

        JsonArray searchTerms = new JsonArray();
        searchTerms.add(query);

        JsonObject actorInput = new JsonObject();
        actorInput.add("searchTerms", searchTerms);
        actorInput.addProperty("maxItems", maxItems);
        actorInput.addProperty("sort", "Latest");

        ApifyClient.RunResult run = ApifyClient.runActor(BACKFILL_ACTOR_ID, actorInput);
        XCostTracker.recordApifyBackfillRun(entity, run);
        JsonArray items = run.items();
        System.out.println("[Apify/X] '" + entity + "' returned " + items.size() + " item(s).");

        JsonArray posts = new JsonArray();
        for (JsonElement el : items) {
            JsonObject post = toXPost(el.getAsJsonObject());
            if (post != null) {
                posts.add(post);
            }
        }

        if (posts.size() > 0) {
            DatabaseService.saveXPosts(posts, entity, keywords, category);
        }
    }

    // Builds the OR'd query with X's native since:/until: date operators appended, e.g.
    // "(ToxicTheMovie) since:2026-06-07 until:2026-09-07".
    private static String buildQuery(List<String> keywords, Instant dateFrom, Instant dateTo) {
        StringBuilder query = new StringBuilder(coreQuery(keywords));
        if (dateFrom != null) {
            query.append(" since:").append(DATE_FORMATTER.format(dateFrom));
        }
        if (dateTo != null) {
            query.append(" until:").append(DATE_FORMATTER.format(dateTo));
        }
        return query.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    // X binds OR tighter than the implicit AND between words, so a bare multi-word keyword inside
    // an OR list ("A OR Minnum Oar Iravu OR B") would AND its words into the whole query; each one
    // is parenthesised to keep it a single alternative.
    static String coreQuery(List<String> keywords) {
        return XService.buildQuery(keywords);
    }

    // Numeric comparison of tweet (snowflake) ids.
    private static int compareIds(String a, String b) {
        return new BigInteger(a).compareTo(new BigInteger(b));
    }

    // The Actors emit "type":"tweet" for tweets (and placeholder items such as {"noResults":true}
    // when nothing matched); anything without an id or of another type is skipped.
    private static boolean isTweet(JsonObject item) {
        String type = stringOrNull(item, "type");
        return type == null || type.equals("tweet");
    }

    private static int viewCount(JsonObject item) {
        return intOrDefault(item, "viewCount", intOrDefault(item, "views", 0));
    }

    /**
     * Reshapes an Apify dataset item into the field set DatabaseService.saveXPosts(...) requires
     * (id, text, created_at, permalink, author, likes_count, comment_count, views_count) so that
     * method can be used unmodified.
     */
    private static JsonObject toXPost(JsonObject item) {
        if (!item.has("id") || item.get("id").isJsonNull() || !isTweet(item)) {
            return null;
        }

        JsonObject post = new JsonObject();
        post.addProperty("id", item.get("id").getAsString());

        String text = stringOrNull(item, "text");
        if (text == null) {
            text = stringOrNull(item, "fullText");
        }
        post.addProperty("text", text != null ? text : "");

        Instant createdAt = parseCreatedAt(item);
        post.addProperty("created_at", (createdAt != null ? createdAt : Instant.now()).toString());

        String permalink = stringOrNull(item, "url");
        if (permalink == null) {
            permalink = stringOrNull(item, "twitterUrl");
        }
        if (permalink != null) {
            post.addProperty("permalink", permalink);
        }

        post.addProperty("author", resolveAuthor(item));
        post.addProperty("likes_count", intOrDefault(item, "likeCount", 0));
        post.addProperty("comment_count", intOrDefault(item, "replyCount", 0));
        post.addProperty("views_count", viewCount(item));
        return post;
    }

    // Author info may come back nested under an "author" object (userName/name) or as top-level
    // fields, depending on the Actor's output shape; try both rather than assume one.
    private static String resolveAuthor(JsonObject item) {
        if (item.has("author") && item.get("author").isJsonObject()) {
            JsonObject author = item.getAsJsonObject("author");
            String name = stringOrNull(author, "name");
            if (name != null) {
                return name;
            }
            String userName = stringOrNull(author, "userName");
            if (userName != null) {
                return userName;
            }
        }
        String name = stringOrNull(item, "name");
        if (name != null) {
            return name;
        }
        String userName = stringOrNull(item, "userName");
        return userName != null ? userName : "unknown_user";
    }

    // "createdAt" comes back in X's classic format ("Wed Oct 10 20:19:24 +0000 2018"), but parse
    // defensively in case the Actor ever switches to plain ISO-8601.
    private static Instant parseCreatedAt(JsonObject item) {
        String raw = stringOrNull(item, "createdAt");
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(raw, TWEET_CREATED_AT_FORMATTER).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int intOrDefault(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
