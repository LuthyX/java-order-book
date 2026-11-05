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
 *   4. latency   one extra run timing every single event: p50 / p99 / p99.9 / max
 *
 * Usage:
 *     MAVEN_OPTS="-Xmx6g" mvn -q compile exec:java \
 *       -Dexec.args="bench data/01302020.NASDAQ_ITCH50.gz [events] [warmups] [runs]"
 *
 * Defaults: 30,000,000 events, 3 warm-up runs, 5 measured runs.
 * Memory: about 38 bytes per captured event + 8 bytes per event for the latency run,
 * plus the books themselves.
 *
 * Use this for every Phase 4 change: run it before, make the change, run it after,
 * with the same arguments, and compare.
 */
public final class BookBenchmark {

    private BookBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: bench <itch-file[.gz]> [events] [warmups] [runs]");
            System.exit(1);
        }
        String path = args[0];
        int maxEvents = args.length > 1 ? Integer.parseInt(args[1].replace("_", "")) : 30_000_000;
        int warmups = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int runs = args.length > 3 ? Integer.parseInt(args[3]) : 5;

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
        LatencyResult lat = latencyRun(events);

        // ---- report ----
        System.out.println();
        System.out.println("=== Book benchmark ===");
        System.out.printf("Events per run      %,d%n", n);
        System.out.printf("Throughput (mean)   %,.0f events/s   (%.0f ns per event)%n", mean, 1e9 / mean);
        System.out.printf("  min / max         %,.0f / %,.0f events/s%n", min, max);
        System.out.printf("  spread (stdev)    %.1f%% of the mean%n", 100 * stdev / mean);
        System.out.printf("GC per run (avg)    %.1f collections, %d ms%n", (double) gcCount / runs, gcMillis / runs);
        System.out.println();
        System.out.println("Latency per event (one timed run; includes ~" + lat.timerOverhead + " ns of stopwatch overhead):");
        System.out.printf("  p50      %,10d ns%n", lat.p50);
        System.out.printf("  p90      %,10d ns%n", lat.p90);
        System.out.printf("  p99      %,10d ns%n", lat.p99);
        System.out.printf("  p99.9    %,10d ns%n", lat.p999);
        System.out.printf("  p99.99   %,10d ns%n", lat.p9999);
        System.out.printf("  max      %,10d ns   (%.1f ms)%n", lat.max, lat.max / 1e6);
        System.out.println();
        System.out.println("(health: " + lat.unknown + " unknown, " + lat.rejected + " rejected; should both be 0)");
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

    private record LatencyResult(long p50, long p90, long p99, long p999, long p9999, long max,
                                 long timerOverhead, long unknown, long rejected) {}

    private static LatencyResult latencyRun(CapturedEvents events) {
        int n = events.size();
        long[] latencies = new long[n];

        // How much does the stopwatch itself cost? Time an empty body the same way.
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            latencies[i] = System.nanoTime() - s;
        }
        Arrays.sort(latencies);
        long overhead = latencies[n / 2];

        ReplayListener replay = new ReplayListener();
        System.gc();
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            events.apply(i, replay);
            latencies[i] = System.nanoTime() - s;
        }
        Arrays.sort(latencies);   // sorted: the value at position p * n is the p-th percentile
        return new LatencyResult(
                at(latencies, 0.50), at(latencies, 0.90), at(latencies, 0.99),
                at(latencies, 0.999), at(latencies, 0.9999), latencies[n - 1],
                overhead, replay.unknownOrderEvents(), replay.rejectedEvents());
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
