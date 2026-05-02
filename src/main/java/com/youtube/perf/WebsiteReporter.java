package com.youtube.perf;

import java.util.Comparator;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
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
    private static final long THRESHOLD_PAGE_LOAD_MS      = 5_000;
    /**
     * Cold page load threshold for website cycle 1.
     * The first navigation after the browser opens tabs has no browser cache,
     * requires new TCP/TLS connections, and contends with YouTube setup.
     * Cycles 2+ use THRESHOLD_PAGE_LOAD_MS.
     */
    private static final long THRESHOLD_PAGE_LOAD_COLD_MS = 8_000;
    /**
     * Cold TTFB threshold for website cycle 1.
     * The first visit has no open HTTP/2 connection to the site, so TTFB
     * includes a full TCP + TLS handshake. Cycles 2+ reuse the connection.
     */
    private static final long THRESHOLD_TTFB_COLD_MS      = 2_000;
    private static final long THRESHOLD_TTFB_MS           = 1_200;
    private static final long THRESHOLD_DCL_MS            = 4_000;
    private static final long THRESHOLD_DNS_LOOKUP_MS     = 200;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Prints the website performance report to stdout, grouped by tab.
     * One section per tab shows all refresh cycles with timing columns and
     * per-domain averages.
     *
     * @param metrics all collected {@link WebsiteMetrics} records
     */
    public void printWebsiteReport(List<WebsiteMetrics> metrics) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════");
        System.out.println("                    WEBSITE PERFORMANCE REPORT  (6 refresh cycles × 5 s)");
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
        System.out.println("══════════════════════════════════════════════════════════════════════════");

        // Group by domain then tool
        Map<String, Map<String, List<DnsResult>>> byDomainAndTool = results.stream()
            .collect(Collectors.groupingBy(
                DnsResult::getDomain,
                Collectors.groupingBy(DnsResult::getTool)
            ));

        System.out.printf("  %-25s  %-10s  %7s  %7s  %8s  %8s  %8s%n",
            "Domain", "Tool", "Rounds", "Success", "Avg(ms)", "Min(ms)", "Max(ms)");
        System.out.println("  " + "─".repeat(80));

        byDomainAndTool.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(domainEntry -> {
                String domain = domainEntry.getKey();
                domainEntry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(toolEntry -> printDnsRow(domain, toolEntry.getKey(), toolEntry.getValue()));
            });

        System.out.println();
    }

    // ── Website section helpers ───────────────────────────────────────────────
    /**
     * Prints the per-tab section header and all cycle rows for one website tab.
     *
     * @param tabIndex   the 1-based tab number
     * @param tabMetrics all metrics records for this tab, unsorted
     */    private void printTabSection(int tabIndex, List<WebsiteMetrics> tabMetrics) {
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

        for (WebsiteMetrics m : sorted) {
            if (!m.isSuccess()) {
                System.out.printf("  %-6d  %-14s%n", m.getRefreshCycle(), "ERROR: " + truncate(m.getErrorMessage(), 70));
                continue;
            }
            System.out.printf("  %-6d  %s  %s  %s  %s  %s%n",
                m.getRefreshCycle(),
                msCell(m.getPageLoadTime(),     THRESHOLD_PAGE_LOAD_MS),
                msCell(m.getTimeToFirstByte(),  THRESHOLD_TTFB_MS),
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

    /** Computes averages [pageLoad, ttfb, dcl, dns, tcp] over successful cycles. */
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
     * Prints a single aggregate summary row for one (domain, tool) combination
     * in the DNS monitoring report.
     *
     * @param domain the fully-qualified domain queried
     * @param tool   the resolver tool identifier (e.g. {@code "8.8.8.8"})
     * @param rows   all {@link DnsResult} records for this domain and tool
     */
    private void printDnsRow(String domain, String tool, List<DnsResult> rows) {
        List<DnsResult> succeeded = rows.stream().filter(DnsResult::isSuccess).toList();

        int total   = rows.size();
        int success = succeeded.size();
        double pct  = total == 0 ? 0 : (success * 100.0) / total;

        if (succeeded.isEmpty()) {
            // All entries failed — typically means the tool is not installed
            String reason = rows.stream()
                .map(DnsResult::getErrorMessage)
                .filter(e -> e != null && !e.isBlank())
                .findFirst().orElse("unknown");
            System.out.printf("  %-25s  %-10s  %7d  %7s  %-55s%n",
                domain, tool, total, "0%", "N/A — " + truncate(reason, 50));
            return;
        }

        LongSummaryStatistics stats = succeeded.stream()
            .mapToLong(DnsResult::getResponseTimeMs)
            .summaryStatistics();

        System.out.printf("  %-25s  %-10s  %7d  %6.0f%%  %8.0f  %8d  %8d%n",
            domain, tool, total, pct,
            stats.getAverage(), stats.getMin(), stats.getMax());
    }

    // ── Final summary PASS/FAIL ─────────────────────────────────────────────

    /**
     * Prints a compact combined PASS/FAIL summary for website loading and DNS
     * queries. Called after all detail reports have been printed.
     *
     * Website criteria  — PASS if: success rate ≥ 80%
     *                               AND avg page load ≤ 5000 ms
     *                               AND avg TTFB ≤ 1200 ms
     * DNS criteria      — PASS if: success rate ≥ 80%
     *                               AND avg response time ≤ 1000 ms
     */
    /**
     * Prints the combined FINAL REPORT covering YouTube videos, website
     * performance, and DNS monitoring.
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
        System.out.println();
        System.out.println("  ╔══ YOUTUBE VIDEO PERFORMANCE ══════════════════════════════════════════");
        System.out.println("  ║  Internet checks per video (7 total):");
        System.out.println("  ║    DNS Lookup ≤ 200 ms  │  TCP RTT ≤ 200 ms  │  TTFB ≤ 3000 ms (cold) / 1200 ms (warm)");
        System.out.println("  ║    Page Load ≤ 15000 ms (cold) / 12000 ms (warm)  │  Buffer ≥ 10 s  │  Avg BW ≥ 250 KB/s");
        System.out.println("  ║    Quality Stability: video must not be downgraded by YouTube's ABR during playback");
        System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  %-5s  %-45s  %-18s  %8s  %6s  %s%n",
            "Tab", "Video Title", "Quality", "Checks", "Status", "Failed checks");
        System.out.println("  " + "─".repeat(110));

        boolean anyYt = ytVerdicts != null && !ytVerdicts.isEmpty();
        if (anyYt) {
            for (VideoVerdict v : ytVerdicts) {
                String title = v.getMetrics().getPageTitle();
                if (title == null || title.isBlank()) title = v.getMetrics().getUrl();
                String failedChecks = v.getChecks().stream()
                    .filter(c -> !c.isPassed())
                    .map(c -> c.getCheckName() + ": " + c.getActual() + " (threshold " + c.getExpected() + ")")
                    .collect(Collectors.joining(", "));

                // Build a concise quality summary for this video:
                //   "hd1080 (stable)"        — stayed at peak the whole time
                //   "hd1080 → hd720"         — ABR downgraded at least once
                //   "N/A"                    — quality was never reported as a specific level
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

        // ── 2. Website Performance ────────────────────────────────────────────
        System.out.println();
        System.out.println("  ╔══ WEBSITE PERFORMANCE ════════════════════════════════════════════════");
        System.out.printf("  ║  Thresholds — warm (cycles 2+): Page Load ≤ %d ms │ TTFB ≤ %d ms%n",
            THRESHOLD_PAGE_LOAD_MS, THRESHOLD_TTFB_MS);
        System.out.printf("  ║  Thresholds — cold (cycle 1):   Page Load ≤ %d ms │ TTFB ≤ %d ms  (no cache, new TCP+TLS)%n",
            THRESHOLD_PAGE_LOAD_COLD_MS, THRESHOLD_TTFB_COLD_MS);
        System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  %-22s  %5s  %12s  %10s  %10s  %-8s  %s%n",
            "Domain", "Cycle", "Page Load", "TTFB", "DCL", "Status", "Reason");
        System.out.println("  " + "─".repeat(95));

        boolean anyWebResults = webMetrics != null && !webMetrics.isEmpty();
        if (anyWebResults) {
            webMetrics.stream()
                .collect(Collectors.groupingBy(WebsiteMetrics::getDomain))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> printWebsiteCycleRows(e.getKey(), e.getValue()));
        } else {
            System.out.println("  No website results.");
        }

        // ── 3. DNS Monitoring ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("  ╔══ DNS MONITORING ══════════════════════════════════════════════════════");
        System.out.println("  ║  PASS = direct UDP query to 8.8.8.8 (Google DNS) resolved successfully \u2014 real internet connectivity confirmed.");
        System.out.println("  ║  FAIL = query timed out or returned an error \u2014 DNS/internet broken at time of query.");
        System.out.println("  ║  Times are true round-trip latency to 8.8.8.8: 10\u2013100 ms = healthy, >500 ms = congested, timeout = down.");
        System.out.println("  ╚═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  %-20s  %-24s  %6s  %10s  %-8s  %s%n",
            "Domain", "Tool", "Round", "Time(ms)", "Status", "Reason");
        System.out.println("  " + "─".repeat(90));

        boolean anyDnsResults = dnsResults != null && !dnsResults.isEmpty();
        if (anyDnsResults) {
            // Group by domain+tool to keep entries together, then print each query row
            dnsResults.stream()
                .collect(Collectors.groupingBy(r -> r.getDomain() + "||" + r.getTool()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|\\|");
                    printDnsQueryRows(parts[0], parts[1], e.getValue());
                });
        } else {
            System.out.println("  No DNS results.");
        }

        // ── 4. Overall verdict ────────────────────────────────────────────────
        // ytVerdicts == null  → YouTube was not attempted at all  → N/A
        // ytVerdicts is empty → YouTube was attempted but crashed → FAIL (0/0)
        // ytVerdicts non-empty → normal evaluation
        boolean ytAttempted  = ytVerdicts != null;
        boolean anyYtResults = ytAttempted && !ytVerdicts.isEmpty();
        boolean allYtPass    = anyYtResults && ytVerdicts.stream().allMatch(VideoVerdict::isPassed);
        long    ytPass       = anyYtResults ? ytVerdicts.stream().filter(VideoVerdict::isPassed).count() : 0;
        long    ytTotal      = anyYtResults ? ytVerdicts.size() : 0;
        boolean allWebPass = anyWebResults && webMetrics.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getDomain))
            .values().stream().allMatch(this::websiteDomainPasses);
        boolean allDnsPass = anyDnsResults && dnsResults.stream()
            .collect(Collectors.groupingBy(r -> r.getDomain() + "||" + r.getTool()))
            .values().stream().allMatch(this::dnsDomainToolPasses);

        System.out.println();
        System.out.println("  ╔══ OVERALL RESULT ══════════════════════════════════════════════════════");
        System.out.printf("  ║  %-50s  [%s]%s%n",
            "YouTube Performance ("+ytPass+"/"+ytTotal+" videos passed)",
            !ytAttempted ? "N/A" : allYtPass ? "PASS" : "FAIL",
            "");
        System.out.printf("  ║  %-50s  [%s]%n", "Website Performance",
            !anyWebResults ? "N/A" : allWebPass ? "PASS" : "FAIL");
        System.out.printf("  ║  %-50s  [%s]%n", "DNS Monitoring",
            !anyDnsResults ? "N/A" : allDnsPass ? "PASS" : "FAIL");
        // Overall passes only if every attempted probe passed; a probe that was
        // attempted but produced no results (empty list) counts as a failure.
        boolean overallPass = (!ytAttempted || allYtPass) && (!anyWebResults || allWebPass) && (!anyDnsResults || allDnsPass);
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
    private void printDnsQueryRows(String domain, String tool, List<DnsResult> rows) {
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
                domain, tool, round,
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
        long loadLimit = (m.getRefreshCycle() == 1) ? THRESHOLD_PAGE_LOAD_COLD_MS : THRESHOLD_PAGE_LOAD_MS;
        long ttfbLimit = (m.getRefreshCycle() == 1) ? THRESHOLD_TTFB_COLD_MS      : THRESHOLD_TTFB_MS;
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
        if (isCold) {
            // Always note cold-start for cycle 1 so the reader understands why a
            // high TTFB or page load still passes: relaxed thresholds apply here.
            reasons.add("cold start (TTFB ≤ " + THRESHOLD_TTFB_COLD_MS + " ms, load ≤ " + THRESHOLD_PAGE_LOAD_COLD_MS + " ms)");
        }
        if (m.getPageLoadTime() < 0) {
            reasons.add("pageLoad N/A");
        } else {
            long loadLimit = isCold ? THRESHOLD_PAGE_LOAD_COLD_MS : THRESHOLD_PAGE_LOAD_MS;
            if (m.getPageLoadTime() > loadLimit)
                reasons.add("pageLoad " + m.getPageLoadTime() + " ms (> " + loadLimit + ")");
        }
        if (m.getTimeToFirstByte() < 0) {
            reasons.add("TTFB N/A");
        } else {
            long ttfbLimit = isCold ? THRESHOLD_TTFB_COLD_MS : THRESHOLD_TTFB_MS;
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
