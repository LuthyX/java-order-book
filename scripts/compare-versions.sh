#!/usr/bin/env bash
#
# Benchmark several versions of the order book BACK TO BACK, in one sitting, so the machine's
# state (power, background apps, temperature) is the same for all of them. Numbers measured on
# different days aren't comparable; numbers from one run of this script are.
#
# For each commit given, it swaps in ONLY the order-book code (core/ and replay/) from that
# commit, keeps the CURRENT benchmark code (bench/), does a clean build, and runs the benchmark.
# At the end it puts your current code back.
#
# Usage (from the project folder):
#     scripts/compare-versions.sh 17c8ab9 bc56c48 b8d92c4
#
# Optional settings:
#     FILE=data/01302020.NASDAQ_ITCH50.gz  EVENTS=60000000  MAVEN_OPTS="-Xmx8g"
#
# Output for each version is also saved to bench-results/<commit>.txt

set -euo pipefail

FILE=${FILE:-data/01302020.NASDAQ_ITCH50.gz}
EVENTS=${EVENTS:-60000000}
export MAVEN_OPTS=${MAVEN_OPTS:--Xmx8g}
SWAP="src/main/java/com/orderbook/core src/main/java/com/orderbook/replay"

if [ $# -eq 0 ]; then
  echo "Usage: scripts/compare-versions.sh <commit> [<commit> ...]"
  echo "Example: scripts/compare-versions.sh 17c8ab9 bc56c48 b8d92c4"
  exit 1
fi

# Refuse to start with uncommitted changes in the code we're about to swap: they'd be lost.
if ! git diff --quiet HEAD -- src/main/java; then
  echo "You have uncommitted changes under src/main/java. Commit or stash them first."
  exit 1
fi

restore() {
  # Put the current commit's core/ and replay/ back, in the working tree AND the index.
  rm -rf $SWAP
  git checkout HEAD -- $SWAP
  git reset -q -- $SWAP
  echo
  echo "Restored your current code. git status:"
  git status --short src/main/java || true
}
trap restore EXIT   # runs even if a build or benchmark fails, or you press Ctrl-C

mkdir -p bench-results

for commit in "$@"; do
  label=$(git log -1 --format='%h %s' "$commit")
  echo
  echo "=================================================================="
  echo " $label"
  echo "=================================================================="

  # Replace the folders entirely: a newer file (e.g. PriceLevel.java) left behind
  # wouldn't compile against an older Order.java.
  rm -rf $SWAP
  git checkout "$commit" -- $SWAP

  mvn -q clean compile exec:java -Dexec.args="bench $FILE $EVENTS 3 10 3" \
    | tee "bench-results/$(git rev-parse --short "$commit").txt"
done
