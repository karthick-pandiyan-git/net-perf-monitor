package com.youtube.perf;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opens each website URL in a separate tab inside a single Chrome instance,
 * then refreshes every 5 seconds for a total of {@code durationSeconds} seconds.
 * Navigation Timing API metrics are collected after each refresh.
 *
 * Runs as an independent Callable so it can execute in parallel with
 * YouTubePerformanceTester and DnsMonitor via an ExecutorService.
 */
public class WebsiteTester implements Callable<List<WebsiteMetrics>> {

    private static final Logger logger = LoggerFactory.getLogger(WebsiteTester.class);

    private static final int CYCLE_INTERVAL_MS       = 5_000;
    private static final int PAGE_LOAD_TIMEOUT_SECS  = 10;  // reduced from 15 — crashes fail faster
    private static final int ELEMENT_WAIT_SECS       = 8;   // reduced from 10

    private final List<String> urls;
    private final CountDownLatch startLatch;
    private final int refreshCycles;
    /** Drop a tab only if it fails every cycle — gives transient outages a fair chance. */
    private final int maxConsecutiveFailures;

    /**
     * Creates a new {@code WebsiteTester}.
     *
     * @param urls            list of website URLs to open and test
     * @param startLatch      synchronisation barrier counted down once all tabs are
     *                        ready; both browser probes count down the same latch
     * @param durationSeconds total monitoring duration in seconds; determines the
     *                        number of refresh cycles ({@code durationSeconds / 5})
     */
    public WebsiteTester(List<String> urls, CountDownLatch startLatch, int durationSeconds) {
        this.urls                  = urls;
        this.startLatch            = startLatch;
        this.refreshCycles         = Math.max(1, durationSeconds / (CYCLE_INTERVAL_MS / 1000));
        this.maxConsecutiveFailures = this.refreshCycles;
    }

    /**
     * Implements the {@link Callable} contract: opens all tabs, waits for the
     * start barrier, then runs {@link #refreshCycles} measurement cycles —
     * one navigation per tab per cycle — collecting Navigation Timing metrics
     * after each load. Tabs that crash or time out repeatedly are permanently
     * skipped to keep the cycle budget stable.
     *
     * @return all collected {@link WebsiteMetrics} records (includes both successful
     *         and failed measurements)
     */
    @Override
    public List<WebsiteMetrics> call() {
        WebDriverManager.chromedriver().setup();
        WebDriver driver = createDriver();
        List<WebsiteMetrics> results = new ArrayList<>();

        try {
            List<String> handles;
            // Nested try-finally guarantees countDown() fires even if openAllTabs
            // throws (e.g. page load timeout), so YouTube never waits forever.
            try {
                handles = openAllTabs(driver, urls);
                logger.info("[WebsiteTester] Tabs ready \u2014 waiting for all probes to sync...");
            } finally {
                startLatch.countDown();
            }
            startLatch.await(5, TimeUnit.MINUTES);
            logger.info("[WebsiteTester] All probes ready \u2014 starting {}-second measurement window", refreshCycles * (CYCLE_INTERVAL_MS / 1000));
            // Consecutive failure counter per tab. Incremented on navigation failure,
            // reset on success. When it reaches maxConsecutiveFailures the handle
            // is nulled so we stop wasting cycle time on a chronically-failing site.
            int[] consecutiveFailures = new int[handles.size()];
            for (int cycle = 1; cycle <= refreshCycles; cycle++) {
                long cycleStart = System.currentTimeMillis();
                logger.info("[WebsiteTester] ── Cycle {}/{} ──", cycle, refreshCycles);

                for (int i = 0; i < handles.size(); i++) {
                    if (handles.get(i) == null) {
                        // Tab's renderer crashed and its handle is gone — unrecoverable,
                        // record FAIL and skip all remaining cycles for this tab.
                        WebsiteMetrics failed = new WebsiteMetrics();
                        failed.setUrl(urls.get(i));
                        failed.setDomain(extractDomain(urls.get(i)));
                        failed.setTabIndex(i + 1);
                        failed.setRefreshCycle(cycle);
                        failed.setTimestamp(System.currentTimeMillis());
                        failed.setSuccess(false);
                        failed.setErrorMessage("Tab lost (browser crash or memory pressure)");
                        results.add(failed);
                        continue;
                    }

                    try {
                        driver.switchTo().window(handles.get(i));
                        WebsiteMetrics m = navigateAndCollect(driver, urls.get(i), i + 1, cycle);
                        results.add(m);

                        if (m.isSuccess()) {
                            consecutiveFailures[i] = 0;  // reset on success
                            logger.info("  [Site-{}] {} | pageLoad={}ms TTFB={}ms DCL={}ms",
                                i + 1, m.getDomain(),
                                m.getPageLoadTime(), m.getTimeToFirstByte(), m.getDomContentLoaded());
                        } else {
                            consecutiveFailures[i]++;
                            logger.warn("  [Site-{}] {} | FAILED: {}", i + 1, m.getDomain(), m.getErrorMessage());
                            if (consecutiveFailures[i] >= maxConsecutiveFailures) {
                                // Site has failed every cycle so far — stop retrying to free up
                                // cycle time and prevent further Chrome renderer instability.
                                logger.warn("  [Site-{}] {} | {} consecutive failures — dropping tab",
                                    i + 1, m.getDomain(), consecutiveFailures[i]);
                                handles.set(i, null);
                            }
                        }
                    } catch (Exception e) {
                        // Tab handle has gone stale (Chrome discarded the tab under memory pressure,
                        // or the renderer crashed). Record a FAIL for this cycle and null the handle
                        // so all future cycles skip this tab rather than repeatedly failing here.
                        logger.warn("[WebsiteTester] Tab {} ({}) lost in cycle {}: {} — skipping for remaining cycles",
                            i + 1, urls.get(i), cycle, e.getMessage());
                        handles.set(i, null);
                        WebsiteMetrics failed = new WebsiteMetrics();
                        failed.setUrl(urls.get(i));
                        failed.setDomain(extractDomain(urls.get(i)));
                        failed.setTabIndex(i + 1);
                        failed.setRefreshCycle(cycle);
                        failed.setTimestamp(System.currentTimeMillis());
                        failed.setSuccess(false);
                        failed.setErrorMessage("Tab lost (browser crash or memory pressure): " + e.getMessage());
                        results.add(failed);
                    }
                }

                // Sleep for the remainder of the 5 s interval before the next cycle
                long elapsed = System.currentTimeMillis() - cycleStart;
                long remaining = CYCLE_INTERVAL_MS - elapsed;
                if (remaining > 0 && cycle < refreshCycles) {
                    Thread.sleep(remaining);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("[WebsiteTester] Interrupted", e);
        } catch (Exception e) {
            // Catch transient browser/renderer errors so partial results are still returned.
            logger.error("[WebsiteTester] Error during monitoring: {}", e.getMessage(), e);
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
            logger.info("[WebsiteTester] Browser closed.");
        }

        return results;
    }

    // ── Driver setup ──────────────────────────────────────────────────────────
    /**
     * Creates and configures the Chrome instance used for website testing.
     * Background-tab throttling flags are disabled so navigation timings are
     * representative of a foreground load even when the tab is not focused.
     *
     * @return a ready-to-use {@link WebDriver} with navigation timeouts applied
     */    private WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-infobars");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        // Keep background tabs fully active so navigation timing is not skewed
        // by Chrome throttling timers or deprioritising occluded renderers.
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-backgrounding-occluded-windows");
        options.addArguments(
            "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECS));
        return driver;
    }

    // ── Tab management ────────────────────────────────────────────────────────

    /**
     * Opens each URL in a new browser tab and returns the corresponding list of
     * window handles. A per-URL failure is logged and handled gracefully — the
     * handle is still recorded (or defaulted) so that each measurement cycle can
     * retry the navigation when the network recovers.
     *
     * @param driver the Chrome instance to reuse
     * @param urls   list of website URLs to open
     * @return an ordered list of window handles (one per URL; may contain {@code null}
     *         entries when a tab could not be opened at all)
     * @throws InterruptedException if the thread is interrupted during tab setup
     */
    private List<String> openAllTabs(WebDriver driver, List<String> urls) throws InterruptedException {
        List<String> handles = new ArrayList<>();

        for (int i = 0; i < urls.size(); i++) {
            try {
                if (i == 0) {
                    driver.get(urls.get(i));
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
                    driver.get(urls.get(i));
                }
                logger.info("[WebsiteTester] Opened tab {}: {}", i + 1, urls.get(i));
            } catch (Exception e) {
                // Tab failed to open (e.g. internet was down). Log the failure but
                // KEEP the window handle so each measurement cycle can retry the
                // navigation — when internet is restored mid-test the cycle will
                // succeed. Only permanently drop the tab (null the handle) after
                // MAX_CONSECUTIVE_FAILURES cycles have failed in a row.
                logger.warn("[WebsiteTester] Tab {} failed to open ({}), will retry each cycle: {}",
                    i + 1, urls.get(i), e.getMessage());
                // For i=0, driver.get() throws before handles.add(), so we must add
                // the handle now. For i>0 the handle was already added before driver.get().
                if (handles.size() <= i) {
                    try {
                        handles.add(driver.getWindowHandle());
                    } catch (Exception ignored) {
                        handles.add(null); // no window at all — truly unrecoverable
                    }
                }
                // Park on about:blank to release the renderer immediately.
                try { ((JavascriptExecutor) driver).executeScript("window.stop();"); } catch (Exception ignored) {}
                try { driver.navigate().to("about:blank"); } catch (Exception ignored) {}
            }
            Thread.sleep(500);
        }
        return handles;
    }

    // ── Navigation + metric collection ───────────────────────────────────────
    /**
     * Navigates the given tab to {@code url}, waits for the page to reach
     * {@code document.readyState === 'complete'}, then reads Navigation Timing
     * metrics via JavaScript.
     *
     * <p>On navigation error the returned object has {@code success = false} and
     * the error message populated. The tab is left on {@code about:blank} so it
     * does not hold the renderer busy for the next cycle.</p>
     *
     * @param driver   the Chrome instance (already switched to the correct tab)
     * @param url      the website URL to load
     * @param tabIndex 1-based tab number used in log messages
     * @param cycle    the current 1-based refresh cycle number
     * @return a fully populated {@link WebsiteMetrics} for this cycle
     */    private WebsiteMetrics navigateAndCollect(WebDriver driver, String url, int tabIndex, int cycle) {
        WebsiteMetrics m = new WebsiteMetrics();
        m.setUrl(url);
        m.setDomain(extractDomain(url));
        m.setTabIndex(tabIndex);
        m.setRefreshCycle(cycle);
        m.setTimestamp(System.currentTimeMillis());

        try {
            driver.navigate().to(url);

            // Wait for document.readyState === 'complete' so timing values are final
            new WebDriverWait(driver, Duration.ofSeconds(ELEMENT_WAIT_SECS))
                .until(d -> "complete".equals(
                    ((JavascriptExecutor) d).executeScript("return document.readyState;")));

            // Guard: if the navigation was aborted (e.g. network error, anti-bot redirect,
            // or a prior cleanup that left the tab on about:blank), Chrome reports
            // readyState='complete' immediately, so no exception is raised and the
            // metrics would be silently collected from a blank page. Detect this early.
            String currentUrl = driver.getCurrentUrl();
            if (currentUrl == null || currentUrl.isBlank()
                    || currentUrl.startsWith("about:") || currentUrl.startsWith("data:")) {
                throw new IllegalStateException(
                    "Navigation did not reach the target URL — landed on: " + currentUrl);
            }

            // Dismiss simple cookie banners / consent dialogs that may block rendering
            dismissConsentDialogs(driver);

            @SuppressWarnings("unchecked")
            Map<String, Object> timing = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                var t = performance.timing, ns = t.navigationStart;
                return {
                    pageLoadTime:       t.loadEventEnd > 0             ? t.loadEventEnd - ns             : -1,
                    domContentLoaded:   t.domContentLoadedEventEnd > 0 ? t.domContentLoadedEventEnd - ns : -1,
                    timeToFirstByte:    t.responseStart > 0            ? t.responseStart - ns            : -1,
                    dnsLookupTime:      t.domainLookupEnd  - t.domainLookupStart,
                    tcpConnectionTime:  t.connectEnd       - t.connectStart,
                    domInteractiveTime: t.domInteractive > 0           ? t.domInteractive - ns           : -1
                };
                """);

            if (timing != null) {
                m.setPageLoadTime(toLong(timing.get("pageLoadTime")));
                m.setDomContentLoaded(toLong(timing.get("domContentLoaded")));
                m.setTimeToFirstByte(toLong(timing.get("timeToFirstByte")));
                m.setDnsLookupTime(toLong(timing.get("dnsLookupTime")));
                m.setTcpConnectionTime(toLong(timing.get("tcpConnectionTime")));
                m.setDomInteractiveTime(toLong(timing.get("domInteractiveTime")));
            }

            try { m.setPageTitle(driver.getTitle()); } catch (Exception ignored) {}
            m.setSuccess(true);

        } catch (Exception e) {
            m.setSuccess(false);
            m.setErrorMessage(e.getMessage());
            logger.warn("[Site-{}] Cycle {} navigation error: {}", tabIndex, cycle, e.getMessage());
            // Stop any pending page load and navigate back to a blank page.
            // Without this, Chrome keeps trying to load the failed page in the
            // background, holding the renderer busy and causing ALL subsequent
            // tabs to block or crash in future cycles.
            try {
                ((JavascriptExecutor) driver).executeScript("window.stop();");
            } catch (Exception ignored) {}
            try {
                driver.navigate().to("about:blank");
            } catch (Exception ignored) {}
        }

        return m;
    }

    /**
     * Clicks common cookie-consent / "Accept all" buttons so they don't skew
     * subsequent timing measurements.
     */
    private void dismissConsentDialogs(WebDriver driver) {
        try {
            List<WebElement> candidates = driver.findElements(By.cssSelector(
                "button[id*='accept'], button[id*='consent'], button[id*='agree'], " +
                "button[class*='accept'], button[class*='consent'], " +
                "[aria-label*='Accept all'], [aria-label*='I agree']"
            ));
            for (WebElement btn : candidates) {
                if (btn.isDisplayed()) { btn.click(); break; }
            }
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the hostname portion of a URL, stripping the scheme and {@code www.} prefix.
     * For example, {@code https://www.example.com/path} returns {@code example.com}.
     *
     * @param url the full URL string
     * @return the bare hostname (e.g. {@code example.com})
     */
    private String extractDomain(String url) {
        return url.replaceFirst("https?://(www\\.)?", "").replaceFirst("/.*", "");
    }

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
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Double d)  return d.longValue();
        if (v instanceof Number n)  return n.longValue();
        return -1;
    }
}
