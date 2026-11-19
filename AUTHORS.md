# Authors and Contributions

This repository preserves a team coursework project. Contributor initials remain in relevant source-file comments.

## Cao Junjia (CJJ)

Primary responsibility for the replicated-auction implementation:

- `ReplicatedAuction.java` — replica-facing RMI contract
- `ReplicaImpl.java` — proposal/commit flow, ordered log application, auction state machine, majority checks, state recovery, and sequencer-state handling
- `ReplicaMain.java` — replica startup, registration, and historical-state synchronisation
- Shared work on `LogEntry.java` and `OperationResult.java`

## Other Team Contributions

The source also includes work on:

- gRPC service definitions and front-end startup
- Single-server auction logic and data models
- Front-end routing and administration
- Consensus-related shared types and leader-election integration
- Client and end-to-end test scenarios

The team confirmed public release under the MIT License on 2026-11-19.
