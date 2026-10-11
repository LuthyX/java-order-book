package com.orderbook.core;

/** Which side of the book an order sits on. BUY orders are bids, SELL orders are asks. */
public enum Side {
    BUY,
    SELL;

    /** The side this order trades against: a BUY matches resting SELLs and vice versa. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
