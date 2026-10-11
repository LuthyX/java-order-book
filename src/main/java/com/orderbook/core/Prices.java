package com.orderbook.core;

/**
 * Prices are stored as whole numbers of 1/10,000 of a dollar (4 implied decimal places),
 * the same convention NASDAQ ITCH uses. $10.25 is stored as 102_500.
 *
 * Why not double? See PricesTest: binary floating point can't represent 0.1 exactly,
 * so sums drift and equality checks fail. A price-level map keyed by doubles could put
 * "the same" price in two different buckets.
 */
public final class Prices {
    public static final long SCALE = 10_000;

    private Prices() {}

    /** Parse a decimal string such as "10.25" into scaled units (102_500). */
    public static long fromString(String price) {
        return new java.math.BigDecimal(price).movePointRight(4).longValueExact();
    }

    /** Format scaled units back to a readable string: 102_500 -> "10.2500". */
    public static String toString(long scaled) {
        return java.math.BigDecimal.valueOf(scaled, 4).toPlainString();
    }
}
