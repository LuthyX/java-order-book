package com.orderbook.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LongOrderMapTest {

    private static Order order(long id) {
        return new Order(id, Side.BUY, 100_000, 100, id);
    }

    @Test
    @DisplayName("put, get and remove a few keys")
    void basics() {
        LongOrderMap map = new LongOrderMap();
        Order a = order(1), b = order(2);

        assertNull(map.put(1, a));
        assertNull(map.put(2, b));
        assertSame(a, map.get(1));
        assertSame(b, map.get(2));
        assertNull(map.get(3));
        assertEquals(2, map.size());

        assertSame(a, map.remove(1));
        assertNull(map.get(1));
        assertNull(map.remove(1), "removing twice returns null");
        assertEquals(1, map.size());
    }

    @Test
    @DisplayName("putIfAbsent inserts once and never overwrites")
    void putIfAbsent() {
        LongOrderMap map = new LongOrderMap();
        Order first = order(7), second = order(7);

        assertNull(map.putIfAbsent(7, first));
        assertSame(first, map.putIfAbsent(7, second), "returns the existing value");
        assertSame(first, map.get(7), "and leaves it in place");
        assertEquals(1, map.size());
    }

    @Test
    @DisplayName("put replaces an existing value without changing the size")
    void putReplaces() {
        LongOrderMap map = new LongOrderMap();
        Order first = order(7), second = order(7);
        map.put(7, first);

        assertSame(first, map.put(7, second));
        assertSame(second, map.get(7));
        assertEquals(1, map.size());
    }

    @Test
    @DisplayName("unusual keys work: 0, negatives, and the extremes of long")
    void unusualKeys() {
        LongOrderMap map = new LongOrderMap();
        long[] keys = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40};
        for (long k : keys) map.put(k, order(k));
        for (long k : keys) assertEquals(k, map.get(k).id(), "key " + k);
        assertTrue(map.containsKey(0), "0 is a real key, not 'empty'");
        assertEquals(keys.length, map.size());
    }

    @Test
    @DisplayName("grows past its initial capacity and keeps every entry")
    void grows() {
        LongOrderMap map = new LongOrderMap();   // starts at 16 slots
        for (long id = 1; id <= 100_000; id++) map.put(id, order(id));

        assertEquals(100_000, map.size());
        for (long id = 1; id <= 100_000; id++) assertEquals(id, map.get(id).id());
        assertNull(map.get(100_001));
    }

    @Test
    @DisplayName("deleting from the middle of a probe run keeps later keys findable")
    void deleteKeepsRunsIntact() {
        // Keys that are multiples of a large power of two all share low bits, which makes
        // collisions (and so long probe runs) likely. Delete every other one, then check the rest.
        LongOrderMap map = new LongOrderMap(16);
        int n = 6;   // stays under the grow threshold of a 16-slot table
        for (int i = 0; i < n; i++) map.put((long) i << 32, order(i));
        for (int i = 0; i < n; i += 2) assertNotNull(map.remove((long) i << 32));
        for (int i = 1; i < n; i += 2) assertNotNull(map.get((long) i << 32), "key " + i + " lost after deletes");
        for (int i = 0; i < n; i += 2) assertNull(map.get((long) i << 32));
    }

    @Test
    @DisplayName("1,000,000 random operations agree exactly with java.util.HashMap")
    void matchesHashMap() {
        // The strongest test: do the same random operations to both maps and compare after each.
        // A small key range forces constant collisions, deletes and reinserts, so every branch of
        // probing, wrap-around, backward-shift deletion and growth gets exercised many times.
        Random random = new Random(1234);
        LongOrderMap mine = new LongOrderMap();
        Map<Long, Order> reference = new HashMap<>();

        for (int step = 0; step < 1_000_000; step++) {
            long key = random.nextInt(5_000) - 1_000;          // includes negatives and 0
            int op = random.nextInt(10);
            if (op < 4) {
                Order o = order(key);
                assertSame(reference.putIfAbsent(key, o), mine.putIfAbsent(key, o), "putIfAbsent " + key);
            } else if (op < 5) {
                Order o = order(key);
                assertSame(reference.put(key, o), mine.put(key, o), "put " + key);
            } else if (op < 8) {
                assertSame(reference.remove(key), mine.remove(key), "remove " + key);
            } else {
                assertSame(reference.get(key), mine.get(key), "get " + key);
            }
            assertEquals(reference.size(), mine.size(), "size after step " + step);
        }
        for (Map.Entry<Long, Order> e : reference.entrySet()) {
            assertSame(e.getValue(), mine.get(e.getKey()), "final check " + e.getKey());
        }
    }
}
