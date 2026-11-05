package com.orderbook.core;

/**
 * One order resting in (or passing through) the book.
 *
 * An order's identity, side, price and arrival never change: a price change is modelled as
 * cancel + new order (it loses its place in line). What does change:
 *   remainingQty      goes down with each fill
 *   prev, next, level its place in its price level's queue (see PriceLevel)
 * Those are package-private and only changed by PriceLevel and OrderBook, so code outside
 * this package can read an order but can't move it or change its quantity.
 */
public final class Order {
    private final long id;          // used for cancels; ITCH calls this the order reference number
    private final Side side;
    private final long price;       // scaled units (see Prices); 0 for market orders
    private final long originalQty;
    private final long seq;         // arrival sequence: lower = earlier = higher time priority
    private long remainingQty;      // goes down with each fill

    // Intrusive linked-list links, managed by PriceLevel. null while the order isn't resting.
    Order prev;                     // the order just ahead of this one at the same price
    Order next;                     // the order just behind it
    PriceLevel level;               // the level it's resting in, so removal needs no TreeMap lookup

    public Order(long id, Side side, long price, long quantity, long seq) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive: " + quantity);
        if (price < 0) throw new IllegalArgumentException("price must not be negative: " + price);
        this.id = id;
        this.side = side;
        this.price = price;
        this.originalQty = quantity;
        this.remainingQty = quantity;
        this.seq = seq;
    }

    /**
     * Reduce the remaining quantity after (part of) this order trades.
     * Package-private: for a RESTING order, call PriceLevel.reduce() instead, so the level's
     * totalQty is updated too. Calling this directly on a resting order would make them disagree.
     */
    void fill(long qty) {
        if (qty <= 0 || qty > remainingQty) {
            throw new IllegalArgumentException("cannot fill " + qty + " of remaining " + remainingQty);
        }
        remainingQty -= qty;
    }

    public boolean isFilled() { return remainingQty == 0; }

    public long id() { return id; }
    public Side side() { return side; }
    public long price() { return price; }
    public long originalQty() { return originalQty; }
    public long remainingQty() { return remainingQty; }
    public long seq() { return seq; }

    @Override
    public String toString() {
        return "Order{id=" + id + ", " + side + " " + remainingQty + "/" + originalQty
                + " @ " + Prices.toString(price) + ", seq=" + seq + "}";
    }
}
