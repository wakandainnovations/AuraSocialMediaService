package com.lit.fire.flame;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Minimal TMDB (The Movie Database) client for {@link ReleaseDiscoveryService}: enumerates
 * India-origin releases in a date window and re-fetches single movies by id. Auth mirrors
 * AuraDataFiller's TmdbClient - the v4 "API Read Access Token" (secrets.properties
 * tmdb.api.read.access.token, sent as Bearer) is preferred, else the v3 key (tmdb.api.key).
 */
public class TmdbReleaseClient {

    private static final String API_BASE = "https://api.themoviedb.org/3";
    private static final int MAX_RETRIES = 5;
    // TMDB caps discover at 500 pages; a ~3-week India window is a handful of pages in practice.
    private static final int MAX_PAGES = 50;
    // TMDB release types: 2 = theatrical (limited), 3 = theatrical. "|" is OR, URL-encoded.
    private static final String THEATRICAL_TYPES_PARAM = "2%7C3";

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("hi", "hindi"), Map.entry("ta", "tamil"), Map.entry("te", "telugu"),
            Map.entry("kn", "kannada"), Map.entry("ml", "malayalam"), Map.entry("mr", "marathi"),
            Map.entry("bn", "bengali"), Map.entry("pa", "punjabi"), Map.entry("gu", "gujarati"),
            Map.entry("or", "odia"), Map.entry("as", "assamese"), Map.entry("bho", "bhojpuri"),
            Map.entry("en", "english"), Map.entry("ur", "urdu"), Map.entry("sa", "sanskrit"),
            Map.entry("kok", "konkani"), Map.entry("tcy", "tulu"), Map.entry("ne", "nepali"),
            Map.entry("mai", "maithili"), Map.entry("raj", "rajasthani"));

    /**
     * {@code contextTerms}: lead cast + director names (TMDB credits), used by the relevance check.
     * {@code details}: genre/runtime/director/production company, copied onto the platform movie by
     * AuraService's "Add to platform".
     */
    public record Release(int tmdbId, String title, String originalTitle, String language,
                          LocalDate releaseDate, Double popularity, String posterPath, List<String> contextTerms,
                          Details details) {
    }

    /** Comma-separated genres and directors; runtime in minutes; first listed production company. */
    public record Details(String genres, Integer runtime, String directors, String productionCompany) {
    }

    // How many top-billed cast members count as movie context for the relevance check.
    private static final int CONTEXT_CAST = 6;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String bearerToken;
    private final String apiKey;

    public TmdbReleaseClient() throws Exception {
        Properties secrets = new Properties();
        try (InputStream in = TmdbReleaseClient.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (in != null) secrets.load(in);
        }
        this.bearerToken = blankToNull(secrets.getProperty("tmdb.api.read.access.token"));
        this.apiKey = blankToNull(secrets.getProperty("tmdb.api.key"));
        if (bearerToken == null && apiKey == null) {
            throw new IllegalStateException("No TMDB credential: set tmdb.api.read.access.token or tmdb.api.key in secrets.properties");
        }
    }

    /**
     * Every India-origin movie with an India theatrical release (TMDB release type 2 "limited
     * theatrical" or 3 "theatrical", region IN) dated in [from, to]. This filters out short films,
     * festival-only screenings and OTT/digital-only premieres, which TMDB records as other release
     * types (or none). Each hit is then re-fetched for its India theatrical date: the discover
     * result's own release_date is the earliest release anywhere (e.g. a festival or overseas
     * premiere), which would put the ±2-day tracking window in the wrong place.
     */
    public List<Release> discoverIndianReleases(LocalDate from, LocalDate to) throws Exception {
        List<Integer> ids = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        while (page <= totalPages && page <= MAX_PAGES) {
            JsonObject root = get(API_BASE + "/discover/movie?with_origin_country=IN&include_adult=false" +
                    "&region=IN&with_release_type=" + THEATRICAL_TYPES_PARAM +
                    "&release_date.gte=" + from + "&release_date.lte=" + to +
                    "&sort_by=primary_release_date.asc&page=" + page);
            for (JsonElement el : root.getAsJsonArray("results")) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("id")) ids.add(o.get("id").getAsInt());
            }
            totalPages = root.has("total_pages") ? root.get("total_pages").getAsInt() : 1;
            page++;
        }
        List<Release> out = new ArrayList<>();
        for (int id : ids) {
            Release r = fetch(id, from);
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * Current details for one movie, dated by its India theatrical release (see
     * {@link #indiaTheatricalDate}); null when TMDB has no India theatrical release for it
     * (anymore), so it isn't (re)tracked.
     */
    public Release fetch(int tmdbId, LocalDate notBefore) throws Exception {
        JsonObject o = get(API_BASE + "/movie/" + tmdbId + "?append_to_response=release_dates,credits");
        LocalDate theatrical = indiaTheatricalDate(o, notBefore);
        return theatrical == null ? null : toRelease(o, theatrical);
    }

    /**
     * The IN theatrical (type 2/3) release date to track, from an append_to_response=release_dates
     * payload: the earliest one on or after {@code notBefore} - so a re-release (e.g. a 2025 film
     * re-released nationwide in Nov 2026) or a postponed release is dated by its current run, not a
     * past one - else the latest past one. Null when there's no IN theatrical release at all.
     */
    static LocalDate indiaTheatricalDate(JsonObject movie, LocalDate notBefore) {
        if (!movie.has("release_dates")) return null;
        LocalDate earliest = null;
        LocalDate latestPast = null;
        for (JsonElement c : movie.getAsJsonObject("release_dates").getAsJsonArray("results")) {
            JsonObject country = c.getAsJsonObject();
            if (!"IN".equals(str(country, "iso_3166_1"))) continue;
            for (JsonElement d : country.getAsJsonArray("release_dates")) {
                JsonObject rd = d.getAsJsonObject();
                int type = rd.has("type") ? rd.get("type").getAsInt() : 0;
                String date = str(rd, "release_date");
                if ((type != 2 && type != 3) || date == null || date.length() < 10) continue;
                try {
                    LocalDate day = LocalDate.parse(date.substring(0, 10));
                    if (notBefore != null && day.isBefore(notBefore)) {
                        if (latestPast == null || day.isAfter(latestPast)) latestPast = day;
                    } else if (earliest == null || day.isBefore(earliest)) {
                        earliest = day;
                    }
                } catch (DateTimeParseException ignored) {
                    // skip malformed entry
                }
            }
        }
        return earliest != null ? earliest : latestPast;
    }

    private static Release toRelease(JsonObject o, LocalDate releaseDate) {
        String title = str(o, "title");
        if (title == null || !o.has("id")) return null;
        String code = str(o, "original_language");
        String language = code == null ? null : LANGUAGES.getOrDefault(code.toLowerCase(), code.toLowerCase());
        Double popularity = o.has("popularity") && !o.get("popularity").isJsonNull() ? o.get("popularity").getAsDouble() : null;
        return new Release(o.get("id").getAsInt(), title, str(o, "original_title"), language,
                releaseDate, popularity, str(o, "poster_path"), creditNames(o), details(o));
    }

    static Details details(JsonObject movie) {
        List<String> genres = new ArrayList<>();
        if (movie.has("genres") && movie.get("genres").isJsonArray()) {
            for (JsonElement g : movie.getAsJsonArray("genres")) {
                String n = str(g.getAsJsonObject(), "name");
                if (n != null) genres.add(n);
            }
        }
        Integer runtime = movie.has("runtime") && !movie.get("runtime").isJsonNull()
                && movie.get("runtime").getAsInt() > 0 ? movie.get("runtime").getAsInt() : null;
        List<String> directors = new ArrayList<>();
        if (movie.has("credits") && movie.get("credits").isJsonObject()
                && movie.getAsJsonObject("credits").has("crew")) {
            for (JsonElement c : movie.getAsJsonObject("credits").getAsJsonArray("crew")) {
                JsonObject m = c.getAsJsonObject();
                String n = str(m, "name");
                if (n != null && "Director".equals(str(m, "job")) && !directors.contains(n)) directors.add(n);
            }
        }
        String company = null;
        if (movie.has("production_companies") && movie.get("production_companies").isJsonArray()
                && !movie.getAsJsonArray("production_companies").isEmpty()) {
            company = str(movie.getAsJsonArray("production_companies").get(0).getAsJsonObject(), "name");
        }
        return new Details(genres.isEmpty() ? null : String.join(",", genres), runtime,
                directors.isEmpty() ? null : String.join(", ", directors), company);
    }

    /** Top-billed cast and director(s) from an append_to_response=credits payload. */
    static List<String> creditNames(JsonObject movie) {
        List<String> names = new ArrayList<>();
        if (!movie.has("credits") || !movie.get("credits").isJsonObject()) return names;
        JsonObject credits = movie.getAsJsonObject("credits");
        if (credits.has("cast")) {
            for (JsonElement c : credits.getAsJsonArray("cast")) {
                if (names.size() >= CONTEXT_CAST) break;
                String n = str(c.getAsJsonObject(), "name");
                if (n != null) names.add(n);
            }
        }
        if (credits.has("crew")) {
            for (JsonElement c : credits.getAsJsonArray("crew")) {
                JsonObject m = c.getAsJsonObject();
                String n = str(m, "name");
                if (n != null && "Director".equals(str(m, "job")) && !names.contains(n)) names.add(n);
            }
        }
        return names;
    }

    private JsonObject get(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (bearerToken != null) {
                builder.uri(URI.create(url)).header("Authorization", "Bearer " + bearerToken);
            } else {
                builder.uri(URI.create(url + (url.contains("?") ? "&" : "?") + "api_key=" + apiKey));
            }
            HttpResponse<String> resp;
            try {
                resp = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(1000L * (attempt + 1));
                    continue;
                }
                throw e;
            }
            if ((resp.statusCode() == 429 || resp.statusCode() >= 500) && attempt < MAX_RETRIES) {
                long waitMs = resp.headers().firstValueAsLong("Retry-After").orElse(attempt + 1L) * 1000;
                Thread.sleep(waitMs);
                continue;
            }
            if (resp.statusCode() != 200) {
                String body = resp.body();
                throw new Exception("TMDB HTTP " + resp.statusCode() + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
            }
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? blankToNull(o.get(key).getAsString()) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
