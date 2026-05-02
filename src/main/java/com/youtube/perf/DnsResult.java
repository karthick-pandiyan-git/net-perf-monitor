package com.youtube.perf;

import java.util.List;

/**
 * Result of a single DNS query (one tool, one domain, one point in time).
 */
public class DnsResult {

    /** "nslookup" or "dig". */
    private String tool;
    private String domain;
    private long timestamp;

    /**
     * Query response time in milliseconds.
     * For nslookup: total process wall-clock time (nslookup does not expose query time).
     * For dig: value parsed from the "Query time: X msec" line in the output.
     */
    private long responseTimeMs;

    private List<String> resolvedAddresses;
    private boolean success;
    /** Full command output, useful for debugging. May be null on error. */
    private String rawOutput;
    private String errorMessage;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getTool()                         { return tool; }
    public void   setTool(String tool)              { this.tool = tool; }

    public String getDomain()                       { return domain; }
    public void   setDomain(String domain)          { this.domain = domain; }

    public long getTimestamp()                      { return timestamp; }
    public void setTimestamp(long timestamp)        { this.timestamp = timestamp; }

    public long getResponseTimeMs()                 { return responseTimeMs; }
    public void setResponseTimeMs(long ms)          { this.responseTimeMs = ms; }

    public List<String> getResolvedAddresses()      { return resolvedAddresses; }
    public void setResolvedAddresses(List<String> a){ this.resolvedAddresses = a; }

    public boolean isSuccess()                      { return success; }
    public void    setSuccess(boolean success)      { this.success = success; }

    public String getRawOutput()                    { return rawOutput; }
    public void   setRawOutput(String raw)          { this.rawOutput = raw; }

    public String getErrorMessage()                 { return errorMessage; }
    public void   setErrorMessage(String msg)       { this.errorMessage = msg; }
}
