# Network Performance Monitor

A Java + Selenium CLI tool that probes internet quality from three independent angles **in parallel**: YouTube streaming, website load times, and direct DNS resolution — with explicit **IPv4 and IPv6** checks for both DNS and website probes. All probes run concurrently and produce a combined JSON + HTML report at the end.

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
| `--duration <seconds>` | value in `test.properties` | Total monitoring window. Clamped to [30, 900]. |
| `--mode <probes>` | `youtube,website,dns` | Comma-separated probes: `youtube` / `youtube_N` (open N tabs), `website`, `dns`, or `all`. |
| `--report <seconds>` | disabled | Print a JSON snapshot to stdout every N seconds plus a final one at the end. |

---

## Configuration

All URLs, domains, and the default duration live in `src/main/resources/test.properties` (bundled into the JAR at build time — rebuild after editing).

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

# Default page-load / TTFB thresholds (cold = cycle 1, warm = cycles 2+)
website.threshold.default.pageload.warm.ms=5000
website.threshold.default.pageload.cold.ms=8000
website.threshold.default.ttfb.warm.ms=1200
website.threshold.default.ttfb.cold.ms=2000

# Per-site overrides — bare domain as key; omitted values fall back to defaults
website.threshold.americanexpress.com.pageload.warm.ms=8000
website.threshold.amazon.com.pageload.cold.ms=12000
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
| Check | Threshold |
|---|---|
| TTFB (server latency) | ≤ 3000 ms (tab 1, cold) / ≤ 1500 ms (tabs 2+, warm) |
| Page Load Time | ≤ 15000 ms (tab 1, cold) / ≤ 12000 ms (tabs 2+, warm) |
| Stream Buffer Depth | ≥ 10 s ahead of playhead |
| Avg Streaming Bandwidth | ≥ 250 KB/s (or pre-buffered) |
| Video Quality Stability | No ABR downgrade from peak quality |
| SFN Connection Speed | ≥ 2500 Kbps (HD minimum) |
| SFN Buffer Health | Never 0 s during any sweep |
| SFN Resolution Match | Current resolution == optimal resolution |
| Buffer Depth Consistency | μ−1σ ≥ 10 s (lower confidence bound never dips below buffer minimum) |
| Frame Drop (Catastrophic) | No single sweep at 100% frame drop |

### Website (per refresh cycle)
| Check | Threshold | Notes |
|---|---|---|
| Page Load Time | ≤ 8000 ms (cycle 1, cold) / ≤ 5000 ms (cycles 2+, warm) | Per-site overrides apply |
| TTFB | ≤ 2000 ms (cycle 1, cold) / ≤ 1200 ms (cycles 2+, warm) | Per-site overrides apply |
| DOMContentLoaded | ≤ 4000 ms | Fixed threshold |
| DNS Lookup | ≤ 200 ms | Fixed threshold; 0 = OS cache hit |
| TCP Connect | — | Reported only, no pass/fail |

### DNS
| Check | Threshold |
|---|---|
| A + AAAA queries | Must return `NOERROR` — any other rcode or network error = failure |

> `NOERROR` with an empty answer (IPv4-only domain, no AAAA record) is **not** a failure.

---

## Output

### Console
- Per-tab YouTube detail: per-sweep bandwidth table + PASS/FAIL check rows
- Website report: per-cycle timings + IPv4/IPv6 reachability, PASS/FAIL per domain
- DNS report: per-domain rows (A + AAAA) with avg/min/max latency and success rate
- Combined final verdict: YouTube + Website + DNS

### JSON (stdout)
Always printed once at the end. With `--report <N>`, also printed every N seconds.
Contains full structured data: tab-level metrics, per-cycle website measurements, and per-round DNS results.

### HTML Report
`net_performance_report.html` — self-contained with inline Chart.js graphs (YouTube BW + buffer, website TTFB/page-load, DNS latency).

---

## Notes

- Audio is **muted** automatically so autoplay policies do not block playback.
- Ads are handled: overlay ads closed, skippable pre-rolls skipped, non-skippable ads waited out (up to 45 s per ad).
- Long-session "Continue watching?" dialogs are dismissed automatically.
- Video looping is enabled so short videos do not stall during long monitoring runs.
- The highest available quality is set once at tab setup; ABR takes over during measurement.
- Background-tab throttling is disabled so all tabs receive equal CPU time.
- On Windows, `run.ps1` sets `[Console]::OutputEncoding = UTF8` so box-drawing characters render correctly.
