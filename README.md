# java-order-book

A limit order book, matching engine and **NASDAQ ITCH 5.0 market data feed handler**, written in Java from scratch.

It does two things:

1. **Acts as an exchange.** A price-time priority matching engine that takes limit and market orders, matches them, and produces trades.
2. **Rebuilds a real exchange's books.** A binary parser for NASDAQ's ITCH 5.0 feed that replays a full real trading day into one order book per stock, ending with the same books NASDAQ had.

## Results

Full NASDAQ trading day, **January 30, 2020** (NASDAQ's public sample file, 5.3 GB compressed):

| | |
|---|---|
| Messages replayed | **423,285,709** (3:02 a.m. to 8:05 p.m. New York time) |
| Stocks | 8,915 in the directory, 8,900 with orders |
| Throughput | **2.7 million messages/s** end to end (decompress + parse + book updates) |
| Total time | 156.6 s |
| Peak resting orders | 1,925,638, at 3:57:45 p.m. |
| Consistency errors | **0** (see [How correctness is checked](#how-correctness-is-checked)) |

NASDAQ top of book at the 4 p.m. close, as rebuilt by the replay:

| Stock | Bid | Ask |
|---|---|---|
| AAPL | 323.87 × 10 | 323.90 × 1,640 |
| MSFT | 172.73 × 17,031 | 172.75 × 15,237 |
| TSLA | 640.77 × 1,000 | 640.93 × 264 |

Measured on an Apple Silicon MacBook M2 Pro, JDK 21. Throughput above is a single end-to-end run.

**Order book alone** (controlled benchmark: 60M real events from 3 a.m. to 10:01 a.m. including the open, replayed from memory, 3 warm-ups + 5 runs, full method in [BENCHMARKS.md](BENCHMARKS.md)):

| Version | Throughput | p50 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| Baseline | 3.06M events/s (±3%), 327 ns/event | 167 ns | 2.5 µs | 7.8 µs | 143 ms (a GC pause) |
| + `PriceLevel` (O(1) cancel) | **4.44M events/s (±1.9%), 225 ns/event: +45%** | 125 ns | **1.1 µs** | 5.4 µs | 82 ms |
| + books array, fewer id lookups | **5.44M events/s (±3.6%), 184 ns/event: +22% (+78% total)** | **84 ns** | 1.1 µs | 6.5 µs | 114 ms |

Throughput and median keep improving; the worst case is still set by garbage-collection pauses, which the remaining changes target.

**Where a full-day replay spends its time:** book updates **83%**, decompression 9%, parsing 7% (34M messages/s on its own), disk 1%. Profiling the book with JDK Flight Recorder points to `TreeMap` price-level lookups (~37% of samples), `HashMap` order lookups (~31%) and the O(n) queue scan on deletes (~11%), which set the order of the optimizations below.

What the day's messages show about real markets: **186.6M orders were added and 180.3M deleted, but only ~8.6M executions happened**. At least 95% of orders never trade; they're placed and cancelled as traders continually reprice.

## Architecture

```
                    ┌──────────────┐   ┌──────────────┐   ┌────────────────┐   ┌──────────────────┐
 ITCH file (.gz) ──►│  ItchReader  │──►│  ItchParser  │──►│ ReplayListener │──►│ OrderBook        │
                    │ cuts stream  │   │ bytes →      │   │ routes by      │   │ (one per stock)  │
                    │ into messages│   │ typed fields │   │ stock locate   │   │                  │
                    └──────────────┘   └──────────────┘   └────────────────┘   │  bids  TreeMap   │
                                                                               │  asks  TreeMap   │
 Orders (limit / market) ─────────────────────────► matching engine ─────────►│  id → order map  │
                                                     (OrderBook.addLimit…)     └──────────────────┘
```

- **`core`**: `OrderBook`, `Order`, `Trade`, `Side`, `Prices`
- **`itch`**: `ItchReader` (framing), `ItchParser` (decoding), `ItchListener` (callbacks)
- **`replay`**: `ReplayListener` (ITCH events → book operations + health checks), `Replay` (the runnable report)

The parser never touches a book and the book never sees a byte: the listener interface sits between them, so each part is tested on its own.

## Design decisions

**Prices are `long`s in 1/10,000 of a dollar**, the same units ITCH uses ($10.25 is `102_500`). `double` can't represent most decimal prices exactly (`0.1 + 0.2 != 0.3`), which would split equal prices into different price levels. `BigDecimal` is exact but allocates on every operation. A `long` is exact, allocation-free, and wide enough for unsigned 32-bit ITCH prices and price × quantity.

**Each side is a `TreeMap<price, PriceLevel>`**, with bids in reverse order so `firstKey()` is the best price on both sides. Price priority comes from the map's ordering, time priority from each level's FIFO queue. A `HashMap<id, Order>` finds any resting order in O(1).

**Each `PriceLevel` is an intrusive doubly linked list.** The orders themselves are the list nodes (`prev` / `next` fields), and each order points back to its level. So once an order is found by id, removing it is two pointer updates, wherever it sits in the queue, and the `TreeMap` is only touched when a level becomes empty. A running `totalQty` makes the volume at a price O(1). Every change to a resting order's quantity goes through the level, so the total can't drift.

**Every trade prints at the resting order's price.** An incoming order that crosses gets price improvement; its limit is a maximum (or minimum), not the price it pays.

**Two modes on one book.** `addLimit` / `addMarket` match like an exchange. `addResting` / `reduce` / `cancel` apply an exchange's *already-matched* results for replay: ITCH reports what happened, so replaying it must never re-match.

**Validate, then mutate.** Every operation checks everything that can fail before changing anything, so a bad message can never leave the book half-updated.

**ITCH parsing is absolute and unsigned.** Each field is read at its exact spec offset (`msg.getInt(32)`), and every number is masked to unsigned (`getInt(off) & 0xFFFFFFFFL`), because Java's integer types are signed and ITCH's aren't. The 6-byte timestamp is assembled from a 2-byte and a 4-byte read.

**Streaming with one reused buffer.** The reader holds one message at a time in a single 64 KB buffer (the largest size a 2-byte length can express), so a ~13 GB uncompressed day never needs to fit in memory and no per-message arrays are allocated.

## How correctness is checked

**93 unit tests** cover matching (priority, sweeps, partial fills, market orders, cancels, bad input), feed-mode operations, every decoded ITCH message type (including a hand-typed "golden" message and the unsigned and 48-bit edge cases), stream framing and truncation, and a full bytes-to-books pipeline.

**Health checks during replay** catch what unit tests can't:

| Check | What it would catch | Full day |
|---|---|---|
| Unknown-order events: an execute/cancel/delete/replace for an order never added | wrong field offsets, skipped messages, wrong stock routing, orders removed too early | **0** |
| Rejected events: e.g. executing more shares than remain | wrong share counts | **0** |
| Crossed books: best bid ≥ best ask, which NASDAQ's displayed book never shows | wrong side or price, a missed removal | **0** |
| Orders resting at the end of the day | removals not applied (NASDAQ deletes every expiring order) | **0** |

Every one of the ~230 million follow-up messages found its order, and every one of the 186.6 million added orders was removed by the end of the day.

## Running it

Requires **JDK 21** and **Maven**.

```bash
mvn test
```

**Replaying a real day:**

1. Download any `*.NASDAQ_ITCH50.gz` file from [NASDAQ's public ITCH archive](https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH) into `data/` (git-ignored; files are several GB).
2. Run, with optional tickers to report at the close:

```bash
MAVEN_OPTS="-Xmx6g" mvn -q compile exec:java \
  -Dexec.args="data/01302020.NASDAQ_ITCH50.gz AAPL MSFT TSLA"
```

The `.gz` is decompressed while streaming, never unpacked to disk. The report prints message counts by type, throughput, peak resting orders, the health checks and the closing top of book.

## Known limitations

- **Garbage-collection pauses** still set the worst case (around 100 ms max): every order is a new object, and ids are boxed `Long`s in a `HashMap`. Planned fixes below.
- **NASDAQ's book only.** US stocks trade on many exchanges at once; this rebuilds NASDAQ's slice, not the consolidated best price (NBBO). Hidden orders never appear in ITCH, so trades against them (`P` messages) are counted but not applied.

## Roadmap

- [x] Matching engine with price-time priority
- [x] ITCH 5.0 parser and full-day replay with health checks
- [x] **Benchmarks** ([BENCHMARKS.md](BENCHMARKS.md)): stage-by-stage timing of the full day, an in-memory book benchmark with warm-up and repeated runs, per-event latency percentiles, and a JDK Flight Recorder profile
- [ ] **Optimizations**, in the order the profile suggests, each measured before and after in [BENCHMARKS.md](BENCHMARKS.md):
  - [x] `PriceLevel` with an intrusive doubly linked list of orders and a running total: O(1) cancel from anywhere, O(1) `volumeAt` (**+45% throughput, p99 2.3× better**)
  - [x] array of books indexed by stock locate instead of a `HashMap`, and one id lookup instead of two on add and delete (**+22% throughput, median −33%**)
  - primitive `long → Order` map instead of boxed `Long` keys
  - object pooling to cut garbage-collection pauses
- [ ] Priority check against the real feed: does each execution hit the order my book says is first in line?
- [ ] NIO TCP server so clients can submit orders over the network

## Data

Market data: [NASDAQ TotalView-ITCH 5.0](https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHspecification.pdf) sample files, published by NASDAQ. The data isn't included in this repository.
