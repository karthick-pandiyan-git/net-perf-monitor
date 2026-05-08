package com.youtube.perf;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Opens all YouTube URLs in a SINGLE Chrome instance (one tab per URL).
 * Monitors all tabs in a shared sweep loop, collecting live network performance
 * data from each tab at every interval without wasting CPU/GPU on extra browsers.
 *
 * WebDriver is single-threaded; we cycle through tabs quickly enough that
 * each tab is sampled within every 5-second window.
 */
public class YouTubePerformanceTester {

    private static final Logger logger = LoggerFactory.getLogger(YouTubePerformanceTester.class);

    // ── Probe configuration (loaded from probe-timing.properties) ─────────────
    private static final int SWEEP_INTERVAL_SECONDS;
    private static final int PAGE_LOAD_TIMEOUT_SECS;
    private static final int ELEMENT_WAIT_SECS;
    private static final int SFN_PANEL_SETTLE_MS;
    private static final int MAX_AD_ROUNDS;
    /** Max time to wait for a single ad to resolve (skip or finish). */
    private static final int MAX_AD_WAIT_MS;
    /** Poll interval inside the unified ad wait loop. */
    private static final int AD_POLL_MS;
    /** Pause between ads to let YouTube load the next one before re-checking. */
    private static final int AD_TRANSITION_WAIT_MS;

    static {
        Properties cfg = loadConfig();
        SWEEP_INTERVAL_SECONDS = Integer.parseInt(cfg.getProperty("youtube.probe.sweep_interval.secs",   "5"));
        PAGE_LOAD_TIMEOUT_SECS = Integer.parseInt(cfg.getProperty("youtube.probe.page_load_timeout.secs","45"));
        ELEMENT_WAIT_SECS      = Integer.parseInt(cfg.getProperty("youtube.probe.element_wait.secs",     "15"));
        SFN_PANEL_SETTLE_MS    = Integer.parseInt(cfg.getProperty("youtube.probe.sfn_panel_settle.ms",   "1200"));
        MAX_AD_ROUNDS          = Integer.parseInt(cfg.getProperty("youtube.probe.ad.max_rounds",          "5"));
        MAX_AD_WAIT_MS         = Integer.parseInt(cfg.getProperty("youtube.probe.ad.max_wait.ms",         "45000"));
        AD_POLL_MS             = Integer.parseInt(cfg.getProperty("youtube.probe.ad.poll.ms",             "500"));
        AD_TRANSITION_WAIT_MS  = Integer.parseInt(cfg.getProperty("youtube.probe.ad.transition_wait.ms", "2000"));
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream is = YouTubePerformanceTester.class.getClassLoader()
                .getResourceAsStream("probe-timing.properties")) {
            if (is != null) {
                p.load(is);
            } else {
                logger.warn("'probe-timing.properties' not found — using built-in YouTube probe defaults");
            }
        } catch (IOException e) {
            logger.warn("Failed to load 'probe-timing.properties': {} — using built-in defaults", e.getMessage());
        }
        return p;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens every URL in a single Chrome instance (one tab per URL), runs the
     * monitoring sweep loop for {@code durationSeconds}, and returns the collected
     * metrics for every tab.
     *
     * <p>The method participates in a two-probe synchronisation barrier:
     * it counts down {@code startLatch} after all tabs are open so that
     * {@link WebsiteTester} and the DNS monitor start their windows at the same time.</p>
     *
     * <p>{@code liveMetrics} is a shared list pre-populated by the caller with one
     * {@link VideoMetrics} placeholder per URL (tabIndex + url already set).
     * After every sweep the placeholders are updated with the latest network
     * aggregates so that a periodic reporter thread can read in-progress data
     * without waiting for the full monitoring window to complete.</p>
     *
     * @param urls            YouTube video URLs to open (one per tab)
     * @param startLatch      barrier counted down once tab setup is complete
     * @param durationSeconds total monitoring window in seconds
     * @param liveMetrics     shared list used as the internal metrics store;
     *                        must be thread-safe (e.g. {@code CopyOnWriteArrayList})
     * @return the same {@code liveMetrics} list, fully populated at the end
     */
    public List<VideoMetrics> runTest(List<String> urls, CountDownLatch startLatch, int durationSeconds,
                                      List<VideoMetrics> liveMetrics, long programStartMs) {
        WebDriverManager.chromedriver().setup();
        WebDriver driver = createDriver();

        // Ordered list of window handles, matching url index
        List<String> handles  = new ArrayList<>();
        // Use liveMetrics as the backing list so in-progress data is always visible
        // to the periodic reporter thread in Main.
        List<VideoMetrics> metrics = liveMetrics;
        // Per-handle network sample accumulator
        Map<String, List<NetworkSample>> samplesMap = new LinkedHashMap<>();
        int sweep = 0;
        // Deadline is set after the latch so durationSeconds is the actual monitoring
        // window, not including browser startup and tab-loading time.
        long absoluteDeadlineMs = programStartMs + durationSeconds * 1000L; // fallback

        try {
            // ── 1. Open all tabs ─────────────────────────────────────────────
            // Nested try-finally guarantees countDown() fires even if tab setup
            // throws, so WebsiteTester and DnsMonitor never wait forever.
            try {
                openAllTabs(driver, urls, handles);

                for (int i = 0; i < handles.size(); i++) {
                    VideoMetrics m = new VideoMetrics();
                    m.setTabIndex(i + 1);
                    m.setUrl(urls.get(i));
                    metrics.add(m);
                    samplesMap.put(handles.get(i), new ArrayList<>());
                }
                // Collect page-load timings and page title from Navigation Timing API
                // now that all tabs have finished loading. These values are stable and
                // won't change, so reading them once here ensures they appear in every
                // periodic snapshot (not just the final report).
                for (int i = 0; i < handles.size(); i++) {
                    driver.switchTo().window(handles.get(i));
                    collectInitialTimings(driver, metrics.get(i));
                }
                logger.info("[YouTube] Tabs ready \u2014 waiting for all probes to sync...");
            } finally {
                startLatch.countDown();
            }
            startLatch.await(5, TimeUnit.MINUTES);
            // Start the monitoring window NOW — after all probes are synced and tabs are
            // loaded. Browser startup and tab-loading can take 15-30 s; using programStartMs
            // for the deadline caused the sweep loop to get 0 iterations on short runs.
            absoluteDeadlineMs = System.currentTimeMillis() + durationSeconds * 1000L;
            logger.info("[YouTube] All probes ready \u2014 monitoring for {} s", durationSeconds);
            // ── 2. Monitoring sweeps ─────────────────────────────────────────
            while (System.currentTimeMillis() < absoluteDeadlineMs) {
                // Sleep for the interval, but wake up early if the deadline is near
                // so we don't overshoot.
                long remaining = absoluteDeadlineMs - System.currentTimeMillis();
                long sleepMs = Math.min(SWEEP_INTERVAL_SECONDS * 1000L, remaining);
                if (sleepMs <= 0) break;
                Thread.sleep(sleepMs);
                if (System.currentTimeMillis() >= absoluteDeadlineMs) break;
                sweep++;
                logger.info("── Sweep {} ──────────────────────────────────", sweep);

                for (int i = 0; i < handles.size(); i++) {
                    driver.switchTo().window(handles.get(i));
                    // Use the lightweight sweep handler — no quality re-setting
                    handleSweep(driver, i + 1);

                    NetworkSample sample = collectNetworkSample(driver, sweep);

                    // Read connection speed from the Stats for Nerds panel (panel is
                    // guaranteed open by handleSweep above). Uses robust multi-strategy
                    // reading so a transient DOM layout difference won't silently drop data.
                    StatsForNerdsData sweepSfn = readStatsForNerds(driver);
                    if (sweepSfn.getConnectionSpeedKbps() > 0) {
                        sample.setConnectionSpeedKbps(sweepSfn.getConnectionSpeedKbps());
                    }
                    if (sweepSfn.getBufferHealthSecs() >= 0) {
                        sample.setBufferHealthSecs(sweepSfn.getBufferHealthSecs());
                    }
                    if (sweepSfn.getDroppedFrames() >= 0) {
                        sample.setDroppedFrames(sweepSfn.getDroppedFrames());
                        sample.setTotalFrames(sweepSfn.getTotalFrames());
                    }

                    samplesMap.get(handles.get(i)).add(sample);

                    // Update live aggregate so periodic reporter sees current data
                    List<NetworkSample> liveSamples = samplesMap.get(handles.get(i));
                    VideoMetrics liveM = metrics.get(i);
                    applyNetworkAggregates(liveM, liveSamples);
                    if (!liveSamples.isEmpty()) {
                        liveM.setBufferedSeconds(liveSamples.get(liveSamples.size() - 1).getVideoBuffered());
                    }
                    // Refresh video dimensions and title each sweep: videoWidth/videoHeight
                    // are only non-zero once the video element has initialised, so checking
                    // each sweep ensures snapshots show them as soon as they become available.
                    refreshLiveTabState(driver, liveM);

                    // Log what was actually read from Stats for Nerds this sweep.
                    // Frames are cumulative since session start (increasing counters).
                    logger.info("  [Tab-{}] #{} | SFN: connSpeed={} Mbps | bufHealth={} s | frames={} dropped of {} | quality={}",
                        i + 1, sweep,
                        sweepSfn.getConnectionSpeedKbps() > 0
                            ? String.format("%.1f", sweepSfn.getConnectionSpeedKbps() / 1000.0) : "n/a",
                        sweepSfn.getBufferHealthSecs() >= 0
                            ? String.format("%.2f", sweepSfn.getBufferHealthSecs()) : "n/a",
                        sweepSfn.getDroppedFrames() >= 0 ? sweepSfn.getDroppedFrames() : "n/a",
                        sweepSfn.getTotalFrames()   >= 0 ? sweepSfn.getTotalFrames()   : "n/a",
                        sample.isAdPlaying() ? "[AD-skipped]" :
                            sample.getQualityLabel() != null ? sample.getQualityLabel() : "?");
                }
            }  // end sweep loop

            // ── 3. Final per-tab metrics collection ──────────────────────────
            for (int i = 0; i < handles.size(); i++) {
                VideoMetrics m = metrics.get(i);
                List<NetworkSample> samples = samplesMap.get(handles.get(i));
                // Always commit sweep data — these are in-memory and do not need the browser.
                m.setNetworkSamples(samples);
                applyNetworkAggregates(m, samples);
                m.setMetricsCollectedAt(System.currentTimeMillis());
                try {
                    driver.switchTo().window(handles.get(i));
                    // Lightweight sweep handler for the final pass too — we only need
                    // the video to be playing; quality must not be altered at this stage.
                    handleSweep(driver, i + 1);
                    collectFinalMetrics(driver, m);
                    // Ensure the Stats for Nerds panel is open before reading — it may have
                    // been closed by a tab switch, ad playback, or quality change since setup.
                    openStatsForNerds(driver, i + 1);
                    Thread.sleep(SFN_PANEL_SETTLE_MS); // let the panel populate its values (animation + JS render)
                    StatsForNerdsData sfn = readStatsForNerds(driver);
                    if (sfn.isAvailable()) {
                        // Average connectionSpeedKbps across all sweep samples instead of using
                        // the instantaneous final value, which is more representative.
                        double avgConnSpeed = samples.stream()
                            .filter(s -> s.getConnectionSpeedKbps() > 0)
                            .mapToDouble(NetworkSample::getConnectionSpeedKbps)
                            .average().orElse(sfn.getConnectionSpeedKbps());
                        sfn.setConnectionSpeedKbps(avgConnSpeed);
                        // Average bufferHealthSecs across all sweep samples instead of using
                        // the instantaneous final value, which is more representative.
                        double avgBufHealth = samples.stream()
                            .filter(s -> s.getBufferHealthSecs() >= 0)
                            .mapToDouble(NetworkSample::getBufferHealthSecs)
                            .average().orElse(sfn.getBufferHealthSecs());
                        sfn.setBufferHealthSecs(avgBufHealth);
                        m.setSfnData(sfn);
                        logger.info("[Tab-{}] Stats for Nerds: speed(avg)={} Kbps, bufHealth(avg)={} s, res={}, codecs={}",
                            i + 1, String.format("%.0f", avgConnSpeed),
                            String.format("%.2f", avgBufHealth),
                            sfn.getCurrentResolution(), sfn.codecSummary());
                    } else {
                        // Live panel unavailable — derive SFN from sweep samples so that
                        // SFN Connection Speed and SFN Buffer Health checks still run.
                        synthesizeSfnFromSweeps(m, samples, i + 1);
                    }
                } catch (InterruptedException ie) {
                    // Selenium's internal shutdown code restores the thread interrupt flag
                    // when the browser process dies (via shutdownGracefully → awaitTermination).
                    // Clear it here so the remaining tabs are not skipped.
                    Thread.interrupted();
                    logger.warn("[Tab-{}] Final collection interrupted (browser may have died) — using sweep data", i + 1);
                    synthesizeSfnFromSweeps(m, samples, i + 1);
                } catch (Exception e) {
                    // Non-fatal: browser crash, stale session, etc.  Preserve what was
                    // already stored (initial timings + sweep aggregates) and derive SFN
                    // from sweep samples so quality checks still run.
                    logger.warn("[Tab-{}] Final collection failed (non-fatal): {} — using sweep data",
                        i + 1, e.getMessage());
                    synthesizeSfnFromSweeps(m, samples, i + 1);
                }
                logger.info("[Tab-{}] Final metrics collected", i + 1);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Test interrupted — preserving {} sweep samples collected so far", sweep);
            // Save whatever sweep data was collected before interruption so the
            // report still shows partial results rather than "0 sweeps".
            for (int i = 0; i < handles.size(); i++) {
                VideoMetrics m = metrics.size() > i ? metrics.get(i) : null;
                if (m == null) continue;
                List<NetworkSample> partial = samplesMap.get(handles.get(i));
                if (partial != null && !partial.isEmpty()) {
                    m.setNetworkSamples(partial);
                    applyNetworkAggregates(m, partial);
                }
            }
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
            logger.info("Browser closed.");
        }

        return metrics;
    }

    // ── Driver setup ──────────────────────────────────────────────────────────

    /**
     * Creates and configures a headless-free Chrome instance optimised for
     * YouTube performance measurement.
     *
     * <p>Automation flags are suppressed, audio is muted for autoplay, and
     * background-tab throttling is disabled so all tabs receive equal CPU time
     * during the sweep loop.</p>
     *
     * @return a ready-to-use {@link WebDriver} with navigation timeouts applied
     */
    private WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-infobars");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--mute-audio");
        options.addArguments("--autoplay-policy=no-user-gesture-required");
        options.addArguments(
            "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );
        // Prevent Chrome from deprioritising background renderers and throttling timers
        // in backgrounded/occluded tabs. Without these flags, YouTube's JavaScript timer
        // that drives playback can be throttled to 1 Hz when the tab isn't in focus,
        // causing the video to stall and producing misleading "currentTime stuck" readings.
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-backgrounding-occluded-windows");
        // Suppress ChromeDriver DevTools discovery logs (CDP version mismatch warnings)
        options.addArguments("--log-level=3");
        options.addArguments("--silent");

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECS));
        ((JavascriptExecutor) driver).executeScript(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        );
        // Expand the PerformanceTiming resource buffer so high-bitrate 8K segments
        // never push out earlier entries before we can read them.
        ((JavascriptExecutor) driver).executeScript(
            "performance.setResourceTimingBufferSize(1000);"
        );
        return driver;
    }

    // ── Tab management ────────────────────────────────────────────────────────

    /**
     * Opens every URL in the driver, one tab per URL, and populates {@code handles}
     * with the corresponding window-handle strings in the same order.
     * Ad dismissal and forced playback are applied to each tab as it opens.
     *
     * <p>Strategy (mirrors {@link WebsiteTester#openAllTabs}):</p>
     * <ol>
     *   <li><b>Phase 1</b> — Trigger all navigations simultaneously via non-blocking
     *       JS calls so Chrome fetches all URLs in parallel.</li>
     *   <li><b>Phase 2</b> — Poll tabs until every one reports
     *       {@code readyState='complete'}, up to {@link #PAGE_LOAD_TIMEOUT_SECS}.</li>
     *   <li><b>Phase 3</b> — Initialise each loaded tab sequentially (consent dialog,
     *       ad skip, force-play, quality, Stats for Nerds).</li>
     * </ol>
     *
     * @param driver  the Chrome instance to reuse
     * @param urls    list of YouTube video URLs to open
     * @param handles output list that receives one window handle per URL, in order
     * @throws InterruptedException if the thread is interrupted during tab setup
     */
    private void openAllTabs(WebDriver driver, List<String> urls, List<String> handles)
            throws InterruptedException {

        // ── Phase 1: Trigger all navigations simultaneously ──────────────────
        // Tab 1 already exists — navigate via window.location.href (non-blocking JS).
        // Tabs 2..N use window.open(url, '_blank') so Chrome begins fetching all
        // URLs in parallel without blocking on any individual page load.
        try {
            handles.add(driver.getWindowHandle());
            ((JavascriptExecutor) driver).executeScript(
                "window.location.href = arguments[0];", urls.get(0));
            logger.info("[Tab-1] Triggered navigation: {}", urls.get(0));
        } catch (Exception e) {
            logger.warn("[Tab-1] Failed to start navigation: {}", e.getMessage());
        }

        for (int i = 1; i < urls.size(); i++) {
            try {
                ((JavascriptExecutor) driver).executeScript(
                    "window.open(arguments[0], '_blank');", urls.get(i));
                Set<String> all = driver.getWindowHandles();
                final int tabIdx = i;
                String newHandle = all.stream()
                    .filter(h -> !handles.contains(h))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                        "New tab handle not found for tab " + (tabIdx + 1)));
                handles.add(newHandle);
                logger.info("[Tab-{}] Triggered navigation: {}", i + 1, urls.get(i));
            } catch (Exception e) {
                logger.warn("[Tab-{}] Failed to open tab: {}", i + 1, e.getMessage());
            }
        }

        // ── Phase 2: Wait for all tabs to reach readyState='complete' ────────
        // All tabs are loading their URLs in parallel — poll until every tab
        // reports 'complete'. A short initial pause lets Chrome register the
        // navigations as 'loading' before the first readyState check so we don't
        // read a stale 'complete' from the previous page.
        Thread.sleep(500);

        long deadline = System.currentTimeMillis() + (long) PAGE_LOAD_TIMEOUT_SECS * 1000L;
        boolean[] loaded = new boolean[handles.size()];
        while (System.currentTimeMillis() < deadline) {
            boolean allDone = true;
            for (int i = 0; i < handles.size(); i++) {
                if (loaded[i] || handles.get(i) == null) continue;
                try {
                    driver.switchTo().window(handles.get(i));
                    String state = (String) ((JavascriptExecutor) driver)
                        .executeScript("return document.readyState;");
                    String currentUrl = driver.getCurrentUrl();
                    // Confirm the tab has navigated to the real URL, not a stale page.
                    boolean urlReady = currentUrl != null && !currentUrl.startsWith("about:");
                    if ("complete".equals(state) && urlReady) {
                        loaded[i] = true;
                        logger.info("[Tab-{}] Page ready: {}", i + 1, urls.get(i));
                    } else {
                        allDone = false;
                    }
                } catch (Exception e) {
                    allDone = false;
                }
            }
            if (allDone) break;
            Thread.sleep(500);
        }

        // ── Phase 3: Initialise each loaded tab (ads, playback, quality, SFN) ─
        // Tabs are fully loaded; apply per-tab setup sequentially (WebDriver is
        // single-threaded and can only interact with one tab at a time).
        for (int i = 0; i < handles.size(); i++) {
            if (handles.get(i) == null) continue;
            driver.switchTo().window(handles.get(i));
            logger.info("[Tab-{}] Initialising: {}", i + 1, urls.get(i));
            handleAdsAndForcePlay(driver, i + 1);
        }

        logger.info("All {} tabs open in one Chrome instance.", urls.size());
    }

    // ── Ad handling + force-play ──────────────────────────────────────────────

    /**
     * Full setup-time handler: dismisses consent dialogs, skips/waits for ads,
     * force-starts playback, sets the highest available quality once, and opens
     * the Stats for Nerds overlay so live stats can be read on each sweep.
     * Called during tab initialisation only.
     */
    private void handleAdsAndForcePlay(WebDriver driver, int tabIndex) {
        try {
            dismissConsentDialog(driver);
            closeOverlayAd(driver);
            skipOrWaitForAd(driver, tabIndex);
            forcePlay(driver, tabIndex);
            enableLoop(driver, tabIndex);
            setHighestQuality(driver, tabIndex);
            openStatsForNerds(driver, tabIndex);
        } catch (Exception e) {
            logger.debug("[Tab-{}] handleAdsAndForcePlay (non-fatal): {}", tabIndex, e.getMessage());
        }
    }

    /**
     * Lightweight sweep-time handler: skips ads and ensures video is playing,
     * but deliberately does NOT call setHighestQuality.
     *
     * Calling setPlaybackQualityRange on every 5-second sweep forces YouTube's ABR
     * engine to re-evaluate the quality level, which can discard buffered segments
     * and restart the download pipeline — causing measured currentTime to stall
     * and inflating frame-drop metrics. Quality is set once at tab setup and then
     * left entirely to the ABR algorithm.
     */
    private void handleSweep(WebDriver driver, int tabIndex) {
        try {
            closeOverlayAd(driver);
            skipOrWaitForAd(driver, tabIndex);
            forcePlay(driver, tabIndex);
            // Re-apply loop each sweep in case YouTube's player replaced the video
            // element (e.g. after an ad or a quality change), which would clear loop.
            enableLoop(driver, tabIndex);
            // Ensure the Stats for Nerds panel stays open — tab switches or ads can
            // close it. openStatsForNerds detects 'already-open' cheaply and returns
            // immediately when the panel is already visible.
            openStatsForNerds(driver, tabIndex);
        } catch (Exception e) {
            logger.debug("[Tab-{}] handleSweep (non-fatal): {}", tabIndex, e.getMessage());
        }
    }

    /**
     * Clicks the first visible Google consent / "Accept all" button if present.
     * Silently no-ops when no consent dialog is shown.
     *
     * @param driver the active Chrome session
     */
    private void dismissConsentDialog(WebDriver driver) {
        try {
            List<WebElement> candidates = driver.findElements(By.cssSelector(
                BrowserSelectors.CONSENT_DIALOG_CSS
            ));
            for (WebElement btn : candidates) {
                if (btn.isDisplayed()) { btn.click(); Thread.sleep(600); return; }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Closes a YouTube overlay ad (banner/card style) if one is currently visible.
     * Silently no-ops when no overlay ad is present.
     *
     * @param driver the active Chrome session
     */
    private void closeOverlayAd(WebDriver driver) {
        try {
            WebElement close = driver.findElement(
                By.cssSelector(BrowserSelectors.OVERLAY_AD_CLOSE_CSS)
            );
            if (close.isDisplayed()) { close.click(); Thread.sleep(300); }
        } catch (Exception ignored) {}
    }

    /**
     * Handles consecutive ads (e.g. two pre-roll ads, the second being skippable).
     *
     * Each iteration deals with one ad using a unified polling loop that checks
     * BOTH conditions on every tick:
     *   (a) skip button visible → click it immediately
     *   (b) overlay gone → ad finished naturally
     *
     * The old design had two separate paths (try-skip for 8 s, then wait-non-skippable
     * for 35 s) where the skip check was abandoned after 8 s. If YouTube only enables
     * the skip button at 10–15 s (valid for some ad campaigns), that path never found
     * the button and the ad played through. The unified loop fixes this.
     *
     * After each ad resolves, {@code AD_TRANSITION_WAIT_MS} lets YouTube load the
     * next queued ad before we re-check the overlay, bridging the ~300–1500 ms
     * inter-ad gap where the overlay is momentarily absent.
     */
    /**
     * Waits for any currently-playing pre-roll or mid-roll ad to finish or be skipped.
     * Iterates up to {@value #MAX_AD_ROUNDS} consecutive ads, polling both the skip
     * button and the overlay element on every {@value #AD_POLL_MS} ms tick.
     *
     * @param driver   the active Chrome session
     * @param tabIndex 1-based tab number used in log messages
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private void skipOrWaitForAd(WebDriver driver, int tabIndex) throws InterruptedException {
        for (int round = 1; round <= MAX_AD_ROUNDS; round++) {
            // Primary check: #movie_player.ad-showing is set by YouTube for ALL ad types
            // (pre-roll, mid-roll, bumper, overlay). Fallback to overlay element check
            // for older player builds that don't use the class.
            boolean adShowing = isAdPlaying(driver);
            if (!adShowing) return;

            logger.info("[Tab-{}] Ad detected (round {}) — polling for skip button or ad end...", tabIndex, round);

            long deadline = System.currentTimeMillis() + MAX_AD_WAIT_MS;
            boolean resolved = false;

            while (System.currentTimeMillis() < deadline) {
                // (a) Check if ad has ended
                if (!isAdPlaying(driver)) {
                    logger.info("[Tab-{}] Ad finished naturally (round {})", tabIndex, round);
                    resolved = true;
                    break;
                }

                // (b) Check if a skip button is now visible and click it immediately.
                // Polled on every tick so we never miss a button that appears late
                // (YouTube enables skip at 5 s for standard ads, but 10–15 s for others).
                try {
                    List<WebElement> btns = driver.findElements(By.cssSelector(
                        BrowserSelectors.SKIP_AD_CSS
                    ));
                    WebElement visible = btns.stream()
                        .filter(WebElement::isDisplayed)
                        .findFirst().orElse(null);
                    if (visible != null) {
                        visible.click();
                        logger.info("[Tab-{}] Skipped ad (round {})", tabIndex, round);
                        resolved = true;
                        break;
                    }
                } catch (Exception ignored) {}

                Thread.sleep(AD_POLL_MS);
            }

            if (!resolved) {
                logger.warn("[Tab-{}] Ad did not resolve within {} s (round {}) — continuing",
                    tabIndex, MAX_AD_WAIT_MS / 1000, round);
                return;
            }

            // Pause before re-checking so YouTube has time to start the next queued ad.
            Thread.sleep(AD_TRANSITION_WAIT_MS);
        }
        logger.warn("[Tab-{}] Reached max ad rounds ({}) — proceeding", tabIndex, MAX_AD_ROUNDS);
    }

    /**
     * Returns {@code true} when any ad (pre-roll, mid-roll, bumper, overlay) is
     * currently playing in the YouTube player.
     *
     * <p>Uses two independent signals:
     * <ol>
     *   <li>{@code #movie_player.ad-showing} — reliable class YouTube sets on the
     *       player container for all ad types in modern builds.</li>
     *   <li>The {@code .ytp-ad-player-overlay} / {@code .ytp-ad-player-overlay-layout}
     *       elements — fallback for older player versions.</li>
     * </ol>
     * Either signal alone is sufficient to declare an ad is playing.
     */
    private boolean isAdPlaying(WebDriver driver) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                "var p = document.getElementById(arguments[0]);" +
                "if (p && p.classList.contains('ad-showing')) return true;" +
                "return !!document.querySelector(arguments[1]);",
                BrowserSelectors.MOVIE_PLAYER_ID,
                BrowserSelectors.AD_PLAYER_OVERLAY_CSS
            );
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Ensures the video element is actively playing.
     * Dismisses any "Continue watching?" confirmation dialog, then calls
     * {@code video.play()} via JavaScript if the video is paused.
     * All exceptions are silently swallowed — this is a best-effort helper.
     *
     * @param driver   the active Chrome session
     * @param tabIndex 1-based tab number used in debug log messages
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    /**
     * Sets {@code video.loop = true} on the page's video element so the video
     * restarts automatically when it ends. This allows monitoring sessions longer
     * than the video duration (e.g. 8-hour videos streamed for 10+ hours).
     *
     * <p>YouTube may replace the video element after ads or quality changes, so
     * this is called both at tab setup and on every sweep to re-apply it.</p>
     */
    private void enableLoop(WebDriver driver, int tabIndex) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "var v = document.querySelector('video'); if (v) { v.loop = true; }");
            logger.debug("[Tab-{}] loop enabled", tabIndex);
        } catch (Exception ignored) {}
    }

    private void forcePlay(WebDriver driver, int tabIndex) throws InterruptedException {
        // ── 1. Dismiss any YouTube pause/interrupt dialogs ───────────────────
        // YouTube surfaces several dialogs that pause the video during long sessions:
        //   (a) "Video paused. Continue watching?" — shown after ~1–4 h of inactivity.
        //       Selector: .yt-confirm-dialog-renderer confirm button, or the newer
        //       ytp-pause-overlay "Yes" button.
        //   (b) "Still watching?" / "Are you still there?" — shown by some embed/kiosk
        //       configs; selector: .yt-confirm-dialog-renderer or button[aria-label*='yes']
        //   (c) Generic confirm dialogs (e.g. sign-in prompts that pause playback).
        // We try all known selectors in one pass. Clicking any visible button is safe —
        // these dialogs only have a "Continue" / "Yes" / "Confirm" action.
        dismissPauseDialogs(driver, tabIndex);

        // ── 2. Resume playback via JS if video is still paused ───────────────
        try {
            Object result = ((JavascriptExecutor) driver).executeScript("""
                var v = document.querySelector('video');
                if (!v) return 'no-video';
                v.muted = true;
                if (v.paused || v.ended) {
                    v.play().catch(function(){});
                    return 'resumed';
                }
                return 'playing';
                """);
            if ("resumed".equals(result)) {
                logger.info("[Tab-{}] Resumed paused video", tabIndex);
            }
            logger.debug("[Tab-{}] forcePlay: {}", tabIndex, result);
        } catch (Exception ignored) {}
    }

    /**
     * Dismisses any YouTube dialog that interrupts long-running playback.
     *
     * <p>Known dialogs handled:</p>
     * <ul>
     *   <li><b>"Video paused. Continue watching?"</b> — appears after ~1–4 h of
     *       background playback. The "Yes" button has class {@code ytp-pause-overlay-actions}
     *       or lives inside {@code .yt-confirm-dialog-renderer}.</li>
     *   <li><b>"Still watching?" / "Are you still there?"</b> — shown on some accounts
     *       after extended inactivity; uses the same confirm-dialog structure.</li>
     *   <li><b>Generic confirm dialogs</b> — any modal that blocks the player.</li>
     * </ul>
     *
     * @param driver   the active Chrome session
     * @param tabIndex 1-based tab number used in log messages
     */
    private void dismissPauseDialogs(WebDriver driver, int tabIndex) {
        try {
            // Build the JS selectors array from the configured BrowserSelectors constants
            // so the selector list can be updated in browser-selectors.properties without
            // touching Java code.
            String selectorsJsArray = BrowserSelectors.toJsArray(BrowserSelectors.PAUSE_DIALOG_SELECTORS);
            Object dismissed = ((JavascriptExecutor) driver).executeScript(
                "var selectors = " + selectorsJsArray + ";" +
                "for (var i = 0; i < selectors.length; i++) {" +
                "    var els = document.querySelectorAll(selectors[i]);" +
                "    for (var j = 0; j < els.length; j++) {" +
                "        var el = els[j];" +
                "        if (el.offsetParent !== null) { el.click(); return selectors[i]; }" +
                "    }" +
                "}" +
                "return null;");
            if (dismissed != null) {
                logger.info("[Tab-{}] Dismissed pause/interrupt dialog ({})", tabIndex, dismissed);
                Thread.sleep(400);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Forces the YouTube player to use the highest available quality level.
     * Uses the #movie_player element's setPlaybackQualityRange() API, which
     * signals to the adaptive bitrate logic that we want the best possible quality.
     * Quality labels follow YouTube's naming: hd2160, hd1440, hd1080, hd720, large, medium, small, tiny.
     */
    private void setHighestQuality(WebDriver driver, int tabIndex) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                "var player = document.getElementById(arguments[0]);" +
                "if (!player || typeof player.getAvailableQualityLevels !== 'function') return 'no-player';" +
                "var levels = player.getAvailableQualityLevels();" +
                "if (!levels || levels.length === 0) return 'no-levels';" +
                "var best = levels[0];" + // YouTube returns levels sorted highest-first
                "player.setPlaybackQualityRange(best, best);" +
                "return 'set:' + best;",
                BrowserSelectors.MOVIE_PLAYER_ID);
            logger.info("[Tab-{}] Quality set to: {}", tabIndex, result);
        } catch (Exception e) {
            logger.debug("[Tab-{}] setHighestQuality (non-fatal): {}", tabIndex, e.getMessage());
        }
    }

    // ── Stats for Nerds panel ─────────────────────────────────────────────────

    /**
     * Opens YouTube's "Stats for Nerds" overlay panel on the current tab.
     *
     * <p>Strategy (tries both approaches in order):</p>
     * <ol>
     *   <li><b>JavaScript toggle</b> — reads the player's internal option state to
     *       detect whether the panel is already visible and toggles it if not.
     *       This works in most modern YouTube builds without requiring UI interaction.</li>
     *   <li><b>Right-click context menu</b> — if the JS approach leaves the panel
     *       closed, performs a Selenium {@link Actions#contextClick} on the player
     *       element and clicks the "Stats for nerds" menu item.  Falls back
     *       gracefully when the menu item is absent.</li>
     * </ol>
     *
     * <p>The panel stays open for the lifetime of the tab once activated —
     * {@link #readStatsForNerds} reads its values on every subsequent sweep.</p>
     *
     * @param driver   the active Chrome session (already on the target tab)
     * @param tabIndex 1-based tab number used in log messages
     */
    private void openStatsForNerds(WebDriver driver, int tabIndex) {
        // ── Pre-check: panel already visible? ────────────────────────────────
        // Uses the same broad multi-strategy selector as readStatsForNerds so
        // the panel is detected even when YouTube uses a generic container class
        // that doesn't match '.ytp-sfn-stats'. Without this guard, every fallback
        // attempt below reaches Attempt 2 (right-click context menu), which TOGGLES
        // the panel — closing it if it was already open.
        try {
            Boolean alreadyVisible = (Boolean) ((JavascriptExecutor) driver).executeScript(
                // Strategy A: known container selectors (from BrowserSelectors.SFN_PANEL_CSS)
                "var sfnPanel = document.querySelector(arguments[0]);" +
                "if (sfnPanel) {" +
                "    var cs = window.getComputedStyle(sfnPanel);" +
                "    if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0)" +
                "        return true;" +
                "}" +
                // Strategy B: label elements visible (panel open but container class unknown)
                "var labelEls = document.querySelectorAll(arguments[1]);" +
                "for (var i = 0; i < labelEls.length; i++) {" +
                "    var cs2 = window.getComputedStyle(labelEls[i]);" +
                "    if (cs2.display !== 'none' && cs2.visibility !== 'hidden') return true;" +
                "}" +
                "return false;",
                BrowserSelectors.SFN_PANEL_CSS,
                BrowserSelectors.SFN_LABEL_CSS
            );
            if (Boolean.TRUE.equals(alreadyVisible)) {
                logger.debug("[Tab-{}] SFN panel already visible — skipping open", tabIndex);
                return;
            }
        } catch (Exception e) {
            logger.debug("[Tab-{}] SFN pre-check (non-fatal): {}", tabIndex, e.getMessage());
        }

        // ── Attempt 1: JavaScript toggle via player option ───────────────────
        try {
            Object opened = ((JavascriptExecutor) driver).executeScript(
                "var player = document.getElementById(arguments[0]);" +
                "if (!player) return 'no-player';" +
                // Try player.getOption / setOption API (available in some YT builds)
                "if (typeof player.setOption === 'function') {" +
                "    try { player.setOption('statsText', 'show'); return 'setOption-show'; } catch(e) {}" +
                "}" +
                // Try direct module access to the stats panel
                "if (typeof player.getModule === 'function') {" +
                "    try {" +
                "        var statsModule = player.getModule('stats');" +
                "        if (statsModule && typeof statsModule.show === 'function') {" +
                "            statsModule.show(); return 'module-show';" +
                "        }" +
                "    } catch(e) {}" +
                "}" +
                // Try yt.config_ flag as a last JS resort
                "try { if (window.yt && window.yt.config_) window.yt.config_['SHOW_STATS_FOR_NERDS'] = true; } catch(e) {}" +
                "return 'not-toggled';",
                BrowserSelectors.MOVIE_PLAYER_ID
            );
            logger.debug("[Tab-{}] SFN JS toggle: {}", tabIndex, opened);

            // Check if it opened — same broad multi-strategy check as the pre-check above.
            Boolean isOpen = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var sfnPanel = document.querySelector(arguments[0]);" +
                "if (sfnPanel) {" +
                "    var cs = window.getComputedStyle(sfnPanel);" +
                "    if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0) return true;" +
                "}" +
                "var labelEls = document.querySelectorAll(arguments[1]);" +
                "for (var i = 0; i < labelEls.length; i++) {" +
                "    var cs2 = window.getComputedStyle(labelEls[i]);" +
                "    if (cs2.display !== 'none' && cs2.visibility !== 'hidden') return true;" +
                "}" +
                "return false;",
                BrowserSelectors.SFN_PANEL_CSS,
                BrowserSelectors.SFN_LABEL_CSS
            );
            if (Boolean.TRUE.equals(isOpen)) {
                logger.info("[Tab-{}] Stats for Nerds panel opened via JS", tabIndex);
                return;
            }
        } catch (Exception e) {
            logger.debug("[Tab-{}] SFN JS toggle error: {}", tabIndex, e.getMessage());
        }

        // ── Attempt 2: Right-click context menu ──────────────────────────────
        try {
            WebElement player = driver.findElement(By.id(BrowserSelectors.MOVIE_PLAYER_ID));
            new Actions(driver).contextClick(player).perform();
            Thread.sleep(600);

            // The context menu items all have class ytp-menuitem
            WebElement statsItem = new FluentWait<>(driver)
                .withTimeout(Duration.ofSeconds(3))
                .pollingEvery(Duration.ofMillis(200))
                .ignoring(NoSuchElementException.class)
                .until(d -> {
                    List<WebElement> items = d.findElements(
                        By.cssSelector(BrowserSelectors.CONTEXT_MENU_ITEM_CSS));
                    return items.stream()
                        .filter(el -> el.getText().toLowerCase().contains("stats for nerds"))
                        .findFirst()
                        .orElse(null);
                });

            if (statsItem != null) {
                statsItem.click();
                Thread.sleep(400);
                logger.info("[Tab-{}] Stats for Nerds opened via right-click menu", tabIndex);
            } else {
                logger.debug("[Tab-{}] 'Stats for nerds' item not found in context menu", tabIndex);
                // Dismiss the context menu by pressing Escape
                ((JavascriptExecutor) driver).executeScript(
                    "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,bubbles:true}));");
            }
        } catch (Exception e) {
            logger.debug("[Tab-{}] SFN right-click approach (non-fatal): {}", tabIndex, e.getMessage());
        }
    }

    /**
     * Reads the current values from YouTube's "Stats for Nerds" overlay panel
     * and returns them as a {@link StatsForNerdsData} snapshot.
     *
     * <p>The panel must have been opened by {@link #openStatsForNerds} first.
     * If the panel is not present or no fields can be parsed the returned object
     * satisfies {@code !sfn.isAvailable()}.</p>
     *
     * <p>The JavaScript reads every {@code <tr>} row in the panel, mapping the
     * {@code .ytp-sfn-label} text to the {@code .ytp-sfn-value} text.  Known
     * rows handled:</p>
     * <ul>
     *   <li>{@code "Connection Speed"} — e.g. {@code "8765 Kbps"}</li>
     *   <li>{@code "Network Activity"} — e.g. {@code "85532 KB"}</li>
     *   <li>{@code "Buffer Health"}    — e.g. {@code "30.94 s"}</li>
     *   <li>{@code "Current / Optimal Res"} — e.g. {@code "1920x1080@60 / 1920x1080@60"}</li>
     *   <li>{@code "Codecs"}           — e.g. {@code "av01.0.13M.08 / opus"}</li>
     *   <li>{@code "Frames"}           — e.g. {@code "875 / 8"}</li>
     * </ul>
     *
     * @param driver the active Chrome session (already switched to the target tab)
     * @return a populated {@code StatsForNerdsData}; fields are -1/null when absent
     */
    StatsForNerdsData readStatsForNerds(WebDriver driver) {
        StatsForNerdsData sfn = new StatsForNerdsData();
        try {
            // Build the JS arrays from BrowserSelectors so selector strings are
            // centralised in browser-selectors.properties and never embedded here.
            String knownLabelsJs    = BrowserSelectors.toJsArray(BrowserSelectors.SFN_KNOWN_LABELS);
            String panelFallbacksJs = BrowserSelectors.toJsArray(BrowserSelectors.SFN_PANEL_FALLBACK_SELECTORS);

            @SuppressWarnings("unchecked")
            Map<String, String> panelRows = (Map<String, String>)
                ((JavascriptExecutor) driver).executeScript(
                    "var result = {};" +
                    // Strategy 1: direct label/value class search — works regardless of the
                    // panel container class (YouTube may wrap it in a generic ytp-panel div).
                    // arguments[0] = SFN_LABEL_CSS, arguments[1] = SFN_VALUE_CSS
                    "var labelEls = document.querySelectorAll(arguments[0]);" +
                    "labelEls.forEach(function(labelEl) {" +
                    "    var row = labelEl.closest('tr') || labelEl.parentElement;" +
                    "    var valueEl = row ? (row.querySelector(arguments[1]) || labelEl.nextElementSibling) : null;" +
                    "    if (valueEl) { result[labelEl.textContent.trim()] = valueEl.textContent.trim(); }" +
                    "});" +
                    "if (Object.keys(result).length > 0) return result;" +
                    // Strategy 2: XPath text-content search for well-known SFN row labels.
                    // Completely independent of CSS class names.
                    "var knownLabels = " + knownLabelsJs + ";" +
                    "knownLabels.forEach(function(name) {" +
                    "    var xr = document.evaluate(\"//*[normalize-space(text())='\" + name + \"']\"," +
                    "        document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);" +
                    "    var labelNode = xr.singleNodeValue;" +
                    "    if (labelNode) {" +
                    "        var valueNode = labelNode.nextElementSibling ||" +
                    "            (labelNode.parentElement ? labelNode.parentElement.nextElementSibling : null);" +
                    "        if (valueNode) result[name] = valueNode.textContent.trim();" +
                    "    }" +
                    "});" +
                    "if (Object.keys(result).length > 0) return result;" +
                    // Strategy 3: panel-container search using multiple known class patterns,
                    // skipping visibility gate since we just opened the panel moments ago.
                    "var panelSels = " + panelFallbacksJs + ";" +
                    "var panel = null;" +
                    "for (var si = 0; si < panelSels.length; si++) {" +
                    "    panel = document.querySelector(panelSels[si]); if (panel) break;" +
                    "}" +
                    "if (panel) {" +
                    "    panel.querySelectorAll('tr').forEach(function(row) {" +
                    "        var cells = row.querySelectorAll('td, th');" +
                    "        if (cells.length >= 2) { result[cells[0].textContent.trim()] = cells[1].textContent.trim(); }" +
                    "    });" +
                    "    if (Object.keys(result).length === 0) {" +
                    "        var leaves = panel.querySelectorAll('td, th, span');" +
                    "        var prev = null;" +
                    "        leaves.forEach(function(el) {" +
                    "            if (el.children.length > 0) return;" +
                    "            var txt = el.textContent.trim(); if (!txt) return;" +
                    "            if (prev !== null) { result[prev] = txt; prev = null; } else { prev = txt; }" +
                    "        });" +
                    "    }" +
                    "}" +
                    "return Object.keys(result).length > 0 ? result : null;",
                    BrowserSelectors.SFN_LABEL_CSS,
                    BrowserSelectors.SFN_VALUE_CSS
                );

            logger.debug("readStatsForNerds raw JS result: {}", panelRows);
            if (panelRows == null || panelRows.isEmpty()) return sfn;

            panelRows.forEach((label, value) -> {
                String lc = label.toLowerCase();
                if (lc.contains("connection speed")) {
                    sfn.setConnectionSpeedKbps(parseFirstNumber(value));
                } else if (lc.contains("network activity")) {
                    long kb = (long) parseFirstNumber(value);
                    if (kb >= 0) sfn.setNetworkActivityKB(kb);
                } else if (lc.contains("buffer health")) {
                    sfn.setBufferHealthSecs(parseFirstNumber(value));
                } else if (lc.contains("current") && lc.contains("res")) {
                    // "Current / Optimal Res" → "1920x1080@60 / 1920x1080@60"
                    String[] parts = value.split("/", 2);
                    sfn.setCurrentResolution(parts[0].trim());
                    if (parts.length > 1) sfn.setOptimalResolution(parts[1].trim());
                } else if (lc.equals("codecs")) {
                    // "av01.0.13M.08 / opus"
                    String[] parts = value.split("/", 2);
                    sfn.setVideoCodec(parts[0].trim());
                    if (parts.length > 1) sfn.setAudioCodec(parts[1].trim());
                } else if (lc.contains("frames") && !lc.contains("viewport")) {
                    // "875 / 8" or "875 / 8 at 60fps"
                    Pattern fp = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
                    Matcher fm = fp.matcher(value);
                    if (fm.find()) {
                        sfn.setTotalFrames(Long.parseLong(fm.group(1)));
                        sfn.setDroppedFrames(Long.parseLong(fm.group(2)));
                    }
                } else if (lc.contains("viewport") && lc.contains("frames")) {
                    String[] parts = value.split("/");
                    if (parts.length >= 3) {
                        // Older YouTube format: "1920x1080 / 875 / 8" (viewport / total / dropped)
                        try {
                            sfn.setTotalFrames(Long.parseLong(parts[1].trim()));
                            sfn.setDroppedFrames(Long.parseLong(parts[2].trim().split("\\s")[0]));
                        } catch (NumberFormatException ignored) {}
                    } else if (parts.length == 2) {
                        // Newer YouTube format: "808x455 / 1821 dropped of 15580"
                        Matcher vfm = Pattern.compile("(\\d+)\\s+dropped\\s+of\\s+(\\d+)",
                            Pattern.CASE_INSENSITIVE).matcher(parts[1]);
                        if (vfm.find()) {
                            sfn.setDroppedFrames(Long.parseLong(vfm.group(1)));
                            sfn.setTotalFrames(Long.parseLong(vfm.group(2)));
                        }
                    }
                }
            });
        } catch (Exception e) {
            logger.debug("readStatsForNerds error: {}", e.getMessage());
        }
        return sfn;
    }

    /**
     * Extracts the first numeric value (integer or decimal) from a Stats for Nerds
     * value string, e.g. {@code "8765 Kbps"} → {@code 8765.0},
     * {@code "30.94 s"} → {@code 30.94}.
     *
     * @param text the raw text from the Stats for Nerds panel cell
     * @return the first number found, or {@code -1} if none could be parsed
     */
    private double parseFirstNumber(String text) {
        if (text == null) return -1;
        Matcher m = Pattern.compile("[\\d]+(?:\\.[\\d]+)?").matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group()); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    // ── Live network sampling ─────────────────────────────────────────────────

    /**
     * Captures a live network snapshot from the current tab by executing
     * a JavaScript snippet that reads the Performance Resource Timing API.
     * Bandwidth is estimated from video-segment bytes transferred within the
     * last sweep window.
     *
     * @param driver       the active Chrome session (already switched to the target tab)
     * @param sampleNumber the 1-based sweep counter used to label this sample
     * @return a populated {@link NetworkSample}; fields default to safe zero values on error
     */
    private NetworkSample collectNetworkSample(WebDriver driver, int sampleNumber) {
        NetworkSample sample = new NetworkSample();
        sample.setSampleNumber(sampleNumber);
        sample.setTimestamp(System.currentTimeMillis());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>)
                ((JavascriptExecutor) driver).executeScript(networkSampleScript(),
                    (long)(SWEEP_INTERVAL_SECONDS * 1000),
                    BrowserSelectors.AD_PLAYER_OVERLAY_CSS,
                    BrowserSelectors.MOVIE_PLAYER_ID);

            if (data != null) {
                sample.setTotalSegmentBytes(toLong(data.get("totalSegmentBytes")));
                sample.setRecentSegmentBytes(toLong(data.get("recentSegmentBytes")));
                sample.setRecentSegmentCount(toInt(data.get("recentSegmentCount")));
                sample.setTotalSegmentCount(toInt(data.get("totalSegmentCount")));
                sample.setBandwidthKBps(toDouble(data.get("bandwidthKBps")));
                sample.setVideoCurrentTime(toDouble(data.get("videoCurrentTime")));
                sample.setVideoBuffered(toDouble(data.get("videoBuffered")));
                // qualityLabel is only set when no ad is playing; adPlaying flag preserved for logging.
                sample.setAdPlaying(Boolean.TRUE.equals(data.get("adPlaying")));
                if (!sample.isAdPlaying()) {
                    sample.setQualityLabel((String) data.get("qualityLabel"));
                }
            }
        } catch (Exception e) {
            logger.debug("Network sample error: {}", e.getMessage());
        }
        return sample;
    }

    /**
     * Returns the JavaScript snippet executed by {@link #collectNetworkSample}.
     * The script reads {@code performance.getEntriesByType('resource')} to find
     * {@code videoplayback} segment entries, computes bandwidth over the last sweep
     * window, and also reads the {@code <video>} element state and playback quality.
     *
     * @return the JS source string (no arguments; window duration is passed via arguments[0])
     */
    private String networkSampleScript() {
        return """
            var windowMs = arguments[0];
            var adOverlayCss  = arguments[1];
            var playerElemId  = arguments[2];
            var result = {};
            try {
                var now = performance.now();
                var entries = performance.getEntriesByType('resource');
                var segs = entries.filter(function(e) { return e.name.indexOf('videoplayback') !== -1; });
                var totalBytes = 0, recentBytes = 0, recentCount = 0;
                segs.forEach(function(e) {
                    var b = e.transferSize > 0 ? e.transferSize : (e.encodedBodySize || 0);
                    totalBytes += b;
                    if (e.responseEnd > now - windowMs) { recentBytes += b; recentCount++; }
                });
                result.totalSegmentBytes  = totalBytes;
                result.recentSegmentBytes = recentBytes;
                result.recentSegmentCount = recentCount;
                result.totalSegmentCount  = segs.length;
                result.bandwidthKBps      = recentBytes / (windowMs / 1000.0) / 1024.0;
            } catch(e) {}
            try {
                var v = document.querySelector('video');
                if (v) {
                    result.videoCurrentTime = v.currentTime;
                    result.videoBuffered = v.buffered && v.buffered.length > 0 ? v.buffered.end(v.buffered.length - 1) : 0;
                }
            } catch(e) {}
            try {
                var player = document.getElementById(playerElemId);
                // Check whether an ad is currently playing before reading quality.
                // Ads stream at 'medium'/'small' regardless of connection speed;
                // including that reading would falsely flag a quality degradation.
                var adOverlay = document.querySelector(adOverlayCss);
                var adActive = !!(adOverlay && adOverlay.offsetParent !== null);
                result.adPlaying = adActive;
                if (!adActive && player && typeof player.getPlaybackQuality === 'function') {
                    result.qualityLabel = player.getPlaybackQuality();
                }
            } catch(e) {}
            return result;
            """;
    }

    /**
     * Reads Navigation Timing and page title from the current tab once, right
     * after the page has loaded. These values are stable and won't change during
     * playback, so collecting them early ensures they appear in periodic snapshots.
     *
     * @param driver the active Chrome session (already switched to the target tab)
     * @param m      the metrics object to populate
     */
    private void collectInitialTimings(WebDriver driver, VideoMetrics m) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>)
                ((JavascriptExecutor) driver).executeScript("""
                    var r = {};
                    try {
                        var t = window.performance.timing, ns = t.navigationStart;
                        r.pageLoadTime         = t.loadEventEnd > 0          ? t.loadEventEnd - ns          : -1;
                        r.domContentLoadedTime = t.domContentLoadedEventEnd > 0 ? t.domContentLoadedEventEnd - ns : -1;
                        r.timeToFirstByte      = t.responseStart > 0         ? t.responseStart - ns         : -1;
                        r.dnsLookupTime        = t.domainLookupEnd  - t.domainLookupStart;
                        r.tcpConnectionTime    = t.connectEnd       - t.connectStart;
                    } catch(e) {}
                    try { r.pageTitle = document.title; } catch(e) {}
                    return r;
                    """);
            if (data != null) {
                m.setPageLoadTime(toLong(data.get("pageLoadTime")));
                m.setDomContentLoadedTime(toLong(data.get("domContentLoadedTime")));
                m.setTimeToFirstByte(toLong(data.get("timeToFirstByte")));
                m.setDnsLookupTime(toLong(data.get("dnsLookupTime")));
                m.setTcpConnectionTime(toLong(data.get("tcpConnectionTime")));
                String title = (String) data.get("pageTitle");
                if (title != null && !title.isBlank()) m.setPageTitle(title);
            }
        } catch (Exception e) {
            logger.debug("[Tab-{}] Initial timing collection error: {}", m.getTabIndex(), e.getMessage());
        }
    }

    /**
     * Updates video dimensions and page title on the live metrics object from the
     * current DOM state. Called each sweep so that periodic snapshots show these
     * fields as soon as the video element initialises (videoWidth/videoHeight start
     * at 0 before playback begins).
     *
     * @param driver the active Chrome session (already switched to the target tab)
     * @param m      the live metrics object to update in-place
     */
    private void refreshLiveTabState(WebDriver driver, VideoMetrics m) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>)
                ((JavascriptExecutor) driver).executeScript("""
                    var r = {};
                    try {
                        var v = document.querySelector('video');
                        if (v) { r.videoWidth = v.videoWidth; r.videoHeight = v.videoHeight; }
                    } catch(e) {}
                    try { r.pageTitle = document.title; } catch(e) {}
                    return r;
                    """);
            if (data != null) {
                int w = toInt(data.get("videoWidth"));
                int h = toInt(data.get("videoHeight"));
                if (w > 0) m.setVideoWidth(w);
                if (h > 0) m.setVideoHeight(h);
                // Update title once the full video title is loaded (initially the
                // page may show a generic 'YouTube' title before the JS renders it).
                String title = (String) data.get("pageTitle");
                if (title != null && !title.isBlank() && !title.equals("YouTube"))
                    m.setPageTitle(title);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Derives aggregate bandwidth and quality statistics from the sweep samples
     * and writes them back into the given {@link VideoMetrics} object.
     *
     * <p>Computes peak and average bandwidth, determines the peak and lowest
     * quality labels observed, and sets {@code qualityDegraded} when YouTube's
     * ABR algorithm stepped down quality during the monitoring window.</p>
     *
     * @param m       the metrics object to enrich; must not be null
     * @param samples the ordered list of per-sweep samples; may be null or empty
     */
    private void applyNetworkAggregates(VideoMetrics m, List<NetworkSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        double peak  = samples.stream().mapToDouble(NetworkSample::getBandwidthKBps).max().orElse(0);
        double avg   = samples.stream().mapToDouble(NetworkSample::getBandwidthKBps).average().orElse(0);
        long   total = samples.stream().mapToLong(NetworkSample::getTotalSegmentBytes).max().orElse(-1);
        m.setPeakBandwidthKBps(peak);
        m.setAvgBandwidthKBps(avg);
        m.setTotalVideoSegmentBytes(total);

        // Compute quality history — "auto" and "unknown" are not specific levels,
        // so filter to only labels that appear in VideoMetrics.QUALITY_ORDER.
        List<String> ranked = samples.stream()
            .map(NetworkSample::getQualityLabel)
            .filter(q -> VideoMetrics.qualityRank(q) >= 0)
            .collect(Collectors.toList());
        m.setQualityHistory(ranked);

        if (!ranked.isEmpty()) {
            // Peak = highest quality observed (lowest rank index)
            String peakQ   = ranked.stream().min(Comparator.comparingInt(VideoMetrics::qualityRank)).orElse(null);
            // Lowest = worst quality observed (highest rank index)
            String lowestQ = ranked.stream().max(Comparator.comparingInt(VideoMetrics::qualityRank)).orElse(null);
            m.setPeakQualityLabel(peakQ);
            m.setLowestQualityLabel(lowestQ);
            // Degraded = worst quality rank > peak quality rank (i.e. ABR stepped down)
            m.setQualityDegraded(
                peakQ != null && lowestQ != null &&
                VideoMetrics.qualityRank(lowestQ) > VideoMetrics.qualityRank(peakQ)
            );
        }
    }

    // ── Final metrics snapshot ────────────────────────────────────────────────

    /**
     * Executes the final JavaScript metrics snapshot after all sweeps are complete
     * and stores the results in {@code m}. Waits briefly for a {@code <video>}
     * element to be present before executing the script.
     *
     * @param driver the active Chrome session (already switched to the target tab)
     * @param m      the metrics object to populate
     */
    private void collectFinalMetrics(WebDriver driver, VideoMetrics m) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(ELEMENT_WAIT_SECS))
                .until(ExpectedConditions.presenceOfElementLocated(By.tagName("video")));
        } catch (Exception ignored) {}

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>)
                ((JavascriptExecutor) driver).executeScript(finalMetricsScript());
            if (data != null) parseMetrics(m, data);
        } catch (Exception e) {
            logger.error("[Tab-{}] Final metrics error: {}", m.getTabIndex(), e.getMessage());
            m.setErrorMessage(e.getMessage());
        }
    }

    /**
     * Builds a {@link StatsForNerdsData} from the sweep samples already collected
     * when the live Stats-for-Nerds panel is unavailable (e.g. the browser process
     * died mid-run).
     *
     * <p>Connection speed and buffer health are averaged across all valid sweep
     * readings.  Frame counters are taken from the last sweep that reported them.
     * Resolution data is not available and is left unset, so the SFN Resolution
     * Match check is skipped for tabs where this path is taken.</p>
     *
     * <p>If the sample list is empty or no valid SFN values were recorded during
     * sweeps, no {@link StatsForNerdsData} is set and the tab keeps its current
     * (possibly {@code null}) SFN state.</p>
     *
     * @param m        the metrics object to update
     * @param samples  per-sweep samples for this tab
     * @param tabIndex 1-based tab number (for logging only)
     */
    private void synthesizeSfnFromSweeps(VideoMetrics m, List<NetworkSample> samples, int tabIndex) {
        // Only synthesize when live SFN data was not already captured.
        if (m.getSfnData() != null && m.getSfnData().isAvailable()) return;
        if (samples == null || samples.isEmpty()) return;

        OptionalDouble avgConnSpeed = samples.stream()
            .filter(s -> s.getConnectionSpeedKbps() > 0)
            .mapToDouble(NetworkSample::getConnectionSpeedKbps)
            .average();
        OptionalDouble avgBufHealth = samples.stream()
            .filter(s -> s.getBufferHealthSecs() >= 0)
            .mapToDouble(NetworkSample::getBufferHealthSecs)
            .average();

        if (avgConnSpeed.isEmpty() && avgBufHealth.isEmpty()) return;

        StatsForNerdsData sfn = new StatsForNerdsData();
        avgConnSpeed.ifPresent(sfn::setConnectionSpeedKbps);
        avgBufHealth.ifPresent(sfn::setBufferHealthSecs);
        // Take cumulative frame counters from the last sweep that reported them.
        samples.stream()
            .filter(s -> s.getDroppedFrames() >= 0)
            .reduce((a, b) -> b)
            .ifPresent(s -> {
                sfn.setDroppedFrames(s.getDroppedFrames());
                sfn.setTotalFrames(s.getTotalFrames());
            });
        m.setSfnData(sfn);
        logger.info("[Tab-{}] SFN synthesized from sweep data: speed(avg)={} Kbps, bufHealth(avg)={} s",
            tabIndex,
            avgConnSpeed.isPresent() ? String.format("%.0f", avgConnSpeed.getAsDouble()) : "n/a",
            avgBufHealth.isPresent() ? String.format("%.2f", avgBufHealth.getAsDouble()) : "n/a");
    }

    /**
     * Returns the JavaScript snippet that reads Navigation Timing, video element
     * state, and {@code VideoPlaybackQuality} from the current page.
     *
     * @return the JS source string; expects no arguments
     */
    private String finalMetricsScript() {
        return """
            const r = {};
            try {
                const t = window.performance.timing, ns = t.navigationStart;
                r.pageLoadTime         = t.loadEventEnd > 0          ? t.loadEventEnd - ns          : -1;
                r.domContentLoadedTime = t.domContentLoadedEventEnd > 0 ? t.domContentLoadedEventEnd - ns : -1;
                r.timeToFirstByte      = t.responseStart > 0         ? t.responseStart - ns         : -1;
                r.dnsLookupTime        = t.domainLookupEnd  - t.domainLookupStart;
                r.tcpConnectionTime    = t.connectEnd       - t.connectStart;
                r.domInteractiveTime   = t.domInteractive > 0        ? t.domInteractive - ns        : -1;
            } catch(e) {}
            try {
                const v = document.querySelector('video');
                r.videoFound = !!v;
                if (v) {
                    r.currentTime  = v.currentTime;
                    r.duration     = isFinite(v.duration) ? v.duration : 0;
                    r.bufferedSeconds = v.buffered && v.buffered.length > 0 ? v.buffered.end(v.buffered.length - 1) : 0;
                    r.readyState   = v.readyState;
                    r.networkState = v.networkState;
                    r.playbackRate = v.playbackRate;
                    r.videoWidth   = v.videoWidth;
                    r.videoHeight  = v.videoHeight;
                    r.paused       = v.paused;
                    r.ended        = v.ended;
                    try {
                        const q = v.getVideoPlaybackQuality();
                        if (q) { r.totalVideoFrames = q.totalVideoFrames; r.droppedVideoFrames = q.droppedVideoFrames; r.corruptedVideoFrames = q.corruptedVideoFrames; }
                    } catch(e) {}
                }
            } catch(e) {}
            try { r.pageTitle = document.title; } catch(e) {}
            return r;
            """;
    }

    /**
     * Transfers all fields from the raw JavaScript result map {@code d}
     * into the typed {@link VideoMetrics} object {@code m}.
     *
     * @param m the target metrics object
     * @param d the raw map returned by the final metrics JavaScript snippet
     */
    private void parseMetrics(VideoMetrics m, Map<String, Object> d) {
        m.setPageTitle(str(d, "pageTitle", "Unknown"));
        m.setPageLoadTime(toLong(d.get("pageLoadTime")));
        m.setDomContentLoadedTime(toLong(d.get("domContentLoadedTime")));
        m.setTimeToFirstByte(toLong(d.get("timeToFirstByte")));
        m.setDnsLookupTime(toLong(d.get("dnsLookupTime")));
        m.setTcpConnectionTime(toLong(d.get("tcpConnectionTime")));
        m.setDomInteractiveTime(toLong(d.get("domInteractiveTime")));
        m.setVideoFound(Boolean.TRUE.equals(d.get("videoFound")));
        m.setCurrentTime(toDouble(d.get("currentTime")));
        m.setDuration(toDouble(d.get("duration")));
        m.setBufferedSeconds(toDouble(d.get("bufferedSeconds")));
        m.setReadyState(toInt(d.get("readyState")));
        m.setNetworkState(toInt(d.get("networkState")));
        m.setPlaybackRate(toDouble(d.get("playbackRate")));
        m.setVideoWidth(toInt(d.get("videoWidth")));
        m.setVideoHeight(toInt(d.get("videoHeight")));
        m.setPaused(Boolean.TRUE.equals(d.get("paused")));
        m.setEnded(Boolean.TRUE.equals(d.get("ended")));
        if (d.containsKey("totalVideoFrames")) {
            m.setTotalVideoFrames(toLong(d.get("totalVideoFrames")));
            m.setDroppedVideoFrames(toLong(d.get("droppedVideoFrames")));
            m.setCorruptedVideoFrames(toLong(d.get("corruptedVideoFrames")));
        }
    }

    // ── Type-safe coercions ───────────────────────────────────────────────────

    /**
     * Safely converts a JavaScript numeric value (returned as {@link Long},
     * {@link Integer}, or {@link Double} by the Selenium JSON deserialiser)
     * to a Java {@code long}. Returns {@code -1} for {@code null} or unknown types.
     *
     * @param v the raw value from a JavaScript result map
     * @return the {@code long} equivalent, or {@code -1} if the value is absent
     */
    private long toLong(Object v) {
        if (v == null) return -1;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Double d) return d.longValue();
        if (v instanceof Number n) return n.longValue();
        return -1;
    }

    /**
     * Safely converts a JavaScript numeric value to a Java {@code double}.
     * Returns {@code 0.0} for {@code null} or unknown types.
     *
     * @param v the raw value from a JavaScript result map
     * @return the {@code double} equivalent, or {@code 0.0} if the value is absent
     */
    private double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    /**
     * Safely converts a JavaScript numeric value to a Java {@code int}.
     * Returns {@code -1} for {@code null} or unknown types.
     *
     * @param v the raw value from a JavaScript result map
     * @return the {@code int} equivalent, or {@code -1} if the value is absent
     */
    private int toInt(Object v) {
        if (v == null) return -1;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    /**
     * Retrieves a {@link String} value from a JavaScript result map, returning
     * {@code def} when the key is absent or the value is not a string.
     *
     * @param d   the raw map returned by a JavaScript snippet
     * @param key the map key to look up
     * @param def the default value to return when the key is missing or non-string
     * @return the String value, or {@code def}
     */
    private String str(Map<String, Object> d, String key, String def) {
        Object v = d.get(key);
        return v instanceof String s ? s : def;
    }
}
