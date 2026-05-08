package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML performance report with Chart.js graphs and
 * a timeline view of all probes. Written to {@code net_performance_report.html}
 * in the working directory.
 *
 * <p>Charts included:</p>
 * <ul>
 *   <li>YouTube — per-tab bandwidth + buffer depth line chart over sweep time</li>
 *   <li>YouTube — TTFB / page-load bar chart across all tabs</li>
 *   <li>Website — per-domain page-load and TTFB line chart per refresh cycle</li>
 *   <li>DNS     — avg response latency bar chart, per domain × record type</li>
 * </ul>
 */
public class HtmlReporter {

    private static final Logger logger = LoggerFactory.getLogger(HtmlReporter.class);

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Provides per-domain page-load and TTFB thresholds used for PASS/FAIL
     * annotation in the HTML report. Accepts {@code null} to retrieve defaults.
     */
    private final Function<String, WebsiteThresholds> thresholdsFor;
    /** Configured test duration in seconds — used for minimum-cycle coverage checks. */
    private final int durationSeconds;
    /** Minimum fraction of expected website cycles that must complete (from statistics.properties). */
    private final double minCyclesRatio;
    /** Website cycle interval in milliseconds (from probe-timing.properties). */
    private final int cycleIntervalMs;

    public HtmlReporter(Function<String, WebsiteThresholds> thresholdsFor, int durationSeconds) {
        this.thresholdsFor  = thresholdsFor;
        this.durationSeconds = durationSeconds;

        // Load min-cycles ratio from statistics.properties
        java.util.Properties stats = new java.util.Properties();
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("statistics.properties")) {
            if (is != null) stats.load(is);
        } catch (IOException ignored) { /* defaults apply */ }
        this.minCyclesRatio = Double.parseDouble(stats.getProperty("website.min_cycles_ratio", "0.25"));

        // Load cycle interval from probe-timing.properties
        java.util.Properties probe = new java.util.Properties();
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("probe-timing.properties")) {
            if (is != null) probe.load(is);
        } catch (IOException ignored) { /* defaults apply */ }
        this.cycleIntervalMs = Integer.parseInt(probe.getProperty("website.probe.cycle_interval.ms", "5000"));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates and writes the full HTML report.
     *
     * @param ytMetrics   YouTube tab metrics (may be null/empty)
     * @param webMetrics  Website cycle metrics (may be null/empty)
     * @param dnsResults  DNS query results (may be null/empty)
     * @param ytVerdicts  Pre-evaluated YouTube verdicts (may be null/empty)
     * @param outputPath  Destination file path
     */
    public void saveReport(
            List<VideoMetrics>   ytMetrics,
            List<WebsiteMetrics> webMetrics,
            List<DnsResult>      dnsResults,
            List<VideoVerdict>   ytVerdicts,
            String outputPath) {

        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8))) {
            w.print(buildHtml(
                ytMetrics  != null ? ytMetrics  : List.of(),
                webMetrics != null ? webMetrics : List.of(),
                dnsResults != null ? dnsResults : List.of(),
                ytVerdicts != null ? ytVerdicts : List.of()));
            logger.info("HTML report saved to {}", outputPath);
            System.out.printf("HTML report saved → %s%n", outputPath);
        } catch (IOException e) {
            logger.error("Failed to write HTML report: {}", e.getMessage());
        }
    }

    // ── HTML construction ─────────────────────────────────────────────────────

    private String buildHtml(
            List<VideoMetrics>   ytMetrics,
            List<WebsiteMetrics> webMetrics,
            List<DnsResult>      dnsResults,
            List<VideoVerdict>   ytVerdicts) {

        StringBuilder sb = new StringBuilder(65_536);
        String now = LocalDateTime.now().format(DT_FMT);

        // overall verdict badge counts
        long ytFail  = ytVerdicts.stream().filter(v -> !v.isPassed()).count();
        long webFail = countWebFail(webMetrics);
        long dnsFail = dnsResults.stream().filter(r -> !r.isSuccess()).count();

        // Smart spike pattern analysis — distinguishes random noise from failures.
        SpikeAnalyzer.Result dnsSpikeAnalysis = SpikeAnalyzer.analyzeDns(dnsResults);
        SpikeAnalyzer.Result webSpikeAnalysis = SpikeAnalyzer.analyzeWebsite(webMetrics, thresholdsFor);

        sb.append(htmlHead(now));
        sb.append("<body>\n");
        sb.append(header(now, ytFail, webFail, dnsFail,
                         dnsSpikeAnalysis, webSpikeAnalysis,
                         ytMetrics, webMetrics, dnsResults));

        // ── Executive summary ─────────────────────────────────────────────────
        if (!ytMetrics.isEmpty() || !webMetrics.isEmpty() || !dnsResults.isEmpty()) {
            sb.append(execSummary(ytMetrics, webMetrics, dnsResults, ytVerdicts,
                                  dnsSpikeAnalysis, webSpikeAnalysis));
        }

        // ── Navigation tabs ───────────────────────────────────────────────────
        List<String> sections = new ArrayList<>();
        if (!ytMetrics.isEmpty())  sections.add("youtube");
        if (!webMetrics.isEmpty()) sections.add("website");
        if (!dnsResults.isEmpty()) sections.add("dns");
        if (!ytMetrics.isEmpty() || !webMetrics.isEmpty() || !dnsResults.isEmpty())
            sections.add("timeline");

        if (!sections.isEmpty()) {
            sb.append(navTabs(sections));
        }

        // ── YouTube section ───────────────────────────────────────────────────
        if (!ytMetrics.isEmpty()) {
            sb.append("<section id=\"sec-youtube\" class=\"section\">\n");
            sb.append("  <h2>YouTube Streaming</h2>\n");

            // Tab cards — overview bar chart inserted after Tab #2 (index 1).
            // If there is only one tab, the overview appears after it.
            for (int ti = 0; ti < ytMetrics.size(); ti++) {
                VideoMetrics m = ytMetrics.get(ti);
                VideoVerdict v = ytVerdicts.stream()
                    .filter(vd -> vd.getMetrics().getTabIndex() == m.getTabIndex())
                    .findFirst().orElse(null);
                sb.append(ytTabCard(m, v));
                if (ti == Math.min(1, ytMetrics.size() - 1)) {
                    sb.append(ytOverviewChart(ytMetrics));
                }
            }
            sb.append("</section>\n");
        }

        // ── Website section ───────────────────────────────────────────────────
        if (!webMetrics.isEmpty()) {
            sb.append("<section id=\"sec-website\" class=\"section\">\n");
            sb.append("  <h2>Website Performance</h2>\n");
            sb.append(websiteCharts(webMetrics));
            sb.append("</section>\n");
        }

        // ── DNS section ───────────────────────────────────────────────────────
        if (!dnsResults.isEmpty()) {
            sb.append("<section id=\"sec-dns\" class=\"section\">\n");
            sb.append("  <h2>DNS Monitoring</h2>\n");
            sb.append(dnsChart(dnsResults));
            sb.append(dnsTimeline(dnsResults));
            sb.append("</section>\n");
        }

        // ── Timeline section ──────────────────────────────────────────────────
        if (!sections.isEmpty()) {
            sb.append("<section id=\"sec-timeline\" class=\"section\">\n");
            sb.append("  <h2>Event Timeline</h2>\n");
            sb.append(timelineChart(ytMetrics, webMetrics, dnsResults));
            sb.append("</section>\n");
        }

        sb.append(scriptInit(ytMetrics, webMetrics, dnsResults, webSpikeAnalysis, dnsSpikeAnalysis));
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    // ── <head> ────────────────────────────────────────────────────────────────

    /**
     * Returns the HTML {@code <head>} block containing the charset meta tag,
     * Chart.js CDN script reference, JetBrains Mono font import, and all
     * embedded CSS including custom-property theme tokens and component styles.
     *
     * @param now human-readable timestamp shown in the browser {@code <title>}
     * @return the full {@code <head>} element as a string
     */
    private String htmlHead(String now) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Net-Perf Console — """ + now + """
            </title>
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
              <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js"></script>
              <style>
                :root {
                  --pass: #00ff87; --fail: #ff2d55; --warn: #ffb300;
                  --accent: #00e5ff; --bg: #070c09; --surface: #0d1911;
                  --surface2: #162b1d; --text: #caf0d4; --muted: #4e7a5e;
                  --border: #1b3323; --radius: 2px;
                  --glow-g: 0 0 10px rgba(0,255,135,.3);
                  --glow-r: 0 0 10px rgba(255,45,85,.3);
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: 'JetBrains Mono', 'Courier New', monospace;
                  background: var(--bg); color: var(--text); line-height: 1.55;
                  background-image:
                    repeating-linear-gradient(0deg, transparent, transparent 2px,
                      rgba(0,0,0,.04) 2px, rgba(0,0,0,.04) 4px);
                }
                a { color: var(--accent); text-decoration: none; }
                a:hover { text-decoration: underline; }
                h1 {
                  font-size: 1.05rem; font-weight: 700; letter-spacing: .07em;
                  text-transform: uppercase; color: var(--pass); text-shadow: var(--glow-g);
                }
                h2 {
                  font-size: .75rem; font-weight: 700; color: var(--accent);
                  text-transform: uppercase; letter-spacing: .16em; margin-bottom: 1rem;
                  padding-left: .65rem; border-left: 2px solid var(--accent);
                }
                h3 {
                  font-size: .7rem; font-weight: 700; margin-bottom: .5rem;
                  color: var(--muted); text-transform: uppercase; letter-spacing: .1em;
                }
                .header {
                  background: var(--surface); padding: .9rem 1.75rem;
                  border-bottom: 1px solid var(--border); position: relative; overflow: hidden;
                }
                .header::before {
                  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 1px;
                  background: linear-gradient(90deg, transparent 0%, var(--pass) 30%, var(--accent) 70%, transparent 100%);
                }
                .header-row { display: flex; align-items: center; gap: 2rem; flex-wrap: wrap; }
                .badge {
                  display: inline-flex; align-items: center; gap: .3rem;
                  padding: .18rem .6rem; border-radius: var(--radius);
                  font-size: .67rem; font-weight: 700;
                  text-transform: uppercase; letter-spacing: .08em; border: 1px solid;
                }
                .badge.pass { background: rgba(0,255,135,.08); color: var(--pass);
                               border-color: rgba(0,255,135,.6); box-shadow: var(--glow-g); }
                .badge.fail { background: rgba(255,45,85,.08); color: var(--fail);
                               border-color: rgba(255,45,85,.6); box-shadow: var(--glow-r); }
                .badge.skip { background: transparent; color: var(--muted); border-color: var(--border); }
                .badge.warn { background: rgba(255,179,0,.08); color: var(--warn);
                               border-color: rgba(255,179,0,.6); }
                .sub { font-size: .65rem; color: var(--muted); margin-top: .2rem; letter-spacing: .05em; }
                .nav {
                  display: flex; gap: 0; padding: 0 1.75rem; background: var(--bg);
                  border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 100;
                  flex-wrap: wrap;
                }
                .nav button {
                  background: none; border: none; border-bottom: 2px solid transparent;
                  color: var(--muted); padding: .58rem 1rem; cursor: pointer;
                  font-size: .7rem; font-family: 'JetBrains Mono', 'Courier New', monospace;
                  font-weight: 700; text-transform: uppercase; letter-spacing: .1em;
                  transition: color .15s, border-color .15s;
                }
                .nav button:hover { color: var(--text); }
                .nav button.active { color: var(--accent); border-bottom-color: var(--accent); }
                .section { padding: 1.75rem; display: none; }
                .section.visible { display: block; }
                .card {
                  background: var(--surface); border: 1px solid var(--border);
                  border-radius: var(--radius); padding: 1.1rem;
                  margin-bottom: 1rem; position: relative;
                }
                .card::before {
                  content: ''; position: absolute; top: 0; left: 0;
                  width: 3px; height: 100%; background: var(--tab-accent, var(--border));
                  border-radius: var(--radius) 0 0 var(--radius); transition: background .2s, box-shadow .2s;
                }
                .card:hover::before { background: var(--tab-accent, var(--accent)); box-shadow: 0 0 8px var(--tab-accent, var(--accent)); }
                .card-title {
                  font-size: .73rem; font-weight: 700; margin-bottom: .85rem;
                  display: flex; align-items: center; gap: .5rem;
                  text-transform: uppercase; letter-spacing: .07em;
                }
                .chart-wrap { position: relative; height: 240px; }
                .chart-wrap-tall { position: relative; height: 320px; }
                .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .9rem; }
                .grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: .9rem; }
                @media (max-width: 900px) {
                  .grid2, .grid3 { grid-template-columns: 1fr; } }
                table { width: 100%; border-collapse: collapse; font-size: .73rem; }
                th {
                  text-align: left; color: var(--accent); font-size: .65rem;
                  text-transform: uppercase; letter-spacing: .12em;
                  padding: .38rem .6rem; border-bottom: 1px solid var(--border);
                  background: rgba(0,229,255,.03);
                }
                td { padding: .35rem .6rem; border-bottom: 1px solid var(--border); }
                tr:last-child td { border-bottom: none; }
                tr:hover td { background: rgba(255,255,255,.02); }
                .pass-cell { color: var(--pass); font-weight: 700; }
                .fail-cell { color: var(--fail); font-weight: 700; }
                .num { text-align: right; font-variant-numeric: tabular-nums; }
                .tag {
                  display: inline-block; padding: .07rem .4rem; border-radius: var(--radius);
                  font-size: .64rem; font-weight: 700;
                  text-transform: uppercase; letter-spacing: .07em; border: 1px solid;
                }
                .tag.pass { background: rgba(0,255,135,.07); color: var(--pass);
                             border-color: rgba(0,255,135,.4); }
                .tag.fail { background: rgba(255,45,85,.07); color: var(--fail);
                             border-color: rgba(255,45,85,.4); }
                .metric-row {
                  display: flex; gap: 0; flex-wrap: wrap; margin-bottom: .85rem;
                  border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden;
                }
                .metric {
                  flex: 1; min-width: 80px; text-align: center;
                  padding: .6rem .4rem; border-right: 1px solid var(--border); background: var(--bg);
                }
                .metric:last-child { border-right: none; }
                .metric .val { font-size: 1.05rem; font-weight: 700; }
                .metric .lbl {
                  font-size: .57rem; color: var(--muted);
                  text-transform: uppercase; letter-spacing: .1em; margin-top: .15rem;
                }
                .timeline-wrap { overflow-x: auto; }
                .tl-header { display: flex; border-bottom: 1px solid var(--border);
                              padding-bottom: .5rem; margin-bottom: .75rem;
                              font-size: .68rem; color: var(--muted); }
                .tl-lane { display: flex; align-items: center; min-height: 2.2rem;
                            border-bottom: 1px solid var(--border); padding: .12rem 0; }
                .tl-label { width: 140px; min-width: 140px; font-size: .7rem;
                             padding-right: .75rem; color: var(--muted); }
                .tl-bar-wrap { flex: 1; position: relative; height: 1.6rem; }
                .tl-event {
                  position: absolute; height: 100%; border-radius: 1px;
                  display: flex; align-items: center; justify-content: center;
                  font-size: .62rem; font-weight: 700; overflow: hidden;
                  white-space: nowrap; cursor: default;
                }
                .dns-pass { background: rgba(0,255,135,.14); color: var(--pass);
                             border: 1px solid rgba(0,255,135,.3); }
                .dns-fail { background: rgba(255,45,85,.14); color: var(--fail);
                             border: 1px solid rgba(255,45,85,.3); }
                .yt-event  { background: rgba(0,229,255,.1); color: var(--accent); }
                .web-event { background: rgba(0,255,135,.07); color: #7ef5a8; }
                .sfn-card {
                  background: var(--bg); border: 1px solid var(--border);
                  border-radius: var(--radius); padding: .65rem .9rem;
                  margin-top: .75rem; font-size: .73rem;
                }
                .sfn-grid {
                  display: grid; grid-template-columns: repeat(auto-fill, minmax(130px,1fr));
                  gap: .4rem; margin-top: .45rem;
                }
                .sfn-item {
                  background: var(--surface); border: 1px solid var(--border);
                  border-radius: var(--radius); padding: .35rem .55rem;
                }
                .sfn-item .val { font-weight: 700; color: var(--accent); }
                .sfn-item .key { font-size: .62rem; color: var(--muted); margin-top: .1rem; }
                .tl-stats-bar { display: flex; gap: .5rem; flex-wrap: wrap;
                                 margin-bottom: .85rem; align-items: center; }
                .tl-stat {
                  display: inline-flex; align-items: center; gap: .3rem;
                  font-size: .68rem; background: var(--bg);
                  padding: .2rem .6rem; border-radius: var(--radius);
                  border: 1px solid var(--border); color: var(--muted);
                }
                .tl-stat .tl-val { font-weight: 700; color: var(--text); }
                .tl-stat.yt-stat  { border-color: rgba(0,229,255,.45); color: var(--accent); }
                .tl-stat.web-stat { border-color: rgba(0,255,135,.45); color: var(--pass); }
                .tl-stat.dns-stat { border-color: rgba(255,179,0,.45); color: var(--warn); }
                .tl-panel-label {
                  font-size: .7rem; font-weight: 700; text-transform: uppercase;
                  letter-spacing: .12em; color: var(--muted); border-left: 2px solid var(--muted);
                  margin: 1rem 0 .4rem; padding-left: .45rem;
                }
                /* ── Executive summary ─────────────────────────────────────── */
                .exec-summary {
                  background: var(--surface); border-bottom: 1px solid var(--border);
                  padding: 1rem 1.75rem;
                }
                .exec-inner   { display: flex; gap: 1rem; align-items: stretch;
                                 flex-wrap: wrap; }
                .exec-verdict {
                  display: flex; flex-direction: column; justify-content: center;
                  align-items: center; gap: .25rem;
                  min-width: 116px; padding: .85rem 1rem;
                  border-radius: var(--radius); flex-shrink: 0; border: 1px solid;
                }
                .exec-verdict .ev-icon { font-size: 1.5rem; line-height: 1; }
                .exec-verdict .ev-word { font-size: .6rem; font-weight: 800;
                                          text-transform: uppercase; letter-spacing: .15em; }
                .exec-verdict .ev-divider { width: 100%; border: none;
                                             border-top: 1px solid rgba(255,255,255,.1);
                                             margin: .25rem 0; }
                .exec-verdict .ev-pct { font-size: 1.4rem; font-weight: 800; line-height: 1.1; }
                .exec-verdict .ev-bar { width: 68px; height: 3px;
                                         background: rgba(255,255,255,.12);
                                         border-radius: 0; overflow: hidden; }
                .exec-verdict .ev-bar-fill { height: 100%; border-radius: 0; }
                .exec-verdict .ev-score-lbl { font-size: .58rem; opacity: .75;
                                               text-transform: uppercase; letter-spacing: .12em; }
                .exec-verdict.healthy  { background: rgba(0,255,135,.06); border-color: rgba(0,255,135,.7);
                                          color: var(--pass); box-shadow: var(--glow-g); }
                .exec-verdict.warning  { background: rgba(255,179,0,.06); border-color: rgba(255,179,0,.7);
                                          color: var(--warn); }
                .exec-verdict.critical { background: rgba(255,45,85,.06); border-color: rgba(255,45,85,.7);
                                          color: var(--fail); box-shadow: var(--glow-r); }
                .exec-cats { display: flex; gap: .75rem; flex: 1; flex-wrap: wrap; }
                .exec-cat  { flex: 1; min-width: 148px; background: var(--bg);
                              border: 1px solid var(--border); border-radius: var(--radius);
                              padding: .72rem .88rem; }
                .exec-cat-head { display: flex; align-items: center; gap: .4rem;
                                  margin-bottom: .45rem; }
                .exec-cat-head .ec-title { font-size: .68rem; font-weight: 800;
                                            text-transform: uppercase; letter-spacing: .12em; }
                .exec-kv  { display: flex; justify-content: space-between; align-items: baseline;
                             font-size: .7rem; margin-bottom: .18rem; }
                .exec-kv .ek-key { color: var(--muted); }
                .exec-kv .ek-val { font-weight: 700; font-variant-numeric: tabular-nums; }
                .exec-kv .ek-val.ev-pass { color: var(--pass); }
                .exec-kv .ek-val.ev-fail { color: var(--fail); }
                .exec-kv .ek-val.ev-warn { color: var(--warn); }
                .ec-divider { border: none; border-top: 1px solid var(--border); margin: .38rem 0; }
                .exec-score { display: flex; align-items: center; gap: .5rem;
                               margin-top: .35rem; }
                .exec-score .es-bar { flex: 1; height: 3px;
                                       background: var(--border); border-radius: 0;
                                       overflow: hidden; }
                .exec-score .es-fill { height: 100%; border-radius: 0;
                                        transition: width .4s; }
                /* ── Spike analysis note inside DNS exec-cat card ─────────── */
                .spike-note {
                  margin-top: .45rem; padding: .35rem .5rem;
                  background: rgba(255,255,255,.04);
                  border: 1px solid rgba(255,255,255,.09);
                  border-radius: var(--radius);
                  font-size: .62rem; line-height: 1.55; color: var(--muted);
                }
                .spike-note .sn-label { font-weight: 700; color: var(--text); }
                .spike-note .sn-text  { color: var(--muted); }
              </style>
            </head>
            """;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    /**
     * Builds the sticky page-header div containing the report title, generation
     * timestamp, and per-probe PASS/FAIL badge row.
     *
     * @param now     human-readable generation timestamp
     * @param ytFail  count of YouTube tabs whose quality checks failed
     * @param webFail count of website cycles that failed PASS criteria
     * @param dnsFail count of DNS queries that returned an error or timed out
     * @param dnsSpikeAnalysis result of the smart DNS spike pattern analysis
     * @param webSpikeAnalysis result of the smart Website spike pattern analysis
     * @param yt      YouTube metrics list (empty → YouTube badge omitted)
     * @param web     Website metrics list (empty → Website badge omitted)
     * @param dns     DNS results list   (empty → DNS badge omitted)
     * @return HTML {@code <div class="header">} block as a string
     */
    private String header(String now, long ytFail, long webFail, long dnsFail,
                          SpikeAnalyzer.Result dnsSpikeAnalysis,
                          SpikeAnalyzer.Result webSpikeAnalysis,
                          List<VideoMetrics> yt, List<WebsiteMetrics> web, List<DnsResult> dns) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"header\">\n");
        sb.append("  <div class=\"header-row\">\n");
        sb.append("    <div>\n");
        sb.append("      <h1>&#128200; Network Performance Report</h1>\n");
        sb.append("      <div class=\"sub\">Generated ").append(now).append("</div>\n");
        sb.append("    </div>\n");
        sb.append("    <div style=\"display:flex;gap:.5rem;flex-wrap:wrap;\">\n");

        // YouTube badge
        if (!yt.isEmpty()) {
            if (ytFail == 0) sb.append(badge("pass", "&#10003; YouTube PASS"));
            else             sb.append(badge("fail", "&#10007; YouTube " + ytFail + " FAIL"));
        } else {
            sb.append(badge("skip", "YouTube skipped"));
        }
        // Website badge — red FAIL for structural spike patterns or insufficient
        // cycle coverage; amber WARN for slow pages or high-rate scattered spikes;
        // green PASS for clean/INFO.
        if (!web.isEmpty()) {
            long headerWebCycles = web.stream().map(WebsiteMetrics::getRefreshCycle).distinct().count();
            long headerExpected  = durationSeconds / Math.max(1, cycleIntervalMs / 1000);
            long headerMinReq   = Math.max(2, Math.round(headerExpected * minCyclesRatio));
            boolean headerInsufficient = headerWebCycles < headerMinReq;

            if (headerInsufficient) {
                sb.append(badge("fail", "&#10007; Website insufficient data"));
            } else if (webSpikeAnalysis.isSignificantFailure()) {
                sb.append(badge("fail", "&#10007; Website spike burst"));
            } else if (webFail > 0 || webSpikeAnalysis.isWarn()) {
                String label = webFail > 0
                    ? "&#9888; Website " + webFail + " slow"
                    : "&#9888; Website spikes";
                sb.append(badge("warn", label));
            } else {
                sb.append(badge("pass", "&#10003; Website PASS"));
            }
        } else {
            sb.append(badge("skip", "Website skipped"));
        }
        // DNS badge — red FAIL only for ≥ 2 % query failure rate or a spike pattern
        // failure (cluster / periodic); isolated random failures show as orange WARN.
        if (!dns.isEmpty()) {
            long   dnsTotal       = dns.size();
            double dnsFailRatePct = dnsTotal > 0 ? 100.0 * dnsFail / dnsTotal : 0.0;
            boolean hardFail      = dnsSpikeAnalysis.isSignificantFailure() || dnsFailRatePct >= 2.0;
            if (dnsFail == 0 && !dnsSpikeAnalysis.isSignificantFailure() && !dnsSpikeAnalysis.isWarn()) {
                sb.append(badge("pass", "&#10003; DNS PASS"));
            } else if (hardFail) {
                String label = dnsSpikeAnalysis.isSignificantFailure() && dnsFail == 0
                    ? "&#10007; DNS spike burst"
                    : "&#10007; DNS " + dnsFail + " fail";
                sb.append(badge("fail", label));
            } else {
                // Minor failures < 2 % or scattered spikes — informational warning only.
                String label = dnsFail > 0
                    ? "&#9888; DNS " + dnsFail + " weak"
                    : "&#9888; DNS spikes";
                sb.append(badge("warn", label));
            }
        } else {
            sb.append(badge("skip", "DNS skipped"));
        }

        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    /**
     * Renders a small coloured badge span used in the page header.
     *
     * @param cls   CSS class controlling badge colour (e.g. {@code "pass"}, {@code "fail"}, {@code "skip"})
     * @param label text displayed inside the badge
     * @return an HTML {@code <span>} element as a string
     */
    private String badge(String cls, String label) {
        return "<span class=\"badge " + cls + "\">" + label + "</span>\n";
    }

    // ── Executive Summary ─────────────────────────────────────────────────────

    /**
     * Builds the executive-summary panel displayed below the page header.
     * Shows an overall PASS/FAIL verdict with a score percentage, plus
     * per-probe category cards (YouTube, Website, DNS) with key aggregated metrics.
     *
     * @param yt       YouTube metrics list
     * @param web      Website metrics list
     * @param dns      DNS results list
     * @param verdicts pre-evaluated per-tab PASS/FAIL verdicts for YouTube
     * @param dnsSpikeAnalysis pre-computed smart DNS spike pattern analysis
     * @param webSpikeAnalysis pre-computed smart Website spike pattern analysis
     * @return HTML {@code <div class="exec-summary">} block as a string
     */
    private String execSummary(List<VideoMetrics>   yt,
                                List<WebsiteMetrics> web,
                                List<DnsResult>      dns,
                                List<VideoVerdict>   verdicts,
                                SpikeAnalyzer.Result dnsSpikeAnalysis,
                                SpikeAnalyzer.Result webSpikeAnalysis) {

        // ── YouTube aggregates ────────────────────────────────────────────────
        int  ytTabs      = yt.size();
        long ytFailed    = verdicts.stream().filter(v -> !v.isPassed()).count();
        long ytPassed    = ytTabs - ytFailed;
        long ytTotalChecks = verdicts.stream().mapToLong(v -> v.getChecks().size()).sum();
        long ytFailedChecks = verdicts.stream()
            .flatMap(v -> v.getChecks().stream()).filter(c -> !c.isPassed()).count();
        double ytAvgTtfb  = yt.stream().mapToLong(VideoMetrics::getTimeToFirstByte)
                               .filter(v -> v > 0).average().orElse(-1);
        double ytAvgLoad  = yt.stream().mapToLong(VideoMetrics::getPageLoadTime)
                               .filter(v -> v > 0).average().orElse(-1);
        double ytAvgBuf   = yt.stream().mapToDouble(VideoMetrics::getBufferedSeconds)
                               .filter(v -> v >= 0).average().orElse(-1);
        double ytAvgBw    = yt.stream().mapToDouble(VideoMetrics::getAvgBandwidthKBps)
                               .filter(v -> v >= 0).average().orElse(-1);

        // ── Website aggregates ────────────────────────────────────────────────
        long webDomains  = web.stream().map(WebsiteMetrics::getTabIndex).distinct().count();
        long webCycles   = web.stream().map(WebsiteMetrics::getRefreshCycle).distinct().count();
        long webTotalRows = web.size();
        long webSlow     = web.stream().filter(m -> {
            WebsiteThresholds t = thresholdsFor.apply(m.getDomain());
            long limit = m.getRefreshCycle() == 1 ? t.pageLoadColdMs : t.pageLoadWarmMs;
            return m.getPageLoadTime() > limit;
        }).count();
        double webAvgLoad = web.stream().mapToLong(WebsiteMetrics::getPageLoadTime)
                               .filter(v -> v > 0).average().orElse(-1);
        double webAvgTtfb = web.stream().mapToLong(WebsiteMetrics::getTimeToFirstByte)
                               .filter(v -> v > 0).average().orElse(-1);
        // Use pre-computed spike analysis — raw outlier count for display,
        // pattern analysis for pass/fail decisions.
        long webOutliers = webSpikeAnalysis.totalSpikes;

        // ── DNS aggregates ────────────────────────────────────────────────────
        long dnsDomains  = dns.stream().map(DnsResult::getDomain).distinct().count();
        long dnsTotal    = dns.size();
        long dnsSuccess  = dns.stream().filter(DnsResult::isSuccess).count();
        double dnsSuccPct = dnsTotal > 0 ? 100.0 * dnsSuccess / dnsTotal : 0;
        double dnsAvgMs  = dns.stream().filter(DnsResult::isSuccess)
                              .mapToLong(DnsResult::getResponseTimeMs).average().orElse(-1);

        // DNS spike detection: response times above μ+5σ per domain × record type
        // Now delegated to the pre-computed DnsSpikeAnalysis passed in as a parameter.
        long dnsOutliers = dnsSpikeAnalysis.totalSpikes;

        // ── Minimum-cycle coverage check (website) ──────────────────────────
        // Expected cycles = duration / interval.  If actual cycles fall below
        // minCyclesRatio × expected, the probe produced too little data to be
        // trusted — flag as critical failure regardless of per-cycle results.
        long expectedWebCycles = durationSeconds / Math.max(1, cycleIntervalMs / 1000);
        long minRequiredCycles = Math.max(2, Math.round(expectedWebCycles * minCyclesRatio));
        boolean webInsufficientCycles = !web.isEmpty() && webCycles < minRequiredCycles;

        // ── Category-level failure flags ──────────────────────────────────────
        boolean ytCritical  = ytFailed > 0;
        // DNS is critical only when the resolver failure rate ≥ 5 % OR when the
        // spike analysis identifies a structurally significant pattern (cluster burst
        // or periodic repetition).  Isolated random spikes do NOT cause critical.
        boolean dnsCritical = dnsSuccPct < 95 || dnsSpikeAnalysis.isSignificantFailure();
        // Website is critical when structural spike patterns exist OR when the
        // probe completed too few cycles to be reliable.
        boolean webCritical = webSpikeAnalysis.isSignificantFailure() || webInsufficientCycles;
        boolean ytWarn      = ytFailedChecks > 0 && !ytCritical;
        boolean webWarn     = webSlow > 0 || webSpikeAnalysis.isWarn();

        // ── Per-category scores (0-100 each) ──────────────────────────────────
        //
        // YouTube: 70 % tab-pass-rate + 30 % check-pass-rate.
        //   Tab-level failures dominate because a failed tab = failed experience,
        //   regardless of how many individual checks happened to pass inside it.
        double ytTabScore   = (ytTabs > 0)
                ? 100.0 * ytPassed / ytTabs : 100.0;
        double ytCheckScore = (ytTotalChecks > 0)
                ? 100.0 * (ytTotalChecks - ytFailedChecks) / ytTotalChecks : 100.0;
        double ytScore = ytTabScore * 0.70 + ytCheckScore * 0.30;

        // DNS: plain resolution success rate
        double dnsScore = (dnsTotal > 0) ? dnsSuccPct : 100.0;

        // Web: hard-slow pages always count. Spikes only reduce the score when the
        // pattern is WARN or FAIL — isolated INFO spikes are random noise.
        // Insufficient cycles = hard cap at 30 % because the data is unreliable.
        long webSpikeCountForScore = (webSpikeAnalysis.isInfo() || "PASS".equals(webSpikeAnalysis.verdict))
                ? 0 : webSpikeAnalysis.totalSpikes;
        double webEffectiveFails = webSlow + webSpikeCountForScore;
        double webScore;
        if (webInsufficientCycles) {
            webScore = Math.min(30.0, (webTotalRows > 0)
                ? Math.max(0.0, 100.0 * (webTotalRows - webEffectiveFails) / webTotalRows)
                : 0.0);
        } else {
            webScore = (webTotalRows > 0)
                ? Math.max(0.0, 100.0 * (webTotalRows - webEffectiveFails) / webTotalRows)
                : 100.0;
        }

        // ── Weighted blend: YouTube 50 % · DNS 25 % · Web 25 % ───────────────
        //   Weights are normalised so absent categories don't create a phantom
        //   100 % bonus (e.g. no web data → YouTube 67 % + DNS 33 %).
        double ytWeight  = (ytTabs      > 0) ? 0.50 : 0.0;
        double dnsWeight = (dnsTotal    > 0) ? 0.25 : 0.0;
        double webWeight = (webTotalRows > 0) ? 0.25 : 0.0;
        double totalWeight = ytWeight + dnsWeight + webWeight;

        int scorePct = (totalWeight > 0)
                ? (int) Math.round(
                        (ytScore * ytWeight + dnsScore * dnsWeight + webScore * webWeight)
                        / totalWeight)
                : 100;
        String scoreColor = scorePct >= 85 ? "#00ff87" : scorePct >= 65 ? "#ffb300" : "#ff2d55";

        // ── Overall health verdict ─────────────────────────────────────────────
        // CRITICAL — score < 60 % (severe multi-category or total failure)
        // WARNING  — score < 85 % OR any individual category has issues
        // HEALTHY  — score >= 85 % and no category flags
        String verdict;
        String verdictIcon;
        if (scorePct < 60) {
            verdict = "CRITICAL"; verdictIcon = "&#128308;";       // red circle
        } else if (scorePct < 85 || ytCritical || dnsCritical || webCritical || ytWarn) {
            verdict = "WARNING";  verdictIcon = "&#128992;";       // orange circle
        } else {
            verdict = "HEALTHY";  verdictIcon = "&#128994;";       // green circle
        }
        String verdictCls = switch (verdict) {
            case "CRITICAL" -> "critical";
            case "WARNING"  -> "warning";
            default         -> "healthy";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"exec-summary\">\n");
        sb.append("  <div class=\"exec-inner\">\n");

        // Overall verdict tile
        sb.append("    <div class=\"exec-verdict ").append(verdictCls).append("\">\n");
        sb.append("      <div class=\"ev-icon\">").append(verdictIcon).append("</div>\n");
        sb.append("      <div class=\"ev-word\">").append(verdict).append("</div>\n");
        sb.append("      <hr class=\"ev-divider\">\n");
        sb.append("      <div class=\"ev-pct\" style=\"color:").append(scoreColor).append(";\">")
          .append(scorePct).append("%</div>\n");
        sb.append("      <div class=\"ev-bar\"><div class=\"ev-bar-fill\" style=\"width:")
          .append(scorePct).append("%;background:").append(scoreColor)
          .append(";\"></div></div>\n");
        sb.append("      <div class=\"ev-score-lbl\">Overall Score</div>\n");
        sb.append("    </div>\n");

        sb.append("    <div class=\"exec-cats\">\n");

        // ── YouTube card ─────────────────────────────────────────────────────
        if (!yt.isEmpty()) {
            String ytColor = ytFailed == 0 ? "var(--pass)" : "var(--fail)";
            sb.append("      <div class=\"exec-cat\">\n");
            sb.append("        <div class=\"exec-cat-head\">\n");
            sb.append("          <span style=\"color:").append(ytColor).append("\">&#9654;</span>\n");
            sb.append("          <span class=\"ec-title\" style=\"color:").append(ytColor).append("\">YouTube</span>\n");
            sb.append("        </div>\n");
            kv(sb, "Tabs", ytPassed + " / " + ytTabs + " pass",
               ytFailed == 0 ? "ev-pass" : "ev-fail");
            kv(sb, "Checks", (ytTotalChecks - ytFailedChecks) + " / " + ytTotalChecks + " pass",
               ytFailedChecks == 0 ? "ev-pass" : "ev-fail");
            sb.append("        <hr class=\"ec-divider\">\n");
            if (ytAvgTtfb > 0)
                kv(sb, "Avg TTFB",    String.format("%.0f ms", ytAvgTtfb), "");
            if (ytAvgLoad > 0)
                kv(sb, "Avg Load",    String.format("%.0f ms", ytAvgLoad), "");
            if (ytAvgBuf  >= 0)
                kv(sb, "Avg Buffer",  String.format("%.1f s",  ytAvgBuf),  ytAvgBuf  >= 10 ? "ev-pass" : "ev-fail");
            if (ytAvgBw   >= 0)
                kv(sb, "Avg BW",      String.format("%.0f KB/s", ytAvgBw), ytAvgBw   >= 250 ? "ev-pass" : "ev-fail");
            sb.append("      </div>\n");
        }

        // ── Website card ─────────────────────────────────────────────────────
        if (!web.isEmpty()) {
            String webColor = webCritical ? "var(--fail)"
                : (webWarn || webOutliers > 0) ? "var(--warn)" : "var(--pass)";
            sb.append("      <div class=\"exec-cat\">\n");
            sb.append("        <div class=\"exec-cat-head\">\n");
            sb.append("          <span style=\"color:").append(webColor).append("\">&#127760;</span>\n");
            sb.append("          <span class=\"ec-title\" style=\"color:").append(webColor).append("\">Website</span>\n");
            sb.append("        </div>\n");
            String cyclesLabel = webDomains + " tested, " + webCycles + " cycle" + (webCycles != 1 ? "s" : "");
            String cyclesCls   = webInsufficientCycles ? "ev-fail" : "";
            kv(sb, "Domains", cyclesLabel, cyclesCls);
            if (webInsufficientCycles) {
                kv(sb, "Cycle coverage",
                   webCycles + " of " + expectedWebCycles + " expected (min " + minRequiredCycles + ")",
                   "ev-fail");
            }
            kv(sb, "Slow pages", webSlow > 0 ? webSlow + " above threshold" : "none",
               webSlow == 0 ? "ev-pass" : "ev-warn");
            sb.append("        <hr class=\"ec-divider\">\n");
            // ── Spike analysis rows ──────────────────────────────────────────
            String webSpikeVerdictCls = switch (webSpikeAnalysis.verdict) {
                case "PASS" -> "ev-pass";
                case "WARN" -> "ev-warn";
                case "FAIL" -> "ev-fail";
                default     -> "";
            };
            String webSpikesLabel = webSpikeAnalysis.totalSpikes == 0
                ? "none"
                : webSpikeAnalysis.totalSpikes + " detected ("
                  + String.format("%.2f", webSpikeAnalysis.spikeRatePct) + "%)";
            // Colour the spike-count row by rate (not verdict): low rate stays neutral
            // even when the cluster verdict is FAIL — the cluster row itself shows red.
            String webSpikeCountCls = webSpikeAnalysis.totalSpikes == 0 ? "ev-pass"
                : webSpikeAnalysis.spikeRatePct < 2.0 ? ""
                : webSpikeAnalysis.spikeRatePct < 5.0 ? "ev-warn"
                : "ev-fail";
            kv(sb, "Spikes (\u03bc+5\u03c3)", webSpikesLabel, webSpikeCountCls);
            if (webSpikeAnalysis.totalSpikes > 0) {
                String clusterVal = webSpikeAnalysis.hasCluster
                    ? "YES — " + webSpikeAnalysis.maxSpikeIn60s + " in 60 s window"
                    : "None (" + webSpikeAnalysis.maxSpikeIn60s + " max in 60 s)";
                kv(sb, "Cluster (60 s)", clusterVal,
                   webSpikeAnalysis.hasCluster ? "ev-fail" : "ev-pass");
                if (webSpikeAnalysis.interSpikeCV >= 0) {
                    String patternVal = webSpikeAnalysis.hasPeriodic
                        ? String.format("Periodic (CV=%.2f)", webSpikeAnalysis.interSpikeCV)
                        : String.format("Random (CV=%.2f)", webSpikeAnalysis.interSpikeCV);
                    kv(sb, "Pattern", patternVal,
                       webSpikeAnalysis.hasPeriodic ? "ev-fail" : "ev-pass");
                }
                kv(sb, "Spike verdict", webSpikeAnalysis.verdict, webSpikeVerdictCls);
            }
            sb.append("        <hr class=\"ec-divider\">\n");
            if (webAvgLoad > 0)
                kv(sb, "Avg Load", String.format("%.0f ms", webAvgLoad),
                   webAvgLoad < 2000 ? "ev-pass" : webAvgLoad < 5000 ? "ev-warn" : "ev-fail");
            if (webAvgTtfb > 0)
                kv(sb, "Avg TTFB", String.format("%.0f ms", webAvgTtfb),
                   webAvgTtfb < 500 ? "ev-pass" : "ev-warn");
            sb.append("      </div>\n");
        }

        // ── DNS card ─────────────────────────────────────────────────────────
        if (!dns.isEmpty()) {
            // Card title colour reflects true severity: only a spike FAIL or high
            // failure rate produces red; isolated spikes stay green/amber.
            String dnsColor = dnsCritical ? "var(--fail)"
                : (dnsSuccPct < 99 || dnsSpikeAnalysis.isWarn()) ? "var(--warn)" : "var(--pass)";
            sb.append("      <div class=\"exec-cat\">\n");
            sb.append("        <div class=\"exec-cat-head\">\n");
            sb.append("          <span style=\"color:").append(dnsColor).append("\">&#128270;</span>\n");
            sb.append("          <span class=\"ec-title\" style=\"color:").append(dnsColor).append("\">DNS</span>\n");
            sb.append("        </div>\n");
            kv(sb, "Domains", dnsDomains + " monitored", "");
            kv(sb, "Queries",  dnsSuccess + " / " + dnsTotal + " ok",
               dnsSuccPct == 100 ? "ev-pass" : dnsSuccPct >= 95 ? "ev-warn" : "ev-fail");
            kv(sb, "Success",  String.format("%.1f %%", dnsSuccPct),
               dnsSuccPct == 100 ? "ev-pass" : dnsSuccPct >= 95 ? "ev-warn" : "ev-fail");
            sb.append("        <hr class=\"ec-divider\">\n");
            if (dnsAvgMs > 0)
                kv(sb, "Avg RT", String.format("%.0f ms", dnsAvgMs),
                   dnsAvgMs < 100 ? "ev-pass" : dnsAvgMs < 200 ? "ev-warn" : "ev-fail");
            // ── Spike analysis rows ──────────────────────────────────────────
            // Determine CSS class for the spike verdict badge value.
            String spikeVerdictCls = switch (dnsSpikeAnalysis.verdict) {
                case "PASS" -> "ev-pass";
                case "WARN" -> "ev-warn";
                case "FAIL" -> "ev-fail";
                default     -> "";
            };
            String spikesLabel = dnsSpikeAnalysis.totalSpikes == 0
                ? "none"
                : dnsSpikeAnalysis.totalSpikes + " detected ("
                  + String.format("%.2f", dnsSpikeAnalysis.spikeRatePct) + "%)";
            // Colour the spike-count row by rate, not verdict.
            // Even if a cluster causes FAIL, 0.6% spike rate should not show red here —
            // the Cluster (60 s) row already shows red when hasCluster is true.
            String spikeCountCls = dnsSpikeAnalysis.totalSpikes == 0 ? "ev-pass"
                : dnsSpikeAnalysis.spikeRatePct < 2.0 ? ""
                : dnsSpikeAnalysis.spikeRatePct < 5.0 ? "ev-warn"
                : "ev-fail";
            kv(sb, "Spikes (\u03bc+5\u03c3)", spikesLabel, spikeCountCls);
            kv(sb, "FAIL gate", SpikeAnalyzer.DNS_HARD_THRESHOLD_MS + " ms (spike must also exceed)", "");
            if (dnsSpikeAnalysis.totalSpikes > 0) {
                // Cluster check
                String clusterVal = dnsSpikeAnalysis.hasCluster
                    ? "YES — " + dnsSpikeAnalysis.maxSpikeIn60s + " in 60 s window"
                    : "None (" + dnsSpikeAnalysis.maxSpikeIn60s + " max in 60 s)";
                kv(sb, "Cluster (60 s)", clusterVal,
                   dnsSpikeAnalysis.hasCluster ? "ev-fail" : "ev-pass");
                // Pattern check (only meaningful when ≥ 3 spikes exist)
                if (dnsSpikeAnalysis.interSpikeCV >= 0) {
                    String patternVal = dnsSpikeAnalysis.hasPeriodic
                        ? String.format("Periodic (CV=%.2f)", dnsSpikeAnalysis.interSpikeCV)
                        : String.format("Random (CV=%.2f)", dnsSpikeAnalysis.interSpikeCV);
                    kv(sb, "Pattern", patternVal,
                       dnsSpikeAnalysis.hasPeriodic ? "ev-fail" : "ev-pass");
                }
                // Spike verdict with reason
                kv(sb, "Spike verdict", dnsSpikeAnalysis.verdict, spikeVerdictCls);
            }
            sb.append("      </div>\n");
        }

        sb.append("    </div>\n"); // exec-cats
        sb.append("  </div>\n");  // exec-inner
        sb.append("</div>\n");
        return sb.toString();
    }

    /** Appends a key–value row inside an exec-cat card. */
    private void kv(StringBuilder sb, String key, String value, String valCls) {
        sb.append("        <div class=\"exec-kv\"><span class=\"ek-key\">")
          .append(escHtml(key)).append("</span>")
          .append("<span class=\"ek-val")
          .append(valCls.isEmpty() ? "" : " " + valCls).append("\">")
          .append(escHtml(value)).append("</span></div>\n");
    }

    /** Appends a percentage score bar row inside an exec-cat card. */
    private void scoreBar(StringBuilder sb, int pct, String color) {
        sb.append("        <div class=\"exec-score\">\n")
          .append("          <span class=\"ek-key\" style=\"font-size:.72rem\">Score</span>\n")
          .append("          <div class=\"es-bar\">")
          .append("<div class=\"es-fill\" style=\"width:").append(pct)
          .append("%;background:").append(color).append(";\"></div></div>\n")
          .append("          <span class=\"ek-val\" style=\"font-size:.78rem;color:")
          .append(color).append(";\">").append(pct).append("%</span>\n")
          .append("        </div>\n");
    }



    /**
     * Builds the sticky navigation tab bar that lets users switch between
     * the YouTube, Website, DNS, and Timeline report sections without reloading.
     *
     * @param sections ordered list of section IDs to show (e.g. {@code ["youtube", "dns"]})
     * @return HTML {@code <nav class="nav">} element as a string
     */
    private String navTabs(List<String> sections) {
        StringBuilder sb = new StringBuilder("<nav class=\"nav\" id=\"main-nav\">\n");
        for (String s : sections) {
            String label = switch (s) {
                case "youtube"  -> "&#9654; YouTube";
                case "website"  -> "&#127760; Website";
                case "dns"      -> "&#128270; DNS";
                case "timeline" -> "&#128336; Timeline";
                default -> s;
            };
            sb.append("  <button onclick=\"showSection('sec-").append(s)
              .append("',this)\">").append(label).append("</button>\n");
        }
        sb.append("</nav>\n");
        return sb.toString();
    }

    // ── YouTube section ───────────────────────────────────────────────────────

    private String ytOverviewChart(List<VideoMetrics> metrics) {
        // Inline chart data — the actual Chart.js call is in scriptInit
        return "<div class=\"card\">\n" +
               "  <div class=\"card-title\">Connection Latency Overview (all tabs)</div>\n" +
               "  <div class=\"chart-wrap\"><canvas id=\"chart-yt-overview\"></canvas></div>\n" +
               "</div>\n";
    }

    /**
     * Builds a detailed card for one YouTube tab, containing the PASS/FAIL badge,
     * a metric-strip (TTFB, page-load, buffer, bandwidth, resolution), a Chart.js
     * bandwidth/buffer timeline, a quality-check results table, and a Stats for
     * Nerds data card (when panel data was captured).
     *
     * @param m the collected metrics for this tab
     * @param v the pre-evaluated PASS/FAIL verdict ({@code null} is treated as PASS)
     * @return HTML {@code <div class="card">} block as a string
     */
    /** Distinct accent colors per tab index (wraps for > 4 tabs). */
    private static final String[] YT_TAB_ACCENT = {"#00e5ff", "#ff9e40", "#b388ff", "#ff6eb4"};

    private String ytTabCard(VideoMetrics m, VideoVerdict v) {
        StringBuilder sb = new StringBuilder();
        String title = m.getPageTitle() != null ? m.getPageTitle()
                           .replace("- YouTube", "").trim() : "Tab " + m.getTabIndex();
        if (title.length() > 70) title = title.substring(0, 67) + "…";

        boolean pass = v == null || v.isPassed();
        String tabAccent = YT_TAB_ACCENT[(m.getTabIndex() - 1) % YT_TAB_ACCENT.length];

        sb.append("<div class=\"card\" style=\"--tab-accent:").append(tabAccent).append("\">\n");
        sb.append("  <div class=\"card-title\">\n");
        sb.append("    <span class=\"tag ").append(pass ? "pass" : "fail").append("\">")
          .append(pass ? "PASS" : "FAIL").append("</span>\n");
        sb.append("    <span style=\"color:").append(tabAccent)
          .append(";font-size:.8rem;font-weight:800\">TAB").append(m.getTabIndex()).append("</span>\n");
        sb.append("    ").append(escHtml(title)).append("\n");
        sb.append("  </div>\n");

        // Metrics strip
        sb.append("  <div class=\"metric-row\">\n");
        sb.append(metricTile("TTFB", m.getTimeToFirstByte() + " ms"));
        sb.append(metricTile("Page Load", m.getPageLoadTime() + " ms"));
        sb.append(metricTile("Buffer", String.format("%.1f s", m.getBufferedSeconds())));
        if (m.getAvgBandwidthKBps() > 0)
            sb.append(metricTile("Avg BW", String.format("%.0f KB/s", m.getAvgBandwidthKBps())));
        else
            sb.append(metricTile("Avg BW", "pre-buf"));
        sb.append(metricTile("Resolution", m.getVideoWidth() + "×" + m.getVideoHeight()));
        sb.append("  </div>\n");

        // Connection Speed chart + Buffer chart (separate, stacked) alongside quality checks
        sb.append("  <div class=\"grid2\">\n");
        sb.append("    <div>\n");
        sb.append("      <h3>Connection Speed (Mbps) over time</h3>\n");
        sb.append("      <div class=\"chart-wrap\"><canvas id=\"chart-yt-bw-")
          .append(m.getTabIndex()).append("\"></canvas></div>\n");
        sb.append("      <h3 style=\"margin-top:1rem\">SFN Buffer Health (s) over time</h3>\n");
        sb.append("      <div class=\"chart-wrap\"><canvas id=\"chart-yt-buf-")
          .append(m.getTabIndex()).append("\"></canvas></div>\n");
        sb.append("      <h3 style=\"margin-top:1rem\">Frames Dropped over time</h3>\n");
        sb.append("      <div class=\"chart-wrap\"><canvas id=\"chart-yt-frames-")
          .append(m.getTabIndex()).append("\"></canvas></div>\n");
        sb.append("    </div>\n");

        // Quality checks table
        sb.append("    <div>\n");
        sb.append("      <h3>Quality Checks</h3>\n");
        if (v != null) {
            sb.append("      <table>\n");
            sb.append("        <thead><tr><th>Check</th><th>Actual</th><th>Status</th></tr></thead>\n");
            sb.append("        <tbody>\n");
            for (CheckResult cr : v.getChecks()) {
                String status = cr.isPassed()
                    ? "<span class=\"tag pass\">PASS</span>"
                    : "<span class=\"tag fail\">FAIL</span>";
                sb.append("          <tr><td>").append(escHtml(cr.getCheckName()))
                  .append("</td><td class=\"num\">").append(escHtml(cr.getActual()))
                  .append("</td><td>").append(status).append("</td></tr>\n");
            }
            sb.append("        </tbody>\n");
            sb.append("      </table>\n");
        }
        sb.append("    </div>\n");
        sb.append("  </div>\n");

        // SFN data
        StatsForNerdsData sfn = m.getSfnData();
        if (sfn != null && sfn.isAvailable()) {
            sb.append(sfnCard(sfn));
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    /**
     * Renders a single metric tile (label/value pair) within the metric-strip row.
     *
     * @param label short description (e.g. {@code "TTFB"})
     * @param value formatted value string (e.g. {@code "145 ms"})
     * @return HTML {@code <div class="metric">} element
     */
    private String metricTile(String label, String value) {
        return "    <div class=\"metric\"><div class=\"val\">" + escHtml(value) +
               "</div><div class=\"lbl\">" + escHtml(label) + "</div></div>\n";
    }

    /**
     * Builds the Stats for Nerds collapsible card displayed inside a YouTube tab card.
     *
     * @param sfn the scraped Stats for Nerds data for one tab
     * @return HTML {@code <div class="sfn-card">} block
     */
    private String sfnCard(StatsForNerdsData sfn) {
        StringBuilder sb = new StringBuilder("<div class=\"sfn-card\">\n");
        sb.append("  <strong>Stats for Nerds</strong>\n");
        sb.append("  <div class=\"sfn-grid\">\n");
        if (sfn.getConnectionSpeedKbps() >= 0)
            sb.append(sfnItem("Connection Speed", String.format("%.0f Kbps", sfn.getConnectionSpeedKbps())));
        if (sfn.getBufferHealthSecs() >= 0)
            sb.append(sfnItem("Buffer Health", String.format("%.2f s", sfn.getBufferHealthSecs())));
        if (sfn.getCurrentResolution() != null)
            sb.append(sfnItem("Current Res", sfn.getCurrentResolution()));
        if (sfn.getOptimalResolution() != null)
            sb.append(sfnItem("Optimal Res", sfn.getOptimalResolution()));
        if (sfn.getVideoCodec() != null)
            sb.append(sfnItem("Video Codec", sfn.getVideoCodec()));
        if (sfn.getAudioCodec() != null)
            sb.append(sfnItem("Audio Codec", sfn.getAudioCodec()));
        if (sfn.getTotalFrames() >= 0) {
            long dropped = sfn.getDroppedFrames();
            long total   = sfn.getTotalFrames();
            String pct   = total > 0 ? String.format(" (%.2f%%)", 100.0 * dropped / total) : "";
            sb.append(sfnItem("Frames Dropped", dropped + " dropped of " + total + pct));
        }
        sb.append("  </div>\n</div>\n");
        return sb.toString();
    }

    /**
     * Renders a single Stats for Nerds key/value item within the SFN grid.
     *
     * @param key  the metric label (e.g. {@code "Buffer Health"})
     * @param val  the formatted metric value (e.g. {@code "30.94 s"})
     * @return HTML {@code <div class="sfn-item">} element
     */
    private String sfnItem(String key, String val) {
        return "    <div class=\"sfn-item\"><div class=\"val\">" + escHtml(val)
             + "</div><div class=\"key\">" + escHtml(key) + "</div></div>\n";
    }

    // ── Website section ───────────────────────────────────────────────────────

    /**
     * Builds the Website section containing a statistical-summary table (average
     * page-load, TTFB, σ, and outlier counts per domain) followed by per-tab
     * page-load and TTFB line charts across all refresh cycles.
     *
     * @param metrics all collected website metrics records
     * @return HTML fragment with summary card and per-tab chart cards
     */
    private String websiteCharts(List<WebsiteMetrics> metrics) {
        // Group by tab
        Map<Integer, List<WebsiteMetrics>> byTab = metrics.stream()
            .collect(Collectors.groupingBy(WebsiteMetrics::getTabIndex));

        StringBuilder sb = new StringBuilder();

        // Summary table — avg, stddev, and outlier count per domain
        sb.append("<div class=\"card\">\n");
        sb.append("  <div class=\"card-title\">Website Summary — Statistical Analysis per Domain</div>\n");
        sb.append("  <table>\n");
        sb.append("    <thead><tr><th>Domain</th><th class=\"num\">Cycles</th>"
                + "<th class=\"num\">Avg Load</th><th class=\"num\">&sigma; Load</th>"
                + "<th class=\"num\">Avg TTFB</th><th class=\"num\">&sigma; TTFB</th>"
                + "<th class=\"num\">Load Outliers</th><th>Status</th></tr></thead>\n");
        sb.append("    <tbody>\n");
        byTab.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            List<WebsiteMetrics> rows = e.getValue();
            String domain = rows.get(0).getDomain();
            List<Double> loadVals = rows.stream().map(r -> (double) r.getPageLoadTime())
                                        .filter(v -> v >= 0).collect(Collectors.toList());
            List<Double> ttfbVals = rows.stream().map(r -> (double) r.getTimeToFirstByte())
                                        .filter(v -> v >= 0).collect(Collectors.toList());
            StatisticsEngine.Stats loadSt    = StatisticsEngine.compute(loadVals);
            StatisticsEngine.Stats ttfbSt    = StatisticsEngine.compute(ttfbVals);
            // Use robust (MAD-based) baseline for outlier detection so that an extreme
            // page-load value cannot inflate σ enough to mask itself from μ+5σ.
            StatisticsEngine.Stats loadRobust = StatisticsEngine.computeRobust(loadVals);
            long outliers = StatisticsEngine.countHighOutliersAtSigma(loadVals, loadRobust, 5.0);
            String sigmaGate = "5&sigma;";
            long failCount = rows.stream().filter(r -> {
                WebsiteThresholds t = thresholdsFor.apply(r.getDomain());
                long limit = r.getRefreshCycle() == 1 ? t.pageLoadColdMs : t.pageLoadWarmMs;
                return r.getPageLoadTime() > limit;
            }).count();
            String status = (outliers == 0 && failCount == 0)
                ? "<span class=\"tag pass\">PASS</span>"
                : "<span class=\"tag fail\">" + outliers + " outlier" + (outliers != 1 ? "s" : "")
                  + " (&gt;" + sigmaGate + ")</span>";
            sb.append("      <tr><td>").append(escHtml(domain))
              .append("</td><td class=\"num\">").append(rows.size())
              .append("</td><td class=\"num\">")
              .append(loadSt != null ? String.format("%.0f ms", loadSt.mean) : "n/a")
              .append("</td><td class=\"num\">")
              .append(loadSt != null ? String.format("%.0f ms", loadSt.stddev) : "n/a")
              .append("</td><td class=\"num\">")
              .append(ttfbSt != null ? String.format("%.0f ms", ttfbSt.mean) : "n/a")
              .append("</td><td class=\"num\">")
              .append(ttfbSt != null ? String.format("%.0f ms", ttfbSt.stddev) : "n/a")
              .append("</td><td class=\"num\">").append(outliers > 0
                  ? "<span class=\"fail-cell\">" + outliers + "</span>" : "0")
              .append("</td><td>").append(status).append("</td></tr>\n");
        });
        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("</div>\n");

        // Per-domain line charts (page load + TTFB)
        sb.append("<div class=\"grid2\">\n");
        byTab.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            List<WebsiteMetrics> rows = e.getValue();
            String domain = rows.get(0).getDomain();
            sb.append("<div class=\"card\">\n");
            sb.append("  <div class=\"card-title\">").append(escHtml(domain)).append("</div>\n");
            sb.append("  <div class=\"chart-wrap\"><canvas id=\"chart-web-")
              .append(e.getKey()).append("\"></canvas></div>\n");
            sb.append("</div>\n");
        });
        sb.append("</div>\n");

        return sb.toString();
    }

    // ── DNS section ───────────────────────────────────────────────────────────

    /**
     * Returns a card placeholder for the DNS average-response-latency bar chart
     * grouped by domain and record type. The Chart.js dataset is injected by
     * {@link #scriptInit}.
     *
     * @param results all DNS query results
     * @return HTML card div with a {@code <canvas id="chart-dns-latency">}
     */
    private String dnsChart(List<DnsResult> results) {
        return "<div class=\"card\">\n" +
               "  <div class=\"card-title\">DNS Response Latency (avg ms per domain × type)</div>\n" +
               "  <div class=\"chart-wrap\"><canvas id=\"chart-dns-latency\"></canvas></div>\n" +
               "</div>\n";
    }

    /**
     * Builds the DNS section detail card: a per-domain summary table (rounds,
     * success rate, avg / min / max latency) and a per-iteration response-time
     * line chart whose data is injected by {@link #scriptInit}.
     *
     * @param results all DNS query results
     * @return HTML fragment with summary table card and iteration-chart card
     */
    private String dnsTimeline(List<DnsResult> results) {
        // Summary table
        Map<String, Map<String, List<DnsResult>>> grouped = results.stream()
            .collect(Collectors.groupingBy(DnsResult::getDomain,
                     LinkedHashMap::new,
                     Collectors.groupingBy(DnsResult::getRecordType,
                     LinkedHashMap::new,
                     Collectors.toList())));

        StringBuilder sb = new StringBuilder("<div class=\"card\">\n");
        sb.append("  <div class=\"card-title\">DNS Results by Domain</div>\n");
        sb.append("  <table>\n");
        sb.append("    <thead><tr><th>Domain</th><th>Type</th><th class=\"num\">Rounds</th>"
                + "<th class=\"num\">Success</th><th class=\"num\">Avg (ms)</th>"
                + "<th class=\"num\">Min (ms)</th><th class=\"num\">Max (ms)</th>"
                + "<th class=\"num\">Spikes (&gt;&mu;+5&sigma;)</th></tr></thead>\n");
        sb.append("    <tbody>\n");

        grouped.forEach((domain, byType) ->
            byType.forEach((type, rows) -> {
                long   success = rows.stream().filter(DnsResult::isSuccess).count();
                double avg     = rows.stream().mapToLong(DnsResult::getResponseTimeMs).average().orElse(0);
                long   min     = rows.stream().mapToLong(DnsResult::getResponseTimeMs).min().orElse(0);
                long   max     = rows.stream().mapToLong(DnsResult::getResponseTimeMs).max().orElse(0);
                List<Double> rtVals = rows.stream().filter(DnsResult::isSuccess)
                    .map(r -> (double) r.getResponseTimeMs()).collect(Collectors.toList());
                StatisticsEngine.Stats rtSt = StatisticsEngine.compute(rtVals);
                long spikes = StatisticsEngine.countHighOutliersAtSigma(rtVals, rtSt, 5.0);
                String rateStr = success + " / " + rows.size()
                    + " (" + (long)(100.0 * success / Math.max(1, rows.size())) + "%)";
                String statusClass = success == rows.size() ? "pass-cell" : "fail-cell";
                String spikesHtml  = spikes > 0
                    ? "<span class=\"fail-cell\">" + spikes + "</span>" : "0";
                sb.append("      <tr><td>").append(escHtml(domain))
                  .append("</td><td>").append(escHtml(type))
                  .append("</td><td class=\"num\">").append(rows.size())
                  .append("</td><td class=\"num ").append(statusClass).append("\">").append(rateStr)
                  .append("</td><td class=\"num\">").append(String.format("%.0f", avg))
                  .append("</td><td class=\"num\">").append(min)
                  .append("</td><td class=\"num\">").append(max)
                  .append("</td><td class=\"num\">").append(spikesHtml)
                  .append("</td></tr>\n");
            })
        );

        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("</div>\n");

        // Per-iteration response-time line chart
        sb.append("<div class=\"card\">\n");
        sb.append("  <div class=\"card-title\">DNS Response Times per Iteration (per domain \u00d7 type)</div>\n");
        sb.append("  <div class=\"chart-wrap-tall\"><canvas id=\"chart-dns-iter\"></canvas></div>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    // ── Timeline section ──────────────────────────────────────────────────────

    /**
     * Builds the Timeline section that overlays all three probes on a shared
     * horizontal time axis: YouTube connection-speed and buffer-health line charts,
     * a website page-load scatter plot, and a DNS success/failure event rug.
     *
     * @param yt  YouTube metrics (sweep-level samples used for the line charts)
     * @param web Website metrics (per-cycle timestamps used for the scatter plot)
     * @param dns DNS results (per-query timestamps used for the event rug)
     * @return HTML card fragment with multiple {@code <canvas>} elements
     */
    private String timelineChart(List<VideoMetrics> yt, List<WebsiteMetrics> web,
                                  List<DnsResult> dns) {
        long ytSweeps = yt.stream()
            .mapToLong(m -> m.getNetworkSamples() != null ? m.getNetworkSamples().size() : 0).sum();
        long webCycles = web.size();
        long dnsQueries = dns.size();
        int  webDomains = (int) web.stream().map(WebsiteMetrics::getTabIndex).distinct().count();
        int  dnsDomains = (int) dns.stream().map(DnsResult::getDomain).distinct().count();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"card\">\n");
        sb.append("  <div class=\"card-title\">Internet Performance Timeline &mdash; All Probes on a Common Time Axis</div>\n");

        // Summary stats bar
        sb.append("  <div class=\"tl-stats-bar\">\n");
        if (!yt.isEmpty())
            sb.append("    <span class=\"tl-stat yt-stat\">&#9654; ").append(yt.size())
              .append(" YouTube tab").append(yt.size() != 1 ? "s" : "")
              .append(" &middot; ").append(ytSweeps).append(" sweeps</span>\n");
        if (!web.isEmpty())
            sb.append("    <span class=\"tl-stat web-stat\">&#127760; ").append(webDomains)
              .append(" domain").append(webDomains != 1 ? "s" : "")
              .append(" &middot; ").append(webCycles).append(" checks</span>\n");
        if (!dns.isEmpty())
            sb.append("    <span class=\"tl-stat dns-stat\">&#128270; ").append(dnsDomains)
              .append(" domain").append(dnsDomains != 1 ? "s" : "")
              .append(" &middot; ").append(dnsQueries).append(" queries</span>\n");
        sb.append("  </div>\n");

        // Panel 1: Connection Speed over time (all YT tabs combined)
        if (!yt.isEmpty()) {
            sb.append("  <div class=\"tl-panel-label\">&#9654; Connection Speed (Mbps) over time</div>\n");
            sb.append("  <div class=\"timeline-wrap\">\n");
            sb.append("    <div style=\"position:relative;height:220px;\"><canvas id=\"chart-tl-cs\"></canvas></div>\n");
            sb.append("  </div>\n");
            // Panel 2: SFN Buffer Health over time (all YT tabs combined)
            sb.append("  <div class=\"tl-panel-label\">&#9654; SFN Buffer Health (s) over time</div>\n");
            sb.append("  <div class=\"timeline-wrap\">\n");
            sb.append("    <div style=\"position:relative;height:220px;\"><canvas id=\"chart-tl-buf\"></canvas></div>\n");
            sb.append("  </div>\n");
            // Panel 3: Frames Dropped over time (all YT tabs combined)
            sb.append("  <div class=\"tl-panel-label\">&#9654; Frames Dropped over time</div>\n");
            sb.append("  <div class=\"timeline-wrap\">\n");
            sb.append("    <div style=\"position:relative;height:220px;\"><canvas id=\"chart-tl-frames\"></canvas></div>\n");
            sb.append("  </div>\n");
        }

        // Panel 2: Website Load timeline (dedicated panel)
        if (!web.isEmpty()) {
            sb.append("  <div class=\"tl-panel-label\">&#127760; Website Load over Time ");
            sb.append("<span style=\"font-size:.68rem;color:#64748b;text-transform:none;font-weight:400\">(page load &amp; TTFB per domain &mdash; hover for details)</span></div>\n");
            sb.append("  <div class=\"timeline-wrap\">\n");
            sb.append("    <div style=\"position:relative;height:220px;\"><canvas id=\"chart-tl-web\"></canvas></div>\n");
            sb.append("  </div>\n");
        }

        // Panel 3: DNS Response timeline (dedicated panel)
        if (!dns.isEmpty()) {
            sb.append("  <div class=\"tl-panel-label\">&#128270; DNS Response over Time ");
            sb.append("<span style=\"font-size:.68rem;color:#64748b;text-transform:none;font-weight:400\">(avg round-trip per domain \u00d7 type to 8.8.8.8 \u2014 hover for details)</span></div>\n");
            sb.append("  <div class=\"timeline-wrap\">\n");
            sb.append("    <div style=\"position:relative;height:220px;\"><canvas id=\"chart-tl-dns\"></canvas></div>\n");
            sb.append("  </div>\n");
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    // ── Chart.js initialisation script ───────────────────────────────────────

    /**
     * Generates the closing {@code <script>} block containing:
     * <ul>
     *   <li>A {@code showSection()} tab-switching helper function.</li>
     *   <li>All Chart.js dataset construction and chart instantiation calls
     *       for every {@code <canvas>} element produced by the HTML builder methods.</li>
     * </ul>
     * Chart data is serialised inline as JavaScript literals so the report is
     * fully self-contained and requires no server at view time.
     *
     * @param yt  YouTube metrics (sweep-level bandwidth / buffer / frame data)
     * @param web Website metrics (per-cycle page-load and TTFB data)
     * @param dns DNS results (per-domain / per-type response-time data)
     * @return a complete {@code <script>...<\/script>} block as a string
     */
    private String scriptInit(List<VideoMetrics> yt, List<WebsiteMetrics> web,
                               List<DnsResult> dns,
                               SpikeAnalyzer.Result webSpikeAnalysis,
                               SpikeAnalyzer.Result dnsSpikeAnalysis) {
        StringBuilder sb = new StringBuilder("<script>\n");

        // ── Helper ─────────────────────────────────────────────────────────
        sb.append("""
            function showSection(id, btn) {
              document.querySelectorAll('.section').forEach(s => s.classList.remove('visible'));
              document.querySelectorAll('#main-nav button').forEach(b => b.classList.remove('active'));
              const s = document.getElementById(id);
              if (s) s.classList.add('visible');
              if (btn) btn.classList.add('active');
            }
            // Show first section on load
            window.addEventListener('DOMContentLoaded', () => {
              const first = document.querySelector('#main-nav button');
              if (first) first.click();
            });
            const CHART_DEFAULTS = {
              responsive: true, maintainAspectRatio: false,
              plugins: { legend: { labels: { color: '#4e7a5e', font: { size: 11, family: "'JetBrains Mono', monospace" } } } },
              scales: {
                x: { ticks: { color: '#4e7a5e', font: { size: 10 } },
                     grid: { color: '#162a1e' } },
                y: { ticks: { color: '#4e7a5e', font: { size: 10 } },
                     grid: { color: '#162a1e' } }
              }
            };
            function mergeDeep(target, source) {
              const output = Object.assign({}, target);
              for (const key in source) {
                if (source[key] && typeof source[key] === 'object' && !Array.isArray(source[key])) {
                  output[key] = mergeDeep(target[key] || {}, source[key]);
                } else { output[key] = source[key]; }
              }
              return output;
            }
            function makeOpts(overrides) { return mergeDeep(CHART_DEFAULTS, overrides || {}); }
            // Plugin: draw numeric value labels on top of each bar segment
            // when chart.options._showBarValues === true
            const barValuePlugin = {
              id: 'barValues',
              afterDatasetsDraw(chart) {
                if (!chart.options._showBarValues) return;
                const { ctx } = chart;
                chart.config.data.datasets.forEach((ds, dsi) => {
                  const meta = chart.getDatasetMeta(dsi);
                  if (meta.hidden || ds.type === 'line') return;
                  meta.data.forEach((bar, j) => {
                    const val = ds.data[j];
                    if (val == null || val <= 0) return;
                    ctx.save();
                    ctx.font = 'bold 9px "Segoe UI",sans-serif';
                    ctx.fillStyle = '#e2e8f0';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'bottom';
                    ctx.fillText(val, bar.x, bar.y - 2);
                    ctx.restore();
                  });
                });
              }
            };
            Chart.register(barValuePlugin);
            // Plugin: crosshair – draws a synchronized vertical cursor at chart._xHair
            const crosshairPlugin = {
              id: 'crosshair',
              afterDraw(chart) {
                if (chart._xHair == null) return;
                const {ctx, chartArea:{top,bottom}, scales:{x}} = chart;
                if (!x) return;
                const px = x.getPixelForValue(chart._xHair);
                ctx.save();
                ctx.beginPath();
                ctx.setLineDash([4,4]);
                ctx.strokeStyle='rgba(78,122,94,.55)';
                ctx.lineWidth=1;
                ctx.moveTo(px,top); ctx.lineTo(px,bottom);
                ctx.stroke();
                ctx.restore();
              }
            };
            Chart.register(crosshairPlugin);
            // Plugin: clusterBand — draws a translucent background band over the cluster window.
            // chart.options._clusterBands = [{xStart, xEnd, color}]
            // For categorical charts (line): xStart/xEnd are label indices.
            // For scatter/linear charts: xStart/xEnd are axis values.
            const clusterBandPlugin = {
              id: 'clusterBand',
              beforeDraw(chart) {
                const bands = chart.options._clusterBands;
                if (!bands || !bands.length) return;
                const {ctx, chartArea} = chart;
                if (!chartArea) return;
                const {top,bottom} = chartArea;
                const xScale = chart.scales && chart.scales.x;
                if (!xScale) return;
                ctx.save();
                for (const b of bands) {
                  let x1, x2;
                  if (xScale.type === 'category') {
                    x1 = xScale.getPixelForTick ? xScale.getPixelForTick(b.xStart) : xScale.getPixelForValue(b.xStart);
                    x2 = xScale.getPixelForTick ? xScale.getPixelForTick(b.xEnd)   : xScale.getPixelForValue(b.xEnd);
                  } else {
                    x1 = xScale.getPixelForValue(b.xStart);
                    x2 = xScale.getPixelForValue(b.xEnd);
                  }
                  const w = x2 - x1;
                  ctx.fillStyle = b.color || 'rgba(255,100,0,0.10)';
                  ctx.fillRect(Math.min(x1,x2), top, Math.abs(w) || 8, bottom - top);
                  // Draw a thin left border to mark cluster start
                  ctx.strokeStyle = (b.color || 'rgba(255,100,0,0.10)').replace(/[\\d.]+\\)$/, '0.75)');
                  ctx.lineWidth = 2;
                  ctx.setLineDash([4, 3]);
                  ctx.beginPath();
                  ctx.moveTo(Math.min(x1, x2), top);
                  ctx.lineTo(Math.min(x1, x2), bottom);
                  ctx.stroke();
                }
                ctx.restore();
              }
            };
            Chart.register(clusterBandPlugin);
            // Plugin: aboveCritLabel — draws ms value labels above data points that
            // exceed the chart's _criticalLine threshold so outliers above the
            // critical dotted line are explicitly readable without hovering.
            const aboveCritPlugin = {
              id: 'aboveCritLabel',
              afterDatasetsDraw(chart) {
                const crit = chart.options._criticalLine;
                if (crit == null) return;
                const { ctx, chartArea } = chart;
                if (!chartArea) return;
                chart.config.data.datasets.forEach((ds, dsi) => {
                  if (ds.pointRadius === 0) return;
                  const meta = chart.getDatasetMeta(dsi);
                  if (meta.hidden) return;
                  meta.data.forEach((pt, j) => {
                    const raw = ds.data[j];
                    if (raw == null) return;
                    const val = typeof raw === 'object' ? (raw.a !== undefined ? raw.a : raw.y) : raw;
                    if (val == null || val <= crit) return;
                    ctx.save();
                    ctx.font = 'bold 9px "Segoe UI", sans-serif';
                    ctx.fillStyle = '#e2e8f0';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'bottom';
                    const yPos = Math.max(pt.y - 8, chartArea.top + 10);
                    ctx.fillText(val + 'ms', pt.x, yPos);
                    ctx.restore();
                  });
                });
              }
            };
            Chart.register(aboveCritPlugin);
            """);

        // ── YouTube overview bar chart ─────────────────────────────────────
        if (!yt.isEmpty()) {
            List<String> labels = yt.stream()
                .map(m -> "Tab #" + m.getTabIndex())
                .collect(Collectors.toList());
            List<Long> ttfb = yt.stream().map(VideoMetrics::getTimeToFirstByte).collect(Collectors.toList());
            List<Long> load = yt.stream().map(VideoMetrics::getPageLoadTime).collect(Collectors.toList());
            List<Long> dcl  = yt.stream().map(VideoMetrics::getDomContentLoadedTime).collect(Collectors.toList());

            long maxYtPageMs = Math.max(Math.max(
                ttfb.stream().mapToLong(l -> l).max().orElse(0L),
                load.stream().mapToLong(l -> l).max().orElse(0L)),
                dcl.stream().mapToLong(l -> l).max().orElse(0L));
            sb.append("new Chart(document.getElementById('chart-yt-overview'), {\n")
              .append("  type: 'bar',\n")
              .append("  data: {\n")
              .append("    labels: ").append(toJsArray(labels)).append(",\n")
              .append("    datasets: [\n")
              .append("      { label: 'TTFB (ms)', data: ").append(toJsLongArray(ttfb))
              .append(", backgroundColor: '#00e5ff' },\n")
              .append("      { label: 'DOM Content Loaded (ms)', data: ").append(toJsLongArray(dcl))
              .append(", backgroundColor: '#ffb300' },\n")
              .append("      { label: 'Page Load (ms)', data: ").append(toJsLongArray(load))
              .append(", backgroundColor: '#b388ff' }\n")
              .append("    ]\n")
              .append("  },\n")
              .append("  options: makeOpts({ _showBarValues: true, plugins: { legend: { position: 'bottom' } },\n")
              .append("    scales: { y: { suggestedMax: ").append(maxYtPageMs).append(" } } })\n")
              .append("});\n");
            for (VideoMetrics m : yt) {
                List<NetworkSample> samples = m.getNetworkSamples();
                if (samples == null || samples.isEmpty()) continue;

                // Fixed metric-type colors — same across all tabs so each metric is visually consistent
                String csHex  = "#00e5ff";
                String csRgb  = "0,229,255";
                String bufHex = "#00ff87";
                String bufRgb = "0,255,135";

                long t0 = samples.get(0).getTimestamp();
                List<String> timeLabels = samples.stream()
                    .map(s -> "+" + ((s.getTimestamp() - t0) / 1000) + "s")
                    .collect(Collectors.toList());
                List<Double> bufData = samples.stream()
                    .map(s -> s.getBufferHealthSecs() >= 0 ? s.getBufferHealthSecs() : null)
                    .collect(Collectors.toList());

                // Compute SFN buffer health statistics for reference bands
                List<Double> bufVals  = bufData.stream().filter(v -> v != null).collect(Collectors.toList());
                StatisticsEngine.Stats bufStats = StatisticsEngine.compute(bufVals);

                // ── Connection Speed chart ───────────────────────────────────────
                // Convert Kbps → Mbps for a readable Y-axis
                List<Double> csData = samples.stream()
                    .map(s -> s.getConnectionSpeedKbps() > 0 ? s.getConnectionSpeedKbps() / 1000.0 : null)
                    .collect(Collectors.toList());
                List<Double> nonNullCs = csData.stream().filter(v -> v != null).collect(Collectors.toList());
                StatisticsEngine.Stats csStats = StatisticsEngine.compute(nonNullCs);

                sb.append("new Chart(document.getElementById('chart-yt-bw-").append(m.getTabIndex()).append("'), {\n")
                  .append("  type: 'line',\n")
                  .append("  data: {\n")
                  .append("    labels: ").append(toJsArray(timeLabels)).append(",\n")
                  .append("    datasets: [\n")
                  .append("      { label: 'Connection Speed (Mbps)', data: ").append(toJsNullableDoubleArray(csData))
                  .append(", borderColor: '").append(csHex).append("', backgroundColor: 'rgba(").append(csRgb).append(",.1)',")
                  .append(" fill: true, tension: 0.3, spanGaps: true, order: 1 }");
                if (csStats != null) {
                    List<Double> csUclLine  = timeLabels.stream().map(l -> csStats.ucl(1.0)).collect(Collectors.toList());
                    List<Double> csLclLine  = timeLabels.stream().map(l -> Math.max(0.0, csStats.lcl(1.0))).collect(Collectors.toList());
                    List<Double> csMeanLine = timeLabels.stream().map(l -> csStats.mean).collect(Collectors.toList());
                    // Min threshold: 2.5 Mbps = YouTube's HD minimum (matches MIN_SFN_CONNECTION_SPEED_KBPS)
                    List<Double> csMinLine  = timeLabels.stream().map(l -> 2.5).collect(Collectors.toList());
                    sb.append(",\n")
                      .append("      { label: '\u03bc+1\u03c3 (").append(String.format("%.1f", csStats.ucl(1.0))).append(" Mbps)',")
                      .append(" data: ").append(toJsDoubleArray(csUclLine)).append(",")
                      .append(" borderColor:'rgba(").append(csRgb).append(",.25)', borderDash:[3,3], borderWidth:1,")
                      .append(" backgroundColor:'rgba(").append(csRgb).append(",.07)', fill:'+1',")
                      .append(" pointRadius:0, order:3 }")
                      .append(",\n")
                      .append("      { label: '\u03bc\u22121\u03c3 (").append(String.format("%.1f", Math.max(0.0, csStats.lcl(1.0)))).append(" Mbps)',")
                      .append(" data: ").append(toJsDoubleArray(csLclLine)).append(",")
                      .append(" borderColor:'rgba(").append(csRgb).append(",.25)', borderDash:[3,3], borderWidth:1,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:3 }")
                      .append(",\n")
                      .append("      { label: '\u03bc (").append(String.format("%.1f", csStats.mean)).append(" Mbps)',")
                      .append(" data: ").append(toJsDoubleArray(csMeanLine)).append(",")
                      .append(" borderColor:'rgba(").append(csRgb).append(",.55)', borderDash:[4,4], borderWidth:1.5,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:2 }")
                      .append(",\n")
                      .append("      { label: 'Min threshold (2.5 Mbps)',")
                      .append(" data: ").append(toJsDoubleArray(csMinLine)).append(",")
                      .append(" borderColor:'rgba(255,45,85,.65)', borderDash:[6,3], borderWidth:1.5,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:2 }");
                }
                double maxCsMbps = nonNullCs.stream().mapToDouble(d -> d).max().orElse(0.0);
                if (csStats != null) maxCsMbps = Math.max(maxCsMbps, csStats.ucl(1.0));
                sb.append("\n    ]\n")
                  .append("  },\n")
                  .append("  options: makeOpts({\n")
                  .append("    plugins: { legend: { position: 'bottom', labels: { boxWidth:10, font:{size:10} } } },\n")
                  .append("    scales: { x: { ticks:{color:'#4e7a5e',font:{size:10}}, grid:{color:'#162a1e'} },\n")
                  .append("             y: { title:{display:true,text:'Mbps',color:'#4e7a5e'},")
                  .append(" ticks:{color:'#4e7a5e',font:{size:10}}, grid:{color:'#162a1e'},")
                  .append(" suggestedMax: ").append(String.format("%.2f", maxCsMbps)).append(" } }\n")
                  .append("  })\n")
                  .append("});\n");

                // ── SFN Buffer Health chart ───────────────────────────────────
                sb.append("new Chart(document.getElementById('chart-yt-buf-").append(m.getTabIndex()).append("'), {\n")
                  .append("  type: 'line',\n")
                  .append("  data: {\n")
                  .append("    labels: ").append(toJsArray(timeLabels)).append(",\n")
                  .append("    datasets: [\n")
                  .append("      { label: 'SFN Buffer Health (s)', data: ").append(toJsNullableDoubleArray(bufData))
                  .append(", borderColor: '").append(bufHex).append("', backgroundColor: 'rgba(").append(bufRgb).append(",.12)',")
                  .append(" fill: true, tension: 0.3, spanGaps: true, order: 1 }");
                if (bufStats != null) {
                    List<Double> bufUclLine  = timeLabels.stream().map(l -> bufStats.ucl(1.0)).collect(Collectors.toList());
                    List<Double> bufLclLine  = timeLabels.stream().map(l -> bufStats.lcl(1.0)).collect(Collectors.toList());
                    List<Double> bufMeanLine = timeLabels.stream().map(l -> bufStats.mean).collect(Collectors.toList());
                    // Threshold at 0 s: 2+ consecutive sweeps at 0 triggers FAIL.
                    List<Double> bufMinLine  = timeLabels.stream().map(l -> 0.0).collect(Collectors.toList());
                    sb.append(",\n")
                      .append("      { label: '\u03bc+1\u03c3 (").append(String.format("%.1f", bufStats.ucl(1.0))).append("s)',")
                      .append(" data: ").append(toJsDoubleArray(bufUclLine)).append(",")
                      .append(" borderColor:'rgba(").append(bufRgb).append(",.25)', borderDash:[3,3], borderWidth:1,")
                      .append(" backgroundColor:'rgba(").append(bufRgb).append(",.06)', fill:'+1',")
                      .append(" pointRadius:0, order:3 }")
                      .append(",\n")
                      .append("      { label: '\u03bc\u22121\u03c3 (").append(String.format("%.1f", bufStats.lcl(1.0))).append("s)',")
                      .append(" data: ").append(toJsDoubleArray(bufLclLine)).append(",")
                      .append(" borderColor:'rgba(").append(bufRgb).append(",.25)', borderDash:[3,3], borderWidth:1,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:3 }")
                      .append(",\n")
                      .append("      { label: '\u03bc buf (").append(String.format("%.1f", bufStats.mean)).append("s)',")
                      .append(" data: ").append(toJsDoubleArray(bufMeanLine)).append(",")
                      .append(" borderColor:'rgba(").append(bufRgb).append(",.45)', borderDash:[4,4], borderWidth:1.5,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:2 }")
                      .append(",\n")
                      .append("      { label: 'Critical floor (0s: 2+ consec sweeps \u2192 FAIL)',")
                      .append(" data: ").append(toJsDoubleArray(bufMinLine)).append(",")
                      .append(" borderColor:'rgba(255,45,85,.65)', borderDash:[6,3], borderWidth:1.5,")
                      .append(" backgroundColor:'transparent', pointRadius:0, order:2 }");
                }
                double maxBufSecs = bufVals.stream().mapToDouble(d -> d).max().orElse(0.0);
                if (bufStats != null) maxBufSecs = Math.max(maxBufSecs, bufStats.ucl(1.0));
                sb.append("\n    ]\n")
                  .append("  },\n")
                  .append("  options: makeOpts({\n")
                  .append("    plugins: { legend: { position: 'bottom', labels: { boxWidth:10, font:{size:10} } } },\n")
                  .append("    scales: { x: { ticks:{color:'#4e7a5e',font:{size:10}}, grid:{color:'#162a1e'} },\n")
                  .append("             y: { title:{display:true,text:'Buffer (s)',color:'#4e7a5e'},")
                  .append(" ticks:{color:'#4e7a5e',font:{size:10}}, grid:{color:'#162a1e'},")
                  .append(" suggestedMax: ").append(String.format("%.2f", maxBufSecs)).append(" } }\n")
                  .append("  })\n")
                  .append("});\n");

                // ── Frames Dropped chart ─────────────────────────────────────
                // Compute per-sweep drop % from cumulative counters:
                // sweep 0: dropped[0]/total[0]*100
                // sweep i: (dropped[i]-dropped[i-1]) / (total[i]-total[i-1]) * 100
                List<Double> frameDropPct = new ArrayList<>();
                for (int si = 0; si < samples.size(); si++) {
                    NetworkSample s = samples.get(si);
                    if (s.getDroppedFrames() < 0 || s.getTotalFrames() <= 0) {
                        frameDropPct.add(null);
                    } else if (si == 0) {
                        frameDropPct.add(s.getTotalFrames() > 0
                            ? 100.0 * s.getDroppedFrames() / s.getTotalFrames() : null);
                    } else {
                        NetworkSample prev = samples.get(si - 1);
                        long dDrop  = s.getDroppedFrames() - (prev.getDroppedFrames() >= 0 ? prev.getDroppedFrames() : 0);
                        long dTotal = s.getTotalFrames()   - (prev.getTotalFrames()   >  0 ? prev.getTotalFrames()   : 0);
                        frameDropPct.add(dTotal > 0 ? 100.0 * dDrop / dTotal : null);
                    }
                }
                boolean hasFrameData = frameDropPct.stream().anyMatch(v -> v != null);
                if (hasFrameData) {
                    List<Double> nonNullFrames = frameDropPct.stream().filter(v -> v != null).collect(Collectors.toList());
                    StatisticsEngine.Stats frameStats = StatisticsEngine.compute(nonNullFrames);

                    sb.append("new Chart(document.getElementById('chart-yt-frames-").append(m.getTabIndex()).append("'), {\n")
                      .append("  type: 'line',\n")
                      .append("  data: {\n")
                      .append("    labels: ").append(toJsArray(timeLabels)).append(",\n")
                      .append("    datasets: [\n")
                      .append("      { label: 'Dropped Frames %', data: ").append(toJsNullableDoubleArray(frameDropPct))
                      .append(", borderColor: '#ff2d55', backgroundColor: 'rgba(255,45,85,.15)',")
                      .append(" fill: true, tension: 0.3, spanGaps: true, pointRadius: 3, order: 1 }");
                    if (frameStats != null) {
                        List<Double> fUclLine  = timeLabels.stream().map(l -> frameStats.ucl(1.0)).collect(Collectors.toList());
                        List<Double> fLclLine  = timeLabels.stream().map(l -> Math.max(0.0, frameStats.lcl(1.0))).collect(Collectors.toList());
                        List<Double> fMeanLine = timeLabels.stream().map(l -> frameStats.mean).collect(Collectors.toList());
                        // Max acceptable drop rate threshold: 1% is a common quality bar
                        List<Double> fMaxLine  = timeLabels.stream().map(l -> 1.0).collect(Collectors.toList());
                        // Critical floor: 100% means ALL frames dropped in that sweep
                        List<Double> f100Line  = timeLabels.stream().map(l -> 100.0).collect(Collectors.toList());
                        sb.append(",\n")
                          .append("      { label: '\u03bc+1\u03c3 (").append(String.format("%.2f", frameStats.ucl(1.0))).append("%)',")
                          .append(" data: ").append(toJsDoubleArray(fUclLine)).append(",")
                          .append(" borderColor:'rgba(255,45,85,.30)', borderDash:[3,3], borderWidth:1,")
                          .append(" backgroundColor:'rgba(255,45,85,.07)', fill:'+1',")
                          .append(" pointRadius:0, order:3 }")
                          .append(",\n")
                          .append("      { label: '\u03bc\u22121\u03c3 (").append(String.format("%.2f", Math.max(0.0, frameStats.lcl(1.0)))).append("%)',")
                          .append(" data: ").append(toJsDoubleArray(fLclLine)).append(",")
                          .append(" borderColor:'rgba(255,45,85,.30)', borderDash:[3,3], borderWidth:1,")
                          .append(" backgroundColor:'transparent', pointRadius:0, order:3 }")
                          .append(",\n")
                          .append("      { label: '\u03bc drop (").append(String.format("%.2f", frameStats.mean)).append("%)',")
                          .append(" data: ").append(toJsDoubleArray(fMeanLine)).append(",")
                          .append(" borderColor:'rgba(255,100,120,.65)', borderDash:[4,4], borderWidth:1.5,")
                          .append(" backgroundColor:'transparent', pointRadius:0, order:2 }")
                          .append(",\n")
                          .append("      { label: 'Max threshold (1%)',")
                          .append(" data: ").append(toJsDoubleArray(fMaxLine)).append(",")
                          .append(" borderColor:'rgba(255,196,0,.75)', borderDash:[6,3], borderWidth:1.5,")
                          .append(" backgroundColor:'transparent', pointRadius:0, order:2 }")
                          .append(",\n")
                          .append("      { label: 'Critical (100% \u2192 FAIL)',")
                          .append(" data: ").append(toJsDoubleArray(f100Line)).append(",")
                          .append(" borderColor:'rgba(255,45,85,.90)', borderDash:[2,2], borderWidth:2,")
                          .append(" backgroundColor:'transparent', pointRadius:0, order:2 }");;
                    }
                    double maxFrameDropPct = Math.max(100.0,
                        nonNullFrames.stream().mapToDouble(d -> d).max().orElse(100.0));
                    sb.append("\n    ]\n")
                      .append("  },\n")
                      .append("  options: makeOpts({\n")
                      .append("    plugins: { legend: { position: 'bottom', labels: { boxWidth:10, font:{size:10} } },\n")
                      .append("               tooltip: { callbacks: { label: function(ctx) { return ctx.dataset.label + ': ' + (ctx.parsed.y != null ? ctx.parsed.y.toFixed(2) + '%' : 'N/A'); } } } },\n")
                      .append("    scales: {\n")
                      .append("      x: { ticks:{color:'#4e7a5e',font:{size:10}}, grid:{color:'#162a1e'} },\n")
                      .append("      y: { title:{display:true,text:'Drop % per sweep',color:'#ff2d55'},")
                      .append(" ticks:{color:'#ff2d55',font:{size:10},callback:function(v){return v.toFixed(1)+'%';}},")
                      .append(" grid:{color:'#162a1e'}, min:0, suggestedMax: ").append(String.format("%.1f", maxFrameDropPct)).append(" }\n")
                      .append("    }\n")
                      .append("  })\n")
                      .append("});\n");
                }
            }
        }

        // ── Website line charts ───────────────────────────────────────────
        if (!web.isEmpty()) {
            Map<Integer, List<WebsiteMetrics>> byTab = web.stream()
                .collect(Collectors.groupingBy(WebsiteMetrics::getTabIndex));

            byTab.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                List<WebsiteMetrics> rows = e.getValue();
                List<String> cycles = rows.stream()
                    .map(r -> "C" + r.getRefreshCycle()).collect(Collectors.toList());
                List<Long> ttfbs = rows.stream().map(WebsiteMetrics::getTimeToFirstByte).collect(Collectors.toList());
                List<Long> dcls  = rows.stream().map(WebsiteMetrics::getDomContentLoaded).collect(Collectors.toList());

                // Build page-load data array with spike flags {y, s:1} for easy point styling
                StringBuilder loadsArr = new StringBuilder("[");
                for (int i = 0; i < rows.size(); i++) {
                    WebsiteMetrics wm = rows.get(i);
                    if (i > 0) loadsArr.append(",");
                    boolean isSpike = webSpikeAnalysis.isSpikeAt(wm.getTimestamp());
                    if (isSpike) {
                        loadsArr.append("{y:").append(wm.getPageLoadTime()).append(",s:1}");
                    } else {
                        loadsArr.append(wm.getPageLoadTime());
                    }
                }
                loadsArr.append("]");

                // Compute ±1σ bands for page load
                List<Double> loadDoubles = rows.stream().map(r -> (double) r.getPageLoadTime())
                    .filter(v -> v >= 0).collect(Collectors.toList());
                StatisticsEngine.Stats loadStats  = StatisticsEngine.compute(loadDoubles);
                // Robust (MAD-based) stats for the outlier gate — matches the summary table
                // calculation so the gate line is visible even when an outlier inflates σ.
                StatisticsEngine.Stats loadRobust = StatisticsEngine.computeRobust(loadDoubles);

                long maxWebTabMs = Math.max(Math.max(
                    rows.stream().mapToLong(WebsiteMetrics::getPageLoadTime).max().orElse(0L),
                    ttfbs.stream().mapToLong(l -> l).max().orElse(0L)),
                    dcls.stream().mapToLong(l -> l).max().orElse(0L));
                if (loadRobust != null)
                    maxWebTabMs = Math.max(maxWebTabMs, (long) loadRobust.ucl(5.0));
                else if (loadStats != null)
                    maxWebTabMs = Math.max(maxWebTabMs, (long) loadStats.ucl(5.0));

                // Compute cluster band as cycle-index range if a cluster was found
                String clusterBandOpt = "[]";
                if (webSpikeAnalysis.hasCluster && webSpikeAnalysis.clusterWindowStartMs > 0) {
                    int bandStart = Integer.MAX_VALUE, bandEnd = Integer.MIN_VALUE;
                    for (int i = 0; i < rows.size(); i++) {
                        long ts = rows.get(i).getTimestamp();
                        if (ts >= webSpikeAnalysis.clusterWindowStartMs
                                && ts <= webSpikeAnalysis.clusterWindowEndMs) {
                            bandStart = Math.min(bandStart, i);
                            bandEnd   = Math.max(bandEnd,   i);
                        }
                    }
                    if (bandStart != Integer.MAX_VALUE) {
                        clusterBandOpt = "[{xStart:" + bandStart + ",xEnd:" + bandEnd
                            + ",color:'rgba(255,100,0,0.10)'}]";
                    }
                }

                sb.append("new Chart(document.getElementById('chart-web-").append(e.getKey()).append("'), {\n")
                  .append("  type: 'line',\n")
                  .append("  data: {\n")
                  .append("    labels: ").append(toJsArray(cycles)).append(",\n")
                  .append("    datasets: [\n")
                  .append("      { label: 'Page Load (ms)', data: ").append(loadsArr)
                  .append(", borderColor: '#b388ff', backgroundColor: 'rgba(179,136,255,.1)', fill: true, tension: 0.3,\n")
                  // Spike points coloured amber; cluster-spike points coloured orange
                  .append("        pointBackgroundColor: function(ctx){\n")
                  .append("          const r=ctx.raw; if(r&&typeof r==='object'&&r.s) return 'rgba(255,180,0,0.9)'; return 'rgba(179,136,255,0.5)';\n")
                  .append("        },\n")
                  .append("        pointBorderColor: function(ctx){\n")
                  .append("          const r=ctx.raw; if(r&&typeof r==='object'&&r.s) return '#ffb300'; return '#b388ff';\n")
                  .append("        },\n")
                  .append("        pointRadius: function(ctx){\n")
                  .append("          const r=ctx.raw; if(r&&typeof r==='object'&&r.s) return 6; return 3;\n")
                  .append("        } },\n")
                  .append("      { label: 'TTFB (ms)', data: ").append(toJsLongArray(ttfbs))
                  .append(", borderColor: '#00e5ff', backgroundColor: 'transparent', tension: 0.3 },\n")
                  .append("      { label: 'DCL (ms)', data: ").append(toJsLongArray(dcls))
                  .append(", borderColor: '#ffb300', backgroundColor: 'transparent', tension: 0.3 }");

                // μ+Nσ annotation band — constant line across all cycles (outlier threshold).
                // Use robust (MAD-based) baseline so an extreme value cannot inflate σ enough
                // to push the gate above itself, keeping it consistent with the summary table.
                StatisticsEngine.Stats gateStats = loadRobust != null ? loadRobust : loadStats;
                if (gateStats != null) {
                    double ucl = gateStats.ucl(5.0);
                    String sigmaLabel = "5";
                    List<Double> uclLine  = cycles.stream().map(c -> ucl).collect(Collectors.toList());
                    // Mean line uses the arithmetic mean so the label is accurate for the viewer.
                    double meanVal = loadStats != null ? loadStats.mean : gateStats.mean;
                    List<Double> meanLine = cycles.stream().map(c -> meanVal).collect(Collectors.toList());
                    sb.append(",\n      { label: '\u03bc+").append(sigmaLabel).append("\u03c3 outlier gate (")
                      .append(String.format("%.0f", ucl)).append(" ms)',\n")
                      .append("        data: ").append(toJsDoubleArray(uclLine))
                      .append(", borderColor: '#ff2d55', borderDash:[6,3], borderWidth:1.5,\n")
                      .append("        backgroundColor:'transparent', pointRadius:0, tension:0 }");
                    sb.append(",\n      { label: '\u03bc=avg (").append(String.format("%.0f", meanVal)).append(" ms)',\n")
                      .append("        data: ").append(toJsDoubleArray(meanLine))
                      .append(", borderColor: '#4e7a5e', borderDash:[3,3], borderWidth:1,\n")
                      .append("        backgroundColor:'transparent', pointRadius:0, tension:0 }");
                }

                sb.append("\n    ]\n")
                  .append("  },\n")
                  .append("  options: makeOpts({ plugins: { legend: { position: 'bottom' },\n")
                  .append("    tooltip: { callbacks: { label: function(ctx) {\n")
                  .append("      const r=ctx.raw, l=ctx.dataset.label||'';\n")
                  .append("      const v=r!==null&&r!==undefined&&typeof r==='object'&&r.y!==undefined?r.y:ctx.parsed.y;\n")
                  .append("      const flag=r&&typeof r==='object'&&r.s?' \u26a1 SPIKE':'';\n")
                  .append("      return `${l}: ${v}ms${flag}`; } } } },\n")
                  .append("    _clusterBands: ").append(clusterBandOpt).append(",\n")
                  .append("    scales: { y: { title: { display:true, text:'ms', color:'#4e7a5e' },\n")
                  .append("      suggestedMax: ").append(maxWebTabMs).append(" } } })\n")
                  .append("});\n");
            });
        }

        // ── DNS bar chart ─────────────────────────────────────────────────
        if (!dns.isEmpty()) {
            Map<String, Map<String, List<DnsResult>>> grouped = dns.stream()
                .collect(Collectors.groupingBy(DnsResult::getDomain,
                         LinkedHashMap::new,
                         Collectors.groupingBy(DnsResult::getRecordType,
                         LinkedHashMap::new,
                         Collectors.toList())));

            List<String> dnsLabels  = new ArrayList<>();
            List<Double>  avgTimes  = new ArrayList<>();
            List<Double>  stddevTimes = new ArrayList<>();
            List<String>  barColors = new ArrayList<>();

            grouped.forEach((domain, byType) ->
                byType.forEach((type, rows) -> {
                    dnsLabels.add(domain + " (" + type + ")");
                    List<Double> rtVals = rows.stream().filter(DnsResult::isSuccess)
                        .map(r -> (double) r.getResponseTimeMs()).collect(Collectors.toList());
                    double avg = rtVals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    avgTimes.add(avg);
                    StatisticsEngine.Stats st = StatisticsEngine.compute(rtVals);
                    stddevTimes.add(st != null ? st.stddev : 0.0);
                    long fail = rows.stream().filter(r -> !r.isSuccess()).count();
                    barColors.add(fail > 0 ? "'#ff2d55'" : "'#00ff87'");
                })
            );

            // Compute overall mean across all labels for a threshold line
            double dnsOverallMean = avgTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            // Compute max bar height (max of avg and stddev bars) for dynamic y-axis scaling
            double maxDnsBarMs = Math.max(
                avgTimes.stream().mapToDouble(d -> d).max().orElse(0.0),
                stddevTimes.stream().mapToDouble(d -> d).max().orElse(0.0));

            sb.append("(function(){\n")
              .append("var dnsAvg=").append(toJsDoubleArray(avgTimes)).append(";\n")
              .append("var dnsStd=").append(toJsDoubleArray(stddevTimes)).append(";\n")
              .append("var dnsMean=").append(String.format("%.2f", dnsOverallMean)).append(";\n")
              .append("new Chart(document.getElementById('chart-dns-latency'), {\n")
              .append("  type: 'bar',\n")
              .append("  data: {\n")
              .append("    labels: ").append(toJsArray(dnsLabels)).append(",\n")
              .append("    datasets: [\n")
              .append("      { label: 'Avg Response (ms)', data: dnsAvg,\n")
              .append("        backgroundColor: [").append(String.join(",", barColors)).append("],\n")
              .append("        order: 2 },\n")
              .append("      { label: '\u03c3 Std Dev (ms)', data: dnsStd,\n")
              .append("        backgroundColor: 'rgba(255,179,0,.4)',\n")
              .append("        borderColor: '#ffb300', borderWidth: 1, order: 2 },\n")
              // Mean line across all DNS entries as a line dataset
              .append("      { label: '\u03bc Mean', type: 'line',\n")
              .append("        data: dnsAvg.map(function(){return dnsMean;}),\n")
              .append("        borderColor: '#4e7a5e', borderDash:[4,4], borderWidth:1,\n")
              .append("        backgroundColor:'transparent', pointRadius:0, order:1 }\n")
              .append("    ]\n")
              .append("  },\n")
              .append("  options: makeOpts({ plugins: { legend: { position: 'bottom' } },\n")
              .append("    scales: { y: { title: { display:true, text:'ms', color:'#4e7a5e' },\n")
              .append("      suggestedMax: ").append(String.format("%.0f", maxDnsBarMs)).append(" } } })\n")
              .append("});\n")
              .append("})();\n");

            // ── DNS per-iteration response-time line chart ────────────────────
            buildDnsIterChart(sb, dns, dnsSpikeAnalysis);
        }

        // ── Timeline scatter chart ────────────────────────────────────────
        // Build a horizontal "Gantt-like" scatter using Chart.js scatter,
        // one dataset per probe category, x = relative time (s), y = probe lane index
        buildTimelineChart(sb, yt, web, dns, webSpikeAnalysis, dnsSpikeAnalysis);

        sb.append("</script>\n");
        return sb.toString();
    }

    private void buildTimelineChart(StringBuilder sb,
                                     List<VideoMetrics>   yt,
                                     List<WebsiteMetrics> web,
                                     List<DnsResult>      dns,
                                     SpikeAnalyzer.Result webSpikeAnalysis,
                                     SpikeAnalyzer.Result dnsSpikeAnalysis) {

        // ── Find baseline and end timestamps ──────────────────────────────────
        long t0    = Long.MAX_VALUE;
        long tLast = Long.MIN_VALUE;
        for (VideoMetrics m : yt) {
            List<NetworkSample> samples = m.getNetworkSamples();
            if (samples != null && !samples.isEmpty()) {
                t0    = Math.min(t0,    samples.get(0).getTimestamp());
                tLast = Math.max(tLast, samples.get(samples.size() - 1).getTimestamp());
            }
            if (m.getMetricsCollectedAt() > 0) {
                t0    = Math.min(t0,    m.getMetricsCollectedAt());
                tLast = Math.max(tLast, m.getMetricsCollectedAt());
            }
        }
        for (WebsiteMetrics w : web) {
            if (w.getTimestamp() > 0) {
                t0    = Math.min(t0,    w.getTimestamp());
                tLast = Math.max(tLast, w.getTimestamp());
            }
        }
        for (DnsResult d : dns) {
            if (d.getTimestamp() > 0) {
                t0    = Math.min(t0,    d.getTimestamp());
                tLast = Math.max(tLast, d.getTimestamp());
            }
        }
        if (t0 == Long.MAX_VALUE) return;
        final long baseTs  = t0;
        double totalSecs   = Math.max(10.0, (tLast - t0) / 1000.0);
        double xMax        = Math.ceil(totalSecs * 1.05); // 5% right padding

        boolean hasYt     = !yt.isEmpty();
        boolean hasProbes = !web.isEmpty() || !dns.isEmpty();

        // Pre-compute y-axis suggested maximums so every chart scales to actual data
        double maxTlCsMbps  = 0.0;
        double maxTlBufSecs = 0.0;
        for (VideoMetrics m : yt) {
            List<NetworkSample> s = m.getNetworkSamples();
            if (s == null) continue;
            for (NetworkSample ns : s) {
                if (ns.getConnectionSpeedKbps() > 0)
                    maxTlCsMbps  = Math.max(maxTlCsMbps,  ns.getConnectionSpeedKbps() / 1000.0);
                if (ns.getBufferHealthSecs() >= 0)
                    maxTlBufSecs = Math.max(maxTlBufSecs, ns.getBufferHealthSecs());
            }
        }
        long maxTlWebMs = web.stream().mapToLong(WebsiteMetrics::getPageLoadTime).max().orElse(0L);
        // Pre-compute overall web load stats for the μ and Critical (μ+5σ) reference lines.
        // Robust (MAD-based) stats are used for the gate so outliers cannot inflate σ enough
        // to push the Critical line above themselves (making spikes invisible in the chart).
        List<Double> allWebTlLoads = web.stream().map(w -> (double) w.getPageLoadTime())
            .filter(v -> v >= 0).collect(Collectors.toList());
        StatisticsEngine.Stats webTlLoadStats       = StatisticsEngine.compute(allWebTlLoads);
        StatisticsEngine.Stats webTlLoadRobustStats = StatisticsEngine.computeRobust(allWebTlLoads);
        StatisticsEngine.Stats webTlGateStats = webTlLoadRobustStats != null
            ? webTlLoadRobustStats : webTlLoadStats;
        if (webTlGateStats != null) maxTlWebMs = Math.max(maxTlWebMs, (long) webTlGateStats.ucl(5.0));

        sb.append("(function(){\n");
        sb.append("var tlMax=").append(String.format("%.1f", xMax)).append(";\n");

        // ── Chart 1: Connection Speed (Mbps) — all YT tabs on one chart ────────
        if (hasYt) {
            String[] csColors  = {"#00e5ff","#ff9e40","#b388ff","#ff6eb4"};
            String[] bufColors = {"#00ff87","#e879f9","#c6ff00","#00bcd4"};
            String[] frColors  = {"#ff2d55","#ffb300","#00bcd4","#b388ff"};

            // ── Connection Speed chart ────────────────────────────────────────
            sb.append("var c1=new Chart(document.getElementById('chart-tl-cs'),{\n")
              .append("  type:'scatter',\n")
              .append("  data:{datasets:[\n");
            boolean firstDs = true;
            for (int i = 0; i < yt.size(); i++) {
                VideoMetrics m = yt.get(i);
                List<NetworkSample> samples = m.getNetworkSamples();
                if (samples == null || samples.isEmpty()) continue;
                if (!firstDs) sb.append(",\n");
                firstDs = false;
                String clr = csColors[i % csColors.length];
                sb.append("    {label:'Tab #").append(m.getTabIndex()).append(" Speed (Mbps)',")
                  .append("showLine:true,fill:true,tension:0.25,")
                  .append("borderColor:'").append(clr).append("',")
                  .append("backgroundColor:'").append(clr).append("1a',")
                  .append("pointRadius:2,pointHoverRadius:5,yAxisID:'cs',order:").append(i + 1).append(",data:[");
                boolean firstPt = true;
                for (NetworkSample s : samples) {
                    if (!firstPt) sb.append(",");
                    firstPt = false;
                    double x = (s.getTimestamp() - baseTs) / 1000.0;
                    // Convert Kbps → Mbps
                    double mbps = s.getConnectionSpeedKbps() > 0 ? s.getConnectionSpeedKbps() / 1000.0 : 0;
                    sb.append("{x:").append(String.format("%.1f", x))
                      .append(",y:").append(String.format("%.2f", mbps)).append("}");
                }
                sb.append("]}");
            }
            // Min threshold 2.5 Mbps
            sb.append(",\n")
              .append("    {label:'Min threshold (2.5 Mbps)',showLine:true,fill:false,")
              .append("borderColor:'rgba(255,45,85,.65)',borderDash:[6,3],borderWidth:1.5,")
              .append("backgroundColor:'transparent',pointRadius:0,yAxisID:'cs',order:99,")
              .append("data:[{x:0,y:2.5},{x:").append(String.format("%.1f", xMax)).append(",y:2.5}]}")
              .append("\n  ]},\n")
              .append("  options:makeOpts({\n")
              .append("    interaction:{mode:'index',intersect:false},\n")
              .append("    plugins:{\n")
              .append("      legend:{position:'bottom',labels:{usePointStyle:true,font:{size:10}}},\n")
              .append("      tooltip:{callbacks:{label:ctx=>{\n")
              .append("        const p=ctx.raw,l=ctx.dataset.label||'';\n")
              .append("        if(p&&p.y!==undefined) return `${l}: ${p.y.toFixed(2)} Mbps  @+${p.x.toFixed(1)}s`;\n")
              .append("        return `${l}: ${ctx.parsed.y}`;\n")
              .append("      }}}\n")
              .append("    },\n")
              .append("    scales:{\n")
              .append("      x:{type:'linear',min:0,max:tlMax,\n")
              .append("         title:{display:true,text:'Elapsed time (s)',color:'#4e7a5e'},\n")
              .append("         ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}},\n")
              .append("      cs:{type:'linear',position:'left',min:0,suggestedMax:")
              .append(String.format("%.2f", maxTlCsMbps))
              .append(",\n          title:{display:true,text:'Speed (Mbps)',color:'#00e5ff'},\n")
              .append("          ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}}\n")
              .append("    }\n")
              .append("  })\n")
              .append("});\n");

            // ── SFN Buffer Health chart ────────────────────────────────────────
            sb.append("var c1b=new Chart(document.getElementById('chart-tl-buf'),{\n")
              .append("  type:'scatter',\n")
              .append("  data:{datasets:[\n");
            firstDs = true;
            for (int i = 0; i < yt.size(); i++) {
                VideoMetrics m = yt.get(i);
                List<NetworkSample> samples = m.getNetworkSamples();
                if (samples == null || samples.isEmpty()) continue;
                if (!firstDs) sb.append(",\n");
                firstDs = false;
                String clr = bufColors[i % bufColors.length];
                sb.append("    {label:'Tab #").append(m.getTabIndex()).append(" Buffer (s)',")
                  .append("showLine:true,fill:true,tension:0.25,")
                  .append("borderColor:'").append(clr).append("',")
                  .append("backgroundColor:'").append(clr).append("1f',")
                  .append("pointRadius:2,pointHoverRadius:5,yAxisID:'buf',order:").append(i + 1).append(",data:[");
                boolean firstPt = true;
                for (NetworkSample s : samples) {
                    if (!firstPt) sb.append(",");
                    firstPt = false;
                    double x = (s.getTimestamp() - baseTs) / 1000.0;
                    double buf = s.getBufferHealthSecs() >= 0 ? s.getBufferHealthSecs() : 0;
                    sb.append("{x:").append(String.format("%.1f", x))
                      .append(",y:").append(String.format("%.2f", buf)).append("}");
                }
                sb.append("]}");
            }
            // Critical floor at 0 s — any sweep touching 0 triggers FAIL
            sb.append(",\n")
              .append("    {label:'Critical floor (0s \u2192 FAIL)',showLine:true,fill:false,")
              .append("borderColor:'rgba(255,45,85,.65)',borderDash:[6,3],borderWidth:1.5,")
              .append("backgroundColor:'transparent',pointRadius:0,yAxisID:'buf',order:99,")
              .append("data:[{x:0,y:0},{x:").append(String.format("%.1f", xMax)).append(",y:0}]}")
              .append("\n  ]},\n")
              .append("  options:makeOpts({\n")
              .append("    interaction:{mode:'index',intersect:false},\n")
              .append("    plugins:{\n")
              .append("      legend:{position:'bottom',labels:{usePointStyle:true,font:{size:10}}},\n")
              .append("      tooltip:{callbacks:{label:ctx=>{\n")
              .append("        const p=ctx.raw,l=ctx.dataset.label||'';\n")
              .append("        if(p&&p.y!==undefined) return `${l}: ${p.y.toFixed(2)}s  @+${p.x.toFixed(1)}s`;\n")
              .append("        return `${l}: ${ctx.parsed.y}`;\n")
              .append("      }}}\n")
              .append("    },\n")
              .append("    scales:{\n")
              .append("      x:{type:'linear',min:0,max:tlMax,\n")
              .append("         title:{display:true,text:'Elapsed time (s)',color:'#4e7a5e'},\n")
              .append("         ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}},\n")
              .append("      buf:{type:'linear',position:'left',min:0,suggestedMax:")
              .append(String.format("%.2f", maxTlBufSecs))
              .append(",\n           title:{display:true,text:'Buffer (s)',color:'#00ff87'},\n")
              .append("           ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}}\n")
              .append("    }\n")
              .append("  })\n")
              .append("});\n");

            // ── Frames Dropped chart ───────────────────────────────────────────
            // Determine if any tab has cumulative frame data
            boolean anyFrameData = yt.stream().anyMatch(m -> {
                List<NetworkSample> s = m.getNetworkSamples();
                return s != null && s.stream().anyMatch(ns -> ns.getDroppedFrames() >= 0 && ns.getTotalFrames() > 0);
            });
            if (anyFrameData) {
                sb.append("var c1c=new Chart(document.getElementById('chart-tl-frames'),{\n")
                  .append("  type:'scatter',\n")
                  .append("  data:{datasets:[\n");
                firstDs = true;
                for (int i = 0; i < yt.size(); i++) {
                    VideoMetrics m = yt.get(i);
                    List<NetworkSample> samples = m.getNetworkSamples();
                    if (samples == null || samples.isEmpty()) continue;
                    // Compute per-sweep drop %
                    boolean tabHasData = false;
                    StringBuilder ptBuf = new StringBuilder();
                    for (int si = 0; si < samples.size(); si++) {
                        NetworkSample s = samples.get(si);
                        if (s.getDroppedFrames() < 0 || s.getTotalFrames() <= 0) continue;
                        double dropPct;
                        if (si == 0) {
                            dropPct = s.getTotalFrames() > 0 ? 100.0 * s.getDroppedFrames() / s.getTotalFrames() : 0;
                        } else {
                            NetworkSample prev = samples.get(si - 1);
                            long dDrop  = s.getDroppedFrames() - (prev.getDroppedFrames() >= 0 ? prev.getDroppedFrames() : 0);
                            long dTotal = s.getTotalFrames()   - (prev.getTotalFrames()   >  0 ? prev.getTotalFrames()   : 0);
                            dropPct = dTotal > 0 ? 100.0 * dDrop / dTotal : 0;
                        }
                        double x = (s.getTimestamp() - baseTs) / 1000.0;
                        if (ptBuf.length() > 0) ptBuf.append(",");
                        ptBuf.append("{x:").append(String.format("%.1f", x))
                             .append(",y:").append(String.format("%.3f", dropPct)).append("}");
                        tabHasData = true;
                    }
                    if (!tabHasData) continue;
                    if (!firstDs) sb.append(",\n");
                    firstDs = false;
                    String clr = frColors[i % frColors.length];
                    sb.append("    {label:'Tab #").append(m.getTabIndex()).append(" Drop %',")
                      .append("showLine:true,fill:true,tension:0.25,")
                      .append("borderColor:'").append(clr).append("',")
                      .append("backgroundColor:'").append(clr).append("26',")
                      .append("pointRadius:2,pointHoverRadius:5,yAxisID:'fr',order:").append(i + 1).append(",data:[")
                      .append(ptBuf).append("]}");
                }
                // Max 1% threshold + critical 100% threshold
                sb.append(",\n")
                  .append("    {label:'Max threshold (1%)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(255,196,0,.75)',borderDash:[6,3],borderWidth:1.5,")
                  .append("backgroundColor:'transparent',pointRadius:0,yAxisID:'fr',order:99,")
                  .append("data:[{x:0,y:1},{x:").append(String.format("%.1f", xMax)).append(",y:1}]}")
                  .append(",\n")
                  .append("    {label:'Critical (100% \u2192 FAIL)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(255,45,85,.90)',borderDash:[2,2],borderWidth:2,")
                  .append("backgroundColor:'transparent',pointRadius:0,yAxisID:'fr',order:99,")
                  .append("data:[{x:0,y:100},{x:").append(String.format("%.1f", xMax)).append(",y:100}]}")
                  .append("\n  ]},\n")
                  .append("  options:makeOpts({\n")
                  .append("    interaction:{mode:'index',intersect:false},\n")
                  .append("    plugins:{\n")
                  .append("      legend:{position:'bottom',labels:{usePointStyle:true,font:{size:10}}},\n")
                  .append("      tooltip:{callbacks:{label:ctx=>{\n")
                  .append("        const p=ctx.raw,l=ctx.dataset.label||'';\n")
                  .append("        if(p&&p.y!==undefined) return `${l}: ${p.y.toFixed(2)}%  @+${p.x.toFixed(1)}s`;\n")
                  .append("        return `${l}: ${ctx.parsed.y}`;\n")
                  .append("      }}}\n")
                  .append("    },\n")
                  .append("    scales:{\n")
                  .append("      x:{type:'linear',min:0,max:tlMax,\n")
                  .append("         title:{display:true,text:'Elapsed time (s)',color:'#4e7a5e'},\n")
                  .append("         ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}},\n")
                  .append("      fr:{type:'linear',position:'left',min:0,suggestedMax:100,\n")
                  .append("          title:{display:true,text:'Drop % per sweep',color:'#ff2d55'},\n")
                  .append("          ticks:{color:'#ff2d55',font:{size:10},callback:function(v){return v.toFixed(1)+'%';}},")
                  .append("grid:{color:'#162a1e'}}\n")
                  .append("    }\n")
                  .append("  })\n")
                  .append("});\n");
            }
        }

        // ── Chart 2: Website Load + DNS Response over time ────────────────────
        if (!web.isEmpty()) {
            Map<Integer, String> webDomByTab = new LinkedHashMap<>();
            web.stream().sorted(Comparator.comparingInt(WebsiteMetrics::getTabIndex))
               .forEach(w -> webDomByTab.putIfAbsent(w.getTabIndex(), w.getDomain()));
            String[] webColors = {"#b388ff","#00e5ff","#00ff87","#ff9e40","#ff6eb4"};
            sb.append("var c2=new Chart(document.getElementById('chart-tl-web'),{\n")
              .append("  type:'scatter',\n")
              .append("  data:{datasets:[\n");
            boolean firstDs = true;
            int wci = 0;
            for (Map.Entry<Integer, String> we : webDomByTab.entrySet()) {
                int tabIdx = we.getKey();
                String domLabel = we.getValue().replace("'", "\\'");
                String clr = webColors[wci % webColors.length];
                // Page load: solid line, large colored dots (green/amber/red by speed)
                if (!firstDs) sb.append(",\n");
                firstDs = false;
                sb.append("    {label:'").append(domLabel).append(" (Load)',")
                  .append("showLine:true,fill:false,tension:0.3,borderColor:'")
                  .append(clr).append("',pointRadius:7,pointHoverRadius:10,")
                  .append("yAxisID:'web',order:").append(wci + 1).append(",data:[");
                boolean firstPt = true;
                for (WebsiteMetrics wm : web) {
                    if (wm.getTabIndex() != tabIdx) continue;
                    if (!firstPt) sb.append(",");
                    firstPt = false;
                    double x = (wm.getTimestamp() - baseTs) / 1000.0;
                    long load = wm.getPageLoadTime();
                    String pc = load < 1000 ? "'#00ff87'" : load < 3000 ? "'#ffb300'" : "'#ff2d55'";
                    sb.append("{x:").append(String.format("%.1f", x)).append(",y:").append(load)
                      .append(",ttfb:").append(wm.getTimeToFirstByte()).append(",c:").append(pc).append("}");
                }
                sb.append("],backgroundColor:function(ctx){return ctx.raw?ctx.raw.c:'").append(clr).append("';}}");
                // TTFB: dashed line, small dots, half-opacity border
                sb.append(",\n");
                sb.append("    {label:'").append(domLabel).append(" (TTFB)',")
                  .append("showLine:true,fill:false,tension:0.3,borderDash:[3,3],borderWidth:1.5,")
                  .append("borderColor:'").append(clr).append("80',backgroundColor:'").append(clr).append("80',")
                  .append("pointRadius:3,pointHoverRadius:6,")
                  .append("yAxisID:'web',order:").append(wci + 1 + webDomByTab.size()).append(",data:[");
                firstPt = true;
                for (WebsiteMetrics wm : web) {
                    if (wm.getTabIndex() != tabIdx) continue;
                    if (!firstPt) sb.append(",");
                    firstPt = false;
                    double x = (wm.getTimestamp() - baseTs) / 1000.0;
                    sb.append("{x:").append(String.format("%.1f", x)).append(",y:").append(wm.getTimeToFirstByte()).append("}");
                }
                sb.append("]}");
                wci++;
            }
            // Threshold reference lines: 1 s (fast) and 3 s (slow)
            sb.append(",\n")
              .append("    {label:'Fast (1s)',showLine:true,fill:false,")
              .append("borderColor:'rgba(0,255,135,.38)',borderDash:[6,4],borderWidth:1.5,")
              .append("pointRadius:0,yAxisID:'web',order:99,")
              .append("data:[{x:0,y:1000},{x:").append(String.format("%.1f", xMax)).append(",y:1000}]},\n")
              .append("    {label:'Slow (3s)',showLine:true,fill:false,")
              .append("borderColor:'rgba(255,179,0,.38)',borderDash:[6,4],borderWidth:1.5,")
              .append("pointRadius:0,yAxisID:'web',order:99,")
              .append("data:[{x:0,y:3000},{x:").append(String.format("%.1f", xMax)).append(",y:3000}]}");
            // μ and Critical (μ+5σ) reference lines for Website Load over Time.
            // Gate uses robust stats; mean label uses arithmetic mean.
            if (webTlGateStats != null) {
                double webTlMean     = webTlLoadStats != null ? webTlLoadStats.mean : webTlGateStats.mean;
                double webTlCritical = webTlGateStats.ucl(5.0);
                sb.append(",\n")
                  .append("    {label:'\u03bc=avg (").append(String.format("%.0f", webTlMean)).append("ms)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(78,122,94,.65)',borderDash:[4,4],borderWidth:1.5,")
                  .append("pointRadius:0,yAxisID:'web',order:100,")
                  .append("data:[{x:0,y:").append(String.format("%.0f", webTlMean))
                  .append("},{x:").append(String.format("%.1f", xMax)).append(",y:").append(String.format("%.0f", webTlMean)).append("}]},\n")
                  .append("    {label:'Critical (\u03bc+5\u03c3=").append(String.format("%.0f", webTlCritical)).append("ms)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(255,45,85,.9)',borderDash:[2,2],borderWidth:2,")
                  .append("pointRadius:0,yAxisID:'web',order:101,")
                  .append("data:[{x:0,y:").append(String.format("%.0f", webTlCritical))
                  .append("},{x:").append(String.format("%.1f", xMax)).append(",y:").append(String.format("%.0f", webTlCritical)).append("}]}");
            }
              sb.append("\n  ]},\n")
              .append("  options:makeOpts({\n")
              .append("    interaction:{mode:'index',intersect:false},\n")
              .append("    plugins:{\n")
              .append("      legend:{position:'bottom',labels:{usePointStyle:true,font:{size:10},")
              .append("filter:function(item){return !item.text.startsWith('Fast') && !item.text.startsWith('Slow');}}},\n")
              .append("      tooltip:{callbacks:{label:ctx=>{\n")
              .append("        const p=ctx.raw,l=ctx.dataset.label||'';\n")
              .append("        if(!p||p.y===undefined) return '';\n")
              .append("        if(l.endsWith('(Load)')) return `${l}: ${p.y}ms  TTFB ${p.ttfb}ms  @+${p.x.toFixed(1)}s`;\n")
              .append("        if(l.endsWith('(TTFB)')) return `${l}: ${p.y}ms  @+${p.x.toFixed(1)}s`;\n")
              .append("        return `${l}: ${ctx.parsed.y}ms`;\n")
              .append("      }}}\n")
              .append("    },\n");
            // Website cluster band on timeline (x = elapsed seconds)
            if (webSpikeAnalysis.hasCluster && webSpikeAnalysis.clusterWindowStartMs > 0 && baseTs > 0) {
                double wcStart = (webSpikeAnalysis.clusterWindowStartMs - baseTs) / 1000.0;
                double wcEnd   = (webSpikeAnalysis.clusterWindowEndMs   - baseTs) / 1000.0;
                sb.append("    _clusterBands:[{xStart:").append(String.format("%.1f", wcStart))
                  .append(",xEnd:").append(String.format("%.1f", wcEnd))
                  .append(",color:'rgba(255,100,0,0.10)'}],\n");
            }
              sb.append("    scales:{\n")
              .append("      x:{type:'linear',min:0,max:tlMax,\n")
              .append("         title:{display:true,text:'Elapsed time (s)',color:'#4e7a5e'},\n")
              .append("         ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}},\n")
              .append("      web:{type:'linear',position:'left',min:0,suggestedMax:")
              .append(maxTlWebMs)
              .append(",\n           title:{display:true,text:'Load Time (ms)',color:'#b388ff'},\n")
              .append("           ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}}\n")
              .append("    }\n")
              .append("  })\n")
              .append("});\n");
        }

        // ── DNS Response over Time — individual queries per domain × type ─────
        if (!dns.isEmpty()) {
            // Plot each individual DNS query at its exact elapsed-second timestamp
            // (no 5-second bucketing) so every data point is visible and no gaps
            // appear when timeout rounds stretch the time axis.
            Map<String, List<DnsResult>> dnsGroups = new LinkedHashMap<>();
            dns.stream()
               .sorted(Comparator.comparing(DnsResult::getDomain)
                                 .thenComparing(DnsResult::getRecordType)
                                 .thenComparingLong(DnsResult::getTimestamp))
               .forEach(r -> dnsGroups.computeIfAbsent(
                   r.getDomain() + " (" + r.getRecordType() + ")",
                   k -> new ArrayList<>()).add(r));

            // y-axis ceiling from successful queries only
            long maxTlDnsMs = dns.stream()
                .filter(DnsResult::isSuccess)
                .mapToLong(DnsResult::getResponseTimeMs).max().orElse(200L);
            // Pre-compute DNS stats for μ and Critical (μ+5σ) reference lines
            List<Double> dnsTlRts = dns.stream()
                .filter(DnsResult::isSuccess)
                .map(r -> (double) r.getResponseTimeMs())
                .collect(Collectors.toList());
            StatisticsEngine.Stats dnsTlStats       = StatisticsEngine.compute(dnsTlRts);
            StatisticsEngine.Stats dnsTlRobustStats = StatisticsEngine.computeRobust(dnsTlRts);
            StatisticsEngine.Stats dnsTlGateStats = dnsTlRobustStats != null
                ? dnsTlRobustStats : dnsTlStats;
            if (dnsTlGateStats != null) maxTlDnsMs = Math.max(maxTlDnsMs, (long) dnsTlGateStats.ucl(5.0));
            // Ensure the hard threshold line is visible on the y-axis
            maxTlDnsMs = Math.max(maxTlDnsMs, SpikeAnalyzer.DNS_HARD_THRESHOLD_MS);

            String[] dnsColors = {"#00e5ff","#00ff87","#ffb300","#e879f9","#ff6eb4",
                                   "#26c6da","#aeea00","#ff9e40","#ce93d8","#ff6eb4"};
            sb.append("var c3=new Chart(document.getElementById('chart-tl-dns'),{\n")
              .append("  type:'scatter',\n")
              .append("  data:{datasets:[\n");
            boolean firstDs = true;
            int di = 0;
            for (Map.Entry<String, List<DnsResult>> dnsEntry : dnsGroups.entrySet()) {
                List<DnsResult> rows = dnsEntry.getValue();
                if (rows.isEmpty()) continue;
                if (!firstDs) sb.append(",\n");
                firstDs = false;
                String lc = dnsColors[di % dnsColors.length];
                String domLabel = dnsEntry.getKey().replace("'", "\\'");
                sb.append("    {label:'").append(domLabel).append("',")
                  .append("showLine:true,fill:false,tension:0.3,borderColor:'").append(lc).append("',")
                  .append("pointStyle:'rectRot',pointRadius:6,pointHoverRadius:9,")
                  .append("yAxisID:'dns',order:").append(di + 1).append(",data:[");
                boolean firstPt = true;
                for (DnsResult r : rows) {
                    if (!firstPt) sb.append(",");
                    firstPt = false;
                    double xSec = (r.getTimestamp() - baseTs) / 1000.0;
                    long rt = r.getResponseTimeMs();
                    boolean ok = r.isSuccess();
                    boolean isCapped = !ok && rt > maxTlDnsMs;
                    long displayRt = isCapped ? maxTlDnsMs : rt;
                    String pc = !ok ? "'#ff2d55'" : rt < 50 ? "'#00ff87'" : rt < 200 ? "'#ffb300'" : "'#ff2d55'";
                    sb.append("{x:").append(String.format("%.1f", xSec)).append(",y:").append(displayRt)
                      .append(",ok:").append(ok).append(",c:").append(pc);
                    if (isCapped) sb.append(",a:").append(rt);
                    sb.append("}");
                }
                sb.append("],backgroundColor:function(ctx){return ctx.raw?ctx.raw.c:'").append(lc).append("';}}");
                di++;
            }
            sb.append(",\n")
              .append("    {label:'Healthy (<=50ms)',showLine:true,fill:false,")
              .append("borderColor:'rgba(0,255,135,.4)',borderDash:[5,4],borderWidth:1.5,")
              .append("pointRadius:0,yAxisID:'dns',order:99,")
              .append("data:[{x:0,y:50},{x:").append(String.format("%.1f", xMax)).append(",y:50}]},\n")
              .append("    {label:'Degraded (>200ms)',showLine:true,fill:false,")
              .append("borderColor:'rgba(255,179,0,.42)',borderDash:[5,4],borderWidth:1.5,")
              .append("pointRadius:0,yAxisID:'dns',order:99,")
              .append("data:[{x:0,y:200},{x:").append(String.format("%.1f", xMax)).append(",y:200}]}");
            // μ and Outlier (μ+5σ) reference lines for DNS Response over Time.
            if (dnsTlGateStats != null) {
                double dnsTlMean     = dnsTlStats != null ? dnsTlStats.mean : dnsTlGateStats.mean;
                double dnsTlCritical = dnsTlGateStats.ucl(5.0);
                sb.append(",\n")
                  .append("    {label:'\u03bc=avg (").append(String.format("%.0f", dnsTlMean)).append("ms)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(78,122,94,.65)',borderDash:[4,4],borderWidth:1.5,")
                  .append("pointRadius:0,yAxisID:'dns',order:100,")
                  .append("data:[{x:0,y:").append(String.format("%.0f", dnsTlMean))
                  .append("},{x:").append(String.format("%.1f", xMax)).append(",y:").append(String.format("%.0f", dnsTlMean)).append("}]},\n")
                  .append("    {label:'Outlier (\u03bc+5\u03c3=").append(String.format("%.0f", dnsTlCritical)).append("ms)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(255,45,85,.9)',borderDash:[2,2],borderWidth:2,")
                  .append("pointRadius:0,yAxisID:'dns',order:101,")
                  .append("data:[{x:0,y:").append(String.format("%.0f", dnsTlCritical))
                  .append("},{x:").append(String.format("%.1f", xMax)).append(",y:").append(String.format("%.0f", dnsTlCritical)).append("}]},\n");
                // Hard threshold line — only spikes above this count toward FAIL
                long htMs = SpikeAnalyzer.DNS_HARD_THRESHOLD_MS;
                sb.append("    {label:'Hard threshold (").append(htMs).append("ms \u2014 FAIL gate)',showLine:true,fill:false,")
                  .append("borderColor:'rgba(255,100,0,.7)',borderDash:[6,3],borderWidth:2,")
                  .append("pointRadius:0,yAxisID:'dns',order:102,")
                  .append("data:[{x:0,y:").append(htMs)
                  .append("},{x:").append(String.format("%.1f", xMax)).append(",y:").append(htMs).append("}]}");
            }
            sb.append("\n  ]},\n")
              .append("  options:makeOpts({\n")
              .append("    interaction:{mode:'nearest',intersect:true},\n")
              .append("    plugins:{\n")
              .append("      legend:{position:'bottom',labels:{usePointStyle:true,font:{size:10},")
              .append("filter:function(item){return !item.text.startsWith('Healthy') && !item.text.startsWith('Degraded');}}},\n")
              .append("      tooltip:{callbacks:{label:ctx=>{\n")
              .append("        const p=ctx.raw,l=ctx.dataset.label||'';\n")
              .append("        if(!p||p.y===undefined) return '';\n")
              .append("        const rt=p.a!==undefined?p.a:p.y;\n")
              .append("        return `${l}: ${rt}ms${p.ok?'':' FAIL'} @+${p.x.toFixed(1)}s`;\n")
              .append("      }}}\n")
              .append("    },\n");
            // DNS cluster band on timeline (x = elapsed seconds)
            if (dnsSpikeAnalysis.hasCluster && dnsSpikeAnalysis.clusterWindowStartMs > 0 && baseTs > 0) {
                double dcStart = (dnsSpikeAnalysis.clusterWindowStartMs - baseTs) / 1000.0;
                double dcEnd   = (dnsSpikeAnalysis.clusterWindowEndMs   - baseTs) / 1000.0;
                sb.append("    _clusterBands:[{xStart:").append(String.format("%.1f", dcStart))
                  .append(",xEnd:").append(String.format("%.1f", dcEnd))
                  .append(",color:'rgba(255,100,0,0.10)'}],\n");
            }
            sb.append("    scales:{\n")
              .append("      x:{type:'linear',min:0,max:tlMax,\n")
              .append("         title:{display:true,text:'Elapsed time (s)',color:'#4e7a5e'},\n")
              .append("         ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}},\n")
              .append("      dns:{type:'linear',position:'left',min:0,suggestedMax:")
              .append(maxTlDnsMs)
              .append(",\n           title:{display:true,text:'DNS RT (ms)',color:'#00e5ff'},\n")
              .append("           ticks:{color:'#4e7a5e',font:{size:10}},grid:{color:'#162a1e'}}\n")
              .append("    }\n")
              .append("  })\n")
              .append("});\n");
        }

        // ── Synchronized crosshair across all timeline panels ─────────────────
        {
            List<String> tlVars = new ArrayList<>();
            if (hasYt) { tlVars.add("c1"); tlVars.add("c1b"); }
            // c1c (frames) may not be emitted if no frame data is available — guard at runtime
            if (hasYt) tlVars.add("typeof c1c!=='undefined'?c1c:null");
            if (!web.isEmpty()) tlVars.add("c2");
            if (!dns.isEmpty()) tlVars.add("c3");
            if (tlVars.size() > 1) {
                sb.append("var tlCharts=[").append(String.join(",", tlVars))
                  .append("].filter(function(c){return c!=null;});\n")
                  .append("function tlSetCross(v){tlCharts.forEach(function(c){c._xHair=v;c.update('none');})}\n");
                sb.append("function tlBind(id){\n");
                sb.append("  var cvs=document.getElementById(id); if(!cvs) return;\n");
                sb.append("  cvs.addEventListener('mousemove',function(e){\n");
                sb.append("    var ch=tlCharts.find(function(c){return c.canvas===cvs;});\n");
                sb.append("    if(!ch||!ch.scales||!ch.scales.x) return;\n");
                sb.append("    var r=cvs.getBoundingClientRect();\n");
                sb.append("    tlSetCross(ch.scales.x.getValueForPixel(e.clientX-r.left));\n");
                sb.append("  });\n");
                sb.append("  cvs.addEventListener('mouseleave',function(){\n");
                sb.append("    tlCharts.forEach(function(c){c._xHair=null;c.update('none');});\n");
                sb.append("  });\n");
                sb.append("}\n");
                if (hasYt) {
                    sb.append("tlBind('chart-tl-cs');\n");
                    sb.append("tlBind('chart-tl-buf');\n");
                    sb.append("tlBind('chart-tl-frames');\n");
                }
                if (!web.isEmpty()) sb.append("tlBind('chart-tl-web');\n");
                if (!dns.isEmpty()) sb.append("tlBind('chart-tl-dns');\n");
            }
        }

        sb.append("})();\n");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates Chart.js initialisation code for the DNS per-iteration line chart
     * ({@code chart-dns-iter}). One dataset per domain × record-type pair, ordered by
     * query timestamp so the X-axis represents round number.
     * Normal points (&lt; 50 ms) are emitted as bare numbers for category-index mapping;
     * annotated points (spikes, elevated, failed) use {@code {y:VALUE,...}} objects.
     * The {@code pointBackgroundColor} callback returns the dataset line colour for
     * normal points so they are always visible on the dark background.
     */
    private void buildDnsIterChart(StringBuilder sb, List<DnsResult> dns,
                                    SpikeAnalyzer.Result spikeAnalysis) {
        if (dns.isEmpty()) return;

        // Group results in temporal order, key = "domain (TYPE)"
        Map<String, List<DnsResult>> byKey = new LinkedHashMap<>();
        dns.stream()
           .sorted(Comparator.comparingLong(DnsResult::getTimestamp))
           .forEach(r -> byKey.computeIfAbsent(
               r.getDomain() + " (" + r.getRecordType() + ")",
               k -> new ArrayList<>()).add(r));

        int maxRounds = byKey.values().stream().mapToInt(List::size).max().orElse(0);
        if (maxRounds == 0) return;

        // Compute y-axis ceiling from SUCCESSFUL query response times only.
        long maxResponseMs = dns.stream()
            .filter(DnsResult::isSuccess)
            .mapToLong(DnsResult::getResponseTimeMs).max().orElse(200L);

        List<String> roundLabels = new ArrayList<>();
        for (int i = 1; i <= maxRounds; i++) roundLabels.add("R" + i);

        // Pre-compute overall stats across all successful queries for μ and μ+5σ lines.
        List<Double> allSuccessRts = dns.stream().filter(DnsResult::isSuccess)
            .map(r -> (double) r.getResponseTimeMs()).collect(Collectors.toList());
        StatisticsEngine.Stats iterOverallStats       = StatisticsEngine.compute(allSuccessRts);
        StatisticsEngine.Stats iterOverallRobustStats = StatisticsEngine.computeRobust(allSuccessRts);
        StatisticsEngine.Stats iterGateStats = iterOverallRobustStats != null
            ? iterOverallRobustStats : iterOverallStats;
        if (iterGateStats != null)
            maxResponseMs = Math.max(maxResponseMs, (long) iterGateStats.ucl(5.0));
        // Ensure the hard threshold line is visible
        maxResponseMs = Math.max(maxResponseMs, SpikeAnalyzer.DNS_HARD_THRESHOLD_MS);

        // Compute cluster band as round-index range across all domain×type groups
        String clusterBandOpt = "[]";
        if (spikeAnalysis.hasCluster && spikeAnalysis.clusterWindowStartMs > 0) {
            int bandStart = Integer.MAX_VALUE, bandEnd = Integer.MIN_VALUE;
            for (List<DnsResult> rows : byKey.values()) {
                for (int i = 0; i < rows.size(); i++) {
                    long ts = rows.get(i).getTimestamp();
                    if (ts >= spikeAnalysis.clusterWindowStartMs
                            && ts <= spikeAnalysis.clusterWindowEndMs) {
                        bandStart = Math.min(bandStart, i);
                        bandEnd   = Math.max(bandEnd,   i);
                    }
                }
            }
            if (bandStart != Integer.MAX_VALUE) {
                clusterBandOpt = "[{xStart:" + bandStart + ",xEnd:" + bandEnd
                    + ",color:'rgba(255,100,0,0.10)'}]";
            }
        }

        String[] palette = {
            "#00e5ff","#00ff87","#ffb300","#b388ff","#ff2d55",
            "#26c6da","#aeea00","#ff9e40","#ce93d8","#ff6eb4"
        };

        sb.append("(function(){\n");
        sb.append("new Chart(document.getElementById('chart-dns-iter'), {\n")
          .append("  type: 'line',\n")
          .append("  data: {\n")
          .append("    labels: ").append(toJsArray(roundLabels)).append(",\n")
          .append("    datasets: [\n");

        int ci = 0;
        boolean first = true;
        for (Map.Entry<String, List<DnsResult>> e : byKey.entrySet()) {
            List<DnsResult> rows = e.getValue();
            String clr = palette[ci % palette.length];
            StringBuilder arr = new StringBuilder("[");
            for (DnsResult r : rows) {
                boolean isSpike   = spikeAnalysis.isSpikeAt(r.getTimestamp());
                boolean isCluster = isSpike && spikeAnalysis.hasCluster
                    && r.getTimestamp() >= spikeAnalysis.clusterWindowStartMs
                    && r.getTimestamp() <= spikeAnalysis.clusterWindowEndMs;

                if (r.isSuccess()) {
                    long rt = r.getResponseTimeMs();
                    // Normal points (< 50ms, no spike) emitted as bare numbers so
                    // Chart.js maps them by array index to the category labels.
                    // Annotated points use {y:VALUE,...} objects for spike/elevated colouring.
                    if (isCluster) {
                        arr.append("{y:").append(rt).append(",c:1}");
                    } else if (isSpike) {
                        arr.append("{y:").append(rt).append(",s:1}");
                    } else if (rt >= 200) {
                        arr.append("{y:").append(rt).append(",h:2}");
                    } else if (rt >= 50) {
                        arr.append("{y:").append(rt).append(",h:1}");
                    } else {
                        arr.append(rt);
                    }
                } else {
                    long rt = r.getResponseTimeMs();
                    boolean isCapped = rt > maxResponseMs;
                    arr.append("{y:").append(isCapped ? maxResponseMs : rt).append(",f:1");
                    if (isCapped) arr.append(",a:").append(rt);
                    if (isCluster) arr.append(",c:1");
                    arr.append("}");
                }
                arr.append(",");
            }
            // Pad to maxRounds with nulls for groups that ended earlier
            for (int i = rows.size(); i < maxRounds; i++) arr.append("null,");
            if (arr.length() > 1) arr.setLength(arr.length() - 1);
            arr.append("]");

            if (!first) sb.append(",\n");
            sb.append("      { label: '").append(e.getKey().replace("'", "\\'")).append("',\n")
              .append("        data: ").append(arr).append(",\n")
              .append("        borderColor: '").append(clr).append("',\n")
              .append("        backgroundColor: 'transparent', tension: 0.3,\n")
              // Normal bare-number points get the dataset line colour so they are
              // visible on the dark background (the old code returned 'transparent').
              .append("        pointBackgroundColor: function(ctx){\n")
              .append("          const r=ctx.raw;\n")
              .append("          if(r&&typeof r==='object') {\n")
              .append("            if(r.c) return 'rgba(255,100,0,0.95)';\n")
              .append("            if(r.f) return 'rgba(255,45,85,0.9)';\n")
              .append("            if(r.s) return 'rgba(255,180,0,0.85)';\n")
              .append("            if(r.h===2) return 'rgba(255,100,0,0.55)';\n")
              .append("            if(r.h===1) return 'rgba(255,179,0,0.45)';\n")
              .append("          }\n")
              .append("          return '").append(clr).append("';\n")
              .append("        },\n")
              .append("        pointBorderColor: function(ctx){\n")
              .append("          const r=ctx.raw;\n")
              .append("          if(r&&typeof r==='object') {\n")
              .append("            if(r.c) return '#ff6400';\n")
              .append("            if(r.f) return '#ff2d55';\n")
              .append("            if(r.s) return '#ffb300';\n")
              .append("            if(r.h===2) return 'rgba(255,100,0,0.75)';\n")
              .append("            if(r.h===1) return 'rgba(255,179,0,0.65)';\n")
              .append("          }\n")
              .append("          return '").append(clr).append("';\n")
              .append("        },\n")
              .append("        pointRadius: function(ctx){\n")
              .append("          const r=ctx.raw;\n")
              .append("          if(r&&typeof r==='object'&&(r.c||r.f||r.s)) return 6;\n")
              .append("          if(r&&typeof r==='object'&&r.h) return 5;\n")
              .append("          return 4;\n")
              .append("        },\n")
              .append("        pointHoverRadius: function(ctx){\n")
              .append("          const r=ctx.raw;\n")
              .append("          if(r&&typeof r==='object'&&(r.c||r.f||r.s)) return 9;\n")
              .append("          if(r&&typeof r==='object'&&r.h) return 8;\n")
              .append("          return 7;\n")
              .append("        },\n")
              .append("        spanGaps: true }");
            first = false;
            ci++;
        }

        // μ and μ+5σ reference lines
        if (iterGateStats != null) {
            double iterMean     = iterOverallStats != null ? iterOverallStats.mean : iterGateStats.mean;
            double iterCritical = iterGateStats.ucl(5.0);
            List<Double> meanConstLine = roundLabels.stream().map(l -> iterMean).collect(Collectors.toList());
            List<Double> critConstLine = roundLabels.stream().map(l -> iterCritical).collect(Collectors.toList());
            if (!first) sb.append(",\n");
            sb.append("      { label: '\u03bc=avg (").append(String.format("%.0f", iterMean)).append(" ms)',\n")
              .append("        data: ").append(toJsDoubleArray(meanConstLine)).append(",\n")
              .append("        borderColor: 'rgba(78,122,94,.65)', borderDash:[4,4], borderWidth:1.5,\n")
              .append("        backgroundColor:'transparent', pointRadius:0, spanGaps:true, tension:0 },\n");
            sb.append("      { label: '\u03bc+5\u03c3 Outlier (").append(String.format("%.0f", iterCritical)).append(" ms)',\n")
              .append("        data: ").append(toJsDoubleArray(critConstLine)).append(",\n")
              .append("        borderColor: 'rgba(255,45,85,.9)', borderDash:[2,2], borderWidth:2,\n")
              .append("        backgroundColor:'transparent', pointRadius:0, spanGaps:true, tension:0 },\n");
            // Hard failure threshold — only spikes above this cause FAIL
            long hardThreshold = SpikeAnalyzer.DNS_HARD_THRESHOLD_MS;
            List<Double> hardLine = roundLabels.stream().map(l -> (double) hardThreshold).collect(Collectors.toList());
            sb.append("      { label: 'Hard threshold (").append(hardThreshold).append(" ms — FAIL gate)',\n")
              .append("        data: ").append(toJsDoubleArray(hardLine)).append(",\n")
              .append("        borderColor: 'rgba(255,100,0,.7)', borderDash:[6,3], borderWidth:2,\n")
              .append("        backgroundColor:'transparent', pointRadius:0, spanGaps:true, tension:0 }");
        }

        // Add 15% headroom so topmost points and their value labels are not clipped.
        // Also ensure the hard threshold line is visible if no data exceeds it.
        long yMaxBase = Math.max(maxResponseMs, SpikeAnalyzer.DNS_HARD_THRESHOLD_MS);
        long suggestedYMax = yMaxBase + Math.max(20, (long)(yMaxBase * 0.15));
        // Determine the critical line value (if available) for the aboveCritLabel plugin.
        String critLineOpt = iterGateStats != null
            ? String.format("%.2f", iterGateStats.ucl(5.0)) : "null";

        sb.append("\n    ]\n")
          .append("  },\n")
          .append("  options: makeOpts({\n")
          .append("    _clusterBands: ").append(clusterBandOpt).append(",\n")
          .append("    _criticalLine: ").append(critLineOpt).append(",\n")
          .append("    plugins: {\n")
          .append("      legend: { position: 'bottom', labels: { boxWidth:10, font:{size:10} } },\n")
          .append("      tooltip: { callbacks: { label: ctx => {\n")
          .append("        const r=ctx.raw,l=ctx.dataset.label||'';\n")
          .append("        if(!r||typeof r!=='object') return `${l}: ${ctx.parsed.y}ms`;\n")
          .append("        const v=r.a!==undefined?r.a:r.y;\n")
          .append("        const flag=r.c?'[CLUSTER SPIKE]':r.f?'\u26a0 FAIL':r.s?'\u26a1 SPIKE':r.h===2?'\u25b2 HIGH':r.h===1?'\u25b2 ELEVATED':'';\n")
          .append("        return `${l}: ${v}ms${flag?'  '+flag:''}`;\n")
          .append("      }}}\n")
          .append("    },\n")
          .append("    scales: { y: {\n")
          .append("      title: { display:true, text:'Response time (ms)', color:'#4e7a5e' },\n")
          .append("      min: 0,\n")
          .append("      suggestedMax: ").append(suggestedYMax).append("\n")
          .append("    }}\n")
          .append("  })\n")
          .append("});\n")
          .append("})();\n");
    }


    /**
     * Counts the number of warm (cycle ≥ 2) website measurements whose page-load
     * time exceeds the configured warm-load threshold for that domain.
     * Used in the page-header badge and executive-summary verdict computation.
     *
     * @param metrics all collected website metrics
     * @return number of warm cycles that exceeded their page-load threshold
     */
    private long countWebFail(List<WebsiteMetrics> metrics) {
        return metrics.stream()
            .filter(m -> m.getRefreshCycle() >= 2
                && m.getPageLoadTime() > thresholdsFor.apply(m.getDomain()).pageLoadWarmMs)
            .count();
    }

    /**
     * Serialises a list of strings to a JavaScript array literal with single-quoted elements.
     * Single quotes inside values are escaped with a backslash.
     *
     * @param items the strings to serialise
     * @return e.g. {@code "['a','b\'c']"}
     */
    private String toJsArray(List<String> items) {
        return "[" + items.stream()
            .map(s -> "'" + s.replace("'", "\\'") + "'")
            .collect(Collectors.joining(",")) + "]";
    }

    /**
     * Serialises a list of {@code long} values to a JavaScript array literal.
     *
     * @param items the values to serialise
     * @return e.g. {@code "[100,200,300]"}
     */
    private String toJsLongArray(List<Long> items) {
        return "[" + items.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
    }

    /**
     * Serialises a list of {@code double} values to a JavaScript array literal
     * rounded to two decimal places.
     *
     * @param items the values to serialise
     * @return e.g. {@code "[1.25,3.70]"}
     */
    private String toJsDoubleArray(List<Double> items) {
        return "[" + items.stream()
            .map(d -> String.format("%.2f", d))
            .collect(Collectors.joining(",")) + "]";
    }

    /** Serialises a list of Double (with potential nulls) to a JS array using {@code null} for missing values (Chart.js spanGaps-compatible). */
    private String toJsNullableDoubleArray(List<Double> items) {
        return "[" + items.stream()
            .map(d -> d == null ? "null" : String.format("%.3f", d))
            .collect(Collectors.joining(",")) + "]";
    }

    /** Serialises a list of Long (with potential nulls) to a JS array using {@code null} for missing values (Chart.js spanGaps-compatible). */
    private String toJsNullableLongArray(List<Long> items) {
        return "[" + items.stream()
            .map(v -> v == null ? "null" : v.toString())
            .collect(Collectors.joining(",")) + "]";
    }

    /**
     * HTML-escapes a string for safe inline inclusion in attribute values and
     * text content. {@code null} is treated as an empty string.
     *
     * @param s the raw string to escape
     * @return a string safe for inclusion in HTML, never {@code null}
     */
    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
