package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Entry point. Runs three probes in parallel:
 *   1. YouTube performance tester — one Chrome, N YouTube tabs, configurable monitoring window
 *   2. Website tester             — one Chrome, M website tabs, refresh every 5 s
 *   3. DNS monitor                — direct UDP queries to 8.8.8.8 throughout the window
 *
 * URLs, domains, and duration are loaded from {@code src/main/resources/test.properties}.
 * Duration can also be supplied on the command line: {@code --duration <seconds>}.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Application entry point. Loads configuration, then runs three probes concurrently:
     * YouTube playback tester, website refresh tester, and DNS monitor.
     * Results from all three probes are collected and printed as a combined final report.
     *
     * @param args command-line arguments; {@code --duration <seconds>} overrides the
     *             {@code test.duration.seconds} value from {@code test.properties}
     */
    public static void main(String[] args) {
        // Load configuration from test.properties (CLI --duration overrides file value)
        TestConfig config;
        try {
            config = TestConfig.load(args);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to load test.properties: " + e.getMessage());
            System.exit(1);
            return;
        }

        List<String> youtubeUrls = config.getYoutubeUrls();
        List<String> websiteUrls = config.getWebsiteUrls();
        List<String> dnsDomains  = config.getDnsDomains();
        int durationSeconds      = config.getDurationSeconds();
        // ── Windows UTF-8 console fix ────────────────────────────────────────
        //
        // Two independent layers must both use UTF-8 for box-drawing characters
        // (╔══, ║, ✔, ≤, ←) to survive on Windows.
        //
        //   Layer 1 – Java's encoding (fixed here):
        //   We write through FileDescriptor directly rather than wrapping the
        //   existing System.out PrintStream. Wrapping a PrintStream with another
        //   one introduces a second charset-encoding step that produces
        //   "Γòö"/"Γ£ô" mojibake. FileDescriptor.out/err bypasses the existing
        //   stream and writes raw UTF-8 bytes straight to the OS handle.
        //
        //   Layer 2 – How the PARENT SHELL decodes those bytes:
        //
        //   • cmd.exe (run.bat / direct "java -jar ..." from cmd.exe):
        //     cmd.exe does NOT re-encode child-process output; bytes go straight
        //     to the console window. The console code page decides how they are
        //     displayed. We call SetConsoleOutputCP(65001) via kernel32 (JNA is
        //     already bundled in the fat JAR through Selenium's transitive deps)
        //     so the console treats our UTF-8 bytes as UTF-8. ✔
        //
        //   • PowerShell (pwsh / powershell.exe):
        //     PowerShell always intercepts external-command stdout and re-encodes
        //     it through [Console]::OutputEncoding. That property is baked into
        //     the StreamReader PowerShell creates BEFORE Java starts, so no
        //     SetConsoleOutputCP call from inside Java can influence it.
        //     To fix this, the shell must set its own encoding FIRST:
        //       • Use the provided wrapper:  .\run.ps1
        //       • Or prefix manually:
        //           [Console]::OutputEncoding=[Text.Encoding]::UTF8; java -jar ...
        //       • Or add to $PROFILE for a permanent fix.
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                // SetConsoleOutputCP(65001) — fixes cmd.exe / run.bat callers.
                // No-op (but harmless) under PowerShell for the reason above.
                com.sun.jna.NativeLibrary.getInstance("kernel32")
                    .getFunction("SetConsoleOutputCP")
                    .invoke(new Object[]{65001});
            } catch (Throwable ignored) {}
        }

        try {
            System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║         YouTube + Website Performance Tester          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("Duration     : %d s%n", durationSeconds);
        System.out.printf("YouTube tabs : %d%n", youtubeUrls.size());
        System.out.printf("Website tabs : %d  (refresh every 5 s for %d s)%n", websiteUrls.size(), durationSeconds);
        System.out.printf("DNS monitor  : 8.8.8.8 (direct UDP, no OS cache) on %d domains for %d s  [%s]%n%n",
            dnsDomains.size(), durationSeconds, String.join(", ", dnsDomains));

        logger.info("Launching all three probes in parallel...");

        // Barrier for the two browser probes: YouTube and Website open their tabs
        // first, then count down. Once both are ready the barrier opens and they
        // start their measurement windows at the same instant.
        // DNS is intentionally excluded from this barrier — it starts immediately
        // so it can capture any connectivity failures that occur while the browsers
        // are still opening tabs (e.g. a WiFi disconnect during setup).
        CountDownLatch startLatch  = new CountDownLatch(2);
        // Counted down by Main after browser probes complete, signalling DNS to stop.
        CountDownLatch dnsStopLatch = new CountDownLatch(1);

        // ── Submit all three probes concurrently ─────────────────────────────
        ExecutorService executor = Executors.newFixedThreadPool(3);

        Future<List<VideoMetrics>>   ytFuture  = executor.submit(
            () -> new YouTubePerformanceTester().runTest(youtubeUrls, startLatch, durationSeconds));
        Future<List<WebsiteMetrics>> webFuture = executor.submit(
            new WebsiteTester(websiteUrls, startLatch, durationSeconds));
        Future<List<DnsResult>>      dnsFuture = executor.submit(
            new DnsMonitor(dnsDomains, dnsStopLatch));

        executor.shutdown();

        // ── Collect results ───────────────────────────────────────────────────
        List<VideoMetrics>   ytResults  = null;
        List<WebsiteMetrics> webResults = null;
        List<DnsResult>      dnsResults = null;

        // Each probe is collected independently so a failure in one does not
        // discard results already gathered by the other.
        try {
            ytResults = ytFuture.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Log only the root cause message — ExecutionException wraps a verbose
            // Selenium exception that includes the full browser capabilities dump,
            // which makes logs unreadable. The root cause is the real signal.
            logger.error("YouTube probe failed: {}", rootCause(e));
            ytFuture.cancel(true);
        }

        try {
            // Website may have partial results even if YouTube failed — always collect.
            webResults = webFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Website probe failed: {}", rootCause(e));
            webFuture.cancel(true);
        } finally {
            // Always signal DNS to stop after both browser probes are done.
            dnsStopLatch.countDown();
        }

        try {
            // Allow DNS extra time beyond the test duration for final round to complete
            dnsResults = dnsFuture.get(durationSeconds + 60L, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("DNS probe failed: {}", rootCause(e));
            dnsFuture.cancel(true);
        }

        // ── Print YouTube detail report + save files ──────────────────────────
        if (ytResults != null && !ytResults.isEmpty()) {
            PerformanceReporter reporter = new PerformanceReporter();
            reporter.printConsoleReport(ytResults);
            reporter.saveCsvReport(ytResults, "youtube_performance_report.csv");
            reporter.saveJsonReport(ytResults, "youtube_performance_report.json");
        } else {
            System.out.println("\n[YouTube] No results collected.");
        }

        // ── Print Website + DNS detail reports ────────────────────────────────
        WebsiteReporter websiteReporter = new WebsiteReporter();

        if (webResults != null && !webResults.isEmpty()) {
            websiteReporter.printWebsiteReport(webResults);
        } else {
            System.out.println("\n[Website] No results collected.");
        }

        if (dnsResults != null && !dnsResults.isEmpty()) {
            websiteReporter.printDnsReport(dnsResults);
        } else {
            System.out.println("\n[DNS] No results collected.");
        }

        // ── Combined Final Report: YouTube + Website + DNS ────────────────────
        // Pass an empty list (not null) when YouTube was attempted but produced no
        // metrics — this lets printFinalSummary distinguish "not attempted" (null)
        // from "attempted but failed" (empty list), so it can show [FAIL] rather
        // than [N/A] when YouTube failed to load.
        List<VideoVerdict> verdicts = (ytResults != null && !ytResults.isEmpty())
            ? new PerformanceEvaluator().evaluateAll(ytResults)
            : (ytResults != null ? java.util.Collections.emptyList() : null);
        websiteReporter.printFinalSummary(verdicts, webResults, dnsResults);

        logger.info("All reports complete.");
    }

    /**
     * Returns the first line of the deepest cause in an exception chain.
     * {@link java.util.concurrent.ExecutionException} wraps the real exception, and Selenium
     * exceptions include the full browser capabilities block after the first line —
     * only the first line is useful as a log summary.
     *
     * @param t the exception (possibly wrapped)
     * @return the first line of the root cause message, or the simple class name if no message
     */
    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null) return cause.getClass().getSimpleName();
        // Selenium messages contain the browser capability dump after a newline — strip it.
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl).trim() : msg.trim();
    }
}
