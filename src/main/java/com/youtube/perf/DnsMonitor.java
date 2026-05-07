package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resolves each domain for 30 seconds by sending direct UDP queries to
 * Google Public DNS (8.8.8.8) using dnsjava's {@link SimpleResolver}.
 *
 * Unlike {@link java.net.InetAddress}, which hits the OS DNS cache and can return
 * 0 ms even when the internet is down, this method sends an actual UDP packet to
 * an external server every time. A PASS result therefore confirms real internet
 * connectivity at the moment of the query; a FAIL or timeout means DNS or the
 * network path is broken.
 *
 * Response times are true round-trip latency to 8.8.8.8: typically 10–100 ms
 * on a healthy connection, noticeably higher when the connection is congested,
 * and a 5-second timeout when internet is completely down.
 */
public class DnsMonitor implements Callable<List<DnsResult>> {

    private static final Logger logger = LoggerFactory.getLogger(DnsMonitor.class);

    /** Google Public DNS — queried directly over UDP, no OS cache involved. */
    private static final String RESOLVER_IP             = "8.8.8.8";
    /** Per-query timeout. If 8.8.8.8 doesn't respond within this time, the internet is down. */
    private static final int    RESOLVER_TIMEOUT_SECS   = 5;
    /** Pause between rounds — one round per 5 s to align with the YouTube and website sweep interval. */
    private static final int    PAUSE_BETWEEN_ROUNDS_MS = 5_000;

    private final List<String> domains;
    /**
     * Counted down by Main after all browser probes finish, signalling DNS to stop.
     * DNS starts immediately (no synchronised start barrier) so it captures any
     * connectivity failures that occur during browser setup — e.g. a WiFi disconnect
     * before Chrome has finished opening tabs.
     */
    private final CountDownLatch stopLatch;
    /**
     * Shared results store published to Main for periodic reporting.
     * Must be thread-safe (e.g. {@code CopyOnWriteArrayList}).
     */
    private final List<DnsResult> liveResults;
    /**
     * Pre-warmed resolver created at construction time so Round 1 does not incur the
     * UDP socket initialisation penalty (~150ms on first use by dnsjava).
     * May be {@code null} if the resolver could not be created (fatal config error);
     * {@code call()} checks for null and aborts early in that case.
     */
    private final SimpleResolver resolver;
    /** Start latch: {@code call()} blocks here until all browser probes are ready. */
    private final CountDownLatch startLatch;

    /**
     * Constructs a new {@code DnsMonitor} and pre-warms the UDP resolver socket
     * so that the first query round has similar latency to subsequent rounds.
     *
     * @param domains     list of fully-qualified domain names to query each round
     * @param startLatch  barrier shared with the browser probes; this monitor will
     *                    block in {@link #call()} until all browser probes count down
     * @param stopLatch   counted down by the main thread after all browser probes
     *                    finish, signalling this monitor to exit its loop
     * @param liveResults thread-safe list (e.g. {@code CopyOnWriteArrayList}) into
     *                    which completed {@link DnsResult} records are appended in
     *                    real time so a periodic reporter can read them safely
     */
    public DnsMonitor(List<String> domains, CountDownLatch startLatch,
                      CountDownLatch stopLatch, List<DnsResult> liveResults) {
        this.domains     = domains;
        this.startLatch  = startLatch;
        this.stopLatch   = stopLatch;
        this.liveResults = liveResults;
        // Pre-create the resolver to warm up UDP socket allocation so Round 1
        // latency matches subsequent rounds (~240-290ms vs ~480ms cold).
        SimpleResolver r = null;
        try {
            r = new SimpleResolver(RESOLVER_IP);
            r.setTimeout(Duration.ofSeconds(RESOLVER_TIMEOUT_SECS));
        } catch (Exception e) {
            logger.warn("[DnsMonitor] Could not pre-create resolver: {}", e.getMessage());
        }
        this.resolver = r;
    }

    /**
     * Implements the {@link java.util.concurrent.Callable} contract.
     * Runs DNS query rounds in a loop, pausing {@value #PAUSE_BETWEEN_ROUNDS_MS} ms
     * between rounds, until {@code stopLatch} is counted down by the main thread.
     * Each round sends one Type-A query and one Type-AAAA query per configured domain
     * to explicitly verify both IPv4 and IPv6 connectivity.
     *
     * @return all collected {@link DnsResult} records from every round
     */
    @Override
    public List<DnsResult> call() {
        // Use the shared live list so each query result is visible to the periodic
        // reporter thread in Main as soon as it is written.
        List<DnsResult> results = liveResults;

        // Synchronize with browser probes: wait until YouTube and Website probes are
        // both ready to measure before firing DNS queries so all three probes run in
        // parallel on the same time axis. In DNS-only mode browserProbeCount=0 so
        // startLatch.count=0 and await() returns immediately.
        logger.info("[DnsMonitor] Waiting for browser probes to enter measurement phase "
            + "(thread: {})...", Thread.currentThread().getName());
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return results;
        }
        final long measurementStart = System.currentTimeMillis();
        logger.info("[DnsMonitor] All probes synchronized — DNS monitoring starting on '{}' "
            + "targeting {} | {} domains: {}",
            Thread.currentThread().getName(), RESOLVER_IP, domains.size(), domains);

        if (resolver == null) {
            logger.error("[DnsMonitor] Resolver failed to initialize; aborting DNS monitoring.");
            return results;
        }

        int round = 0;

        // Loop until Main signals stop via stopLatch or the thread is interrupted.
        // stopLatch.await(timeout) doubles as the inter-round pause: false = keep going,
        // true = stop latch counted down.
        boolean stopped = false;
        while (!stopped) {
            round++;
            long roundStart = System.currentTimeMillis();
            long elapsedSec = (roundStart - measurementStart) / 1000;
            logger.info("[DnsMonitor] ─── Round {} (t+{}s) — querying {} domains via {} ───",
                round, elapsedSec, domains.size(), RESOLVER_IP);

            int roundPass = 0, roundFail = 0;
            for (String domain : domains) {
                // Query both A (IPv4) and AAAA (IPv6) per round to independently verify
                // IPv4 and IPv6 internet connectivity.
                DnsResult ra = runDirectDns(resolver, domain, Type.A,    "A");
                DnsResult rb = runDirectDns(resolver, domain, Type.AAAA, "AAAA");
                results.add(ra);
                results.add(rb);
                if (ra.isSuccess()) roundPass++; else roundFail++;
                if (rb.isSuccess()) roundPass++; else roundFail++;
            }
            long roundMs = System.currentTimeMillis() - roundStart;
            logger.info("[DnsMonitor] Round {} completed in {}ms — {}/{} queries ok",
                round, roundMs, roundPass, roundPass + roundFail);

            try {
                stopped = stopLatch.await(PAUSE_BETWEEN_ROUNDS_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long totalMs = System.currentTimeMillis() - measurementStart;
        logger.info("[DnsMonitor] Finished — {} rounds, {} total queries in {}ms (thread: {})",
            round, results.size(), totalMs, Thread.currentThread().getName());
        return results;
    }
    // ── Direct DNS query (bypasses OS cache) ─────────────────────────────────

    /**
     * Sends a DNS query of the specified type directly to {@value RESOLVER_IP} via UDP.
     *
     * No OS DNS cache is consulted — every call goes over the network, so a PASS
     * result confirms real internet connectivity at the moment of the call.
     * A timeout (IOException) or non-NOERROR rcode means DNS/internet is broken.
     *
     * @param queryType      {@link Type#A} for IPv4 or {@link Type#AAAA} for IPv6
     * @param recordTypeName human-readable label written into the result ("A" or "AAAA")
     */
    private DnsResult runDirectDns(SimpleResolver resolver, String domain,
                                   int queryType, String recordTypeName) {
        DnsResult result = new DnsResult();
        result.setDomain(domain);
        result.setRecordType(recordTypeName);
        result.setTimestamp(System.currentTimeMillis());

        long start = System.nanoTime();
        try {
            // Trailing dot = fully-qualified domain name; prevents search-list appending.
            Name    name  = Name.fromString(domain + ".");
            Record  q     = Record.newRecord(name, queryType, DClass.IN);
            Message query = Message.newQuery(q);

            Message response = resolver.send(query);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            result.setResponseTimeMs(elapsed);

            int rcode = response.getHeader().getRcode();
            if (rcode == Rcode.NOERROR) {
                List<String> ips = Arrays.stream(response.getSectionArray(Section.ANSWER))
                    .filter(r -> r.getType() == queryType)
                    .map(r -> queryType == Type.A
                        ? ((ARecord)   r).getAddress().getHostAddress()
                        : ((AAAARecord) r).getAddress().getHostAddress())
                    .collect(Collectors.toList());
                result.setResolvedAddresses(ips);
                // NOERROR with empty answers means the resolver is healthy — the domain
                // simply has no records of this type (e.g. an IPv4-only site has no AAAA).
                // This is NOT a DNS failure; mark it successful so the success-rate stat
                // reflects actual resolver/network health rather than domain configuration.
                result.setSuccess(true);
                String answer = ips.isEmpty()
                    ? "(no " + recordTypeName + " records — IPv4-only domain?)"
                    : String.join(", ", ips);
                logger.info("[DNS]  OK  {} ({})  {}ms  -> {}",
                    domain, recordTypeName, elapsed, answer);
            } else {
                result.setSuccess(false);
                result.setErrorMessage("DNS error: " + Rcode.string(rcode));
                logger.warn("[DNS] FAIL {} ({})  {}ms  rcode={}",
                    domain, recordTypeName, elapsed, Rcode.string(rcode));
            }

        } catch (Exception e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            result.setResponseTimeMs(elapsed);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            logger.warn("[DNS] FAIL {} ({})  {}ms  exception: {}",
                domain, recordTypeName, elapsed, e.getMessage());
        }

        return result;
    }
}