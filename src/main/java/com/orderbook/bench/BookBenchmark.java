package com.orderbook.bench;

import com.orderbook.replay.ReplayListener;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Measures the order book alone: real NASDAQ events, already decoded and held in memory,
 * replayed through ReplayListener + OrderBook again and again.
 *
 *   1. capture   decode the first N book events of the file once (CapturedEvents)
 *   2. warm up   a few untimed runs so the JIT compiles the hot code to machine code
 *   3. measure   several timed runs on fresh, empty books: mean, min, max, spread, GC
 *   4. latency   extra runs timing every single event: p50 / p99 / p99.9 / max, the median
 *                of each across runs, and which kinds of event make up the slowest 0.1%
 *
 * Usage:
 *     MAVEN_OPTS="-Xmx8g" mvn -q compile exec:java \
 *       -Dexec.args="bench data/01302020.NASDAQ_ITCH50.gz [events] [warmups] [runs] [latencyRuns]"
 *
 * Defaults: 30,000,000 events, 3 warm-up runs, 5 measured runs, 3 latency runs.
 * Memory: about 38 bytes per captured event + 16 bytes per event for the latency runs,
 * plus the books themselves.
 *
 * Use this for every Phase 4 change: run it before, make the change, run it after,
 * with the same arguments, and compare.
 */
public final class BookBenchmark {

    private BookBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: bench <itch-file[.gz]> [events] [warmups] [runs] [latencyRuns]");
            System.exit(1);
        }
        String path = args[0];
        int maxEvents = args.length > 1 ? Integer.parseInt(args[1].replace("_", "")) : 30_000_000;
        int warmups = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int runs = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int latencyRuns = args.length > 4 ? Integer.parseInt(args[4]) : 3;

        // ---- 1. capture ----
        System.out.printf("Capturing up to %,d book events from %s ...%n", maxEvents, path);
        long t = System.nanoTime();
        CapturedEvents events = CapturedEvents.capture(path, maxEvents);
        int n = events.size();
        System.out.printf("Captured %,d events in %.1f s (reaches %s New York time)%n%n",
                n, (System.nanoTime() - t) / 1e9, ReplayListener.time(events.lastTimestamp()));

        // ---- 2. warm up ----
        for (int w = 1; w <= warmups; w++) {
            double secs = timedRun(events).seconds;
            System.out.printf("warm-up %d   %6.2f s   %,12.0f events/s%n", w, secs, n / secs);
        }

        // ---- 3. measured runs ----
        double[] rates = new double[runs];
        long gcCount = 0, gcMillis = 0;
        for (int r = 0; r < runs; r++) {
            Run run = timedRun(events);
            rates[r] = n / run.seconds;
            gcCount += run.gcCount;
            gcMillis += run.gcMillis;
            System.out.printf("run %d       %6.2f s   %,12.0f events/s   GC: %d collections, %d ms%n",
                    r + 1, run.seconds, rates[r], run.gcCount, run.gcMillis);
        }

        double mean = Arrays.stream(rates).average().orElse(0);
        double min = Arrays.stream(rates).min().orElse(0);
        double max = Arrays.stream(rates).max().orElse(0);
        double stdev = Math.sqrt(Arrays.stream(rates).map(x -> (x - mean) * (x - mean)).sum() / runs);

        // ---- 4. latency ----
        LatencyResult lat = latencyRuns(events, latencyRuns);

        // ---- report ----
        System.out.println();
        System.out.println("=== Book benchmark ===");
        System.out.printf("Events per run      %,d%n", n);
        System.out.printf("Throughput (mean)   %,.0f events/s   (%.0f ns per event)%n", mean, 1e9 / mean);
        System.out.printf("  min / max         %,.0f / %,.0f events/s%n", min, max);
        System.out.printf("  spread (stdev)    %.1f%% of the mean%n", 100 * stdev / mean);
        System.out.printf("GC per run (avg)    %.1f collections, %d ms%n", (double) gcCount / runs, gcMillis / runs);
        System.out.println();
        System.out.printf("Latency per event (median of %d timed runs; stopwatch overhead ~%d ns):%n",
                latencyRuns, lat.timerOverhead);
        String[] names = {"p50", "p90", "p99", "p99.9", "p99.99", "max"};
        for (int p = 0; p < names.length; p++) {
            long[] perRun = lat.percentiles[p];
            System.out.printf("  %-7s %,12d ns     runs: %s%n", names[p], median(perRun), formatRuns(perRun));
        }
        System.out.println();

        // Which kinds of event are the slowest 0.1%? If a kind's share of the tail is much bigger than
        // its share of all events, that kind's code path is where the tail comes from.
        System.out.println("Slowest 0.1% of events (at or above each run's p99.9), by kind, all runs combined:");
        System.out.printf("  %-20s %13s %10s %12s %10s %8s%n", "kind", "all events", "share", "in tail", "share", "ratio");
        long tailTotal = 0;
        for (long c : lat.tailCounts) tailTotal += c;
        for (int k = 1; k < CapturedEvents.KINDS; k++) {
            double allShare = (double) lat.kindCounts[k] / n;
            double tailShare = tailTotal == 0 ? 0 : (double) lat.tailCounts[k] / tailTotal;
            System.out.printf("  %-20s %,13d %9.1f%% %,12d %9.1f%% %7.1fx%n",
                    CapturedEvents.kindName(k), lat.kindCounts[k], 100 * allShare,
                    lat.tailCounts[k], 100 * tailShare, allShare == 0 ? 0 : tailShare / allShare);
        }
        System.out.println("  (ratio > 1: that kind is over-represented among the slowest events)");
        System.out.println();
        System.out.println("(health: " + lat.unknown + " unknown, " + lat.rejected + " rejected; should both be 0)");
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String formatRuns(long[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(String.format("%,d", values[i]));
        }
        return sb.toString();
    }

    // ===================== throughput =====================

    private record Run(double seconds, long gcCount, long gcMillis) {}

    private static Run timedRun(CapturedEvents events) {
        ReplayListener replay = new ReplayListener();   // fresh, empty books every run
        System.gc();                                     // start each run from a clean heap
        long gc0 = gcCount(), gcMs0 = gcMillis();
        long start = System.nanoTime();
        events.applyAll(replay);
        long elapsed = System.nanoTime() - start;
        if (replay.unknownOrderEvents() + replay.rejectedEvents() != 0) {
            throw new IllegalStateException("replay errors: the benchmark would be measuring a broken book");
        }
        return new Run(elapsed / 1e9, gcCount() - gc0, gcMillis() - gcMs0);
    }

    // ===================== latency =====================

    /**
     * percentiles[p][run]: p50, p90, p99, p99.9, p99.99, max for each run.
     * kindCounts[k]: how many events of kind k there are in one run.
     * tailCounts[k]: how many events of kind k were at or above p99.9, summed over all runs.
     */
    private record LatencyResult(long[][] percentiles, long[] kindCounts, long[] tailCounts,
                                 long timerOverhead, long unknown, long rejected) {}

    private static LatencyResult latencyRuns(CapturedEvents events, int runs) {
        int n = events.size();
        long[] latencies = new long[n];   // latencies[i] belongs to event i (NOT sorted)
        long[] sorted = new long[n];      // a sorted copy, to read percentiles from

        // How much does the stopwatch itself cost? Time an empty body the same way.
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            sorted[i] = System.nanoTime() - s;
        }
        Arrays.sort(sorted);
        long overhead = sorted[n / 2];

        long[] kindCounts = new long[CapturedEvents.KINDS];
        for (int i = 0; i < n; i++) kindCounts[events.kind(i)]++;

        long[][] percentiles = new long[6][runs];
        long[] tailCounts = new long[CapturedEvents.KINDS];
        long unknown = 0, rejected = 0;

        for (int r = 0; r < runs; r++) {
            ReplayListener replay = new ReplayListener();
            System.gc();
            for (int i = 0; i < n; i++) {
                long s = System.nanoTime();
                events.apply(i, replay);
                latencies[i] = System.nanoTime() - s;
            }
            unknown += replay.unknownOrderEvents();
            rejected += replay.rejectedEvents();

            // Percentiles: sort a COPY, so latencies[] still lines up with the events.
            System.arraycopy(latencies, 0, sorted, 0, n);
            Arrays.sort(sorted);   // sorted: the value at position p * n is the p-th percentile
            percentiles[0][r] = at(sorted, 0.50);
            percentiles[1][r] = at(sorted, 0.90);
            percentiles[2][r] = at(sorted, 0.99);
            percentiles[3][r] = at(sorted, 0.999);
            percentiles[4][r] = at(sorted, 0.9999);
            percentiles[5][r] = sorted[n - 1];

            // Tail breakdown: which kinds of event took at least this run's p99.9?
            long threshold = percentiles[3][r];
            for (int i = 0; i < n; i++) {
                if (latencies[i] >= threshold) tailCounts[events.kind(i)]++;
            }
            System.out.printf("latency run %d done (p99.9 = %,d ns)%n", r + 1, threshold);
        }
        return new LatencyResult(percentiles, kindCounts, tailCounts, overhead, unknown, rejected);
    }

    private static long at(long[] sorted, double percentile) {
        return sorted[(int) Math.min(sorted.length - 1, (long) (percentile * sorted.length))];
    }

    // ===================== GC counters from the JVM =====================

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, gc.getCollectionCount());
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, gc.getCollectionTime());
        return total;
    }
}
