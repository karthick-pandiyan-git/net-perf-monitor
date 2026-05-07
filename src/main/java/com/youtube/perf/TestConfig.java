package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Loads {@code test.properties} from the classpath and exposes the configured
 * YouTube URLs, website URLs, DNS domains, and monitoring duration.
 *
 * <p>The monitoring duration can be overridden at runtime with the
 * {@code --duration <seconds>} command-line flag; the property file value
 * is used as the default when the flag is absent.
 */
public class TestConfig {

    private static final Logger logger = LoggerFactory.getLogger(TestConfig.class);

    private static final String PROPERTIES_FILE  = "test.properties";
    private static final int    MIN_DURATION_SECS = 30;

    /** Probe names recognised by {@code --mode} (after normalising {@code youtube_N} → "youtube"). */
    private static final Set<String> VALID_PROBES = Set.of("youtube", "website", "dns");

    /** Default number of YouTube tabs when no explicit count is given (e.g. {@code --mode all}). */
    private static final int DEFAULT_YOUTUBE_COUNT = 2;

    private final List<String> youtubeUrls;
    private final List<String> websiteUrls;
    private final List<String> dnsDomains;
    private final int          durationSeconds;
    /**
     * Which probes are active. Contains one or more of "youtube", "website", "dns".
     * Defaults to all three when {@code --mode} is not supplied.
     */
    private final Set<String>  mode;
    /**
     * Number of YouTube URLs (tabs) to actually stream.  The full list in
     * {@code test.properties} may be longer; only the first {@code youtubeCount}
     * entries are used.  Set via {@code youtube_N} in {@code --mode}; defaults
     * to {@value DEFAULT_YOUTUBE_COUNT} when the count suffix is omitted.
     */
    private final int          youtubeCount;
    /**
     * Human-readable mode label that preserves the original {@code youtube_N}
     * token (e.g. {@code "dns,website,youtube_10"}) so reports reflect the
     * actual number of streams rather than just "youtube".
     */
    private final String       modeLabel;
    /**
     * How often (in seconds) to print an intermediate JSON snapshot during the
     * monitoring window. {@code -1} means no periodic reporting — only the
     * final snapshot is printed.
     */
    private final int          reportIntervalSeconds;
    /** Default page-load and TTFB thresholds used for any site without a specific override. */
    private final WebsiteThresholds            defaultThresholds;
    /** Per-domain threshold overrides keyed by bare hostname (e.g. {@code "americanexpress.com"}). */
    private final Map<String, WebsiteThresholds> siteThresholds;

    private TestConfig(List<String> youtubeUrls,
                       List<String> websiteUrls,
                       List<String> dnsDomains,
                       int durationSeconds,
                       Set<String> mode,
                       int youtubeCount,
                       String modeLabel,
                       int reportIntervalSeconds,
                       WebsiteThresholds defaultThresholds,
                       Map<String, WebsiteThresholds> siteThresholds) {
        this.youtubeUrls           = youtubeUrls;
        this.websiteUrls           = websiteUrls;
        this.dnsDomains            = dnsDomains;
        this.durationSeconds       = durationSeconds;
        this.mode                  = Collections.unmodifiableSet(mode);
        this.youtubeCount          = youtubeCount;
        this.modeLabel             = modeLabel;
        this.reportIntervalSeconds = reportIntervalSeconds;
        this.defaultThresholds     = defaultThresholds;
        this.siteThresholds        = Collections.unmodifiableMap(siteThresholds);
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Loads {@code test.properties} from the classpath and applies any
     * {@code --duration} override found in {@code args}.
     *
     * @param args command-line arguments from {@code main(String[] args)}
     * @return fully populated {@link TestConfig}
     * @throws IOException if the properties file cannot be found or read
     */
    public static TestConfig load(String[] args) throws IOException {
        Properties props = new Properties();
        try (InputStream is = TestConfig.class.getClassLoader()
                .getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IOException("Cannot find '" + PROPERTIES_FILE
                    + "' on the classpath. Make sure it exists under src/main/resources/.");
            }
            props.load(is);
        }

        List<String> allYoutubeUrls = loadNumberedList(props, "youtube.url.");
        List<String> websiteUrls     = loadNumberedList(props, "website.url.");
        List<String> dnsDomains      = loadNumberedList(props, "dns.domain.");

        // Parse default duration from properties file
        int defaultDuration = parseIntProp(props, "test.duration.seconds", 30);
        int durationSeconds = clampDuration(defaultDuration, "test.properties");

        // --duration CLI flag overrides the property file value
        durationSeconds = applyCliDuration(args, durationSeconds);

        // --mode CLI flag selects which probes to run (default: all three)
        // youtube_N variant is normalised to "youtube" in the mode set; the N
        // is extracted separately and caps the YouTube URL list.
        Set<String> mode         = parseMode(args);
        int         youtubeCount = parseYoutubeCount(args, allYoutubeUrls.size());
        String      modeLabel    = buildModeLabel(mode, youtubeCount);

        // Trim the YouTube list to the requested count
        List<String> youtubeUrls = mode.contains("youtube")
            ? allYoutubeUrls.subList(0, Math.min(youtubeCount, allYoutubeUrls.size()))
            : allYoutubeUrls;

        // --report CLI flag sets the periodic snapshot interval (-1 = disabled)
        int reportIntervalSeconds = parseReportInterval(args);

        // If report interval exceeds duration, cap it to duration
        if (reportIntervalSeconds > 0 && reportIntervalSeconds > durationSeconds) {
            logger.warn("--report value {}s exceeds duration {}s — capping report interval to {}s",
                reportIntervalSeconds, durationSeconds, durationSeconds);
            reportIntervalSeconds = durationSeconds;
        }

        // Load website thresholds (defaults + per-site overrides)
        WebsiteThresholds defaultThresholds = loadDefaultThresholds(props);
        Map<String, WebsiteThresholds> siteThresholds = loadSiteThresholds(props, defaultThresholds);
        if (!siteThresholds.isEmpty()) {
            logger.info("Loaded per-site threshold overrides for: {}", siteThresholds.keySet());
        }

        // Build a log summary that only mentions probes included in the active mode.
        List<String> loadedParts = new java.util.ArrayList<>();
        if (mode.contains("youtube")) loadedParts.add(youtubeUrls.size() + " YouTube URL(s)");
        if (mode.contains("website")) loadedParts.add(websiteUrls.size() + " website URL(s)");
        if (mode.contains("dns"))     loadedParts.add(dnsDomains.size()  + " DNS domain(s)");
        logger.info("Loaded {}; duration={}s; mode={}; report={}",
            String.join(", ", loadedParts), durationSeconds, modeLabel,
            reportIntervalSeconds < 0 ? "disabled" : reportIntervalSeconds + "s");

        return new TestConfig(youtubeUrls, websiteUrls, dnsDomains, durationSeconds, mode,
                              youtubeCount, modeLabel, reportIntervalSeconds,
                              defaultThresholds, siteThresholds);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the YouTube video URLs to stream (already trimmed to
     * {@link #getYoutubeCount()} entries).
     */
    public List<String> getYoutubeUrls()  { return youtubeUrls;     }

    /** Returns the list of website URLs loaded from the properties file. */
    public List<String> getWebsiteUrls()  { return websiteUrls;     }

    /** Returns the list of DNS domain names to query during the monitoring window. */
    public List<String> getDnsDomains()   { return dnsDomains;      }

    /** Returns the monitoring window duration in seconds (minimum {@code 30}). */
    public int          getDurationSeconds() { return durationSeconds; }

    /**
     * Returns the set of active probe names.
     * Contains one or more of {@code "youtube"}, {@code "website"}, {@code "dns"}.
     */
    public Set<String>  getMode()         { return mode; }

    /**
     * Returns the number of YouTube tabs to open.  The YouTube URL list returned
     * by {@link #getYoutubeUrls()} is already capped to this count.
     */
    public int getYoutubeCount()          { return youtubeCount; }

    /**
     * Returns the display-friendly mode label, preserving any {@code youtube_N}
     * token so reports show the actual stream count (e.g. {@code "youtube_10,website,dns"}).
     */
    public String getModeLabel()          { return modeLabel; }

    /**
     * Returns the periodic snapshot interval in seconds, or {@code -1} when
     * periodic reporting is disabled.
     */
    public int getReportIntervalSeconds() { return reportIntervalSeconds; }
    /**
     * Returns the {@link WebsiteThresholds} to apply for the given domain.
     * Falls back to the configured defaults when no site-specific override exists.
     *
     * @param domain the bare hostname (e.g. {@code "americanexpress.com"}), or
     *               {@code null} to retrieve the defaults directly
     * @return the thresholds to use for this domain
     */
    public WebsiteThresholds getThresholds(String domain) {
        if (domain == null) return defaultThresholds;
        return siteThresholds.getOrDefault(domain, defaultThresholds);
    }
    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Collects all values whose key matches {@code prefix + N} for N = 1, 2, …
     * until a key is missing. Blank / null values are silently skipped.
     */
    private static List<String> loadNumberedList(Properties props, String prefix) {
        List<String> list = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = props.getProperty(prefix + i);
            if (value == null) break;
            value = value.trim();
            if (!value.isEmpty()) list.add(value);
        }
        return list;
    }

    /**
     * Reads the four {@code website.threshold.default.*} properties and returns
     * a {@link WebsiteThresholds} with those values (falling back to
     * {@link WebsiteThresholds#DEFAULT} for any missing key).
     */
    private static WebsiteThresholds loadDefaultThresholds(Properties props) {
        String pfx = "website.threshold.default.";
        long plWarm = parseIntProp(props, pfx + "pageload.warm.ms", (int) WebsiteThresholds.DEFAULT.pageLoadWarmMs);
        long plCold = parseIntProp(props, pfx + "pageload.cold.ms", (int) WebsiteThresholds.DEFAULT.pageLoadColdMs);
        long ttWarm = parseIntProp(props, pfx + "ttfb.warm.ms",     (int) WebsiteThresholds.DEFAULT.ttfbWarmMs);
        long ttCold = parseIntProp(props, pfx + "ttfb.cold.ms",     (int) WebsiteThresholds.DEFAULT.ttfbColdMs);
        return new WebsiteThresholds(plWarm, plCold, ttWarm, ttCold);
    }

    /**
     * Scans all properties for keys of the form
     * {@code website.threshold.&lt;domain&gt;.&lt;metric&gt;.ms} (excluding the
     * {@code default.} namespace) and builds a map of domain → override thresholds.
     * Any of the four metric values may be absent; missing values fall back to
     * the supplied {@code defaults}.
     */
    private static Map<String, WebsiteThresholds> loadSiteThresholds(
            Properties props, WebsiteThresholds defaults) {
        String prefix   = "website.threshold.";
        String[] suffixes = {
            ".pageload.warm.ms", ".pageload.cold.ms",
            ".ttfb.warm.ms",     ".ttfb.cold.ms"
        };

        // Discover all domain names that have at least one threshold property.
        Set<String> domains = new LinkedHashSet<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            if (rest.startsWith("default.")) continue;  // handled by loadDefaultThresholds
            for (String suffix : suffixes) {
                if (rest.endsWith(suffix)) {
                    domains.add(rest.substring(0, rest.length() - suffix.length()));
                    break;
                }
            }
        }

        Map<String, WebsiteThresholds> map = new LinkedHashMap<>();
        for (String domain : domains) {
            String dp   = prefix + domain + ".";
            long plWarm = parseIntProp(props, dp + "pageload.warm.ms", (int) defaults.pageLoadWarmMs);
            long plCold = parseIntProp(props, dp + "pageload.cold.ms", (int) defaults.pageLoadColdMs);
            long ttWarm = parseIntProp(props, dp + "ttfb.warm.ms",     (int) defaults.ttfbWarmMs);
            long ttCold = parseIntProp(props, dp + "ttfb.cold.ms",     (int) defaults.ttfbColdMs);
            map.put(domain, new WebsiteThresholds(plWarm, plCold, ttWarm, ttCold));
        }
        return map;
    }

    /** Parses an integer property, returning {@code defaultValue} on parse error. */
    private static int parseIntProp(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value for '{}': '{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Enforces the minimum duration and logs a warning if the value was below it.
     *
     * @param source a human-readable label for the warning (e.g. "test.properties" or "--duration")
     */
    private static int clampDuration(int seconds, String source) {
        if (seconds < MIN_DURATION_SECS) {
            logger.warn("Duration {}s from {} is below minimum {}s — using {}s",
                seconds, source, MIN_DURATION_SECS, MIN_DURATION_SECS);
            return MIN_DURATION_SECS;
        }
        return seconds;
    }

    /**
     * Scans {@code args} for {@code --report <value>} and returns the interval in
     * seconds. Returns {@code -1} (disabled) when the flag is absent, zero, or
     * negative.
     */
    private static int parseReportInterval(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--report".equalsIgnoreCase(args[i])) {
                try {
                    int v = Integer.parseInt(args[i + 1]);
                    if (v <= 0) {
                        logger.warn("--report value must be > 0 — periodic reporting disabled");
                        return -1;
                    }
                    return v;
                } catch (NumberFormatException e) {
                    logger.warn("Invalid --report value '{}' — periodic reporting disabled", args[i + 1]);
                }
            }
        }
        return -1;  // not specified — no periodic reporting
    }

    /**
     * Scans {@code args} for {@code --duration <value>} and returns the clamped
     * value if found, otherwise returns {@code currentDuration} unchanged.
     */
    private static int applyCliDuration(String[] args, int currentDuration) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--duration".equalsIgnoreCase(args[i])) {
                try {
                    int requested = Integer.parseInt(args[i + 1]);
                    return clampDuration(requested, "--duration");
                } catch (NumberFormatException e) {
                    logger.warn("Invalid --duration value '{}' — using {}s", args[i + 1], currentDuration);
                }
            }
        }
        return currentDuration;
    }

    /**
     * Scans {@code args} for {@code --mode <value>} and returns the parsed set of
     * probe names. {@code <value>} may be {@code "all"} or a comma-separated
     * combination of {@code "youtube"}, {@code "website"}, and {@code "dns"}.
     * A {@code youtube_N} token (e.g. {@code youtube_10}) is accepted and
     * normalised to {@code "youtube"} in the returned set; the count is extracted
     * separately by {@link #parseYoutubeCount}.
     * Defaults to all three probes when the flag is absent or invalid.
     *
     * <p>Examples:</p>
     * <pre>
     *   --mode all                  → youtube, website, dns
     *   --mode youtube              → youtube
     *   --mode youtube_10           → youtube  (10 tabs)
     *   --mode youtube_10,website,dns → youtube, website, dns  (10 tabs)
     *   --mode website,dns          → website, dns
     * </pre>
     */
    private static Set<String> parseMode(String[] args) {
        Set<String> all = new LinkedHashSet<>(Arrays.asList("youtube", "website", "dns"));
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equalsIgnoreCase(args[i])) {
                String raw = args[i + 1].toLowerCase().trim();
                if ("all".equals(raw)) {
                    return all;
                }
                Set<String> parsed = new LinkedHashSet<>();
                for (String token : raw.split(",")) {
                    String p = token.trim();
                    // Accept "youtube_N" as a valid youtube probe token
                    if (p.startsWith("youtube_")) {
                        parsed.add("youtube");
                    } else if (VALID_PROBES.contains(p)) {
                        parsed.add(p);
                    } else {
                        logger.warn("Unknown probe '{}' in --mode — ignored. "
                            + "Valid: youtube, youtube_N, website, dns, all", p);
                    }
                }
                if (!parsed.isEmpty()) {
                    return parsed;
                }
                logger.warn("--mode '{}' contained no valid probes — running all three", args[i + 1]);
                return all;
            }
        }
        return all;  // default: all probes
    }

    /**
     * Extracts the YouTube tab count from a {@code youtube_N} token in
     * {@code --mode}.  Returns {@link #DEFAULT_YOUTUBE_COUNT} when:
     * <ul>
     *   <li>no {@code --mode} flag is supplied (i.e. default "all" mode), or</li>
     *   <li>the mode contains {@code "youtube"} without a count suffix.</li>
     * </ul>
     * The returned count is capped to {@code availableCount} so we never request
     * more tabs than there are URLs in {@code test.properties}.
     *
     * @param args           command-line arguments from {@code main}
     * @param availableCount total number of YouTube URLs in the properties file
     * @return the number of YouTube tabs to open
     */
    private static int parseYoutubeCount(String[] args, int availableCount) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equalsIgnoreCase(args[i])) {
                String raw = args[i + 1].toLowerCase().trim();
                for (String token : raw.split(",")) {
                    String p = token.trim();
                    if (p.startsWith("youtube_")) {
                        String numPart = p.substring("youtube_".length());
                        try {
                            int n = Integer.parseInt(numPart);
                            if (n < 1) {
                                logger.warn("youtube_N count must be >= 1 — using default {}", DEFAULT_YOUTUBE_COUNT);
                                return DEFAULT_YOUTUBE_COUNT;
                            }
                            if (n > availableCount) {
                                logger.warn("youtube_{} exceeds available {} URL(s) — capping to {}",
                                    n, availableCount, availableCount);
                                return availableCount;
                            }
                            return n;
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid youtube_N token '{}' — using default {}", p, DEFAULT_YOUTUBE_COUNT);
                        }
                    }
                }
            }
        }
        // No youtube_N found — use default (2 for "all" / plain "youtube")
        return DEFAULT_YOUTUBE_COUNT;
    }

    /**
     * Builds a human-readable mode label that replaces the plain {@code "youtube"}
     * token with {@code "youtube_N"} so reports show the actual stream count.
     *
     * <p>Examples:</p>
     * <pre>
     *   mode={youtube,website,dns}, count=2  → "youtube_2,website,dns"
     *   mode={youtube},             count=10 → "youtube_10"
     *   mode={website,dns},         count=2  → "website,dns"
     * </pre>
     */
    private static String buildModeLabel(Set<String> mode, int youtubeCount) {
        return mode.stream()
            .sorted()
            .map(p -> "youtube".equals(p) ? "youtube_" + youtubeCount : p)
            .collect(java.util.stream.Collectors.joining(","));
    }
}

