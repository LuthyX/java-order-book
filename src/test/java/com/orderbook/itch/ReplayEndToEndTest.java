package com.orderbook.itch;

import com.orderbook.core.OrderBook;
import com.orderbook.core.Side;
import com.orderbook.replay.ReplayListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.OptionalLong;
import java.util.Set;

import static com.orderbook.itch.ItchMessages.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole pipeline: raw bytes (as in the file) -> ItchReader -> ItchParser -> ReplayListener -> books.
 * Lives in the itch package so it can use the ItchMessages byte builder.
 */
class ReplayEndToEndTest {

    @Test
    @DisplayName("a small trading 'day' in raw bytes rebuilds the right book")
    void miniDay() throws IOException {
        long t = 34_200_000_000_000L;
        byte[] day = framed(
                systemEvent(10_800_000_000_000L, 'O'),          // start of messages
                stockDirectory(13, t, "AAPL"),
                addOrder(13, t, 1, 'S', 100, "AAPL", 3_208_500), // ask 100 @ 320.85
                addOrder(13, t, 2, 'S', 200, "AAPL", 3_209_000), // ask 200 @ 320.90
                addOrder(13, t, 3, 'B', 300, "AAPL", 3_208_000), // bid 300 @ 320.80
                executed(13, t, 1, 100, 1),                      // #1 fully traded: 320.85 level gone
                cancel(13, t, 3, 50),                            // #3 down to 250
                replace(13, t, 2, 4, 150, 3_208_800),            // #2 -> #4: sell 150 @ 320.88
                unknown('P', 44),                                // a trade message: counted, not applied
                systemEvent(57_600_000_000_000L, 'M'));          // 4 p.m. close

        ReplayListener replay = new ReplayListener(Set.of("AAPL"));
        long messages = new ItchReader().readAll(new ByteArrayInputStream(day), replay);

        assertEquals(10, messages);
        assertEquals(10, replay.messageCount());
        OrderBook aapl = replay.bookFor("AAPL").orElseThrow();
        assertEquals(OptionalLong.of(3_208_000), aapl.bestBid());
        assertEquals(250, aapl.volumeAt(Side.BUY, 3_208_000));
        assertEquals(OptionalLong.of(3_208_800), aapl.bestAsk());
        assertEquals(150, aapl.volumeAt(Side.SELL, 3_208_800));
        assertEquals(Side.SELL, aapl.getOrder(4).orElseThrow().side());
        assertEquals(2, replay.restingOrders());
        assertEquals(3, replay.peakRestingOrders());
        assertEquals(0, replay.unknownOrderEvents());
        assertEquals(0, replay.rejectedEvents());
        assertEquals(0, replay.crossedBooks());
        assertEquals(OptionalLong.of(3_208_800), replay.closeSnapshot().get("AAPL").ask());
    }
}
