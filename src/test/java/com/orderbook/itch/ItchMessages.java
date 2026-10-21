package com.orderbook.itch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Test helper: builds real ITCH 5.0 messages byte by byte, following the spec's layouts.
 * Each method returns the message WITHOUT the 2-byte length prefix (what the parser sees);
 * framed() adds the prefix (what the file contains).
 */
final class ItchMessages {

    private ItchMessages() {}

    static byte[] systemEvent(long ts, char code) {
        return header(12, 'S', 0, ts).put((byte) code).array();
    }

    static byte[] stockDirectory(int locate, long ts, String symbol) {
        ByteBuffer b = header(39, 'R', locate, ts);
        putSymbol(b, symbol);       // offset 11..18
        // offsets 19..38: market category, round lot size, flags... not decoded; leave as zeros
        return b.array();
    }

    static byte[] addOrder(int locate, long ts, long ref, char side, long shares, String symbol, long price) {
        ByteBuffer b = header(36, 'A', locate, ts);
        b.putLong(ref).put((byte) side).putInt((int) shares);
        putSymbol(b, symbol);
        b.putInt((int) price);
        return b.array();
    }

    static byte[] addOrderWithMpid(int locate, long ts, long ref, char side, long shares, String symbol,
                                   long price, String mpid) {
        ByteBuffer b = header(40, 'F', locate, ts);
        b.putLong(ref).put((byte) side).putInt((int) shares);
        putSymbol(b, symbol);
        b.putInt((int) price);
        b.put(mpid.getBytes(StandardCharsets.US_ASCII), 0, 4);
        return b.array();
    }

    static byte[] executed(int locate, long ts, long ref, long shares, long matchNumber) {
        return header(31, 'E', locate, ts).putLong(ref).putInt((int) shares).putLong(matchNumber).array();
    }

    static byte[] executedWithPrice(int locate, long ts, long ref, long shares, long matchNumber, long price) {
        return header(36, 'C', locate, ts).putLong(ref).putInt((int) shares).putLong(matchNumber)
                .put((byte) 'Y').putInt((int) price).array();
    }

    static byte[] cancel(int locate, long ts, long ref, long cancelledShares) {
        return header(23, 'X', locate, ts).putLong(ref).putInt((int) cancelledShares).array();
    }

    static byte[] delete(int locate, long ts, long ref) {
        return header(19, 'D', locate, ts).putLong(ref).array();
    }

    static byte[] replace(int locate, long ts, long oldRef, long newRef, long shares, long price) {
        return header(35, 'U', locate, ts).putLong(oldRef).putLong(newRef).putInt((int) shares)
                .putInt((int) price).array();
    }

    /** A type we don't decode, with some body bytes, like a real Trade (P) message would have. */
    static byte[] unknown(char type, int length) {
        return header(length, type, 0, 0).array();
    }

    /** What the file actually contains: a 2-byte big-endian length, then the message. */
    static byte[] framed(byte[]... messages) {
        int total = 0;
        for (byte[] m : messages) total += 2 + m.length;
        ByteBuffer out = ByteBuffer.allocate(total);
        for (byte[] m : messages) out.putShort((short) m.length).put(m);
        return out.array();
    }

    // The 11-byte header every message starts with. Returns a buffer positioned at offset 11.
    private static ByteBuffer header(int length, char type, int locate, long ts) {
        ByteBuffer b = ByteBuffer.allocate(length);          // big-endian by default
        b.put((byte) type);                                  // 0
        b.putShort((short) locate);                          // 1..2
        b.putShort((short) 0);                               // 3..4 tracking number
        b.putShort((short) (ts >>> 32)).putInt((int) ts);    // 5..10: 48-bit timestamp
        return b;
    }

    private static void putSymbol(ByteBuffer b, String symbol) {
        String padded = String.format("%-8s", symbol);       // right-pad with spaces to 8
        b.put(padded.getBytes(StandardCharsets.US_ASCII));
    }
}
