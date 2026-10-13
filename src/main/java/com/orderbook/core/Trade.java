package com.orderbook.core;

/**
 * One execution between a buy order and a sell order.
 * The price is always the RESTING (maker) order's price.
 *
 * A record gives us a constructor, accessors, equals/hashCode and toString for free,
 * so tests can compare trades with assertEquals.
 */
public record Trade(long buyOrderId, long sellOrderId, long price, long quantity) {
    @Override
    public String toString() {
        return "Trade{buy=" + buyOrderId + ", sell=" + sellOrderId + ", "
                + quantity + " @ " + Prices.toString(price) + "}";
    }
}
