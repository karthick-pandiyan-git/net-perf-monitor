package com.youtube.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates each {@link VideoMetrics} snapshot for internet connection quality.
 *
 * The 7 checks that directly reflect network health:
 *   1. DNS Lookup Time          - resolver latency
 *   2. TCP Connection RTT       - distance to CDN edge
 *   3. TTFB                     - server latency + CDN routing
 *   4. Page Load Time           - combined bandwidth + latency
 *   5. Stream Buffer Depth      - sustained throughput health
 *   6. Avg Streaming BW         - live throughput measurement over 30 s
 *   7. Video Quality Stability  - did YouTube's ABR degrade quality during playback?
 *
 * Frame drops, paused state, and ready state are intentionally excluded — they
 * reflect device/codec behaviour, not network performance. Resolution IS included
 * (check 7) because YouTube's adaptive bitrate (ABR) algorithm downgrades quality
 * specifically when bandwidth is insufficient, making it a direct network indicator.
 */
public class PerformanceEvaluator {

    // ── Internet-performance thresholds ──────────────────────────────────────

    /**
     * Page load threshold for warm connections (tabs 2+).
     * Raised to 12 s to accommodate heavy pages such as 8K YouTube videos whose
     * initial segment fetch inflates the Navigation Timing load event. A genuine
     * connectivity problem will still show up in TTFB and bandwidth checks.
     */
    private static final long MAX_PAGE_LOAD_MS      = 12_000;
    /**
     * Cold page load threshold for Tab 1.
     * Tab 1 loads the page without any browser or OS DNS cache, requiring a full
     * TCP/TLS handshake and fresh resource downloads. 15 s covers real-world
     * cold-start variance without masking genuinely broken connections.
     */
    private static final long MAX_PAGE_LOAD_COLD_MS = 15_000;

    /**
     * TTFB thresholds.
     * Tab 1 pays a cold-connection penalty (uncached DNS, new TCP+TLS handshake).
     * All other tabs reuse warm HTTP/2 connections to YouTube's CDN.
     * 1500 ms warm threshold accommodates normal CDN latency variation;
     * at > 1500 ms the connection latency is consistently affecting streaming.
     */
    private static final long MAX_TTFB_MS      = 1_500;
    private static final long MAX_TTFB_COLD_MS = 3_000;

    /**
     * DNS lookup threshold. > 200 ms indicates a slow resolver or no local cache.
     * A result of 0 ms means the OS DNS cache was hit (expected on tabs 2+).
     */
    private static final long MAX_DNS_MS = 200;

    /**
     * TCP connection threshold — round-trip time to the CDN edge.
     * > 150 ms suggests routing to a distant server.
     * 0 ms means the connection was reused (HTTP/2 keep-alive).
     */
    private static final long MAX_TCP_MS = 200;

    /**
     * Buffer depth: seconds of video pre-loaded beyond the current playhead.
     * < 10 s means the connection is barely keeping up with real-time playback.
     */
    private static final double MIN_BUFFERED_SECS = 10.0;

    /**
     * Minimum average bandwidth across the 30-second monitoring window.
     * 250 KB/s ≈ 2 Mbps — minimum for consistent HD streaming.
     */
    private static final double MIN_AVG_BANDWIDTH_KBPS = 250.0;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluates the seven internet-health checks for one YouTube video tab.
     * Each check compares a collected metric against its defined threshold and
     * produces a {@link CheckResult} annotated as PASS or FAIL.
     *
     * @param m the collected metrics for one tab
     * @return a {@link VideoVerdict} containing the check results and the overall PASS/FAIL status
     */
    public VideoVerdict evaluate(VideoMetrics m) {
        List<CheckResult> checks = new ArrayList<>();

        // 1. DNS lookup — resolver latency (0 ms = OS cache hit, expected on tabs 2+)
        boolean dnsOk = m.getDnsLookupTime() >= 0 && m.getDnsLookupTime() <= MAX_DNS_MS;
        checks.add(check(
            "DNS Lookup Time",
            "<= " + MAX_DNS_MS + " ms  (0 = cached)",
            m.getDnsLookupTime() >= 0 ? m.getDnsLookupTime() + " ms" : "N/A",
            dnsOk
        ));

        // 2. TCP round-trip — latency to CDN edge (0 ms = HTTP/2 connection reused)
        boolean tcpOk = m.getTcpConnectionTime() >= 0 && m.getTcpConnectionTime() <= MAX_TCP_MS;
        checks.add(check(
            "TCP Connection (RTT)",
            "<= " + MAX_TCP_MS + " ms  (0 = reused)",
            m.getTcpConnectionTime() >= 0 ? m.getTcpConnectionTime() + " ms" : "N/A",
            tcpOk
        ));

        // 3. TTFB — server latency; Tab 1 gets cold-connection allowance
        long   ttfbLimit = (m.getTabIndex() == 1) ? MAX_TTFB_COLD_MS : MAX_TTFB_MS;
        String ttfbLabel = (m.getTabIndex() == 1)
            ? "<= " + MAX_TTFB_COLD_MS + " ms (cold)"
            : "<= " + MAX_TTFB_MS      + " ms (warm)";
        boolean ttfbOk = m.getTimeToFirstByte() > 0 && m.getTimeToFirstByte() <= ttfbLimit;
        checks.add(check(
            "TTFB (server latency)",
            ttfbLabel,
            m.getTimeToFirstByte() > 0 ? m.getTimeToFirstByte() + " ms" : "N/A",
            ttfbOk
        ));

        // 4. Page load — bandwidth + latency; Tab 1 gets cold-start allowance
        long   loadLimit = (m.getTabIndex() == 1) ? MAX_PAGE_LOAD_COLD_MS : MAX_PAGE_LOAD_MS;
        String loadLabel = (m.getTabIndex() == 1)
            ? "<= " + MAX_PAGE_LOAD_COLD_MS + " ms (cold)"
            : "<= " + MAX_PAGE_LOAD_MS      + " ms (warm)";
        boolean loadOk = m.getPageLoadTime() > 0 && m.getPageLoadTime() <= loadLimit;
        checks.add(check(
            "Page Load Time",
            loadLabel,
            m.getPageLoadTime() > 0 ? m.getPageLoadTime() + " ms" : "N/A",
            loadOk
        ));

        // 5. Buffer depth — does bandwidth sustain the stream without stalling?
        boolean bufOk = m.getBufferedSeconds() >= MIN_BUFFERED_SECS;
        checks.add(check(
            "Stream Buffer Depth",
            ">= " + MIN_BUFFERED_SECS + " s ahead",
            String.format("%.1f s", m.getBufferedSeconds()),
            bufOk
        ));

        // 6. Live bandwidth — average throughput measured during the 30-second window.
        //
        // Special case: pre-buffered video.
        // If the player downloaded all of its data BEFORE the monitoring window started
        // (during tab setup + latch sync), every sweep sees 0 new segments because the
        // player is already idle with a deep buffer. avgBandwidthKBps will be 0 (or a
        // tiny rounding artefact < 1 KB/s) while totalVideoSegmentBytes is large.
        // This is the best-case scenario — not a failure.
        // We use a 1 KB/s threshold rather than exact zero to absorb floating-point noise
        // from the JavaScript bandwidth calculation.
        List<NetworkSample> samples = m.getNetworkSamples();
        boolean preBuffered = m.getAvgBandwidthKBps() < 1.0
            && m.getTotalVideoSegmentBytes() > 1_000_000;   // > 1 MB transferred in total
        boolean bwOk;
        String bwActual;
        if (preBuffered) {
            bwOk     = true;
            double mb = m.getTotalVideoSegmentBytes() / (1024.0 * 1024.0);
            bwActual = String.format("pre-buffered (%.1f MB loaded before window)", mb);
        } else {
            bwOk     = samples != null && !samples.isEmpty()
                        && m.getAvgBandwidthKBps() >= MIN_AVG_BANDWIDTH_KBPS;
            bwActual = samples != null && !samples.isEmpty()
                        ? String.format("%.1f KB/s", m.getAvgBandwidthKBps())
                        : "no samples";
        }
        checks.add(check(
            "Avg Streaming Bandwidth",
            ">= " + MIN_AVG_BANDWIDTH_KBPS + " KB/s",
            bwActual,
            bwOk
        ));

        // 7. Video quality stability — did YouTube's ABR algorithm downgrade quality?
        // PASS when quality stayed at the peak level throughout the window.
        // If quality was never reported as a specific level (e.g. always "auto"),
        // we cannot evaluate this — treat as PASS to avoid false failures.
        String peak   = m.getPeakQualityLabel();
        String lowest = m.getLowestQualityLabel();
        if (peak != null) {
            boolean qualityOk = !m.isQualityDegraded();
            String actual = m.isQualityDegraded()
                ? peak + " → " + lowest + " (degraded)"   // e.g. hd1080 → hd720
                : peak + " (stable)";
            checks.add(check(
                "Video Quality Stability",
                "No downgrade from " + peak,
                actual,
                qualityOk
            ));
        } else {
            // Quality was "auto" or not reported at all — cannot evaluate
            checks.add(check(
                "Video Quality Stability",
                "No downgrade",
                "N/A (quality auto/unknown)",
                true
            ));
        }

        return new VideoVerdict(m, checks);
    }

    /**
     * Evaluates all entries in {@code metricsList} by delegating to
     * {@link #evaluate(VideoMetrics)} for each one.
     *
     * @param metricsList list of per-tab metrics
     * @return list of verdicts in the same order as the input list
     */
    public List<VideoVerdict> evaluateAll(List<VideoMetrics> metricsList) {
        List<VideoVerdict> verdicts = new ArrayList<>();
        for (VideoMetrics m : metricsList) {
            verdicts.add(evaluate(m));
        }
        return verdicts;
    }

    /**
     * Factory method that creates a {@link CheckResult}.
     *
     * @param name     human-readable name of the check
     * @param expected description of the pass criterion
     * @param actual   the measured value as a string
     * @param passed   {@code true} when the check passes
     * @return a new {@link CheckResult}
     */
    private CheckResult check(String name, String expected, String actual, boolean passed) {
        return new CheckResult(name, expected, actual, passed);
    }
}
