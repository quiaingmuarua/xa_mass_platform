# Transport Boundary Baseline

Last updated: 2026-05-13

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
- worker presence truth as a shared readable runtime view keyed by worker with
  one or more route-owner records addressed by `adapterId + routeKey +
  transportNodeId`
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
- `TaskDispatchHandoff`: post-claim assignment queue between engine and
  transport; it is not the runtime ready queue
- `NodeTargetedTaskDispatchHandoff`: per-transport-node dispatch inbox for the
  same post-claim `TaskDispatchBatch` shape
- `TaskPullResult`: explicit pull-path status plus delivered dispatch items;
  empty queue, invalid request, temporary unavailability, and shutdown must not
  be flattened into one fake "no work" result on the transport mainline
- `WorkerPresenceStore`: shared transport-owned reachability projection with
  lease-based `ONLINE/STALE/OFFLINE` semantics
- `WorkerDispatchRouteOwnerView`: narrow read-only route-owner view used by
  engine-side dispatch routing after worker matching has already selected a
  worker

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
- engine-to-transport dispatch handoff queue/store ownership after assignment;
  current embedded default wiring is an in-memory `TaskDispatchHandoff` drained
  by a runtime pump, while split adapter runtimes may use node-targeted Redis
  inboxes
- both producer and consumer sides now speak `TaskDispatchBatchListener` at
  this seam; engine submits immutable `TaskDispatchContext +
  List<TaskDispatchBinding>` batches into handoff, and transport drains the same
  batch shape without rewrapping through an older listener API
- worker transport-binding resolution from registered worker truth via storage
  lookup contracts rather than the broader engine worker facade
- consumption of shared dispatch-ready/result-ingest seams from neutral runtime
  contracts rather than direct engine listener/package ownership

Concrete adapters own protocol I/O only:

- server/session/endpoint lifecycle for their protocol
- frame or request/response codec
- endpoint connect/disconnect/heartbeat ingress and writes into transport
  presence
- calls into runtime delivery and result-ingest contracts
- accept/read/write loops submitted through the runtime executor context when they block

Engine may read transport reachability for matching and dispatch eligibility,
but transport owns the online truth itself. Heartbeat expiry is a transport
lease rule, not an engine selector heuristic. `STALE` is transport-owned
diagnostic state and must be treated as not dispatchable on the engine side.
Shared-store implementations such as Redis must preserve the same route-owner
semantics as the in-memory default. A worker may have multiple route owners;
`getPresence(workerId)` is only a compatibility projection, while
`findOwners(workerId)` is the route-owner view for dispatch routing. Engine
must not write presence, read adapter sessions, or treat presence as a schedule
owner.

## Distributed Transport V1

The first split deployment is central engine plus independent transport JVMs.
It is queue-first and does not add server-owned transport ingress endpoints.
`xa-mass-server` remains a shell/API validation surface, not the transport data
plane owner.

The split runtime uses three transport/runtime channels:

- dispatch handoff: engine submits `TaskDispatchBatch` values after claim,
  attempt creation, lease, and worker binding have already happened; transport
  drains only this small assigned window. Multi-process adapter mode uses
  `NodeTargetedTaskDispatchHandoff` queues keyed by `transportNodeId` so each
  transport JVM consumes only its own inbox.
- delivery inbox: transport routes dispatch envelopes into
  `TransportDeliveryStore` by `adapterId + routeKey`
- result/compensation inboxes: transport writes `TransportResultEnvelope`
  values and retryable dispatch-failure events to Redis-backed inboxes; the
  engine process drains those inboxes into its local result ingest and
  assignment compensation ports. Result lifecycle ownership is defined in
  [../doc/RESULT_BOUNDARY_BASELINE.md](../doc/RESULT_BOUNDARY_BASELINE.md).

These queues are runtime-state queues. They must be bounded and must preserve
backpressure instead of growing without limit. They also must not be treated as
durable task lifecycle truth. `TaskWorkRuntime` remains the only owner of ready
membership, delayed visibility, active lease, retry timing, result application,
and terminal convergence.

`TaskDispatchBatch` is the process-boundary payload for dispatch handoff. It
contains `TaskDispatchContext` plus `List<TaskDispatchBinding>` and must stay
JSON-safe. Large item bodies continue to use `payloadRef` when needed; the
handoff queue is not a copy of a million-item task queue.

Worker runtime state is not a queue. The shared worker view contains
route-owner records:

```text
workerId, adapterId, routeKey, transportNodeId, connectionId,
state, leaseExpireAt, updatedAt
```

Engine matching still selects a worker from control-plane registration,
capability, rule, lock, and reachability inputs. Only after assignment has
produced concrete bindings does dispatch routing choose one ONLINE route owner
and write the batch to that owner's node inbox. Missing/stale/offline route
owners or offline transport nodes go through engine-owned compensation/retry;
transport does not re-schedule or mutate task lifecycle.

SDK/starter assembly exposes three runtime roles:

- `EMBEDDED`: engine, local handoff pump, transport runtime, adapters, and local
  result ingest run in one JVM
- `ENGINE_PRODUCER`: engine runs and submits dispatch batches into the configured
  handoff; it drains result and dispatch-failure inboxes back into local engine
  ports, but does not start transport adapters
- `TRANSPORT_CONSUMER`: transport adapters, presence, delivery store, and
  handoff pump run without starting the engine; results and retryable dispatch
  failures are enqueued for engine-side draining

Transport consumers may read shared worker/control-plane storage for worker
lookup through storage contracts. They must not call engine/server APIs for
worker lookup, result finality, retry, release, or task state mutation.

Presence owner semantics are also part of the transport contract:

- `workerId` identifies the canonical shared presence record
- `connectionId` identifies the current live owner for that worker route
- `markOnline(...)` may install or replace the owner with a new
  `adapterId + routeKey + connectionId`
- `refreshHeartbeat(...)` and `markOffline(...)` must only mutate shared
  presence when the incoming `connectionId` still matches the stored owner
- stale heartbeat or disconnect events from an older connection must never
  revoke a newer active connection after reconnect or route takeover

## Model Boundaries

`TaskDispatchItem` is currently a hybrid dispatch payload:

- worker-facing payload fields: task id, message id, event code, input, shared config
- runtime metadata fields: worker id, worker-context id, batch id, internal attempt id

Dispatch input projection should be payload-shape driven. If transport needs to
unwrap a worker-facing wrapper such as the current SDK `type=json,data=...` or
`type=text,text=...` shapes, that decision must come from the dispatch payload
itself, not from unrelated task metadata like `_sdk.payloadType`.

Transport internals should prefer direct access to the hybrid's real owner
fields. Packet assembly, routing, and internal result correlation should read
`TaskDispatchItem` directly instead of rebuilding internal wrapper objects
around the same data. Add a derived view only when it carries a distinct protocol
or lifecycle boundary.

The internal attempt identity is intentionally exposed through `attemptId()`, not
`getAttemptId()`, so JSON serializers do not add it to worker API responses by
JavaBean convention. Do not add JavaBean getters for internal metadata unless
the worker wire contract is intentionally changed.

Do not split this hybrid opportunistically. That is a cross-adapter wire change
touching adapter codecs and external worker API behavior.

`TaskDispatchContext` is the current task-level dispatch snapshot passed through
the engine -> transport handoff seam. It freezes the task shell fields needed
for delivery payload assembly after assignment has already selected concrete
`TaskDispatchBinding` values. Transport should consume this snapshot instead of
depending on a live mutable `Task` reference across the handoff boundary.

`TransportPacket` is now the internal flat transport envelope for dispatch,
result, and worker-system-event shapes. In the current mainline, dispatch is
packet-backed first: `TransportDispatchEnvelope` carries a `TASK_DISPATCH`
packet plus runtime delivery identity. Adapters may read packet fields for
routing and frame assembly, but external worker wire behavior remains the
current JSON contract. `TransportPacket.payload` is a JSON object boundary,
not an arbitrary JVM object slot. Durable queue codecs must be able to
round-trip packet payloads without relying on Java-local runtime types.
Packet identity rules are type-specific and part of the transport contract:
`TASK_DISPATCH` requires `taskId`, `messageId`, and `eventCode`; `TASK_RESULT`
requires `taskId` and `messageId`; `WORKER_SYSTEM_EVENT` requires `eventCode`.
Allowed payload values are JSON-safe primitives plus nested JSON-safe object
or array shapes only: `String`, `Number`, `Boolean`, `null`,
`Map<String, Object>`, and lists/arrays composed from the same value set.
Transport must reject unsupported JVM-only objects at payload assembly time
instead of letting different codecs or queue implementations observe different
behavior.
`TaskDispatchItem` and `TaskResultReport` are the primary owners of their
transport payload views: they should freeze nested JSON-safe values once and
expose a stable payload map for packet assembly instead of rebuilding wrapper
maps repeatedly across hot paths.

`TransportResultEnvelope` is internal runtime metadata around a
`TaskResultReport`. `TaskResultReport` remains the protocol payload. Envelope
fields such as `routeKey`, `attemptId`, and `leaseToken` may be used by runtime
validation, but old workers that only submit `TaskResultReport` remain valid
until the security model explicitly changes. `routeKey` is the transport
address truth; enveloped result ingress must therefore carry non-blank
`adapterId + routeKey`. Adapter-local worker/session/connection identities are
local diagnostics only and do not belong on the shared result-envelope
mainline.

When envelope identity validation rejects stale attempt or lease evidence,
transport result ingress returns accepted-noop semantics: the envelope was
handled and intentionally not applied. Transport still does not decide retry,
finality, task terminal convergence, or public result read truth.

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
- transport bindings must declare their route-key resolver explicitly at
  assembly time; runtime must not hide `workerId -> routeKey` policy behind
  builder defaults or shared fallback helpers
- mainline polling/websocket/socket bindings currently resolve `routeKey` from
  worker id explicitly at binding assembly time; that is a current policy, not
  a transport-global invariant
- adapter ingress may also register a session with an explicit `routeKey`
  provided by handshake / hello metadata and fall back to `workerId` only when
  no route key is supplied
- realtime endpoint registries may still be keyed by worker id today, but
  their direct-send contract is route-based: send and online checks should
  speak in terms of `routeKey`, not imply that worker identity is the only
  valid address key
- blank `routeKey` is invalid for both queued delivery and direct-send delivery
- queue ownership and poll/drain isolation key off canonical `(adapterId, routeKey)`
- `routeKey` meaning is adapter-local; transport runtime must not reinterpret it as
  task, attempt, lease, or business routing truth
- route-only endpoint helpers may exist only inside one concrete adapter
  implementation; the shared runtime/registry contract must use adapter-scoped
  route operations rather than inferring ownership from endpoint snapshots
- worker-addressed debug/raw side-channels are not route truth; if they remain,
  they must first resolve one unique active `(adapterId, routeKey)` from
  endpoint state before adapter send, and the send contract itself should stay
  adapter-scoped rather than reviving route-only shared operations
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

Result ingest is a hot path. Validation may read runtime lease metadata plus a
bounded latest-attempt projection, but it must not load full task history or
scan all attempts. Storage implementations should provide bounded lookups for:

```text
(taskId, messageId) -> active runtime lease
(taskId, messageId) -> latest-attempt compatibility residue view
```

Dispatch is also a hot path. Delivery queues currently store
`TransportDispatchEnvelope` values and should avoid deep-copying task payload
maps beyond the immutable copies already owned by packet assembly. Retryable
dispatch outcomes must correlate by explicit `attemptId`; transport trace ids
are diagnostics and must not double as compensation keys.

Assignment-to-transport handoff is also part of the hot path. The embedded
runtime uses an in-memory `TaskDispatchHandoff` plus `TaskDispatchHandoffPump`;
split runtime uses Redis-backed handoffs with the same producer/consumer
contract. Single-queue Redis handoff remains available for simple split
deployments, while node-targeted Redis handoff is the multi-adapter mainline.
Do not reintroduce direct synchronous engine->transport callback coupling as a
parallel mainline, and do not treat any handoff queue as a second runtime ready
queue.

Runtime delivery stores must enforce explicit admission control. The current
in-memory store has both per-worker queue caps and a configurable total
queued-item cap; Redis or JDBC replacements should preserve equivalent
backpressure. `TransportDeliveryStoreStats` is queue/store-path only; direct-send
diagnostics are assembled above the store boundary by
`TransportDeliveryServiceStats`. Poll semantics must stay explicit enough to
distinguish delivered, empty, invalid-request, unavailable, and shutdown
results without forcing callers to treat every non-delivery outcome as an empty
queue. `TaskPullChannel.pollTaskMessagesResult(...)` is the transport mainline
for that statusful view; list-only pull helpers are convenience wrappers above
it. `DELIVERED` status must always carry one or more dispatch items/envelopes;
empty payload sets are `EMPTY`, not a second encoding of delivery. Thread interruption is not a store result contract; store
implementations should throw interruption and let callers handle it above the
store boundary. Store shutdown is
also part of the runtime contract: after shutdown the store rejects new
delivery, clears in-memory backlog, and wakes waiting pollers without changing
engine-owned task lifecycle state.

For Redis-ready queue diagnostics, treat the stats contract in two tiers:

- hard contract fields:
  `queuedItems`, `queueCount`, `maxQueuedItems`, and the per-adapter
  `queuedItems` / `queueCount` breakdown
- best-effort diagnostics:
  `waitingPollers`, `oldestQueuedAgeMillis`, `enqueuedItems`,
  `drainedItems`, `backpressureRejectedItems`, `invalidItems`,
  `unavailableItems`, `shutdownClearedItems`, and the per-adapter mirrors of
  those values

Best-effort diagnostics must remain meaningful, but future distributed queue
implementations are not required to preserve the exact local JVM waiter or
snapshot timing model of the current in-memory store.

Queue mechanics such as keyed FIFO storage, blocking poll coordination,
per-key/global admission, and queue snapshot counters may live under
`platform_infra` so long as transport semantics remain owned by
`TransportDeliveryStore`, `TransportDispatchEnvelope`, and `DispatchOutcome`.
Embedded runtime composition may choose between the default in-memory delivery
store and a Redis-backed transport delivery store, but that selection belongs
to SDK/starter assembly rather than transport-facing adapter contracts.
That assembly layer also owns queue-cap tuning such as total queued items and
per-route queued-item caps; transport contracts should consume those resolved
limits rather than hard-code runtime policy.

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
