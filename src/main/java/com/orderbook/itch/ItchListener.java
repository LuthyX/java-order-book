package com.orderbook.itch;

import com.orderbook.core.Side;

/**
 * Receives decoded ITCH 5.0 messages from ItchParser.
 *
 * The parser knows about BYTES; the listener knows what to DO with each message
 * (update a book, count messages, print them...). Keeping them separate means the
 * parser can be tested without a book, and the book never sees a byte.
 *
 * Every method has an empty default, so a listener only overrides what it cares about.
 *
 * Common fields:
 *   stockLocate  small number identifying the stock for this day (see onStockDirectory)
 *   timestamp    nanoseconds since midnight, New York time
 *   prices       scaled long, 4 implied decimals (same convention as Prices)
 */
public interface ItchListener {

    /** S: day-level events. Codes: O start of messages, S start of system hours,
     *  Q start of market hours, M end of market hours, E end of system hours, C end of messages. */
    default void onSystemEvent(long timestamp, char eventCode) {}

    /** R: maps a stock locate code to its ticker symbol (spaces trimmed) for the day. */
    default void onStockDirectory(int stockLocate, long timestamp, String symbol) {}

    /** A and F: a new order is now resting on the book. (F's extra attribution field is ignored.) */
    default void onAddOrder(int stockLocate, long timestamp, long orderRef, Side side, long shares, long price) {}

    /** E: a resting order traded shares at its own price. */
    default void onOrderExecuted(int stockLocate, long timestamp, long orderRef, long executedShares) {}

    /** C: a resting order traded shares at a different price (e.g. in an auction). */
    default void onOrderExecutedWithPrice(int stockLocate, long timestamp, long orderRef,
                                          long executedShares, long executionPrice) {}

    /** X: PART of a resting order was cancelled; the rest stays. */
    default void onOrderCancel(int stockLocate, long timestamp, long orderRef, long cancelledShares) {}

    /** D: the whole order is gone. */
    default void onOrderDelete(int stockLocate, long timestamp, long orderRef) {}

    /** U: the original order is removed; a new order (new reference) takes its place at the back of the queue. */
    default void onOrderReplace(int stockLocate, long timestamp, long originalOrderRef, long newOrderRef,
                                long shares, long price) {}

    /** Any message type we don't decode (trades, auctions, halts...). */
    default void onOther(char messageType) {}
}
