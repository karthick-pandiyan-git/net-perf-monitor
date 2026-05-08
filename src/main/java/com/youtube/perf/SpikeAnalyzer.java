package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared spike-pattern analyser used by both the HTML and console reporters.
 *
 * <p>The same 4-step formula is applied to DNS response times and website
 * page-load times to distinguish random network noise from structurally
 * significant failure patterns.</p>
 *
 * <h3>Pass/Fail formula</h3>
 * <ol>
 *   <li>Identify spikes: metric &gt; μ + {@link #SPIKE_SIGMA_GATE}σ within each per-group baseline.
 *       <b>All</b> spikes are recorded for chart rendering.</li>
 *   <li><b>Hard-threshold gate</b>: only spikes that ALSO exceed the hard threshold
 *       ({@link #DNS_HARD_THRESHOLD_MS} for DNS, per-site page-load threshold for website)
 *       are "critical spikes" that participate in the cluster/periodic/FAIL logic.
 *       Spikes below the hard threshold are flagged for visualization only and cannot cause FAIL.</li>
 *   <li>Cluster burst: ≥ cluster threshold <b>critical</b> spikes in any {@link #CLUSTER_WINDOW_MS} ms window → <b>FAIL</b>.
 *       DNS uses {@link #CLUSTER_THRESHOLD_DNS}; website uses {@link #CLUSTER_THRESHOLD}.</li>
 *   <li>Periodicity: inter-spike CV &lt; {@link #PERIODIC_CV_MAX} and rate &gt; {@link #PERIODIC_RATE_MIN_PCT} % (critical only) → <b>FAIL</b>.</li>
 *   <li>High critical rate: spike rate &gt; {@link #WARN_RATE_PCT} % with no cluster/pattern → <b>WARN</b>.</li>
 *   <li>Otherwise: <b>PASS</b> — shown in charts, no pass/fail impact.</li>
 * </ol>
 *
 * All formula parameters are loaded from {@code statistics.properties} on the classpath.
 */
public final class SpikeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SpikeAnalyzer.class);

    /**
     * Sigma multiplier used to identify a spike: a measurement is a spike when it
     * exceeds {@code mean + SPIKE_SIGMA_GATE * stddev} of its per-group baseline.
     */
    public static final double SPIKE_SIGMA_GATE;

    /** Width (ms) of the sliding window used for cluster-burst detection. */
    public static final long   CLUSTER_WINDOW_MS;

    /** Minimum spikes inside {@link #CLUSTER_WINDOW_MS} before declaring a cluster burst (website probe). */
    public static final int    CLUSTER_THRESHOLD;

    /** Minimum spikes inside {@link #CLUSTER_WINDOW_MS} before declaring a cluster burst (DNS probe). */
    public static final int    CLUSTER_THRESHOLD_DNS;

    /** Maximum inter-spike CV to consider a pattern periodic. */
    public static final double PERIODIC_CV_MAX;

    /** Minimum spike rate (%) for the periodic gate to apply. */
    public static final double PERIODIC_RATE_MIN_PCT;

    /** Spike rate (%) above which we emit WARN even without pattern/cluster. */
    public static final double WARN_RATE_PCT;

    /**
     * DNS hard threshold (ms): a spike only counts toward cluster/periodic FAIL
     * when it also exceeds this absolute value.  Spikes below this are flagged
     * for visualization but do not influence the pass/fail verdict.
     */
    public static final long DNS_HARD_THRESHOLD_MS;

    /**
     * When true, website spike FAIL logic requires the spike to also exceed the
     * per-site page-load threshold (from website-thresholds.properties).
     */
    public static final boolean WEBSITE_USE_PAGELOAD_THRESHOLD;

    static {
        Properties cfg = loadConfig();
        SPIKE_SIGMA_GATE      = Double.parseDouble(cfg.getProperty("spike.sigma_gate",              "5.0"));
        CLUSTER_WINDOW_MS     = Long.parseLong(    cfg.getProperty("spike.cluster_window.ms",       "60000"));
        CLUSTER_THRESHOLD     = Integer.parseInt(  cfg.getProperty("spike.cluster_threshold.website", "5"));
        CLUSTER_THRESHOLD_DNS = Integer.parseInt(  cfg.getProperty("spike.cluster_threshold.dns",
                                                     cfg.getProperty("spike.cluster_threshold.website", "10")));
        PERIODIC_CV_MAX       = Double.parseDouble(cfg.getProperty("spike.periodic.cv_max",         "0.40"));
        PERIODIC_RATE_MIN_PCT = Double.parseDouble(cfg.getProperty("spike.periodic.rate_min_pct",   "1.0"));
        WARN_RATE_PCT         = Double.parseDouble(cfg.getProperty("spike.warn_rate_pct",           "5.0"));
        DNS_HARD_THRESHOLD_MS = Long.parseLong(    cfg.getProperty("spike.dns.hard_threshold.ms",   "500"));
        WEBSITE_USE_PAGELOAD_THRESHOLD = Boolean.parseBoolean(
            cfg.getProperty("spike.website.use_pageload_threshold", "true"));
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream is = SpikeAnalyzer.class.getClassLoader()
                .getResourceAsStream("statistics.properties")) {
            if (is != null) {
                p.load(is);
            } else {
                logger.warn("'statistics.properties' not found — using built-in spike-analysis defaults");
            }
        } catch (IOException e) {
            logger.warn("Failed to load 'statistics.properties': {} — using built-in defaults", e.getMessage());
        }
        return p;
    }

    private SpikeAnalyzer() {}

    // ── Result record ──────────────────────────────────────────────────────────

    /** Immutable result of one spike-pattern analysis run. */
    public static final class Result {
        /** Total spike events detected across all groups. */
        public final int    totalSpikes;
        /** Total samples analysed (queries or page loads). */
        public final int    totalSamples;
        /** Percentage of samples that were spikes (totalSpikes / totalSamples × 100). */
        public final double spikeRatePct;
        /** Maximum spikes found in any 60-second sliding window. */
        public final int    maxSpikeIn60s;
        /** True when {@link #maxSpikeIn60s} ≥ the applicable cluster threshold. */
        public final boolean hasCluster;
        /** Coefficient of variation of inter-spike intervals; -1 when fewer than 2 intervals. */
        public final double  interSpikeCV;
        /** True when CV &lt; threshold and rate &gt; 1 %. */
        public final boolean hasPeriodic;
        /** {@code "PASS"}, {@code "WARN"}, or {@code "FAIL"}. */
        public final String  verdict;
        /** Human-readable reason explaining the verdict. */
        public final String  reason;
        /** Sorted list of spike event timestamps (epoch ms). Empty when no spikes. */
        public final List<Long> spikeTsMs;
        /** Start timestamp (epoch ms) of the worst cluster window; 0 if no cluster was found. */
        public final long clusterWindowStartMs;
        /** End timestamp (epoch ms) of the worst cluster window; 0 if no cluster was found. */
        public final long clusterWindowEndMs;
        /** Lookup set derived from {@link #spikeTsMs} for O(1) membership checks. */
        private final Set<Long> spikeTsSet;

        Result(int totalSpikes, int totalSamples, double spikeRatePct,
               int maxSpikeIn60s, boolean hasCluster,
               double interSpikeCV, boolean hasPeriodic,
               String verdict, String reason,
               List<Long> spikeTsMs, long clusterWindowStartMs, long clusterWindowEndMs) {
            this.totalSpikes   = totalSpikes;
            this.totalSamples  = totalSamples;
            this.spikeRatePct  = spikeRatePct;
            this.maxSpikeIn60s = maxSpikeIn60s;
            this.hasCluster    = hasCluster;
            this.interSpikeCV  = interSpikeCV;
            this.hasPeriodic   = hasPeriodic;
            this.verdict       = verdict;
            this.reason        = reason;
            this.spikeTsMs     = spikeTsMs != null ? List.copyOf(spikeTsMs) : List.of();
            this.clusterWindowStartMs = clusterWindowStartMs;
            this.clusterWindowEndMs   = clusterWindowEndMs;
            this.spikeTsSet    = new HashSet<>(this.spikeTsMs);
        }

        /**
         * Returns {@code true} when the given epoch timestamp (ms) corresponds to a
         * detected spike event. O(1) lookup.
         */
        public boolean isSpikeAt(long timestampMs) { return spikeTsSet.contains(timestampMs); }

        /** True when the pattern warrants a hard failure (cluster burst or periodic). */
        public boolean isSignificantFailure() { return "FAIL".equals(verdict); }
        /** True when the pattern is elevated but not a hard failure. */
        public boolean isWarn()               { return "WARN".equals(verdict); }
        /** @deprecated INFO verdict is no longer emitted; always returns {@code false}. */
        @Deprecated
        public boolean isInfo()               { return false; }
    }

    // ── DNS analysis ───────────────────────────────────────────────────────────

    /**
     * Analyses DNS response-time spikes across all results.
     * Spikes are RT &gt; μ + 5σ, computed per domain × record-type group,
     * successful queries only.
     *
     * <p>All spikes are recorded for chart rendering ({@link Result#isSpikeAt(long)}),
     * but only spikes that <b>also</b> exceed {@link #DNS_HARD_THRESHOLD_MS} (default 500 ms)
     * contribute to the cluster/periodic/FAIL verdict.  Spikes below the hard threshold
     * are informational annotations only.</p>
     *
     * @param dns all DNS query results for the session
     * @return a {@link Result} containing the verdict and supporting data
     */
    public static Result analyzeDns(List<DnsResult> dns) {
        int totalSamples = dns.size();
        if (totalSamples == 0) {
            return noSpikes(0, "No DNS data");
        }

        // ── Step 1: collect spike timestamps per domain × type ──────────────
        // allSpikeTsMs = every spike (for visualization / isSpikeAt)
        // criticalSpikeTsMs = spikes also above hard threshold (for verdict)
        List<Long> allSpikeTsMs      = new ArrayList<>();
        List<Long> criticalSpikeTsMs = new ArrayList<>();
        Map<String, Map<String, List<DnsResult>>> grouped = dns.stream()
            .collect(Collectors.groupingBy(DnsResult::getDomain, LinkedHashMap::new,
                     Collectors.groupingBy(DnsResult::getRecordType, LinkedHashMap::new,
                                           Collectors.toList())));
        for (Map<String, List<DnsResult>> byType : grouped.values()) {
            for (List<DnsResult> rows : byType.values()) {
                List<Double> rtVals = rows.stream().filter(DnsResult::isSuccess)
                    .map(r -> (double) r.getResponseTimeMs()).collect(Collectors.toList());
                // Use robust (MAD-based) baseline so a single extreme value cannot
                // inflate σ enough to mask itself from the μ+5σ threshold.
                StatisticsEngine.Stats st = StatisticsEngine.computeRobust(rtVals);
                if (st == null) continue;
                double threshold = st.ucl(SPIKE_SIGMA_GATE);
                rows.stream().filter(DnsResult::isSuccess)
                    .filter(r -> r.getResponseTimeMs() > threshold)
                    .forEach(r -> {
                        allSpikeTsMs.add(r.getTimestamp());
                        // Only spikes that also exceed the hard threshold affect the verdict
                        if (r.getResponseTimeMs() >= DNS_HARD_THRESHOLD_MS) {
                            criticalSpikeTsMs.add(r.getTimestamp());
                        }
                    });
            }
        }

        return buildResult(allSpikeTsMs, criticalSpikeTsMs, totalSamples, "queries", CLUSTER_THRESHOLD_DNS);
    }

    // ── Website analysis ───────────────────────────────────────────────────────

    /**
     * Analyses website page-load spikes across all metrics.
     * Spikes are pageLoadTime &gt; μ + 5σ, computed per tab, positive load times only.
     *
     * <p>When {@link #WEBSITE_USE_PAGELOAD_THRESHOLD} is true, only spikes that also
     * exceed the per-site page-load threshold contribute to the cluster/periodic/FAIL
     * verdict.  Spikes below the page-load threshold are flagged for visualization only.</p>
     *
     * @param web all website metrics for the session
     * @return a {@link Result} containing the verdict and supporting data
     */
    public static Result analyzeWebsite(List<WebsiteMetrics> web) {
        return analyzeWebsite(web, null);
    }

    /**
     * Analyses website page-load spikes with an explicit threshold function.
     *
     * @param web            all website metrics for the session
     * @param thresholdsFor  function returning per-domain page-load thresholds (may be null
     *                       — falls back to hard-coded default of 5000 ms warm / 8000 ms cold)
     * @return a {@link Result} containing the verdict and supporting data
     */
    public static Result analyzeWebsite(List<WebsiteMetrics> web,
                                         java.util.function.Function<String, WebsiteThresholds> thresholdsFor) {
        int totalSamples = web.size();
        if (totalSamples == 0) {
            return noSpikes(0, "No website data");
        }

        // ── Step 1: collect spike timestamps per tab ────────────────────────
        List<Long> allSpikeTsMs      = new ArrayList<>();
        List<Long> criticalSpikeTsMs = new ArrayList<>();
        Map<Integer, List<WebsiteMetrics>> byTab = web.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getTabIndex,
                                           LinkedHashMap::new, Collectors.toList()));
        for (List<WebsiteMetrics> rows : byTab.values()) {
            List<Double> loadVals = rows.stream()
                .filter(m -> m.getPageLoadTime() > 0)
                .map(m -> (double) m.getPageLoadTime())
                .collect(Collectors.toList());
            // Robust baseline: MAD-derived σ prevents a single large page-load from
            // masking itself by inflating the mean+σ threshold.
            StatisticsEngine.Stats st = StatisticsEngine.computeRobust(loadVals);
            if (st == null) continue;
            double threshold = st.ucl(SPIKE_SIGMA_GATE);
            rows.stream()
                .filter(m -> m.getPageLoadTime() > threshold)
                .forEach(m -> {
                    long ts = m.getTimestamp();
                    if (ts > 0) {
                        allSpikeTsMs.add(ts);
                        // Only spikes that also exceed the page-load threshold affect the verdict
                        if (WEBSITE_USE_PAGELOAD_THRESHOLD) {
                            WebsiteThresholds t = (thresholdsFor != null)
                                ? thresholdsFor.apply(m.getDomain())
                                : WebsiteThresholds.DEFAULT;
                            long loadLimit = (m.getRefreshCycle() == 1)
                                ? t.pageLoadColdMs : t.pageLoadWarmMs;
                            if (m.getPageLoadTime() > loadLimit) {
                                criticalSpikeTsMs.add(ts);
                            }
                        } else {
                            criticalSpikeTsMs.add(ts);
                        }
                    }
                });
        }

        return buildResult(allSpikeTsMs, criticalSpikeTsMs, totalSamples, "pages", CLUSTER_THRESHOLD);
    }

    // ── Shared computation ─────────────────────────────────────────────────────

    /**
     * Steps 2–4: cluster detection, periodicity check, and verdict assignment.
     *
     * <p>All spikes ({@code allSpikeTs}) are recorded in the result for visual rendering
     * (chart annotations, {@link Result#isSpikeAt(long)}).  The <b>verdict</b> (cluster,
     * periodic, WARN, PASS) is computed exclusively from {@code criticalSpikeTs} — the
     * subset of spikes that also exceed the hard threshold.  This means outliers that are
     * above μ+5σ but below the hard threshold are informational-only and never cause FAIL.</p>
     *
     * @param allSpikeTs     all spike event timestamps (for visualization)
     * @param criticalSpikeTs subset of spikes that also exceed the hard threshold (for verdict)
     * @param totalSamples   total number of samples analysed
     * @param unit           human label for the unit type (e.g. "queries", "pages")
     * @param clusterThreshold minimum spikes in one 60 s window to declare a cluster burst
     */
    private static Result buildResult(List<Long> allSpikeTs, List<Long> criticalSpikeTs,
                                       int totalSamples, String unit, int clusterThreshold) {
        allSpikeTs.sort(Comparator.naturalOrder());
        criticalSpikeTs.sort(Comparator.naturalOrder());

        int    totalSpikes  = allSpikeTs.size();
        int    criticalCount = criticalSpikeTs.size();
        double spikeRatePct = 100.0 * totalSpikes / totalSamples;

        if (totalSpikes == 0) {
            return noSpikes(totalSamples, "No spikes detected");
        }

        // If no spikes exceed the hard threshold, the verdict is PASS regardless of
        // how many statistical outliers were detected — they are flagged for display only.
        if (criticalCount == 0) {
            String reason = String.format(
                "%d outlier%s above \u03bc+5\u03c3 but none exceeded hard threshold — flagged only, not critical",
                totalSpikes, totalSpikes != 1 ? "s" : "");
            return new Result(totalSpikes, totalSamples, spikeRatePct,
                0, false, -1.0, false, "PASS", reason,
                allSpikeTs, 0L, 0L);
        }

        // ── Step 2: cluster detection on CRITICAL spikes only ────────────────
        double criticalRatePct = 100.0 * criticalCount / totalSamples;
        int  maxSpikeIn60s       = 1;
        long clusterWindowStart  = 0L;
        long clusterWindowEnd    = 0L;
        for (int i = 0; i < criticalSpikeTs.size(); i++) {
            long windowStart = criticalSpikeTs.get(i);
            long windowEnd   = windowStart + CLUSTER_WINDOW_MS;
            int  cnt = 0;
            for (int j = i; j < criticalSpikeTs.size() && criticalSpikeTs.get(j) <= windowEnd; j++) cnt++;
            if (cnt > maxSpikeIn60s) {
                maxSpikeIn60s      = cnt;
                clusterWindowStart = windowStart;
                clusterWindowEnd   = windowEnd;
            }
        }
        boolean hasCluster = maxSpikeIn60s >= clusterThreshold;

        // ── Step 3: periodicity detection on CRITICAL spikes ─────────────────
        double  interSpikeCV = -1.0;
        boolean hasPeriodic  = false;
        if (criticalSpikeTs.size() >= 3) {
            List<Double> intervals = new ArrayList<>();
            for (int i = 1; i < criticalSpikeTs.size(); i++) {
                intervals.add((double)(criticalSpikeTs.get(i) - criticalSpikeTs.get(i - 1)));
            }
            StatisticsEngine.Stats intSt = StatisticsEngine.compute(intervals);
            if (intSt != null && intSt.mean > 0) {
                interSpikeCV = intSt.cv;
                hasPeriodic  = interSpikeCV < PERIODIC_CV_MAX && criticalRatePct > PERIODIC_RATE_MIN_PCT;
            }
        }

        // ── Step 4: verdict (based on critical spikes only) ──────────────────
        String verdict;
        String reason;
        if (hasCluster || hasPeriodic) {
            verdict = "FAIL";
            if (hasCluster && hasPeriodic) {
                reason = String.format("Cluster burst (%d critical in 60 s) + periodic (CV=%.2f)",
                    maxSpikeIn60s, interSpikeCV);
            } else if (hasCluster) {
                reason = String.format("%d critical spikes in a 60 s window — sustained burst", maxSpikeIn60s);
            } else {
                reason = String.format("Periodic: CV=%.2f < %.2f, critical rate=%.1f%%",
                    interSpikeCV, PERIODIC_CV_MAX, criticalRatePct);
            }
        } else if (criticalRatePct > WARN_RATE_PCT) {
            verdict = "WARN";
            reason  = String.format("Critical rate %.1f%% (%d of %d %s exceed hard threshold) — scattered but frequent",
                criticalRatePct, criticalCount, totalSamples, unit);
        } else {
            // Critical spikes exist but are too few and non-clustered to be structurally significant.
            verdict = "PASS";
            reason  = String.format("%d critical of %d total outliers (%d %s) — isolated, not significant",
                criticalCount, totalSpikes, totalSamples, unit);
        }

        return new Result(totalSpikes, totalSamples, spikeRatePct,
            maxSpikeIn60s, hasCluster, interSpikeCV, hasPeriodic, verdict, reason,
            allSpikeTs, clusterWindowStart, clusterWindowEnd);
    }

    private static Result noSpikes(int totalSamples, String reason) {
        return new Result(0, totalSamples, 0.0, 0, false, -1.0, false, "PASS", reason,
            List.of(), 0L, 0L);
    }
}
