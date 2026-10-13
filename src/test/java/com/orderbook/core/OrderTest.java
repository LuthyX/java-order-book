package com.orderbook.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void newOrderIsUnfilled() {
        Order o = new Order(1, Side.BUY, 100_000, 300, 0);
        assertEquals(300, o.remainingQty());
        assertEquals(300, o.originalQty());
        assertFalse(o.isFilled());
    }

    @Test
    void partialThenFullFill() {
        Order o = new Order(1, Side.BUY, 100_000, 300, 0);
        o.fill(100);
        assertEquals(200, o.remainingQty());
        assertEquals(300, o.originalQty(), "original quantity never changes");
        o.fill(200);
        assertTrue(o.isFilled());
    }

    @Test
    void cannotOverfill() {
        Order o = new Order(1, Side.BUY, 100_000, 100, 0);
        assertThrows(IllegalArgumentException.class, () -> o.fill(101));
        assertThrows(IllegalArgumentException.class, () -> o.fill(0));
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 100_000, 0, 0));
    }
}
