package com.youtube.perf;

import java.util.List;

/**
 * Result of a single DNS query (one record type, one domain, one point in time).
 *
 * <p>All query results are captured using dnsjava's {@code SimpleResolver} targeting
 * Google Public DNS (8.8.8.8) over UDP, bypassing the OS resolver cache so each
 * record reflects a live internet connectivity probe.</p>
 */
public class DnsResult {

    /** DNS record type queried: {@code "A"} (IPv4) or {@code "AAAA"} (IPv6). */
    private String recordType;

    /** The fully-qualified domain name that was resolved (e.g. {@code "google.com"}). */
    private String domain;

    /** Epoch-millisecond timestamp when this query was dispatched. */
    private long timestamp;

    /** Query response time in milliseconds — true round-trip latency to the resolver. */
    private long responseTimeMs;

    /**
     * IP addresses returned in the answer section of the DNS response.
     * Populated only when {@link #success} is {@code true}. May be empty when
     * the resolver returns NOERROR with an empty answer (NXDOMAIN variant).
     */
    private List<String> resolvedAddresses;

    /** {@code true} when the resolver returned a non-error response code (RCODE 0). */
    private boolean success;

    /**
     * Human-readable error description when {@link #success} is {@code false}.
     * {@code null} on successful queries. Common values:
     * <ul>
     *   <li>{@code "Timed out"} — resolver did not respond within the configured timeout</li>
     *   <li>{@code "SERVFAIL"} / {@code "NXDOMAIN"} — upstream resolver returned an error code</li>
     * </ul>
     */
    private String errorMessage;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    /** Returns the DNS record type queried ({@code "A"} or {@code "AAAA"}). */
    public String getRecordType()                   { return recordType; }
    /** Sets the DNS record type ({@code "A"} or {@code "AAAA"}). */
    public void   setRecordType(String recordType)  { this.recordType = recordType; }

    /** Returns the fully-qualified domain name resolved in this query. */
    public String getDomain()                       { return domain; }
    /** Sets the domain name for this query result. */
    public void   setDomain(String domain)          { this.domain = domain; }

    /** Returns the epoch-millisecond timestamp when this query was dispatched. */
    public long getTimestamp()                      { return timestamp; }
    /** Sets the epoch-millisecond dispatch timestamp. */
    public void setTimestamp(long timestamp)        { this.timestamp = timestamp; }

    /** Returns the query response time in milliseconds (round-trip latency to the resolver). */
    public long getResponseTimeMs()                 { return responseTimeMs; }
    /** Sets the query response time in milliseconds. */
    public void setResponseTimeMs(long ms)          { this.responseTimeMs = ms; }

    /** Returns the list of IP addresses returned in the DNS answer, or an empty list on failure. */
    public List<String> getResolvedAddresses()      { return resolvedAddresses; }
    /** Sets the list of resolved IP addresses. */
    public void setResolvedAddresses(List<String> a){ this.resolvedAddresses = a; }

    /** Returns {@code true} when the resolver returned a successful response. */
    public boolean isSuccess()                      { return success; }
    /** Sets the success flag for this query result. */
    public void    setSuccess(boolean success)      { this.success = success; }

    /** Returns the error description, or {@code null} when the query succeeded. */
    public String getErrorMessage()                 { return errorMessage; }
    /** Sets the error description. Should be {@code null} when {@link #isSuccess()} is {@code true}. */
    public void   setErrorMessage(String msg)       { this.errorMessage = msg; }
}
