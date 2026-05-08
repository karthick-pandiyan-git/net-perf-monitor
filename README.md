# Network Performance Monitor

A Java + Selenium CLI tool that probes internet quality from three independent angles **in parallel**: YouTube streaming, website load times, and direct DNS resolution — with explicit **IPv4 and IPv6** checks for both DNS and website probes. All probes run concurrently and produce a combined JSON + CSV + HTML report at the end.

---

## Tech Stack

| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 |
| Build | Maven | 3.8+ |
| Browser automation | Selenium WebDriver | 4.20.0 |
| ChromeDriver management | WebDriverManager (bonigarcia) | 5.8.0 |
| JSON serialization | Jackson Databind | 2.17.1 |
| DNS queries | dnsjava | 3.5.3 |
| Logging | SLF4J Simple | 2.0.13 |
| OS target | Windows (primary), any JVM platform |

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 17+ |
| Maven | 3.8+ |
| Google Chrome | Latest stable |

> **ChromeDriver is managed automatically** by WebDriverManager — no manual download needed.

---

## Build

```bash
mvn clean package -q
```

Produces `target/net-perf-monitor-jar-with-dependencies.jar`.

---

## Run

```bash
# PowerShell (UTF-8 safe — recommended on Windows)
.\run.ps1

# Or directly
java -jar target/net-perf-monitor-jar-with-dependencies.jar

# Override duration
java -jar target/net-perf-monitor-jar-with-dependencies.jar --duration 60

# Run only specific probes (youtube_N opens N tabs from the URL list)
java -jar target/net-perf-monitor-jar-with-dependencies.jar --mode youtube_3,website,dns

# Emit periodic JSON snapshots every 30 s
java -jar target/net-perf-monitor-jar-with-dependencies.jar --report 30
```

---

## CLI Flags

| Flag | Default | Description |
|---|---|---|
| `--duration <seconds>` | value in `monitor-targets.properties` | Total monitoring window. Clamped to [30, 900]. |
| `--mode <probes>` | `youtube,website,dns` | Comma-separated probes: `youtube` / `youtube_N` (open N tabs), `website`, `dns`, or `all`. |
| `--report <seconds>` | disabled | Print a JSON snapshot to stdout every N seconds plus a final one at the end. |

---

## Configuration

All tuneable parameters are externalised into six property files under `src/main/resources/`. Changes take effect on the next run — no recompile required.

| File | Purpose |
|---|---|
| `monitor-targets.properties` | YouTube URLs, website URLs, DNS domains, and monitoring duration |
| `website-thresholds.properties` | Website quality pass/fail thresholds — default + per-site overrides |
| `youtube-thresholds.properties` | YouTube quality pass/fail thresholds (TTFB, page load, buffer depth, bandwidth, SFN speed, buffer consistency) |
| `probe-timing.properties` | Sweep interval, page-load timeouts, ad handling wait times, DNS resolver config |
| `statistics.properties` | Spike-detection formula parameters and statistical sample-size gates |
| `browser-selectors.properties` | All CSS selectors and element IDs used to automate the YouTube player DOM |

### monitor-targets.properties

```properties
# Default monitoring window in seconds (overridable with --duration)
test.duration.seconds=30

# YouTube tabs — one URL per numbered key; youtube_N mode picks the first N
youtube.url.1=https://www.youtube.com/watch?v=...
youtube.url.2=https://www.youtube.com/watch?v=...

# Website tabs — refreshed every 5 s for the full duration
website.url.1=https://www.facebook.com
website.url.2=https://www.amazon.com

# DNS domains — queried directly over UDP to 8.8.8.8 (bypasses OS cache)
# Choose domains with AAAA records for meaningful IPv6 results
dns.domain.1=google.com
dns.domain.2=cloudflare.com
dns.domain.3=wikipedia.org
```

### website-thresholds.properties

```properties
# Default thresholds (cold = cycle 1, warm = cycles 2+)
website.threshold.default.pageload.warm.ms=5000
website.threshold.default.pageload.cold.ms=8000
website.threshold.default.ttfb.warm.ms=1200
website.threshold.default.ttfb.cold.ms=2000

# Per-site overrides — bare domain as key; omitted values fall back to defaults
website.threshold.americanexpress.com.pageload.warm.ms=8000
website.threshold.amazon.com.pageload.cold.ms=12000
```

### youtube-thresholds.properties

```properties
youtube.eval.page_load.warm.ms=12000
youtube.eval.page_load.cold.ms=15000
youtube.eval.ttfb.warm.ms=1500
youtube.eval.ttfb.cold.ms=3000
youtube.eval.buffer.min_depth.secs=10.0
youtube.eval.bandwidth.min_avg.kbps=250.0
youtube.eval.sfn.min_connection_speed.kbps=2500.0
youtube.eval.sfn.buffer_health.consecutive_zero_fail=2
```

### statistics.properties (spike analysis)

```properties
spike.sigma_gate=5.0               # Spike = measurement > μ + 5σ
spike.dns.hard_threshold.ms=500    # DNS spikes below this don't cause FAIL
spike.website.use_pageload_threshold=true  # Website spikes gated by per-site threshold
spike.cluster_window.ms=60000      # Sliding window for burst detection
spike.cluster_threshold.website=5  # Spikes in window to trigger FAIL (website)
spike.cluster_threshold.dns=7      # Spikes in window to trigger FAIL (DNS)
spike.periodic.cv_max=0.40         # Max inter-spike CV to declare periodic pattern
spike.warn_rate_pct=5.0            # Rate above which WARN is emitted
website.min_cycles_ratio=0.25      # Min fraction of expected cycles that must complete
```

---

## How It Works

Three probes run concurrently via a fixed-thread `ExecutorService`. YouTube and Website probes share a `CountDownLatch` so measurements start at the same wall-clock moment; the DNS probe starts immediately to capture failures during browser setup.

| Probe | What it does |
|---|---|
| **YouTube** (`YouTubePerformanceTester`) | Opens N YouTube tabs in one Chrome instance. Every 5 s: reads Navigation Timing API, `HTMLVideoElement` state (readyState, buffer depth, quality label), bandwidth via XHR intercept, and Stats-for-Nerds overlay. Evaluates each tab through `PerformanceEvaluator` (up to 10 quality checks including statistical buffer consistency and frame-drop analysis). |
| **Website** (`WebsiteTester`) | Opens configured sites in one Chrome instance. Every 5 s: navigates to each URL, reads Navigation Timing API, then probes **IPv4 and IPv6 TCP reachability** to port 443 from the JVM (independent of the browser). Per-site thresholds apply. |
| **DNS** (`DnsMonitor`) | Sends **A and AAAA** UDP queries directly to 8.8.8.8 for each configured domain — bypasses OS cache to confirm true internet connectivity for each IP version. Runs until browser probes complete. |

---

## Pass/Fail Criteria

### YouTube (up to 10 checks per tab)

> Thresholds configured in `youtube-thresholds.properties`.

| Check | Threshold |
|---|---|
| TTFB (server latency) | ≤ 3000 ms (tab 1, cold) / ≤ 1500 ms (tabs 2+, warm) |
| Page Load Time | ≤ 15000 ms (tab 1, cold) / ≤ 12000 ms (tabs 2+, warm) |
| Stream Buffer Depth | ≥ 10 s ahead of playhead |
| Avg Streaming Bandwidth | ≥ 250 KB/s (or pre-buffered) |
| Video Quality Stability | No ABR downgrade from peak quality |
| SFN Connection Speed | ≥ 2500 Kbps (HD minimum) |
| SFN Buffer Health | ≥ 2 consecutive sweeps at 0 s to FAIL (single 0 s = transient glitch) |
| SFN Resolution Match | Current resolution == optimal resolution |
| Buffer Depth Consistency | μ−1σ ≥ 10 s (lower confidence bound never dips below buffer minimum) |
| Frame Drop (Catastrophic) | No single sweep at 100% frame drop |

### Website (per refresh cycle)

> Thresholds configured in `website-thresholds.properties`. Per-site overrides apply for page load and TTFB.

| Check | Threshold | Notes |
|---|---|---|
| Page Load Time | ≤ 8000 ms (cycle 1, cold) / ≤ 5000 ms (cycles 2+, warm) | Per-site overrides apply |
| TTFB | ≤ 2000 ms (cycle 1, cold) / ≤ 1200 ms (cycles 2+, warm) | Per-site overrides apply |
| DOMContentLoaded | ≤ 4000 ms | Fixed threshold |
| DNS Lookup | ≤ 200 ms | Fixed threshold; 0 = OS cache hit |
| TCP Connect | — | Reported only, no pass/fail |
| Minimum Cycle Coverage | ≥ 25% of expected cycles must complete | FAIL if too few cycles |

### DNS
| Check | Threshold |
|---|---|
| A + AAAA queries | Must return `NOERROR` — any other rcode or network error = failure |
| Hard failure rate | ≥ 2% triggers red FAIL badge independently of spikes |

> `NOERROR` with an empty answer (IPv4-only domain, no AAAA record) is **not** a failure.

---

## Spike Analysis

Both DNS and Website probes run the same 4-step statistical pattern analysis (via `SpikeAnalyzer`) to detect structural connectivity problems beyond isolated outliers. All parameters are configured in `statistics.properties`.

### Step 1 — Identify spikes
Per group (domain×record-type for DNS; tab for Website), compute μ and σ of the metric. Any result exceeding μ + 5σ is a **spike event**. Additionally, spikes must exceed a hard threshold to count towards failure (DNS: 500 ms; Website: per-site page-load threshold).

### Step 2 — Cluster detection (60-second sliding window)
Sort spike timestamps. For each spike, count how many fall within the next 60 seconds. If the count exceeds the threshold → **cluster burst** detected.

| Probe | Cluster threshold |
|---|---|
| Website | ≥ 5 spikes |
| DNS | ≥ 7 spikes |

### Step 3 — Periodicity detection
Compute the coefficient of variation (CV) of inter-spike intervals. If CV < 0.40 **and** spike rate > 1% → **periodic pattern** detected (spikes repeat at a regular cadence).

### Step 4 — Verdict

| Condition | Verdict |
|---|---|
| Cluster OR periodic pattern | **FAIL** — structural problem |
| Spike rate > 5% | **WARN** — scattered but too frequent |
| Spikes exist, none of the above | **PASS** — isolated/random (annotated in charts) |
| No spikes | **PASS** — clean |

---

## Output

### Console
- Per-tab YouTube detail: per-sweep bandwidth table + PASS/FAIL check rows
- Website report: per-cycle timings + IPv4/IPv6 reachability, PASS/FAIL per domain. DNS section annotates each row with ⚡ SPIKE / ▲ HIGH / ▲ ELEVATED flags and prints a statistical summary footer.
- DNS report: per-domain rows (A + AAAA) with avg/min/max latency and success rate
- Combined final verdict: YouTube + Website + DNS

### JSON (stdout)
Always printed once at the end. With `--report <N>`, also printed every N seconds.
Contains full structured data: tab-level metrics, per-cycle website measurements, and per-round DNS results. A separate `youtube_performance_report.json` is also written.

### CSV
`target/net_performance_report.csv` — comprehensive CSV with three sections: YouTube, Website, and DNS (all probes).

### HTML Report
`target/net_performance_report.html` — self-contained with inline Chart.js graphs:
- **YouTube**: bandwidth + buffer line chart, TTFB/page-load bar chart
- **Website**: per-domain line chart with μ+5σ outlier gate (robust/MAD-based), amber spike points, shaded cluster band
- **DNS**: avg-latency bar chart + per-iteration line chart with spike/cluster highlighting, failed queries shown as red ⚠ points
- **Event Timeline**: μ and critical (μ+5σ) lines plus cluster bands for Website Load and DNS Response panels
- **Executive summary cards**: per-probe verdict with colour-coded spike-rate rows

---

## Project Structure

```
net-perf-monitor/
├── pom.xml
├── run.ps1                          # Windows launcher (sets UTF-8)
├── docs/                            # AI context files
│   ├── PROJECT_CONTEXT.md
│   ├── ARCHITECTURE.md
│   ├── DOMAIN_MODEL.md
│   ├── CONVENTIONS.md
│   └── COMMON_TASKS.md
├── src/main/
│   ├── java/com/youtube/perf/       # All source classes
│   │   ├── Main.java                # Entry point + orchestration
│   │   ├── TestConfig.java          # Config loader (properties + CLI)
│   │   ├── YouTubePerformanceTester.java  # YouTube probe
│   │   ├── WebsiteTester.java       # Website probe
│   │   ├── DnsMonitor.java          # DNS probe
│   │   ├── PerformanceEvaluator.java  # YouTube quality checks
│   │   ├── StatisticsEngine.java    # Descriptive statistics
│   │   ├── SpikeAnalyzer.java       # Spike pattern detection
│   │   ├── HtmlReporter.java        # HTML report generator
│   │   ├── JsonReporter.java        # JSON report generator
│   │   ├── PerformanceReporter.java # Console + CSV report
│   │   ├── WebsiteReporter.java     # Website console report
│   │   ├── BrowserSelectors.java    # YouTube DOM selectors
│   │   ├── GenerateTestReport.java  # Standalone report regenerator
│   │   └── (data classes)           # VideoMetrics, NetworkSample, etc.
│   └── resources/
│       ├── monitor-targets.properties
│       ├── website-thresholds.properties
│       ├── youtube-thresholds.properties
│       ├── probe-timing.properties
│       ├── statistics.properties
│       └── browser-selectors.properties
└── target/
    ├── net-perf-monitor-jar-with-dependencies.jar
    ├── net_performance_report.html
    └── net_performance_report.csv
```

---

## Notes

- Audio is **muted** automatically so autoplay policies do not block playback.
- Ads are handled: overlay ads closed, skippable pre-rolls skipped, non-skippable ads waited out (up to 45 s per ad).
- Long-session "Continue watching?" dialogs are dismissed automatically.
- Video looping is enabled so short videos do not stall during long monitoring runs.
- The highest available quality is set once at tab setup; ABR takes over during measurement.
- Background-tab throttling is disabled so all tabs receive equal CPU time.
- On Windows, `run.ps1` sets `[Console]::OutputEncoding = UTF8` so box-drawing characters render correctly.
- YouTube DOM selectors are externalised to `browser-selectors.properties` — update there when YouTube changes its DOM, no Java recompile needed.
