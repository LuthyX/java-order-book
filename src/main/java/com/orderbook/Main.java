package com.orderbook;

import com.orderbook.bench.BookBenchmark;
import com.orderbook.bench.Breakdown;
import com.orderbook.replay.Replay;

import java.util.Arrays;

/**
 * Entry point for `mvn exec:java`.
 *
 *   no arguments                 -> a reminder of how to run things
 *   breakdown <itch file.gz>     -> time each stage of the pipeline separately
 *   bench <itch file> [events]   -> benchmark the order book alone (throughput + latency)
 *   <itch file> [TICKERS]        -> replay the file into order books and print a report
 */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("java-order-book");
            System.out.println("Replay a NASDAQ ITCH file:");
            System.out.println("  mvn -q compile exec:java -Dexec.args=\"data/01302020.NASDAQ_ITCH50.gz\"");
            System.out.println("Time each stage (disk / decompress / parse / book):");
            System.out.println("  mvn -q compile exec:java -Dexec.args=\"breakdown data/01302020.NASDAQ_ITCH50.gz\"");
            return;
        }
        if (args[0].equals("breakdown")) {
            Breakdown.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args[0].equals("bench")) {
            BookBenchmark.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Replay.main(args);
    }
}
