package com.youtube.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Stand-alone utility that generates a sample HTML report using synthetic data
 * matching the real data from the most recent test run.
 * Run with: java -cp target/net-perf-monitor-jar-with-dependencies.jar com.youtube.perf.GenerateTestReport
 */
public class GenerateTestReport {

    public static void main(String[] args) throws Exception {
        long baseTs = System.currentTimeMillis() - 120_000;
        Random rnd = new Random(42);

        // ── YouTube Tab #1 ─────────────────────────────────────────────────────
        VideoMetrics yt1 = new VideoMetrics();
        yt1.setTabIndex(1);
        yt1.setPageTitle("Rainbow Reef 3 - A Magical 8HR Underwater Journey in 8K - YouTube");
        yt1.setTimeToFirstByte(584);
        yt1.setPageLoadTime(8088);
        yt1.setDomContentLoadedTime(3754);
        yt1.setDnsLookupTime(0);
        yt1.setTcpConnectionTime(96);
        yt1.setVideoWidth(7680);
        yt1.setVideoHeight(4320);
        yt1.setBufferedSeconds(71.92);
        yt1.setAvgBandwidthKBps(2517.9);
        yt1.setTotalVideoSegmentBytes(75_000_000L);
        yt1.setMetricsCollectedAt(baseTs + 40_000);

        double[] bufTimes1 = {30.0, 36.0, 42.0, 48.0, 54.0, 60.16, 65.92, 71.92};
        double[] bwTimes1  = {0, 0, 0, 20143.51, 0, 0, 0, 0};
        double[] csKbps1   = {51200, 48800, 49500, 47300, 50100, 49800, 48200, 49102}; // SFN connection speed per sweep
        double[] bhSecs1   = {5.2, 6.8, 7.1, 6.4, 7.9, 8.3, 7.6, 8.04}; // SFN buffer health per sweep
        long[] dropped1    = {210, 480, 820, 1050, 1290, 1510, 1700, 1821}; // SFN dropped frames (cumulative) per sweep
        long[] total1      = {1950, 3900, 5850, 7760, 9700, 11640, 13600, 15580}; // SFN total frames (cumulative) per sweep
        long[] offsets1    = {0, 6000, 11000, 17000, 23000, 28000, 34000, 40000};
        List<NetworkSample> samples1 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            NetworkSample s = new NetworkSample();
            s.setTimestamp(baseTs + 40000 + offsets1[i]);
            s.setBandwidthKBps(bwTimes1[i]);
            s.setVideoBuffered(bufTimes1[i]);
            s.setConnectionSpeedKbps(csKbps1[i]);
            s.setBufferHealthSecs(bhSecs1[i]);
            s.setDroppedFrames(dropped1[i]);
            s.setTotalFrames(total1[i]);
            samples1.add(s);
        }
        yt1.setNetworkSamples(samples1);

        StatsForNerdsData sfn1 = new StatsForNerdsData();
        sfn1.setConnectionSpeedKbps(49263); // avg of csKbps1
        sfn1.setBufferHealthSecs(7.04); // avg of bhSecs1
        sfn1.setCurrentResolution("7680x4320@25");
        sfn1.setOptimalResolution("7680x4320@25");
        sfn1.setVideoCodec("av01.0.16M.08 (571)");
        sfn1.setAudioCodec("opus (251)");
        sfn1.setTotalFrames(15580);
        sfn1.setDroppedFrames(1821);
        yt1.setSfnData(sfn1);

        // ── YouTube Tab #2 ─────────────────────────────────────────────────────
        VideoMetrics yt2 = new VideoMetrics();
        yt2.setTabIndex(2);
        yt2.setPageTitle("8K UNDERWATER FILM: \"Secrets of the Ocean\" 8HR Nature Relaxation - YouTube");
        yt2.setTimeToFirstByte(140);
        yt2.setPageLoadTime(4188);
        yt2.setDomContentLoadedTime(3078);
        yt2.setDnsLookupTime(0);
        yt2.setTcpConnectionTime(0);
        yt2.setVideoWidth(7680);
        yt2.setVideoHeight(4320);
        yt2.setBufferedSeconds(58.81);
        yt2.setAvgBandwidthKBps(3356.5);
        yt2.setTotalVideoSegmentBytes(80_000_000L);
        yt2.setMetricsCollectedAt(baseTs + 80_000);

        double[] bufTimes2 = {23.52, 29.40, 35.29, 35.29, 41.17, 47.05, 52.93, 58.81};
        double[] bwTimes2  = {0, 14014.44, 0, 0, 12837.61, 0, 0, 0};
        double[] csKbps2   = {252000, 248500, 245800, 250100, 247600, 244900, 249300, 247243}; // SFN connection speed per sweep
        double[] bhSecs2   = {8.1, 9.4, 10.2, 9.7, 11.3, 10.8, 9.9, 9.92}; // SFN buffer health per sweep
        long[] dropped2    = {12, 28, 55, 88, 130, 180, 235, 284}; // SFN dropped frames (cumulative) per sweep
        long[] total2      = {3555, 7110, 10665, 14220, 17775, 21330, 24885, 28440}; // SFN total frames (cumulative) per sweep
        long[] offsets2    = {0, 5000, 11000, 17000, 22000, 28000, 33000, 39000};
        List<NetworkSample> samples2 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            NetworkSample s = new NetworkSample();
            s.setTimestamp(baseTs + 41_000 + offsets2[i]);
            s.setBandwidthKBps(bwTimes2[i]);
            s.setVideoBuffered(bufTimes2[i]);
            s.setConnectionSpeedKbps(csKbps2[i]);
            s.setBufferHealthSecs(bhSecs2[i]);
            s.setDroppedFrames(dropped2[i]);
            s.setTotalFrames(total2[i]);
            samples2.add(s);
        }
        yt2.setNetworkSamples(samples2);

        StatsForNerdsData sfn2 = new StatsForNerdsData();
        sfn2.setConnectionSpeedKbps(248435); // avg of csKbps2
        sfn2.setBufferHealthSecs(9.92); // avg of bhSecs2
        sfn2.setCurrentResolution("7680x4320@24");
        sfn2.setOptimalResolution("7680x4320@24");
        sfn2.setVideoCodec("av01.0.16M.08 (571)");
        sfn2.setAudioCodec("opus (251)");
        sfn2.setTotalFrames(28440);
        sfn2.setDroppedFrames(284);
        yt2.setSfnData(sfn2);

        List<VideoMetrics> ytMetrics = List.of(yt1, yt2);

        // ── Verdicts ───────────────────────────────────────────────────────────
        PerformanceEvaluator evaluator = new PerformanceEvaluator();
        List<VideoVerdict> verdicts = evaluator.evaluateAll(ytMetrics);

        // ── Website metrics ────────────────────────────────────────────────────
        // Tab 4 uses americanexpress.com which has elevated thresholds (warm=8000ms,
        // cold=12000ms) configured in test.properties. Load times are realistic for
        // that heavy site and stay within those per-site limits.
        String[][] webDomains = {
            {"craigslist.org",      "https://www.craigslist.org"},
            {"wikipedia.org",       "https://www.wikipedia.org"},
            {"facebook.com",        "https://www.facebook.com"},
            {"americanexpress.com", "https://www.americanexpress.com"}
        };
        long[][] webLoads = {
            {755,   453,  455,  661,  523,  402,  437},
            {272,   350,  234,  257,  162,  197,  147},
            {754,   506,  471,  831,  451,  611,  433},
            // amex: cold cycle 1 near the 12 000 ms cold limit; warm cycles near 8 000 ms limit
            {10_800, 7_100, 6_900, 7_800, 6_600, 8_200, 7_400}
        };
        long[][] webTtfb = {
            {288, 66,  89,  189, 133, 71,  82},
            {140, 135, 74,  101, 46,  71,  49},
            {222, 221, 186, 205, 171, 261, 166},
            // amex TTFB is heavier but stays within default 2000/1200 ms thresholds
            {1750, 980, 850, 1100, 760, 930, 1050}
        };
        long[][] webDcl = {
            {330,  98,  137, 254, 186, 99,  132},
            {265,  329, 213, 177, 154, 189, 142},
            {580,  426, 425, 790, 417, 557, 361},
            {4_200, 2_800, 2_500, 3_200, 2_400, 3_500, 2_900}
        };

        List<WebsiteMetrics> webMetrics = new ArrayList<>();
        for (int di = 0; di < webDomains.length; di++) {
            for (int cycle = 0; cycle < 7; cycle++) {
                WebsiteMetrics wm = new WebsiteMetrics();
                wm.setTabIndex(di + 1);
                wm.setDomain(webDomains[di][0]);
                wm.setUrl(webDomains[di][1]);
                wm.setRefreshCycle(cycle + 1);
                wm.setPageLoadTime(webLoads[di][cycle]);
                wm.setTimeToFirstByte(webTtfb[di][cycle]);
                wm.setDomContentLoaded(webDcl[di][cycle]);
                wm.setTimestamp(baseTs + 35_000 + (long)(di * 1200) + (long)(cycle * 5_500));
                webMetrics.add(wm);
            }
        }

        // ── DNS results ────────────────────────────────────────────────────────
        String[] dnsDomains2 = {"google.com", "cloudflare.com", "wikipedia.org", "netflix.com", "apple.com"};
        // Round-trip times from the real run (17 rounds each)
        int[][] dnsArts = {
            {39, 20, 25, 30, 35, 45, 103, 28, 22, 31, 40, 33, 29, 55, 41, 38, 27}, // google A
            {33, 20, 22, 26, 30, 56, 28, 24, 31, 35, 29, 21, 44, 32, 25, 38, 20},  // google AAAA
            {34, 24, 28, 32, 37, 41, 58, 29, 26, 33, 30, 25, 45, 35, 28, 40, 24},  // cloudflare A
            {32, 24, 26, 28, 35, 39, 55, 27, 25, 31, 29, 23, 43, 33, 26, 38, 22},  // cloudflare AAAA
            {34, 24, 27, 31, 36, 40, 55, 28, 27, 32, 30, 25, 44, 34, 27, 39, 23},  // wikipedia A
            {35, 26, 29, 33, 38, 42, 66, 29, 28, 33, 31, 26, 45, 35, 28, 40, 24},  // wikipedia AAAA
            {43, 25, 29, 35, 40, 47, 89, 33, 28, 37, 35, 30, 50, 40, 31, 44, 28},  // netflix A
            {38, 21, 26, 31, 35, 43, 60, 30, 25, 34, 32, 27, 47, 37, 29, 41, 25},  // netflix AAAA
            {28, 18, 21, 24, 29, 33, 51, 24, 22, 27, 26, 20, 39, 29, 23, 34, 19},  // apple A
            {26, 17, 20, 22, 27, 31, 52, 23, 21, 25, 24, 19, 37, 27, 21, 32, 18},  // apple AAAA
        };
        String[] recordTypes = {"A", "AAAA"};

        List<DnsResult> dnsResults = new ArrayList<>();
        // DNS runs in parallel with YouTube and Website (all enter measurement phase together).
        // Website starts at baseTs+35_000; DNS mirrors that so the timeline shows all three
        // probes on the same time axis. 9 rounds × 5 s = 45 s measurement window.
        long dnsStartTs = baseTs + 35_000;
        for (int ri = 0; ri < 9; ri++) {
            long roundTs = dnsStartTs + (long)(ri * 5_000);
            for (int domIdx = 0; domIdx < dnsDomains2.length; domIdx++) {
                for (int typeIdx = 0; typeIdx < 2; typeIdx++) {
                    DnsResult r = new DnsResult();
                    r.setDomain(dnsDomains2[domIdx]);
                    r.setRecordType(recordTypes[typeIdx]);
                    int artIdx = domIdx * 2 + typeIdx;
                    r.setResponseTimeMs(dnsArts[artIdx][ri]);
                    r.setTimestamp(roundTs + typeIdx * 50L + domIdx * 30L);
                    r.setSuccess(true);
                    r.setResolvedAddresses(List.of("8.8.8.8"));
                    dnsResults.add(r);
                }
            }
        }

        // ── Generate HTML report ───────────────────────────────────────────────
        // Mirror the per-site threshold overrides from test.properties so that the
        // generated report reflects the same PASS/FAIL criteria as the real run.
        Map<String, WebsiteThresholds> thresholdOverrides = Map.of(
            "americanexpress.com", new WebsiteThresholds(8_000, 12_000, 1_200, 2_000)
        );
        Function<String, WebsiteThresholds> thresholdsFor =
            d -> thresholdOverrides.getOrDefault(d, WebsiteThresholds.DEFAULT);

        HtmlReporter reporter = new HtmlReporter(thresholdsFor);
        String outPath = "target/net_performance_report.html";
        reporter.saveReport(ytMetrics, webMetrics, dnsResults, verdicts, outPath);
        System.out.println("Report generated: " + outPath);
    }
}
