# Project Context — net-perf-monitor

> **AI FIRST**: Read this file before touching any source. It is purpose-built to answer "what is this project?" in minimal tokens.

## One-liner

A standalone Java CLI tool that probes internet quality from three independent angles **in parallel**: YouTube streaming, website load times, and DNS resolution — producing a combined JSON + HTML report.

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

## Artifact

- **JAR**: `target/net-perf-monitor-jar-with-dependencies.jar` (fat JAR)
- Main class: `com.youtube.perf.Main`

---

## Build & Run Commands

```bash
# Build
mvn clean package -q

# Run (PowerShell — UTF-8 safe)
.\run.ps1

# Run directly
java -jar target/net-perf-monitor-jar-with-dependencies.jar

# Common flags
--duration <seconds>       # Override default from monitor-targets.properties (30–900 s)
--mode youtube,website,dns # Comma-separated list of probes; youtube_N = N tabs
--report <seconds>         # Print intermediate JSON snapshot every N seconds
```

---

## Configuration Files

All property files live under `src/main/resources/` and are bundled into the JAR.

| File | Purpose |
|---|---|
| `monitor-targets.properties` | YouTube URLs, website URLs, DNS domains, and monitoring duration |
| `website-thresholds.properties` | Website quality pass/fail thresholds — page-load and TTFB, default + per-site overrides |
| `youtube-thresholds.properties` | YouTube quality pass/fail thresholds (TTFB, page load, buffer, bandwidth, SFN speed) |
| `probe-timing.properties` | Sweep interval, page-load timeouts, ad handling wait times, DNS resolver config |
| `statistics.properties` | Spike-detection formula parameters and statistical sample-size gates |
| `browser-selectors.properties` | All CSS selectors and element IDs used to automate the YouTube player DOM |

### `monitor-targets.properties` key patterns

| Key pattern | Purpose |
|---|---|
| `test.duration.seconds` | Default monitoring window (30–900 s) |
| `youtube.url.N` | YouTube tab URLs (1-based index) |
| `website.url.N` | Website URLs to refresh every 5 s |
| `dns.domain.N` | Domains queried via DNS (both A + AAAA) |

### `website-thresholds.properties` key patterns

| Key pattern | Purpose |
|---|---|
| `website.threshold.default.pageload.warm.ms` | Default warm page-load threshold (all sites) |
| `website.threshold.default.pageload.cold.ms` | Default cold page-load threshold (all sites) |
| `website.threshold.default.ttfb.warm.ms` | Default warm TTFB threshold (all sites) |
| `website.threshold.default.ttfb.cold.ms` | Default cold TTFB threshold (all sites) |
| `website.threshold.<domain>.pageload.warm.ms` | Per-site warm page-load override |
| `website.threshold.<domain>.pageload.cold.ms` | Per-site cold page-load override |
| `website.threshold.<domain>.ttfb.warm.ms` | Per-site warm TTFB override |
| `website.threshold.<domain>.ttfb.cold.ms` | Per-site cold TTFB override |

---

## Output Files

| File | Description |
|---|---|
| stdout (JSON) | Final JSON snapshot (always printed) + periodic snapshots if `--report` used |
| `target/net_performance_report.html` | Self-contained HTML report with Chart.js graphs |
| `target/net_performance_report.csv` | Comprehensive CSV with three sections: YouTube, Website, DNS (all probes) |
| `youtube_performance_report.json` | Detailed YouTube-only JSON (per-tab SFN + video metrics) |

---

## Project Structure

```
net-perf-monitor/
├── pom.xml
├── run.ps1                          # Windows launcher (sets UTF-8)
├── src/main/
│   ├── java/com/youtube/perf/       # All source classes (see ARCHITECTURE.md)
│   └── resources/         # monitor-targets.properties, website-thresholds.properties, …
├── docs/                            # AI context files (this directory)
└── target/
    └── net-perf-monitor-jar-with-dependencies.jar
```

---

## Package

All classes live in `com.youtube.perf` (historical name; the tool monitors more than YouTube).

---

## Key Constraints

- Duration is clamped to [30, 900] seconds — no shorter, no longer.
- DNS queries bypass the OS cache via dnsjava's `SimpleResolver → 8.8.8.8:53 UDP`.
- YouTube and Website probes synchronise via a `CountDownLatch` before measurements begin.
- ChromeDriver is managed automatically by WebDriverManager — no manual install.
- Windows UTF-8: fixed via `SetConsoleOutputCP(65001)` for cmd.exe; `run.ps1` sets `[Console]::OutputEncoding` for PowerShell.
