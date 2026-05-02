package com.youtube.perf;

/**
 * Result of a single quality check against one video tab.
 */
public class CheckResult {

    private final String checkName;
    private final String expected;
    private final String actual;
    private final boolean passed;

    /**
     * Creates an immutable check result.
     *
     * @param checkName human-readable name of the quality check
     * @param expected  description of the pass criterion (e.g. {@code "<= 200 ms"})
     * @param actual    the measured value as a string (e.g. {@code "145 ms"})
     * @param passed    {@code true} when the actual value satisfies the expected criterion
     */
    public CheckResult(String checkName, String expected, String actual, boolean passed) {
        this.checkName = checkName;
        this.expected  = expected;
        this.actual    = actual;
        this.passed    = passed;
    }

    /** Returns the human-readable name of this check. */
    public String getCheckName() { return checkName; }

    /** Returns the description of the pass criterion. */
    public String getExpected()  { return expected; }

    /** Returns the measured value as a string. */
    public String getActual()    { return actual; }

    /** Returns {@code true} when the measured value satisfies the pass criterion. */
    public boolean isPassed()    { return passed; }
}
