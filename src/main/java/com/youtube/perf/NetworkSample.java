package com.youtube.perf;

/**
 * A live network snapshot captured at one sweep interval during video playback.
 * Bandwidth is estimated from video segment bytes transferred in the past sweep window.
 */
public class NetworkSample {

    private int sampleNumber;
    private long timestamp;           // epoch ms when sample was taken

    // ── Video segment traffic ──
    /** Cumulative bytes from videoplayback requests since page load. */
    private long totalSegmentBytes;
    /** Bytes transferred in the last sweep window (~5 s). */
    private long recentSegmentBytes;
    /** Number of segment requests completed in the last sweep window. */
    private int recentSegmentCount;
    /** Total segment requests completed since page load. */
    private int totalSegmentCount;
    /** Estimated throughput in KB/s based on the last sweep window. */
    private double bandwidthKBps;

    // ── Video state at sample time ──
    private double videoCurrentTime;  // seconds played so far
    private double videoBuffered;     // furthest buffered position (seconds)
    /** Per-sweep quality labels (auto/unknown filtered out). */
    private String qualityLabel;
    /** True when this sample was taken while an ad was playing. Quality data is omitted in that case. */
    private boolean adPlaying;

    // ── Getters / setters ──────────────────────────────────────────────────────

    public int getSampleNumber() { return sampleNumber; }
    public void setSampleNumber(int sampleNumber) { this.sampleNumber = sampleNumber; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getTotalSegmentBytes() { return totalSegmentBytes; }
    public void setTotalSegmentBytes(long totalSegmentBytes) { this.totalSegmentBytes = totalSegmentBytes; }

    public long getRecentSegmentBytes() { return recentSegmentBytes; }
    public void setRecentSegmentBytes(long recentSegmentBytes) { this.recentSegmentBytes = recentSegmentBytes; }

    public int getRecentSegmentCount() { return recentSegmentCount; }
    public void setRecentSegmentCount(int recentSegmentCount) { this.recentSegmentCount = recentSegmentCount; }

    public int getTotalSegmentCount() { return totalSegmentCount; }
    public void setTotalSegmentCount(int totalSegmentCount) { this.totalSegmentCount = totalSegmentCount; }

    public double getBandwidthKBps() { return bandwidthKBps; }
    public void setBandwidthKBps(double bandwidthKBps) { this.bandwidthKBps = bandwidthKBps; }

    public double getVideoCurrentTime() { return videoCurrentTime; }
    public void setVideoCurrentTime(double videoCurrentTime) { this.videoCurrentTime = videoCurrentTime; }

    public double getVideoBuffered() { return videoBuffered; }
    public void setVideoBuffered(double videoBuffered) { this.videoBuffered = videoBuffered; }

    public String getQualityLabel() { return qualityLabel; }
    public void setQualityLabel(String qualityLabel) { this.qualityLabel = qualityLabel; }

    public boolean isAdPlaying() { return adPlaying; }
    public void setAdPlaying(boolean adPlaying) { this.adPlaying = adPlaying; }
}
