package com.orderbook.core;

/**
 * One order resting in (or passing through) the book.
 *
 * Everything is final except remainingQty: an order's identity, side, price and arrival
 * never change. A price change is modelled as cancel + new order (it loses its place in line).
 */
public final class Order {
    private final long id;          // used for cancels; ITCH calls this the order reference number
    private final Side side;
    private final long price;       // scaled units (see Prices); 0 for market orders
    private final long originalQty;
    private final long seq;         // arrival sequence: lower = earlier = higher time priority
    private long remainingQty;      // the only mutable field: goes down with each fill

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

    /** Reduce the remaining quantity after (part of) this order trades. */
    public void fill(long qty) {
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
