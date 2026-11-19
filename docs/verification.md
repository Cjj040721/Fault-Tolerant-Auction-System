# Verification Record

Verified on 2026-11-19 using the cleaned repository, with no reliance on the original compiled `target/` directories.

## Build

```bash
cd part1 && mvn -q clean package
cd ../part2 && mvn -q clean package
```

Result: both builds passed, including protobuf and gRPC code generation.

## Part 1 Integration

Started `server.sh` and ran `client.AuctionClient` over real gRPC and RMI calls.

Verified:

- Three users registered with IDs 1, 2, and 3.
- Two auctions were created.
- Creating an auction for an unknown user returned `-1`.
- Bids below the reserve or current highest bid were rejected.
- Valid bids at and above the reserve were accepted.
- A non-owner could not close an auction.
- The owner closed the item with the expected winner and price.
- Closed items rejected subsequent bids.
- The final active-item list was empty.

## Part 2 Integration and Failover

Started the supplied five-replica cluster and ran the client successfully.

Stopped the initial sequencer:

```bash
kill "$(cat .pids/replica1.pid)"
```

Ran the same client again and verified:

- The front end detected the unavailable sequencer.
- `replica2` was elected as the new sequencer.
- Registration continued with user ID 5 rather than restarting at 1.
- Auction creation continued with item ID 3 rather than restarting at 1.
- Bidding, owner checks, closing, and list operations continued successfully.

The run used Java bytecode target 17 and the existing Maven configuration. Netty emitted JDK deprecation warnings but did not affect the test results.
