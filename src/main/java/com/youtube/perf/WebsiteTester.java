package com.youtube.perf;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

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

    // ── Probe configuration (loaded from probe-timing.properties) ─────────────
    private static final int CYCLE_INTERVAL_MS;
    // Initial tab open (cold load: uncached DNS, new TLS, full JS bundle download).
    private static final int PAGE_LOAD_TIMEOUT_COLD_SECS;
    // Warm cycle limit — tabs are already open and connections are reused.
    private static final int PAGE_LOAD_TIMEOUT_SECS;
    private static final int ELEMENT_WAIT_SECS;

    static {
        Properties cfg = loadConfig();
        CYCLE_INTERVAL_MS           = Integer.parseInt(cfg.getProperty("website.probe.cycle_interval.ms",             "5000"));
        PAGE_LOAD_TIMEOUT_COLD_SECS = Integer.parseInt(cfg.getProperty("website.probe.page_load_timeout_cold.secs",   "45"));
        PAGE_LOAD_TIMEOUT_SECS      = Integer.parseInt(cfg.getProperty("website.probe.page_load_timeout_warm.secs",   "30"));
        ELEMENT_WAIT_SECS           = Integer.parseInt(cfg.getProperty("website.probe.element_wait.secs",             "15"));
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream is = WebsiteTester.class.getClassLoader()
                .getResourceAsStream("probe-timing.properties")) {
            if (is != null) {
                p.load(is);
            } else {
                logger.warn("'probe-timing.properties' not found — using built-in website probe defaults");
            }
        } catch (IOException e) {
            logger.warn("Failed to load 'probe-timing.properties': {} — using built-in defaults", e.getMessage());
        }
        return p;
    }

    private final List<String> urls;
    private final CountDownLatch startLatch;
    /** Epoch-ms at which the measurement loop must stop. Reset after latch sync. */
    private long absoluteDeadlineMs;
    private final int durationSeconds;
    /** Drop a tab only if it fails every cycle — gives transient outages a fair chance. */
    private final int maxConsecutiveFailures;
    /**
     * Shared results store published to Main for periodic reporting.
     * Must be thread-safe (e.g. {@code CopyOnWriteArrayList}).
     */
    private final List<WebsiteMetrics> liveResults;

    /**
     * Creates a new {@code WebsiteTester}.
     *
     * @param urls            list of website URLs to open and test
     * @param startLatch      synchronisation barrier counted down once all tabs are
     *                        ready; both browser probes count down the same latch
     * @param durationSeconds total monitoring duration in seconds; determines the
     *                        number of refresh cycles ({@code durationSeconds / 5})
     * @param liveResults     shared list used as the internal results store;
     *                        must be thread-safe (e.g. {@code CopyOnWriteArrayList})
     */
    public WebsiteTester(List<String> urls, CountDownLatch startLatch, int durationSeconds,
                         List<WebsiteMetrics> liveResults, long programStartMs) {
        this.urls                   = urls;
        this.startLatch             = startLatch;
        // Absolute deadline: programStartMs + durationSeconds.
        // Setup time (tab open + cold loads) is consumed from the budget, so total
        // wall-clock time equals durationSeconds regardless of how long setup takes.
        this.absoluteDeadlineMs     = programStartMs + durationSeconds * 1000L; // fallback; reset after latch
        this.durationSeconds        = durationSeconds;
        this.maxConsecutiveFailures = Math.max(1, durationSeconds / (CYCLE_INTERVAL_MS / 1000));
        this.liveResults            = liveResults;
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
        // Use the shared live list so each result is visible to the periodic reporter
        // thread in Main as soon as it is written.
        List<WebsiteMetrics> results = liveResults;

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
            // Reset the deadline NOW — after all probes are synced and tabs are open.
            // Tab setup (cold loads) can take 15-30 s; using programStartMs as the
            // origin causes the cycle loop to get fewer iterations than expected.
            absoluteDeadlineMs = System.currentTimeMillis() + (long) durationSeconds * 1000L;
            // Switch from the generous cold-load timeout used during tab setup to
            // the shorter warm-cycle timeout now that connections are established.
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECS));
            logger.info("[WebsiteTester] All probes ready \u2014 monitoring for {} s", durationSeconds);
            // Consecutive failure counter per tab. Incremented on navigation failure,
            // reset on success. When it reaches maxConsecutiveFailures the handle
            // is nulled so we stop wasting cycle time on a chronically-failing site.
            int[] consecutiveFailures = new int[handles.size()];
            int cycle = 0;
            while (System.currentTimeMillis() < absoluteDeadlineMs) {
                cycle++;
                long cycleStart = System.currentTimeMillis();
                logger.info("[WebsiteTester] ── Cycle {} ──", cycle);

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

                // Sleep for the remainder of the 5 s interval, but cap at the
                // time left before the absolute deadline so we don't overshoot.
                long elapsed  = System.currentTimeMillis() - cycleStart;
                long timeLeft = absoluteDeadlineMs - System.currentTimeMillis();
                long sleepMs  = Math.min(CYCLE_INTERVAL_MS - elapsed, timeLeft);
                if (sleepMs > 0) Thread.sleep(sleepMs);
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
        // Suppress ChromeDriver DevTools discovery logs (CDP version mismatch warnings)
        options.addArguments("--log-level=3");
        options.addArguments("--silent");
        options.addArguments(
            "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );
        ChromeDriver driver = new ChromeDriver(options);
        // Start with the cold-load timeout for initial tab setup.
        // It is reduced to PAGE_LOAD_TIMEOUT_SECS before measurement cycles begin.
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_COLD_SECS));
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

        // ── Phase 1: Launch all tabs directly at their target URLs ───────────────
        // Tab 1 already exists — navigate it via window.location.href (non-blocking).
        // Tabs 2..N are opened with window.open(url, '_blank') which tells Chrome to
        // start loading the real URL immediately — no about:blank intermediate step.
        try {
            handles.add(driver.getWindowHandle());
            ((JavascriptExecutor) driver).executeScript(
                "window.location.href = arguments[0];", urls.get(0));
            logger.info("[WebsiteTester] Triggered navigation tab 1: {}", urls.get(0));
        } catch (Exception e) {
            logger.warn("[WebsiteTester] Tab 1 failed to start navigation, will retry each cycle: {}", e.getMessage());
        }

        for (int i = 1; i < urls.size(); i++) {
            try {
                // Pass the real URL to window.open so Chrome begins the fetch right away.
                ((JavascriptExecutor) driver).executeScript(
                    "window.open(arguments[0], '_blank');", urls.get(i));
                Set<String> all = driver.getWindowHandles();
                final int tabIdx = i;
                String newHandle = all.stream()
                    .filter(h -> !handles.contains(h))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("New tab handle not found for URL " + tabIdx));
                handles.add(newHandle);
                logger.info("[WebsiteTester] Triggered navigation tab {}: {}", i + 1, urls.get(i));
            } catch (Exception e) {
                logger.warn("[WebsiteTester] Tab {} ({}) failed to open, will retry each cycle: {}",
                    i + 1, urls.get(i), e.getMessage());
                handles.add(null);
            }
        }

        // ── Phase 2: Wait for all tabs to reach readyState='complete' ────────────
        // All tabs are already loading their real URLs in parallel — just poll until
        // every tab reports 'complete'.  A short initial pause lets Chrome register
        // the navigations as 'loading' before the first readyState check so we don't
        // accidentally read a stale 'complete' from the previous page.
        Thread.sleep(500);

        long deadline = System.currentTimeMillis() + (long) PAGE_LOAD_TIMEOUT_COLD_SECS * 1000L;
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
                    boolean urlMatched = currentUrl != null && !currentUrl.startsWith("about:");
                    if ("complete".equals(state) && urlMatched) {
                        loaded[i] = true;
                        logger.info("[WebsiteTester] Tab {} ready: {}", i + 1, urls.get(i));
                    } else {
                        allDone = false;
                    }
                } catch (Exception e) {
                    // Tab unresponsive mid-load — stop polling and let cycles retry.
                    logger.warn("[WebsiteTester] Tab {} unresponsive during load check: {}", i + 1, e.getMessage());
                    loaded[i] = true;
                }
            }
            if (allDone) break;
            Thread.sleep(500);
        }
        for (int i = 0; i < handles.size(); i++) {
            if (!loaded[i] && handles.get(i) != null) {
                logger.warn("[WebsiteTester] Tab {} ({}) did not finish initial load within {}s — continuing",
                    i + 1, urls.get(i), PAGE_LOAD_TIMEOUT_COLD_SECS);
            }
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
            try {
                driver.navigate().to(url);
            } catch (TimeoutException te) {
                // Page load exceeded the timeout — the page may be partially loaded.
                // Stop Chrome from continuing to fetch resources, then try to read
                // whatever Navigation Timing data is already available before giving up.
                try { ((JavascriptExecutor) driver).executeScript("window.stop();"); } catch (Exception ignored) {}
                logger.warn("[Site-{}] Cycle {} load timeout for {} — collecting partial timings",
                    tabIndex, cycle, url);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> partial = (Map<String, Object>)
                        ((JavascriptExecutor) driver).executeScript("""
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
                    if (partial != null) {
                        m.setPageLoadTime(toLong(partial.get("pageLoadTime")));
                        m.setDomContentLoaded(toLong(partial.get("domContentLoaded")));
                        m.setTimeToFirstByte(toLong(partial.get("timeToFirstByte")));
                        m.setDnsLookupTime(toLong(partial.get("dnsLookupTime")));
                        m.setTcpConnectionTime(toLong(partial.get("tcpConnectionTime")));
                        m.setDomInteractiveTime(toLong(partial.get("domInteractiveTime")));
                    }
                    try { m.setPageTitle(driver.getTitle()); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
                m.setSuccess(false);
                // Keep first line of timeout message only — Selenium appends a full
                // capabilities/session dump after the first newline which is noise.
                m.setErrorMessage("timeout: page load exceeded " + PAGE_LOAD_TIMEOUT_SECS + "s");
                try { driver.navigate().to("about:blank"); } catch (Exception ignored) {}
                return m;
            }

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
            // Probe IPv4 and IPv6 TCP reachability to port 443 independently of the
            // browser so we can report on each IP version's stability every cycle.
            probeIpConnectivity(m.getDomain(), m);

        } catch (Exception e) {
            m.setSuccess(false);
            // Trim to first line — Selenium appends capabilities/session info after \n.
            String msg = e.getMessage();
            if (msg != null) {
                int nl = msg.indexOf('\n');
                msg = nl > 0 ? msg.substring(0, nl).trim() : msg.trim();
            }
            m.setErrorMessage(msg);
            logger.warn("[Site-{}] Cycle {} navigation error: {}", tabIndex, cycle, msg);
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
     * Probes TCP reachability to port 443 for the IPv4 (A record) and IPv6 (AAAA record)
     * addresses of the given domain, then records the results on the supplied metrics object.
     *
     * <p>This runs Java-side after each successful page load, independent of the browser.
     * A failure here means the OS network stack cannot reach the site over that IP version
     * even though Chrome may have used happy-eyeballs to load the page successfully.</p>
     *
     * <p>The probe uses a 3-second connect timeout per address to avoid blocking a cycle
     * when IPv6 is absent or filtered at the network level.</p>
     *
     * @param domain the hostname to probe (e.g. {@code "facebook.com"})
     * @param m      the metrics object to populate with IPv4/IPv6 results
     */
    private void probeIpConnectivity(String domain, WebsiteMetrics m) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(domain);
            InetAddress ipv4 = null, ipv6 = null;
            for (InetAddress a : addrs) {
                if (ipv4 == null && a instanceof Inet4Address) ipv4 = a;
                if (ipv6 == null && a instanceof Inet6Address) ipv6 = a;
            }

            if (ipv4 != null) {
                m.setIpv4Address(ipv4.getHostAddress());
                long s = System.nanoTime();
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(ipv4, 443), 3_000);
                    m.setIpv4ConnectMs((System.nanoTime() - s) / 1_000_000);
                    m.setIpv4Reachable(true);
                } catch (Exception e) {
                    m.setIpv4ConnectMs((System.nanoTime() - s) / 1_000_000);
                    m.setIpv4Reachable(false);
                }
            }

            if (ipv6 != null) {
                m.setIpv6Address(ipv6.getHostAddress());
                long s = System.nanoTime();
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(ipv6, 443), 3_000);
                    m.setIpv6ConnectMs((System.nanoTime() - s) / 1_000_000);
                    m.setIpv6Reachable(true);
                } catch (Exception e) {
                    m.setIpv6ConnectMs((System.nanoTime() - s) / 1_000_000);
                    m.setIpv6Reachable(false);
                }
            }

            logger.debug("[Site-{}] IP probe {} → IPv4:{} {}ms IPv6:{} {}ms",
                m.getTabIndex(), domain,
                m.getIpv4Address(), m.getIpv4ConnectMs(),
                m.getIpv6Address(), m.getIpv6ConnectMs());

        } catch (Exception e) {
            logger.warn("[Site-{}] IP connectivity probe failed for {}: {}",
                m.getTabIndex(), domain, e.getMessage());
        }
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
