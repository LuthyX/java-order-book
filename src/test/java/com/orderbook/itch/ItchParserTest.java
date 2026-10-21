package com.orderbook.itch;

import com.orderbook.core.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;

import static com.orderbook.itch.ItchMessages.*;
import static com.orderbook.itch.RecordingListener.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each test builds a real ITCH message byte by byte and checks the parser decodes every field.
 * Work through them top to bottom.
 */
class ItchParserTest {

    private static final long NINE_THIRTY = 34_200_000_000_000L;   // 09:30:00.000 in ns since midnight

    private final ItchParser parser = new ItchParser();
    private final RecordingListener listener = new RecordingListener();

    private List<Object> parse(byte[] message) {
        parser.parse(ByteBuffer.wrap(message), listener);
        return listener.events;
    }

    // ------------------------------------------------------------------
    // The golden test: bytes typed out by hand, NOT made by ItchMessages.
    // If the builder and your parser shared the same mistake, every other test
    // could still pass. This one can't.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("golden bytes: a hand-written Add Order decodes to exactly the right fields")
    void goldenAddOrder() {
        byte[] bytes = HexFormat.ofDelimiter(" ").parseHex(
                "41 "                          // 'A'
              + "00 01 "                       // stock locate 1
              + "00 00 "                       // tracking number
              + "1F 1A CE D9 F0 00 "           // timestamp 34,200,000,000,000 ns = 09:30:00
              + "00 00 00 00 00 00 30 39 "     // order ref 12345
              + "42 "                          // 'B' buy
              + "00 00 00 64 "                 // 100 shares
              + "41 41 50 4C 20 20 20 20 "     // "AAPL    "
              + "00 1C 99 7C");                // price 1,874,300 = $187.4300

        assertEquals(List.of(new AddOrder(1, NINE_THIRTY, 12345, Side.BUY, 100, 1_874_300)), parse(bytes));
    }

    // ------------------------------------------------------------------
    // One test per message type
    // ------------------------------------------------------------------

    @Test
    @DisplayName("S System Event: timestamp and event code")
    void decodesSystemEvent() {
        assertEquals(List.of(new SystemEvent(10_800_000_000_000L, 'O')),
                parse(systemEvent(10_800_000_000_000L, 'O')));   // 03:00:00, 'O' = start of messages
    }

    @Test
    @DisplayName("R Stock Directory: locate code and trimmed symbol")
    void decodesStockDirectory() {
        assertEquals(List.of(new StockDirectory(13, NINE_THIRTY, "AAPL")),
                parse(stockDirectory(13, NINE_THIRTY, "AAPL")));
    }

    @Test
    @DisplayName("R Stock Directory: a full 8-character symbol has nothing to trim")
    void decodesStockDirectoryLongSymbol() {
        assertEquals(List.of(new StockDirectory(2, NINE_THIRTY, "ABCDEFGH")),
                parse(stockDirectory(2, NINE_THIRTY, "ABCDEFGH")));
    }

    @Test
    @DisplayName("A Add Order: sell side")
    void decodesAddOrderSell() {
        assertEquals(List.of(new AddOrder(7, NINE_THIRTY, 999, Side.SELL, 300, 100_500)),
                parse(addOrder(7, NINE_THIRTY, 999, 'S', 300, "MSFT", 100_500)));
    }

    @Test
    @DisplayName("F Add Order with MPID decodes the same as A (attribution ignored)")
    void decodesAddOrderWithMpid() {
        assertEquals(List.of(new AddOrder(7, NINE_THIRTY, 1000, Side.BUY, 200, 99_900)),
                parse(addOrderWithMpid(7, NINE_THIRTY, 1000, 'B', 200, "MSFT", 99_900, "GSCO")));
    }

    @Test
    @DisplayName("E Order Executed: order ref and executed shares")
    void decodesExecuted() {
        assertEquals(List.of(new Executed(7, NINE_THIRTY, 999, 120)),
                parse(executed(7, NINE_THIRTY, 999, 120, 555_555)));
    }

    @Test
    @DisplayName("C Order Executed With Price: shares and the execution price")
    void decodesExecutedWithPrice() {
        assertEquals(List.of(new ExecutedWithPrice(7, NINE_THIRTY, 999, 50, 100_400)),
                parse(executedWithPrice(7, NINE_THIRTY, 999, 50, 555_556, 100_400)));
    }

    @Test
    @DisplayName("X Order Cancel: order ref and cancelled shares")
    void decodesCancel() {
        assertEquals(List.of(new Cancel(7, NINE_THIRTY, 999, 80)),
                parse(cancel(7, NINE_THIRTY, 999, 80)));
    }

    @Test
    @DisplayName("D Order Delete: order ref")
    void decodesDelete() {
        assertEquals(List.of(new Delete(7, NINE_THIRTY, 999)),
                parse(delete(7, NINE_THIRTY, 999)));
    }

    @Test
    @DisplayName("U Order Replace: original ref, new ref, new shares and price")
    void decodesReplace() {
        assertEquals(List.of(new Replace(7, NINE_THIRTY, 999, 1001, 400, 100_600)),
                parse(replace(7, NINE_THIRTY, 999, 1001, 400, 100_600)));
    }

    // ------------------------------------------------------------------
    // The traps: unsigned values and the 48-bit timestamp
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unsigned price: the 4-byte maximum ($429,496.7295) must not come out negative")
    void unsignedPrice() {
        long maxPrice = 0xFFFF_FFFFL;   // 4,294,967,295 = $429,496.7295
        assertEquals(List.of(new AddOrder(1, NINE_THIRTY, 1, Side.SELL, 1, maxPrice)),
                parse(addOrder(1, NINE_THIRTY, 1, 'S', 1, "BRK A", maxPrice)));
    }

    @Test
    @DisplayName("unsigned shares above 2^31 must not come out negative")
    void unsignedShares() {
        long shares = 3_000_000_000L;
        assertEquals(List.of(new Executed(1, NINE_THIRTY, 1, shares)),
                parse(executed(1, NINE_THIRTY, 1, shares, 1)));
    }

    @Test
    @DisplayName("unsigned stock locate above 32,767 must not come out negative")
    void unsignedLocate() {
        assertEquals(List.of(new Delete(40_000, NINE_THIRTY, 5)),
                parse(delete(40_000, NINE_THIRTY, 5)));
    }

    @Test
    @DisplayName("48-bit timestamp: one nanosecond before midnight needs more than 32 bits")
    void largeTimestamp() {
        long lastNanoOfDay = 86_399_999_999_999L;   // 23:59:59.999999999
        assertEquals(List.of(new SystemEvent(lastNanoOfDay, 'C')),
                parse(systemEvent(lastNanoOfDay, 'C')));
    }

    @Test
    @DisplayName("48-bit timestamp: a low half with its top bit set must not leak a sign")
    void timestampLowHalfTopBit() {
        long ts = 0x0001_8000_0000L;   // low 32 bits = 0x80000000, negative if read as a signed int
        assertEquals(List.of(new SystemEvent(ts, 'Q')), parse(systemEvent(ts, 'Q')));
    }

    // ------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown message type is reported via onOther, never thrown")
    void unknownType() {
        assertEquals(List.of(new Other('P')), parse(unknown('P', 44)));   // P = Trade (non-cross)
    }

    @Test
    @DisplayName("parsing is absolute: the buffer's position is not used or required")
    void independentOfPosition() {
        ByteBuffer buf = ByteBuffer.wrap(delete(7, NINE_THIRTY, 999));
        parser.parse(buf, listener);
        parser.parse(buf, listener);   // same buffer again: must decode identically
        assertEquals(List.of(new Delete(7, NINE_THIRTY, 999), new Delete(7, NINE_THIRTY, 999)), listener.events);
    }
}
