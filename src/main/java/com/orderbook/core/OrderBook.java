package com.orderbook.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A limit order book for ONE stock, with price-time priority matching.
 *
 * Data structures:
 *   bids        TreeMap<price, FIFO queue>  HIGHEST price first (reverse order)
 *   asks        TreeMap<price, FIFO queue>  LOWEST price first (natural order)
 *   ordersById  HashMap<id, Order>          every RESTING order, for O(1) lookup on cancel
 *
 * Because bids are reversed, firstKey() is the BEST price on both sides.
 *
 * Matching rules:
 *   1. An incoming order matches against the OPPOSITE side, best price first.
 *   2. At the same price, the earliest resting order fills first (FIFO).
 *   3. Every trade happens at the RESTING order's price.
 *   4. A limit order stops when the next opposite price is worse than its limit;
 *      any leftover RESTS on the book at its own price.
 *   5. A market order matches at any price; any leftover is CANCELLED (never rests).
 *   6. An emptied price level is removed, so bestBid/bestAsk stay correct.
 */
public class OrderBook {

    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();
    private long nextSeq = 0;

    /**
     * Submit a limit order. Matches what it can, rests the rest.
     *
     * @return the trades this order caused, in the order they happened (empty if none)
     * @throws IllegalArgumentException if quantity <= 0, price <= 0, or the id is already in the book
     */
    public List<Trade> addLimit(long id, Side side, long price, long quantity) {
        if (ordersById.containsKey(id)) throw new IllegalArgumentException("Id already contained in order book: " + id);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity can not be less than or equal to 0");
        if (price <= 0) throw new IllegalArgumentException("Price can not be less than or equal to 0");

        // Create the order ONCE, with its true original quantity, then fill it as it trades (fixes bug 3).
        Order incoming = new Order(id, side, price, quantity, nextSeq++);
        List<Trade> trades = match(incoming, false);

        // Rule 4: whatever didn't fill rests at the order's own price.
        if (!incoming.isFilled()) {
            TreeMap<Long, ArrayDeque<Order>> sameBook = (side == Side.BUY) ? bids : asks;
            sameBook.computeIfAbsent(price, k -> new ArrayDeque<>()).addLast(incoming);
            ordersById.put(id, incoming);
        }
        return trades;
    }

    /**
     * Submit a market order. Matches at any price; whatever can't fill is cancelled.
     *
     * @return the trades this order caused (empty if the opposite side was empty)
     * @throws IllegalArgumentException if quantity <= 0 or the id is already in the book
     */
    public List<Trade> addMarket(long id, Side side, long quantity) {
        if (ordersById.containsKey(id)) throw new IllegalArgumentException("Id already contained in order book: " + id);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity can not be less than or equal to 0");

        // Price 0 is a placeholder: a market order has no limit, and match() skips the limit check.
        Order incoming = new Order(id, side, 0, quantity, nextSeq++);

        // Rule 5: no "rest the leftover" step. Whatever didn't fill is simply never stored,
        // and that IS the cancel.
        return match(incoming, true);
    }

    /**
     * Your matching loop from addLimit, pulled out so addLimit and addMarket share it.
     * Trades the incoming order against the opposite side until it's filled, the opposite
     * side runs out, or (for limit orders) the prices stop overlapping.
     */
    private List<Trade> match(Order incoming, boolean isMarket) {
        List<Trade> trades = new ArrayList<>();   // local: only THIS order's trades (fixes bug 1)
        Side side = incoming.side();
        TreeMap<Long, ArrayDeque<Order>> oppositeBook = (side == Side.BUY) ? asks : bids;

        while (!incoming.isFilled() && !oppositeBook.isEmpty()) {
            long bestPrice = oppositeBook.firstKey();   // rule 1: best opposite price

            // Rule 4: stop if the best opposite price is worse than our limit (market orders skip this).
            if (!isMarket && ((side == Side.BUY && incoming.price() < bestPrice)
                           || (side == Side.SELL && incoming.price() > bestPrice))) {
                break;
            }

            ArrayDeque<Order> ordersAtBestPrice = oppositeBook.get(bestPrice);
            while (!ordersAtBestPrice.isEmpty() && !incoming.isFilled()) {
                Order restingOrder = ordersAtBestPrice.getFirst();   // rule 2: front of the queue
                long tradeQty = Math.min(incoming.remainingQty(), restingOrder.remainingQty());

                // Rule 3: trade at the RESTING order's price.
                trades.add(new Trade(
                        side == Side.BUY ? incoming.id() : restingOrder.id(),
                        side == Side.SELL ? incoming.id() : restingOrder.id(),
                        restingOrder.price(),
                        tradeQty));

                incoming.fill(tradeQty);
                restingOrder.fill(tradeQty);
                if (restingOrder.isFilled()) {
                    ordersAtBestPrice.removeFirst();
                    ordersById.remove(restingOrder.id());
                }
                // A partly filled resting order stays at the front: it keeps its priority.
            }
            if (ordersAtBestPrice.isEmpty()) {
                oppositeBook.remove(bestPrice);   // rule 6
            }
        }
        return trades;
    }

    /**
     * Remove a resting order.
     *
     * @return true if the order was resting and is now removed; false if unknown,
     *         already filled, or already cancelled
     */
    public boolean cancel(long id) {
        // Filled, cancelled and market orders are never in ordersById, so one lookup covers all cases.
        Order order = ordersById.remove(id);
        if (order == null) {
            return false;
        }
        TreeMap<Long, ArrayDeque<Order>> sameBook = (order.side() == Side.BUY) ? bids : asks;
        ArrayDeque<Order> ordersAtPrice = sameBook.get(order.price());

        // Known slow spot, a Phase 4 target: ArrayDeque.remove(Object) scans the queue, so it's O(n).
        ordersAtPrice.remove(order);

        if (ordersAtPrice.isEmpty()) {
            sameBook.remove(order.price());   // rule 6 again
        }
        return true;
    }

    /** Highest resting buy price, or empty if there are no bids. */
    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    /** Lowest resting sell price, or empty if there are no asks. */
    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /** Total remaining quantity resting at one price on one side (0 if none). */
    public long volumeAt(Side side, long price) {
        ArrayDeque<Order> ordersAtPrice = ((side == Side.BUY) ? bids : asks).get(price);
        if (ordersAtPrice == null) {
            return 0;
        }
        long total = 0;
        for (Order order : ordersAtPrice) {
            total += order.remainingQty();
        }
        return total;
    }

    /** A resting order by id, or empty if it isn't (or is no longer) in the book. */
    public Optional<Order> getOrder(long id) {
        return Optional.ofNullable(ordersById.get(id));
    }
}
