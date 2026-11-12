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
 * The book-changing events from the start of an ITCH file, decoded once and held in memory,
 * so benchmarks can replay them again and again with no disk, decompression or parsing.
 *
 * Stored as PARALLEL PRIMITIVE ARRAYS ("structure of arrays"): event i is
 * kind[i], locate[i], ref[i], ... One object per event (a List<Event>) would mean tens of
 * millions of objects, and the garbage collector would be busy with the benchmark's own
 * data instead of the book's. Arrays of primitives are compact (~38 bytes per event) and
 * create almost no objects.
 *
 * Only messages that change a book are kept: A/F, E, C, X, D, U.
 */
public final class CapturedEvents {

    static final byte ADD = 1, EXECUTE = 2, CANCEL = 3, DELETE = 4, REPLACE = 5;

    private final byte[] kind;
    private final int[] locate;
    private final long[] ref;      // order ref; for REPLACE the ORIGINAL ref
    private final long[] newRef;   // REPLACE only
    private final long[] shares;
    private final long[] price;    // ADD and REPLACE only
    private final boolean[] buy;   // ADD only
    private int size;
    private long lastTimestamp;

    private CapturedEvents(int capacity) {
        kind = new byte[capacity];
        locate = new int[capacity];
        ref = new long[capacity];
        newRef = new long[capacity];
        shares = new long[capacity];
        price = new long[capacity];
        buy = new boolean[capacity];
    }

    public int size() { return size; }

    /** Number of event kinds, for arrays indexed by kind (index 0 is unused). */
    static final int KINDS = 6;

    /** The kind of event i: ADD, EXECUTE, CANCEL, DELETE or REPLACE. */
    byte kind(int i) { return kind[i]; }

    /** A readable name for an event kind, for reports. */
    static String kindName(int k) {
        return switch (k) {
            case ADD -> "add (A/F)";
            case EXECUTE -> "execute (E/C)";
            case CANCEL -> "partial cancel (X)";
            case DELETE -> "delete (D)";
            case REPLACE -> "replace (U)";
            default -> "?";
        };
    }

    /** Time of day (ns since midnight) of the last captured event: how far into the day the slice reaches. */
    public long lastTimestamp() { return lastTimestamp; }

    /** Decode the first maxEvents book events of a (gzipped or raw) ITCH file. */
    public static CapturedEvents capture(String path, int maxEvents) throws IOException {
        CapturedEvents events = new CapturedEvents(maxEvents);
        Recorder recorder = events.new Recorder();
        try (InputStream in = open(path)) {
            new ItchReader().readAll(in, recorder);
        } catch (Full stop) {
            // reached maxEvents: stop reading (see Full below)
        } catch (EOFException e) {
            // a sample file cut mid-message: keep what we have
        }
        return events;
    }

    /** Apply event i to a replay, exactly as if the parser had just decoded it. */
    public void apply(int i, ReplayListener r) {
        switch (kind[i]) {
            case ADD -> r.onAddOrder(locate[i], 0, ref[i], buy[i] ? Side.BUY : Side.SELL, shares[i], price[i]);
            case EXECUTE -> r.onOrderExecuted(locate[i], 0, ref[i], shares[i]);
            case CANCEL -> r.onOrderCancel(locate[i], 0, ref[i], shares[i]);
            case DELETE -> r.onOrderDelete(locate[i], 0, ref[i]);
            case REPLACE -> r.onOrderReplace(locate[i], 0, ref[i], newRef[i], shares[i], price[i]);
            default -> throw new IllegalStateException("bad event kind " + kind[i]);
        }
    }

    /** Apply every event, in order. */
    public void applyAll(ReplayListener r) {
        for (int i = 0; i < size; i++) {
            apply(i, r);
        }
    }

    private static InputStream open(String path) throws IOException {
        InputStream file = new FileInputStream(path);
        return path.endsWith(".gz")
                ? new BufferedInputStream(new GZIPInputStream(file, 1 << 20), 1 << 20)
                : new BufferedInputStream(file, 1 << 20);
    }

    /**
     * Thrown by the recorder when the arrays are full, to stop the reader early.
     * The reader has no "stop" button, and reading the rest of a 400M-message file just to
     * ignore it would waste minutes. Skipping the stack trace makes it cheap to throw.
     */
    private static final class Full extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Full() { super(null, null, false, false); }
    }

    /** The capturing listener: appends each book event to the arrays. */
    private final class Recorder implements ItchListener {

        private int next(byte k, int loc, long ts, long r) {
            if (size == kind.length) throw new Full();
            int i = size++;
            kind[i] = k;
            locate[i] = loc;
            ref[i] = r;
            lastTimestamp = ts;
            return i;
        }

        @Override public void onAddOrder(int loc, long ts, long r, Side side, long sh, long px) {
            int i = next(ADD, loc, ts, r);
            buy[i] = side == Side.BUY;
            shares[i] = sh;
            price[i] = px;
        }

        @Override public void onOrderExecuted(int loc, long ts, long r, long sh) {
            shares[next(EXECUTE, loc, ts, r)] = sh;
        }

        @Override public void onOrderExecutedWithPrice(int loc, long ts, long r, long sh, long px) {
            shares[next(EXECUTE, loc, ts, r)] = sh;   // same effect on the book as E
        }

        @Override public void onOrderCancel(int loc, long ts, long r, long sh) {
            shares[next(CANCEL, loc, ts, r)] = sh;
        }

        @Override public void onOrderDelete(int loc, long ts, long r) {
            next(DELETE, loc, ts, r);
        }

        @Override public void onOrderReplace(int loc, long ts, long oldRef, long nRef, long sh, long px) {
            int i = next(REPLACE, loc, ts, oldRef);
            newRef[i] = nRef;
            shares[i] = sh;
            price[i] = px;
        }
    }
}
