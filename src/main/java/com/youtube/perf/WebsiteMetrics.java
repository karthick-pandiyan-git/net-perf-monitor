package com.youtube.perf;

/**
 * Performance snapshot for one website load (one refresh cycle of one tab).
 * Navigation Timing API values are collected via JavaScript after the page finishes loading.
 */
public class WebsiteMetrics {

    private String url;
    /** Hostname extracted from the URL (e.g. "facebook.com"). */
    private String domain;
    private String pageTitle;
    /** 1-based index of this tab within the website tester. */
    private int tabIndex;
    /** 1-based refresh cycle number (1–6 over the 30 s window). */
    private int refreshCycle;
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

    private boolean success;
    private String errorMessage;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getUrl()                      { return url; }
    public void   setUrl(String url)            { this.url = url; }

    public String getDomain()                   { return domain; }
    public void   setDomain(String domain)      { this.domain = domain; }

    public String getPageTitle()                { return pageTitle; }
    public void   setPageTitle(String t)        { this.pageTitle = t; }

    public int  getTabIndex()                   { return tabIndex; }
    public void setTabIndex(int tabIndex)       { this.tabIndex = tabIndex; }

    public int  getRefreshCycle()               { return refreshCycle; }
    public void setRefreshCycle(int c)          { this.refreshCycle = c; }

    public long getTimestamp()                  { return timestamp; }
    public void setTimestamp(long timestamp)    { this.timestamp = timestamp; }

    public long getPageLoadTime()               { return pageLoadTime; }
    public void setPageLoadTime(long v)         { this.pageLoadTime = v; }

    public long getTimeToFirstByte()            { return timeToFirstByte; }
    public void setTimeToFirstByte(long v)      { this.timeToFirstByte = v; }

    public long getDomContentLoaded()           { return domContentLoaded; }
    public void setDomContentLoaded(long v)     { this.domContentLoaded = v; }

    public long getDnsLookupTime()              { return dnsLookupTime; }
    public void setDnsLookupTime(long v)        { this.dnsLookupTime = v; }

    public long getTcpConnectionTime()          { return tcpConnectionTime; }
    public void setTcpConnectionTime(long v)    { this.tcpConnectionTime = v; }

    public long getDomInteractiveTime()         { return domInteractiveTime; }
    public void setDomInteractiveTime(long v)   { this.domInteractiveTime = v; }

    public boolean isIpv4Reachable()            { return ipv4Reachable; }
    public void    setIpv4Reachable(boolean v)  { this.ipv4Reachable = v; }

    public boolean isIpv6Reachable()            { return ipv6Reachable; }
    public void    setIpv6Reachable(boolean v)  { this.ipv6Reachable = v; }

    public String getIpv4Address()              { return ipv4Address; }
    public void   setIpv4Address(String v)      { this.ipv4Address = v; }

    public String getIpv6Address()              { return ipv6Address; }
    public void   setIpv6Address(String v)      { this.ipv6Address = v; }

    public long getIpv4ConnectMs()              { return ipv4ConnectMs; }
    public void setIpv4ConnectMs(long v)        { this.ipv4ConnectMs = v; }

    public long getIpv6ConnectMs()              { return ipv6ConnectMs; }
    public void setIpv6ConnectMs(long v)        { this.ipv6ConnectMs = v; }

    public boolean isSuccess()                  { return success; }
    public void    setSuccess(boolean success)  { this.success = success; }

    public String getErrorMessage()             { return errorMessage; }
    public void   setErrorMessage(String msg)   { this.errorMessage = msg; }
}
