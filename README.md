# Network Performance Monitor

A Java + Selenium project that probes internet quality from three angles **in parallel**: YouTube streaming, website load times, and direct DNS resolution — including explicit **IPv4 and IPv6 stability checks** for both DNS and website probes. All probes run concurrently and produce a combined PASS/FAIL report at the end.

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

This produces `target/net-perf-monitor-jar-with-dependencies.jar`.

---

## Run

```bash
# PowerShell (UTF-8 safe — use this on Windows)
.\run.ps1

# Or directly from the project root
java -jar target/net-perf-monitor-jar-with-dependencies.jar

# Override the monitoring duration at runtime
java -jar target/net-perf-monitor-jar-with-dependencies.jar --duration 60

# Run only specific probes
java -jar target/net-perf-monitor-jar-with-dependencies.jar --mode website,dns

# Emit periodic JSON snapshots every 30 s (in addition to the final report)
java -jar target/net-perf-monitor-jar-with-dependencies.jar --report 30
```

---

## CLI Flags

| Flag | Default | Description |
|---|---|---|
| `--duration <seconds>` | value in `test.properties` | Total monitoring window. Clamped to [30, 900]. |
| `--mode <probes>` | `youtube,website,dns` | Comma-separated list of probes to run: `youtube`, `website`, `dns`. |
| `--report <seconds>` | disabled | Emit a JSON snapshot to stdout every N seconds during the run, plus a final snapshot at the end. |

---

## Configuration

All URLs, domains, and the default duration are loaded from `src/main/resources/test.properties`.  
A rebuild is required after editing this file (the JAR bundles the properties at package time).

```properties
# Default monitoring window in seconds (overridable with --duration)
test.duration.seconds=30

# YouTube video tabs (one per numbered key)
youtube.url.1=https://www.youtube.com/watch?v=YOUR_VIDEO_ID
youtube.url.2=https://www.youtube.com/watch?v=ANOTHER_VIDEO_ID

# Website tabs (refreshed every 5 s for the full duration)
website.url.1=https://example.com
website.url.2=https://another-site.com

# Domains queried directly over UDP to 8.8.8.8 (no OS cache).
# Both a Type-A (IPv4) and Type-AAAA (IPv6) query are sent per domain per round.
# Choose domains that have AAAA records (e.g. google.com, cloudflare.com, wikipedia.org)
# to get meaningful IPv6 results.
dns.domain.1=google.com
dns.domain.2=cloudflare.com
dns.domain.3=wikipedia.org
```

---

## How It Works

Three probes run concurrently via an `ExecutorService`:

| Probe | Class | What it does |
|---|---|---|
| **YouTube** | `YouTubePerformanceTester` | Opens all YouTube URLs in a single Chrome instance. Sweeps every 5 s to sample bandwidth, buffer depth, and quality label. |
| **Website** | `WebsiteTester` | Opens each website URL in a single Chrome instance. Navigates to each URL every 5 s, reads Navigation Timing API metrics, and probes **IPv4 and IPv6 TCP reachability** to port 443 after each successful load. |
| **DNS** | `DnsMonitor` | Sends **both Type-A (IPv4) and Type-AAAA (IPv6)** UDP queries directly to Google Public DNS (8.8.8.8), bypassing the OS cache, to confirm real internet connectivity for each IP version. Runs continuously until the browser probes finish. |

YouTube and Website probes synchronise through a `CountDownLatch` barrier so their windows start at the same moment. DNS starts immediately to capture failures during browser setup.

---

## Metrics Collected

### YouTube — Page-load Timing (Navigation Timing API)
| Metric | Description |
|---|---|
| `pageLoadTime` | Navigation start → `load` event (ms) |
| `domContentLoadedTime` | Navigation start → DOMContentLoaded (ms) |
| `timeToFirstByte` (TTFB) | Navigation start → first response byte (ms) |
| `dnsLookupTime` | DNS resolution duration (ms; 0 = OS cache hit) |
| `tcpConnectionTime` | TCP handshake duration (ms; 0 = connection reused) |
| `domInteractiveTime` | Navigation start → DOM interactive (ms) |

### YouTube — Video Element State (`HTMLVideoElement`)
| Metric | Description |
|---|---|
| `videoWidth` / `videoHeight` | Actual render resolution |
| `currentTime` | Playback position (seconds) |
| `duration` | Total video length (seconds) |
| `bufferedSeconds` | Furthest buffered position (seconds) |
| `playbackRate` | Current speed multiplier |
| `readyState` | 0–4 (HAVE_NOTHING → HAVE_ENOUGH_DATA) |
| `networkState` | 0–3 (EMPTY / IDLE / LOADING / NO_SOURCE) |
| `paused` | Whether playback is paused |

### YouTube — Live Network Sampling (per 5-second sweep)
| Metric | Description |
|---|---|
| `bandwidthKBps` | Estimated throughput for the last sweep window |
| `recentSegmentBytes` | Video segment bytes transferred in last window |
| `recentSegmentCount` | Number of segment requests completed in last window |
| `videoCurrentTime` | Playhead position at sample time (seconds) |
| `videoBuffered` | Furthest buffered position at sample time (seconds) |
| `qualityLabel` | YouTube quality label (e.g. `hd1080`, `hd720`) — excluded during ads |
| `adPlaying` | Whether an ad was playing during this sample |

### YouTube — Quality Tracking
| Metric | Description |
|---|---|
| `peakQualityLabel` | Best quality observed (e.g. `hd1080`) |
| `lowestQualityLabel` | Worst quality observed during the window |
| `qualityDegraded` | `true` if YouTube's ABR stepped down from peak |

### YouTube — Playback Quality (`getVideoPlaybackQuality()`)
| Metric | Description |
|---|---|
| `totalVideoFrames` | Frames decoded since playback started |
| `droppedVideoFrames` | Frames the renderer skipped |
| `corruptedVideoFrames` | Frames with decode errors |
| `dropRate` | `droppedFrames / totalFrames × 100` (%) |

### Website — Navigation Timing (per refresh cycle)
| Metric | Description |
|---|---|
| `pageLoadTime` | Full page load time (ms) |
| `timeToFirstByte` | TTFB (ms) |
| `domContentLoaded` | DOMContentLoaded time (ms) |
| `dnsLookupTime` | DNS resolution for this navigation (ms) |
| `tcpConnectionTime` | TCP connection time (ms) |
| `domInteractiveTime` | DOM interactive time (ms) |

### Website — IPv4 / IPv6 TCP Reachability (per refresh cycle, Java-side)
Probed on each successful page load by connecting to port 443 directly from the JVM (independent of the browser's happy-eyeballs logic).

| Metric | Description |
|---|---|
| `ipv4Address` | First IPv4 address resolved for the domain |
| `ipv4Reachable` | Whether a TCP connection to port 443 succeeded over IPv4 |
| `ipv4ConnectMs` | TCP connect time over IPv4 (ms) |
| `ipv6Address` | First IPv6 address resolved for the domain (if any AAAA record exists) |
| `ipv6Reachable` | Whether a TCP connection to port 443 succeeded over IPv6 |
| `ipv6ConnectMs` | TCP connect time over IPv6 (ms) |

### DNS — Direct UDP Query Results (per domain, per record type)
One set of results is produced for **Type A (IPv4)** and another for **Type AAAA (IPv6)** per domain per round.

| Metric | Description |
|---|---|
| `recordType` | `"A"` (IPv4) or `"AAAA"` (IPv6) |
| `responseTimeMs` | True round-trip latency to 8.8.8.8 |
| `resolvedAddresses` | Deduplicated list of IPs returned across all rounds |
| `success` | `true` if the resolver returned `NOERROR` (including empty answers for IPv4-only domains) |
| `errorMessage` | Non-NOERROR rcode or network error description |

> **Note:** A `NOERROR` response with no addresses (e.g. a domain with no AAAA record) is still reported as `success = true` — it means the resolver is healthy and the domain is simply IPv4-only, not that DNS is broken.

---

## Pass/Fail Criteria

### YouTube (7 checks per video)
| Check | Threshold |
|---|---|
| DNS Lookup Time | ≤ 200 ms (0 = cached) |
| TCP Connection RTT | ≤ 200 ms (0 = reused) |
| TTFB | ≤ 3000 ms (tab 1, cold) / ≤ 1200 ms (tabs 2+, warm) |
| Page Load Time | ≤ 15000 ms (tab 1, cold) / ≤ 12000 ms (tabs 2+, warm) |
| Stream Buffer Depth | ≥ 10 s ahead of playhead |
| Avg Streaming Bandwidth | ≥ 250 KB/s (or pre-buffered) |
| Video Quality Stability | No ABR downgrade from peak quality |

### Website (per refresh cycle)
| Check | Threshold |
|---|---|
| Page Load Time | ≤ 8000 ms (cycle 1, cold) / ≤ 5000 ms (cycles 2+) |
| TTFB | ≤ 2000 ms (cycle 1, cold) / ≤ 1200 ms (cycles 2+) |

### DNS
| Check | Threshold |
|---|---|
| Every query | Must return `NOERROR` (network error or non-NOERROR rcode = failure) |

---

## Output

### Console
- Per-tab YouTube streaming detail with per-sweep bandwidth table
- Summary table sorted by TTFB
- Website report: per-cycle timings with PASS/FAIL annotation per domain
- DNS report: per-domain rows split by record type (A / AAAA) with avg/min/max latency
- Combined final report: YouTube + Website + DNS PASS/FAIL verdict

### JSON (stdout, via `--report`)
When `--report <N>` is passed, a JSON snapshot is printed to stdout every N seconds and once more at the end. Each snapshot contains:

```json
{
  "timestamp": "2026-05-04T22:10:00",
  "type": "snapshot",
  "snapshotNumber": 1,
  "mode": "website,dns",
  "duration_seconds": 60,
  "website": [
    {
      "tab": 1,
      "url": "https://www.wikipedia.org",
      "domain": "wikipedia.org",
      "cycles": [
        {
          "cycle": 1,
          "timestamp": "2026-05-04T22:09:05",
          "success": true,
          "pageLoad_ms": 1820,
          "ttfb_ms": 310,
          "ipv4_address": "208.80.154.224",
          "ipv4_reachable": true,
          "ipv4Connect_ms": 18,
          "ipv6_address": "2620:0:861:ed1a::1",
          "ipv6_reachable": true,
          "ipv6Connect_ms": 22
        }
      ]
    }
  ],
  "dns": [
    {
      "domain": "wikipedia.org",
      "recordType": "A",
      "resolver": "8.8.8.8",
      "resolvedAddresses": [ "208.80.154.224" ],
      "rounds": [ { "round": 1, "timestamp": "...", "success": true, "responseTime_ms": 28 } ],
      "successRounds": 13,
      "totalRounds": 13,
      "successRate_pct": 100.0
    },
    {
      "domain": "wikipedia.org",
      "recordType": "AAAA",
      "resolver": "8.8.8.8",
      "resolvedAddresses": [ "2620:0:861:ed1a:0:0:0:1" ],
      "rounds": [ { "round": 1, "timestamp": "...", "success": true, "responseTime_ms": 29 } ],
      "successRounds": 13,
      "totalRounds": 13,
      "successRate_pct": 100.0
    }
  ]
}
```

### Files (written to the working directory)
| File | Format |
|---|---|
| `youtube_performance_report.csv` | Spreadsheet-friendly, one row per tab |
| `youtube_performance_report.json` | Full structured data including per-sweep samples |

---

## Project Structure

```
net-perf-monitor/
├── pom.xml
├── run.ps1                              # PowerShell launcher (UTF-8 safe)
├── src/main/resources/
│   └── test.properties                  # URLs, domains, and default duration
└── src/main/java/com/youtube/perf/
    ├── Main.java                        # Entry point; runs three probes in parallel
    ├── TestConfig.java                  # Loads test.properties + CLI flags
    ├── YouTubePerformanceTester.java    # YouTube streaming probe (multi-tab Chrome)
    ├── WebsiteTester.java               # Website load-time + IPv4/IPv6 reachability probe
    ├── DnsMonitor.java                  # Direct UDP DNS probe (Type-A + Type-AAAA per domain)
    ├── PerformanceEvaluator.java        # Evaluates 7 internet-health checks per video
    ├── PerformanceReporter.java         # YouTube CSV + JSON + console report
    ├── JsonReporter.java                # Periodic JSON snapshots to stdout
    ├── WebsiteReporter.java             # Website + DNS detail + combined final report
    ├── VideoMetrics.java                # Data model: one YouTube tab
    ├── NetworkSample.java               # Data model: one per-sweep bandwidth sample
    ├── WebsiteMetrics.java              # Data model: one website refresh cycle (incl. IPv4/IPv6)
    ├── DnsResult.java                   # Data model: one DNS query result (A or AAAA)
    ├── VideoVerdict.java                # Composite PASS/FAIL verdict for one video
    └── CheckResult.java                 # Single check result (name, expected, actual, pass)
```

---

## Notes

- Audio is **muted** automatically so autoplay policies don't block playback.
- YouTube ads are handled automatically: overlay ads are closed, skippable pre-rolls are skipped, non-skippable ads are waited out (up to 45 s per ad).
- Long-session pause dialogs (YouTube's "Continue watching?" prompt) are dismissed automatically.
- Video looping is enabled so short videos don't stall during long monitoring runs.
- The highest available quality is set once during tab setup; quality is then left to YouTube's ABR during measurement.
- Background-tab throttling is disabled so all Chrome tabs receive equal CPU time.
- The IPv6 TCP probe uses a 3-second connect timeout per address. If a site has no AAAA record the probe is skipped for that cycle.
- DNS domains with no AAAA records (e.g. `github.com`) always return `NOERROR` with empty addresses — this is correct behaviour and does not count as a failure. Use domains with confirmed IPv6 support (`google.com`, `cloudflare.com`, `wikipedia.org`) for meaningful AAAA results.
- On Windows, `run.ps1` sets `[Console]::OutputEncoding = UTF8` before launching the JVM so box-drawing characters render correctly.
- `performance.timing` is deprecated but remains fully functional in Chrome.


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

This produces `target/net-perf-monitor-jar-with-dependencies.jar`.

---

## Run

```bash
# PowerShell (UTF-8 safe — use this on Windows)
.\run.ps1

# Or directly from the project root
java -jar target/net-perf-monitor-jar-with-dependencies.jar

# Override the monitoring duration (seconds) at runtime
java -jar target/net-perf-monitor-jar-with-dependencies.jar --duration 60
```

---

## Configuration

All URLs, domains, and the default duration are loaded from `src/main/resources/test.properties`:

```properties
# YouTube video tabs (one per numbered key)
youtube.url.1=https://www.youtube.com/watch?v=YOUR_VIDEO_ID
youtube.url.2=https://www.youtube.com/watch?v=ANOTHER_VIDEO_ID

# Website tabs (refreshed every 5 s for the full duration)
website.url.1=https://example.com
website.url.2=https://another-site.com

# Domains queried directly over UDP to 8.8.8.8 (no OS cache)
dns.domain.1=google.com
dns.domain.2=youtube.com

# Default monitoring window in seconds (overridable with --duration)
test.duration.seconds=30
```

The `--duration <seconds>` CLI flag overrides `test.duration.seconds`. Duration is clamped to [30, 900].

---

## How It Works

Three probes run concurrently via an `ExecutorService`:

| Probe | Class | What it does |
|---|---|---|
| **YouTube** | `YouTubePerformanceTester` | Opens all YouTube URLs in a single Chrome instance (one tab per URL). Sweeps every 5 s to sample bandwidth, buffer depth, and quality label. Sets the highest available quality on load. |
| **Website** | `WebsiteTester` | Opens each website URL in a single Chrome instance. Navigates to each URL every 5 s and reads Navigation Timing API metrics after each load. |
| **DNS** | `DnsMonitor` | Sends type-A UDP queries directly to Google Public DNS (8.8.8.8), bypassing the OS cache, to confirm real internet connectivity. Runs continuously until the browser probes finish. |

YouTube and Website probes synchronise through a `CountDownLatch` barrier so their measurement windows start at the same moment. DNS starts immediately to capture failures that occur during browser setup.

---

## Metrics Collected

### YouTube — Page-load Timing (Navigation Timing API)
| Metric | Description |
|---|---|
| `pageLoadTime` | Navigation start → `load` event (ms) |
| `domContentLoadedTime` | Navigation start → DOMContentLoaded (ms) |
| `timeToFirstByte` (TTFB) | Navigation start → first response byte (ms) |
| `dnsLookupTime` | DNS resolution duration (ms; 0 = OS cache hit) |
| `tcpConnectionTime` | TCP handshake duration (ms; 0 = connection reused) |
| `domInteractiveTime` | Navigation start → DOM interactive (ms) |

### YouTube — Video Element State (`HTMLVideoElement`)
| Metric | Description |
|---|---|
| `videoWidth` / `videoHeight` | Actual render resolution |
| `currentTime` | Playback position (seconds) |
| `duration` | Total video length (seconds) |
| `bufferedSeconds` | Furthest buffered position (seconds) |
| `playbackRate` | Current speed multiplier |
| `readyState` | 0–4 (HAVE_NOTHING → HAVE_ENOUGH_DATA) |
| `networkState` | 0–3 (EMPTY / IDLE / LOADING / NO_SOURCE) |
| `paused` | Whether playback is paused |

### YouTube — Live Network Sampling (per 5-second sweep)
| Metric | Description |
|---|---|
| `bandwidthKBps` | Estimated throughput for the last sweep window |
| `recentSegmentBytes` | Video segment bytes transferred in last window |
| `recentSegmentCount` | Number of segment requests completed in last window |
| `videoCurrentTime` | Playhead position at sample time (seconds) |
| `videoBuffered` | Furthest buffered position at sample time (seconds) |
| `qualityLabel` | YouTube quality label (e.g. `hd1080`, `hd720`) — excluded during ads |
| `adPlaying` | Whether an ad was playing during this sample |

### YouTube — Quality Tracking
| Metric | Description |
|---|---|
| `peakQualityLabel` | Best quality observed (e.g. `hd1080`) |
| `lowestQualityLabel` | Worst quality observed during the window |
| `qualityDegraded` | `true` if YouTube's ABR stepped down from peak |

### YouTube — Playback Quality (`getVideoPlaybackQuality()`)
| Metric | Description |
|---|---|
| `totalVideoFrames` | Frames decoded since playback started |
| `droppedVideoFrames` | Frames the renderer skipped |
| `corruptedVideoFrames` | Frames with decode errors |
| `dropRate` | `droppedFrames / totalFrames × 100` (%) |

### Website — Navigation Timing (per refresh cycle)
| Metric | Description |
|---|---|
| `pageLoadTime` | Full page load time (ms) |
| `timeToFirstByte` | TTFB (ms) |
| `domContentLoaded` | DOMContentLoaded time (ms) |
| `dnsLookupTime` | DNS resolution for this navigation (ms) |
| `tcpConnectionTime` | TCP connection time (ms) |
| `domInteractiveTime` | DOM interactive time (ms) |

### DNS — Direct UDP Query Results
| Metric | Description |
|---|---|
| `responseTimeMs` | True round-trip latency to 8.8.8.8 |
| `resolvedAddresses` | List of A record IPs returned |
| `success` | Whether the query resolved successfully |
| `errorMessage` | Error description on failure |

---

## Pass/Fail Criteria

### YouTube (7 checks per video)
| Check | Threshold |
|---|---|
| DNS Lookup Time | ≤ 200 ms (0 = cached) |
| TCP Connection RTT | ≤ 200 ms (0 = reused) |
| TTFB | ≤ 3000 ms (tab 1, cold) / ≤ 1200 ms (tabs 2+, warm) |
| Page Load Time | ≤ 15000 ms (tab 1, cold) / ≤ 12000 ms (tabs 2+, warm) |
| Stream Buffer Depth | ≥ 10 s ahead of playhead |
| Avg Streaming Bandwidth | ≥ 250 KB/s (or pre-buffered) |
| Video Quality Stability | No ABR downgrade from peak quality |

### Website (per refresh cycle)
| Check | Threshold |
|---|---|
| Page Load Time | ≤ 8000 ms (cycle 1, cold) / ≤ 5000 ms (cycles 2+) |
| TTFB | ≤ 2000 ms (cycle 1, cold) / ≤ 1200 ms (cycles 2+) |

### DNS
| Check | Threshold |
|---|---|
| Every query | Must resolve successfully (any failure = connection broken) |

---

## Output

### Console
- Per-tab YouTube streaming detail with per-sweep bandwidth table
- Summary table sorted by TTFB
- Website report: per-cycle timings with PASS/FAIL annotation per domain
- DNS report: per-domain/tool aggregate with avg/min/max latency
- Combined final report: YouTube + Website + DNS PASS/FAIL verdict

### Files (written to the working directory)
| File | Format |
|---|---|
| `youtube_performance_report.csv` | Spreadsheet-friendly, one row per tab |
| `youtube_performance_report.json` | Full structured data including per-sweep samples |

---

## Project Structure

```
net-perf-monitor/
├── pom.xml
├── run.ps1                              # PowerShell launcher (UTF-8 safe)
├── src/main/resources/
│   └── test.properties                  # URLs, domains, and default duration
└── src/main/java/com/youtube/perf/
    ├── Main.java                        # Entry point; runs three probes in parallel
    ├── TestConfig.java                  # Loads test.properties + --duration CLI flag
    ├── YouTubePerformanceTester.java    # YouTube streaming probe (multi-tab Chrome)
    ├── WebsiteTester.java               # Website load-time probe (multi-tab Chrome)
    ├── DnsMonitor.java                  # Direct UDP DNS probe to 8.8.8.8
    ├── PerformanceEvaluator.java        # Evaluates 7 internet-health checks per video
    ├── PerformanceReporter.java         # YouTube CSV + JSON + console report
    ├── WebsiteReporter.java             # Website + DNS detail + combined final report
    ├── VideoMetrics.java                # Data model: one YouTube tab
    ├── NetworkSample.java               # Data model: one per-sweep bandwidth sample
    ├── WebsiteMetrics.java              # Data model: one website refresh cycle
    ├── DnsResult.java                   # Data model: one DNS query result
    ├── VideoVerdict.java                # Composite PASS/FAIL verdict for one video
    └── CheckResult.java                 # Single check result (name, expected, actual, pass)
```

---

## Notes

- Audio is **muted** automatically so autoplay policies don't block playback.
- YouTube ads are handled automatically: overlay ads are closed, skippable pre-rolls are skipped, non-skippable ads are waited out (up to 45 s per ad, up to 5 consecutive ads).
- The highest available quality is set once during tab setup; quality is then left entirely to YouTube's ABR algorithm during measurement to avoid disrupting the download pipeline.
- Background-tab throttling is disabled on the Chrome instances so all tabs receive equal CPU time during sweep loops.
- On Windows, the PowerShell wrapper `run.ps1` sets `[Console]::OutputEncoding = UTF8` before launching the JVM so box-drawing characters render correctly. Running directly from `cmd.exe` also works without modification.
- `performance.timing` is deprecated but remains fully functional in Chrome.
