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

    public boolean isSuccess()                  { return success; }
    public void    setSuccess(boolean success)  { this.success = success; }

    public String getErrorMessage()             { return errorMessage; }
    public void   setErrorMessage(String msg)   { this.errorMessage = msg; }
}
