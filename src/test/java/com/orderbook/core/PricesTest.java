package com.orderbook.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PricesTest {

    @Test
    void doublesCannotBeTrustedForMoney() {
        // This is the reason the whole project uses long for prices.
        double sum = 0.1 + 0.2;
        assertNotEquals(0.3, sum);          // sum is actually 0.30000000000000004
    }

    @Test
    void scaledLongsAreExact() {
        long sum = Prices.fromString("0.1") + Prices.fromString("0.2");
        assertEquals(Prices.fromString("0.3"), sum);
    }

    @Test
    void roundTripsThroughString() {
        assertEquals(102_500L, Prices.fromString("10.25"));
        assertEquals("10.2500", Prices.toString(102_500L));
    }

    @Test
    void rejectsMoreThanFourDecimals() {
        // longValueExact throws instead of silently rounding: fail loudly on bad input.
        assertThrows(ArithmeticException.class, () -> Prices.fromString("10.12345"));
    }

    @Test
    void oppositeSide() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }
}
