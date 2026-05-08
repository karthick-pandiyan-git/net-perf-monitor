package com.youtube.perf;

/**
 * Page-load and TTFB thresholds for a single website.
 *
 * <p>Cold thresholds apply to cycle 1 (the first navigation, which has no
 * browser cache and requires a full TCP/TLS handshake). Warm thresholds apply
 * to cycles 2+ where connections are already established and resources may be
 * cached.</p>
 *
 * <p>The default values match the historical static constants used before
 * per-site configuration was introduced. Override them per domain in
 * {@code website-thresholds.properties} using keys of the form:</p>
 * <pre>
 *   website.threshold.&lt;domain&gt;.pageload.warm.ms=8000
 *   website.threshold.&lt;domain&gt;.pageload.cold.ms=12000
 *   website.threshold.&lt;domain&gt;.ttfb.warm.ms=1200
 *   website.threshold.&lt;domain&gt;.ttfb.cold.ms=2000
 * </pre>
 */
public class WebsiteThresholds {

    /**
     * Default thresholds applied to all websites that do not have a per-site
     * override configured in {@code website-thresholds.properties}.
     */
    public static final WebsiteThresholds DEFAULT =
        new WebsiteThresholds(5_000, 8_000, 1_200, 2_000);

    /** Page-load threshold in milliseconds for warm cycles (cycle 2+). */
    public final long pageLoadWarmMs;
    /** Page-load threshold in milliseconds for the cold first cycle. */
    public final long pageLoadColdMs;
    /** TTFB threshold in milliseconds for warm cycles (cycle 2+). */
    public final long ttfbWarmMs;
    /** TTFB threshold in milliseconds for the cold first cycle. */
    public final long ttfbColdMs;

    public WebsiteThresholds(long pageLoadWarmMs, long pageLoadColdMs,
                              long ttfbWarmMs,     long ttfbColdMs) {
        this.pageLoadWarmMs = pageLoadWarmMs;
        this.pageLoadColdMs = pageLoadColdMs;
        this.ttfbWarmMs     = ttfbWarmMs;
        this.ttfbColdMs     = ttfbColdMs;
    }
}
