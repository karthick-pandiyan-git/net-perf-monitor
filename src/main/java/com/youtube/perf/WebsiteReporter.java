package com.youtube.perf;

import java.util.Comparator;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prints the website performance and DNS monitoring reports to stdout.
 *
 * Website report  — per-domain table showing every refresh cycle with
 *                   Page Load, TTFB, DCL, DNS lookup, and TCP timings, plus
 *                   per-domain averages.
 *
 * DNS report      — per-domain / per-tool summary: rounds executed, success
 *                   rate, and avg / min / max response times.
 */
public class WebsiteReporter {

    // Thresholds used for inline PASS/FAIL annotation
    /** Returns the applicable thresholds for a given domain. */
    private final Function<String, WebsiteThresholds> thresholdsFor;

    private static final long THRESHOLD_DCL_MS         = 4_000;
    private static final long THRESHOLD_DNS_LOOKUP_MS  = 200;

    public WebsiteReporter(Function<String, WebsiteThresholds> thresholdsFor) {
        this.thresholdsFor = thresholdsFor;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Prints the website performance report to stdout, grouped by tab.
     * One section per tab shows all refresh cycles with timing columns and
     * per-domain averages.
     *
     * @param metrics all collected {@link WebsiteMetrics} records
     */
    public void printWebsiteReport(List<WebsiteMetrics> metrics) {
        int totalCycles = metrics.isEmpty() ? 0
            : metrics.stream().mapToInt(WebsiteMetrics::getRefreshCycle).max().orElse(0);
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════");
        System.out.printf( "                    WEBSITE PERFORMANCE REPORT  (%d refresh cycles × 5 s)%n", totalCycles);
        System.out.println("══════════════════════════════════════════════════════════════════════════");

        Map<Integer, List<WebsiteMetrics>> byTab = metrics.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getTabIndex));

        byTab.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> printTabSection(entry.getKey(), entry.getValue()));
    }

    /**
     * Prints the DNS monitoring report to stdout.
     * Rows are grouped by domain and tool and show round count, success rate,
     * and avg / min / max response times.
     *
     * @param results all collected {@link DnsResult} records
     */
    public void printDnsReport(List<DnsResult> results) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════");
        System.out.println("                    DNS MONITORING REPORT  (30 s continuous)");
        System.out.println("                    A = IPv4 record  |  AAAA = IPv6 record");
        System.out.println("══════════════════════════════════════════════════════════════════════════");

        // Group by domain, then record type (A / AAAA) for a side-by-side view of each IP version.
        Map<String, Map<String, List<DnsResult>>> byDomainAndType = results.stream()
            .collect(Collectors.groupingBy(
                DnsResult::getDomain,
                Collectors.groupingBy(DnsResult::getRecordType)
            ));

        System.out.printf("  %-25s  %-6s  %7s  %7s  %8s  %8s  %8s  %8s%n",
            "Domain", "Type", "Rounds", "Success", "Avg(ms)", "Min(ms)", "Max(ms)", "Stddev");
        System.out.println("  " + "─".repeat(90));

        byDomainAndType.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(domainEntry -> {
                String domain = domainEntry.getKey();
                // Print A before AAAA for consistent ordering.
                domainEntry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(typeEntry -> printDnsRow(domain, typeEntry.getKey(), typeEntry.getValue()));
            });

        System.out.println();
    }

    // ── Website section helpers ───────────────────────────────────────────────
    /**
     * Prints the per-tab section header and all cycle rows for one website tab.
     *
     * @param tabIndex   the 1-based tab number
     * @param tabMetrics all metrics records for this tab, unsorted
     */
    private void printTabSection(int tabIndex, List<WebsiteMetrics> tabMetrics) {
        String domain = tabMetrics.isEmpty() ? "?" : tabMetrics.get(0).getDomain();
        String title  = tabMetrics.stream()
            .filter(m -> m.getPageTitle() != null && !m.getPageTitle().isBlank())
            .map(WebsiteMetrics::getPageTitle)
            .findFirst().orElse("");

        System.out.println();
        System.out.printf("  [ Tab %d ] %s%s%n", tabIndex, domain,
            title.isBlank() ? "" : "  —  " + truncate(title, 50));
        System.out.println("  " + "─".repeat(90));
        System.out.printf("  %-6s  %-14s  %-12s  %-12s  %-12s  %-12s%n",
            "Cycle", "Page Load", "TTFB", "DCL", "DNS Lookup", "TCP Connect");
        System.out.println("  " + "─".repeat(90));

        List<WebsiteMetrics> sorted = tabMetrics.stream()
            .sorted(Comparator.comparingInt(WebsiteMetrics::getRefreshCycle))
            .toList();

        WebsiteThresholds thr = thresholdsFor.apply(domain);
        for (WebsiteMetrics m : sorted) {
            if (!m.isSuccess()) {
                System.out.printf("  %-6d  %-14s%n", m.getRefreshCycle(), "ERROR: " + truncate(m.getErrorMessage(), 70));
                continue;
            }
            boolean isCold = m.getRefreshCycle() == 1;
            System.out.printf("  %-6d  %s  %s  %s  %s  %s%n",
                m.getRefreshCycle(),
                msCell(m.getPageLoadTime(),     isCold ? thr.pageLoadColdMs : thr.pageLoadWarmMs),
                msCell(m.getTimeToFirstByte(),  isCold ? thr.ttfbColdMs     : thr.ttfbWarmMs),
                msCell(m.getDomContentLoaded(), THRESHOLD_DCL_MS),
                msCell(m.getDnsLookupTime(),    THRESHOLD_DNS_LOOKUP_MS),
                plain(m.getTcpConnectionTime()));
        }

        // Averages row
        System.out.println("  " + "─".repeat(90));
        long[] avgs = averages(sorted);
        System.out.printf("  %-6s  %-14s  %-12s  %-12s  %-12s  %-12s%n",
            "AVG",
            fmtMs(avgs[0]), fmtMs(avgs[1]), fmtMs(avgs[2]),
            fmtMs(avgs[3]), fmtMs(avgs[4]));

        // Statistics row (mean ± σ + outlier count)
        List<Double> loadValues = sorted.stream().filter(WebsiteMetrics::isSuccess)
            .map(m -> (double) m.getPageLoadTime()).filter(v -> v >= 0).collect(Collectors.toList());
        List<Double> ttfbValues = sorted.stream().filter(WebsiteMetrics::isSuccess)
            .map(m -> (double) m.getTimeToFirstByte()).filter(v -> v >= 0).collect(Collectors.toList());

        StatisticsEngine.Stats loadStats = StatisticsEngine.compute(loadValues);
        StatisticsEngine.Stats ttfbStats = StatisticsEngine.compute(ttfbValues);

        if (loadStats != null || ttfbStats != null) {
            String loadStat = loadStats != null
                ? String.format("μ=%.0f ±σ%.0f ms", loadStats.mean, loadStats.stddev)
                : "n/a";
            String ttfbStat = ttfbStats != null
                ? String.format("μ=%.0f ±σ%.0f ms", ttfbStats.mean, ttfbStats.stddev)
                : "n/a";
            long loadOutliers = StatisticsEngine.countHighOutliers(loadValues, loadStats);
            long ttfbOutliers = StatisticsEngine.countHighOutliers(ttfbValues, ttfbStats);
            String outlierNote = "";
            if (loadOutliers > 0 || ttfbOutliers > 0)
                outlierNote = String.format("  ⚠ outliers: %d load  %d TTFB  (>μ+%sσ)",
                    loadOutliers, ttfbOutliers,
                    (loadStats != null && loadStats.isReliable()) ? "2" : "3");
            System.out.printf("  %-6s  %-14s  %-12s%s%n",
                "STAT", loadStat, ttfbStat, outlierNote);
            if (loadStats != null && !loadStats.isReliable())
                System.out.println("         (limited samples — σ-gate widened to 3σ to reduce false positives)");
        }
        System.out.println();
    }

    /**
     * Formats a timing value as a fixed-width cell with an inline PASS/FAIL symbol.
     * Values below {@code threshold} are annotated with ✓; values above with ✗.
     *
     * @param value     the timing in milliseconds; negative values are shown as {@code N/A}
     * @param threshold the threshold in milliseconds used to determine pass/fail
     * @return a left-aligned, 12-character formatted string
     */
    private String msCell(long value, long threshold) {
        if (value < 0) return String.format("%-12s", "N/A");
        String verdict = value <= threshold ? "✓" : "✗";
        return String.format("%-12s", value + " ms " + verdict);
    }

    /**
     * Formats a timing value without a PASS/FAIL annotation.
     * Negative values are shown as {@code N/A}.
     *
     * @param value the timing in milliseconds
     * @return a left-aligned, 12-character formatted string
     */
    private String plain(long value) {
        return String.format("%-12s", value < 0 ? "N/A" : value + " ms");
    }

    /**
     * Formats a timing value as a left-aligned millisecond string.
     * Negative values are shown as {@code N/A}. Used for the averages row.
     *
     * @param value the timing in milliseconds
     * @return a left-aligned, 12-character formatted string
     */
    private String fmtMs(long value) {
        return String.format("%-12s", value < 0 ? "N/A" : value + " ms");
    }

    /**
     * Computes per-metric averages over the successful cycles in {@code list}.
     *
     * @param list all {@link WebsiteMetrics} records for one tab (may include failures)
     * @return a 5-element array: {@code [pageLoad, ttfb, dcl, dnsLookup, tcpConnect]}
     *         in milliseconds; any metric with no valid readings is set to {@code -1}
     */
    private long[] averages(List<WebsiteMetrics> list) {
        List<WebsiteMetrics> ok = list.stream().filter(WebsiteMetrics::isSuccess).toList();
        if (ok.isEmpty()) return new long[]{-1, -1, -1, -1, -1};
        return new long[]{
            (long) ok.stream().mapToLong(WebsiteMetrics::getPageLoadTime).filter(v -> v >= 0).average().orElse(-1),
            (long) ok.stream().mapToLong(WebsiteMetrics::getTimeToFirstByte).filter(v -> v >= 0).average().orElse(-1),
            (long) ok.stream().mapToLong(WebsiteMetrics::getDomContentLoaded).filter(v -> v >= 0).average().orElse(-1),
            (long) ok.stream().mapToLong(WebsiteMetrics::getDnsLookupTime).filter(v -> v >= 0).average().orElse(-1),
            (long) ok.stream().mapToLong(WebsiteMetrics::getTcpConnectionTime).filter(v -> v >= 0).average().orElse(-1)
        };
    }

    // ── DNS section helpers ───────────────────────────────────────────────────

    /**
     * Prints a single aggregate summary row for one (domain, record type) combination
     * in the DNS monitoring report.
     *
     * @param domain     the domain name (e.g. {@code "google.com"})
     * @param recordType the DNS record type: {@code "A"} (IPv4) or {@code "AAAA"} (IPv6)
     * @param rows       all {@link DnsResult} records for this domain and record type
     */
    private void printDnsRow(String domain, String recordType, List<DnsResult> rows) {
        List<DnsResult> succeeded = rows.stream().filter(DnsResult::isSuccess).toList();

        int total   = rows.size();
        int success = succeeded.size();
        double pct  = total == 0 ? 0 : (success * 100.0) / total;

        if (succeeded.isEmpty()) {
            // All queries failed — typically means no AAAA records exist or IPv6 is blocked.
            String reason = rows.stream()
                .map(DnsResult::getErrorMessage)
                .filter(e -> e != null && !e.isBlank())
                .findFirst().orElse("unknown");
            System.out.printf("  %-25s  %-6s  %7d  %7s  %-55s%n",
                domain, recordType, total, "0%", "N/A — " + truncate(reason, 50));
            return;
        }

        LongSummaryStatistics stats = succeeded.stream()
            .mapToLong(DnsResult::getResponseTimeMs)
            .summaryStatistics();

        // Statistical analysis of response-time distribution
        List<Double> rtValues = succeeded.stream()
            .map(r -> (double) r.getResponseTimeMs()).collect(Collectors.toList());
        StatisticsEngine.Stats rtStats = StatisticsEngine.compute(rtValues);
        long spikeCount = StatisticsEngine.countHighOutliers(rtValues, rtStats);
        String spikeNote = (spikeCount > 0)
            ? String.format("  ⚠ %d spike%s>μ+%sσ", spikeCount, spikeCount > 1 ? "s" : "",
                (rtStats != null && rtStats.isReliable() ? "2" : "3"))
            : "";
        String stddevStr = (rtStats != null)
            ? String.format("σ=%.0f", rtStats.stddev)
            : "";

        System.out.printf("  %-25s  %-6s  %7d  %6.0f%%  %8.0f  %8d  %8d  %8s%s%n",
            domain, recordType, total, pct,
            stats.getAverage(), stats.getMin(), stats.getMax(), stddevStr, spikeNote);
    }

    /**
     * Prints the combined FINAL REPORT covering YouTube videos, website
     * performance, and DNS monitoring.
     *
     * <p>Each section is omitted when its probe was not active in the current run.
     * Website and DNS sections include per-cycle PASS/FAIL rows and overall
     * success-rate summaries.</p>
     *
     * @param ytVerdicts  per-video PASS/FAIL verdicts (may be null/empty)
     * @param webMetrics  website refresh-cycle metrics  (may be null/empty)
     * @param dnsResults  DNS query results               (may be null/empty)
     */
    public void printFinalSummary(
            List<VideoVerdict>   ytVerdicts,
            List<WebsiteMetrics> webMetrics,
            List<DnsResult>      dnsResults) {

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                          COMBINED FINAL REPORT                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");

        // ── 1. YouTube Videos ─────────────────────────────────────────────────
        // Section is omitted entirely when YouTube was not part of the run.
        if (ytVerdicts != null) {
            System.out.println();
            System.out.println("  ╔══ YOUTUBE VIDEO PERFORMANCE ══════════════════════════════════════════");
            System.out.println("  ║  Internet checks per video (5 core + up to 3 Stats for Nerds + up to 2 statistical):");
            System.out.println("  ║    TTFB ≤ 3000 ms (cold) / 1500 ms (warm)  │  Page Load ≤ 15000 ms (cold) / 12000 ms (warm)");
            System.out.println("  ║    Buffer ≥ 10 s  │  Avg BW ≥ 250 KB/s  │  Quality Stability: no ABR downgrade during playback");
            System.out.println("  ║    Stats for Nerds (when available): SFN Connection Speed ≥ 2500 Kbps  │  SFN Buffer Health ≥ 10 s  │  SFN Resolution match");
            System.out.println("  ║    Statistical: Buffer Depth Consistency (μ−1σ ≥ 10 s)  │  Frame Drop (Catastrophic): no 100% drop sweep");
            System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
            System.out.printf("  %-5s  %-45s  %-18s  %8s  %6s  %s%n",
                "Tab", "Video Title", "Quality", "Checks", "Status", "Failed checks");
            System.out.println("  " + "─".repeat(110));

            boolean anyYt = !ytVerdicts.isEmpty();
            if (anyYt) {
                for (VideoVerdict v : ytVerdicts) {
                    String title = v.getMetrics().getPageTitle();
                    if (title == null || title.isBlank()) title = v.getMetrics().getUrl();
                    String failedChecks = v.getChecks().stream()
                        .filter(c -> !c.isPassed())
                        .map(c -> c.getCheckName() + ": " + c.getActual() + " (threshold " + c.getExpected() + ")")
                        .collect(Collectors.joining(", "));

                    VideoMetrics vm = v.getMetrics();
                    String qualitySummary;
                    if (vm.getPeakQualityLabel() != null) {
                        qualitySummary = vm.isQualityDegraded()
                            ? vm.getPeakQualityLabel() + " \u2192 " + vm.getLowestQualityLabel()
                            : vm.getPeakQualityLabel() + " (stable)";
                    } else {
                        qualitySummary = "N/A (auto)";
                    }

                    System.out.printf("  %-5d  %-45s  %-18s  %3d / %2d  [%-4s]  %s%n",
                        vm.getTabIndex(),
                        truncate(title, 45),
                        qualitySummary,
                        v.passCount(), v.getChecks().size(),
                        v.isPassed() ? "PASS" : "FAIL",
                        failedChecks);
                }
            } else {
                System.out.println("  No YouTube results.");
            }
        }

        // ── 2. Website Performance ────────────────────────────────────────────
        // Section is omitted entirely when Website was not part of the run.
        boolean anyWebResults = webMetrics != null && !webMetrics.isEmpty();
        if (webMetrics != null) {
            System.out.println();
            System.out.println("  ╔══ WEBSITE PERFORMANCE ════════════════════════════════════════════════");
            WebsiteThresholds def = thresholdsFor.apply(null);
            System.out.printf("  ║  Thresholds (defaults) — warm (cycles 2+): Page Load ≤ %d ms │ TTFB ≤ %d ms  (per-site overrides may apply)%n",
                def.pageLoadWarmMs, def.ttfbWarmMs);
            System.out.printf("  ║  Thresholds (defaults) — cold (cycle 1):   Page Load ≤ %d ms │ TTFB ≤ %d ms  (no cache, new TCP+TLS)%n",
                def.pageLoadColdMs, def.ttfbColdMs);
            System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
            System.out.printf("  %-22s  %5s  %12s  %10s  %10s  %-8s  %s%n",
                "Domain", "Cycle", "Page Load", "TTFB", "DCL", "Status", "Reason");
            System.out.println("  " + "─".repeat(95));

            if (anyWebResults) {
                webMetrics.stream()
                    .collect(Collectors.groupingBy(WebsiteMetrics::getDomain))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> printWebsiteCycleRows(e.getKey(), e.getValue()));
            } else {
                System.out.println("  No website results.");
            }
        }

        // ── 3. DNS Monitoring ─────────────────────────────────────────────────
        // Section is omitted entirely when DNS was not part of the run.
        boolean anyDnsResults = dnsResults != null && !dnsResults.isEmpty();
        if (dnsResults != null) {
            System.out.println();
            System.out.println("  ╔══ DNS MONITORING ══════════════════════════════════════════════════════");
            System.out.println("  ║  PASS = direct UDP query to 8.8.8.8 (Google DNS) resolved successfully \u2014 real internet connectivity confirmed.");
            System.out.println("  ║  FAIL = query timed out or returned an error \u2014 DNS/internet broken at time of query.");
            System.out.println("  ║  Times are true round-trip latency to 8.8.8.8: 10\u2013100 ms = healthy, >500 ms = congested, timeout = down.");
            System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
            System.out.printf("  %-20s  %-24s  %6s  %10s  %-8s  %s%n",
                "Domain", "Type", "Round", "Time(ms)", "Status", "Reason");
            System.out.println("  " + "─".repeat(90));

            if (anyDnsResults) {
                dnsResults.stream()
                    .collect(Collectors.groupingBy(r -> r.getDomain() + "||" + r.getRecordType()))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        String[] parts = e.getKey().split("\\|\\|");
                        printDnsQueryRows(parts[0], parts[1], e.getValue());
                    });
            } else {
                System.out.println("  No DNS results.");
            }
        }

        // ── 4. Overall verdict ────────────────────────────────────────────────
        // null list  → probe was not part of this run → omit its row from the summary
        // empty list → probe ran but produced no results (crash/setup failure) → FAIL
        boolean ytAttempted  = ytVerdicts != null;
        boolean anyYtResults = ytAttempted && !ytVerdicts.isEmpty();
        boolean allYtPass    = anyYtResults && ytVerdicts.stream().allMatch(VideoVerdict::isPassed);
        long    ytPass       = anyYtResults ? ytVerdicts.stream().filter(VideoVerdict::isPassed).count() : 0;
        long    ytTotal      = anyYtResults ? ytVerdicts.size() : 0;
        boolean allWebPass = anyWebResults && webMetrics.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getDomain))
            .values().stream().allMatch(this::websiteDomainPasses);
        boolean allDnsPass = anyDnsResults && dnsResults.stream()
            .collect(Collectors.groupingBy(r -> r.getDomain() + "||" + r.getRecordType()))
            .values().stream().allMatch(this::dnsDomainToolPasses);

        System.out.println();
        System.out.println("  ╔══ OVERALL RESULT ══════════════════════════════════════════════════════");
        // Only print a row for probes that were part of this run.
        if (ytAttempted) {
            System.out.printf("  ║  %-50s  [%s]%n",
                "YouTube Performance (" + ytPass + "/" + ytTotal + " videos passed)",
                allYtPass ? "PASS" : "FAIL");
        }
        if (webMetrics != null) {
            System.out.printf("  ║  %-50s  [%s]%n", "Website Performance",
                anyWebResults && allWebPass ? "PASS" : "FAIL");
        }
        if (dnsResults != null) {
            System.out.printf("  ║  %-50s  [%s]%n", "DNS Monitoring",
                anyDnsResults && allDnsPass ? "PASS" : "FAIL");
        }
        // Overall passes only if every attempted probe passed.
        boolean overallPass = (!ytAttempted || allYtPass)
            && (webMetrics  == null || (anyWebResults && allWebPass))
            && (dnsResults  == null || (anyDnsResults && allDnsPass));
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════");
        System.out.printf("  ║  %-50s  [%s]%n", "TEST SUITE RESULT",
            (!ytAttempted && !anyWebResults && !anyDnsResults) ? "N/A" : overallPass ? "PASS" : "FAIL");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════");
        System.out.println();
    }

    /**
     * Prints one row per refresh cycle for a given domain.
     * Each cycle is evaluated independently: PASS only if the page loaded
     * successfully AND both page-load time and TTFB are within thresholds.
     */
    private void printWebsiteCycleRows(String domain, List<WebsiteMetrics> rows) {
        List<WebsiteMetrics> sorted = rows.stream()
            .sorted(java.util.Comparator.comparingInt(WebsiteMetrics::getRefreshCycle)).toList();
        for (WebsiteMetrics m : sorted) {
            boolean pass = websiteCyclePasses(m);
            String status = pass ? "[PASS]" : "[FAIL]";
            List<String> reasons = buildCycleFailReasons(m);
            String reason = String.join(", ", reasons);
            System.out.printf("  %-22s  %5d  %10s  %8s  %8s  %-8s  %s%n",
                domain, m.getRefreshCycle(),
                m.getPageLoadTime()  < 0 ? "N/A" : m.getPageLoadTime()  + " ms",
                m.getTimeToFirstByte() < 0 ? "N/A" : m.getTimeToFirstByte() + " ms",
                m.getDomContentLoaded() < 0 ? "N/A" : m.getDomContentLoaded() + " ms",
                status, reason);
        }
        System.out.println("  " + "─".repeat(95));
    }

    /**
     * Prints one row per individual DNS query result.
     * PASS if the query succeeded and response time is within the threshold.
     */
    private void printDnsQueryRows(String domain, String recordType, List<DnsResult> rows) {
        List<DnsResult> sorted = rows.stream()
            .sorted(java.util.Comparator.comparingLong(DnsResult::getTimestamp)).toList();
        int round = 0;
        for (DnsResult r : sorted) {
            round++;
            // PASS = direct UDP query to 8.8.8.8 succeeded — real internet connectivity
            // was present at this moment. Times are true round-trip latency (never 0 ms).
            boolean pass = r.isSuccess();
            String status = pass ? "[PASS]" : "[FAIL]";
            String reason = r.isSuccess() ? "" :
                (r.getErrorMessage() != null ? truncate(r.getErrorMessage(), 50) : "resolution failed");
            System.out.printf("  %-20s  %-24s  %6d  %8s  %-8s  %s%n",
                domain, recordType, round,
                r.getResponseTimeMs() + " ms",
                status, reason);
        }
        System.out.println("  " + "─".repeat(90));
    }

    /**
     * Returns {@code true} if every cycle for the given domain passed
     * {@link #websiteCyclePasses(WebsiteMetrics)}. An empty list returns {@code false}.
     *
     * @param rows all metrics records for one domain
     * @return {@code true} when all cycles passed the per-cycle criteria
     */
    private boolean websiteDomainPasses(List<WebsiteMetrics> rows) {
        // Every single cycle must pass individually (100% success rate)
        return !rows.isEmpty() && rows.stream().allMatch(this::websiteCyclePasses);
    }

    /**
     * Returns {@code true} when a single refresh cycle is considered PASS:
     * the page loaded successfully, page-load time is within the applicable
     * threshold (cold for cycle 1, warm for cycle 2+), and TTFB is within
     * the applicable threshold.
     *
     * @param m the metrics for one refresh cycle
     * @return {@code true} when every timing criterion passes
     */
    private boolean websiteCyclePasses(WebsiteMetrics m) {
        if (!m.isSuccess()) return false;
        // Cycle 1 is a cold load (no browser cache) — apply more lenient thresholds.
        WebsiteThresholds thr = thresholdsFor.apply(m.getDomain());
        long loadLimit = (m.getRefreshCycle() == 1) ? thr.pageLoadColdMs : thr.pageLoadWarmMs;
        long ttfbLimit = (m.getRefreshCycle() == 1) ? thr.ttfbColdMs     : thr.ttfbWarmMs;
        boolean loadOk = m.getPageLoadTime() >= 0 && m.getPageLoadTime() <= loadLimit;
        boolean ttfbOk = m.getTimeToFirstByte() >= 0 && m.getTimeToFirstByte() <= ttfbLimit;
        return loadOk && ttfbOk;
    }

    /**
     * Builds a human-readable list of reasons why a website refresh cycle
     * did not pass. Returns an empty list when the cycle passed.
     *
     * @param m the metrics for one refresh cycle
     * @return list of failure reason strings (empty when the cycle passed)
     */
    private List<String> buildCycleFailReasons(WebsiteMetrics m) {
        List<String> reasons = new java.util.ArrayList<>();
        if (!m.isSuccess()) {
            reasons.add("page load failed" + (m.getErrorMessage() != null ? ": " + truncate(m.getErrorMessage(), 40) : ""));
            return reasons;
        }
        boolean isCold = (m.getRefreshCycle() == 1);
        WebsiteThresholds thr = thresholdsFor.apply(m.getDomain());
        if (isCold) {
            // Always note cold-start for cycle 1 so the reader understands why a
            // high TTFB or page load still passes: relaxed thresholds apply here.
            reasons.add("cold start (TTFB ≤ " + thr.ttfbColdMs + " ms, load ≤ " + thr.pageLoadColdMs + " ms)");
        }
        if (m.getPageLoadTime() < 0) {
            reasons.add("pageLoad N/A");
        } else {
            long loadLimit = isCold ? thr.pageLoadColdMs : thr.pageLoadWarmMs;
            if (m.getPageLoadTime() > loadLimit)
                reasons.add("pageLoad " + m.getPageLoadTime() + " ms (> " + loadLimit + ")");
        }
        if (m.getTimeToFirstByte() < 0) {
            reasons.add("TTFB N/A");
        } else {
            long ttfbLimit = isCold ? thr.ttfbColdMs : thr.ttfbWarmMs;
            if (m.getTimeToFirstByte() > ttfbLimit)
                reasons.add("TTFB " + m.getTimeToFirstByte() + " ms (> " + ttfbLimit + ")");
        }
        return reasons;
    }

    /**
     * Returns {@code true} when every DNS query in the given list resolved
     * successfully. A single failure means real connectivity was broken and
     * the result must be FAIL.
     *
     * @param rows all {@link DnsResult} records for one (domain, tool) combination
     * @return {@code true} when all queries succeeded
     */
    private boolean dnsDomainToolPasses(List<DnsResult> rows) {
        // PASS = every direct UDP query to 8.8.8.8 resolved successfully.
        // A single timeout or error means real connectivity was broken at that moment
        // and must be reported as FAIL.
        if (rows.isEmpty()) return false;
        return rows.stream().allMatch(DnsResult::isSuccess);
    }

    // ── Utility ───────────────────────────────────────────────────────────────
    /**
     * Truncates {@code s} to at most {@code max} characters, appending
     * {@code "..."} when truncation occurs. Returns an empty string for
     * {@code null} input.
     *
     * @param s   the string to truncate
     * @param max maximum number of characters to retain (inclusive)
     * @return the (possibly truncated) string, never {@code null}
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
