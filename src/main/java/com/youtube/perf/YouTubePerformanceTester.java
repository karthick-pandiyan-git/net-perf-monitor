package com.youtube.perf;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private static final int SWEEP_INTERVAL_SECONDS  = 5;
    private static final int PAGE_LOAD_TIMEOUT_SECS  = 45;
    private static final int ELEMENT_WAIT_SECS       = 15;

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
     * @param urls            YouTube video URLs to open (one per tab)
     * @param startLatch      barrier counted down once tab setup is complete
     * @param durationSeconds total monitoring window in seconds
     * @return per-tab {@link VideoMetrics} list, ordered by tab index
     */
    public List<VideoMetrics> runTest(List<String> urls, CountDownLatch startLatch, int durationSeconds) {
        int numSweeps = Math.max(1, durationSeconds / SWEEP_INTERVAL_SECONDS);
        WebDriverManager.chromedriver().setup();
        WebDriver driver = createDriver();

        // Ordered list of window handles, matching url index
        List<String> handles  = new ArrayList<>();
        List<VideoMetrics> metrics = new ArrayList<>();
        // Per-handle network sample accumulator
        Map<String, List<NetworkSample>> samplesMap = new LinkedHashMap<>();

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
                logger.info("[YouTube] Tabs ready \u2014 waiting for all probes to sync...");
            } finally {
                startLatch.countDown();
            }
            startLatch.await(5, TimeUnit.MINUTES);
            logger.info("[YouTube] All probes ready \u2014 starting {}-second measurement window", durationSeconds);
            // ── 2. Monitoring sweeps ─────────────────────────────────────────
            for (int sweep = 1; sweep <= numSweeps; sweep++) {
                Thread.sleep(SWEEP_INTERVAL_SECONDS * 1000L);
                logger.info("── Sweep {}/{} ──────────────────────────────────", sweep, numSweeps);

                for (int i = 0; i < handles.size(); i++) {
                    driver.switchTo().window(handles.get(i));
                    // Use the lightweight sweep handler — no quality re-setting
                    handleSweep(driver, i + 1);

                    NetworkSample sample = collectNetworkSample(driver, sweep);
                    samplesMap.get(handles.get(i)).add(sample);

                    logger.info("  [Tab-{}] #{} | {} KB/s | played={}s | buffered={}s | {} new segs | quality={}",
                        i + 1, sweep,
                        String.format("%.1f", sample.getBandwidthKBps()),
                        String.format("%.1f", sample.getVideoCurrentTime()),
                        String.format("%.1f", sample.getVideoBuffered()),
                        sample.getRecentSegmentCount(),
                        // Mark ad-during samples clearly so logs are unambiguous
                        sample.isAdPlaying() ? "[AD-skipped]" :
                            sample.getQualityLabel() != null ? sample.getQualityLabel() : "?");
                }
            }  // end sweep loop

            // ── 3. Final per-tab metrics collection ──────────────────────────
            for (int i = 0; i < handles.size(); i++) {
                driver.switchTo().window(handles.get(i));
                // Lightweight sweep handler for the final pass too — we only need
                // the video to be playing; quality must not be altered at this stage.
                handleSweep(driver, i + 1);

                VideoMetrics m = metrics.get(i);
                List<NetworkSample> samples = samplesMap.get(handles.get(i));
                m.setNetworkSamples(samples);
                applyNetworkAggregates(m, samples);
                m.setMetricsCollectedAt(System.currentTimeMillis());
                collectFinalMetrics(driver, m);
                logger.info("[Tab-{}] Final metrics collected", i + 1);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Test interrupted", e);
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
     * @param driver  the Chrome instance to reuse
     * @param urls    list of YouTube video URLs to open
     * @param handles output list that receives one window handle per URL, in order
     * @throws InterruptedException if the thread is interrupted during tab setup
     */
    private void openAllTabs(WebDriver driver, List<String> urls, List<String> handles)
            throws InterruptedException {

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (i == 0) {
                driver.get(url);
                handles.add(driver.getWindowHandle());
            } else {
                ((JavascriptExecutor) driver).executeScript("window.open('about:blank', '_blank');");
                Set<String> all = driver.getWindowHandles();
                String newHandle = all.stream()
                    .filter(h -> !handles.contains(h))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("New tab handle not found"));
                handles.add(newHandle);
                driver.switchTo().window(newHandle);
                driver.get(url);
            }
            logger.info("[Tab-{}] Opened: {}", i + 1, url);
            dismissConsentDialog(driver);
            handleAdsAndForcePlay(driver, i + 1);
            Thread.sleep(800);
        }
        logger.info("All {} tabs open in one Chrome instance.", urls.size());
    }

    // ── Ad handling + force-play ──────────────────────────────────────────────

    /**
     * Full setup-time handler: dismisses consent dialogs, skips/waits for ads,
     * force-starts playback, and sets the highest available quality once.
     * Called during tab initialisation only.
     */
    private void handleAdsAndForcePlay(WebDriver driver, int tabIndex) {
        try {
            dismissConsentDialog(driver);
            closeOverlayAd(driver);
            skipOrWaitForAd(driver, tabIndex);
            forcePlay(driver, tabIndex);
            setHighestQuality(driver, tabIndex);
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
                "button[aria-label*='Accept all'], button[aria-label*='Agree'], " +
                "tp-yt-paper-button#agree, .eom-buttons button"
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
                By.cssSelector(".ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container")
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
    private static final int MAX_AD_ROUNDS         = 5;
    /** Max time to wait for a single ad to resolve (skip or finish). */
    private static final int MAX_AD_WAIT_MS        = 45_000;
    /** Poll interval inside the unified ad wait loop. */
    private static final int AD_POLL_MS            = 500;
    /** Pause between ads to let YouTube load the next one before re-checking. */
    private static final int AD_TRANSITION_WAIT_MS = 2_000;

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
            boolean adShowing = !driver.findElements(
                By.cssSelector(".ytp-ad-player-overlay, .ytp-ad-player-overlay-layout")
            ).isEmpty();
            if (!adShowing) return;

            logger.info("[Tab-{}] Ad detected (round {}) — polling for skip button or ad end...", tabIndex, round);

            long deadline = System.currentTimeMillis() + MAX_AD_WAIT_MS;
            boolean resolved = false;

            while (System.currentTimeMillis() < deadline) {
                // (a) Check if the overlay has already gone (non-skippable ad finished)
                boolean overlayGone = driver.findElements(
                    By.cssSelector(".ytp-ad-player-overlay, .ytp-ad-player-overlay-layout")
                ).isEmpty();
                if (overlayGone) {
                    logger.info("[Tab-{}] Ad finished naturally (round {})", tabIndex, round);
                    resolved = true;
                    break;
                }

                // (b) Check if a skip button is now visible and click it immediately.
                // Polled on every tick so we never miss a button that appears late
                // (YouTube enables skip at 5 s for standard ads, but 10–15 s for others).
                try {
                    List<WebElement> btns = driver.findElements(By.cssSelector(
                        ".ytp-skip-ad-button, .ytp-ad-skip-button, " +
                        ".ytp-skip-ad-button-container button, .ytp-ad-skip-button-container button"
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
     * Ensures the video element is actively playing.
     * Dismisses any "Continue watching?" confirmation dialog, then calls
     * {@code video.play()} via JavaScript if the video is paused.
     * All exceptions are silently swallowed — this is a best-effort helper.
     *
     * @param driver   the active Chrome session
     * @param tabIndex 1-based tab number used in debug log messages
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    private void forcePlay(WebDriver driver, int tabIndex) throws InterruptedException {
        try {
            WebElement btn = driver.findElement(By.cssSelector(
                ".yt-confirm-dialog-renderer button, " +
                "tp-yt-paper-button.yt-confirm-dialog-renderer--confirm-button"
            ));
            if (btn.isDisplayed()) { btn.click(); Thread.sleep(400); }
        } catch (Exception ignored) {}

        try {
            Object result = ((JavascriptExecutor) driver).executeScript("""
                var v = document.querySelector('video');
                if (v) { v.muted = true; if (v.paused) { v.play().catch(function(){}); return 'played'; } return 'playing'; }
                return 'no-video';
                """);
            logger.debug("[Tab-{}] forcePlay: {}", tabIndex, result);
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
            Object result = ((JavascriptExecutor) driver).executeScript("""
                var player = document.getElementById('movie_player');
                if (!player || typeof player.getAvailableQualityLevels !== 'function') return 'no-player';
                var levels = player.getAvailableQualityLevels();
                if (!levels || levels.length === 0) return 'no-levels';
                var best = levels[0]; // YouTube returns levels sorted highest-first
                player.setPlaybackQualityRange(best, best);
                return 'set:' + best;
                """);
            logger.info("[Tab-{}] Quality set to: {}", tabIndex, result);
        } catch (Exception e) {
            logger.debug("[Tab-{}] setHighestQuality (non-fatal): {}", tabIndex, e.getMessage());
        }
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
                    (long)(SWEEP_INTERVAL_SECONDS * 1000));

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
                var player = document.getElementById('movie_player');
                // Check whether an ad is currently playing before reading quality.
                // Ads stream at 'medium'/'small' regardless of connection speed;
                // including that reading would falsely flag a quality degradation.
                // Detection: the ad overlay element is present and visible.
                var adOverlay = document.querySelector('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout');
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
