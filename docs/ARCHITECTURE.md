# Architecture — net-perf-monitor

> Read `PROJECT_CONTEXT.md` first. This file explains how the code is organised.

---

## Thread Model

```
main thread
  └► TestConfig.load(args)            # parse CLI + monitor-targets.properties + website-thresholds.properties
  └─► ExecutorService (fixed pool)
        ├─► YouTubePerformanceTester    # 1 Chrome instance, N YouTube tabs
        ├─► WebsiteTester              # 1 Chrome instance, M website tabs
        └─► DnsMonitor                 # raw UDP → 8.8.8.8 (no browser)
  └─► (await Future.get on all three)
  └─► JsonReporter.print(...)          # stdout JSON
  └─► HtmlReporter.write(...)          # net_performance_report.html
```

- YouTube + Website probes use a shared `CountDownLatch startLatch` so both Chrome instances start measurements at the same wall-clock moment.
- DNS probe uses a separate `CountDownLatch dnsStopLatch` — it runs continuously and is signalled to stop after the browser probes finish.
- Thread pool size = number of active probes (1–3).

---

## Class Map

### Entry & Orchestration

| Class | Role |
|---|---|
| `Main` | Entry point. Parses config, wires latches, submits probes to executor, collects results, invokes reporters. |
| `TestConfig` | Loads `monitor-targets.properties` + `website-thresholds.properties` + CLI args. Exposes `youtubeUrls`, `websiteUrls`, `dnsDomains`, `durationSeconds`, `mode`, `youtubeCount`, `reportIntervalSeconds`, `WebsiteThresholds`. |

### Probes (Runnable workers)

| Class | Role |
|---|---|
| `YouTubePerformanceTester` | Opens one Chrome. Opens N YouTube tabs **in parallel** (Phase 1: trigger all navigations simultaneously via non-blocking JS; Phase 2: poll until every tab reaches `readyState='complete'`; Phase 3: initialise each tab — consent dialog, ad skip, force-play, quality, Stats for Nerds). Every 5 s sweeps each tab: reads Navigation Timing API, `HTMLVideoElement` state, bandwidth via XHR intercept and Stats-for-Nerds. Collects `VideoMetrics` + `NetworkSample` lists. Counts down `startLatch` at measurement start. Final metrics collection loop is per-tab resilient: browser failures and interrupted-thread state are caught per tab; sweep-derived SFN (`synthesizeSfnFromSweeps`) ensures SFN checks still run even when Chrome crashes mid-run. |
| `WebsiteTester` | Opens one Chrome. Opens M website tabs. Every 5 s navigates each tab to its URL, reads Navigation Timing API, then probes IPv4/IPv6 TCP reachability to port 443. Collects `WebsiteMetrics` list per cycle. |
| `DnsMonitor` | No browser. Sends `A` + `AAAA` UDP queries to 8.8.8.8 for each configured domain. Waits on `startLatch`, then loops until `dnsStopLatch` is counted down. Appends `DnsResult` entries to a shared `CopyOnWriteArrayList`. |

### Data / Domain Classes

| Class | What it holds |
|---|---|
| `VideoMetrics` | Single YouTube tab snapshot: page-load timing, `HTMLVideoElement` state (readyState, networkState, bufferedSeconds, currentTime), quality label, errorMessage. |
| `NetworkSample` | One bandwidth sweep for one YouTube tab: segmentBytes, bandwidthKBps, videoCurrentTime, videoBuffered, qualityLabel, adPlaying. |
| `StatsForNerdsData` | YouTube "Stats for Nerds" values parsed from the overlay: connection speed (Kbps), resolution, codec, dropped frames, etc. |
| `WebsiteMetrics` | Single website refresh cycle: URL, domain, tabIndex, refreshCycle, Navigation Timing fields, IPv4/IPv6 reachability + addresses + connect times, success flag, errorMessage. |
| `DnsResult` | Single DNS query result: recordType (A/AAAA), domain, timestamp, responseTimeMs, resolvedAddresses, success, errorMessage. |
| `CheckResult` | Immutable result of one quality check: checkName, expected criterion string, actual value string, passed boolean. |
| `VideoVerdict` | Wraps a `VideoMetrics` + `List<CheckResult>`. Computes `isPassed()`, `passCount()`, `failCount()`. |
| `WebsiteThresholds` | Four threshold longs: `pageLoadWarmMs`, `pageLoadColdMs`, `ttfbWarmMs`, `ttfbColdMs`. Static `DEFAULT` constant. Per-site overrides configured in `website-thresholds.properties`. |

### Configuration & Constants

| Class | Role |
|---|---|
| `BrowserSelectors` | Loads **all CSS selectors and DOM element IDs** used by `YouTubePerformanceTester` from `browser-selectors.properties`. Exposes public static String constants (`CONSENT_DIALOG_CSS`, `SKIP_AD_CSS`, `SFN_PANEL_CSS`, `MOVIE_PLAYER_ID`, etc.) and a `toJsArray()` helper for injecting selector arrays into JavaScript strings. Update `browser-selectors.properties` when YouTube changes its DOM — no Java recompile needed. |

### Evaluation & Statistics

| Class | Role |
|---|---|
| `PerformanceEvaluator` | Takes a `VideoMetrics` snapshot and returns a `VideoVerdict`. Runs up to 10 quality checks: 5 core (TTFB, Page Load, Buffer Depth, Avg BW, Quality Stability) + up to 3 Stats-for-Nerds (Connection Speed, Buffer Health, Resolution Match) + up to 2 statistical (Buffer Depth Consistency, Catastrophic Frame Drop). Applies cold (tab 1) vs warm (tabs 2+) thresholds. All pass/fail thresholds loaded from `youtube-thresholds.properties`. SFN checks run whenever `VideoMetrics.getSfnData().isAvailable()` — including when SFN was synthesized from sweep data. SFN Buffer Health FAILs only when `youtube.eval.sfn.buffer_health.consecutive_zero_fail` (default 2) or more **consecutive** sweeps register 0 s; a single isolated 0 s reading is treated as a transient glitch. |
| `StatisticsEngine` | Computes descriptive statistics (mean, stddev, p50, p95) for a `List<Long>`. Classifies individual values as NORMAL / ELEVATED / HIGH / CRITICAL. Uses Bessel's correction. Sample-size reliability gates loaded from `statistics.properties`. |
| `SpikeAnalyzer` | Shared spike-pattern analyser. 5-step formula: (1) identify spikes (metric > μ+5σ), (2) **hard-threshold gate** — only spikes also exceeding a hard threshold count as "critical" for verdict logic (DNS: `spike.dns.hard_threshold.ms` default 500 ms; website: per-site page-load threshold when `spike.website.use_pageload_threshold=true`), (3) cluster burst on critical spikes only, (4) periodicity on critical spikes, (5) rate check. All spikes are still recorded for chart rendering via `isSpikeAt(long)`. Spikes below the hard threshold are flagged/annotated but never cause FAIL. DNS uses `CLUSTER_THRESHOLD_DNS` (≥ 7); website uses `CLUSTER_THRESHOLD` (≥ 5). |

### Reporting

| Class | Role |
|---|---|
| `JsonReporter` | Prints a compact JSON snapshot to stdout. Merges YouTube `VideoMetrics`, website `WebsiteMetrics`, and DNS `DnsResult` lists. Supports numbered intermediate snapshots plus a final snapshot. |
| `HtmlReporter` | Generates `net_performance_report.html`. Self-contained with inline Chart.js. Charts: YouTube BW + buffer line chart, TTFB/page-load bar chart, website per-domain line chart (μ+5σ outlier gate drawn using **robust/MAD-based stats** matching the summary table — not regular σ — so the gate stays visible even when an outlier inflates σ; amber spike points, shaded cluster band), DNS avg-latency bar chart + per-iteration line chart (amber/orange-red spike + cluster points, shaded cluster band; failed queries shown as red ⚠ points; **all data points are emitted as objects with explicit type tags** so the `pointBackgroundColor` callback gives every point — including normal/healthy ones — a visible fill colour matching the dataset line colour; **elevated points ≥50 ms are also coloured using the same 50 ms/200 ms absolute thresholds as the timeline chart** — dim amber for 50–199 ms, dim orange for ≥200 ms non-spike — so both DNS charts highlight the same visually elevated values). DNS "over time" timeline plots **individual queries at their exact elapsed-second timestamps** (no 5-second bucketing) so every data point is visible even when timeout rounds stretch the time axis. Event Timeline includes μ and Critical(μ+5σ) lines plus cluster bands for Website Load and DNS Response panels. Custom `clusterBandPlugin` (Chart.js plugin registered inline) draws shaded background over the cluster window on both categorical and linear x-axes. Custom `aboveCritPlugin` draws ms-value labels above data points that exceed the μ+5σ critical threshold in the DNS per-iteration chart (activated via `_criticalLine` chart option); the chart's Y-axis also adds 15 % headroom above the max data point so labels are not clipped. Executive summary cards for DNS and Website omit the "Pass formula" note; the spike-count row colour is determined by spike-rate (< 2 % neutral, < 5 % amber, ≥ 5 % red) independent of overall verdict. |
| `PerformanceReporter` | Console text report: formats check rows as PASS/FAIL table, summary verdicts per tab. Also writes YouTube-only `youtube_performance_report.json` and the combined `target/net_performance_report.csv` (all three probes). |
| `WebsiteReporter` | Console text report for website probe results: formats per-domain per-cycle table. DNS section in the combined final report annotates each per-round row with ⚡ SPIKE / ▲ HIGH / ▲ ELEVATED flags matching the HTML chart thresholds, and prints a statistical summary footer with μ, σ, μ+5σ critical value, counts above threshold, and spike-analysis verdict. |
| `GenerateTestReport` | Standalone `main()` that regenerates the HTML report from a saved JSON file (useful for debugging/re-rendering without re-running probes). |

---

## Data Flow

```
monitor-targets.properties + website-thresholds.properties + CLI args
        │
        ▼
   TestConfig
        │
   ┌────┴─────────────────────────────────┐
   │               │                      │
   ▼               ▼                      ▼
YouTubePerformanceTester  WebsiteTester  DnsMonitor
   │ VideoMetrics[]         │ WebsiteMetrics[]  │ DnsResult[]
   │ NetworkSample[]        │                   │
   └────────────────────────┴──────────-─────────┘
                            │
                  ┌─────────┴────────────┐
                  ▼                      ▼
            JsonReporter           HtmlReporter
            (stdout JSON)    (net_performance_report.html)
                                         │
                                   PerformanceReporter
                               (net_performance_report.csv)
```

---

## Configuration Files (Resources)

All tuneable parameters are now externalised into four property files under `src/main/resources/`. No recompile is needed to adjust any threshold, timing, or selector.

| File | Controls | Consumed by |
|---|---|---|
| `monitor-targets.properties` | YouTube URLs, website URLs, DNS domains, monitoring duration | `TestConfig` |
| `website-thresholds.properties` | Website quality evaluation thresholds — default + per-site overrides | `TestConfig` |
| `youtube-thresholds.properties` | YouTube quality pass/fail thresholds (TTFB, page load, buffer depth, bandwidth, SFN speed, pre-buffer heuristic, buffer consistency σ) | `PerformanceEvaluator` |
| `probe-timing.properties` | Sweep interval, page-load timeouts (cold/warm), ad wait budgets (rounds, poll ms, transition ms), SFN settle delay, DNS resolver IP + timeout + round pause | `YouTubePerformanceTester`, `WebsiteTester`, `DnsMonitor` |
| `statistics.properties` | Sample-size reliability gates, outlier sigma multipliers, spike sigma gate, cluster window + per-probe thresholds (`spike.cluster_threshold.website`, `spike.cluster_threshold.dns`), periodicity CV max, warn rate, **DNS hard failure threshold** (`spike.dns.hard_threshold.ms`=500), **website page-load gate** (`spike.website.use_pageload_threshold`=true) | `StatisticsEngine`, `SpikeAnalyzer` |
| `browser-selectors.properties` | CSS selectors and element IDs for YouTube DOM automation (consent dialog, ad overlays, skip button, pause dialogs, Stats-for-Nerds panel/label/value, context menu, SFN known label names) | `BrowserSelectors` → `YouTubePerformanceTester` |

---

## Performance Thresholds (YouTube)

> Now configured in `youtube-thresholds.properties`. Values below are the shipped defaults.

| Check | Warm threshold | Cold threshold (tab 1) |
|---|---|---|
| Page Load Time | 12,000 ms | 15,000 ms |
| TTFB | 1,500 ms | 3,000 ms |
| Buffer Depth | ≥ 10 s | — |
| Avg Bandwidth | ≥ 250 KB/s | — |
| SFN Connection Speed | ≥ 2,500 Kbps | — |

## Performance Thresholds (Website — defaults)

> Now configured in `website-thresholds.properties` (website.threshold.default.*). Values below are the shipped defaults.

| Check | Warm | Cold |
|---|---|---|
| Page Load | 5,000 ms | 8,000 ms |
| TTFB | 1,200 ms | 2,000 ms |

Per-site overrides via `website.threshold.<domain>.*` in `website-thresholds.properties`.

## Spike Analysis Parameters

> Now configured in `statistics.properties`. Values below are the shipped defaults.

| Parameter | Default | Meaning |
|---|---|---|
| `spike.sigma_gate` | 5.0 | Spike = measurement > μ + 5σ of its group baseline |
| `spike.cluster_window.ms` | 60,000 | Sliding window for burst detection |
| `spike.cluster_threshold.website` | 5 | Spikes in window to trigger FAIL (website probe) |
| `spike.cluster_threshold.dns` | 10 | Spikes in window to trigger FAIL (DNS probe — higher bar due to natural DNS variance) |
| `spike.periodic.cv_max` | 0.40 | Max inter-spike CV to declare periodic pattern |
| `spike.warn_rate_pct` | 5.0 | Spike rate above which WARN is emitted |
| `website.min_cycles_ratio` | 0.25 | Minimum fraction of expected website cycles that must complete — FAIL if actual < expected × ratio (min 2) |

---

## Spike Pass/Fail Formula (DNS and Website)

The same 4-step pattern analysis is applied to both DNS (`HtmlReporter.analyzeDnsSpikes()`)
and Website page-load times (`HtmlReporter.analyzeWebsiteSpikes()`). Each probe uses its
own spike definition but shares the same cluster/periodicity verdict logic.

### DNS spikes
Response-time outliers (RT > μ + 5σ) per domain × record-type group, **successful** queries only.

### Website spikes
Page-load-time outliers (pageLoadTime > μ + 5σ) per tab, **positive** load times only.
Uses `WebsiteMetrics.getTimestamp()` for timing-based cluster detection.

### Step 1 — Identify spikes
Per group (domain×type for DNS; tab for Website), compute μ and σ of the metric.
Any result exceeding μ + 5σ is a **spike event**. Spike timestamps are collected globally.

### Step 2 — Cluster detection (60-second sliding window)
Sort spike timestamps. For each spike, count how many other spikes fall within the
next 60 seconds. If spikes in the window exceed the threshold → `hasCluster = true`.

| Probe | Cluster threshold | Rationale |
|---|---|---|
| **Website** | ≥ 5 spikes | Strict — page-load spikes are unlikely to be noise |
| **DNS** | ≥ 10 spikes | Relaxed — DNS has higher natural query variance |

The `SpikeAnalyzer.Result` also stores `clusterWindowStartMs` / `clusterWindowEndMs` and
`isSpikeAt(long timestampMs)` so `HtmlReporter` can shade the cluster window and colour
individual spike points without re-running the analysis.

### Step 3 — Periodicity detection (inter-spike CV)
Compute the **coefficient of variation** (CV = σ/μ) of the intervals between consecutive
spike timestamps. If CV < 0.40 **and** spike rate > 1 % → `hasPeriodic = true`.
(Low CV means the intervals are uniform — i.e. the spikes repeat at a regular cadence.)

### Step 4 — Verdict

| Condition | Verdict | Meaning |
|---|---|---|
| `hasCluster` OR `hasPeriodic` | **FAIL** | Structural problem — burst or periodic pattern |
| spike rate > 5 % | **WARN** | Scattered but too frequent to ignore |
| spikes exist, none of the above | **PASS** | Isolated/random events — annotated in charts only |
| no spikes | **PASS** | Clean |

> **Note:** The former "INFO" verdict no longer exists. Isolated random spikes now emit PASS
> (they are still annotated as amber points in the charts for visibility).

### Effect on report

| Probe | PASS / INFO | WARN | FAIL |
|---|---|---|---|
| **DNS** | ✓ green badge | ⚠ amber, may warn overall | ✗ red, `dnsCritical=true` |
| **Website** | ✓ green badge | ⚠ amber (also triggered by slow pages) | ✗ red, `webCritical=true` |

The spike-count row in each exec-summary card is coloured by **spike rate**, not by overall verdict:
- 0 spikes → green (`ev-pass`)
- 0 %–2 % → neutral
- 2 %–5 % → amber (`ev-warn`)
- ≥ 5 % → red (`ev-fail`)

DNS hard-failure rate ≥ 2 % also triggers a red FAIL badge independently of spikes.
Slow pages (exceeding absolute page-load thresholds) always push Website to WARN but not FAIL.
