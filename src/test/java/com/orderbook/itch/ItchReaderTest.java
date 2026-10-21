package com.orderbook.itch;

import com.orderbook.core.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.orderbook.itch.ItchMessages.*;
import static com.orderbook.itch.RecordingListener.*;
import static org.junit.jupiter.api.Assertions.*;

/** The reader cuts a stream of length-prefixed messages into single messages. */
class ItchReaderTest {

    private static final long T = 34_200_000_000_000L;

    private final ItchReader reader = new ItchReader();
    private final RecordingListener listener = new RecordingListener();

    private long read(byte[] stream) throws IOException {
        return reader.readAll(new ByteArrayInputStream(stream), listener);
    }

    @Test
    @DisplayName("an empty stream has no messages")
    void emptyStream() throws IOException {
        assertEquals(0, read(new byte[0]));
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("reads several messages of different lengths, in order")
    void readsInOrder() throws IOException {
        byte[] stream = framed(
                systemEvent(T, 'O'),                              // 12 bytes
                stockDirectory(1, T, "AAPL"),                     // 39 bytes
                addOrder(1, T, 42, 'B', 100, "AAPL", 1_874_300),  // 36 bytes
                ItchMessages.delete(1, T, 42));                   // 19 bytes

        assertEquals(4, read(stream));
        assertEquals(List.of(
                new SystemEvent(T, 'O'),
                new StockDirectory(1, T, "AAPL"),
                new AddOrder(1, T, 42, Side.BUY, 100, 1_874_300),
                new Delete(1, T, 42)
        ), listener.events);
    }

    @Test
    @DisplayName("unknown message types are skipped by their length, and reading carries on")
    void skipsUnknownTypes() throws IOException {
        byte[] stream = framed(
                unknown('P', 44),
                ItchMessages.delete(1, T, 42),
                unknown('Q', 40),
                ItchMessages.delete(1, T, 43));

        assertEquals(4, read(stream));
        assertEquals(List.of(new Other('P'), new Delete(1, T, 42), new Other('Q'), new Delete(1, T, 43)),
                listener.events);
    }

    @Test
    @DisplayName("a long run of messages (the reused buffer must not leak bytes between them)")
    void manyMessages() throws IOException {
        byte[][] messages = new byte[1_000][];
        for (int i = 0; i < messages.length; i++) {
            // alternate long and short messages so a stale tail from a longer one would show up
            messages[i] = (i % 2 == 0)
                    ? addOrder(1, T, i, 'S', i + 1, "MSFT", 100_000 + i)
                    : ItchMessages.delete(1, T, i);
        }
        assertEquals(1_000, read(framed(messages)));
        assertEquals(new AddOrder(1, T, 998, Side.SELL, 999, 100_998), listener.events.get(998));
        assertEquals(new Delete(1, T, 999), listener.events.get(999));
    }

    @Test
    @DisplayName("a stream cut off in the middle of a message throws EOFException")
    void truncatedMessage() {
        byte[] full = framed(ItchMessages.delete(1, T, 42), ItchMessages.delete(1, T, 43));
        byte[] cut = Arrays.copyOf(full, full.length - 5);

        assertThrows(EOFException.class, () -> read(cut));
        assertEquals(List.of(new Delete(1, T, 42)), listener.events, "the complete first message is still delivered");
    }

    @Test
    @DisplayName("a stream cut off in the middle of a length prefix throws EOFException")
    void truncatedLength() {
        byte[] full = framed(ItchMessages.delete(1, T, 42));
        byte[] cut = Arrays.copyOf(full, full.length + 1);   // one stray byte after the message
        cut[cut.length - 1] = 0x00;

        assertThrows(EOFException.class, () -> read(cut));
    }
}
