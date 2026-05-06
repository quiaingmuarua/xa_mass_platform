# Transport Boundary Baseline

Last updated: 2026-04-29

Status: current transport boundary baseline.

Trust order for transport decisions: code, verified runtime behavior, this
baseline, then design/reference or historical notes.

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
- `TransportDispatchEnvelope`: runtime-owned dispatch envelope, not a `TaskMsg`
  replacement
- `TransportDeliveryStore`: runtime-owned queueing/drain/poll seam for transport
  delivery
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
- runtime-owned envelope identity/timestamp generation for queued or direct
  dispatch handoff
- delivery backlog admission control and store statistics
- runtime executor handoff into adapter bootstraps for transport-owned blocking work
- result-envelope validation and runtime logging
- routing from task assignment events to adapter dispatch channels
- worker transport-binding resolution from registered worker truth via storage
  lookup contracts rather than the broader engine worker facade

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

Transport internals should prefer the explicit projections already exposed on
the hybrid:

- `wireView()` for adapter codec / worker-facing canonical frame assembly
- `runtimeMetadata()` for routing and internal result correlation

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

## Delivery Addressing

Transport delivery addressing is the pair:

- `adapterId`: concrete adapter identity such as `polling`, `websocket`, `socket`
- `routeKey`: adapter-local delivery address such as worker id / session id / endpoint id

Current runtime rules:

- `adapterId` is canonicalized by trim + lowercase
- `routeKey` is canonicalized by trim only; case is preserved
- route-key assembly is owned by transport runtime binding composition; the
  current default resolver uses worker id, but listeners must not hard-code
  worker id as the only valid delivery address
- mainline polling/websocket/socket bindings currently resolve `routeKey` from
  worker id explicitly at binding assembly time; that is a current policy, not
  a transport-global invariant
- realtime endpoint registries may still be keyed by worker id today, but
  their direct-send contract is route-based: send and online checks should
  speak in terms of `routeKey`, not imply that worker identity is the only
  valid address key
- blank `routeKey` is invalid for both queued delivery and direct-send delivery
- queue ownership and poll/drain isolation key off canonical `(adapterId, routeKey)`
- `routeKey` meaning is adapter-local; transport runtime must not reinterpret it as
  task, attempt, lease, or business routing truth
- route-only endpoint lookups are a single-adapter convenience only; once more
  than one endpoint registry is composed, callers must use adapter-scoped
  route operations instead of inferring ownership from endpoint snapshots
- worker-addressed debug/raw side-channels are not route truth; if they remain,
  they must first resolve one unique active `(adapterId, routeKey)` from
  endpoint state before adapter send
- future Redis/JDBC queue replacements must preserve the same canonical addressing
  rules and must not require hot-path scans to recover queue ownership

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
without first proving why the existing stable concepts cannot carry the
behavior.

## Hot-Path Rule

Result ingest is a hot path. Validation may read active attempt metadata, but it
must not load full task history or scan all attempts. Storage implementations
should provide an indexed lookup for:

```text
(taskId, messageId) -> latest active attempt
```

Dispatch is also a hot path. Delivery queues currently store
`TransportDispatchEnvelope` values and should avoid deep-copying task payload
maps beyond the immutable copies already owned by `TaskDispatchItem`.

Runtime delivery stores must enforce explicit admission control. The current
in-memory store has both per-worker queue caps and a configurable total
queued-item cap; Redis or JDBC replacements should preserve equivalent
backpressure. `TransportDeliveryStoreStats` is queue/store-path only; direct-send
diagnostics are assembled above the store boundary by
`TransportDeliveryServiceStats`. Poll semantics must stay explicit enough to
distinguish delivered, empty, invalid-request, unavailable, and shutdown
results without forcing callers to treat every non-delivery outcome as an empty
queue. Store shutdown is
also part of the runtime contract: after shutdown the store rejects new
delivery, clears in-memory backlog, and wakes waiting pollers without changing
engine-owned task lifecycle state.

Queue mechanics such as keyed FIFO storage, blocking poll coordination,
per-key/global admission, and queue snapshot counters may live under
`platform_infra` so long as transport semantics remain owned by
`TransportDeliveryStore`, `TransportDispatchEnvelope`, and `DispatchOutcome`.

## Direct vs Queued Delivery

Transport currently has two delivery paths:

- direct-send: realtime adapters attempt synchronous endpoint delivery and return
  `SENT` / `ENDPOINT_OFFLINE` / `FAILED` / `ADAPTER_UNAVAILABLE` / `INVALID_ITEM`
- queued delivery: polling or backlog-backed adapters admit an envelope into
  `TransportDeliveryStore` and return `QUEUED` / `BACKPRESSURE_REJECTED` /
  `ADAPTER_UNAVAILABLE` / `INVALID_ITEM`

These paths intentionally share `DispatchOutcome` identity fields and status
language, but they do not form one richer transport-owned lifecycle model.

Keep these rules:

- `SENT` does not imply durable store ownership, ack tracking, or later dequeue
- `QUEUED` means store admission only; engine lifecycle truth still lives outside transport
- queue stats such as `queueByAdapter`, backlog age, waiting pollers, and queued-item counts
  are queue-path diagnostics only
- direct-send counters are separate transport diagnostics and must not appear as
  synthetic queue occupancy or queue ownership
- do not add a transport-owned retry/lease/state machine that merges direct-send
  and queued delivery into one second attempt lifecycle

Observability rule:

- use logs, traces, queue stats, and indexed runtime lookups for diagnosis
- do not add full-history, full-task, or full-queue scans to transport hot paths
- delivery submission, result ingest, and shutdown handling should remain safe under duplicate or repeated calls
