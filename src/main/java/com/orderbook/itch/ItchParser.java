package com.orderbook.itch;

import com.orderbook.core.Side;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes ONE ITCH 5.0 message (already cut out of the stream) and calls the listener.
 *
 * The buffer it receives:
 *   - byte 0 is the message type; the 2-byte length prefix has already been removed
 *     by ItchReader, so the spec's offsets apply directly
 *   - its limit is the end of the message
 *
 * Every read is ABSOLUTE (msg.getInt(20), not msg.getInt()): it names the exact offset
 * from the spec table and never moves the buffer's position. That makes each line
 * checkable against the spec, and the same buffer can be parsed twice safely.
 *
 * Every message starts with the same 11-byte header:
 *     offset 0  len 1  Message Type
 *     offset 1  len 2  Stock Locate      unsigned 16-bit
 *     offset 3  len 2  Tracking Number   (not used)
 *     offset 5  len 6  Timestamp         unsigned 48-bit, ns since midnight
 * Message-specific fields start at offset 11. Layouts: ITCH 5.0 spec, section 1.
 */
public final class ItchParser {

    // Header offsets shared by every message type.
    private static final int TYPE = 0;
    private static final int STOCK_LOCATE = 1;
    private static final int TIMESTAMP = 5;
    private static final int BODY = 11;   // first message-specific field

    public void parse(ByteBuffer msg, ItchListener listener) {
        char type = (char) msg.get(TYPE);

        switch (type) {
            case 'S' -> listener.onSystemEvent(
                    timestamp(msg),
                    (char) msg.get(BODY));                       // 11: Event Code

            case 'R' -> listener.onStockDirectory(
                    locate(msg),
                    timestamp(msg),
                    symbol(msg, BODY));                          // 11..18: Stock

            // A and F share the same layout up to offset 36; F then adds a 4-byte
            // attribution (the firm's ID), which we don't need, so one case handles both.
            case 'A', 'F' -> listener.onAddOrder(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY),                           // 11: Order Reference Number
                    side(msg.get(19)),                           // 19: Buy/Sell Indicator
                    u32(msg, 20),                                // 20: Shares
                    // 24..31: Stock (symbol) skipped: the locate already identifies the stock
                    u32(msg, 32));                               // 32: Price

            case 'E' -> listener.onOrderExecuted(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY),                           // 11: Order Reference Number
                    u32(msg, 19));                               // 19: Executed Shares
                    // 23: Match Number (8) not needed for the book

            case 'C' -> listener.onOrderExecutedWithPrice(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY),                           // 11: Order Reference Number
                    u32(msg, 19),                                // 19: Executed Shares
                    // 23: Match Number (8), 31: Printable (1) not needed
                    u32(msg, 32));                               // 32: Execution Price

            case 'X' -> listener.onOrderCancel(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY),                           // 11: Order Reference Number
                    u32(msg, 19));                               // 19: Cancelled Shares

            case 'D' -> listener.onOrderDelete(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY));                          // 11: Order Reference Number

            case 'U' -> listener.onOrderReplace(
                    locate(msg),
                    timestamp(msg),
                    msg.getLong(BODY),                           // 11: Original Order Reference Number
                    msg.getLong(19),                             // 19: New Order Reference Number
                    u32(msg, 27),                                // 27: Shares
                    u32(msg, 31));                               // 31: Price

            // ~20 message types exist (trades, auctions, halts, ...). The ones we don't
            // need are reported, never thrown: one unexpected type mustn't stop a replay.
            default -> listener.onOther(type);
        }
    }

    // ===================== field helpers =====================
    // ByteBuffer is big-endian by default, which is ITCH's byte order, so getShort/getInt/
    // getLong read the bytes in the right order. What they get wrong is the SIGN: Java's
    // short, int and long are signed, but ITCH's numbers are unsigned. Each helper masks
    // the value into a wider type so the top bit is treated as a value, not a minus sign.

    /** Stock Locate: unsigned 16-bit at offset 1. */
    private static int locate(ByteBuffer msg) {
        return u16(msg, STOCK_LOCATE);
    }

    /** Timestamp: unsigned 48-bit at offset 5. */
    private static long timestamp(ByteBuffer msg) {
        return u48(msg, TIMESTAMP);
    }

    /**
     * Unsigned 16-bit. getShort returns -32768..32767; "& 0xFFFF" keeps only the low 16 bits
     * as a positive int, so 0x9C40 reads as 40000 instead of -25536.
     */
    static int u16(ByteBuffer b, int offset) {
        return b.getShort(offset) & 0xFFFF;
    }

    /**
     * Unsigned 32-bit. getInt returns a signed int, so a price above $214,748.3647 would be
     * negative. "& 0xFFFFFFFFL" (note the L: a long mask) widens it to a long first, keeping
     * all 32 bits as a positive value.
     */
    static long u32(ByteBuffer b, int offset) {
        return b.getInt(offset) & 0xFFFF_FFFFL;
    }

    /**
     * Unsigned 48-bit. There's no getInt48, so read it in two parts:
     *   high = the first 2 bytes (offset .. offset+1)    -> bits 47..32
     *   low  = the next 4 bytes  (offset+2 .. offset+5)  -> bits 31..0
     * and glue them together: (high << 32) | low.
     * Both halves go through the unsigned helpers. Without that, a low half like 0x80000000
     * would be sign-extended to 0xFFFFFFFF80000000 and the OR would wipe out the high half.
     */
    static long u48(ByteBuffer b, int offset) {
        long high = u16(b, offset);
        long low = u32(b, offset + 2);
        return (high << 32) | low;
    }

    /** 8-byte ASCII stock symbol, right-padded with spaces: "AAPL    " -> "AAPL". */
    static String symbol(ByteBuffer b, int offset) {
        byte[] chars = new byte[8];
        b.get(offset, chars);   // absolute bulk read (Java 13+): doesn't move the position
        return new String(chars, StandardCharsets.US_ASCII).trim();
    }

    /** Buy/Sell Indicator: 'B' or 'S'. Anything else means we're reading the wrong bytes. */
    private static Side side(byte indicator) {
        return switch (indicator) {
            case 'B' -> Side.BUY;
            case 'S' -> Side.SELL;
            default -> throw new IllegalArgumentException(
                    "Bad Buy/Sell Indicator 0x" + Integer.toHexString(indicator & 0xFF)
                    + ": expected 'B' or 'S' (is an offset wrong?)");
        };
    }
}
