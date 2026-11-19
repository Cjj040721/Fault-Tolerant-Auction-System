#!/usr/bin/env bash
# Demonstrate server-side concurrency: 3 clients run in parallel.
# User IDs and item IDs assigned in interleaved order proves concurrent access.
cd "$(dirname "${BASH_SOURCE[0]}")"

echo "=== Launching 3 clients simultaneously ==="
echo ""

mvn -q exec:java -Dexec.mainClass=client.AuctionClient 2>/dev/null | sed 's/^/[Client-1] /' &
PID1=$!
mvn -q exec:java -Dexec.mainClass=client.AuctionClient 2>/dev/null | sed 's/^/[Client-2] /' &
PID2=$!
mvn -q exec:java -Dexec.mainClass=client.AuctionClient 2>/dev/null | sed 's/^/[Client-3] /' &
PID3=$!

wait $PID1 $PID2 $PID3
echo ""
echo "=== All 3 clients finished ==="
echo "Note: user IDs and item IDs are NOT sequential within each client"
echo "because the server assigned them concurrently across all three clients."
