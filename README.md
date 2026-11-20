<div align="center">

# Fault-Tolerant Auction System

**A distributed Java auction with gRPC, RMI, replication, and leader failover.**

A single-server auction and a five-replica variant demonstrating thread-safe state management, sequencer-based writes, majority acknowledgement, ordered log application, state recovery, and automatic leader election.

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![gRPC](https://img.shields.io/badge/gRPC-Client_API-2AA887)](https://grpc.io/)
[![Java RMI](https://img.shields.io/badge/Java_RMI-Replica_Communication-0072B2)](#architecture)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Tests](https://github.com/Cjj040721/Fault-Tolerant-Auction-System/actions/workflows/tests.yml/badge.svg)](https://github.com/Cjj040721/Fault-Tolerant-Auction-System/actions/workflows/tests.yml)
[![Verification](https://img.shields.io/badge/Failover-Verified-6E56CF)](#verification)

[Overview](#overview) · [Architecture](#architecture) · [Quick Start](#quick-start) · [Failover Demo](#failover-demo) · [Verification](#verification) · [Limitations](#limitations)

**[Team contributions](AUTHORS.md) · [Verification record](docs/verification.md)**

</div>

---

<a id="overview"></a>
## 1. Overview

This repository preserves a **team coursework project** with two implementations of the same auction API. The client registers users, creates items, places bids, queries state, and closes auctions without needing to know whether the backend is a single server or a replica cluster.

Part 1 focuses on the basic gRPC-to-RMI request path and thread-safe auction state. Part 2 adds replication, majority-checked writes, historical synchronisation, gap recovery, and failover.

> This is an educational distributed-system implementation, not a production Raft or Paxos service. State is held in memory and networking is designed for a local demonstration.

## 2. System Variants

| Variant | Request path | Main behaviours |
| --- | --- | --- |
| **Part 1** | Client → gRPC front end → one RMI auction server | Registration, item creation, bid validation, owner-only closing, concurrent clients |
| **Part 2** | Client → gRPC front end → five RMI replicas | Sequencer writes, majority acknowledgement, ordered commits, state recovery, leader failover |

<a id="architecture"></a>
## 3. Architecture

```text
                         ┌─ Replica 1 (initial sequencer)
Auction Client ─ gRPC ─> Front End ─ RMI ─┼─ Replica 2
                         ├─ Replica 3
                         ├─ Replica 4
                         └─ Replica 5
```

A write follows this path:

1. The sequencer assigns a monotonically increasing sequence number.
2. Followers receive the proposal and acknowledge it.
3. The operation proceeds only after a majority of the current member list acknowledges.
4. Committed operations are applied in sequence order on every available replica.
5. New replicas pull committed history; missing entries trigger best-effort gap recovery.
6. If the sequencer is unreachable, the front end elects a replacement and continues serving clients.

<a id="quick-start"></a>
## 4. Quick Start

### Requirements

- Java 17 or newer
- Maven 3.8 or newer
- A Bash-compatible shell
- Ports `1099` and `50055` available

```bash
git clone https://github.com/Cjj040721/Fault-Tolerant-Auction-System.git
cd Fault-Tolerant-Auction-System
```

Build both variants:

```bash
cd part1 && mvn clean package
cd ../part2 && mvn clean package
```

### Run Part 1

```bash
cd part1
./server.sh
```

In another terminal:

```bash
cd part1
mvn -q exec:java -Dexec.mainClass=client.AuctionClient
```

Run three clients concurrently:

```bash
./concurrent_test.sh
```

### Run Part 2

```bash
cd part2
./server.sh
```

Wait for the replicas to register, then run:

```bash
cd part2
mvn -q exec:java -Dexec.mainClass=client.AuctionClient
```

Stop processes and remove build artifacts with `make clean` in the relevant directory.

<a id="failover-demo"></a>
## 5. Failover Demo

With Part 2 running, execute the client once, then stop the initial sequencer:

```bash
kill "$(cat .pids/replica1.pid)"
```

Run the client again. The front end detects the failure, elects another replica, and continues processing the same client workflow.

In the verified run, `replica2` became the new sequencer. Registration continued from user ID 5 and auction creation from item ID 3 rather than restarting at 1.

<a id="verification"></a>
## 6. Verification

| Check | Result |
| --- | --- |
| Part 1 JUnit | Auction rules, unknown users, reserve prices, and owner checks passed |
| Part 2 JUnit | **5 tests passed**: leader writes, minority rejection, gap handling, sequence continuity, non-leader rejection |
| Part 1 Maven build | Passed, including protobuf and gRPC generation |
| Part 2 Maven build | Passed, including protobuf and gRPC generation |
| Auction rules | Reserve price, highest bid, unknown users, and owner checks behaved as expected |
| Part 1 integration | Registration, creation, bidding, closing, and final empty list passed over real gRPC and RMI |
| Part 2 integration | Five-replica workflow passed |
| Sequencer failure | `replica2` elected after stopping `replica1`; requests continued |
| State continuity | Next user ID `5`, next item ID `3`; no reset to 1 |

The full commands and results are recorded in [`docs/verification.md`](docs/verification.md).

## 7. Repository Structure

```text
Fault-Tolerant-Auction-System/
├── part1/
│   ├── proto/auction.proto    # Client-facing gRPC contract
│   ├── src/main/java/         # Front end, server, models, and client
│   ├── concurrent_test.sh
│   ├── server.sh
│   └── pom.xml
├── part2/
│   ├── proto/auction.proto    # Same client-facing contract
│   ├── src/main/java/         # Front end, replicas, shared types, and client
│   ├── server.sh              # Five-replica startup
│   └── pom.xml
├── docs/verification.md
├── AUTHORS.md
└── LICENSE
```

<a id="limitations"></a>
## 8. Limitations

- State is in memory; there is no durable database or write-ahead log.
- Leader election and failure detection are deliberately simple.
- Gap recovery is best-effort and does not cover every network-partition scenario.
- Local plaintext gRPC and Java RMI are used without authentication or TLS.
- The replication protocol is designed for teaching and should not be treated as a production consensus implementation.

## 9. Authors and Licence

This is a **team coursework project**. Cao Junjia's primary contributions were the replica interface, replica implementation and startup, proposal/commit flow, ordered log application, and state recovery. See [`AUTHORS.md`](AUTHORS.md) for the complete contribution breakdown.

The team has confirmed public release under the [MIT License](LICENSE). Third-party libraries retain their original licences.

---

<div align="center">

**Fault-Tolerant Auction System — Same client. More replicas. Keep serving after the leader stops.**

Java 17 + gRPC + RMI + Maven

</div>
