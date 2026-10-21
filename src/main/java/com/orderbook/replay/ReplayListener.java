package com.orderbook.replay;

import com.orderbook.core.Order;
import com.orderbook.core.OrderBook;
import com.orderbook.core.Side;
import com.orderbook.itch.ItchListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * The bridge between the ITCH parser and the order books.
 *
 * Every decoded message becomes an operation on ONE stock's book, chosen by its stock
 * locate code. Nothing is matched here: NASDAQ's engine already did that, and the feed
 * tells us the results (see OrderBook's "feed mode").
 *
 *     ITCH message                 book operation
 *     R  Stock Directory           remember locate -> symbol
 *     A/F Add Order                addResting
 *     E  Executed                  reduce
 *     C  Executed With Price       reduce
 *     X  Cancel (partial)          reduce
 *     D  Delete                    cancel
 *     U  Replace                   cancel old + addResting new (side taken from the old order)
 *
 * It also checks the replay's health. A bad event is COUNTED, never thrown, so one
 * surprise can't stop a 400-million-message run, but it's never silently ignored either:
 *     unknownOrderEvents  an E/C/X/D/U referring to an order we never saw added
 *                         (a parser bug, skipped data, or a hidden order)
 *     rejectedEvents      an event the book refused, e.g. executing more shares than remain
 */
public final class ReplayListener implements ItchListener {

    /** Best bid/ask at one moment, for reporting. A missing side has an empty price and size 0. */
    public record TopOfBook(String symbol, OptionalLong bid, long bidSize, OptionalLong ask, long askSize) {}

    private static final int MAX_PROBLEMS_KEPT = 10;

    // One book per stock, keyed by stock locate. (Phase 4: locates are 1..~9,000, so this can
    // become a plain OrderBook[] indexed by locate, with no hashing and no boxed Integer keys.)
    private final Map<Integer, OrderBook> books = new HashMap<>();
    private final Map<Integer, String> symbolsByLocate = new HashMap<>();
    private final Map<String, Integer> locatesBySymbol = new HashMap<>();

    // Health and statistics.
    private final long[] countsByType = new long[128];   // indexed by the message-type letter
    private long unknownOrderEvents;
    private long rejectedEvents;
    private final List<String> firstProblems = new ArrayList<>();
    private long restingOrders;
    private long peakRestingOrders;
    private long peakRestingTimestamp;
    private long lastTimestamp;

    // Top of book for these symbols, captured when the market closes (System Event 'M').
    private final Set<String> watchedSymbols;
    private final Map<String, TopOfBook> closeSnapshot = new LinkedHashMap<>();

    public ReplayListener(Set<String> watchedSymbols) {
        this.watchedSymbols = watchedSymbols;
    }

    public ReplayListener() {
        this(Set.of());
    }

    // ===================== ITCH callbacks =====================

    @Override
    public void onSystemEvent(long timestamp, char eventCode) {
        count('S', timestamp);
        if (eventCode == 'M') {          // 'M' = End of Market hours: 4:00 p.m., the official close
            snapshotWatchedSymbols();
        }
    }

    @Override
    public void onStockDirectory(int stockLocate, long timestamp, String symbol) {
        count('R', timestamp);
        symbolsByLocate.put(stockLocate, symbol);
        locatesBySymbol.put(symbol, stockLocate);
    }

    @Override
    public void onAddOrder(int stockLocate, long timestamp, long orderRef, Side side, long shares, long price) {
        count('A', timestamp);   // A and F both arrive here, so they're counted together as 'A'
        OrderBook book = books.computeIfAbsent(stockLocate, k -> new OrderBook());
        try {
            book.addResting(orderRef, side, price, shares);
            restingChanged(+1, timestamp);
        } catch (IllegalArgumentException e) {
            reject("A", stockLocate, orderRef, timestamp, e);
        }
    }

    @Override
    public void onOrderExecuted(int stockLocate, long timestamp, long orderRef, long executedShares) {
        count('E', timestamp);
        reduce("E", stockLocate, timestamp, orderRef, executedShares);
    }

    @Override
    public void onOrderExecutedWithPrice(int stockLocate, long timestamp, long orderRef,
                                         long executedShares, long executionPrice) {
        count('C', timestamp);
        // The trade happened at a different price (e.g. an auction), but the resting order
        // is reduced exactly the same way. The price matters for trade reporting, not the book.
        reduce("C", stockLocate, timestamp, orderRef, executedShares);
    }

    @Override
    public void onOrderCancel(int stockLocate, long timestamp, long orderRef, long cancelledShares) {
        count('X', timestamp);
        reduce("X", stockLocate, timestamp, orderRef, cancelledShares);
    }

    @Override
    public void onOrderDelete(int stockLocate, long timestamp, long orderRef) {
        count('D', timestamp);
        OrderBook book = books.get(stockLocate);
        if (book != null && book.cancel(orderRef)) {
            restingChanged(-1, timestamp);
        } else {
            unknownOrder("D", stockLocate, orderRef, timestamp);
        }
    }

    @Override
    public void onOrderReplace(int stockLocate, long timestamp, long originalOrderRef, long newOrderRef,
                               long shares, long price) {
        count('U', timestamp);
        OrderBook book = books.get(stockLocate);
        Optional<Order> original = (book == null) ? Optional.empty() : book.getOrder(originalOrderRef);
        if (original.isEmpty()) {
            unknownOrder("U", stockLocate, originalOrderRef, timestamp);
            return;
        }
        // U doesn't say BUY or SELL: a replace keeps the original order's side.
        // Read it BEFORE cancelling, because after cancel() the order is gone.
        Side side = original.get().side();
        book.cancel(originalOrderRef);
        restingChanged(-1, timestamp);
        try {
            book.addResting(newOrderRef, side, price, shares);   // back of the queue: priority lost
            restingChanged(+1, timestamp);
        } catch (IllegalArgumentException e) {
            reject("U", stockLocate, newOrderRef, timestamp, e);
        }
    }

    @Override
    public void onOther(char messageType) {
        count(messageType, lastTimestamp);   // trades, auctions, halts...: counted, not applied
    }

    // ===================== shared steps =====================

    private void reduce(String type, int stockLocate, long timestamp, long orderRef, long shares) {
        OrderBook book = books.get(stockLocate);
        if (book == null) {
            unknownOrder(type, stockLocate, orderRef, timestamp);
            return;
        }
        int before = book.orderCount();
        try {
            if (!book.reduce(orderRef, shares)) {
                unknownOrder(type, stockLocate, orderRef, timestamp);
                return;
            }
        } catch (IllegalArgumentException e) {
            reject(type, stockLocate, orderRef, timestamp, e);   // book left unchanged by reduce()
            return;
        }
        restingChanged(book.orderCount() - before, timestamp);   // -1 if it was reduced to zero, else 0
    }

    private void count(char type, long timestamp) {
        if (type < countsByType.length) {
            countsByType[type]++;
        }
        lastTimestamp = timestamp;
    }

    private void restingChanged(int delta, long timestamp) {
        restingOrders += delta;
        if (restingOrders > peakRestingOrders) {
            peakRestingOrders = restingOrders;
            peakRestingTimestamp = timestamp;
        }
    }

    private void unknownOrder(String type, int locate, long orderRef, long timestamp) {
        unknownOrderEvents++;
        problem(type + " for unknown order #" + orderRef + " in " + symbolOf(locate) + " at " + time(timestamp));
    }

    private void reject(String type, int locate, long orderRef, long timestamp, IllegalArgumentException e) {
        rejectedEvents++;
        problem(type + " rejected for order #" + orderRef + " in " + symbolOf(locate) + " at " + time(timestamp)
                + ": " + e.getMessage());
    }

    private void problem(String description) {
        if (firstProblems.size() < MAX_PROBLEMS_KEPT) {
            firstProblems.add(description);
        }
    }

    private void snapshotWatchedSymbols() {
        for (String symbol : watchedSymbols) {
            topOfBook(symbol).ifPresent(top -> closeSnapshot.put(symbol, top));
        }
    }

    // ===================== results =====================

    /** The book for a ticker such as "AAPL", or empty if that stock had no orders (or doesn't exist). */
    public Optional<OrderBook> bookFor(String symbol) {
        Integer locate = locatesBySymbol.get(symbol);
        return (locate == null) ? Optional.empty() : Optional.ofNullable(books.get(locate));
    }

    /** Current best bid/ask and their sizes for a ticker. */
    public Optional<TopOfBook> topOfBook(String symbol) {
        return bookFor(symbol).map(book -> {
            OptionalLong bid = book.bestBid();
            OptionalLong ask = book.bestAsk();
            long bidSize = bid.isPresent() ? book.volumeAt(Side.BUY, bid.getAsLong()) : 0;
            long askSize = ask.isPresent() ? book.volumeAt(Side.SELL, ask.getAsLong()) : 0;
            return new TopOfBook(symbol, bid, bidSize, ask, askSize);
        });
    }

    /** Message counts by type letter, largest first. ('A' includes F.) */
    public Map<Character, Long> countsByType() {
        Map<Character, Long> counts = new TreeMap<>();
        for (char c = 0; c < countsByType.length; c++) {
            if (countsByType[c] > 0) counts.put(c, countsByType[c]);
        }
        List<Map.Entry<Character, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Character, Long>comparingByValue().reversed());
        Map<Character, Long> result = new LinkedHashMap<>();
        for (Map.Entry<Character, Long> e : sorted) result.put(e.getKey(), e.getValue());
        return result;
    }

    public long messageCount() {
        long total = 0;
        for (long c : countsByType) total += c;
        return total;
    }

    /** How many books currently have best bid >= best ask, which a correct replay should never produce. */
    public int crossedBooks() {
        int crossed = 0;
        for (OrderBook book : books.values()) {
            OptionalLong bid = book.bestBid();
            OptionalLong ask = book.bestAsk();
            if (bid.isPresent() && ask.isPresent() && bid.getAsLong() >= ask.getAsLong()) {
                crossed++;
            }
        }
        return crossed;
    }

    public long unknownOrderEvents()        { return unknownOrderEvents; }
    public long rejectedEvents()            { return rejectedEvents; }
    public List<String> firstProblems()     { return Collections.unmodifiableList(firstProblems); }
    public long restingOrders()             { return restingOrders; }
    public long peakRestingOrders()         { return peakRestingOrders; }
    public long peakRestingTimestamp()      { return peakRestingTimestamp; }
    public long lastTimestamp()             { return lastTimestamp; }
    public int bookCount()                  { return books.size(); }
    public int stockCount()                 { return symbolsByLocate.size(); }
    public Map<String, TopOfBook> closeSnapshot() { return Collections.unmodifiableMap(closeSnapshot); }

    private String symbolOf(int locate) {
        return symbolsByLocate.getOrDefault(locate, "locate " + locate);
    }

    /** Nanoseconds since midnight -> "HH:MM:SS.mmm". */
    public static String time(long nanosSinceMidnight) {
        long millis = nanosSinceMidnight / 1_000_000;
        return String.format("%02d:%02d:%02d.%03d",
                millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000);
    }
}
