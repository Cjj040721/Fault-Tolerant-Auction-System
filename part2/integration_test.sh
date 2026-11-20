#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
LOG_FILE="integration-server.log"
CLIENT_LOG="integration-client.log"
rm -f "$LOG_FILE" "$CLIENT_LOG"

cleanup() {
  if [ -d .pids ]; then
    for file in .pids/*.pid; do
      [ -f "$file" ] || continue
      kill "$(cat "$file")" 2>/dev/null || true
    done
  fi
  pkill -x rmiregistry 2>/dev/null || true
  pkill -f 'frontend\.FrontEndServer' 2>/dev/null || true
  pkill -f 'replica\.ReplicaMain' 2>/dev/null || true
}
trap cleanup EXIT

./server.sh >"$LOG_FILE" 2>&1 &

ready_count=0
for _ in {1..60}; do
  ready_count=$(grep -c 'registered with front-end. Ready' "$LOG_FILE" || true)
  if [ "$ready_count" -ge 5 ]; then
    break
  fi
  sleep 1
done
if [ "$ready_count" -lt 5 ]; then
  echo "Only $ready_count/5 replicas became ready" >&2
  exit 1
fi

mvn -q exec:java -Dexec.mainClass=client.AuctionClient >>"$CLIENT_LOG" 2>&1
kill "$(cat .pids/replica1.pid)"
sleep 1
mvn -q exec:java -Dexec.mainClass=client.AuctionClient >>"$CLIENT_LOG" 2>&1

grep -q 'Elected new sequencer: replica2' "$LOG_FILE"
grep -q 'alice=5  bob=6  carol=7  dave=8' "$CLIENT_LOG"
grep -q 'item1(Laptop)=3  item2(Phone)=4' "$CLIENT_LOG"
grep -q 'Alice closes Laptop: winner=7 price=750' "$CLIENT_LOG"

echo "Five-replica failover passed: replica2 elected; user IDs continue at 5 and item IDs at 3."
