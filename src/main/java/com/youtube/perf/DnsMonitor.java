package com.youtube.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
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
    /** Pause between rounds so we sample stability over time rather than burst-querying. */
    private static final int    PAUSE_BETWEEN_ROUNDS_MS = 2_000;

    private final List<String> domains;
    /**
     * Counted down by Main after all browser probes finish, signalling DNS to stop.
     * DNS starts immediately (no synchronised start barrier) so it captures any
     * connectivity failures that occur during browser setup — e.g. a WiFi disconnect
     * before Chrome has finished opening tabs.
     */
    private final CountDownLatch stopLatch;

    /**
     * Creates a new {@code DnsMonitor}.
     *
     * @param domains   the list of fully-qualified domain names to query each round
     * @param stopLatch counted down by {@code Main} once the browser probes finish;
     *                  the monitor runs until this latch reaches zero
     */
    public DnsMonitor(List<String> domains, CountDownLatch stopLatch) {
        this.domains   = domains;
        this.stopLatch = stopLatch;
    }

    /**
     * Implements the {@link java.util.concurrent.Callable} contract.
     * Runs DNS query rounds in a loop, pausing {@value #PAUSE_BETWEEN_ROUNDS_MS} ms
     * between rounds, until {@code stopLatch} is counted down by the main thread.
     * Each round sends one type-A query per configured domain.
     *
     * @return all collected {@link DnsResult} records from every round
     */
    @Override
    public List<DnsResult> call() {
        List<DnsResult> results = new ArrayList<>();

        // DNS starts immediately — no synchronised start barrier.
        // It runs until Main counts down stopLatch (after browser probes finish).
        // Starting early is intentional: it captures any connectivity failures that
        // happen while Chrome is still opening tabs (e.g. a WiFi disconnect during setup).
        logger.info("[DnsMonitor] Starting immediately — monitoring until browser probes complete");

        SimpleResolver resolver;
        try {
            // "8.8.8.8" is an IP literal so no DNS lookup is performed here.
            resolver = new SimpleResolver(RESOLVER_IP);
            resolver.setTimeout(Duration.ofSeconds(RESOLVER_TIMEOUT_SECS));
        } catch (Exception e) {
            logger.error("[DnsMonitor] Failed to create resolver for {}: {}", RESOLVER_IP, e.getMessage(), e);
            return results;
        }

        int round = 0;

        // Loop until Main signals stop via stopLatch or the thread is interrupted.
        // stopLatch.await() doubles as the inter-round pause: it returns false on
        // timeout (keep going) and true when counted down (time to stop).
        boolean stopped = false;
        while (!stopped) {
            round++;
            logger.info("[DnsMonitor] Round {}", round);

            for (String domain : domains) {
                results.add(runDirectDns(resolver, domain));
            }

            try {
                stopped = stopLatch.await(PAUSE_BETWEEN_ROUNDS_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.info("[DnsMonitor] Finished {} rounds ({} queries)", round, results.size());
        return results;
    }
    // ── Direct DNS query (bypasses OS cache) ─────────────────────────────────

    /**
     * Sends a type-A DNS query directly to {@value RESOLVER_IP} via UDP.
     *
     * No OS DNS cache is consulted — every call goes over the network, so a PASS
     * result confirms real internet connectivity at the moment of the call.
     * A timeout (IOException) or non-NOERROR rcode means DNS/internet is broken.
     */
    private DnsResult runDirectDns(SimpleResolver resolver, String domain) {
        DnsResult result = new DnsResult();
        result.setTool(RESOLVER_IP);
        result.setDomain(domain);
        result.setTimestamp(System.currentTimeMillis());

        long start = System.nanoTime();
        try {
            // Trailing dot = fully-qualified domain name; prevents search-list appending.
            Name    name  = Name.fromString(domain + ".");
            Record  q     = Record.newRecord(name, Type.A, DClass.IN);
            Message query = Message.newQuery(q);

            Message response = resolver.send(query);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            result.setResponseTimeMs(elapsed);

            int rcode = response.getHeader().getRcode();
            if (rcode == Rcode.NOERROR) {
                List<String> ips = Arrays.stream(response.getSectionArray(Section.ANSWER))
                    .filter(r -> r.getType() == Type.A)
                    .map(r -> ((ARecord) r).getAddress().getHostAddress())
                    .collect(Collectors.toList());
                result.setResolvedAddresses(ips);
                result.setSuccess(!ips.isEmpty());
                if (ips.isEmpty()) {
                    result.setErrorMessage("NOERROR but no A records returned");
                }
            } else {
                result.setSuccess(false);
                result.setErrorMessage("DNS error: " + Rcode.string(rcode));
            }

            logger.info("[DNS] {} -> {}ms via {} | {}",
                domain, elapsed, RESOLVER_IP,
                result.isSuccess() ? result.getResolvedAddresses() : result.getErrorMessage());

        } catch (Exception e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            result.setResponseTimeMs(elapsed);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            logger.warn("[DNS] {} failed after {}ms via {}: {}", domain, elapsed, RESOLVER_IP, e.getMessage());
        }

        return result;
    }
}