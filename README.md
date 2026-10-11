# java-order-book

A limit order book and matching engine in Java that replays real NASDAQ ITCH 5.0 market data.

> Work in progress. Headline numbers, architecture and the optimization write-up land here as phases finish.

## Build and test

Requires JDK 21 and Maven.

```bash
mvn test          # run the test suite
mvn -q compile exec:java   # run Main
```

## Roadmap

- [x] Phase 0: project setup
- [ ] Phase 1: matching engine (price-time priority)
- [ ] Phase 2: ITCH 5.0 parser and replay
- [ ] Phase 3: benchmarks (JMH, latency percentiles)
- [ ] Phase 4: optimization passes
- [ ] Phase 5: NIO TCP server (stretch)
# java-order-book
