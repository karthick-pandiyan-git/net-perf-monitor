# Conventions — net-perf-monitor

> Coding patterns used in this project. Follow these when adding or modifying code.

---

## Package & Naming

- **Single package**: all production classes in `com.youtube.perf` (no sub-packages).
- **Class names**: PascalCase nouns that describe what they model (`VideoMetrics`, `DnsResult`) or what they do (`PerformanceEvaluator`, `JsonReporter`).
- **Constants**: `SCREAMING_SNAKE_CASE`, declared `private static final` at the top of the class.
- **Logger**: each class declares `private static final Logger logger = LoggerFactory.getLogger(ClassName.class)`.

---

## Data Classes (Model / DTO Pattern)

- Private fields with explicit default values (`-1` for longs/ints meaning "not collected", `0.0` for doubles).
- Full getters/setters — no Lombok, no records (JDK 17 records are available but not used in this codebase to keep the style consistent).
- Javadoc on every field explaining the unit, valid range, and what `-1` / `null` means.

---

## Configuration Pattern

- All tuneable values go in `src/main/resources/test.properties`.
- `TestConfig` is the only class that touches `Properties`. No other class reads `test.properties` directly.
- CLI flags override property-file values — never the reverse.
- Duration is always clamped: `Math.max(MIN, Math.min(MAX, value))`.
- Per-site threshold keys: `website.threshold.<bare-hostname>.<metric>.<warmth>.ms`  
  Example: `website.threshold.americanexpress.com.pageload.warm.ms=8000`

---

## Concurrency Rules

- Worker classes (`YouTubePerformanceTester`, `WebsiteTester`, `DnsMonitor`) implement `Callable<T>` (not `Runnable`) so they can return typed results via `Future`.
- Shared live result lists are `CopyOnWriteArrayList` — write from one thread, read from another without locking.
- `CountDownLatch` is used (not `CyclicBarrier`) because it is one-shot.
- The executor is always shut down in a `finally` block after awaiting futures.

---

## Probe Sweep Interval

- Both YouTube and Website probes sweep every **5 seconds** — do not change this without updating `JsonReporter` and `HtmlReporter` (they derive time axes from the sweep count × interval).

---

## Thresholds — Cold vs Warm

- "Cold" = cycle/tab 1 (no browser cache, fresh TCP/TLS handshake).
- "Warm" = cycles/tabs 2+ (reused connections, possible OS/browser cache).
- Evaluators always check `tabIndex == 1` or `refreshCycle == 1` to select the right threshold.
- YouTube thresholds are hardcoded in `PerformanceEvaluator`.
- Website thresholds come from `WebsiteThresholds` (default or per-site override from `TestConfig`).

---

## Reporting Pattern

- `JsonReporter` writes to **stdout only** — nothing else should write to stdout during a monitoring run (logs go to stderr via SLF4J).
- `HtmlReporter` writes to `${workingDir}/net_performance_report.html`.
- `PerformanceReporter` and `WebsiteReporter` write console text tables to stdout before the JSON output.
- JSON snapshot numbering: `snapshotNum = 0` → final report; `snapshotNum > 0` → intermediate.

---

## Error Handling in Probes

- Exceptions inside a probe are caught, stored as `errorMessage` on the metrics object, and logged via `logger.error(...)`.
- A probe **never** throws from its `call()` method — it returns its (possibly partial) results so the other probes can still report.
- Browser probes always call `driver.quit()` in a `finally` block.

---

## HTML Report

- `HtmlReporter` generates a **self-contained** file — all Chart.js assets are loaded from a CDN link, not embedded.
- Charts use `Chart.js` via CDN — do not add new charting libraries.
- Report file name is always `net_performance_report.html` (hardcoded, not configurable).

---

## Windows UTF-8

- `run.ps1` must always precede the `java` invocation with UTF-8 encoding setup.
- `Main` calls `SetConsoleOutputCP(65001)` via JNA for cmd.exe callers.
- `System.out` and `System.err` are rewrapped with `StandardCharsets.UTF_8` in `Main`.
- **Do not use** `System.out.println` outside of `Main`'s initial setup and reporter classes — use `logger` everywhere else.

---

## Test / Standalone Utilities

- `GenerateTestReport` is a standalone `main()` class for re-rendering the HTML from a JSON file. It is **not** part of the main monitoring flow.
- No unit test classes currently exist in `src/test/`.

---

## Adding a New Probe

See `COMMON_TASKS.md` for the step-by-step guide.

---

## File Naming

- All source files: PascalCase `.java` (Java standard).
- Config: lowercase `test.properties`.
- Build script: lowercase `run.ps1` with a `.ps1` extension.
- AI docs: `SCREAMING_CASE.md` in `docs/`.
