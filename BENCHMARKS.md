# Benchmarks

All numbers from the same machine and the same workload, so each change can be compared with the one before.

**Machine:** Apple Silicon MacBook M2 Pro, JDK 21, `-Xmx8g`
**Workload:** first 60,000,000 book-changing events (A/F, E, C, X, D, U) of NASDAQ ITCH 5.0, January 30, 2020, covering 3:02 a.m. to 10:01 a.m. New York time, including the 9:30 open. Events are decoded once and replayed from memory, so the numbers measure the order book alone: no disk, decompression or parsing.

```bash
MAVEN_OPTS="-Xmx8g" mvn -q compile exec:java -Dexec.args="bench data/01302020.NASDAQ_ITCH50.gz 60000000"
```

Method: 3 warm-up runs (discarded), then 5 timed runs on fresh, empty books; then one run timing every event individually. Throughput spread across runs is about 3%, so a change has to beat the previous result by more than ~6% to count as a real improvement.

Note: on Apple Silicon, `System.nanoTime()` ticks in steps of about 42 ns, so per-event latencies are rounded to that granularity and values below ~50 ns aren't meaningful.

## Results

| Version | Throughput (events/s) | ns/event | p50 | p99 | p99.9 | p99.99 | max | GC per run |
|---|---|---|---|---|---|---|---|---|
| **Baseline**: TreeMap + ArrayDeque + HashMap | 3,056,529 (±3%) | 327 | 167 ns | 2.5 µs | 7.8 µs | 54 µs | 143 ms | 8.6 collections, 369 ms |
| **1. `PriceLevel`**: intrusive linked list, order → level back-pointer, running `totalQty` | 4,437,475 (±1.9%) | 225 | 125 ns | 1.1 µs | 5.4 µs | 16 µs | 82 ms | 5.0 collections, 213 ms |
| *change vs previous* | **+45%** | −31% | −25% | **2.3× better** | −31% | **3.3× better** | −42% | −42% GC time |

### 1. `PriceLevel`

**What changed:** each price level's `ArrayDeque<Order>` became a `PriceLevel` whose orders form an intrusive doubly linked list (`prev` / `next` fields on `Order`), and each resting order points back to its level. Removing an order (delete, full execution, cancel) now unlinks it in O(1) and only touches the `TreeMap` if the level becomes empty; before, it looked the level up in the `TreeMap` again and scanned the queue for the order. `volumeAt` reads a running total instead of summing the queue.

**Why the numbers moved:**
- Throughput: removes the profile's two largest book hotspots, the repeated `TreeMap.getEntry` (~20% of samples) and the `ArrayDeque` scan (~11%).
- Tail latency improved most (p99 2.3×, p99.99 3.3×): the slowest events were deletes deep in long queues around the open, which are now O(1).
- GC down 42% despite `Order` gaining three fields: no `ArrayDeque` objects or backing arrays per level, which were allocated, grown and discarded as levels came and went.

**Correctness:** 103 tests, including linked-list tests for every unlink position and 200,000 randomized operations with a full structural check (links, totals, counts, id index). A deliberately planted bug (tail not updated on removal) passed all 93 earlier tests and was caught only by the new ones. Replaying 150 MB of real ITCH through the old and new books gave identical results.

**Still to fix:** the max (82 ms) is still a GC pause.

## Where the time goes (full day, end to end)

Same file, all 423,285,709 messages, timed stage by stage (`breakdown` command):

| Stage | Time | Share |
|---|---|---|
| Disk read | 1.9 s | 1% |
| Decompression | 16.0 s | 9% |
| Framing + parsing | 12.4 s | 7% (34M messages/s on its own) |
| Book updates | 149.6 s | **83%** (2.8M messages/s on its own) |

## Profile of the baseline (JDK Flight Recorder, full-day replay)

| Data structure | Share of CPU samples | Main cause |
|---|---|---|
| `TreeMap` (price levels) | ~37% | every add looks up its level; every delete/execution looks it up *again* in `removeFromBook`; levels constantly created and destroyed |
| `HashMap` (order ids, books by locate) | ~31% | several lookups per message, boxed `Long` keys |
| `ArrayDeque.removeFirstOccurrence` | ~11% | O(n) scan to delete from the middle of a queue |
| Reading + parsing | ~16% | |

GC pauses over the full day: 80 pauses, 4.5 s total; median 44 ms, p99 649 ms.

## Planned changes, in order of the profile

1. ~~`PriceLevel` with an intrusive doubly linked list, and each `Order` pointing back to its level~~ **done: +45%**
2. Books indexed by stock locate in an array; one id lookup instead of two on add
3. Primitive `long → Order` map (no boxing)
4. Faster price-level lookup for adds
5. Object pooling to cut GC pauses
