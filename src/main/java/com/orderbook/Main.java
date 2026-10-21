package com.orderbook;

import com.orderbook.replay.Replay;

/**
 * Entry point for `mvn exec:java`.
 *
 *   no arguments           -> a reminder of how to run things
 *   <itch file> [TICKERS]  -> replay the file into order books and print a report
 */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("java-order-book");
            System.out.println("Replay a NASDAQ ITCH file:");
            System.out.println("  mvn -q compile exec:java -Dexec.args=\"data/01302020.NASDAQ_ITCH50.gz\"");
            return;
        }
        Replay.main(args);
    }
}
