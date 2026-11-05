package com.orderbook.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The intrusive linked list on its own: append, unlink from every position, totals. */
class PriceLevelTest {

    private static long seq = 0;

    private static Order order(long id, long qty) {
        return new Order(id, Side.BUY, 100_000, qty, seq++);
    }

    /** Walk head -> tail and return the ids, checking every back-link on the way. */
    private static List<Long> ids(PriceLevel level) {
        List<Long> ids = new ArrayList<>();
        Order previous = null;
        for (Order o = level.head; o != null; o = o.next) {
            assertSame(previous, o.prev, "back-link of order " + o.id());
            assertSame(level, o.level);
            ids.add(o.id());
            previous = o;
        }
        assertSame(previous, level.tail, "tail must be the last order");
        assertEquals(ids.size(), level.orderCount);
        return ids;
    }

    @Test
    @DisplayName("a new level is empty")
    void empty() {
        PriceLevel level = new PriceLevel(100_000);
        assertTrue(level.isEmpty());
        assertNull(level.head);
        assertNull(level.tail);
        assertEquals(0, level.totalQty);
    }

    @Test
    @DisplayName("append keeps arrival order and adds to the total")
    void appendKeepsOrder() {
        PriceLevel level = new PriceLevel(100_000);
        level.append(order(1, 100));
        level.append(order(2, 200));
        level.append(order(3, 300));

        assertEquals(List.of(1L, 2L, 3L), ids(level));
        assertEquals(600, level.totalQty);
    }

    @Test
    @DisplayName("removing the head moves head to the next order")
    void removeHead() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 100), b = order(2, 200), c = order(3, 300);
        level.append(a); level.append(b); level.append(c);

        level.remove(a);
        assertEquals(List.of(2L, 3L), ids(level));
        assertEquals(500, level.totalQty);
        assertNull(a.prev); assertNull(a.next); assertNull(a.level);
    }

    @Test
    @DisplayName("removing from the middle joins its neighbours")
    void removeMiddle() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 100), b = order(2, 200), c = order(3, 300);
        level.append(a); level.append(b); level.append(c);

        level.remove(b);
        assertEquals(List.of(1L, 3L), ids(level));
        assertSame(c, a.next);
        assertSame(a, c.prev);
        assertEquals(400, level.totalQty);
    }

    @Test
    @DisplayName("removing the tail moves tail back")
    void removeTail() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 100), b = order(2, 200), c = order(3, 300);
        level.append(a); level.append(b); level.append(c);

        level.remove(c);
        assertEquals(List.of(1L, 2L), ids(level));
        assertSame(b, level.tail);
    }

    @Test
    @DisplayName("removing the only order empties the level; appending after works")
    void removeOnly() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 100);
        level.append(a);
        level.remove(a);

        assertTrue(level.isEmpty());
        assertNull(level.tail);
        assertEquals(0, level.totalQty);

        level.append(order(2, 50));
        assertEquals(List.of(2L), ids(level));
        assertEquals(50, level.totalQty);
    }

    @Test
    @DisplayName("reduce lowers the order and the level total together, keeping its place")
    void reduce() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 300), b = order(2, 100);
        level.append(a); level.append(b);

        level.reduce(a, 120);
        assertEquals(180, a.remainingQty());
        assertEquals(280, level.totalQty);
        assertEquals(List.of(1L, 2L), ids(level));
    }

    @Test
    @DisplayName("an order reduced to zero then removed leaves the total consistent")
    void reduceToZeroThenRemove() {
        PriceLevel level = new PriceLevel(100_000);
        Order a = order(1, 300), b = order(2, 100);
        level.append(a); level.append(b);

        level.reduce(a, 300);
        level.remove(a);
        assertEquals(100, level.totalQty);
        assertEquals(List.of(2L), ids(level));
    }
}
