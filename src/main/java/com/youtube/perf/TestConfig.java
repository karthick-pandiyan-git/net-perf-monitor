package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    private final List<String> youtubeUrls;
    private final List<String> websiteUrls;
    private final List<String> dnsDomains;
    private final int          durationSeconds;

    private TestConfig(List<String> youtubeUrls,
                       List<String> websiteUrls,
                       List<String> dnsDomains,
                       int durationSeconds) {
        this.youtubeUrls     = youtubeUrls;
        this.websiteUrls     = websiteUrls;
        this.dnsDomains      = dnsDomains;
        this.durationSeconds = durationSeconds;
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

        logger.info("Loaded {} YouTube URL(s), {} website URL(s), {} DNS domain(s); duration={}s",
            youtubeUrls.size(), websiteUrls.size(), dnsDomains.size(), durationSeconds);

        return new TestConfig(youtubeUrls, websiteUrls, dnsDomains, durationSeconds);
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
}
