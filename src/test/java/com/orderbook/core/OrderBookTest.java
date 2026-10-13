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
 * The specification for OrderBook. Read the test names top to bottom: together they
 * describe exactly how a price-time priority matching engine behaves.
 *
 * Work through them in order. They go from simplest to hardest.
 */
class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();   // a fresh, empty book for every test
    }

    /** Readable prices: p("10.05") instead of 100_500. */
    private static long p(String price) {
        return Prices.fromString(price);
    }

    // ------------------------------------------------------------------
    // 1. Resting: orders that don't cross just sit in the book
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty book has no best bid or ask")
    void emptyBook() {
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    @DisplayName("a buy into an empty book rests as the best bid")
    void buyRestsInEmptyBook() {
        List<Trade> trades = book.addLimit(1, BUY, p("10.00"), 100);

        assertTrue(trades.isEmpty());
        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(100, book.volumeAt(BUY, p("10.00")));
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    @DisplayName("a sell into an empty book rests as the best ask")
    void sellRestsInEmptyBook() {
        List<Trade> trades = book.addLimit(1, SELL, p("10.05"), 100);

        assertTrue(trades.isEmpty());
        assertEquals(OptionalLong.of(p("10.05")), book.bestAsk());
        assertEquals(100, book.volumeAt(SELL, p("10.05")));
    }

    @Test
    @DisplayName("prices that don't overlap don't trade: both orders rest, leaving a spread")
    void noOverlapNoTrade() {
        book.addLimit(1, SELL, p("10.05"), 100);
        List<Trade> trades = book.addLimit(2, BUY, p("10.00"), 200);

        assertTrue(trades.isEmpty());
        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(OptionalLong.of(p("10.05")), book.bestAsk());
    }

    @Test
    @DisplayName("best bid is the HIGHEST buy price; best ask is the LOWEST sell price")
    void bestPricesAcrossLevels() {
        book.addLimit(1, BUY, p("9.98"), 100);
        book.addLimit(2, BUY, p("10.00"), 100);
        book.addLimit(3, BUY, p("9.99"), 100);
        book.addLimit(4, SELL, p("10.07"), 100);
        book.addLimit(5, SELL, p("10.05"), 100);
        book.addLimit(6, SELL, p("10.06"), 100);

        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(OptionalLong.of(p("10.05")), book.bestAsk());
    }

    @Test
    @DisplayName("orders at the same price add up in that level's volume")
    void volumeAddsUpAtSamePrice() {
        book.addLimit(1, BUY, p("10.00"), 100);
        book.addLimit(2, BUY, p("10.00"), 250);

        assertEquals(350, book.volumeAt(BUY, p("10.00")));
        assertEquals(0, book.volumeAt(BUY, p("9.99")));
        assertEquals(0, book.volumeAt(SELL, p("10.00")));
    }

    // ------------------------------------------------------------------
    // 2. Simple matches: one incoming order meets one resting order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("equal size at the same price: full fill, book is empty afterwards")
    void exactMatch() {
        book.addLimit(1, SELL, p("10.00"), 100);
        List<Trade> trades = book.addLimit(2, BUY, p("10.00"), 100);

        assertEquals(List.of(new Trade(2, 1, p("10.00"), 100)), trades);
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    @DisplayName("price improvement: an incoming buy trades at the resting ask's price, not its own limit")
    void buyerGetsRestingPrice() {
        book.addLimit(1, SELL, p("10.00"), 100);
        List<Trade> trades = book.addLimit(2, BUY, p("10.05"), 100);

        assertEquals(List.of(new Trade(2, 1, p("10.00"), 100)), trades);
    }

    @Test
    @DisplayName("price improvement works the other way: an incoming sell trades at the resting bid's price")
    void sellerGetsRestingPrice() {
        book.addLimit(1, BUY, p("10.05"), 100);
        List<Trade> trades = book.addLimit(2, SELL, p("10.00"), 100);

        // buyOrderId is always the buy side, whichever order arrived first
        assertEquals(List.of(new Trade(1, 2, p("10.05"), 100)), trades);
    }

    @Test
    @DisplayName("incoming order bigger than resting: the leftover rests at the incoming order's price")
    void incomingPartiallyFilledRestsLeftover() {
        book.addLimit(1, SELL, p("10.00"), 100);
        List<Trade> trades = book.addLimit(2, BUY, p("10.00"), 150);

        assertEquals(List.of(new Trade(2, 1, p("10.00"), 100)), trades);
        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
        assertEquals(50, book.volumeAt(BUY, p("10.00")));
        assertEquals(50, book.getOrder(2).orElseThrow().remainingQty());
    }

    @Test
    @DisplayName("incoming order smaller than resting: the resting order stays with reduced quantity")
    void restingPartiallyFilled() {
        book.addLimit(1, SELL, p("10.00"), 300);
        List<Trade> trades = book.addLimit(2, BUY, p("10.00"), 100);

        assertEquals(List.of(new Trade(2, 1, p("10.00"), 100)), trades);
        assertEquals(200, book.getOrder(1).orElseThrow().remainingQty());
        assertEquals(200, book.volumeAt(SELL, p("10.00")));
        assertTrue(book.getOrder(2).isEmpty(), "fully filled incoming order must not rest");
    }

    @Test
    @DisplayName("a fully filled resting order is removed from the book")
    void filledRestingOrderIsGone() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, BUY, p("10.00"), 100);

        assertTrue(book.getOrder(1).isEmpty());
        assertFalse(book.cancel(1), "can't cancel an order that already filled");
    }

    // ------------------------------------------------------------------
    // 3. Priority: price first, then time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("price priority: the better price fills first even if it arrived later")
    void betterPriceBeatsEarlierArrival() {
        book.addLimit(1, SELL, p("10.02"), 100);   // arrived first, worse price
        book.addLimit(2, SELL, p("10.00"), 100);   // arrived second, better price
        List<Trade> trades = book.addLimit(3, BUY, p("10.05"), 100);

        assertEquals(List.of(new Trade(3, 2, p("10.00"), 100)), trades);
        assertEquals(100, book.getOrder(1).orElseThrow().remainingQty());
    }

    @Test
    @DisplayName("time priority: at the same price, the earliest order fills first (FIFO)")
    void fifoAtSamePrice() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, SELL, p("10.00"), 100);
        book.addLimit(3, SELL, p("10.00"), 100);
        List<Trade> trades = book.addLimit(4, BUY, p("10.00"), 150);

        assertEquals(List.of(
                new Trade(4, 1, p("10.00"), 100),
                new Trade(4, 2, p("10.00"), 50)
        ), trades);
        assertTrue(book.getOrder(1).isEmpty());
        assertEquals(50, book.getOrder(2).orElseThrow().remainingQty());
        assertEquals(100, book.getOrder(3).orElseThrow().remainingQty());
    }

    @Test
    @DisplayName("a partially filled resting order keeps its place at the front of the queue")
    void partialFillKeepsPriority() {
        book.addLimit(1, SELL, p("10.00"), 300);
        book.addLimit(2, SELL, p("10.00"), 100);
        book.addLimit(3, BUY, p("10.00"), 100);      // takes 100 from order 1
        List<Trade> trades = book.addLimit(4, BUY, p("10.00"), 100);

        assertEquals(List.of(new Trade(4, 1, p("10.00"), 100)), trades, "order 1 is still first in line");
    }

    // ------------------------------------------------------------------
    // 4. Sweeping: one order trades through several price levels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sweep: buy 250 @ 10.05 takes two levels, then rests 50 as a bid at 10.05")
    void sweepThenRest() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, SELL, p("10.03"), 100);
        List<Trade> trades = book.addLimit(3, BUY, p("10.05"), 250);

        assertEquals(List.of(
                new Trade(3, 1, p("10.00"), 100),
                new Trade(3, 2, p("10.03"), 100)
        ), trades);
        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertEquals(OptionalLong.of(p("10.05")), book.bestBid());
        assertEquals(50, book.volumeAt(BUY, p("10.05")));
    }

    @Test
    @DisplayName("a limit order stops at its limit: it won't trade with asks above its price")
    void sweepStopsAtLimit() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, SELL, p("10.10"), 100);
        List<Trade> trades = book.addLimit(3, BUY, p("10.05"), 150);

        assertEquals(List.of(new Trade(3, 1, p("10.00"), 100)), trades);
        assertEquals(OptionalLong.of(p("10.05")), book.bestBid());
        assertEquals(50, book.volumeAt(BUY, p("10.05")));
        assertEquals(OptionalLong.of(p("10.10")), book.bestAsk(), "the 10.10 ask is untouched");
        assertEquals(100, book.volumeAt(SELL, p("10.10")));
    }

    @Test
    @DisplayName("an emptied price level disappears: the next level becomes the best price")
    void emptyLevelIsRemoved() {
        book.addLimit(1, BUY, p("10.00"), 100);
        book.addLimit(2, BUY, p("9.99"), 100);
        book.addLimit(3, SELL, p("10.00"), 100);

        assertEquals(OptionalLong.of(p("9.99")), book.bestBid());
        assertEquals(0, book.volumeAt(BUY, p("10.00")));
    }

    @Test
    @DisplayName("a sell sweep walks bids from highest to lowest")
    void sellSweepsBidsDownward() {
        book.addLimit(1, BUY, p("10.00"), 100);
        book.addLimit(2, BUY, p("9.98"), 100);
        book.addLimit(3, BUY, p("9.99"), 100);
        List<Trade> trades = book.addLimit(4, SELL, p("9.98"), 300);

        assertEquals(List.of(
                new Trade(1, 4, p("10.00"), 100),
                new Trade(3, 4, p("9.99"), 100),
                new Trade(2, 4, p("9.98"), 100)
        ), trades);
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    // ------------------------------------------------------------------
    // 5. Market orders: match at any price, never rest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("market buy sweeps asks at whatever prices are available")
    void marketBuySweeps() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, SELL, p("10.50"), 100);
        List<Trade> trades = book.addMarket(3, BUY, 150);

        assertEquals(List.of(
                new Trade(3, 1, p("10.00"), 100),
                new Trade(3, 2, p("10.50"), 50)
        ), trades);
        assertEquals(50, book.volumeAt(SELL, p("10.50")));
    }

    @Test
    @DisplayName("market order leftover is cancelled, never rests on the book")
    void marketLeftoverCancelled() {
        book.addLimit(1, SELL, p("10.00"), 100);
        List<Trade> trades = book.addMarket(2, BUY, 250);

        assertEquals(List.of(new Trade(2, 1, p("10.00"), 100)), trades);
        assertEquals(OptionalLong.empty(), book.bestBid(), "the unfilled 150 must not rest");
        assertTrue(book.getOrder(2).isEmpty());
    }

    @Test
    @DisplayName("market order into an empty opposite side: no trades, nothing rests")
    void marketIntoEmptyBook() {
        book.addLimit(1, BUY, p("10.00"), 100);         // same side, irrelevant
        List<Trade> trades = book.addMarket(2, BUY, 100);

        assertTrue(trades.isEmpty());
        assertEquals(100, book.volumeAt(BUY, p("10.00")));
        assertTrue(book.getOrder(2).isEmpty());
    }

    @Test
    @DisplayName("market sell hits bids, highest first")
    void marketSell() {
        book.addLimit(1, BUY, p("9.99"), 100);
        book.addLimit(2, BUY, p("10.00"), 100);
        List<Trade> trades = book.addMarket(3, SELL, 100);

        assertEquals(List.of(new Trade(2, 3, p("10.00"), 100)), trades);
    }

    // ------------------------------------------------------------------
    // 6. Cancels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancel removes a resting order and its volume")
    void cancelRemoves() {
        book.addLimit(1, BUY, p("10.00"), 100);

        assertTrue(book.cancel(1));
        assertTrue(book.getOrder(1).isEmpty());
        assertEquals(0, book.volumeAt(BUY, p("10.00")));
        assertEquals(OptionalLong.empty(), book.bestBid());
    }

    @Test
    @DisplayName("cancel one order at a level leaves the others there, in order")
    void cancelMiddleOfQueue() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.addLimit(2, SELL, p("10.00"), 100);
        book.addLimit(3, SELL, p("10.00"), 100);
        book.cancel(2);

        assertEquals(200, book.volumeAt(SELL, p("10.00")));
        List<Trade> trades = book.addLimit(4, BUY, p("10.00"), 200);
        assertEquals(List.of(
                new Trade(4, 1, p("10.00"), 100),
                new Trade(4, 3, p("10.00"), 100)
        ), trades);
    }

    @Test
    @DisplayName("a cancelled order never trades")
    void cancelledOrderDoesNotTrade() {
        book.addLimit(1, SELL, p("10.00"), 100);
        book.cancel(1);
        List<Trade> trades = book.addLimit(2, BUY, p("10.00"), 100);

        assertTrue(trades.isEmpty());
        assertEquals(OptionalLong.of(p("10.00")), book.bestBid());
    }

    @Test
    @DisplayName("cancelling an unknown order, or the same order twice, returns false")
    void cancelUnknownOrTwice() {
        assertFalse(book.cancel(42));
        book.addLimit(1, BUY, p("10.00"), 100);
        assertTrue(book.cancel(1));
        assertFalse(book.cancel(1));
    }

    @Test
    @DisplayName("cancel works on a partially filled order")
    void cancelPartiallyFilled() {
        book.addLimit(1, SELL, p("10.00"), 300);
        book.addLimit(2, BUY, p("10.00"), 100);

        assertTrue(book.cancel(1));
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    // ------------------------------------------------------------------
    // 7. Bad input is rejected, loudly
    // ------------------------------------------------------------------

    @Test
    @DisplayName("zero or negative quantity is rejected")
    void rejectsBadQuantity() {
        assertThrows(IllegalArgumentException.class, () -> book.addLimit(1, BUY, p("10.00"), 0));
        assertThrows(IllegalArgumentException.class, () -> book.addLimit(2, BUY, p("10.00"), -5));
        assertThrows(IllegalArgumentException.class, () -> book.addMarket(3, BUY, 0));
    }

    @Test
    @DisplayName("a limit price of zero or below is rejected")
    void rejectsBadPrice() {
        assertThrows(IllegalArgumentException.class, () -> book.addLimit(1, BUY, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> book.addLimit(2, SELL, -100, 100));
    }

    @Test
    @DisplayName("an id that is already resting in the book is rejected")
    void rejectsDuplicateId() {
        book.addLimit(1, BUY, p("10.00"), 100);
        assertThrows(IllegalArgumentException.class, () -> book.addLimit(1, SELL, p("11.00"), 100));
        assertEquals(OptionalLong.empty(), book.bestAsk(), "a rejected order must not change the book");
    }
}
