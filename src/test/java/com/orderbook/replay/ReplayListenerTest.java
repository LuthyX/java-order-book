package com.orderbook.replay;

import com.orderbook.core.OrderBook;
import com.orderbook.core.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.Set;

import static com.orderbook.core.Side.BUY;
import static com.orderbook.core.Side.SELL;
import static org.junit.jupiter.api.Assertions.*;

/** Drives the listener with decoded events directly (no bytes) and checks the books. */
class ReplayListenerTest {

    private static final int AAPL = 13, MSFT = 5294;
    private static final long T = 34_200_000_000_000L;   // 09:30

    private ReplayListener replay;

    @BeforeEach
    void setUp() {
        replay = new ReplayListener(Set.of("AAPL"));
        replay.onStockDirectory(AAPL, T, "AAPL");
        replay.onStockDirectory(MSFT, T, "MSFT");
    }

    private OrderBook aapl() {
        return replay.bookFor("AAPL").orElseThrow();
    }

    @Test
    @DisplayName("the stock directory maps tickers to books; a stock with no orders has no book yet")
    void directory() {
        assertEquals(2, replay.stockCount());
        assertTrue(replay.bookFor("AAPL").isEmpty());
        assertTrue(replay.bookFor("NOPE").isEmpty());
    }

    @Test
    @DisplayName("add order: rests in that stock's book")
    void add() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);

        assertEquals(OptionalLong.of(3_208_000), aapl().bestBid());
        assertEquals(1, replay.restingOrders());
    }

    @Test
    @DisplayName("each locate gets its own book")
    void separateBooks() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onAddOrder(MSFT, T, 2, SELL, 100, 1_743_800);

        assertEquals(OptionalLong.empty(), aapl().bestAsk());
        assertEquals(OptionalLong.of(1_743_800), replay.bookFor("MSFT").orElseThrow().bestAsk());
        assertEquals(2, replay.bookCount());
    }

    @Test
    @DisplayName("execute (E) reduces the order; executing the rest removes it")
    void execute() {
        replay.onAddOrder(AAPL, T, 1, SELL, 300, 3_208_500);
        replay.onOrderExecuted(AAPL, T, 1, 100);

        assertEquals(200, aapl().getOrder(1).orElseThrow().remainingQty());
        assertEquals(1, replay.restingOrders());

        replay.onOrderExecuted(AAPL, T, 1, 200);
        assertTrue(aapl().getOrder(1).isEmpty());
        assertEquals(0, replay.restingOrders());
    }

    @Test
    @DisplayName("execute with price (C) reduces the order the same way")
    void executeWithPrice() {
        replay.onAddOrder(AAPL, T, 1, SELL, 300, 3_208_500);
        replay.onOrderExecutedWithPrice(AAPL, T, 1, 300, 3_208_000);

        assertTrue(aapl().getOrder(1).isEmpty());
    }

    @Test
    @DisplayName("partial cancel (X) reduces; delete (D) removes the whole order")
    void cancelAndDelete() {
        replay.onAddOrder(AAPL, T, 1, BUY, 500, 3_208_000);
        replay.onOrderCancel(AAPL, T, 1, 300);
        assertEquals(200, aapl().getOrder(1).orElseThrow().remainingQty());

        replay.onOrderDelete(AAPL, T, 1);
        assertTrue(aapl().getOrder(1).isEmpty());
        assertEquals(OptionalLong.empty(), aapl().bestBid());
        assertEquals(0, replay.restingOrders());
    }

    @Test
    @DisplayName("replace (U) keeps the original's side, moves to the new ref, price and size")
    void replaceKeepsSide() {
        replay.onAddOrder(AAPL, T, 1, SELL, 100, 3_208_500);
        replay.onOrderReplace(AAPL, T, 1, 2, 250, 3_209_000);

        assertTrue(aapl().getOrder(1).isEmpty());
        var replaced = aapl().getOrder(2).orElseThrow();
        assertEquals(Side.SELL, replaced.side(), "U has no side field: it must come from order #1");
        assertEquals(3_209_000, replaced.price());
        assertEquals(250, replaced.remainingQty());
        assertEquals(1, replay.restingOrders());
    }

    @Test
    @DisplayName("replace (U) loses time priority: the new order goes to the back of its level")
    void replaceLosesPriority() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onAddOrder(AAPL, T, 2, BUY, 100, 3_208_000);
        replay.onOrderReplace(AAPL, T, 1, 3, 100, 3_208_000);   // same price, new ref

        var trades = aapl().addLimit(99, SELL, 3_208_000, 100);
        assertEquals(2, trades.get(0).buyOrderId(), "order 2 is now first in line");
    }

    @Test
    @DisplayName("events for unknown orders are counted, never thrown")
    void unknownOrders() {
        replay.onOrderExecuted(AAPL, T, 404, 100);   // no AAPL book yet
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onOrderCancel(AAPL, T, 405, 10);
        replay.onOrderDelete(AAPL, T, 406);
        replay.onOrderReplace(AAPL, T, 407, 408, 100, 3_208_000);

        assertEquals(4, replay.unknownOrderEvents());
        assertTrue(aapl().getOrder(408).isEmpty(), "a replace of an unknown order adds nothing");
        assertEquals(1, replay.restingOrders());
        assertFalse(replay.firstProblems().isEmpty());
    }

    @Test
    @DisplayName("an impossible event (executing more than remains) is rejected and counted; the book is unchanged")
    void rejected() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onOrderExecuted(AAPL, T, 1, 150);

        assertEquals(1, replay.rejectedEvents());
        assertEquals(100, aapl().getOrder(1).orElseThrow().remainingQty());
    }

    @Test
    @DisplayName("peak resting orders is the high-water mark, with its time")
    void peakResting() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onAddOrder(AAPL, T + 1, 2, BUY, 100, 3_207_000);
        replay.onAddOrder(AAPL, T + 2, 3, BUY, 100, 3_206_000);
        replay.onOrderDelete(AAPL, T + 3, 1);
        replay.onOrderDelete(AAPL, T + 4, 2);

        assertEquals(1, replay.restingOrders());
        assertEquals(3, replay.peakRestingOrders());
        assertEquals(T + 2, replay.peakRestingTimestamp());
    }

    @Test
    @DisplayName("message counts by type include types we don't apply")
    void counts() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onOrderDelete(AAPL, T, 1);
        replay.onOther('P');
        replay.onOther('P');

        assertEquals(2, (long) replay.countsByType().get('P'));
        assertEquals(2, (long) replay.countsByType().get('R'));
        assertEquals(6, replay.messageCount());
    }

    @Test
    @DisplayName("the close snapshot captures watched symbols at System Event 'M' (4 p.m.)")
    void closeSnapshot() {
        replay.onAddOrder(AAPL, T, 1, BUY, 100, 3_208_000);
        replay.onAddOrder(AAPL, T, 2, SELL, 40, 3_208_500);
        replay.onSystemEvent(57_600_000_000_000L, 'M');      // 16:00
        replay.onOrderDelete(AAPL, T, 1);                       // after-hours change: not in the snapshot

        var top = replay.closeSnapshot().get("AAPL");
        assertEquals(OptionalLong.of(3_208_000), top.bid());
        assertEquals(100, top.bidSize());
        assertEquals(OptionalLong.of(3_208_500), top.ask());
        assertEquals(40, top.askSize());
    }

    @Test
    @DisplayName("crossedBooks spots a book whose best bid is at or above its best ask")
    void crossed() {
        replay.onAddOrder(AAPL, T, 1, SELL, 100, 3_208_000);
        assertEquals(0, replay.crossedBooks());
        replay.onAddOrder(AAPL, T, 2, BUY, 100, 3_208_000);   // locked: bid == ask
        assertEquals(1, replay.crossedBooks());
    }

    @Test
    @DisplayName("time() formats nanoseconds since midnight")
    void timeFormat() {
        assertEquals("03:02:33.404", ReplayListener.time(10_953_404_452_051L));
        assertEquals("16:00:00.000", ReplayListener.time(57_600_000_000_000L));
    }
}
