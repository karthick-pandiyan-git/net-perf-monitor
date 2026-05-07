package com.youtube.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Produces a compact JSON snapshot of all probe results to stdout.
 *
 * <p>Each entry in the JSON reflects the value <em>at the end of the
 * monitoring window</em> — the most recent cycle for website tabs, the
 * final aggregate for YouTube, and round-level stats for DNS — rather
 * than a full per-sample breakdown.</p>
 *
 * <p>Probes that were not run are omitted from the output entirely.</p>
 */
public class JsonReporter {

    private static final Logger logger = LoggerFactory.getLogger(JsonReporter.class);

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                         .withZone(ZoneId.systemDefault());

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds and prints the JSON snapshot to stdout.
     *
     * @param mode            active probe mode string, e.g. {@code "youtube,dns"}
     * @param durationSeconds the configured monitoring window in seconds
     * @param snapshotNum     snapshot sequence number (1, 2, …) for intermediate
     *                        reports; {@code 0} marks the final report
     * @param fromTs          epoch-ms lower bound: only include website cycles and
     *                        DNS rounds whose timestamp is ≥ this value.
     *                        Pass {@code 0} to include all data (used for FINAL).
     * @param ytResults       YouTube probe results, or {@code null} if not active
     * @param webResults      Website probe results, or {@code null} if not active
     * @param dnsResults      DNS probe results, or {@code null} if not active
     */
    public void printJson(String mode,
                          int durationSeconds,
                          int snapshotNum,
                          long fromTs,
                          List<VideoMetrics> ytResults,
                          List<WebsiteMetrics> webResults,
                          List<DnsResult> dnsResults) {

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp",        TS_FMT.format(Instant.now()));
        root.put("type",             snapshotNum > 0 ? "snapshot" : "final");
        if (snapshotNum > 0) root.put("snapshotNumber", snapshotNum);
        root.put("mode",             mode);
        root.put("duration_seconds", durationSeconds);

        if (ytResults != null) {
            root.put("youtube", buildYoutube(ytResults));
        }
        if (webResults != null) {
            root.put("website", buildWebsite(webResults, fromTs));
        }
        if (dnsResults != null) {
            root.put("dns", buildDns(dnsResults, fromTs));
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(root));
        } catch (Exception e) {
            logger.error("Failed to serialize JSON output: {}", e.getMessage(), e);
        }
    }

    // ── YouTube ───────────────────────────────────────────────────────────────

    /**
     * Builds one JSON object per YouTube tab containing connection metrics,
     * streaming throughput, and the final video quality/state.
     */
    private List<Map<String, Object>> buildYoutube(List<VideoMetrics> metrics) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VideoMetrics m : metrics) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tab",   m.getTabIndex());
            entry.put("url",   m.getUrl());
            entry.put("title", m.getPageTitle());
            if (m.getMetricsCollectedAt() > 0) {
                entry.put("timestamp", TS_FMT.format(Instant.ofEpochMilli(m.getMetricsCollectedAt())));
            }

            // Connection / page-load timings
            entry.put("pageLoad_ms",         nullIfNeg(m.getPageLoadTime()));
            entry.put("ttfb_ms",             nullIfNeg(m.getTimeToFirstByte()));
            entry.put("dnsLookup_ms",        nullIfNeg(m.getDnsLookupTime()));
            entry.put("tcpConnection_ms",    nullIfNeg(m.getTcpConnectionTime()));
            entry.put("domContentLoaded_ms", nullIfNeg(m.getDomContentLoadedTime()));

            // Streaming throughput
            entry.put("avgBandwidth_kbps",  round2(m.getAvgBandwidthKBps()));
            entry.put("peakBandwidth_kbps", round2(m.getPeakBandwidthKBps()));
            entry.put("totalData_bytes",
                m.getTotalVideoSegmentBytes() >= 0 ? m.getTotalVideoSegmentBytes() : null);
            entry.put("bufferedSeconds",    round2(m.getBufferedSeconds()));

            // Video quality / render state
            entry.put("videoWidth",          m.getVideoWidth()  > 0 ? m.getVideoWidth()  : null);
            entry.put("videoHeight",         m.getVideoHeight() > 0 ? m.getVideoHeight() : null);
            entry.put("peakQuality",         m.getPeakQualityLabel());
            entry.put("lowestQuality",       m.getLowestQualityLabel());
            entry.put("qualityDegraded",     m.isQualityDegraded());
            entry.put("droppedFrameRate_pct", round2(m.getDroppedFrameRatePct()));

            // Stats for Nerds
            StatsForNerdsData sfn = m.getSfnData();
            if (sfn != null && sfn.isAvailable()) {
                Map<String, Object> sfnMap = new LinkedHashMap<>();
                if (sfn.getConnectionSpeedKbps() >= 0)
                    sfnMap.put("connectionSpeed_kbps", round2(sfn.getConnectionSpeedKbps()));
                if (sfn.getBufferHealthSecs() >= 0)
                    sfnMap.put("bufferHealth_s",        round2(sfn.getBufferHealthSecs()));
                if (sfn.getNetworkActivityKB() >= 0)
                    sfnMap.put("networkActivity_kb",    sfn.getNetworkActivityKB());
                if (sfn.getCurrentResolution() != null)
                    sfnMap.put("currentResolution",     sfn.getCurrentResolution());
                if (sfn.getOptimalResolution() != null)
                    sfnMap.put("optimalResolution",     sfn.getOptimalResolution());
                if (sfn.getVideoCodec() != null)
                    sfnMap.put("videoCodec",            sfn.getVideoCodec());
                if (sfn.getAudioCodec() != null)
                    sfnMap.put("audioCodec",            sfn.getAudioCodec());
                if (sfn.getTotalFrames() >= 0) {
                    sfnMap.put("totalFrames",   sfn.getTotalFrames());
                    sfnMap.put("droppedFrames", sfn.getDroppedFrames());
                    long t = sfn.getTotalFrames();
                    sfnMap.put("droppedFrameRate_pct",
                        t > 0 ? round2(100.0 * sfn.getDroppedFrames() / t) : 0.0);
                }
                entry.put("statsForNerds", sfnMap);
            }

            // Per-sweep bandwidth / buffer timeline
            List<NetworkSample> samples = m.getNetworkSamples();
            if (samples != null && !samples.isEmpty()) {
                List<Map<String, Object>> sweeps = new ArrayList<>();
                for (NetworkSample s : samples) {
                    Map<String, Object> sw = new LinkedHashMap<>();
                    sw.put("sweep",       s.getSampleNumber());
                    sw.put("timestamp",   TS_FMT.format(Instant.ofEpochMilli(s.getTimestamp())));
                    sw.put("bandwidth_kbps",      round2(s.getBandwidthKBps()));
                    sw.put("totalData_bytes",      s.getTotalSegmentBytes());
                    sw.put("recentData_bytes",     s.getRecentSegmentBytes());
                    sw.put("videoCurrentTime_s",   round2(s.getVideoCurrentTime()));
                    sw.put("videoBuffered_s",       round2(s.getVideoBuffered()));
                    sw.put("qualityLabel",          s.getQualityLabel());
                    if (s.isAdPlaying()) sw.put("adPlaying", true);
                    if (s.getConnectionSpeedKbps() >= 0)
                        sw.put("connectionSpeed_kbps", round2(s.getConnectionSpeedKbps()));
                    if (s.getBufferHealthSecs() >= 0)
                        sw.put("bufferHealth_s",        round2(s.getBufferHealthSecs()));
                    if (s.getTotalFrames() >= 0) {
                        sw.put("totalFrames",   s.getTotalFrames());
                        sw.put("droppedFrames", s.getDroppedFrames());
                    }
                    sweeps.add(sw);
                }
                entry.put("sweeps", sweeps);
            }

            if (m.getErrorMessage() != null) {
                entry.put("error", m.getErrorMessage());
            }
            list.add(entry);
        }
        return list;
    }

    // ── Website ───────────────────────────────────────────────────────────────

    /**
     * Builds one JSON object per website tab.
     * Each tab contains one entry per refresh cycle (with its timestamp) plus
     * window summary stats.
     *
     * <p>When {@code fromTs > 0} only cycles whose {@code timestamp >= fromTs}
     * are included — this scopes each periodic snapshot to its own window so
     * Snapshot #1 shows cycles 1–N and Snapshot #2 shows cycles N+1…M, etc.</p>
     *
     * @param metrics all collected {@link WebsiteMetrics}
     * @param fromTs  epoch-ms lower bound; 0 = include all cycles (used for FINAL)
     */
    private List<Map<String, Object>> buildWebsite(List<WebsiteMetrics> metrics, long fromTs) {
        Map<Integer, List<WebsiteMetrics>> byTab = metrics.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getTabIndex));

        List<Map<String, Object>> list = new ArrayList<>();
        byTab.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                // Apply window filter when fromTs > 0.
                List<WebsiteMetrics> window = (fromTs > 0)
                    ? e.getValue().stream()
                        .filter(m -> m.getTimestamp() >= fromTs)
                        .sorted(Comparator.comparingInt(WebsiteMetrics::getRefreshCycle))
                        .toList()
                    : e.getValue().stream()
                        .sorted(Comparator.comparingInt(WebsiteMetrics::getRefreshCycle))
                        .toList();

                if (window.isEmpty()) return;  // no cycles in this snapshot window yet

                WebsiteMetrics first = e.getValue().get(0);

                Map<String, Object> tabEntry = new LinkedHashMap<>();
                tabEntry.put("tab",    e.getKey());
                tabEntry.put("url",    first.getUrl());
                tabEntry.put("domain", first.getDomain());

                // ── Per-cycle breakdown ──────────────────────────────────────
                List<Map<String, Object>> cycles = new ArrayList<>();
                for (WebsiteMetrics m : window) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("cycle",     m.getRefreshCycle());
                    c.put("timestamp", TS_FMT.format(Instant.ofEpochMilli(m.getTimestamp())));
                    c.put("success",   m.isSuccess());
                    if (m.isSuccess()) {
                        c.put("pageLoad_ms",         nullIfNeg(m.getPageLoadTime()));
                        c.put("ttfb_ms",             nullIfNeg(m.getTimeToFirstByte()));
                        c.put("dnsLookup_ms",        nullIfNeg(m.getDnsLookupTime()));
                        c.put("tcpConnection_ms",    nullIfNeg(m.getTcpConnectionTime()));
                        c.put("domContentLoaded_ms", nullIfNeg(m.getDomContentLoaded()));
                        // IPv4/IPv6 TCP reachability probe results (Java-side, port 443)
                        if (m.getIpv4Address() != null) {
                            c.put("ipv4_address",   m.getIpv4Address());
                            c.put("ipv4_reachable", m.isIpv4Reachable());
                            c.put("ipv4Connect_ms", nullIfNeg(m.getIpv4ConnectMs()));
                        }
                        if (m.getIpv6Address() != null) {
                            c.put("ipv6_address",   m.getIpv6Address());
                            c.put("ipv6_reachable", m.isIpv6Reachable());
                            c.put("ipv6Connect_ms", nullIfNeg(m.getIpv6ConnectMs()));
                        }
                    } else {
                        c.put("error", m.getErrorMessage());
                    }
                    cycles.add(c);
                }
                tabEntry.put("cycles", cycles);

                // ── Window summary ───────────────────────────────────────────
                List<WebsiteMetrics> ok = window.stream().filter(WebsiteMetrics::isSuccess).toList();
                tabEntry.put("successCycles", ok.size());
                tabEntry.put("totalCycles",   window.size());
                if (!ok.isEmpty()) {
                    tabEntry.put("avgPageLoad_ms", avgOf(ok, WebsiteMetrics::getPageLoadTime));
                    tabEntry.put("avgTtfb_ms",     avgOf(ok, WebsiteMetrics::getTimeToFirstByte));
                }

                list.add(tabEntry);
            });
        return list;
    }

    // ── DNS ───────────────────────────────────────────────────────────────────

    /**
     * Builds one JSON object per domain.
     * Each domain contains a per-round breakdown (with timestamp) plus
     * window summary stats.
     *
     * <p>When {@code fromTs > 0} only rounds whose {@code timestamp >= fromTs}
     * are included, matching the same snapshot window used for website cycles.</p>
     */
    private List<Map<String, Object>> buildDns(List<DnsResult> results, long fromTs) {
        List<DnsResult> window = (fromTs > 0)
            ? results.stream().filter(r -> r.getTimestamp() >= fromTs).toList()
            : results;

        Map<String, List<DnsResult>> byKey = window.stream()
            .collect(Collectors.groupingBy(r -> r.getDomain() + "|" + r.getRecordType()));

        List<Map<String, Object>> list = new ArrayList<>();
        byKey.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                int    sep        = e.getKey().indexOf('|');
                String domain     = e.getKey().substring(0, sep);
                String recordType = e.getKey().substring(sep + 1);
                List<DnsResult> dr = e.getValue().stream()
                    .sorted(Comparator.comparingLong(DnsResult::getTimestamp))
                    .toList();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("domain",     domain);
                entry.put("recordType", recordType);
                entry.put("resolver",   "8.8.8.8");

                // ── Per-round breakdown ──────────────────────────────────────
                List<Map<String, Object>> roundsList = new ArrayList<>();
                int roundNum = 0;
                for (DnsResult r : dr) {
                    roundNum++;
                    Map<String, Object> rd = new LinkedHashMap<>();
                    rd.put("round",     roundNum);
                    rd.put("timestamp", TS_FMT.format(Instant.ofEpochMilli(r.getTimestamp())));
                    rd.put("success",   r.isSuccess());
                    if (r.isSuccess()) {
                        rd.put("responseTime_ms", r.getResponseTimeMs());
                    } else {
                        rd.put("error", r.getErrorMessage());
                    }
                    roundsList.add(rd);
                }
                entry.put("rounds", roundsList);

                // ── Window summary ───────────────────────────────────────────
                // Collect all unique addresses seen across every successful round, preserving
                // insertion order so the most-recently-seen address appears last.
                List<String> allAddresses = dr.stream()
                    .filter(DnsResult::isSuccess)
                    .filter(r -> r.getResolvedAddresses() != null)
                    .flatMap(r -> r.getResolvedAddresses().stream())
                    .distinct()
                    .collect(Collectors.toList());
                if (!allAddresses.isEmpty()) {
                    entry.put("resolvedAddresses", allAddresses);
                }

                long successCount = dr.stream().filter(DnsResult::isSuccess).count();
                LongSummaryStatistics stats = dr.stream()
                    .filter(DnsResult::isSuccess)
                    .mapToLong(DnsResult::getResponseTimeMs)
                    .summaryStatistics();

                entry.put("successRounds",   successCount);
                entry.put("totalRounds",     dr.size());
                entry.put("successRate_pct",
                    dr.isEmpty() ? 0.0 : round2(100.0 * successCount / dr.size()));
                entry.put("avgResponse_ms",
                    stats.getCount() > 0 ? (long) stats.getAverage() : null);
                entry.put("minResponse_ms",
                    stats.getCount() > 0 ? stats.getMin() : null);
                entry.put("maxResponse_ms",
                    stats.getCount() > 0 ? stats.getMax() : null);
                list.add(entry);
            });
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns {@code null} for negative timing values (meaning "not collected"). */
    private static Long nullIfNeg(long v) {
        return v < 0 ? null : v;
    }

    /** Rounds to 2 decimal places. */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Computes the average of a long field across a list of {@link WebsiteMetrics},
     * ignoring negative (not-collected) values.
     *
     * @return the average rounded to the nearest millisecond, or {@code null} if
     *         no non-negative values exist
     */
    private static Long avgOf(List<WebsiteMetrics> list, ToLongFunction<WebsiteMetrics> extractor) {
        return list.stream()
            .mapToLong(extractor)
            .filter(v -> v >= 0)
            .average()
            .stream()
            .mapToObj(avg -> (Long)(long) avg)
            .findFirst()
            .orElse(null);
    }
}
