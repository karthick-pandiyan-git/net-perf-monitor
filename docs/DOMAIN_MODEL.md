# Domain Model — net-perf-monitor

> All classes live in package `com.youtube.perf`.

---

## Configuration

### `TestConfig`
Loaded once at startup from classpath `test.properties` + CLI args.

| Field | Type | Source | Notes |
|---|---|---|---|
| `youtubeUrls` | `List<String>` | `youtube.url.N` | Ordered; only first `youtubeCount` are used |
| `websiteUrls` | `List<String>` | `website.url.N` | All used |
| `dnsDomains` | `List<String>` | `dns.domain.N` | All used; should have AAAA records for IPv6 to work |
| `durationSeconds` | `int` | `test.duration.seconds` / `--duration` | Clamped [30, 900] |
| `mode` | `Set<String>` | `--mode` | Values: "youtube", "website", "dns". Default: all three |
| `youtubeCount` | `int` | `youtube_N` suffix in mode | Default: 2 |
| `modeLabel` | `String` | derived | Human-readable, preserves `youtube_N` token |
| `reportIntervalSeconds` | `int` | `--report` | -1 = no periodic JSON; > 0 = n-second snapshots |
| `defaultThresholds` | `WebsiteThresholds` | `test.properties` global keys | Applied when no per-site override exists |
| `siteThresholds` | `Map<String, WebsiteThresholds>` | `website.threshold.<domain>.*` | Keyed by bare hostname |

### `WebsiteThresholds`
| Field | Type | Default |
|---|---|---|
| `pageLoadWarmMs` | `long` | 5000 |
| `pageLoadColdMs` | `long` | 8000 |
| `ttfbWarmMs` | `long` | 1200 |
| `ttfbColdMs` | `long` | 2000 |

Static `DEFAULT` instance uses these values. `test.properties` keys:
```
website.threshold.<domain>.pageload.warm.ms
website.threshold.<domain>.pageload.cold.ms
website.threshold.<domain>.ttfb.warm.ms
website.threshold.<domain>.ttfb.cold.ms
```

---

## YouTube Probe Data

### `VideoMetrics` — final snapshot for one YouTube tab
| Field | Type | Notes |
|---|---|---|
| `tabIndex` | `int` | 1-based |
| `url` | `String` | full YouTube URL |
| `pageTitle` | `String` | `document.title` |
| `metricsCollectedAt` | `long` | epoch ms |
| `errorMessage` | `String` | null on success |
| `pageLoadTime` | `long` | ms, Navigation Timing loadEventEnd |
| `domContentLoadedTime` | `long` | ms |
| `timeToFirstByte` | `long` | ms, responseStart |
| `dnsLookupTime` | `long` | ms |
| `tcpConnectionTime` | `long` | ms |
| `domInteractiveTime` | `long` | ms |
| `videoFound` | `boolean` | HTMLVideoElement found in DOM |
| `currentTime` | `double` | seconds played |
| `duration` | `double` | total duration s |
| `bufferedSeconds` | `double` | furthest buffered position |
| `readyState` | `int` | 0–4 (HTMLMediaElement enum) |
| `networkState` | `int` | 0–3 (HTMLMediaElement enum) |
| `qualityLabel` | `String` | YouTube quality string (e.g. "1080p60") |
| Bandwidth fields | — | See `NetworkSample` |

### `NetworkSample` — one 5-second sweep for one YouTube tab
| Field | Type | Notes |
|---|---|---|
| `sampleNumber` | `int` | 1-based |
| `timestamp` | `long` | epoch ms |
| `totalSegmentBytes` | `long` | cumulative video bytes |
| `recentSegmentBytes` | `long` | bytes in last sweep window |
| `recentSegmentCount` | `int` | segment requests in window |
| `totalSegmentCount` | `int` | cumulative segment requests |
| `bandwidthKBps` | `double` | estimated KB/s this window |
| `videoCurrentTime` | `double` | seconds played |
| `videoBuffered` | `double` | buffered position s |
| `qualityLabel` | `String` | per-sweep quality |
| `adPlaying` | `boolean` | true → quality data omitted |

### `StatsForNerdsData` — YouTube Stats-for-Nerds overlay values
| Field | Type | Notes |
|---|---|---|
| `connectionSpeedKbps` | `double` | live connection speed from overlay |
| `resolution` | `String` | e.g. "1920x1080@60" |
| `codec` | `String` | video codec string |
| `droppedFrames` | `int` | cumulative dropped frames |
| `totalFrames` | `int` | cumulative total frames |

---

## Website Probe Data

### `WebsiteMetrics` — one refresh cycle for one website tab
| Field | Type | Notes |
|---|---|---|
| `url` | `String` | full URL |
| `domain` | `String` | bare hostname |
| `pageTitle` | `String` | `document.title` |
| `tabIndex` | `int` | 1-based |
| `refreshCycle` | `int` | 1 = cold, 2+ = warm |
| `timestamp` | `long` | epoch ms |
| `pageLoadTime` | `long` | ms, -1 if unavailable |
| `timeToFirstByte` | `long` | ms, -1 if unavailable |
| `domContentLoaded` | `long` | ms, -1 if unavailable |
| `dnsLookupTime` | `long` | ms, -1 if unavailable |
| `tcpConnectionTime` | `long` | ms, -1 if unavailable |
| `domInteractiveTime` | `long` | ms, -1 if unavailable |
| `ipv4Reachable` | `boolean` | TCP port 443 probe over IPv4 |
| `ipv6Reachable` | `boolean` | TCP port 443 probe over IPv6 |
| `ipv4Address` | `String` | first A record; null if none |
| `ipv6Address` | `String` | first AAAA record; null if none |
| `ipv4ConnectMs` | `long` | ms, -1 if not probed |
| `ipv6ConnectMs` | `long` | ms, -1 if not probed |
| `success` | `boolean` | true = page loaded + timing collected |
| `errorMessage` | `String` | null on success |

---

## DNS Probe Data

### `DnsResult` — single UDP DNS query result
| Field | Type | Notes |
|---|---|---|
| `recordType` | `String` | "A" or "AAAA" |
| `domain` | `String` | FQDN queried |
| `timestamp` | `long` | epoch ms of query dispatch |
| `responseTimeMs` | `long` | true UDP round-trip latency |
| `resolvedAddresses` | `List<String>` | answer section IPs; may be empty |
| `success` | `boolean` | RCODE 0 = true |
| `errorMessage` | `String` | "Timed out" / "SERVFAIL" / "NXDOMAIN" / null |

---

## Evaluation Data

### `CheckResult` — one quality check verdict (immutable)
| Field | Type | Example |
|---|---|---|
| `checkName` | `String` | "TTFB" |
| `expected` | `String` | "<= 1500 ms" |
| `actual` | `String` | "324 ms" |
| `passed` | `boolean` | true |

### `VideoVerdict` — all checks for one YouTube tab
| Field/Method | Notes |
|---|---|
| `metrics` | The `VideoMetrics` that was evaluated |
| `checks` | `List<CheckResult>` in evaluation order |
| `isPassed()` | true only if every check passes |
| `passCount()` | number of passing checks |
| `failCount()` | number of failing checks |

---

## Statistics

### `StatisticsEngine.Stats` — descriptive stats for a sample
| Field | Type | Notes |
|---|---|---|
| `count` | `int` | sample size |
| `mean` | `double` | arithmetic mean |
| `stddev` | `double` | sample stddev (Bessel's correction) |
| `p50` | `double` | median |
| `p95` | `double` | 95th percentile |
| `reliable` | `boolean` | count ≥ 10 |

### `StatisticsEngine.Grade` enum
`NORMAL` → `ELEVATED` → `HIGH` → `CRITICAL`
Thresholds: mean±1σ / mean+2σ / mean+3σ
