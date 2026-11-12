# Benchmarks

**Machine:** Apple Silicon MacBook M2 Pro, JDK 21, `-Xmx8g`, on mains power with other apps closed
**Workload:** first 60,000,000 book-changing events (A/F, E, C, X, D, U) of NASDAQ ITCH 5.0, January 30, 2020, covering 3:02 a.m. to 10:01 a.m. New York time, including the 9:30 open. Events are decoded once and replayed from memory, so the numbers measure the order book alone: no disk, decompression or parsing.

```bash
MAVEN_OPTS="-Xmx8g" mvn -q compile exec:java -Dexec.args="bench data/01302020.NASDAQ_ITCH50.gz 60000000 3 10"
```

**Method:** 3 warm-up runs (discarded), then 10 timed runs on fresh, empty books; then one run timing every event individually. **Every version below was measured on the same day under the same conditions**, by checking out each commit in turn. Spread across runs was 0.3–3.3%.

**Why same-session matters:** the same code measured on different days varied by up to ~20% depending on power, background apps and temperature. An earlier, cross-day comparison credited `PriceLevel` with +45%; measured side by side with the baseline it is +19%. Only same-session comparisons are used here.

**Timer note:** on Apple Silicon, `System.nanoTime()` ticks in steps of about 42 ns, so per-event latencies are rounded to that granularity (84 ns = 2 ticks) and values below ~50 ns aren't meaningful.

**Tail-latency note:** the latency columns in the first table come from a **single** timed run per version, and later turned out to be unreliable for p99.9 and beyond (the same version's p99.9 varied from ~1.8 to ~5.5 µs between sessions). The benchmark now times three full runs and reports the median of each percentile; see [Tail latency, measured properly](#tail-latency-measured-properly). Throughput, GC and p50 in the first table are unaffected.

## Results: throughput (all four versions, same session, November 7)

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

**Summary:** throughput +61%, median latency halved, GC time down 69%. (Ignore the p99.9 and p99.99 columns above: single-run values. The reliable tail numbers are in the next section.)

## Tail latency, measured properly

The three optimized versions measured again in one session (November 12) with `scripts/compare-versions.sh 17c8ab9 bc56c48 b8d92c4`, which swaps in each version's order-book code while keeping the same benchmark. Each latency figure is the **median of three fully timed runs**; the three individual values are shown for p99.9.

| Version | Throughput | GC per run | p50 | p99 | p99.9 (median; 3 runs) | p99.99 | max |
|---|---|---|---|---|---|---|---|
| 1. `PriceLevel` | 4,733,662 (±3.7%) | 186 ms | 166 ns | 1,042 ns | **5,333 ns** (5,083 / 5,333 / 5,458) | 12,667 ns | 99 ms |
| 2. Books array + fewer id lookups | 6,056,567 (±7.1%) | 138 ms | 125 ns | 916 ns | **4,750 ns** (5,042 / 4,542 / 4,750) | 12,208 ns | 80 ms |
| 3. Primitive `long → Order` map | 7,236,914 (±1.2%) | 69 ms | 84 ns | 750 ns | **1,250 ns** (1,208 / 1,250 / 1,375) | 5,750 ns | 35 ms |

**Which events are the slowest 0.1%?** For each run, every event at or above that run's p99.9 is counted by kind, then compared with how common that kind is overall. A ratio above 1 means the kind is over-represented among the slowest events.

| Event kind | Share of all events | Ratio in tail: v1 | v2 | v3 |
|---|---|---|---|---|
| add (A/F) | 44.9% | 1.4× | 1.3× | **1.0×** |
| execute (E/C) | 1.6% | 0.6× | 0.7× | 0.9× |
| partial cancel (X) | 3.0% | 0.4× | 0.5× | 0.2× |
| delete (D) | 40.8% | 0.4× | 0.4× | 0.7× |
| replace (U) | 9.7% | 2.2× | 2.1× | **2.5×** |

**Findings:**
1. **Change 2 did not regress the tail.** Its p99.9 (4.75 µs) is slightly better than change 1's (5.3 µs), and its tail breakdown is almost identical. The earlier "2× worse" came from single latency runs in different sessions, where p99.9 for these versions moves between roughly 1.8 and 5.5 µs. Repeating the timed run and taking the median settled it.
2. **Change 3 is what shortened the tail:** p99.9 3.8× better (4.75 → 1.25 µs), p99.99 2.1× better, max 80 → 35 ms, consistent across all three runs. Adds fell from 1.3–1.4× over-represented to exactly their normal share, so inserts into `HashMap<Long, Order>` (a boxed `Long` and a `Node` per order, scattered in memory, plus occasional table resizes) appear to have been the main source of slow events.
3. **Replaces are now the clearest tail outlier** (24% of the slowest events from 9.7% of all events). A replace does three things in one event: look up the original order, cancel it (possibly removing an emptied price level), and add the new order (finding or creating its level). A dedicated replace operation that finds the original with a single `remove(id)` is the next tail target.
4. **The worst case (35 ms) is still a garbage-collection pause.**

Throughput in this session is lower for versions 1 and 2 than on November 7 (4.73M vs 5.38M; 6.06M vs 6.59M), and version 2 had a noisy run (±7.1%); version 3 matched (7.24M vs 7.26M). The ordering and the size of each step are consistent across both sessions.

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
- **The tail did not get worse.** Single latency runs suggested p99.9 doubled, and two sessions seemed to agree, but with three timed runs per version measured side by side, change 2's p99.9 is slightly better than change 1's and its tail breakdown is almost identical. See [Tail latency, measured properly](#tail-latency-measured-properly). Lesson: a far-tail percentile from one run isn't a measurement.

**Correctness:** all tests pass; replaying 150 MB of real ITCH gives results identical to the original book.

### 3. Primitive `long → Order` map

**What changed:** the id index went from `HashMap<Long, Order>` to `LongOrderMap`, an open-addressing hash map with primitive `long[]` keys and an `Order[]` of values: linear probing, backward-shift deletion (no tombstones), load factor 0.5, Fibonacci hashing to spread sequential ids. `HashMap` created a boxed `Long` and a `HashMap.Node` for every order; this map allocates nothing per entry once it has grown.

**Why the numbers moved:**
- **GC time −54% (85 → 39 ms per run):** for every *resting* order, the `Long` and `Node` lived as long as the order did, so they were part of what each collection had to copy and track. Removing them made every collection lighter.
- Tail: p99.9 **3.8× better** (4.75 → 1.25 µs, median of three runs) and max 80 → 35 ms. Adds stopped being over-represented among the slowest events, so `HashMap` inserts appear to have been the main source of the slow tail.
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
2. ~~Books indexed by stock locate; one id lookup instead of two~~ **done: +23%**
3. ~~Primitive `long → Order` map~~ **done: +10%, GC −54%, p99.9 3.8× better**
4. ~~Find the cause of change 2's tail regression~~ **resolved: no regression; it was single-run noise**
5. A dedicated replace operation (replaces are 2.5× over-represented among the slowest events)
6. Re-profile, then faster price-level lookup for adds
7. Low-latency GC (generational ZGC) and/or object pooling for the remaining worst-case pauses
