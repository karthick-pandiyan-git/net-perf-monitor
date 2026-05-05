package com.youtube.perf;

import java.util.List;

/**
 * Result of a single DNS query (one record type, one domain, one point in time).
 */
public class DnsResult {

    /** DNS record type queried: "A" (IPv4) or "AAAA" (IPv6). */
    private String recordType;
    private String domain;
    private long timestamp;

    /** Query response time in milliseconds — true round-trip latency to the resolver. */
    private long responseTimeMs;

    private List<String> resolvedAddresses;
    private boolean success;
    private String errorMessage;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getRecordType()                   { return recordType; }
    public void   setRecordType(String recordType)  { this.recordType = recordType; }

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

    public String getErrorMessage()                 { return errorMessage; }
    public void   setErrorMessage(String msg)       { this.errorMessage = msg; }
}
