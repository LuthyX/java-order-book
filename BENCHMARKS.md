# Benchmarks

**Machine:** Apple Silicon MacBook M2 Pro, JDK 21, `-Xmx8g`, on mains power with other apps closed
**Workload:** first 60,000,000 book-changing events (A/F, E, C, X, D, U) of NASDAQ ITCH 5.0, January 30, 2020, covering 3:02 a.m. to 10:01 a.m. New York time, including the 9:30 open. Events are decoded once and replayed from memory, so the numbers measure the order book alone: no disk, decompression or parsing.

```bash
MAVEN_OPTS="-Xmx8g" mvn -q compile exec:java -Dexec.args="bench data/01302020.NASDAQ_ITCH50.gz 60000000 3 10"
```

**Method:** 3 warm-up runs (discarded), then 10 timed runs on fresh, empty books; then one run timing every event individually. **Every version below was measured on the same day under the same conditions**, by checking out each commit in turn. Spread across runs was 0.3–3.3%.

**Why same-session matters:** the same code measured on different days varied by up to ~20% depending on power, background apps and temperature. An earlier, cross-day comparison credited `PriceLevel` with +45%; measured side by side with the baseline it is +19%. Only same-session comparisons are used here.

**Timer note:** on Apple Silicon, `System.nanoTime()` ticks in steps of about 42 ns, so per-event latencies are rounded to that granularity (84 ns = 2 ticks) and values below ~50 ns aren't meaningful. Latency percentiles come from a single timed run, so the far tail (p99.99, max) is noisier than throughput, which is averaged over 10 runs.

## Results

| Version | Throughput (events/s) | ns/event | GC per run | p50 | p90 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|---|---|---|---|
| **Baseline**: `TreeMap` + `ArrayDeque` + `HashMap` | 4,515,305 (±0.3%) | 221 | 7.8 collections, 124 ms | 166 ns | 459 ns | 1.08 µs | 2.7 µs | 7.3 µs | 40.1 ms |
| **1. `PriceLevel`** (intrusive linked list) | 5,376,089 (±1.8%) | 186 | 5.0 collections, 104 ms | 125 ns | 375 ns | 0.92 µs | 1.8 µs | 6.1 µs | 36.7 ms |
| **2. Books array + fewer id lookups** | 6,590,262 (±1.4%) | 152 | 4.0 collections, 85 ms | 84 ns | 334 ns | 0.92 µs | 3.6 µs | 12.1 µs | 44.6 ms |
| **3. Primitive `long → Order` map** | **7,257,351** (±3.3%) | **138** | **3.0 collections, 39 ms** | **84 ns** | **292 ns** | **0.88 µs** | 2.6 µs | 9.5 µs | **35.4 ms** |

| Step | Throughput | ns/event | GC time | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|---|---|---|
| 1 vs baseline | **+19%** | −16% | −16% | −25% | −15% | **−33%** | −16% | −8% |
| 2 vs 1 | **+23%** | −18% | −18% | **−33%** | same | **2× worse** | **2× worse** | +21% |
| 3 vs 2 | **+10%** | −9% | **−54%** | same | −4% | −28% | −21% | −21% |
| **3 vs baseline** | **+61%** | **−38%** | **−69%** | **−49%** | −19% | −3% | +32% | −12% |

**Summary:** throughput +61%, median latency halved, GC time down 69%. The far tail (p99.9 and beyond) has not improved overall: change 2 doubled it and change 3 recovered only part of that. The cause is under investigation (see change 2).

## Changes

### 1. `PriceLevel`

**What changed:** each price level's `ArrayDeque<Order>` became a `PriceLevel` whose orders form an intrusive doubly linked list (`prev` / `next` fields on `Order`), and each resting order points back to its level. Removing an order (delete, full execution, cancel) unlinks it in O(1) and only touches the `TreeMap` if the level becomes empty; before, it looked the level up in the `TreeMap` again and scanned the queue for the order. `volumeAt` reads a running total instead of summing the queue.

**Why the numbers moved:**
- Throughput +19%: removes the profile's two largest book hotspots, the repeated `TreeMap.getEntry` (~20% of samples) and the `ArrayDeque` scan (~11%).
- p99.9 −33%: some of the slowest events were deletes deep in long queues around the open, now O(1).
- GC −16% despite `Order` gaining three fields: no `ArrayDeque` objects or backing arrays per level.

**Correctness:** linked-list tests for every unlink position, and 200,000 randomized operations with a full structural check (links, totals, counts, id index). A deliberately planted bug (tail not updated on removal) passed all 93 earlier tests and was caught only by the new ones. Replaying 150 MB of real ITCH through the old and new books gave identical results.

### 2. Books array + fewer id lookups

**What changed:**
- `ReplayListener` finds each stock's book with `books[locate]` (a plain `OrderBook[65536]`) instead of `HashMap<Integer, OrderBook>.get(locate)`. Locates are 2-byte numbers, so the array covers every possible one in about 0.5 MB. This runs on every message.
- `addResting` checks for a duplicate id and inserts with one `putIfAbsent` instead of `containsKey` + `put`. It's the last check that can fail, so a rejected order still leaves the book unchanged.
- `cancel` finds and removes the order with one `remove(id)` instead of `get(id)` + `remove(id)`.

**Why the numbers moved:**
- Throughput +23% and median −33%: one fewer hash lookup on every message and one fewer id search on every add and delete. The *common case* got cheaper.
- **The tail got worse (p99.9 and p99.99 about 2×), reproduced in two separate sessions, so it isn't noise.** It isn't GC either: p99.9 is the slowest ~60,000 events, while a GC pause lands on only a handful. The cause is not yet known. Next step: break the slowest events down by type (add / execute / cancel / delete / replace) to find which operation is responsible.

**Correctness:** all tests pass; replaying 150 MB of real ITCH gives results identical to the original book.

### 3. Primitive `long → Order` map

**What changed:** the id index went from `HashMap<Long, Order>` to `LongOrderMap`, an open-addressing hash map with primitive `long[]` keys and an `Order[]` of values: linear probing, backward-shift deletion (no tombstones), load factor 0.5, Fibonacci hashing to spread sequential ids. `HashMap` created a boxed `Long` and a `HashMap.Node` for every order; this map allocates nothing per entry once it has grown.

**Why the numbers moved:**
- **GC time −54% (85 → 39 ms per run):** for every *resting* order, the `Long` and `Node` lived as long as the order did, so they were part of what each collection had to copy and track. Removing them made every collection lighter.
- Tail −21 to −28% (p99.9, p99.99, max): fewer and shorter pauses landing among the events.
- Throughput +10%: lookups were already O(1); they're now cheaper, with no pointer-chasing through `Node` and `Long`.
- Median unchanged (84 ns, 2 timer ticks): this change helps the outliers, not the typical event.

**Correctness:** a differential test runs 1,000,000 random operations against both `LongOrderMap` and `java.util.HashMap` and checks they agree after every step, with a deliberately small key range so collisions, wrap-around and backward-shift deletes happen constantly. Two planted bugs (deleting by just emptying the slot; an off-by-one in the wrap-around check) were both caught by the new tests and missed by the earlier book tests. Real-data replay is identical to the original book.

## Where the time goes (full day, end to end)

Same file, all 423,285,709 messages, timed stage by stage (`breakdown` command), on the baseline version:

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

## Planned changes

1. ~~`PriceLevel` with an intrusive doubly linked list~~ **done: +19%**
2. ~~Books indexed by stock locate; one id lookup instead of two~~ **done: +23%, tail regression under investigation**
3. ~~Primitive `long → Order` map~~ **done: +10%, GC −54%**
4. Find the cause of change 2's tail regression (slowest events broken down by type)
5. Faster price-level lookup for adds
6. Object pooling to cut remaining GC pauses
