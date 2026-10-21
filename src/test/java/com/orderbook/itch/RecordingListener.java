package com.orderbook.itch;

import com.orderbook.core.Side;

import java.util.ArrayList;
import java.util.List;

/** Test helper: records every callback as a small record, so tests can assertEquals on them. */
final class RecordingListener implements ItchListener {

    record SystemEvent(long ts, char code) {}
    record StockDirectory(int locate, long ts, String symbol) {}
    record AddOrder(int locate, long ts, long ref, Side side, long shares, long price) {}
    record Executed(int locate, long ts, long ref, long shares) {}
    record ExecutedWithPrice(int locate, long ts, long ref, long shares, long price) {}
    record Cancel(int locate, long ts, long ref, long shares) {}
    record Delete(int locate, long ts, long ref) {}
    record Replace(int locate, long ts, long oldRef, long newRef, long shares, long price) {}
    record Other(char type) {}

    final List<Object> events = new ArrayList<>();

    @Override public void onSystemEvent(long ts, char code) { events.add(new SystemEvent(ts, code)); }

    @Override public void onStockDirectory(int locate, long ts, String symbol) {
        events.add(new StockDirectory(locate, ts, symbol));
    }

    @Override public void onAddOrder(int locate, long ts, long ref, Side side, long shares, long price) {
        events.add(new AddOrder(locate, ts, ref, side, shares, price));
    }

    @Override public void onOrderExecuted(int locate, long ts, long ref, long shares) {
        events.add(new Executed(locate, ts, ref, shares));
    }

    @Override public void onOrderExecutedWithPrice(int locate, long ts, long ref, long shares, long price) {
        events.add(new ExecutedWithPrice(locate, ts, ref, shares, price));
    }

    @Override public void onOrderCancel(int locate, long ts, long ref, long shares) {
        events.add(new Cancel(locate, ts, ref, shares));
    }

    @Override public void onOrderDelete(int locate, long ts, long ref) { events.add(new Delete(locate, ts, ref)); }

    @Override public void onOrderReplace(int locate, long ts, long oldRef, long newRef, long shares, long price) {
        events.add(new Replace(locate, ts, oldRef, newRef, shares, price));
    }

    @Override public void onOther(char type) { events.add(new Other(type)); }
}
