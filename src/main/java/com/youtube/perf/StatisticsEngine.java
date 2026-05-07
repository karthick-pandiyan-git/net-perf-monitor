package com.youtube.perf;

import java.util.List;

/**
 * Utility that computes descriptive statistics for a sample of values and
 * classifies individual observations against those statistics.
 *
 * <p><b>Short-run vs long-run reliability</b></p>
 * <ul>
 *   <li>&lt; 3 samples  — statistics skipped; only absolute thresholds apply.</li>
 *   <li>3–9 samples     — statistics computed but flagged as {@code limited};
 *       the outlier gate is tightened to {@code mean + 3σ} to avoid false positives
 *       with small-sample variance.</li>
 *   <li>≥ 10 samples    — full confidence; outlier gate is {@code mean + 2σ}.</li>
 * </ul>
 *
 * <p>All computations use Bessel's correction (divides by n−1) for an unbiased
 * sample standard deviation estimate.</p>
 */
public class StatisticsEngine {

    /** Minimum samples for {@link Stats#isReliable()} to return {@code true}. */
    public static final int MIN_RELIABLE_SAMPLES = 10;

    /** Below this count statistics are computed but treated as low-confidence. */
    public static final int MIN_USABLE_SAMPLES = 3;

    // ── Grade enum ────────────────────────────────────────────────────────────

    /**
     * Classification of a single observed value relative to the sample distribution.
     * NORMAL through CRITICAL run from "within expected variance" to "extreme outlier".
     */
    public enum Grade {
        /** Value is within mean ± 1σ of the distribution. */
        NORMAL,
        /** Value is between mean+1σ and mean+2σ — slightly elevated. */
        ELEVATED,
        /** Value exceeds mean+2σ — statistically anomalous (high-side). */
        HIGH,
        /** Value exceeds mean+3σ — extreme outlier. */
        CRITICAL
    }

    // ── Stats record ──────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of descriptive statistics for a sample.
     */
    public static final class Stats {

        /** Number of values in the sample. */
        public final int    n;
        /** Arithmetic mean (average). */
        public final double mean;
        /** Sample standard deviation (Bessel-corrected). */
        public final double stddev;
        /** Minimum observed value. */
        public final double min;
        /** Maximum observed value. */
        public final double max;
        /**
         * Coefficient of variation (stddev / mean). Measures relative variability.
         * A CV &gt; 1.0 means the standard deviation exceeds the mean — very high spread.
         */
        public final double cv;

        private Stats(int n, double mean, double stddev, double min, double max) {
            this.n      = n;
            this.mean   = mean;
            this.stddev = stddev;
            this.min    = min;
            this.max    = max;
            this.cv     = (mean > 0) ? stddev / mean : 0.0;
        }

        /**
         * Returns {@code true} when the sample is large enough
         * ({@code n >= MIN_RELIABLE_SAMPLES}) for high-confidence statistical conclusions.
         */
        public boolean isReliable() { return n >= MIN_RELIABLE_SAMPLES; }

        /**
         * Returns {@code true} when there are at least {@link StatisticsEngine#MIN_USABLE_SAMPLES}
         * observations — enough to compute a meaningful (if limited) estimate.
         */
        public boolean isUsable() { return n >= MIN_USABLE_SAMPLES; }

        /**
         * The sigma multiplier to use for outlier detection, adjusted for sample size.
         * Small samples use a stricter 3σ gate to reduce false positives.
         */
        public double outlierSigma() { return isReliable() ? 2.0 : 3.0; }

        /** Upper control limit: mean + k·stddev. */
        public double ucl(double k) { return mean + k * stddev; }

        /** Lower control limit: max(0, mean − k·stddev). */
        public double lcl(double k) { return Math.max(0, mean - k * stddev); }

        /**
         * Human-readable one-line summary, e.g.
         * {@code "n=12 μ=145.2 ms σ=23.1 ms CV=0.16 [110–210]"}.
         */
        @Override
        public String toString() {
            return String.format("n=%d  μ=%.1f  σ=%.1f  CV=%.2f  [%.1f–%.1f]%s",
                n, mean, stddev, cv, min, max,
                isReliable() ? "" : "  (limited)");
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Computes statistics for the supplied sample.
     *
     * @param values list of observed numeric values; {@code null} / empty → returns {@code null}
     * @return a {@link Stats} object, or {@code null} when there are fewer than
     *         {@link #MIN_USABLE_SAMPLES} values
     */
    public static Stats compute(List<Double> values) {
        if (values == null || values.size() < MIN_USABLE_SAMPLES) return null;

        int    n    = values.size();
        double sum  = values.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / n;

        // Bessel's correction: divide by n-1 for unbiased sample variance
        double variance = values.stream()
            .mapToDouble(v -> (v - mean) * (v - mean))
            .sum() / (n - 1);
        double stddev = Math.sqrt(variance);

        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        return new Stats(n, mean, stddev, min, max);
    }

    // ── Classification ────────────────────────────────────────────────────────

    /**
     * Classifies a single value on the high side of the distribution.
     * Returns {@link Grade#NORMAL} when stats is {@code null} or stddev is zero.
     *
     * @param value the observed value
     * @param stats the reference distribution
     * @return the grade reflecting how many standard deviations above the mean {@code value} is
     */
    public static Grade classify(double value, Stats stats) {
        if (stats == null || stats.stddev == 0) return Grade.NORMAL;
        double z = (value - stats.mean) / stats.stddev;
        if (z >= 3.0) return Grade.CRITICAL;
        if (z >= 2.0) return Grade.HIGH;
        if (z >= 1.0) return Grade.ELEVATED;
        return Grade.NORMAL;
    }

    /**
     * Returns {@code true} when {@code value} is an outlier on the high side
     * (latency, load time, etc.) using the sample-size-adjusted sigma gate from
     * {@link Stats#outlierSigma()}.
     *
     * @param value the observed value
     * @param stats the reference distribution (must not be {@code null})
     */
    public static boolean isHighOutlier(double value, Stats stats) {
        if (stats == null || !stats.isUsable()) return false;
        return value > stats.ucl(stats.outlierSigma());
    }

    /**
     * Returns {@code true} when {@code value} is an outlier on the low side
     * (bandwidth, buffer depth, etc.) using the sample-size-adjusted sigma gate.
     *
     * @param value the observed value
     * @param stats the reference distribution (must not be {@code null})
     */
    public static boolean isLowOutlier(double value, Stats stats) {
        if (stats == null || !stats.isUsable()) return false;
        return value < stats.lcl(stats.outlierSigma());
    }

    /**
     * Compact label for display purposes, e.g. {@code "μ=145 ms  σ=23 ms"}.
     * Returns {@code "(n/a)"} when stats is {@code null}.
     */
    public static String label(Stats stats) {
        if (stats == null) return "(n/a)";
        return String.format("μ=%.0f  σ=%.0f  CV=%.2f%s",
            stats.mean, stats.stddev, stats.cv,
            stats.isReliable() ? "" : "  [limited n=" + stats.n + "]");
    }

    /**
     * Counts the number of values in {@code values} that are high-side outliers
     * according to {@code stats}.
     */
    public static long countHighOutliers(List<Double> values, Stats stats) {
        if (stats == null || values == null) return 0;
        return values.stream().filter(v -> isHighOutlier(v, stats)).count();
    }
}
