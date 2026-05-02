package com.youtube.perf;

import java.util.List;

/**
 * Overall verdict for one YouTube video tab, composed of individual check results.
 */
public class VideoVerdict {

    private final VideoMetrics metrics;
    private final List<CheckResult> checks;

    /**
     * Creates a new {@code VideoVerdict}.
     *
     * @param metrics the raw metrics collected for this tab
     * @param checks  the list of individual check results that make up the verdict
     */
    public VideoVerdict(VideoMetrics metrics, List<CheckResult> checks) {
        this.metrics = metrics;
        this.checks  = checks;
    }

    /** Returns the raw metrics that were evaluated to produce this verdict. */
    public VideoMetrics getMetrics() { return metrics; }

    /** Returns the list of individual check results in evaluation order. */
    public List<CheckResult> getChecks() { return checks; }

    /** PASS only when every individual check passes. */
    public boolean isPassed() {
        return checks.stream().allMatch(CheckResult::isPassed);
    }

    /**
     * Returns the number of checks that passed.
     *
     * @return the count of passed checks
     */
    public long passCount() { return checks.stream().filter(CheckResult::isPassed).count(); }

    /**
     * Returns the number of checks that failed.
     *
     * @return the count of failed checks
     */
    public long failCount() { return checks.stream().filter(c -> !c.isPassed()).count(); }
}
