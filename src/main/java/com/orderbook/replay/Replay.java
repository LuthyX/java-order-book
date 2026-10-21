package com.orderbook.replay;

import com.orderbook.core.Prices;
import com.orderbook.itch.ItchReader;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Replays a NASDAQ ITCH 5.0 file into order books and prints a report.
 *
 * Usage (from the project folder):
 *     mvn -q compile exec:java -Dexec.args="data/01302020.NASDAQ_ITCH50.gz"
 *     mvn -q compile exec:java -Dexec.args="data/01302020.NASDAQ_ITCH50.gz AAPL MSFT TSLA NVDA"
 *
 * The file can be gzipped (read while decompressing, never unpacked to disk) or raw.
 * Extra arguments are tickers to report at the 4 p.m. close (default: AAPL MSFT TSLA).
 *
 * NOTE: the timing here covers EVERYTHING: decompression, parsing and book updates
 * together. It's a first rough number, not a benchmark. Phase 3 measures the book on
 * its own with JMH, so decompression and disk speed don't blur the result.
 */
public final class Replay {

    private Replay() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: Replay <itch-file[.gz]> [TICKER ...]");
            System.exit(1);
        }
        String path = args[0];
        Set<String> watched = new LinkedHashSet<>(args.length > 1
                ? List.of(args).subList(1, args.length)
                : List.of("AAPL", "MSFT", "TSLA"));

        ReplayListener replay = new ReplayListener(watched);
        System.out.println("Replaying " + path + " ...");

        long start = System.nanoTime();
        boolean truncated = false;
        try (InputStream in = open(path)) {
            new ItchReader().readAll(in, replay);
        } catch (EOFException e) {
            truncated = true;   // e.g. a sample cut from the start of a file: report what we have
        }
        long elapsedNanos = System.nanoTime() - start;

        report(replay, elapsedNanos, truncated);
    }

    private static InputStream open(String path) throws IOException {
        InputStream file = new FileInputStream(path);
        // 1 MB buffers: GZIPInputStream and the reader both make many small reads,
        // and a big buffer turns those into a few large ones.
        if (path.endsWith(".gz")) {
            return new BufferedInputStream(new GZIPInputStream(file, 1 << 20), 1 << 20);
        }
        return new BufferedInputStream(file, 1 << 20);
    }

    private static void report(ReplayListener r, long elapsedNanos, boolean truncated) {
        double seconds = elapsedNanos / 1e9;
        long messages = r.messageCount();

        System.out.println();
        System.out.println("=== Replay report ===");
        if (truncated) {
            System.out.println("(file ends partway through a message: normal for a sample, a problem for a full day)");
        }
        System.out.printf("Messages            %,d%n", messages);
        System.out.printf("Time                %.1f s  (decompress + parse + book updates)%n", seconds);
        System.out.printf("Throughput          %,.0f messages/s%n", messages / seconds);
        System.out.printf("Last message at     %s%n", ReplayListener.time(r.lastTimestamp()));
        System.out.println();

        System.out.println("By type ('A' includes F):");
        for (Map.Entry<Character, Long> e : r.countsByType().entrySet()) {
            System.out.printf("  %c  %,15d  %5.1f%%%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / messages);
        }
        System.out.println();

        System.out.printf("Stocks in directory %,d%n", r.stockCount());
        System.out.printf("Books with orders   %,d%n", r.bookCount());
        System.out.printf("Peak resting orders %,d  at %s%n", r.peakRestingOrders(),
                ReplayListener.time(r.peakRestingTimestamp()));
        System.out.printf("Resting at the end  %,d%n", r.restingOrders());
        System.out.println();

        System.out.println("Health checks (all should be 0):");
        System.out.printf("  unknown-order events  %,d%n", r.unknownOrderEvents());
        System.out.printf("  rejected events       %,d%n", r.rejectedEvents());
        System.out.printf("  crossed books now     %,d%n", r.crossedBooks());
        for (String p : r.firstProblems()) {
            System.out.println("    e.g. " + p);
        }
        System.out.println();

        if (r.closeSnapshot().isEmpty()) {
            System.out.println("(no 4 p.m. close in this file, so no closing snapshot)");
        } else {
            System.out.println("NASDAQ top of book at the 4 p.m. close:");
            for (ReplayListener.TopOfBook t : r.closeSnapshot().values()) {
                System.out.printf("  %-6s bid %s  |  ask %s%n", t.symbol(),
                        side(t.bid().isPresent(), t.bid().orElse(0), t.bidSize()),
                        side(t.ask().isPresent(), t.ask().orElse(0), t.askSize()));
            }
        }
    }

    private static String side(boolean present, long price, long size) {
        return present ? String.format("%12s x %-7d", Prices.toString(price), size) : String.format("%22s", "-");
    }
}
