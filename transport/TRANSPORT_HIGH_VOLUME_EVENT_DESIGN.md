# Transport High-Volume Event Design

Last updated: 2026-04-27

This document turns transport from an adapter experiment into a production
data-plane design for high-volume worker events.

The goal is not richer realtime introspection. The goal is a transport path
that stays bounded, observable, and recoverable under load.

Use it with:

- [./AGENTS.md](./AGENTS.md)
- [./TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md)
- [../doc/HIGH_VOLUME_MODEL_BASELINE.md](../doc/HIGH_VOLUME_MODEL_BASELINE.md)
- [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md)
- [../doc/TESTING_BASELINE.md](../doc/TESTING_BASELINE.md)

## 1. Definition

In this document, "high-volume event" means a large number of worker-deliverable
units flowing through transport. Today those units are represented by
`TaskDispatchItem` with an optional `eventCode`. Later they may be represented
by a smaller queue-native envelope, but they must keep the same transport
semantics:

- route by `adapterId`
- deliver to a concrete worker endpoint
- apply explicit admission control and backpressure
- return adapter-neutral `DispatchOutcome`
- ingest results through `TransportResultEnvelope`
- keep task lifecycle mutation inside engine

This is not the SDK control-plane event handler API. SDK event handlers may
produce work, but transport is the worker delivery data plane.

## 2. Current Reality

Current transport already has the right permanent concepts:

- `TaskDispatchChannel`
- `DispatchOutcome`
- `TransportDelivery`
- `TransportDeliveryService`
- `TransportDeliveryStore`
- `TransportResultEnvelope`
- `TaskResultIngestChannel`
- runtime executor diagnostics

The experimental risk is not the concept set. The risk is that high-volume
behavior is still implicit:

- no single document defines the high-volume transport flow
- admission control exists, but retry/backpressure policy is still log-only
- delivery records do not yet expose durable ids, state, or age distribution
- in-memory delivery is the only store
- adapter direct-send and queue-send share outcomes, but not a full lifecycle
  model
- result identity validation is log-only

The design goal is to make the current concepts stricter rather than add new
adapter-specific paths.

Working bias:

- prefer explicit admission and idempotent outcomes
- prefer counters and trace over full-state reconstruction
- prefer store replacement for HA over protocol churn

## 3. Target Shape

Transport should converge on one data-plane shape:

```text
engine assignment
  -> TaskDispatchItem or future compact envelope
  -> TransportRoutingTaskMsgDispatchListener
  -> TransportDeliveryService
  -> TransportDeliveryStore
  -> adapter drain/send
  -> worker
  -> TransportResultEnvelope
  -> TaskResultIngestChannel
  -> engine result lifecycle
```

Adapter differences are delivery mechanics only:

- polling drains queued delivery
- websocket may direct-send when online and queue when appropriate
- socket may direct-send when online and queue when appropriate

All three adapters must keep the same observable delivery contract:

- `SENT`: endpoint accepted synchronous delivery
- `QUEUED`: runtime store accepted asynchronous delivery
- `ENDPOINT_OFFLINE`: adapter knows the endpoint is not currently reachable
- `BACKPRESSURE_REJECTED`: runtime or adapter admission rejected the item
- `INVALID_ITEM`: malformed dispatch input
- `ADAPTER_UNAVAILABLE`: adapter/runtime dependency is missing or stopped
- `FAILED`: unexpected delivery failure normalized by the adapter/runtime

## 4. Ownership

Transport owns:

- delivery queue/store/drain
- adapter routing by `adapterId`
- endpoint online/offline perception
- delivery admission and backpressure
- runtime executor admission
- transport diagnostics
- result envelope validation before handing results to engine

Engine owns:

- worker matching
- worker-context lock and release
- `TaskMsgAttempt`
- lease expiry
- retry and terminal policy
- final task/message lifecycle state

Transport must not create a second attempt state machine. Dispatch failures may
later feed an engine retry/release policy, but that policy must be designed as
an explicit engine interaction, not hidden inside adapters.

## 5. High-Volume Semantics

### 5.1 Admission

Every accepted worker-deliverable item must pass through an explicit admission
decision:

- per-worker queue cap
- global delivery backlog cap
- runtime executor pending cap
- adapter connection/session cap

Rejected admission must surface as `DispatchOutcome`, not as an unbounded
exception path.

Default policy:

- `BACKPRESSURE_REJECTED` is retryable
- `ENDPOINT_OFFLINE` is retryable
- `ADAPTER_UNAVAILABLE` is retryable only when the runtime dependency may
  recover
- `INVALID_ITEM` is not retryable
- `FAILED` depends on the concrete failure

### 5.2 Ordering

Transport only provides per-worker FIFO for queued delivery in the current
in-memory store. It does not provide global ordering across workers, adapters,
or tasks.

Future Redis/JDBC stores should preserve per `(adapterId, workerId)` FIFO for
queued delivery unless a stronger ordering requirement is explicitly approved.

### 5.3 Durability

Current in-memory delivery is best-effort and process-local. That is acceptable
for embedded validation and small deployments, but not enough for HA.

Durable store requirements:

- preserve `TransportDeliveryStore` API shape
- enforce the same admission semantics
- expose `TransportDeliveryStoreStats`
- support wake/drain for polling workers
- model wake/drain as single-consumer delivery per queued item; shutdown may wake all waiters for cleanup
- support shutdown/recovery without mutating engine lifecycle directly
- provide indexed lookup by `(adapterId, workerId)`

Durability must be introduced by replacing the store, not by changing adapter
wire protocols.

### 5.4 Backpressure

Backpressure must be visible at three levels:

- dispatch outcome per item
- queue/detail diagnostics
- test or load-runner counters

Backpressure must not silently mark a task message failed in transport. In the
current phase it relies on engine lease expiry and retry. A later phase may add
an explicit engine policy hook, but the hook must be centralized and tested.

### 5.5 Result Ingest

Result ingest stays hot-path and must avoid full task history scans.

Current result identity validation is log-only. The target progression is:

1. carry `attemptId` in `TransportResultEnvelope`
2. validate active attempt by indexed lookup
3. add `leaseToken`
4. reject only after token semantics and old-worker behavior are approved

## 6. Runtime Diagnostics

High-volume transport must expose enough data to answer operational questions:

- is the delivery store available
- how many items are queued
- how many worker queues exist
- how many pollers are waiting
- what is the global queue cap
- how old the oldest queued delivery is
- how many delivery records were accepted, drained, rejected, invalid,
  unavailable, or cleared during shutdown
- how many direct-send deliveries were sent, offline, failed, invalid, or
  unavailable
- direct-send counters by concrete `adapterId`
- are transport/event executors available
- how many runtime tasks were submitted, completed, active, pending, rejected
- what are executor pending caps

Next diagnostic fields to add when the store supports them:

- per-adapter queued counts
- per-adapter waiting pollers
- per-adapter backpressure count

Diagnostics are not lifecycle state. They guide capacity, alerting, and load
testing.

## 7. Store Interface Direction

`TransportDeliveryStore` is the replacement point for Redis or JDBC.

The interface should remain narrow:

- enqueue
- drain
- poll
- stats
- shutdown

Do not push engine concepts into the store. If later phases need delivery ids,
acks, or dead-letter handling, add them as transport delivery concepts:

- `deliveryId`
- `enqueueTime`
- `deliveryState`
- `lastOutcome`
- `attemptCount`

Do not add `TaskMsgAttempt` ownership or worker-lock ownership to the store.

## 8. Adapter Direction

Polling:

- mainline for external non-JVM workers
- always queue-first
- long-poll drains runtime delivery
- should get richer queue diagnostics before custom behavior

WebSocket:

- realtime adapter, not the product boundary
- direct-send may return `SENT`
- offline should either return `ENDPOINT_OFFLINE` or queue through runtime when
  an approved fallback policy exists
- must not own a private delivery queue

Socket:

- peer realtime adapter
- same delivery outcomes as websocket
- blocking accept/read/write work should use runtime executor abstractions

Adapters should not decide task retry or release.

## 9. Migration Plan

### Phase 1: Stabilize Current Runtime

Already in progress:

- common dispatch outcomes
- shared delivery store/service
- delivery queue diagnostics
- bounded runtime executors
- configurable runtime admission caps
- startup failure cleanup

Next small slices:

- add runtime delivery counters
- add oldest queued age to `TransportDeliveryStoreStats`
- add per-adapter queue counts if needed by load tests
- add load-runner assertions for backpressure and drain behavior

### Phase 2: Queue-First Realtime Delivery

Unify realtime adapter send around runtime delivery:

- engine dispatch enqueues into runtime store first
- realtime adapter drain loop attempts send for online endpoints
- polling remains pull-drain
- direct-send becomes an optimization behind the same delivery record

This is the point where delivery ids become useful.

### Phase 3: Durable Store

Implement `RedisTransportDeliveryStore` or `JdbcTransportDeliveryStore` behind
the existing store interface.

Acceptance:

- no adapter wire change
- same dispatch outcome semantics
- same diagnostics shape
- process restart does not lose queued delivery that was admitted to durable
  store

### Phase 4: Engine Policy Hook

Only after delivery outcomes are observable and tested:

- define how `BACKPRESSURE_REJECTED`, `ENDPOINT_OFFLINE`, and durable
  dead-letter states feed engine retry/release
- keep the hook centralized
- keep task lifecycle mutation in engine

### Phase 5: Result Security

Add `leaseToken` and strict active-attempt validation after compatibility and
retry behavior are designed.

## 10. Acceptance

Transport high-volume readiness should be judged by these lanes:

- unit tests for delivery store admission, drain, poll, shutdown, and stats
- runtime tests for dispatch grouping and outcome logging
- adapter tests for sent/offline/backpressure/unavailable outcomes
- SDK embedded load runner for polling, WebSocket, and socket realtime pressure
- Boot-shell E2E for public worker API compatibility
- scheduled chaos probes for restart/offline/backpressure cases

The minimum non-experimental bar is:

- bounded queues
- bounded executors
- observable backpressure
- explicit cleanup on failure
- shared outcomes across adapters
- no adapter-specific private lifecycle state
- no hidden task lifecycle mutation from transport
