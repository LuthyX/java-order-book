package com.orderbook.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Throws a long random mix of operations at the book (adds, matches, reduces, cancels,
 * market orders) and checks every internal invariant along the way: linked lists intact,
 * totals right, no empty levels, ids indexed.
 *
 * Hand-written tests check the cases we thought of. This finds the ones we didn't, which
 * matters for pointer code. The seed is fixed, so any failure can be replayed exactly.
 */
class OrderBookRandomTest {

    @Test
    @DisplayName("200,000 random operations never break the book's invariants")
    void randomOperationsKeepInvariants() {
        Random random = new Random(42);
        OrderBook book = new OrderBook();
        List<Long> liveIds = new ArrayList<>();   // ids we believe might be resting
        long nextId = 1;

        for (int step = 0; step < 200_000; step++) {
            int op = random.nextInt(100);
            // A narrow price band (9.90..10.10) keeps queues long and crossing frequent.
            long price = 99_000 + 100L * random.nextInt(21);
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + random.nextInt(500);

            if (op < 35) {
                book.addResting(nextId, side, price, qty);
                liveIds.add(nextId++);
            } else if (op < 50) {
                book.addLimit(nextId, side, price, qty);
                liveIds.add(nextId++);
            } else if (op < 55) {
                book.addMarket(nextId++, side, qty);
            } else if (op < 75 && !liveIds.isEmpty()) {
                long id = liveIds.get(random.nextInt(liveIds.size()));
                book.getOrder(id).ifPresent(o -> {
                    long r = 1 + random.nextInt((int) Math.min(o.remainingQty(), 1_000));
                    assertTrue(book.reduce(id, r));
                });
            } else if (!liveIds.isEmpty()) {
                int index = random.nextInt(liveIds.size());
                book.cancel(liveIds.get(index));
                liveIds.set(index, liveIds.get(liveIds.size() - 1));   // O(1) remove from the list
                liveIds.remove(liveIds.size() - 1);
            }

            if (step % 1_000 == 0) {
                book.checkInvariants();
            }
        }
        book.checkInvariants();
        assertTrue(book.orderCount() > 0, "the run should leave some orders resting");
    }

    @Test
    @DisplayName("volumeAt always equals the sum of the orders at that price")
    void volumeMatchesOrders() {
        Random random = new Random(7);
        OrderBook book = new OrderBook();
        for (long id = 1; id <= 5_000; id++) {
            long price = 99_000 + 100L * random.nextInt(5);
            book.addLimit(id, random.nextBoolean() ? Side.BUY : Side.SELL, price, 1 + random.nextInt(300));
            if (id % 3 == 0) book.cancel(id - 1);
        }
        book.checkInvariants();   // includes: each level's totalQty == sum of its orders
    }
}
