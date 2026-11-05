package com.orderbook.core;

/**
 * All resting orders at ONE price on ONE side, in arrival order (FIFO).
 *
 * The queue is an INTRUSIVE doubly linked list: the orders themselves are the nodes,
 * through their prev / next fields. This level only holds the two ends.
 *
 *     head ─► #7 ⇄ #9 ⇄ #12 ◄─ tail        (each order also points back here via order.level)
 *
 * Why not ArrayDeque? Removing an order from the middle of an ArrayDeque means scanning
 * from the front to find it (O(n)), and deletes are ~43% of ITCH messages. Here, once
 * OrderBook has the Order (an O(1) lookup by id), unlinking it is two pointer updates: O(1).
 *
 * Why not java.util.LinkedList? It hides its nodes, so remove(order) still scans, and it
 * allocates an extra Node object per element. Putting the links inside Order avoids both.
 *
 * totalQty is kept up to date on every change, so the volume at a price is O(1).
 * To keep it correct, EVERY change to a resting order's quantity goes through this class
 * (append, reduce, remove). That's why Order.fill() is package-private.
 */
final class PriceLevel {

    final long price;
    Order head;          // earliest order: matched first
    Order tail;          // latest order: new orders join here
    long totalQty;       // sum of remainingQty over every order in this level
    int orderCount;

    PriceLevel(long price) {
        this.price = price;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** Join the BACK of the queue (time priority: last to arrive, last to fill). O(1). */
    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail != null) {
            tail.next = order;   // old last order now points forward to the new one
        } else {
            head = order;        // the level was empty: the new order is also the first
        }
        tail = order;
        totalQty += order.remainingQty();
        orderCount++;
    }

    /**
     * Unlink an order from wherever it sits in the queue: front, middle or back. O(1).
     * Its neighbours are joined to each other; if it was at an end, head or tail moves.
     */
    void remove(Order order) {
        if (order.prev != null) {
            order.prev.next = order.next;   // the order before now skips over it
        } else {
            head = order.next;              // it was the first order
        }
        if (order.next != null) {
            order.next.prev = order.prev;   // the order after now points back past it
        } else {
            tail = order.prev;              // it was the last order
        }
        totalQty -= order.remainingQty();   // 0 if it was fully filled (reduce already subtracted)
        orderCount--;
        order.prev = null ;                  // clear its links so a removed order can't
        order.next = null;                  // accidentally keep pointing into the queue
        order.level = null;
    }

    /** Reduce a resting order (a fill, an execution or a partial cancel). It keeps its place in line. */
    void reduce(Order order, long qty) {
        order.fill(qty);        // validates qty and subtracts from the order
        totalQty -= qty;        // and from the level, so the two can never disagree
    }
}
