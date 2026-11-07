package com.orderbook.core;

/**
 * A hash map from a primitive long (the order id) to an Order, with no boxing and no
 * per-entry objects. Replaces HashMap<Long, Order> for the book's id index.
 *
 * WHY: HashMap<Long, Order> creates two objects for every order: a Long for the key
 * (Java generics can't hold a primitive long) and a HashMap.Node for the entry. With
 * ~186 million orders a day that's hundreds of millions of short-lived objects for the
 * garbage collector, and every lookup follows pointers table -> Node -> Long -> Order.
 * Here the keys sit directly in a long[] and the values in an Order[]: adding an order
 * allocates nothing (except when the table doubles), and comparing keys never leaves the array.
 *
 * HOW (open addressing with linear probing):
 *
 *     keys:    [   0 | 48213 |     0 | 50111 | 50112 |     0 | ... ]
 *     values:  [ null|  #48213 | null|  #50111 |  #50112 | null | ... ]
 *
 *   - A key's "home" slot is a hash of the key masked to the table size (a power of two).
 *   - If the home slot is taken by another key, try the next slot, and the next...
 *   - An EMPTY slot is one whose value is null, so any long can be a key, including 0.
 *   - A lookup stops at the key or at the first empty slot ("not here").
 *   - The table doubles when it's more than half full, keeping those probe runs short.
 *
 * DELETING is the tricky part: just emptying a slot would cut a probe run in two, and a
 * key stored after the gap would become unfindable. This uses BACKWARD-SHIFT deletion:
 * after emptying a slot, later entries in the same run are moved back into the gap when
 * their home slot allows it. No "deleted" markers are left behind to slow later lookups.
 *
 * Not thread-safe; keys must be unique; values must not be null.
 */
final class LongOrderMap {

    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private Order[] values;
    private int mask;        // capacity - 1, so "& mask" is a fast "% capacity"
    private int size;
    private int growAt;      // grow when size exceeds this (half the capacity)

    LongOrderMap() {
        this(MIN_CAPACITY);
    }

    LongOrderMap(int initialCapacity) {
        int capacity = MIN_CAPACITY;
        while (capacity < initialCapacity) capacity <<= 1;
        allocate(capacity);
    }

    int size() {
        return size;
    }

    /** The value for key, or null if absent. */
    Order get(long key) {
        for (int i = home(key); ; i = (i + 1) & mask) {
            Order v = values[i];
            if (v == null) return null;          // reached an empty slot: the key isn't here
            if (keys[i] == key) return v;
        }
    }

    boolean containsKey(long key) {
        return get(key) != null;
    }

    /**
     * Insert key -> value if key is absent and return null; if key is already present,
     * change nothing and return the existing value. One probe sequence either way.
     */
    Order putIfAbsent(long key, Order value) {
        int i = home(key);
        for (Order v; (v = values[i]) != null; i = (i + 1) & mask) {
            if (keys[i] == key) return v;        // already there: leave it alone
        }
        insertAt(i, key, value);
        return null;
    }

    /** Insert or replace. Returns the previous value, or null. */
    Order put(long key, Order value) {
        int i = home(key);
        for (Order v; (v = values[i]) != null; i = (i + 1) & mask) {
            if (keys[i] == key) {
                values[i] = value;
                return v;
            }
        }
        insertAt(i, key, value);
        return null;
    }

    /** Remove key and return its value, or null if it wasn't present. */
    Order remove(long key) {
        int i = home(key);
        Order removed;
        while (true) {
            removed = values[i];
            if (removed == null) return null;    // not present
            if (keys[i] == key) break;
            i = (i + 1) & mask;
        }
        shiftBack(i);
        size--;
        return removed;
    }

    // ===================== internals =====================

    private void insertAt(int slot, long key, Order value) {
        keys[slot] = key;
        values[slot] = value;
        if (++size > growAt) {
            grow();
        }
    }

    /**
     * Empty slot `gap`, then walk forward through the rest of its probe run. An entry at j
     * whose home slot lies cyclically in (gap, j] is still reachable and stays put. Otherwise
     * its probe path passes through the gap, so it moves back into the gap, and the slot it
     * left becomes the new gap. Stops at the first empty slot (the end of the run).
     */
    private void shiftBack(int gap) {
        int j = gap;
        while (true) {
            j = (j + 1) & mask;
            Order v = values[j];
            if (v == null) break;
            int home = home(keys[j]);
            boolean reachable = (gap <= j)
                    ? (gap < home && home <= j)      // run doesn't wrap past the end of the array
                    : (gap < home || home <= j);     // run wraps around to the start
            if (reachable) continue;
            keys[gap] = keys[j];
            values[gap] = v;
            gap = j;
        }
        values[gap] = null;                          // the final gap is now truly empty
        keys[gap] = 0;
    }

    /**
     * Home slot: mix all 64 bits so sequential ids (ITCH order references mostly count up)
     * spread across the table, then keep the low bits. The multiplier is 2^64 / golden ratio,
     * a standard choice ("Fibonacci hashing") that scatters consecutive numbers well.
     */
    private int home(long key) {
        long h = key * 0x9E37_79B9_7F4A_7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    private void grow() {
        long[] oldKeys = keys;
        Order[] oldValues = values;
        allocate(oldKeys.length << 1);
        for (int i = 0; i < oldKeys.length; i++) {
            Order v = oldValues[i];
            if (v != null) {
                int slot = home(oldKeys[i]);
                while (values[slot] != null) slot = (slot + 1) & mask;   // keys are unique: no compare needed
                keys[slot] = oldKeys[i];
                values[slot] = v;
            }
        }
        // size is unchanged by a resize
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Order[capacity];
        mask = capacity - 1;
        growAt = capacity >>> 1;   // load factor 0.5: short probe runs, cheap misses
    }
}
