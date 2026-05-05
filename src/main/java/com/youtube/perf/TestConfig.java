package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int    MAX_DURATION_SECS = 900;  // 15 minutes

    /** Probe names recognised by {@code --mode}. */
    private static final Set<String> VALID_PROBES = Set.of("youtube", "website", "dns");

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
     * How often (in seconds) to print an intermediate JSON snapshot during the
     * monitoring window. {@code -1} means no periodic reporting — only the
     * final snapshot is printed.
     */
    private final int          reportIntervalSeconds;

    private TestConfig(List<String> youtubeUrls,
                       List<String> websiteUrls,
                       List<String> dnsDomains,
                       int durationSeconds,
                       Set<String> mode,
                       int reportIntervalSeconds) {
        this.youtubeUrls           = youtubeUrls;
        this.websiteUrls           = websiteUrls;
        this.dnsDomains            = dnsDomains;
        this.durationSeconds       = durationSeconds;
        this.mode                  = Collections.unmodifiableSet(mode);
        this.reportIntervalSeconds = reportIntervalSeconds;
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

        List<String> youtubeUrls = loadNumberedList(props, "youtube.url.");
        List<String> websiteUrls = loadNumberedList(props, "website.url.");
        List<String> dnsDomains  = loadNumberedList(props, "dns.domain.");

        // Parse default duration from properties file
        int defaultDuration = parseIntProp(props, "test.duration.seconds", 30);
        int durationSeconds = clampDuration(defaultDuration, "test.properties");

        // --duration CLI flag overrides the property file value
        durationSeconds = applyCliDuration(args, durationSeconds);

        // --mode CLI flag selects which probes to run (default: all three)
        Set<String> mode = parseMode(args);

        // --report CLI flag sets the periodic snapshot interval (-1 = disabled)
        int reportIntervalSeconds = parseReportInterval(args);

        // Build a log summary that only mentions probes included in the active mode.
        List<String> loadedParts = new java.util.ArrayList<>();
        if (mode.contains("youtube")) loadedParts.add(youtubeUrls.size() + " YouTube URL(s)");
        if (mode.contains("website")) loadedParts.add(websiteUrls.size() + " website URL(s)");
        if (mode.contains("dns"))     loadedParts.add(dnsDomains.size()  + " DNS domain(s)");
        logger.info("Loaded {}; duration={}s; mode={}; report={}",
            String.join(", ", loadedParts), durationSeconds, mode,
            reportIntervalSeconds < 0 ? "disabled" : reportIntervalSeconds + "s");

        return new TestConfig(youtubeUrls, websiteUrls, dnsDomains, durationSeconds, mode, reportIntervalSeconds);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns the list of YouTube video URLs loaded from the properties file. */
    public List<String> getYoutubeUrls()  { return youtubeUrls;     }

    /** Returns the list of website URLs loaded from the properties file. */
    public List<String> getWebsiteUrls()  { return websiteUrls;     }

    /** Returns the list of DNS domain names to query during the monitoring window. */
    public List<String> getDnsDomains()   { return dnsDomains;      }

    /** Returns the monitoring window duration in seconds (clamped to [{@code 30}, {@code 900}]). */
    public int          getDurationSeconds() { return durationSeconds; }

    /**
     * Returns the set of active probe names.
     * Contains one or more of {@code "youtube"}, {@code "website"}, {@code "dns"}.
     */
    public Set<String>  getMode()         { return mode; }

    /**
     * Returns the periodic snapshot interval in seconds, or {@code -1} when
     * periodic reporting is disabled.
     */
    public int getReportIntervalSeconds() { return reportIntervalSeconds; }

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
     * Clamps duration to [MIN, MAX] and logs a warning if the value was out of range.
     *
     * @param source a human-readable label for the warning (e.g. "test.properties" or "--duration")
     */
    private static int clampDuration(int seconds, String source) {
        if (seconds < MIN_DURATION_SECS) {
            logger.warn("Duration {}s from {} is below minimum {}s — using {}s",
                seconds, source, MIN_DURATION_SECS, MIN_DURATION_SECS);
            return MIN_DURATION_SECS;
        }
        if (seconds > MAX_DURATION_SECS) {
            logger.warn("Duration {}s from {} exceeds maximum {}s — using {}s",
                seconds, source, MAX_DURATION_SECS, MAX_DURATION_SECS);
            return MAX_DURATION_SECS;
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
     * Defaults to all three probes when the flag is absent or invalid.
     *
     * <p>Examples:</p>
     * <pre>
     *   --mode all               → youtube, website, dns
     *   --mode youtube           → youtube
     *   --mode website,dns       → website, dns
     *   --mode youtube,website   → youtube, website
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
                    if (VALID_PROBES.contains(p)) {
                        parsed.add(p);
                    } else {
                        logger.warn("Unknown probe '{}' in --mode — ignored. Valid: youtube, website, dns, all", p);
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
}
