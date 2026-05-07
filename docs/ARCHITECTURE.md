# Architecture — net-perf-monitor

> Read `PROJECT_CONTEXT.md` first. This file explains how the code is organised.

---

## Thread Model

```
main thread
  └─► TestConfig.load(args)            # parse CLI + test.properties
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
| `TestConfig` | Loads `test.properties` + CLI args. Exposes `youtubeUrls`, `websiteUrls`, `dnsDomains`, `durationSeconds`, `mode`, `youtubeCount`, `reportIntervalSeconds`, `WebsiteThresholds`. |

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
| `WebsiteThresholds` | Four threshold longs: `pageLoadWarmMs`, `pageLoadColdMs`, `ttfbWarmMs`, `ttfbColdMs`. Static `DEFAULT` constant. Per-site overrides configured in `test.properties`. |

### Evaluation & Statistics

| Class | Role |
|---|---|
| `PerformanceEvaluator` | Takes a `VideoMetrics` snapshot and returns a `VideoVerdict`. Runs up to 10 quality checks: 5 core (TTFB, Page Load, Buffer Depth, Avg BW, Quality Stability) + up to 3 Stats-for-Nerds (Connection Speed, Buffer Health, Resolution Match) + up to 2 statistical (Buffer Depth Consistency, Catastrophic Frame Drop). Applies cold (tab 1) vs warm (tabs 2+) thresholds. SFN checks run whenever `VideoMetrics.getSfnData().isAvailable()` — including when SFN was synthesized from sweep data. |
| `StatisticsEngine` | Computes descriptive statistics (mean, stddev, p50, p95) for a `List<Long>`. Classifies individual values as NORMAL / ELEVATED / HIGH / CRITICAL. Uses Bessel's correction. Has `Grade` enum and `Stats` record. |

### Reporting

| Class | Role |
|---|---|
| `JsonReporter` | Prints a compact JSON snapshot to stdout. Merges YouTube `VideoMetrics`, website `WebsiteMetrics`, and DNS `DnsResult` lists. Supports numbered intermediate snapshots plus a final snapshot. |
| `HtmlReporter` | Generates `net_performance_report.html`. Self-contained with inline Chart.js. Charts: YouTube BW + buffer line chart, TTFB/page-load bar chart, website per-domain line chart, DNS avg-latency bar chart. |
| `PerformanceReporter` | Console text report: formats check rows as PASS/FAIL table, summary verdicts per tab. |
| `WebsiteReporter` | Console text report for website probe results: formats per-domain per-cycle table. |
| `GenerateTestReport` | Standalone `main()` that regenerates the HTML report from a saved JSON file (useful for debugging/re-rendering without re-running probes). |

---

## Data Flow

```
test.properties + CLI args
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
```

---

## Performance Thresholds (YouTube)

| Check | Warm threshold | Cold threshold (tab 1) |
|---|---|---|
| Page Load Time | 12,000 ms | 15,000 ms |
| TTFB | 1,500 ms | 3,000 ms |
| Buffer Depth | ≥ 10 s | — |
| Avg Bandwidth | ≥ 250 KB/s | — |
| SFN Connection Speed | ≥ 2,500 Kbps | — |

## Performance Thresholds (Website — defaults)

| Check | Warm | Cold |
|---|---|---|
| Page Load | 5,000 ms | 8,000 ms |
| TTFB | 1,200 ms | 2,000 ms |

Per-site overrides via `website.threshold.<domain>.*` in `test.properties`.
