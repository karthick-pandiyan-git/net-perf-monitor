package com.youtube.perf;

/**
 * Raw values scraped from YouTube's "Stats for Nerds" overlay panel.
 *
 * <p>The panel is opened by right-clicking the YouTube player and selecting
 * "Stats for nerds". It exposes metrics that YouTube's internal ABR engine
 * computes — notably the live connection speed measurement, which is more
 * accurate than estimates derived from resource-timing entries.</p>
 *
 * <p>All numeric fields default to -1 when the panel was not available or
 * the value could not be parsed. String fields default to {@code null}.</p>
 */
public class StatsForNerdsData {

    // ── Live streaming metrics ────────────────────────────────────────────────

    /**
     * Connection speed in <em>kilobits per second (Kbps)</em> as measured by
     * YouTube's ABR engine. This is YouTube's own throughput estimate, not the
     * browser resource-timing approximation captured in {@link NetworkSample}.
     *
     * <p>Example panel value: {@code "8765 Kbps"}</p>
     */
    private double connectionSpeedKbps = -1;

    /**
     * Total video bytes downloaded since the page was first loaded, in KB.
     * Example panel value: {@code "85532 KB"}
     */
    private long networkActivityKB = -1;

    /**
     * Seconds of video buffered ahead of the current playhead.
     * The same quantity as {@link VideoMetrics#getBufferedSeconds()} but sourced
     * directly from YouTube's overlay rather than the HTMLVideoElement API.
     * Example panel value: {@code "30.94 s"}
     */
    private double bufferHealthSecs = -1;

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * The resolution currently being rendered, e.g. {@code "1920x1080@60"}.
     * Sourced from the "Current / Optimal Res" row (left-hand value).
     */
    private String currentResolution;

    /**
     * The resolution YouTube's ABR algorithm considers optimal for this
     * connection, e.g. {@code "1920x1080@60"}.
     * Sourced from the "Current / Optimal Res" row (right-hand value).
     * When {@code currentResolution} differs from {@code optimalResolution} the
     * player has not yet reached the quality the connection can support (or is
     * actively constrained by bandwidth).
     */
    private String optimalResolution;

    // ── Frame counters ────────────────────────────────────────────────────────

    /**
     * Total decoded video frames since the session started.
     * Sourced from the "Frames" row (e.g. {@code "875 / 8"}).
     */
    private long totalFrames = -1;

    /**
     * Dropped video frames since the session started.
     * Sourced from the "Frames" row (e.g. {@code "875 / 8"} → 8 dropped).
     */
    private long droppedFrames = -1;

    // ── Codec information ─────────────────────────────────────────────────────

    /**
     * Video codec identifier, e.g. {@code "av01.0.13M.08"} (AV1),
     * {@code "vp09.00.51.08"} (VP9), or {@code "avc1.640028"} (H.264).
     * Sourced from the "Codecs" row left-hand value.
     */
    private String videoCodec;

    /**
     * Audio codec identifier, e.g. {@code "opus"} or {@code "mp4a.40.2"}.
     * Sourced from the "Codecs" row right-hand value.
     */
    private String audioCodec;

    // ── Getters / setters ─────────────────────────────────────────────────────

    public double getConnectionSpeedKbps()       { return connectionSpeedKbps; }
    public void setConnectionSpeedKbps(double v) { this.connectionSpeedKbps = v; }

    public long getNetworkActivityKB()            { return networkActivityKB; }
    public void setNetworkActivityKB(long v)      { this.networkActivityKB = v; }

    public double getBufferHealthSecs()           { return bufferHealthSecs; }
    public void setBufferHealthSecs(double v)     { this.bufferHealthSecs = v; }

    public String getCurrentResolution()          { return currentResolution; }
    public void setCurrentResolution(String v)    { this.currentResolution = v; }

    public String getOptimalResolution()          { return optimalResolution; }
    public void setOptimalResolution(String v)    { this.optimalResolution = v; }

    public long getTotalFrames()                  { return totalFrames; }
    public void setTotalFrames(long v)            { this.totalFrames = v; }

    public long getDroppedFrames()                { return droppedFrames; }
    public void setDroppedFrames(long v)          { this.droppedFrames = v; }

    public String getVideoCodec()                 { return videoCodec; }
    public void setVideoCodec(String v)           { this.videoCodec = v; }

    public String getAudioCodec()                 { return audioCodec; }
    public void setAudioCodec(String v)           { this.audioCodec = v; }

    // ── Derived ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the panel could be read (at least one numeric
     * field was successfully parsed).
     */
    public boolean isAvailable() {
        return connectionSpeedKbps >= 0 || bufferHealthSecs >= 0
            || networkActivityKB >= 0 || currentResolution != null;
    }

    /**
     * Returns {@code true} when both current and optimal resolutions were
     * captured and they differ. A mismatch typically means YouTube's ABR
     * is still ramping up to the optimal quality for this connection speed.
     */
    public boolean isResolutionBelowOptimal() {
        if (currentResolution == null || optimalResolution == null) return false;
        // Compare base resolution (strip frame-rate suffix for a robust match)
        String curr = currentResolution.replaceAll("@\\d+$", "").trim();
        String opt  = optimalResolution.replaceAll("@\\d+$", "").trim();
        return !curr.equals(opt);
    }

    /**
     * Returns a short codec summary string suitable for display, e.g.
     * {@code "av01 / opus"}, {@code "vp09 / opus"}, or {@code "avc1 / mp4a"}.
     * Returns {@code "N/A"} when codec information was not captured.
     */
    public String codecSummary() {
        if (videoCodec == null && audioCodec == null) return "N/A";
        String vc = videoCodec != null ? videoCodec.split("\\.")[0] : "?";
        String ac = audioCodec != null ? audioCodec              : "?";
        return vc + " / " + ac;
    }
}
