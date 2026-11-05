package com.orderbook.bench;

import com.orderbook.core.Side;
import com.orderbook.itch.ItchListener;
import com.orderbook.itch.ItchReader;
import com.orderbook.replay.ReplayListener;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Where does the replay's time go?
 *
 * Runs the same file through progressively more of the pipeline and subtracts:
 *
 *   A  read the .gz file from disk, nothing else          -> disk
 *   B  read + decompress                                  -> B - A = decompression
 *   C  read + decompress + frame + parse (no book work)   -> C - B = parsing
 *   D  the full replay into order books                   -> D - C = book updates
 *
 * Usage:
 *     MAVEN_OPTS="-Xmx6g" mvn -q compile exec:java -Dexec.args="breakdown data/01302020.NASDAQ_ITCH50.gz"
 *
 * Caveats (this is a rough split, not a JMH benchmark):
 *   - Each stage runs once. Run the whole thing twice and expect the numbers to move a bit.
 *   - Stage A's first read may come from disk and later ones from the operating system's
 *     file cache, so A mostly measures "reading bytes", not your SSD.
 *   - The stages overlap in reality (the CPU decompresses while the disk reads ahead),
 *     so the differences are estimates of each stage's cost, not exact.
 */
public final class Breakdown {

    private static final int BUFFER = 1 << 20;   // 1 MB

    private Breakdown() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: breakdown <itch-file.gz>");
            System.exit(1);
        }
        String path = args[0];
        System.out.println("Timing " + path + " in four stages (this reads the file four times)...");
        System.out.println();

        // A: raw bytes from disk
        long t = System.nanoTime();
        long compressedBytes = drain(new FileInputStream(path));
        double a = seconds(t);
        System.out.printf("A  read file                  %7.1f s   (%,d MB compressed)%n", a, compressedBytes >> 20);

        // B: read + decompress
        t = System.nanoTime();
        long rawBytes = drain(new GZIPInputStream(new FileInputStream(path), BUFFER));
        double b = seconds(t);
        System.out.printf("B  + decompress               %7.1f s   (%,d MB raw ITCH)%n", b, rawBytes >> 20);

        // C: read + decompress + parse, with a listener that does almost nothing
        ChecksumListener checksum = new ChecksumListener();
        t = System.nanoTime();
        readAll(path, checksum);
        double c = seconds(t);
        long messages = checksum.messages;   // counted by the listener, so a truncated sample still reports
        System.out.printf("C  + parse (no book work)     %7.1f s   (%,d messages)%n", c, messages);

        // D: full replay
        ReplayListener replay = new ReplayListener();
        t = System.nanoTime();
        readAll(path, replay);
        double d = seconds(t);
        System.out.printf("D  + book updates (full)      %7.1f s%n", d);

        System.out.println();
        System.out.println("Estimated cost of each stage:");
        row("disk read", a, d);
        row("decompression", b - a, d);
        row("framing + parsing", c - b, d);
        row("book updates", d - c, d);
        System.out.println();
        System.out.printf("Parsing alone:      %,.0f messages/s%n", messages / (c - b));
        System.out.printf("Book updates alone: %,.0f messages/s%n", messages / (d - c));
        System.out.println();
        // Printed so the JIT can't treat the parse-only run's work as unused and delete it.
        System.out.println("(checksum " + Long.toHexString(checksum.sum) + ", health: "
                + replay.unknownOrderEvents() + " unknown, " + replay.rejectedEvents() + " rejected)");
    }

    /** Read a stream to the end into a reused buffer, counting bytes. */
    private static long drain(InputStream in) throws IOException {
        byte[] buf = new byte[BUFFER];
        long total = 0;
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
            }
        }
        return total;
    }

    private static void readAll(String path, ItchListener listener) throws IOException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(new FileInputStream(path), BUFFER), BUFFER)) {
            new ItchReader().readAll(in, listener);
        } catch (EOFException e) {
            // a sample cut from the start of a file ends mid-message: time what we got
        }
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    private static void row(String name, double secs, double total) {
        System.out.printf("  %-18s %7.1f s   %5.1f%%%n", name, secs, 100 * secs / total);
    }

    /**
     * A listener that does almost nothing, but not quite nothing.
     *
     * If its methods were empty, the JIT compiler could notice that the decoded fields are
     * never used and skip reading them, so stage C would measure less than real parsing.
     * Folding every field into one number, and printing it at the end, keeps the work "used".
     * (JMH's Blackhole exists to solve exactly this problem.)
     */
    private static final class ChecksumListener implements ItchListener {
        long sum;
        long messages;

        @Override public void onSystemEvent(long ts, char code) { messages++; sum += ts ^ code; }
        @Override public void onStockDirectory(int loc, long ts, String symbol) { messages++; sum += loc ^ ts ^ symbol.length(); }
        @Override public void onAddOrder(int loc, long ts, long ref, Side side, long shares, long price) {
            messages++;
            sum += loc ^ ts ^ ref ^ side.ordinal() ^ shares ^ price;
        }
        @Override public void onOrderExecuted(int loc, long ts, long ref, long shares) { messages++; sum += loc ^ ts ^ ref ^ shares; }
        @Override public void onOrderExecutedWithPrice(int loc, long ts, long ref, long shares, long price) {
            messages++;
            sum += loc ^ ts ^ ref ^ shares ^ price;
        }
        @Override public void onOrderCancel(int loc, long ts, long ref, long shares) { messages++; sum += loc ^ ts ^ ref ^ shares; }
        @Override public void onOrderDelete(int loc, long ts, long ref) { messages++; sum += loc ^ ts ^ ref; }
        @Override public void onOrderReplace(int loc, long ts, long oldRef, long newRef, long shares, long price) {
            messages++;
            sum += loc ^ ts ^ oldRef ^ newRef ^ shares ^ price;
        }
        @Override public void onOther(char type) { messages++; sum += type; }
    }
}
