package com.orderbook.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static com.orderbook.core.Side.BUY;
import static com.orderbook.core.Side.SELL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Feed mode: applying NASDAQ's already-matched results to the book (Phase 2).
 * No matching happens here. The book just records what the exchange tells it.
 */
class OrderBookFeedTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    private static long p(String price) {
        return Prices.fromString(price);
    }

    // ------------------------------------------------------------------
    // addResting: ITCH "Add Order" (A / F)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addResting puts the order in the book")
    void addRestingRests() {
        book.addResting(1, BUY, p("10.00"), 100);

        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(100, book.volumeAt(BUY, p("10.00")));
        assertEquals(100, book.getOrder(1).orElseThrow().remainingQty());
    }

    @Test
    @DisplayName("addResting never matches, even when prices cross: the feed is the truth")
    void addRestingNeverMatches() {
        book.addResting(1, SELL, p("10.00"), 100);
        book.addResting(2, BUY, p("10.05"), 100);   // would trade in addLimit; must NOT here

        assertEquals(OptionalLong.of(p("10.05")), book.bestBid());
        assertEquals(OptionalLong.of(p("10.00")), book.bestAsk());
        assertTrue(book.getOrder(1).isPresent());
        assertTrue(book.getOrder(2).isPresent());
    }

    @Test
    @DisplayName("addResting joins the BACK of its price level's queue")
    void addRestingKeepsFifo() {
        book.addResting(1, SELL, p("10.00"), 100);
        book.addResting(2, SELL, p("10.00"), 100);
        List<Trade> trades = book.addLimit(3, BUY, p("10.00"), 150);

        assertEquals(List.of(
                new Trade(3, 1, p("10.00"), 100),
                new Trade(3, 2, p("10.00"), 50)
        ), trades);
    }

    @Test
    @DisplayName("addResting rejects bad quantity, bad price and duplicate ids, leaving the book unchanged")
    void addRestingValidates() {
        book.addResting(1, BUY, p("10.00"), 100);

        assertThrows(IllegalArgumentException.class, () -> book.addResting(1, SELL, p("11.00"), 100));
        assertThrows(IllegalArgumentException.class, () -> book.addResting(2, SELL, p("11.00"), 0));
        assertThrows(IllegalArgumentException.class, () -> book.addResting(3, SELL, 0, 100));
        assertEquals(OptionalLong.empty(), book.bestAsk(), "rejected orders must not change the book");
    }

    // ------------------------------------------------------------------
    // reduce: ITCH "Executed" (E / C) and "Cancel" (X)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reduce lowers an order's remaining quantity and its level's volume")
    void reducePartially() {
        book.addResting(1, SELL, p("10.00"), 300);

        assertTrue(book.reduce(1, 100));
        assertEquals(200, book.getOrder(1).orElseThrow().remainingQty());
        assertEquals(200, book.volumeAt(SELL, p("10.00")));
    }

    @Test
    @DisplayName("a partly reduced order keeps its place at the front of the queue")
    void reduceKeepsPriority() {
        book.addResting(1, SELL, p("10.00"), 300);
        book.addResting(2, SELL, p("10.00"), 100);
        book.reduce(1, 100);
        List<Trade> trades = book.addLimit(3, BUY, p("10.00"), 100);

        assertEquals(List.of(new Trade(3, 1, p("10.00"), 100)), trades);
    }

    @Test
    @DisplayName("reducing an order to zero removes it, and its level if that was the last order")
    void reduceToZeroRemoves() {
        book.addResting(1, SELL, p("10.00"), 100);
        book.addResting(2, SELL, p("10.05"), 100);

        assertTrue(book.reduce(1, 100));
        assertTrue(book.getOrder(1).isEmpty());
        assertEquals(0, book.volumeAt(SELL, p("10.00")));
        assertEquals(OptionalLong.of(p("10.05")), book.bestAsk(), "the empty 10.00 level must be gone");
        assertFalse(book.cancel(1), "a fully reduced order is no longer in the book");
    }

    @Test
    @DisplayName("reducing an order in the middle of a queue to zero leaves the others in order")
    void reduceMiddleOfQueue() {
        book.addResting(1, BUY, p("10.00"), 100);
        book.addResting(2, BUY, p("10.00"), 100);
        book.addResting(3, BUY, p("10.00"), 100);
        book.reduce(2, 100);

        assertEquals(200, book.volumeAt(BUY, p("10.00")));
        List<Trade> trades = book.addLimit(4, SELL, p("10.00"), 200);
        assertEquals(List.of(
                new Trade(1, 4, p("10.00"), 100),
                new Trade(3, 4, p("10.00"), 100)
        ), trades);
    }

    @Test
    @DisplayName("reduce on an unknown or already-removed order returns false")
    void reduceUnknown() {
        assertFalse(book.reduce(42, 100));
        book.addResting(1, BUY, p("10.00"), 100);
        book.reduce(1, 100);
        assertFalse(book.reduce(1, 1));
    }

    @Test
    @DisplayName("reducing by more than the remaining quantity is an error and changes nothing")
    void reduceTooMuch() {
        book.addResting(1, BUY, p("10.00"), 100);

        assertThrows(IllegalArgumentException.class, () -> book.reduce(1, 101));
        assertEquals(100, book.getOrder(1).orElseThrow().remainingQty());
        assertEquals(100, book.volumeAt(BUY, p("10.00")));
    }

    @Test
    @DisplayName("reducing by zero or a negative amount is an error")
    void reduceNonPositive() {
        book.addResting(1, BUY, p("10.00"), 100);

        assertThrows(IllegalArgumentException.class, () -> book.reduce(1, 0));
        assertThrows(IllegalArgumentException.class, () -> book.reduce(1, -5));
    }

    // ------------------------------------------------------------------
    // Putting it together: what a real ITCH sequence looks like
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ITCH Replace (U) = cancel the old order + addResting the new one, which loses priority")
    void replaceLosesPriority() {
        book.addResting(1, SELL, p("10.00"), 100);
        book.addResting(2, SELL, p("10.00"), 100);

        // U: order 1 replaced by order 10, same price, new size
        assertTrue(book.cancel(1));
        book.addResting(10, SELL, p("10.00"), 200);

        List<Trade> trades = book.addLimit(3, BUY, p("10.00"), 100);
        assertEquals(List.of(new Trade(3, 2, p("10.00"), 100)), trades, "order 2 is now first in line");
    }

    @Test
    @DisplayName("the example from our discussion: A #7, E #7, A #8 leaves only a 50-share bid")
    void discussionExample() {
        book.addResting(7, SELL, p("10.00"), 100);   // A  order #7 added
        book.reduce(7, 100);                          // E  order #7 executed 100
        book.addResting(8, BUY, p("10.00"), 50);      // A  order #8 added (the aggressor's leftover)

        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(50, book.volumeAt(BUY, p("10.00")));
        assertTrue(book.getOrder(7).isEmpty());
    }
}
