# Fault-Tolerant Auction System

A Java distributed auction application demonstrating gRPC, Java RMI, thread-safe state management, sequencer-based replication, majority acknowledgement, leader failover, and replica state recovery.

This was developed as a **team coursework project**. See [`AUTHORS.md`](AUTHORS.md) for the contribution breakdown and publication status.

## System Variants

### Part 1 — Single-Server Auction

```text
Auction Client ── gRPC ──> Front End ── RMI ──> Auction Server
```

- User registration and item creation
- Reserve-price and highest-bid validation
- Owner-only auction closing
- Thread-safe server state
- Concurrent client demonstration

### Part 2 — Replicated, Fault-Tolerant Auction

```text
                           ┌─ Replica 1 (initial sequencer)
Auction Client ── gRPC ──> Front End ── RMI ──┼─ Replica 2
                           ├─ Replica 3
                           ├─ Replica 4
                           └─ Replica 5
```

The supplied startup script launches five replicas. The client uses the same gRPC API as Part 1 and does not need to know which replica is the sequencer.

Key behaviors:

- The sequencer assigns monotonically increasing sequence numbers.
- Followers acknowledge proposals before operations are committed.
- A majority of the current member list is required before a write is applied.
- Committed operations are applied in sequence order on each replica.
- New replicas pull committed history from the current sequencer.
- Missing log entries trigger best-effort gap recovery.
- If the sequencer becomes unreachable, the front end elects a replacement and continues serving clients.

## Repository Layout

```text
part1/
├── proto/auction.proto
├── src/main/java/
├── pom.xml
├── server.sh
├── concurrent_test.sh
└── Makefile
part2/
├── proto/auction.proto
├── src/main/java/
├── pom.xml
├── server.sh
└── Makefile
docs/verification.md
```

## Requirements

- Java 17 or newer
- Maven 3.8 or newer
- A Bash-compatible shell
- Ports `1099` and `50055` available

## Build

```bash
cd part1 && mvn clean package
cd ../part2 && mvn clean package
```

## Run Part 1

```bash
cd part1
./server.sh
```

In another terminal:

```bash
cd part1
mvn -q exec:java -Dexec.mainClass=client.AuctionClient
```

To launch three clients concurrently:

```bash
cd part1
./concurrent_test.sh
```

## Run Part 2

```bash
cd part2
./server.sh
```

Wait until the replicas have registered, then run:

```bash
cd part2
mvn -q exec:java -Dexec.mainClass=client.AuctionClient
```

To stop processes and remove build artifacts:

```bash
make clean
```

## Fault-Tolerance Demonstration

With Part 2 running, execute the client once, then stop the initial sequencer:

```bash
kill "$(cat .pids/replica1.pid)"
```

Run the client again. The front end detects the failure, elects another replica, and continues processing requests. The verified run elected `replica2` and preserved the next user and item IDs.

See [`docs/verification.md`](docs/verification.md) for the build and integration-check results.

## Scope and Limitations

- State is in memory; there is no durable database or write-ahead log.
- The implementation is an educational replication protocol, not a production Raft or Paxos implementation.
- Networking uses local plaintext gRPC and Java RMI without authentication or TLS.
- Failure detection and leader election are deliberately simple.
- Gap recovery is best-effort and does not cover every network-partition scenario.

## License

The team has confirmed public release under the [MIT License](LICENSE). The license applies to original team code and documentation; third-party libraries retain their own licenses.
