package com.youtube.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all performance metrics collected from a single YouTube video tab.
 * Fields default to -1 (longs/ints) or 0.0 (doubles) to distinguish
 * "not collected" from a genuine zero reading.
 */
public class VideoMetrics {

    // ── Identity ─────────────────────────────────────────────────────────────
    /** 1-based index of this tab within the YouTube performance tester. */
    private int tabIndex;
    /** Full YouTube video URL opened in this tab. */
    private String url;
    /** {@code document.title} of the loaded video page (the video title shown in the browser tab). */
    private String pageTitle;
    /** Epoch-milliseconds when the final metrics snapshot was collected for this tab. */
    private long metricsCollectedAt;   // epoch ms
    /**
     * Error description if an exception prevented metrics collection, or {@code null}
     * when all fields were collected successfully.
     */
    private String errorMessage;

    // ── Page-load timing (all in milliseconds) ────────────────────────────────
    /** Total time from navigation start until the load event fires. */
    private long pageLoadTime = -1;
    /** Time from navigation start until DOMContentLoaded fires. */
    private long domContentLoadedTime = -1;
    /** Time from navigation start until first byte received (TTFB). */
    private long timeToFirstByte = -1;
    /** DNS lookup duration. */
    private long dnsLookupTime = -1;
    /** TCP handshake duration. */
    private long tcpConnectionTime = -1;
    /** Time from navigation start until the DOM became interactive. */
    private long domInteractiveTime = -1;

    // ── HTMLVideoElement state ────────────────────────────────────────────────
    private boolean videoFound;
    /** Seconds of video that have been played so far. */
    private double currentTime;
    /** Total video duration in seconds. */
    private double duration;
    /** Furthest buffered position in seconds. */
    private double bufferedSeconds;
    /**
     * HTMLMediaElement.readyState:
     * 0 HAVE_NOTHING, 1 HAVE_METADATA, 2 HAVE_CURRENT_DATA,
     * 3 HAVE_FUTURE_DATA, 4 HAVE_ENOUGH_DATA
     */
    private int readyState = -1;
    /**
     * HTMLMediaElement.networkState:
     * 0 NETWORK_EMPTY, 1 NETWORK_IDLE, 2 NETWORK_LOADING, 3 NETWORK_NO_SOURCE
     */
    private int networkState = -1;
    private double playbackRate;
    private int videoWidth;
    private int videoHeight;
    private boolean paused;
    private boolean ended;

    // ── VideoPlaybackQuality (via getVideoPlaybackQuality()) ──────────────────
    private long totalVideoFrames = -1;
    private long droppedVideoFrames = -1;
    private long corruptedVideoFrames = -1;

    // ── Live network performance ──────────────────────────────────────────────
    /** Periodic bandwidth snapshots taken during playback. */
    private List<NetworkSample> networkSamples = new ArrayList<>();
    /** Peak KB/s observed across all samples. */
    private double peakBandwidthKBps = 0;
    /** Average KB/s across all samples. */
    private double avgBandwidthKBps = 0;
    /** Total video segment bytes transferred (from last sample's cumulative total). */
    private long totalVideoSegmentBytes = -1;

    // ── Derived ───────────────────────────────────────────────────────────────
    /**
     * Computes the frame-drop rate as a percentage of total decoded frames.
     * Returns {@code 0.0} when total frames is zero or negative to avoid division by zero.
     *
     * @return the dropped frame rate in percent (0.0 – 100.0)
     */
    public double getDroppedFrameRatePct() {
        if (totalVideoFrames <= 0) return 0.0;
        return 100.0 * droppedVideoFrames / totalVideoFrames;
    }

    // ── Getters & setters ─────────────────────────────────────────────────────

    public int getTabIndex() { return tabIndex; }
    public void setTabIndex(int tabIndex) { this.tabIndex = tabIndex; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }

    public long getMetricsCollectedAt() { return metricsCollectedAt; }
    public void setMetricsCollectedAt(long metricsCollectedAt) { this.metricsCollectedAt = metricsCollectedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getPageLoadTime() { return pageLoadTime; }
    public void setPageLoadTime(long pageLoadTime) { this.pageLoadTime = pageLoadTime; }

    public long getDomContentLoadedTime() { return domContentLoadedTime; }
    public void setDomContentLoadedTime(long domContentLoadedTime) { this.domContentLoadedTime = domContentLoadedTime; }

    public long getTimeToFirstByte() { return timeToFirstByte; }
    public void setTimeToFirstByte(long timeToFirstByte) { this.timeToFirstByte = timeToFirstByte; }

    public long getDnsLookupTime() { return dnsLookupTime; }
    public void setDnsLookupTime(long dnsLookupTime) { this.dnsLookupTime = dnsLookupTime; }

    public long getTcpConnectionTime() { return tcpConnectionTime; }
    public void setTcpConnectionTime(long tcpConnectionTime) { this.tcpConnectionTime = tcpConnectionTime; }

    public long getDomInteractiveTime() { return domInteractiveTime; }
    public void setDomInteractiveTime(long domInteractiveTime) { this.domInteractiveTime = domInteractiveTime; }

    public boolean isVideoFound() { return videoFound; }
    public void setVideoFound(boolean videoFound) { this.videoFound = videoFound; }

    public double getCurrentTime() { return currentTime; }
    public void setCurrentTime(double currentTime) { this.currentTime = currentTime; }

    public double getDuration() { return duration; }
    public void setDuration(double duration) { this.duration = duration; }

    public double getBufferedSeconds() { return bufferedSeconds; }
    public void setBufferedSeconds(double bufferedSeconds) { this.bufferedSeconds = bufferedSeconds; }

    public int getReadyState() { return readyState; }
    public void setReadyState(int readyState) { this.readyState = readyState; }

    public int getNetworkState() { return networkState; }
    public void setNetworkState(int networkState) { this.networkState = networkState; }

    public double getPlaybackRate() { return playbackRate; }
    public void setPlaybackRate(double playbackRate) { this.playbackRate = playbackRate; }

    public int getVideoWidth() { return videoWidth; }
    public void setVideoWidth(int videoWidth) { this.videoWidth = videoWidth; }

    public int getVideoHeight() { return videoHeight; }
    public void setVideoHeight(int videoHeight) { this.videoHeight = videoHeight; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public boolean isEnded() { return ended; }
    public void setEnded(boolean ended) { this.ended = ended; }

    public long getTotalVideoFrames() { return totalVideoFrames; }
    public void setTotalVideoFrames(long totalVideoFrames) { this.totalVideoFrames = totalVideoFrames; }

    public long getDroppedVideoFrames() { return droppedVideoFrames; }
    public void setDroppedVideoFrames(long droppedVideoFrames) { this.droppedVideoFrames = droppedVideoFrames; }

    public long getCorruptedVideoFrames() { return corruptedVideoFrames; }
    public void setCorruptedVideoFrames(long corruptedVideoFrames) { this.corruptedVideoFrames = corruptedVideoFrames; }

    public List<NetworkSample> getNetworkSamples() { return networkSamples; }
    public void setNetworkSamples(List<NetworkSample> networkSamples) { this.networkSamples = networkSamples; }

    public double getPeakBandwidthKBps() { return peakBandwidthKBps; }
    public void setPeakBandwidthKBps(double peakBandwidthKBps) { this.peakBandwidthKBps = peakBandwidthKBps; }

    public double getAvgBandwidthKBps() { return avgBandwidthKBps; }
    public void setAvgBandwidthKBps(double avgBandwidthKBps) { this.avgBandwidthKBps = avgBandwidthKBps; }

    public long getTotalVideoSegmentBytes() { return totalVideoSegmentBytes; }
    public void setTotalVideoSegmentBytes(long totalVideoSegmentBytes) { this.totalVideoSegmentBytes = totalVideoSegmentBytes; }

    // ── Quality tracking ────────────────────────────────────────────

    /**
     * YouTube quality labels from highest to lowest resolution.
     * "auto" and "unknown" are intentionally omitted — they are not specific levels.
     * "highres" is YouTube's label for 8K/4320p and above (returned by getPlaybackQuality()
     * and getAvailableQualityLevels() when the player selects the highest tier).
     */
    public static final List<String> QUALITY_ORDER = List.of(
        "highres", "hd2160", "hd1440", "hd1080", "hd720", "large", "medium", "small", "tiny");

    /**
     * Returns the rank index of a quality label (lower = higher quality).
     * Returns -1 for labels not in QUALITY_ORDER ("auto", "unknown", null).
     */
    public static int qualityRank(String label) {
        if (label == null) return -1;
        return QUALITY_ORDER.indexOf(label);
    }

    /** Per-sweep quality labels collected during playback ("auto" and "unknown" values are filtered out). */
    private List<String> qualityHistory = new ArrayList<>();
    /** Highest quality label observed during the monitoring window (e.g. {@code "hd1080"}). {@code null} if quality was never reported as a specific level. */
    private String peakQualityLabel;
    /** Lowest quality label observed — equals {@link #peakQualityLabel} when stable, lower when ABR downgraded quality during playback. */
    private String lowestQualityLabel;
    /** {@code true} if the ABR algorithm downgraded quality below the peak at any point during the window. */
    private boolean qualityDegraded;

    /** Returns the per-sweep quality labels collected during playback ("auto"/"unknown" filtered out). */
    public List<String> getQualityHistory()                           { return qualityHistory; }
    /** Sets the per-sweep quality history list. */
    public void setQualityHistory(List<String> qualityHistory)        { this.qualityHistory = qualityHistory; }
    /** Returns the highest quality label observed, or {@code null} when quality was always reported as "auto" or "unknown". */
    public String getPeakQualityLabel()                               { return peakQualityLabel; }
    /** Sets the peak (highest) quality label observed during the monitoring window. */
    public void setPeakQualityLabel(String peakQualityLabel)          { this.peakQualityLabel = peakQualityLabel; }
    /** Returns the lowest quality label observed (equals peak when ABR was stable; lower when quality was downgraded). */
    public String getLowestQualityLabel()                             { return lowestQualityLabel; }
    /** Sets the lowest quality label observed. */
    public void setLowestQualityLabel(String lowestQualityLabel)      { this.lowestQualityLabel = lowestQualityLabel; }
    /** Returns {@code true} if YouTube's ABR algorithm downgraded quality below the peak at any point during playback. */
    public boolean isQualityDegraded()                                { return qualityDegraded; }
    /** Sets the quality-degraded flag. */
    public void setQualityDegraded(boolean qualityDegraded)           { this.qualityDegraded = qualityDegraded; }

    // ── Stats for Nerds ──────────────────────────────────────────────────────

    /**
     * Data scraped from YouTube's "Stats for Nerds" overlay panel.
     * {@code null} when the panel was not available during this session.
     */
    private StatsForNerdsData sfnData;

    public StatsForNerdsData getSfnData()            { return sfnData; }
    public void setSfnData(StatsForNerdsData sfnData) { this.sfnData = sfnData; }
}
