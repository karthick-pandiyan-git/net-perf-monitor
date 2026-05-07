package com.youtube.perf;

/**
 * Performance snapshot for one website load (one refresh cycle of one tab).
 * Navigation Timing API values are collected via JavaScript after the page finishes loading.
 *
 * <p>Negative values ({@code -1}) for timing fields indicate that the metric
 * was not available \u2014 either because the page failed to load or the Navigation
 * Timing API did not expose that value.</p>
 */
public class WebsiteMetrics {

    /** Full URL of the page that was navigated to. */
    private String url;
    /** Hostname extracted from the URL (e.g. {@code "facebook.com"}). */
    private String domain;
    /** {@code document.title} of the loaded page, or {@code null} if the page failed to load. */
    private String pageTitle;
    /** 1-based index of this tab within the website tester. */
    private int tabIndex;
    /** 1-based refresh cycle number (1 = cold load, 2+ = warm re-navigations). */
    private int refreshCycle;
    /** Epoch-millisecond timestamp when this metrics snapshot was collected. */
    private long timestamp;

    // ── Navigation Timing fields (milliseconds, -1 = not available) ──────────
    /** Time from navigation start to full page load (loadEventEnd). */
    private long pageLoadTime      = -1;
    /** Time from navigation start to first byte received (responseStart). */
    private long timeToFirstByte   = -1;
    /** Time from navigation start to DOMContentLoaded event. */
    private long domContentLoaded  = -1;
    /** DNS resolution time for this navigation. */
    private long dnsLookupTime     = -1;
    /** TCP connection establishment time. */
    private long tcpConnectionTime = -1;
    /** Time from navigation start to DOM interactive state. */
    private long domInteractiveTime = -1;

    // ── IPv4 / IPv6 TCP reachability probes (port 443, Java-side) ────────────
    /** True if a TCP connection to port 443 over IPv4 (A record) succeeded. */
    private boolean ipv4Reachable;
    /** True if a TCP connection to port 443 over IPv6 (AAAA record) succeeded. */
    private boolean ipv6Reachable;
    /** First IPv4 address resolved for the domain, or null if no A record found. */
    private String  ipv4Address;
    /** First IPv6 address resolved for the domain, or null if no AAAA record found. */
    private String  ipv6Address;
    /** TCP connect time to the IPv4 address in ms; -1 if probe was not run. */
    private long    ipv4ConnectMs = -1;
    /** TCP connect time to the IPv6 address in ms; -1 if probe was not run. */
    private long    ipv6ConnectMs = -1;

    /** {@code true} when the page navigated successfully and Navigation Timing metrics were collected. */
    private boolean success;

    /**
     * Human-readable description of the navigation error, or {@code null} on success.
     * Common values: page-load timeout message, JavaScript execution errors, or browser crash text.
     */
    private String errorMessage;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    /** Returns the full URL of the page that was loaded. */
    public String getUrl()                      { return url; }
    /** Sets the full URL for this metrics record. */
    public void   setUrl(String url)            { this.url = url; }

    /** Returns the hostname portion of the URL (e.g. {@code "facebook.com"}). */
    public String getDomain()                   { return domain; }
    /** Sets the hostname (domain) of the monitored page. */
    public void   setDomain(String domain)      { this.domain = domain; }

    /** Returns the page title ({@code document.title}), or {@code null} if the page failed to load. */
    public String getPageTitle()                { return pageTitle; }
    /** Sets the page title. */
    public void   setPageTitle(String t)        { this.pageTitle = t; }

    /** Returns the 1-based tab index within the website tester. */
    public int  getTabIndex()                   { return tabIndex; }
    /** Sets the 1-based tab index. */
    public void setTabIndex(int tabIndex)       { this.tabIndex = tabIndex; }

    /** Returns the 1-based refresh cycle number (1 = cold load, 2+ = warm re-navigation). */
    public int  getRefreshCycle()               { return refreshCycle; }
    /** Sets the refresh cycle number for this metrics record. */
    public void setRefreshCycle(int c)          { this.refreshCycle = c; }

    /** Returns the epoch-millisecond timestamp when this snapshot was collected. */
    public long getTimestamp()                  { return timestamp; }
    /** Sets the collection timestamp in epoch milliseconds. */
    public void setTimestamp(long timestamp)    { this.timestamp = timestamp; }

    /** Returns the Navigation Timing page-load time in milliseconds ({@code -1} if unavailable). */
    public long getPageLoadTime()               { return pageLoadTime; }
    /** Sets the page-load time in milliseconds. */
    public void setPageLoadTime(long v)         { this.pageLoadTime = v; }

    /** Returns the Navigation Timing TTFB in milliseconds ({@code -1} if unavailable). */
    public long getTimeToFirstByte()            { return timeToFirstByte; }
    /** Sets the time-to-first-byte in milliseconds. */
    public void setTimeToFirstByte(long v)      { this.timeToFirstByte = v; }

    /** Returns the Navigation Timing DOMContentLoaded time in milliseconds ({@code -1} if unavailable). */
    public long getDomContentLoaded()           { return domContentLoaded; }
    /** Sets the DOMContentLoaded time in milliseconds. */
    public void setDomContentLoaded(long v)     { this.domContentLoaded = v; }

    /** Returns the Navigation Timing DNS lookup duration in milliseconds ({@code -1} if unavailable). */
    public long getDnsLookupTime()              { return dnsLookupTime; }
    /** Sets the DNS lookup duration in milliseconds. */
    public void setDnsLookupTime(long v)        { this.dnsLookupTime = v; }

    /** Returns the Navigation Timing TCP connection establishment time in milliseconds ({@code -1} if unavailable). */
    public long getTcpConnectionTime()          { return tcpConnectionTime; }
    /** Sets the TCP connection time in milliseconds. */
    public void setTcpConnectionTime(long v)    { this.tcpConnectionTime = v; }

    /** Returns the Navigation Timing DOM-interactive time in milliseconds ({@code -1} if unavailable). */
    public long getDomInteractiveTime()         { return domInteractiveTime; }
    /** Sets the DOM-interactive time in milliseconds. */
    public void setDomInteractiveTime(long v)   { this.domInteractiveTime = v; }

    /** Returns {@code true} if a TCP connection to port 443 over IPv4 (A record) succeeded. */
    public boolean isIpv4Reachable()            { return ipv4Reachable; }
    /** Sets the IPv4 TCP reachability result. */
    public void    setIpv4Reachable(boolean v)  { this.ipv4Reachable = v; }

    /** Returns {@code true} if a TCP connection to port 443 over IPv6 (AAAA record) succeeded. */
    public boolean isIpv6Reachable()            { return ipv6Reachable; }
    /** Sets the IPv6 TCP reachability result. */
    public void    setIpv6Reachable(boolean v)  { this.ipv6Reachable = v; }

    /** Returns the first IPv4 address resolved for this domain, or {@code null} if no A record found. */
    public String getIpv4Address()              { return ipv4Address; }
    /** Sets the resolved IPv4 address. */
    public void   setIpv4Address(String v)      { this.ipv4Address = v; }

    /** Returns the first IPv6 address resolved for this domain, or {@code null} if no AAAA record found. */
    public String getIpv6Address()              { return ipv6Address; }
    /** Sets the resolved IPv6 address. */
    public void   setIpv6Address(String v)      { this.ipv6Address = v; }

    /** Returns the TCP connect latency to the IPv4 address in ms, or {@code -1} if not probed. */
    public long getIpv4ConnectMs()              { return ipv4ConnectMs; }
    /** Sets the IPv4 TCP connect latency in milliseconds. */
    public void setIpv4ConnectMs(long v)        { this.ipv4ConnectMs = v; }

    /** Returns the TCP connect latency to the IPv6 address in ms, or {@code -1} if not probed. */
    public long getIpv6ConnectMs()              { return ipv6ConnectMs; }
    /** Sets the IPv6 TCP connect latency in milliseconds. */
    public void setIpv6ConnectMs(long v)        { this.ipv6ConnectMs = v; }

    /** Returns {@code true} when the page navigated successfully and metrics were collected. */
    public boolean isSuccess()                  { return success; }
    /** Sets the success flag. Should be {@code true} only when timing values are valid. */
    public void    setSuccess(boolean success)  { this.success = success; }

    /** Returns the error description, or {@code null} when {@link #isSuccess()} is {@code true}. */
    public String getErrorMessage()             { return errorMessage; }
    /** Sets the navigation error description. */
    public void   setErrorMessage(String msg)   { this.errorMessage = msg; }
}
