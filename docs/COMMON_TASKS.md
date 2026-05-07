# Common Tasks — net-perf-monitor

> Step-by-step guides for frequent modifications. Read `ARCHITECTURE.md` and `DOMAIN_MODEL.md` first.

---

## 1 — Add a New YouTube URL

1. Open `src/main/resources/test.properties`.
2. Add a new numbered entry:
   ```properties
   youtube.url.11=https://www.youtube.com/watch?v=NEW_ID
   ```
3. Rebuild: `mvn clean package -q`.
4. Run with `--mode youtube_11` to use all 11 URLs, or leave mode default (uses up to `youtubeCount`).

> **No Java changes needed.**

---

## 2 — Add a New Website URL

1. Open `src/main/resources/test.properties`.
2. Add a numbered entry:
   ```properties
   website.url.5=https://www.newsite.com
   ```
3. (Optional) Add per-site thresholds if the default [5 s warm / 8 s cold] are wrong for this URL:
   ```properties
   website.threshold.newsite.com.pageload.warm.ms=10000
   website.threshold.newsite.com.pageload.cold.ms=15000
   website.threshold.newsite.com.ttfb.warm.ms=2000
   website.threshold.newsite.com.ttfb.cold.ms=3000
   ```
4. Rebuild: `mvn clean package -q`.

> **No Java changes needed.**

---

## 3 — Add a New DNS Domain

1. Open `src/main/resources/test.properties`.
2. Add:
   ```properties
   dns.domain.5=example.com
   ```
   > Pick a domain with AAAA records (e.g. google.com, cloudflare.com, wikipedia.org) for IPv6 results. Domains without AAAA records (e.g. netflix.com in some regions) will always return empty IPv6 results.
3. Rebuild: `mvn clean package -q`.

> **No Java changes needed.**

---

## 4 — Change Default Monitoring Duration

1. Open `src/main/resources/test.properties`.
2. Edit:
   ```properties
   test.duration.seconds=60
   ```
3. Rebuild: `mvn clean package -q`.

> Override at runtime with `--duration 90` (no rebuild required).

---

## 5 — Add a New Per-Site Threshold

There are two levels of override:

**Option A — properties file (no rebuild)**:
Add keys to `test.properties`:
```properties
website.threshold.amazon.com.pageload.warm.ms=7000
website.threshold.amazon.com.ttfb.warm.ms=1500
```

**Option B — default threshold for all sites**:
Change `WebsiteThresholds.DEFAULT` in `WebsiteThresholds.java` and rebuild.

---

## 6 — Add a New Quality Check for YouTube

1. Open `PerformanceEvaluator.java`.
2. Add a new private constant for the threshold.
3. Add a new `private CheckResult check<Name>(VideoMetrics m, boolean isCold)` method.
4. Call it inside `evaluate(VideoMetrics m)` and append the result to the `checks` list.
5. Rebuild and run tests.
6. **Update `docs/ARCHITECTURE.md`** — add the new check to the thresholds table.

---

## 7 — Add a New Field to VideoMetrics

1. Add private field to `VideoMetrics.java` with Javadoc.
2. Generate getter + setter.
3. In `YouTubePerformanceTester.java`, collect the value during the sweep and call the setter.
4. In `JsonReporter.java`, add the field to the YouTube section of the JSON map.
5. In `HtmlReporter.java`, add the field to the HTML table if it should appear in the report.
6. **Update `docs/DOMAIN_MODEL.md`** — add the new field row to the `VideoMetrics` table.

---

## 8 — Add a New Field to WebsiteMetrics

1. Add private field to `WebsiteMetrics.java` with Javadoc.
2. Generate getter + setter.
3. In `WebsiteTester.java`, collect the value and call the setter.
4. In `JsonReporter.java`, add the field to the website section.
5. In `HtmlReporter.java`, add if it should appear in the report.
6. **Update `docs/DOMAIN_MODEL.md`**.

---

## 9 — Add a New Probe (e.g. Ping / Traceroute)

1. Create a new class `XyzProbe implements Callable<List<XyzResult>>` in `com.youtube.perf`.
2. Follow the same latch pattern:
   - Accept `CountDownLatch startLatch` in constructor; count down when ready to measure.
   - Accept `CountDownLatch stopLatch` if the probe is ongoing (like DNS); otherwise use duration.
3. Create an `XyzResult` data class (all fields with sensible defaults, getters/setters, Javadoc).
4. In `Main.java`:
   - Add `runXyz` boolean from `mode.contains("xyz")`.
   - Update pool size formula.
   - Submit `XyzProbe` to the executor.
   - Collect results via `Future.get()`.
5. Wire into `JsonReporter` and `HtmlReporter`.
6. Add `--mode xyz` to `run.ps1` usage comment.
7. **Update `docs/ARCHITECTURE.md`** (thread model + class map) and `docs/DOMAIN_MODEL.md`.

---

## 10 — Regenerate HTML Report from Saved JSON

```bash
java -cp target/net-perf-monitor-jar-with-dependencies.jar \
  com.youtube.perf.GenerateTestReport path/to/snapshot.json
```

This re-runs `HtmlReporter` from the JSON file without running any probes.

---

## 11 — Build and Verify

```bash
mvn clean package -q
.\run.ps1 --duration 30 --mode dns   # quick smoke test
```

Expected: JSON printed to stdout, `net_performance_report.html` written to working directory.
