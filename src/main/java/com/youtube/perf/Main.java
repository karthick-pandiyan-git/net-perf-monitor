package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
        Set<String>  mode        = config.getMode();

        // Derive which probes to run from the mode set
        boolean runYoutube = mode.contains("youtube");
        boolean runWebsite = mode.contains("website");
        boolean runDns     = mode.contains("dns");

        // Human-readable mode label for output (sorted for consistency)
        String modeLabel = mode.stream().sorted().collect(Collectors.joining(","));
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

        List<String> probeNames = new java.util.ArrayList<>();
        if (runYoutube) probeNames.add("YouTube");
        if (runWebsite) probeNames.add("Website");
        if (runDns)     probeNames.add("DNS");
        String title = String.join(" + ", probeNames) + " Performance Monitor";
        // Centre the title inside the 54-character inner width of the banner box.
        int innerWidth = 54;
        int padding = Math.max(0, (innerWidth - title.length()) / 2);
        String centred = " ".repeat(padding) + title;
        centred = centred + " ".repeat(Math.max(0, innerWidth - centred.length()));
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║" + centred + "║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("Mode         : %s%n", modeLabel);
        System.out.printf("Duration     : %d s%n", durationSeconds);
        if (runYoutube) System.out.printf("YouTube tabs : %d%n", youtubeUrls.size());
        if (runWebsite) System.out.printf("Website tabs : %d  (refresh every 5 s for %d s)%n", websiteUrls.size(), durationSeconds);
        if (runDns)     System.out.printf("DNS monitor  : 8.8.8.8 (direct UDP, no OS cache) on %d domains for %d s  [%s]%n",
            dnsDomains.size(), durationSeconds, String.join(", ", dnsDomains));
        int reportIntervalSecs = config.getReportIntervalSeconds();
        if (reportIntervalSecs > 0) System.out.printf("Report every : %d s%n", reportIntervalSecs);
        System.out.println();

        logger.info("Launching probes in parallel (mode={})...", modeLabel);

        // ── Thread-safe shared lists for live in-progress results ────────────
        // Each probe writes into its own CopyOnWriteArrayList so the periodic
        // reporter thread can read a safe snapshot at any time without locks.
        // YouTube: entries are added by YouTubePerformanceTester itself once tabs
        // are open. The list is empty during browser setup, which is correct —
        // a snapshot that fires during setup simply shows no YouTube data yet.
        List<VideoMetrics>   liveYt  = runYoutube ? new CopyOnWriteArrayList<>() : null;
        List<WebsiteMetrics> liveWeb = runWebsite ? new CopyOnWriteArrayList<>() : null;
        List<DnsResult>      liveDns = runDns     ? new CopyOnWriteArrayList<>() : null;

        // startLatch synchronises the two browser probes so their measurement
        // windows begin at the same moment. Count equals the number of browser
        // probes that are actually running.
        int browserProbeCount = (runYoutube ? 1 : 0) + (runWebsite ? 1 : 0);
        CountDownLatch startLatch   = new CountDownLatch(browserProbeCount);
        // Counted down after all browser probes finish to signal DNS to stop.
        CountDownLatch dnsStopLatch = new CountDownLatch(1);

        // ── Submit selected probes concurrently ──────────────────────────────
        int poolSize = (runYoutube ? 1 : 0) + (runWebsite ? 1 : 0) + (runDns ? 1 : 0);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, poolSize));

        Future<List<VideoMetrics>>   ytFuture  = null;
        Future<List<WebsiteMetrics>> webFuture = null;
        Future<List<DnsResult>>      dnsFuture = null;

        // Absolute deadline from program launch — setup time (Chrome opening, page loads)
        // is consumed from this budget so total wall-clock time equals durationSeconds.
        final long programStartMs     = System.currentTimeMillis();
        final long absoluteDeadlineMs = programStartMs + durationSeconds * 1000L;

        if (runYoutube) {
            final List<VideoMetrics> ly = liveYt;
            ytFuture = executor.submit(
                () -> new YouTubePerformanceTester().runTest(youtubeUrls, startLatch, durationSeconds, ly, programStartMs));
        }
        if (runWebsite) {
            webFuture = executor.submit(
                new WebsiteTester(websiteUrls, startLatch, durationSeconds, liveWeb, programStartMs));
        }
        if (runDns) {
            dnsFuture = executor.submit(new DnsMonitor(dnsDomains, dnsStopLatch, liveDns));
        }

        executor.shutdown();

        // When no browser probes are running, the dnsStopLatch will never be
        // counted down by probe collection. A timer thread handles it instead,
        // stopping DNS at the absolute deadline.
        if (runDns && browserProbeCount == 0) {
            Thread timer = new Thread(() -> {
                try {
                    long remaining = absoluteDeadlineMs - System.currentTimeMillis();
                    if (remaining > 0) Thread.sleep(remaining);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                dnsStopLatch.countDown();
            });
            timer.setDaemon(true);
            timer.setName("dns-stop-timer");
            timer.start();
        }

        // ── Periodic JSON reporter ────────────────────────────────────────────
        // The scheduler starts only AFTER the measurement window opens (both
        // browser probes have synced on startLatch).  This ensures that
        // "--report 5" means "every 5 s of actual measurement time", not
        // "5 s from program launch" (which includes browser setup time).
        //
        // For DNS-only mode startLatch has count 0, so await() returns
        // immediately and the scheduler starts right away.
        AtomicReference<ScheduledExecutorService> schedulerRef = new AtomicReference<>();
        AtomicInteger snapshotNum    = new AtomicInteger(0);
        // Tracks the start of the current snapshot window so each snapshot
        // only includes data collected SINCE the previous snapshot fired.
        AtomicLong windowStartTs = new AtomicLong(0);

        if (reportIntervalSecs > 0) {
            final String lm       = modeLabel;
            final int    dur      = durationSeconds;
            final long   startMs  = programStartMs;
            final long   intervalMs = reportIntervalSecs * 1000L;
            final CountDownLatch sl = startLatch;

            // Snapshot windows are anchored to programStartMs, not to when setup
            // completes.  With "--report 30" the first snapshot fires at t=30 s from
            // program launch, the second at t=60 s, etc., regardless of how long
            // tab setup took.  windowStartTs begins at programStartMs so each
            // snapshot window filter covers data since the previous boundary.
            windowStartTs.set(startMs);

            Thread schedulerStarter = new Thread(() -> {
                try {
                    sl.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Calculate how long to wait from NOW until the next report boundary
                // aligned to programStartMs.  If setup already consumed more than one
                // interval (e.g. --report 10 but setup took 25 s), we jump to the next
                // boundary that hasn't fired yet.
                long elapsedMs        = System.currentTimeMillis() - startMs;
                long intervalsElapsed = elapsedMs / intervalMs;
                long nextBoundaryMs   = (intervalsElapsed + 1) * intervalMs; // ms from startMs
                long initialDelayMs   = nextBoundaryMs - elapsedMs;
                if (initialDelayMs < 0) initialDelayMs = 0;

                ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "periodic-reporter");
                    t.setDaemon(true);
                    return t;
                });
                schedulerRef.set(sch);
                sch.scheduleAtFixedRate(() -> {
                    long from = windowStartTs.get();
                    int  snap = snapshotNum.incrementAndGet();
                    System.out.println();
                    System.out.println("--- Snapshot #" + snap + " ---");
                    new JsonReporter().printJson(lm, dur, snap, from, liveYt, liveWeb, liveDns);
                    // Advance window start to the current boundary so the next
                    // snapshot only shows data collected since this one fired.
                    windowStartTs.set(System.currentTimeMillis());
                }, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
            }, "scheduler-starter");
            schedulerStarter.setDaemon(true);
            schedulerStarter.start();
        }

        // ── Collect results ──────────────────────────────────────────────────
        if (ytFuture != null) {
            try {
                ytFuture.get(10, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.error("YouTube probe failed: {}", rootCause(e));
                ytFuture.cancel(true);
            } finally {
                // If no website probe is running, signal DNS to stop now.
                if (webFuture == null) {
                    dnsStopLatch.countDown();
                }
            }
        }

        if (webFuture != null) {
            try {
                webFuture.get(5, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.error("Website probe failed: {}", rootCause(e));
                webFuture.cancel(true);
            } finally {
                // Always signal DNS after the last browser probe finishes.
                dnsStopLatch.countDown();
            }
        }

        if (dnsFuture != null) {
            try {
                dnsFuture.get(durationSeconds + 60L, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.error("DNS probe failed: {}", rootCause(e));
                dnsFuture.cancel(true);
            }
        }

        // Stop the periodic reporter before printing the final report so
        // they don't interleave.
        ScheduledExecutorService scheduler = schedulerRef.get();
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }

        // ── Final JSON snapshot (fromTs=0 → show every cycle, no window filter) ──
        System.out.println();
        System.out.println("--- FINAL ---");
        new JsonReporter().printJson(modeLabel, durationSeconds, 0, 0L, liveYt, liveWeb, liveDns);

        // ── Combined Final Report (console table view) ───────────────────────
        if (liveYt != null && !liveYt.isEmpty()) {
            PerformanceReporter reporter = new PerformanceReporter();
            reporter.printConsoleReport(liveYt);
            reporter.saveCsvReport(liveYt, "youtube_performance_report.csv");
            reporter.saveJsonReport(liveYt, "youtube_performance_report.json");
        }

        WebsiteReporter websiteReporter = new WebsiteReporter();

        if (liveWeb != null && !liveWeb.isEmpty()) {
            websiteReporter.printWebsiteReport(liveWeb);
        }

        if (liveDns != null && !liveDns.isEmpty()) {
            websiteReporter.printDnsReport(liveDns);
        }

        List<VideoVerdict> verdicts = (liveYt != null && !liveYt.isEmpty())
            ? new PerformanceEvaluator().evaluateAll(liveYt)
            : (liveYt != null ? java.util.Collections.emptyList() : null);
        websiteReporter.printFinalSummary(verdicts, liveWeb, liveDns);

        logger.info("All probes complete.");
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
