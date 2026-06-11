# Transport Boundary Baseline

Last updated: 2026-06-11

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
- worker route-owner heartbeat evidence as a shared readable runtime view with
  one or more active consumer records addressed by opaque `routeKey` and
  carrying `adapterId`, `transportNodeId`, lease, and connection evidence in
  the owner value
- adapter registration and adapter selection by `adapterId`
- task dispatch delivery, queueing, draining, and dispatch outcomes
- task result ingress wrapping with transport metadata
- worker system-event ingress and egress for explicit worker lifecycle events;
  adapter session connect/disconnect/lease refresh does not decide worker
  online/offline

Engine remains the owner of task lifecycle:

- worker matching and runtime worker/resource locks or reservations
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
- `RouteTargetedTaskDispatchHandoff`: post-claim delivery queue between engine
  and transport. It carries already resolved `routeKey + adapterId` delivery
  domains plus the selected consumer's node-local drain lane and is not the
  runtime ready queue.
- `TaskPullResult`: explicit pull-path status plus delivered dispatch items;
  empty queue, invalid request, temporary unavailability, and shutdown must not
  be flattened into one fake "no work" result on the transport mainline
- `TransportRouteOwnerStore`: transport adapter write surface for route-owner claim,
  heartbeat refresh, and owner release; it does not expose dispatch routing or
  worker-id inspection reads
- `WorkerDispatchRouteOwnerView`: narrow read-only route-owner view used by
  engine/starter-side dispatch assembly after worker matching has already
  selected worker bindings
- `TransportRouteOwnerInspectionView`: worker-id projection view for SDK/operator
  inspection; it is not a dispatch routing dependency
- `WorkerSystemEventChannel`: transport-neutral ingress seam for worker
  connect/disconnect/heartbeat signals. It is not a worker command, worker
  state-report, or capability-report lifecycle owner.
- `AdapterNodeRecord`: worker registration endpoint and logical adapter
  deployment identity. It is not `transportNodeId`, not worker capability
  truth, and not a worker load or lease owner.
- `NodeGroupBindingRecord`: adapter-node to WorkerGroup hosting relation truth.
  It gates whether one node may host or drain a group, but it must not own
  event capability.

Avoid adding new transport model names unless they carry a distinct runtime
behavior that cannot fit one of these concepts.

Worker system events are intentionally narrow in the current baseline. Future
worker command, worker state-report, or worker capability self-report flows may
use transport ingress, but they must first define a separate owner that
validates, stores, projects, repairs, and exposes the resulting state. Transport
must not route command acknowledgements through task result ingest, treat state
reports as reachability truth, or mutate worker capability truth directly from
the system-event channel.

The completed worker-control owner-baseline roadmap is archived at
`../doc/archive/xa-mass-engine/2026-05-18_EVENT_AND_WORKER_CONTROL_ROADMAP.md`. Current
owner truth is in `../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md`.
Transport may carry command delivery later, but it must not own command
lifecycle state.

## Module Ownership

`transport_api` owns stable contracts used across adapters and runtime:

- adapter SPI
- transport-neutral dispatch/result/system-event interfaces
- endpoint registry contracts
- transport-neutral models that adapters must exchange with runtime
- canonical worker route-key codec contract for the WRB convergence target

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
- adapter dispatch from already resolved route-targeted handoff batches
- engine-to-transport dispatch handoff queue/store ownership after assignment;
  current embedded default wiring is an in-memory
  `RouteTargetedTaskDispatchHandoff`, while split runtimes use Redis
  route-targeted inboxes awakened by `transportNodeId`
- producer-side assembly submits immutable `TaskDispatchContext +
  List<TaskDispatchBinding>` batches into route-targeted delivery bindings;
  binding-level `workerId`, when present, remains the engine-selected execution
  constraint used to choose a concrete route consumer; transport consumers drain
  only pre-resolved `routeKey + adapterId` delivery targets and do not reselect
  workers or decode route-key minting rules
- worker transport-binding resolution from registered worker truth before
  dispatch handoff; transport consumers do not call worker-resource runtime for
  second-stage selection
- consumption of shared dispatch-ready/result-ingest seams from neutral runtime
  contracts rather than direct engine listener/package ownership

Concrete adapters own protocol I/O only:

- server/session/endpoint lifecycle for their protocol
- frame or request/response codec
- endpoint connect/disconnect/heartbeat lease refresh and route-owner evidence
  writes into transport-owned route evidence
- calls into runtime delivery and result-ingest contracts
- accept/read/write loops submitted through the runtime executor context when they block

Engine/starter dispatch assembly may read transport route-owner evidence after
worker selection has already produced concrete bindings. Transport owns only
route-owner heartbeat evidence, not worker online/offline lifecycle. Heartbeat
expiry is a transport lease rule, not an engine selector heuristic. Expired
owner evidence is not dispatchable; readers may derive unreachable/stale views
without transport persisting a status enum. Shared-store implementations such
as Redis must preserve the same route-owner semantics as the in-memory default.
One opaque `routeKey` may have multiple active consumer records.
`getLatestOwnerByWorker(workerId)`, `isWorkerReachable(workerId)`, and
`findRouteOwners(workerId)` are SDK/operator inspection projections derived
from route-owner truth; dispatch handoff must resolve active consumers by
opaque `routeKey`. Engine must not write presence, read adapter sessions, or
treat presence as a schedule owner.
Runtime assembly may keep the `TransportRouteOwnerStore` write surface for adapter
writes and shutdown ownership, but dispatch handoff assembly should bind only
`WorkerDispatchRouteOwnerView`; SDK/operator reads
should bind `TransportRouteOwnerInspectionView`.

## Worker Registration Relation Baseline

The current worker registration relation model is:

```text
AdapterNode
  -> NodeGroupBinding
      -> WorkerGroup
          -> Worker
```

Meanings:

- `AdapterNode`: worker registration endpoint, logical adapter deployment
  identity, callback scope, and node-level diagnostics.
- `NodeGroupBinding`: adapter node hosts WorkerGroup relation truth.
- `WorkerGroup`: capability cohort and `eventBindings` truth.
- `Worker`: platform dispatchable execution identity.

Transport remains a multi-protocol worker data plane. Polling, WebSocket, and
socket adapters are peer protocol adapters. `adapterId` is concrete adapter
runtime identity; `transportHint` is only a coarse family hint.
`routeKey`, `connectionId`, and `transportNodeId` are transport-owned
route-owner evidence.

Owner boundaries:

- `WorkerGroup.eventBindings` is the only worker capability truth.
- `AdapterNode`, `NodeGroupBinding`, and raw capability reports must not own a
  second event-capability model.
- `NodeGroupBinding.enabled=false` or `draining=true` blocks new work only for
  that adapter-node/group hosting relation; it does not delete capability.
- `Worker` remains the smallest schedulable execution identity. `Device`,
  `AccountSlot`, `AdapterNode`, and transport sessions must not replace it as
  the scheduling subject.
- `adapterId`, `adapterNodeId`, and `transportNodeId` are separate identities:
  adapter/protocol runtime identity, logical adapter deployment identity, and
  split-runtime route-owner node.
- Attributes such as `deviceId`, `accountId`, `phoneId`, `devicePool`, `route`,
  and `region` are scheduling evidence first. They must not silently create
  device owners, account-slot lifecycle, or implicit locks.

Resolved relation conflict:

- `WorkerGroupRecord.adapterNodeId` is not canonical relation truth.
- `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)` is not a current
  production relation surface.
- `WorkerGroupCompatibilityProjection` is retired from node/group relation
  ownership.
- If operator history or offline query needs adapter-node, group, route-owner,
  load, or reachability facts, emit trace/events and let async materialization
  build durable views. Do not reintroduce direct DB CRUD paths as runtime
  relation truth.

## Distributed Transport V1

The first split deployment is central engine plus independent transport JVMs.
It is queue-first and does not add server-owned transport ingress endpoints.
`xa-mass-server` remains a shell/API validation surface, not the transport data
plane owner.

The split runtime uses three transport/runtime channels:

- dispatch handoff: engine/starter assembly submits route-targeted delivery
  batches after claim, attempt creation, lease, and worker binding have already
  happened; transport drains only this small assigned window. Multi-process
  adapter mode uses `RouteTargetedTaskDispatchHandoff` route-domain queues with
  node-local drain lanes and per-`transportNodeId` ready indexes so each
  transport JVM is awakened only for route work it can currently serve.
- delivery inbox: transport routes dispatch envelopes into
  `TransportDeliveryStore` by opaque `routeKey`; `adapterId` remains
  delivery request metadata and diagnostics
- result/compensation inboxes: transport writes `TransportResultEnvelope`
  values and retryable dispatch-failure events to Redis-backed inboxes; the
  engine process drains those inboxes into its local result ingest and
  assignment compensation ports. Result lifecycle ownership is defined in
  [../doc/TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md).

These queues are runtime-state queues. They must be bounded and must preserve
backpressure instead of growing without limit. They also must not be treated as
durable task lifecycle truth. `TaskWorkRuntime` remains the only owner of ready
membership, delayed visibility, active lease, retry timing, result application,
and terminal convergence.

`RouteTargetedTaskDispatchBatch` is the process-boundary payload for dispatch
handoff. It contains `TaskDispatchContext` plus route-targeted bindings and
must stay JSON-safe. Large item bodies continue to use `payloadRef` when
needed; the handoff queue is not a copy of a million-item task queue.

Worker runtime state is not a queue. The shared worker view contains
route-owner records:

```text
routeKey, adapterId, transportNodeId, connectionId,
optional workerId, leaseExpireAt, updatedAt
```

Engine matching still selects a worker from control-plane registration,
capability, rule, lock, admission, and worker-runtime evidence. Only after
assignment has produced concrete bindings does dispatch assembly resolve
`routeKey + adapterId` and the binding-level selected worker constraint to
active route consumers, then write route-targeted batches to those consumers'
node-local drain lanes. Missing or expired route owners, or offline transport
nodes, go through engine-owned compensation/retry; transport does not
re-schedule or mutate task lifecycle.

SDK/starter assembly exposes three runtime roles:

- `EMBEDDED`: engine, local handoff pump, transport runtime, adapters, and local
  result ingest run in one JVM
- `ENGINE_PRODUCER`: engine runs and submits dispatch batches into the configured
  handoff; it drains result and dispatch-failure inboxes back into local engine
  ports, but does not start transport adapters
- `TRANSPORT_CONSUMER`: transport adapters, route-owner store, delivery store,
  and route-targeted handoff pump run without starting the engine; results and
  retryable dispatch failures are enqueued for engine-side draining

Transport consumers consume already resolved delivery targets. They must not
call worker runtime, engine, or server APIs for worker lookup, worker
selection, result finality, retry, release, or task state mutation.

Route-owner semantics are also part of the transport contract:

- `routeKey` identifies a worker-consumption address; transport treats it as
  opaque
- `workerId` is optional execution metadata attached to a route consumer
- `connectionId` identifies one live consumer connection for that route
- `claimRouteOwner(...)` may install or refresh a consumer value for the same
  `routeKey` with `adapterId + transportNodeId + connectionId`
- `refreshHeartbeat(...)` extends the matching consumer lease only
- `releaseRouteOwner(...)` removes the matching consumer only; it does not
  write an offline worker state
- stale heartbeat or disconnect events from an older connection must never
  revoke a newer active connection after reconnect or route takeover

## Redis Key Manifest

Transport Redis keys are runtime-state keys only. They are not worker runtime,
admission, scheduling, capacity, reservation, or task lifecycle truth. The
default component namespaces are:

| Component | Default namespace | Retained key families |
| --- | --- | --- |
| route-owner | `xa:mass:transport:route-owner:v1` | `route:<encodedRouteKey>:consumers`, `routes`, `deadline`, `worker-route:<workerId>` |
| delivery | `xa:mass:transport:delivery:v1` | `q:<routeKey>`, `meta:<routeKey>`, `queues`, `stats` |
| nodes | `xa:mass:transport:nodes:v1` | transport-node owner/heartbeat records and deadlines |
| dispatch-route | `xa:mass:transport:dispatch-route:v1` | `route:<encodedRouteKey>:node:<encodedTransportNodeId>:q`, `routes`, `node:<transportNodeId>:ready-routes` |
| result-inbox | `xa:mass:transport:result-inbox:v1` | engine-drained result inbox entries |
| dispatch-failure | `xa:mass:transport:dispatch-failure:v1` | engine-drained retryable dispatch-failure entries |

Route-owner truth is keyed by opaque route and consumer id. Redis stores a
route consumer hash plus deadline index so one opaque `routeKey` can have
multiple active consumer records. `worker-route:<workerId>` is a
transport-derived SDK/operator projection from worker id to the latest known
route key. It must not be treated as worker metadata truth or dispatch truth.

Forbidden transport key families:

- route-owner-side `workers`, `owner-shards`, `worker-routes:*`, and
  `route-presence:*`
- transport-owned worker capacity, reservation, active lease, dispatch gate,
  event-binding ceiling, `group:{groupId}:slots`, `worker:meta:*`, or
  `worker:occupancy:*` keys

Worker runtime aggregates such as `group:{groupId}:slots` remain worker runtime
truth. Transport may read route owner and node owner state; it must not preserve
or derive scheduling/admission truth in its Redis keyspace.

## Model Boundaries

`TaskDispatchItem` is currently a hybrid dispatch payload:

- worker-facing payload fields: task id, message id, event code, input, shared config
- runtime metadata fields: worker id, optional legacy worker-context id, batch
  id, internal attempt id

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
address truth; enveloped result ingress must therefore carry a non-blank
route key when route-owner evidence is enforced. Adapter-local
worker/session/connection identities are local diagnostics only and do not
belong on the shared result-envelope mainline.

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
- `routeKey`: opaque transport delivery address

Current runtime rules:

- `adapterId` is canonicalized by trim + lowercase
- `routeKey` is canonicalized by trim only; case is preserved
- route-key assembly is outside transport runtime. SDK/starter currently uses
  `CanonicalWorkerGroupRouteKeyCodec` as the default worker-group consumption
  route rule from `workerGroupId`; a future assembly policy may mint a route
  from `adapterId`, worker group, or another lane key. Transport
  runtime/adapters must not import or hard-code any of those rules.
- transport bindings must declare their route-key resolver explicitly at
  assembly time; runtime must not hide `workerId -> routeKey` policy behind
  builder defaults or shared fallback helpers
- mainline polling/websocket/socket bindings use the resolver injected by
  runtime assembly
- adapter ingress must receive an explicit routeKey; public managed SDK
  sessions generate that key from worker group. Handshake / hello fallback to
  raw worker id is not a target path.
- realtime endpoint registries may still be keyed by worker id today, but
  their direct-send contract is route-based: send and online checks should
  speak in terms of `routeKey`, not imply that worker identity is the only
  valid address key
- blank `routeKey` is invalid for both queued delivery and direct-send delivery
- queue ownership and poll/drain isolation key off opaque `routeKey`;
  `adapterId` remains delivery request metadata, not queue identity
- for worker delivery, `routeKey` is the transport delivery address; transport
  runtime must not reinterpret it as task, attempt, lease, worker-group, worker,
  or business routing truth
- route-only endpoint helpers may exist only inside one concrete adapter
  implementation; the shared runtime/registry contract must use adapter-scoped
  route operations rather than inferring ownership from endpoint snapshots
- worker-addressed debug/raw side-channels are not route truth; if they remain,
  they must first resolve the current route owner for the selected `routeKey`
  before adapter send, and the send contract itself should stay adapter-scoped
  rather than reviving route-only shared operations
- future Redis/JDBC queue replacements must preserve the same opaque addressing
  rules and must not require hot-path scans to recover queue ownership

WRB convergence note:

- `CanonicalWorkerGroupRouteKeyCodec` names the current SDK/starter default
  route-key mint rule: route identity is minted from `workerGroupId`. This is
  not a transport runtime rule; adapters and shared runtime code receive
  explicit route keys or injected resolvers.
- `routeKey` locates a delivery domain. Binding-level `workerId`, when present,
  remains the selected execution identity and adapter delivery constraint within
  that domain.
- Redis-backed route-owner state uses route-key consumer hashes plus deadline
  indexes; `currentOwners(routeKey)` is a bounded read.
- Delivery queues are routeKey-owned. Adapter change for the same routeKey does
  not strand already queued envelopes in an old adapter-specific queue.
- `adapterId`, `transportNodeId`, and `connectionId` remain delivery-owner
  evidence. They must stay in owner values or queue metadata, not become the
  route-key minting rule.

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
runtime uses an in-memory `RouteTargetedTaskDispatchHandoff` plus
`RouteTargetedTaskDispatchHandoffPump`; split runtime uses Redis-backed
route-targeted handoffs with the same producer/consumer contract. Do not
reintroduce direct synchronous engine->transport callback coupling as a
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
  `queuedItems`, `queueCount`, and `maxQueuedItems`
- best-effort diagnostics:
  `queueByAdapter` legacy breakdown, `waitingPollers`,
  `oldestQueuedAgeMillis`, `enqueuedItems`,
  `drainedItems`, `backpressureRejectedItems`, `invalidItems`,
  `unavailableItems`, `shutdownClearedItems`, and nested breakdown mirrors of
  those values

`queueByAdapter` keeps its legacy field name for existing diagnostics, but it
is not queue ownership truth. RouteKey-owned Redis queues may aggregate that
field under a route-owner bucket instead of preserving adapter-specific queue
identity. Best-effort diagnostics must remain meaningful, but future
distributed queue implementations are not required to preserve the exact local
JVM waiter or snapshot timing model of the current in-memory store.

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
  are queue-path diagnostics only; `queueByAdapter` is not canonical queue ownership truth
- direct-send counters are separate transport diagnostics and must not appear as
  synthetic queue occupancy or queue ownership
- do not add a transport-owned retry/lease/state machine that merges direct-send
  and queued delivery into one second attempt lifecycle

Observability rule:

- use logs, traces, queue stats, and indexed runtime lookups for diagnosis
- do not add full-history, full-task, or full-queue scans to transport hot paths
- delivery submission, result ingest, and shutdown handling should remain safe under duplicate or repeated calls
