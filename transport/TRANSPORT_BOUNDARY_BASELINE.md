# Transport Boundary Baseline

This file freezes the current transport boundary so transport does not turn
into a second task engine.

Transport is now a production data plane, not an adapter experiment. Favor
throughput, availability, bounded admission, and idempotent runtime behavior
over richer but expensive observability state.

## Scope

Transport owns delivery mechanics for workers:

- worker endpoint connectivity and endpoint metadata
- adapter registration and adapter selection by `adapterId`
- task dispatch delivery, queueing, draining, and dispatch outcomes
- task result ingress wrapping with transport metadata
- worker system-event ingress and egress

Engine remains the owner of task lifecycle:

- worker matching and worker-context locks
- `TaskMsgAttempt` creation, lease, active-attempt truth, and closure
- `TaskMsg` status transitions, retry, release, and terminal policy
- lifecycle security decisions that mutate task state

## Stable Concepts

Transport should stay centered on these concepts only:

- `TaskDispatchChannel`: adapter dispatch SPI returning `DispatchOutcome`
- `DispatchOutcome`: adapter-neutral delivery result, never task-lifecycle truth
- `TransportDelivery`: runtime-owned delivery record, not a `TaskMsg` replacement
- `TaskResultIngestChannel`: result-ingest seam back into engine lifecycle
- `TransportResultEnvelope`: transport metadata around `TaskResultReport`, not a second worker protocol

Avoid adding new transport model names unless they carry a distinct runtime
behavior that cannot fit one of these concepts.

## Module Ownership

`transport_api` owns stable contracts used across adapters and runtime:

- adapter SPI
- transport-neutral dispatch/result/system-event interfaces
- endpoint registry contracts
- transport-neutral models that adapters must exchange with runtime

`transport_runtime` owns runtime-only coordination:

- adapter binding and registration resolution
- canonical adapter-id resolution; old aliases such as `ws`, `pull`, `queue`, or `tcp-socket` are not adapter identities
- canonical transport-hint resolution; adapter labels such as `websocket`,
  `ws`, `push`, `pull`, or `queue` are not family aliases
- delivery service and delivery store
- delivery backlog admission control and store statistics
- runtime executor handoff into adapter bootstraps for transport-owned blocking work
- result-envelope validation and runtime logging
- routing from task assignment events to adapter dispatch channels

Concrete adapters own protocol I/O only:

- server/session/endpoint lifecycle for their protocol
- frame or request/response codec
- endpoint online/offline perception
- calls into runtime delivery and result-ingest contracts
- accept/read/write loops submitted through the runtime executor context when they block

## Model Boundaries

`TaskDispatchItem` is currently a hybrid dispatch payload:

- worker-facing payload fields: task id, message id, event code, input, shared config
- runtime metadata fields: worker id, worker-context id, batch id, internal attempt id

The internal attempt identity is intentionally exposed through `attemptId()`, not
`getAttemptId()`, so JSON serializers do not add it to worker API responses by
JavaBean convention. Do not add JavaBean getters for internal metadata unless
the worker wire contract is intentionally changed.

Do not split this hybrid opportunistically. That is a cross-adapter wire change
touching adapter codecs and external worker API behavior.

`TransportResultEnvelope` is internal runtime metadata around a
`TaskResultReport`. `TaskResultReport` remains the protocol payload. Envelope
fields such as `attemptId` and `leaseToken` may be used by runtime validation,
but old workers that only submit `TaskResultReport` remain valid until the
security model explicitly changes.

`leaseToken` is reserved. Do not enforce it until there is an approved design
for token generation, storage, expiry, retry interaction, old-worker behavior,
and rejection semantics.

## Forbidden Drift

Do not let transport grow these responsibilities:

- task scheduling, matching, or worker lock ownership
- `TaskMsgAttempt` lifecycle mutation outside engine services
- direct task release, retry, or terminal decisions from adapters
- adapter-specific delivery queues when `TransportDeliveryService` can own the path
- worker wire payload changes for internal runtime metadata
- protocol-specific frame/codec types in `transport_api`
- compatibility wrappers that preserve old transport paths as parallel mainlines

Do not add generic-looking transport models such as `TransportTask`,
`TransportTaskMessage`, `WorkerDeliveryState`, or `TaskTransportSnapshot`
without first proving why the existing five stable concepts cannot carry the
behavior.

## Hot-Path Rule

Result ingest is a hot path. Validation may read active attempt metadata, but it
must not load full task history or scan all attempts. Storage implementations
should provide an indexed lookup for:

```text
(taskId, messageId) -> latest active attempt
```

Dispatch is also a hot path. Delivery queues may store `TransportDelivery`, but
they should avoid deep-copying task payload maps beyond the immutable copies
already owned by `TaskDispatchItem`.

Runtime delivery stores must enforce explicit admission control. The current
in-memory store has both per-worker queue caps and a configurable total
queued-item cap; Redis or JDBC replacements should preserve equivalent
backpressure and expose the same `TransportDeliveryStoreStats` shape for
backlog, queue, backlog age, and waiting-poller diagnostics. Store shutdown is
also part of the runtime contract: after shutdown the store rejects new
delivery, clears in-memory backlog, and wakes waiting pollers without changing
engine-owned task lifecycle state.

Observability rule:

- use logs, traces, queue stats, and indexed runtime lookups for diagnosis
- do not add full-history, full-task, or full-queue scans to transport hot paths
- delivery submission, result ingest, and shutdown handling should remain safe under duplicate or repeated calls
