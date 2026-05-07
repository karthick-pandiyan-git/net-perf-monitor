package com.youtube.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Renders performance results to the console and saves them as CSV and JSON.
 */
public class PerformanceReporter {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceReporter.class);

    private static final String[] READY_STATE_LABELS = {
        "HAVE_NOTHING", "HAVE_METADATA", "HAVE_CURRENT_DATA",
        "HAVE_FUTURE_DATA", "HAVE_ENOUGH_DATA"
    };
    private static final String[] NETWORK_STATE_LABELS = {
        "NETWORK_EMPTY", "NETWORK_IDLE", "NETWORK_LOADING", "NETWORK_NO_SOURCE"
    };

    // ── Console output ────────────────────────────────────────────────────────
    /**
     * Prints the full YouTube performance report to stdout.
     * For each tab the report shows connection metrics, page-loading metrics,
     * streaming throughput with a per-sample breakdown, then a sorted summary
     * table at the end.
     *
     * @param metrics list of collected {@link VideoMetrics}, one per tab
     */
    public void printConsoleReport(List<VideoMetrics> metrics) {
        String sep  = "=".repeat(110);
        String dash = "-".repeat(110);

        System.out.println("\n" + sep);
        System.out.printf("  YOUTUBE PERFORMANCE REPORT   %s%n",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println(sep);

        for (VideoMetrics m : metrics) {
            System.out.printf("%nTab #%d  |  %s%n", m.getTabIndex(), m.getUrl());
            System.out.printf("Title  : %s%n", m.getPageTitle());
            System.out.printf("Collected at: %s%n",
                Instant.ofEpochMilli(m.getMetricsCollectedAt())
                       .atZone(ZoneId.systemDefault())
                       .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            System.out.println(dash);

            // ── Connection metrics (latency / resolver) ──────────────────
            System.out.println("  [ CONNECTION ]");
            printRow("TTFB",          formatMs(m.getTimeToFirstByte())   + "  ← server latency" +
                (m.getTabIndex() == 1 ? " (cold conn)" : " (warm conn)"));

            // ── Loading metrics (bandwidth + latency together) ────────────
            System.out.println("  [ PAGE LOADING ]");
            printRow("Page Load Time",     formatMs(m.getPageLoadTime()));
            printRow("DOM Content Loaded", formatMs(m.getDomContentLoadedTime()));

            // ── Streaming / throughput metrics ────────────────────────────
            List<NetworkSample> samples = m.getNetworkSamples();
            System.out.println("  [ STREAMING THROUGHPUT ]");
            if (samples != null && !samples.isEmpty()) {
                // When avgBandwidthKBps < 1 KB/s but totalVideoSegmentBytes > 1 MB,
                // the video was fully pre-buffered before the monitoring window — excellent.
                // Use a 1 KB/s threshold rather than exact zero to absorb floating-point
                // rounding noise from the JavaScript bandwidth calculation.
                boolean preBuffered = m.getAvgBandwidthKBps() < 1.0
                    && m.getTotalVideoSegmentBytes() > 1_000_000;
                if (preBuffered) {
                    printRow("Avg Bandwidth",  "pre-buffered ← all data loaded before monitoring window");
                    printRow("Peak Bandwidth", "pre-buffered");
                } else {
                    printRow("Avg Bandwidth",  String.format("%.2f KB/s  (%.1f Mbps)",
                        m.getAvgBandwidthKBps(), m.getAvgBandwidthKBps() * 8 / 1024));
                    printRow("Peak Bandwidth", String.format("%.2f KB/s  (%.1f Mbps)",
                        m.getPeakBandwidthKBps(), m.getPeakBandwidthKBps() * 8 / 1024));
                }
                printRow("Total Data",     formatBytes(m.getTotalVideoSegmentBytes()) + "  ← transferred in ~30 s");
                printRow("Buffer Depth",   String.format("%.1f s ahead  ← pre-loaded at test end",
                    m.getBufferedSeconds()));

                System.out.printf("  %-8s  %-8s  %-18s  %-13s  %-13s  %-9s  %s%n",
                    "Sample", "@Time", "Bandwidth", "Played(s)", "Buffered(s)", "Quality", "New Segs");
                System.out.println("  " + "-".repeat(90));
                for (NetworkSample s : samples) {
                    // When the player downloaded nothing this window, annotate why:
                    // < 5 s ahead  → genuinely low buffer, potential stall
                    // 5–10 s ahead → normal ABR lazy loading (player may burst-fetch soon)
                    // > 10 s ahead → player is deliberately idle, buffer is healthy
                    double bufferAhead = s.getVideoBuffered() - s.getVideoCurrentTime();
                    String bwDisplay = s.getRecentSegmentCount() == 0 && !s.isAdPlaying()
                        ? (bufferAhead >= 10.0 ? "0.0 KB/s (idle)"
                          : bufferAhead >= 5.0  ? "0.0 KB/s (lazy)"
                          :                       "0.0 KB/s (!low)")
                        : String.format("%.1f KB/s", s.getBandwidthKBps());
                    System.out.printf("  #%-7d  +%-6ds  %-18s  %-13s  %-13s  %-9s  %d%n",
                        s.getSampleNumber(),
                        s.getSampleNumber() * 5,
                        bwDisplay,
                        String.format("%.1f", s.getVideoCurrentTime()),
                        String.format("%.1f", s.getVideoBuffered()),
                        s.isAdPlaying() ? "[AD]" : (s.getQualityLabel() != null ? s.getQualityLabel() : "?"),
                        s.getRecentSegmentCount());
                }
            } else {
                printRow("Bandwidth", "N/A (no samples collected)");
            }

            if (m.getErrorMessage() != null) {
                System.out.printf("%n  !! ERROR: %s%n", m.getErrorMessage());
            }

            // ── Stats for Nerds section ───────────────────────────────────────
            StatsForNerdsData sfn = m.getSfnData();
            if (sfn != null && sfn.isAvailable()) {
                System.out.println("  [ STATS FOR NERDS ]");
                if (sfn.getConnectionSpeedKbps() >= 0)
                    printRow("Connection Speed",
                        String.format("%.0f Kbps  (%.1f Mbps)",
                            sfn.getConnectionSpeedKbps(), sfn.getConnectionSpeedKbps() / 1000.0));
                if (sfn.getNetworkActivityKB() >= 0)
                    printRow("Network Activity", sfn.getNetworkActivityKB() + " KB");
                if (sfn.getBufferHealthSecs() >= 0)
                    printRow("Buffer Health", String.format("%.2f s", sfn.getBufferHealthSecs()));
                if (sfn.getCurrentResolution() != null)
                    printRow("Current Res", sfn.getCurrentResolution()
                        + (sfn.getOptimalResolution() != null
                            ? "  /  optimal: " + sfn.getOptimalResolution()
                            : ""));
                if (sfn.getVideoCodec() != null || sfn.getAudioCodec() != null)
                    printRow("Codecs", sfn.codecSummary()
                        + "  (" + (sfn.getVideoCodec() != null ? sfn.getVideoCodec() : "?")
                        + " / " + (sfn.getAudioCodec() != null ? sfn.getAudioCodec() : "?") + ")");
                if (sfn.getTotalFrames() >= 0)
                    printRow("Frames (total/drop)", sfn.getTotalFrames() + " / " + sfn.getDroppedFrames());
            }

            // ── Quality checks section ────────────────────────────────────────
            VideoVerdict verdict = new PerformanceEvaluator().evaluate(m);
            List<CheckResult> checks = verdict.getChecks();
            if (!checks.isEmpty()) {
                System.out.println("  [ QUALITY CHECKS ]");
                System.out.printf("  %-35s  %-30s  %-25s  %s%n",
                    "Check", "Threshold", "Actual", "Status");
                System.out.println("  " + "-".repeat(100));
                for (CheckResult c : checks) {
                    System.out.printf("  %-35s  %-30s  %-25s  [%s]%n",
                        c.getCheckName(),
                        c.getExpected(),
                        c.getActual(),
                        c.isPassed() ? "PASS" : "FAIL");
                }
            }
        }

        printSummaryTable(metrics);
    }

    /**
     * Prints a single labelled row to the console report.
     *
     * @param label the left-hand column label (up to 22 characters)
     * @param value the right-hand column value
     */
    private void printRow(String label, String value) {
        System.out.printf("  %-22s: %s%n", label, value);
    }

    /**
     * Prints a compact summary table for all tabs, sorted by TTFB
     * (best network connection first).
     *
     * @param metrics list of collected {@link VideoMetrics}, one per tab
     */
    private void printSummaryTable(List<VideoMetrics> metrics) {
        String sep = "=".repeat(120);
        System.out.println("\n" + sep);
        System.out.println("  INTERNET PERFORMANCE SUMMARY  (sorted by TTFB — best connection first)");
        System.out.println(sep);
        System.out.printf("%-4s  %-9s  %-10s  %-14s  %-14s  %-12s  %s%n",
            "Tab", "TTFB(ms)", "Load(ms)",
            "Avg BW (KB/s)", "Peak BW(KB/s)", "Buffer(s)", "Title");
        System.out.println("-".repeat(120));

        metrics.stream()
               .sorted(Comparator.comparingLong(VideoMetrics::getTimeToFirstByte))
               .forEach(m -> {
                   String avgBw  = m.getAvgBandwidthKBps()  > 0 ? String.format("%.0f", m.getAvgBandwidthKBps())  : "N/A";
                   String peakBw = m.getPeakBandwidthKBps() > 0 ? String.format("%.0f", m.getPeakBandwidthKBps()) : "N/A";
                   System.out.printf("%-4d  %-9s  %-10s  %-14s  %-14s  %-12s  %s%n",
                       m.getTabIndex(),
                       formatMs(m.getTimeToFirstByte()),
                       formatMs(m.getPageLoadTime()),
                       avgBw, peakBw,
                       String.format("%.1f s", m.getBufferedSeconds()),
                       truncate(m.getPageTitle(), 35));
               });

        System.out.println(sep + "\n");
    }

    // ── CSV output ────────────────────────────────────────────────────────────
    /**
     * Saves a CSV report to {@code filename}, with one row per tab.
     * On error the exception is logged and no file is written.
     *
     * @param metrics  list of collected {@link VideoMetrics}, one per tab
     * @param filename target file path (relative or absolute)
     */
    public void saveCsvReport(List<VideoMetrics> metrics, String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println(
                "Tab,URL,Title,PageLoad_ms,DOMContentLoaded_ms,TTFB_ms,DNS_ms,TCP_ms," +
                "DOMInteractive_ms,VideoFound,Width,Height,CurrentTime_s,Duration_s," +
                "Buffered_s,PlaybackRate,ReadyState,NetworkState,Paused," +
                "TotalFrames,DroppedFrames,CorruptedFrames,DropRate_pct," +
                "SFN_ConnectionSpeed_Kbps,SFN_BufferHealth_s,SFN_NetworkActivity_KB," +
                "SFN_CurrentRes,SFN_OptimalRes,SFN_VideoCodec,SFN_AudioCodec,Error"
            );

            for (VideoMetrics m : metrics) {
                StatsForNerdsData sfn = m.getSfnData();
                pw.printf("%d,\"%s\",\"%s\",%s,%s,%s,%s,%s,%s,%b,%d,%d,%.2f,%.2f,%.2f,%.2f,%s,%s,%b,%s,%s,%s,%.2f,%s,%s,%s,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                    m.getTabIndex(),
                    csvEscape(m.getUrl()),
                    csvEscape(m.getPageTitle()),
                    m.getPageLoadTime(), m.getDomContentLoadedTime(),
                    m.getTimeToFirstByte(), m.getDnsLookupTime(),
                    m.getTcpConnectionTime(), m.getDomInteractiveTime(),
                    m.isVideoFound(),
                    m.getVideoWidth(), m.getVideoHeight(),
                    m.getCurrentTime(), m.getDuration(), m.getBufferedSeconds(),
                    m.getPlaybackRate(),
                    readyLabel(m.getReadyState()),
                    networkLabel(m.getNetworkState()),
                    m.isPaused(),
                    m.getTotalVideoFrames(), m.getDroppedVideoFrames(), m.getCorruptedVideoFrames(),
                    m.getDroppedFrameRatePct(),
                    sfn != null && sfn.getConnectionSpeedKbps() >= 0 ? String.format("%.0f", sfn.getConnectionSpeedKbps()) : "",
                    sfn != null && sfn.getBufferHealthSecs() >= 0    ? String.format("%.2f", sfn.getBufferHealthSecs())    : "",
                    sfn != null && sfn.getNetworkActivityKB() >= 0   ? String.valueOf(sfn.getNetworkActivityKB())          : "",
                    csvEscape(sfn != null ? sfn.getCurrentResolution() : null),
                    csvEscape(sfn != null ? sfn.getOptimalResolution()  : null),
                    csvEscape(sfn != null ? sfn.getVideoCodec()         : null),
                    csvEscape(sfn != null ? sfn.getAudioCodec()         : null),
                    csvEscape(m.getErrorMessage())
                );
            }

            System.out.println("CSV report saved  → " + new File(filename).getAbsolutePath());
            logger.info("CSV report saved to {}", filename);

        } catch (IOException e) {
            logger.error("Failed to save CSV report", e);
        }
    }

    // ── JSON output ───────────────────────────────────────────────────────────
    /**
     * Saves a pretty-printed JSON report to {@code filename} using Jackson.
     * On error the exception is logged and no file is written.
     *
     * @param metrics  list of collected {@link VideoMetrics}, one per tab
     * @param filename target file path (relative or absolute)
     */
    public void saveJsonReport(List<VideoMetrics> metrics, String filename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(filename), metrics);
            System.out.println("JSON report saved → " + new File(filename).getAbsolutePath());
            logger.info("JSON report saved to {}", filename);
        } catch (IOException e) {
            logger.error("Failed to save JSON report", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    /**
     * Formats a timing value as a human-readable millisecond string.
     * Returns {@code "N/A"} for negative values.
     *
     * @param value the timing in milliseconds
     * @return e.g. {@code "245 ms"} or {@code "N/A"}
     */
    private String formatMs(long value) {
        return value >= 0 ? value + " ms" : "N/A";
    }

    /**
     * Converts an {@code HTMLMediaElement.readyState} integer to a human-readable label.
     *
     * @param state the readyState value (0 – 4)
     * @return e.g. {@code "HAVE_ENOUGH_DATA (4)"} or {@code "UNKNOWN (-1)"}
     */
    private String readyLabel(int state) {
        return (state >= 0 && state < READY_STATE_LABELS.length)
               ? READY_STATE_LABELS[state] + " (" + state + ")"
               : "UNKNOWN (" + state + ")";
    }

    /**
     * Converts an {@code HTMLMediaElement.networkState} integer to a human-readable label.
     *
     * @param state the networkState value (0 – 3)
     * @return e.g. {@code "NETWORK_LOADING (2)"} or {@code "UNKNOWN (-1)"}
     */
    private String networkLabel(int state) {
        return (state >= 0 && state < NETWORK_STATE_LABELS.length)
               ? NETWORK_STATE_LABELS[state] + " (" + state + ")"
               : "UNKNOWN (" + state + ")";
    }

    /**
     * Truncates {@code s} to at most {@code max} characters, appending
     * {@code "…"} (ellipsis) when truncation occurs. Returns an empty string
     * for {@code null} input.
     *
     * @param s   the string to truncate
     * @param max maximum number of characters to retain (inclusive)
     * @return the (possibly truncated) string, never {@code null}
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Escapes double-quote characters in a string for safe inclusion in a CSV field.
     * A single {@code "} is replaced with {@code ""}.
     *
     * @param s the string to escape; {@code null} is treated as empty
     * @return the CSV-safe string
     */
    private String csvEscape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    /**
     * Formats a byte count as a human-readable string (B, KB, or MB).
     * Returns {@code "N/A"} for negative values.
     *
     * @param bytes the byte count
     * @return e.g. {@code "1.23 MB"}, {@code "512.0 KB"}, or {@code "N/A"}
     */
    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

}
