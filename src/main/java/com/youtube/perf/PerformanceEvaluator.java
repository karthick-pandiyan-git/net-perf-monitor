package com.youtube.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Evaluates each {@link VideoMetrics} snapshot for internet connection quality.
 *
 * The 5 checks that directly reflect network health:
 *   1. TTFB                     - server latency + CDN routing
 *   2. Page Load Time           - combined bandwidth + latency
 *   3. Stream Buffer Depth      - sustained throughput health
 *   4. Avg Streaming BW         - live throughput measurement over 30 s
 *   5. Video Quality Stability  - did YouTube's ABR degrade quality during playback?
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
     * Buffer depth: seconds of video pre-loaded beyond the current playhead.
     * < 10 s means the connection is barely keeping up with real-time playback.
     */
    private static final double MIN_BUFFERED_SECS = 10.0;

    /**
     * Minimum connection speed (in Kbps) as reported by YouTube's Stats for Nerds panel.
     * 2500 Kbps = 2.5 Mbps, the minimum YouTube recommends for sustained HD (720p) streaming.
     */
    private static final double MIN_SFN_CONNECTION_SPEED_KBPS = 2500.0;


    /**
     * Minimum average bandwidth across the 30-second monitoring window.
     * 250 KB/s ≈ 2 Mbps — minimum for consistent HD streaming.
     */
    private static final double MIN_AVG_BANDWIDTH_KBPS = 250.0;

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

        // 1. TTFB — server latency; Tab 1 gets cold-connection allowance
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

        // ── Stats for Nerds checks (8–10) — only when panel data was captured ──
        StatsForNerdsData sfn = m.getSfnData();
        if (sfn != null && sfn.isAvailable()) {

            // 8. SFN Connection Speed — YouTube's own ABR throughput measurement.
            //    Provides a second opinion on bandwidth that is independent of the
            //    resource-timing API estimate used in check 6.
            if (sfn.getConnectionSpeedKbps() >= 0) {
                boolean speedOk = sfn.getConnectionSpeedKbps() >= MIN_SFN_CONNECTION_SPEED_KBPS;
                checks.add(check(
                    "SFN Connection Speed",
                    ">= " + (int) MIN_SFN_CONNECTION_SPEED_KBPS + " Kbps (HD minimum)",
                    String.format("%.0f Kbps (%.1f Mbps)",
                        sfn.getConnectionSpeedKbps(),
                        sfn.getConnectionSpeedKbps() / 1000.0),
                    speedOk
                ));
            }

            // 9. SFN Buffer Health — FAIL only when any monitoring sweep recorded 0 s,
            //    which means the buffer was fully drained (imminent stall).
            //    Transient dips above 0 are acceptable; only a genuine empty-buffer
            //    event during playback is actionable.
            if (sfn.getBufferHealthSecs() >= 0) {
                boolean anyZero = m.getNetworkSamples().stream()
                    .anyMatch(s -> s.getBufferHealthSecs() == 0.0);
                boolean sfnBufOk = !anyZero;
                String actual = anyZero
                    ? "0 s hit (buffer drained)"
                    : String.format("%.2f s avg (min > 0)", sfn.getBufferHealthSecs());
                checks.add(check(
                    "SFN Buffer Health",
                    "never 0 s during sweeps",
                    actual,
                    sfnBufOk
                ));
            }

            // 10. SFN Resolution Match — current resolution should equal the optimal
            //     resolution YouTube calculated for this connection.  A mismatch means
            //     the ABR engine served a lower tier than the connection can sustain,
            //     which can signal an unstable throughput during the ramp-up period.
            if (sfn.getCurrentResolution() != null && sfn.getOptimalResolution() != null) {
                boolean resOk = !sfn.isResolutionBelowOptimal();
                String resActual = sfn.getCurrentResolution() + " / optimal: " + sfn.getOptimalResolution();
                checks.add(check(
                    "SFN Resolution Match",
                    "current == optimal",
                    resActual,
                    resOk
                ));
            }
        }

        // ── Statistical checks (11–12) — sweep-sample based, adaptive to run length ──
        addStatisticalChecks(checks, samples);

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

    // ── Statistical checks (added when ≥ 3 sweep samples are available) ───────

    /**
     * Adds statistical pass/fail checks derived from the buffer and frame-drop sweep
     * samples collected during the monitoring window.
     *
     * <p>Two checks are produced:</p>
     * <ul>
     *   <li><b>Buffer Depth Consistency</b> — the lower confidence bound
     *       (mean − 1σ) of per-sweep buffer readings must remain above
     *       {@link #MIN_BUFFERED_SECS}. A result below the threshold means the
     *       buffer occasionally dipped dangerously low during playback.</li>
     *   <li><b>Frame Drop (Catastrophic)</b> — fails only when any single sweep
     *       recorded 100% frame drops, indicating a complete playback stall.</li>
     * </ul>
     *
     * @param checks  list to append results to
     * @param samples per-sweep network samples for this tab
     */
    void addStatisticalChecks(List<CheckResult> checks, List<NetworkSample> samples) {
        if (samples == null || samples.size() < StatisticsEngine.MIN_USABLE_SAMPLES) return;

        // ── Buffer depth consistency ──────────────────────────────────────────
        List<Double> bufValues = samples.stream()
            .filter(s -> !s.isAdPlaying())
            .map(NetworkSample::getVideoBuffered)
            .collect(Collectors.toList());

        StatisticsEngine.Stats bufStats = StatisticsEngine.compute(bufValues);
        if (bufStats != null) {
            // Lower bound = mean - 1σ.  If this dips below MIN_BUFFERED_SECS the
            // buffer was sometimes insufficient even if the average looks fine.
            double lowerBound = bufStats.lcl(1.0);
            boolean bufConsistent = lowerBound >= MIN_BUFFERED_SECS;
            String sampleNote = bufStats.isReliable() ? "" : "  [limited n=" + bufStats.n + "]";
            checks.add(check(
                "Buffer Depth Consistency",
                String.format("μ−1σ ≥ %.0f s (never dips low)", MIN_BUFFERED_SECS),
                String.format("μ=%.1f s  σ=%.1f s  μ−1σ=%.1f s%s",
                    bufStats.mean, bufStats.stddev, lowerBound, sampleNote),
                bufConsistent
            ));
        }

        // ── Frame drop rate consistency ───────────────────────────────────────
        // Compute per-sweep drop % from the cumulative SFN counters stored on each
        // sample, then evaluate the mean and spread against the 1% quality threshold.
        List<Double> dropPctValues = new ArrayList<>();
        for (int si = 0; si < samples.size(); si++) {
            NetworkSample s = samples.get(si);
            if (s.getDroppedFrames() < 0 || s.getTotalFrames() <= 0) continue;
            if (si == 0) {
                dropPctValues.add(s.getTotalFrames() > 0
                    ? 100.0 * s.getDroppedFrames() / s.getTotalFrames() : null);
            } else {
                NetworkSample prev = samples.get(si - 1);
                long dDrop  = s.getDroppedFrames() - (prev.getDroppedFrames() >= 0 ? prev.getDroppedFrames() : 0);
                long dTotal = s.getTotalFrames()   - (prev.getTotalFrames()   >  0 ? prev.getTotalFrames()   : 0);
                if (dTotal > 0) dropPctValues.add(100.0 * dDrop / dTotal);
            }
        }
        if (!dropPctValues.isEmpty()) {
            // Catastrophic check: any single sweep where ALL frames were dropped.
            // Normal drop rates (even elevated ones) are device/codec artefacts and
            // are not indicative of a network problem; only a 100% sweep signals a
            // complete playback stall that is actionable.
            boolean anyTotal100 = dropPctValues.stream().anyMatch(v -> v >= 100.0);
            checks.add(check(
                "Frame Drop (Catastrophic)",
                "no sweep at 100%",
                anyTotal100
                    ? "100% drop detected in a sweep"
                    : "no 100% sweep  (n=" + dropPctValues.size() + ")",
                !anyTotal100
            ));
        }
    }
}
