package com.orderbook.itch;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Cuts a raw ITCH file stream into messages and hands each one to the parser.
 *
 * File format (NASDAQ's sample files): message after message, each prefixed by its
 * length as an unsigned 16-bit big-endian number. Nothing else: no headers, no separators.
 *
 *     [len hi][len lo][ message: len bytes ][len hi][len lo][ message ] ...
 *
 * Design choices:
 *  - Streaming: one message in memory at a time, so a many-GB day never has to fit in RAM.
 *  - One reused buffer: a 2-byte length can't exceed 65,535, so a buffer that size fits
 *    every possible message. Allocating a new array per message would create hundreds of
 *    millions of short-lived objects for the garbage collector.
 *  - Truncation fails loudly: a file cut off mid-message throws EOFException instead of
 *    silently replaying half a message.
 *
 * For real files the caller passes something like:
 *     new BufferedInputStream(new GZIPInputStream(new FileInputStream(path)), 1 << 16)
 */
public final class ItchReader {

    private static final int MAX_MESSAGE_LENGTH = 0xFFFF;   // largest value a 2-byte length can hold

    private final ItchParser parser = new ItchParser();
    private final byte[] buffer = new byte[MAX_MESSAGE_LENGTH];

    /**
     * Read every message from the stream, in order, passing each to the listener.
     *
     * @return the number of messages read
     * @throws EOFException if the stream ends partway through a message or its length prefix
     */
    public long readAll(InputStream in, ItchListener listener) throws IOException {
        DataInputStream data = new DataInputStream(in);
        long count = 0;

        while (true) {
            // Read the length prefix one byte at a time, so we can tell the two kinds of "end" apart:
            //   end of stream BEFORE the first byte  -> the file ended cleanly between messages
            //   end of stream AFTER the first byte   -> the file was cut off inside a length prefix
            int high = data.read();
            if (high == -1) {
                return count;
            }
            int low = data.read();
            if (low == -1) {
                throw new EOFException("Stream ended inside a length prefix after " + count + " messages");
            }
            int length = (high << 8) | low;   // big-endian: first byte is the high byte

            // readFully keeps reading until it has exactly `length` bytes, or throws EOFException.
            // (A plain read() may return fewer bytes than asked, especially from a GZIP stream.)
            data.readFully(buffer, 0, length);

            // wrap(buffer, 0, length): position 0, limit `length`, so the parser sees exactly
            // this message and nothing left over from a longer earlier one.
            parser.parse(ByteBuffer.wrap(buffer, 0, length), listener);
            count++;
        }
    }
}
