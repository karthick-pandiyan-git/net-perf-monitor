package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Central repository for all CSS selectors, element IDs, and XPath label names
 * used by {@link YouTubePerformanceTester} to interact with the YouTube player DOM.
 *
 * <p>Values are loaded once at class-initialisation time from
 * {@code browser-selectors.properties} on the classpath.  Update that file
 * when YouTube changes its player markup — no Java recompile needed.</p>
 *
 * <p>The multi-value pause-dialog and SFN-fallback properties use {@code " | "}
 * as a separator so individual selector strings may contain commas.</p>
 */
public final class BrowserSelectors {

    private static final Logger logger = LoggerFactory.getLogger(BrowserSelectors.class);
    private static final String CONFIG_FILE = "browser-selectors.properties";

    // ── Player element ────────────────────────────────────────────────────────

    /** The {@code id} attribute of the YouTube movie-player div. */
    public static final String MOVIE_PLAYER_ID;

    // ── Consent / cookie dialog ───────────────────────────────────────────────

    /** Combined CSS selector for the Google / YouTube consent / cookie dialog button. */
    public static final String CONSENT_DIALOG_CSS;

    // ── Ad overlay selectors ──────────────────────────────────────────────────

    /** CSS selector for the close button of a YouTube overlay (banner/card) ad. */
    public static final String OVERLAY_AD_CLOSE_CSS;

    /** CSS selector for the skip-ad button (pre-roll / mid-roll). */
    public static final String SKIP_AD_CSS;

    /**
     * CSS selector used to detect whether any ad (pre-roll, mid-roll, bumper)
     * is currently occupying the player.  Referenced from both the Java ad-wait
     * loop and the inline network-sample JavaScript snippet.
     */
    public static final String AD_PLAYER_OVERLAY_CSS;

    // ── Pause / interrupt dialog selectors ────────────────────────────────────

    /**
     * Ordered array of CSS selectors for the "Continue watching?" / "Still watching?"
     * pause-interrupt dialog buttons.  The first visible match is clicked.
     */
    public static final String[] PAUSE_DIALOG_SELECTORS;

    // ── Stats for Nerds panel selectors ──────────────────────────────────────

    /** Primary CSS selector for the Stats-for-Nerds panel container. */
    public static final String SFN_PANEL_CSS;

    /** CSS selector for label cells inside the Stats-for-Nerds table. */
    public static final String SFN_LABEL_CSS;

    /** CSS selector for value cells inside the Stats-for-Nerds table. */
    public static final String SFN_VALUE_CSS;

    /**
     * Ordered array of panel-container CSS selectors used as a third-strategy
     * fallback in {@code readStatsForNerds} when the label/value class search
     * and XPath search both return empty results.
     */
    public static final String[] SFN_PANEL_FALLBACK_SELECTORS;

    /**
     * Human-readable label names shown in Stats-for-Nerds panel rows.
     * Strategy 2 in {@code readStatsForNerds} uses XPath text-node matching
     * against these names — completely independent of CSS class names.
     */
    public static final String[] SFN_KNOWN_LABELS;

    // ── Context menu ──────────────────────────────────────────────────────────

    /** CSS selector for items in the YouTube right-click context menu. */
    public static final String CONTEXT_MENU_ITEM_CSS;

    // ── Static initialiser ────────────────────────────────────────────────────

    static {
        Properties cfg = loadConfig();
        MOVIE_PLAYER_ID            = cfg.getProperty("yt.player.element_id",          "movie_player");
        CONSENT_DIALOG_CSS         = cfg.getProperty("yt.selector.consent_dialog",
            "button[aria-label*='Accept all'], button[aria-label*='Agree'], tp-yt-paper-button#agree, .eom-buttons button");
        OVERLAY_AD_CLOSE_CSS       = cfg.getProperty("yt.selector.overlay_ad_close",
            ".ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container");
        SKIP_AD_CSS                = cfg.getProperty("yt.selector.skip_ad",
            ".ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-skip-ad-button-container button, .ytp-ad-skip-button-container button");
        AD_PLAYER_OVERLAY_CSS      = cfg.getProperty("yt.selector.ad_player_overlay",
            ".ytp-ad-player-overlay, .ytp-ad-player-overlay-layout");
        SFN_PANEL_CSS              = cfg.getProperty("yt.selector.sfn_panel",
            ".ytp-sfn-stats, [class*=\"sfn-stats\"], .html5-video-info-panel, [class*=\"video-info-panel\"]");
        SFN_LABEL_CSS              = cfg.getProperty("yt.selector.sfn_label",
            ".ytp-sfn-label, [class*=\"sfn-label\"], [class*=\"sfn\"][class*=\"label\"]");
        SFN_VALUE_CSS              = cfg.getProperty("yt.selector.sfn_value",
            ".ytp-sfn-value, [class*=\"sfn-value\"], [class*=\"sfn\"][class*=\"value\"]");
        CONTEXT_MENU_ITEM_CSS      = cfg.getProperty("yt.selector.context_menu_item",
            ".ytp-contextmenu .ytp-menuitem-label,.ytp-contextmenu .ytp-menuitem");

        PAUSE_DIALOG_SELECTORS     = loadIndexedList(cfg, "yt.selector.pause_dialog.", new String[]{
            ".ytp-pause-overlay .ytp-pause-overlay-actions button",
            ".yt-confirm-dialog-renderer button",
            "tp-yt-paper-button.yt-confirm-dialog-renderer--confirm-button",
            "button[aria-label*=\"Continue\"], button[aria-label*=\"Yes\"], button[aria-label*=\"continue\"]",
            "yt-confirm-dialog-renderer paper-button[dialog-confirm]",
            "#confirm-button"
        });

        SFN_PANEL_FALLBACK_SELECTORS = splitPipeSeparated(
            cfg.getProperty("yt.selector.sfn_panel_fallbacks",
                ".ytp-sfn-stats | .html5-video-info-panel | [class*=\"sfn-stats\"] | [class*=\"sfn\"] | [class*=\"video-info-panel\"]"));

        SFN_KNOWN_LABELS = splitCommaSeparated(
            cfg.getProperty("yt.sfn.known_labels",
                "Connection Speed,Buffer Health,Network Activity,Current / Optimal Res,Codecs,Frames,Viewport / Frames"));
    }

    private BrowserSelectors() {}

    // ── Helpers for JavaScript injection ─────────────────────────────────────

    /**
     * Converts an array of selector strings into a JavaScript array literal, e.g.
     * <pre>['selector1', 'selector2']</pre>
     * Single quotes inside selector values are escaped.  Use this to inject the
     * pause-dialog selectors array into a JavaScript string at call sites.
     *
     * @param selectors the selector array to serialise
     * @return a JS array literal string ready to embed in a {@code executeScript} call
     */
    public static String toJsArray(String[] selectors) {
        return Arrays.stream(selectors)
            .map(s -> "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'")
            .collect(Collectors.joining(", ", "[", "]"));
    }

    // ── Private loading utilities ─────────────────────────────────────────────

    /**
     * Loads {@code browser-selectors.properties} from the classpath.
     * Falls back to an empty {@link Properties} (triggering in-code defaults for
     * every constant) if the file cannot be found or read.
     */
    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream is = BrowserSelectors.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                p.load(is);
            } else {
                logger.warn("'{}' not found on classpath — using built-in selector defaults", CONFIG_FILE);
            }
        } catch (IOException e) {
            logger.warn("Failed to load '{}': {} — using built-in selector defaults", CONFIG_FILE, e.getMessage());
        }
        return p;
    }

    /**
     * Loads indexed list entries from properties (keys {@code prefix1}, {@code prefix2}, …)
     * until a missing key is encountered.  Falls back to {@code defaults} when no
     * keys are present.
     */
    private static String[] loadIndexedList(Properties cfg, String prefix, String[] defaults) {
        List<String> list = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = cfg.getProperty(prefix + i);
            if (value == null) break;
            list.add(value.trim());
        }
        return list.isEmpty() ? defaults : list.toArray(new String[0]);
    }

    /** Splits a {@code " | "}-delimited string into a trimmed array. */
    private static String[] splitPipeSeparated(String value) {
        return Arrays.stream(value.split("\\|"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    /** Splits a comma-delimited string into a trimmed array. */
    private static String[] splitCommaSeparated(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }
}
