# Transport Boundary Baseline

Last updated: 2026-06-23

Status: current transport boundary baseline.

Trust order for transport decisions: code, verified runtime behavior, this
baseline, then design/reference or historical notes.

This file freezes the current transport boundary so transport does not turn
into a second task engine.

Transport is now a production data plane, not an adapter experiment. Favor
throughput, availability, bounded admission, and idempotent runtime behavior
over richer but expensive observability state.

## Scope

Transport owns two runtime lanes for worker connectivity and dispatch:

1. Network/session evidence production.
2. Best-effort assigned-delivery execution.

These lanes share adapter observations, endpoint/session evidence, and mailbox
facts, but they must not collapse into one lifecycle owner. The evidence lane
produces transport-local endpoint freshness and confirmed current-session loss
signals; the assigned-delivery lane is the pure delivery executor.

Transport owns network/session evidence mechanics:

- worker endpoint connectivity and endpoint metadata
- worker endpoint lease evidence keyed by `deliveryBucketId + workerId`.
  Endpoint leases contain adapter/runtime/session facts for current delivery
  feasibility, while timestamps stay in store deadline indexes or
  policy-specific consumer evidence instead of the general view record.
- endpoint/session evidence for adapter connect/disconnect/heartbeat
  observations. Connected and heartbeat observations remain transport-local.
  Confirmed current-session disconnect may cross assembly as negative dispatch
  block evidence, while endpoint lease refresh does not decide worker
  lifecycle, state-report, capability, slot heartbeat, or positive eligibility
  recovery truth

Network/session evidence is system hygiene state, not message delivery. It
must remain bounded and self-cleaning through finite lease deadlines, explicit
release, or local active-session refresh. A refresher is valid only when its
owner is narrow, its source is bounded local state, and its sink is typed
evidence; it must not grow into worker lifecycle, adapter health, reconnect,
failover, scheduling, retry, or operator diagnostics truth.

Transport owns assigned-delivery mechanics:

- local adapter binding registration and final-hop adapter resolution by
  transport-owned adapter identity
- task dispatch delivery, queueing, draining, and dispatch outcomes

Transport owns result-ingress carrier mechanics:

- task result ingress queuing and relay through opaque routing envelopes

Engine remains the owner of task lifecycle:

- worker matching and runtime worker/resource locks or reservations
- `TaskMsgAttempt` creation, lease, active-attempt truth, and closure
- `TaskMsg` status transitions, retry, release, and terminal policy
- lifecycle security decisions that mutate task state

Transport core and concrete adapters are separate layers. Transport core owns
the endpoint/session evidence lane, assigned-delivery executor contract,
delivery queue/store mechanics, result ingress inboxes, and delivery outcomes.
Concrete adapters own protocol I/O, local session indexes, protocol frame
parsing and writing, session evidence observation, and final-hop send attempts
for already selected workers. Current embedded Java adapters are one deployment
shape, not the transport contract. Facts that a future remote adapter process
cannot provide through typed delivery commands, endpoint lease evidence, result
ingress envelopes, delivery outcomes, diagnostics, or session/availability
observations must stay in embedded adapter support rather than
transport-neutral APIs.

## Stable Concepts

Transport should stay centered on these concepts only:

- `AdapterCommandExecutor.dispatch(List<DispatchMessage>)`: embedded Java
  adapter command execution callback returning `DispatchOutcome`. The executor
  owns only the local final-hop attempt and lives with runtime embedded adapter
  support, not in `transport_api`. Adapter id, transport hint, protocol label,
  pull channel, protocol resource start/stop, diagnostics, and raw/manual
  side-channels are binding or contribution metadata, not executor facts. This
  SPI is not the remote-adapter contract; transport core must not require executor-local
  connection/session classes or adapter-owned registries.
- `AdapterCommandExecutors.perMessage(...)`: runtime embedded-support template
  for push adapters whose final hop is one selected-worker send attempt per
  `DispatchMessage`. It owns only reusable batch/null-item/send-true/send-false/
  exception to `DispatchOutcome` normalization. Concrete push adapters still
  own protocol frame encoding and selected-worker session send.
- `TransportBinding`: explicit runtime binding for adapter id, adapter mailbox
  key, transport hint, protocol label, optional pull channel, and pull-session
  evidence driver. A pull-capable binding must provide both the pull channel
  and the evidence driver; it must not read adapter metadata back from the
  command executor.
- `TransportAdapterContribution`: explicit adapter bootstrap output for
  contributed bindings, managed adapters, servers, raw/manual channels, and
  diagnostics. Runtime input context and adapter-produced outputs must not share
  mutable single-slot state.
- `TransportAdapterBootstrapContext`: embedded adapter support capability
  surface. It exposes host assignment, mailbox, session evidence, result
  ingress, and host-resource capabilities. Concrete adapters must not receive
  raw endpoint lease stores, worker-runtime scheduling mutation surfaces,
  mailbox registries, dispatch handoff internals, or generic delivery services
  through this surface.
- `EmbeddedAdapterHostSet` / `EmbeddedAdapterContributionHost`: current
  embedded Java adapter host-support classes around one or more adapter
  contributions. Their stable role is host mounting: start/stop
  contribution-owned managed resources and servers with the application without
  making one binding own shared protocol resources.
  They are not adapter health, restart, migration, or failover lifecycle
  owners.
- `MailboxConsumerAvailabilityPublisher`: current embedded host-support class for narrow
  mailbox consumer availability claim, refresh, and release for one
  command-delivery binding. This is queue-safety evidence only. It does not own
  dispatch queues, selected-worker session maps, task result decode, endpoint
  lease projection, worker scheduling, concrete protocol state, adapter health,
  restart, migration, or mailbox placement policy.
- `PollingDeliveryExecutor`: polling adapter command executor. It owns only
  `DispatchMessage` enqueue into the polling delivery buffer and dispatch
  outcome normalization/logging. It must not own pull polling, endpoint lease
  projection, worker dispatch eligibility, or mailbox consumer availability.
- `PollingDeliveryPullChannel`: polling adapter pull-channel implementation. It
  owns only worker pull-buffer demux by adapter mailbox plus `selectedWorkerId`
  and status mapping into `DeliveryPullResult`. It must not own command
  execution or endpoint/session evidence.
- `PullSessionEvidenceDriver`: embedded runtime seam consumed by SDK
  `EmbeddedPullWorkerSession` for connect/heartbeat/disconnect evidence projection.
  The polling implementation is `PollingSessionEvidenceDriver`, which delegates
  to runtime publishers instead of exposing raw stores or registries to the SDK
  session.
- `TransportEndpointLeasePublisher`: runtime owner that projects adapter
  session facts into endpoint lease evidence. Concrete adapters should not
  duplicate endpoint lease record construction. Mailbox-level handoff consumer
  availability is claimed by embedded adapter host support when the handoff
  requires it, not by endpoint lease projection and not as adapter lifecycle
  truth.
- `CurrentSessionDisconnectSink`: runtime assembly sink for confirmed
  current-session loss. `AdapterSessionEvidencePublisher` writes transport
  endpoint lease evidence first; only a successful current endpoint release may
  call this sink. The sink is narrow negative evidence, not a generic worker
  presence event channel.
- `DispatchOutcome`: adapter-neutral delivery result, never task-lifecycle truth
  and the single retryable delivery-failure fact owner. It carries stable
  delivery identity, selected worker, opaque delivery correlation, status,
  retryability, reason, and time. It must not expose adapter id, delivery queue
  key, route key, connection id, endpoint lease evidence, or task-shaped
  message/attempt fields.
- `DispatchMessage`: assigned-item delivery carrier inside an
  adapter-mailbox dispatch batch. It carries delivery id, `selectedWorkerId`,
  opaque worker payload, opaque delivery correlation, deadline, and creation
  timestamp. Task shell metadata, delivery bucket, adapter, lane, target node,
  endpoint lease, connection, session, packet, and structured task payload
  facts are not item fields.
- `AdapterMailboxDispatchBatch`: producer/serialized dispatch handoff record for one
  adapter mailbox. It carries `adapterMailboxKey` and
  flat `DispatchMessage` values. Handoff implementations store item values
  under the mailbox queue and expose bounded destructive mailbox poll. There is
  no assigned-dispatch claim wrapper, ack, visibility timeout, or requeue owner
  in transport.
- Polling pending pull-buffer values use `DispatchMessage` directly and
  project to `PulledDeliveryMessage` only at the pull API boundary. This buffer
  is owned by `polling-adapter`, not transport core. It does not serialize
  packets, routeKey, endpoint evidence, deliveryQueueKey, taskName, project,
  userId, or task payload fields as transport-owned facts.
- `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)`: opaque
  result ingress carrier. Transport may buffer, enqueue, and diagnose it, but
  task-shaped payload parsing and result correctness belong above transport.
- `AdapterResultIngressEntries`: runtime helper for result-ingress carrier
  construction after a concrete adapter has parsed protocol-local frames into
  result correlation, opaque payload, and diagnostics. It owns generated result
  message id, default deadline/timestamp, diagnostics copying, and required
  correlation/payload validation; it does not parse result payloads.
- `TransportResultIngressChannel` / `TransportResultIngressHandler` /
  `TransportResultIngressOutcome`: result ingress producer/consumer seams and
  ackability outcome used by local buffers and Redis inbox pumps.
- `TransportDispatchHandoff`: best-effort dispatch queue between
  engine/starter assembly and transport. Producers offer
  `AdapterMailboxDispatchBatch(adapterMailboxKey=<key>, items)` after
  worker-runtime delivery target evidence resolves the already selected worker
  to an adapter mailbox. Handoff implementations own bounded queue admission,
  destructive mailbox poll, mailbox consumer availability evidence, and
  availability/backpressure outcomes. Mailbox consumer availability is finite
  queue-safety proof refreshed by adapter-owned mailbox consumers; it is not an
  adapter health monitor, lifecycle truth, or recovery owner.
- Adapter-owned mailbox consumers are embedded-support contributions owned by
  concrete adapter bootstraps. A consumer polls one mailbox through
  `TransportDispatchHandoff`, invokes the local `AdapterCommandExecutor`, and
  emits retryable delivery failure evidence for known final-hop failures. There
  is no production global dispatch pump/listener or central mailbox mount.
- `DeliveryPullResult`: explicit transport pull-path status plus delivered
  `PulledDeliveryMessage` items. SDK/server worker polling projects those
  messages into `WorkerAction` and `WorkerPollResult`; task-shaped pull DTOs
  must not live in transport core. Empty queue, invalid request, temporary
  unavailability, and shutdown must not be flattened into one fake "no work"
  result on the transport mainline.
- `TransportEndpointLeaseStore`: transport adapter write surface for current
  endpoint lease claim, heartbeat refresh, and release. Claims carry
  `deliveryBucketId` and `workerId` explicitly; the store must not infer a
  bucket from adapterId, routeKey, connection/session id, or worker id.
- `TransportEndpointLeaseView`: narrow diagnostic read surface for current
  `deliveryBucketId + workerId` endpoint metadata. General view records do not
  expose lease deadlines; endpoint lease stores do not own dispatch
  handoff consumer leases.
- Transport/adapters do not own worker dispatch eligibility recovery. They may
  only produce best-effort negative observations through delivery outcomes or
  current-session disconnect. SDK/starter assembly bridges confirmed
  current-session disconnect observations to worker-runtime block records; it
  must not expose clear-capable gate APIs to transport or concrete adapters.
  Final-hop `NO_ENDPOINT`, pre-transport missing target, mailbox unavailable,
  backpressure, invalid input, shutdown, and generic failed outcomes remain
  delivery outcomes/failure evidence in the current roadmap.
- `WorkerGroup`: capability declaration and scheduling entry boundary. It owns
  project/event capability truth through event bindings.
- `Worker`: selected execution identity plus scheduling evidence. Worker rows
  must not self-declare project/event capability outside WorkerGroup bindings.
- Adapter endpoint/session evidence: transport-owned final-hop connectivity
  state. It may prove whether the already selected worker currently has a
  deliverable endpoint, but it must not expand worker capability or choose a
  different worker.

Avoid adding new transport model names unless they carry a distinct runtime
behavior that cannot fit one of these concepts.

There is intentionally no transport-to-worker-runtime session-presence ingress
in the current baseline. Future worker command, worker state-report, or worker
capability self-report flows may use transport ingress, but they must first
define a separate owner that validates, stores, projects, repairs, and exposes
the resulting state. Transport must not route command acknowledgements through
task result ingest, treat state reports as reachability truth, mutate worker
capability truth directly from adapter session events, or let
`TransportEndpointLeaseStore` claim/refresh/release currentness drive worker
presence, slot heartbeat, or positive dispatch eligibility recovery.

The completed worker-control owner-baseline roadmap is archived at
`../doc/archive/xa-mass-engine/2026-05-18_EVENT_AND_WORKER_CONTROL_ROADMAP.md`. Current
owner truth is in `../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md`.
Transport may carry command delivery later, but it must not own command
lifecycle state.

The assigned-delivery executor depends on worker-runtime delivery target
evidence, but it does not produce that projection while dispatching. The
evidence lane may publish adapter session observations and endpoint/session
evidence that worker-runtime projects into reachability and
`selectedWorkerId -> adapterMailboxKey`; the delivery lane consumes the already
resolved `selectedWorkerId + adapterMailboxKey + payload` command. This keeps
transport from becoming either a worker scheduler or a post-assignment routing
engine.

## Lifecycle Discipline

Transport and adapters are transmission owners by default, not lifecycle policy
owners. They may observe protocol sessions, maintain local resource state,
publish endpoint/session evidence, publish mailbox consumer availability when a
handoff requires it, and return delivery outcomes. They must not infer or own
worker lifecycle, adapter health lifecycle, adapter restart/failover/migration,
task retry, compensation, final recovery, or scheduling policy.

Embedded adapter host support may start resources with the application and close
them when the application stops. It must not become an adapter supervisor or
state machine owner. If future external adapters need monitoring,
reconciliation, takeover, migration, or health management, that work needs a
separate owner and side-channel contract. Do not mix those loops into command
drain, final-hop send, endpoint lease writes, session stores, or result ingress.

Stats, list, count, inspect, and watch APIs are diagnostics only. They must not
be used as delivery correctness proof, scheduling input, lifecycle truth, or
hot-path recovery logic.

## Module Ownership

`transport_api` owns stable contracts used across adapters and runtime:

- transport-neutral dispatch/result/session-evidence interfaces
- transport-neutral models that adapters must exchange with runtime
- canonical worker route-key codec contract for the WRB convergence target

`transport_runtime` owns runtime-only coordination:

- embedded Java adapter command callback, binding assembly, and host-support
  wiring
- host-assigned adapter mailbox key capability for embedded adapter bootstraps
- adapter binding and registration resolution
- canonical adapter-id resolution; old aliases such as `ws`, `pull`, `queue`, or `tcp-socket` are not adapter identities
- canonical transport-hint resolution; adapter labels such as `websocket`,
  `ws`, `push`, `pull`, or `queue` are not family aliases
- dispatch handoff queue/store and mailbox-scoped embedded host drain
- runtime-owned envelope identity/timestamp generation for queued dispatch
  handoff
- dispatch handoff admission control and polling-adapter-owned pending buffer
  configuration
- adapter-facing host executor capability for transport-owned blocking work
- result ingress envelope queueing, buffering, and runtime logging; result
  payload decoding, correlation, and lease/attempt validation live in
  SDK/starter or engine-owned result code
- core dispatch handoff bounded admission and destructive mailbox poll
  semantics. Current embedded assembly drains flat dispatch items through
  adapter-owned mailbox consumers, not a starter-owned global pump/listener or
  central mount. Known final-hop failures are emitted as failure evidence; if
  evidence emission fails after destructive poll, engine task-attempt timeout is
  the recovery path.
- engine-to-transport dispatch handoff queue/store ownership after assignment;
  current embedded default wiring is an in-memory `TransportDispatchHandoff`,
  while split runtimes use Redis adapter-mailbox dispatch queues plus mailbox
  consumer availability evidence
- producer-side starter assembly translates immutable `TaskDispatchContext +
  TaskDispatchBinding` assignment facts into flat `DispatchMessage` values.
  The binding-level worker-group context remains `deliveryBucketId`, and
  binding-level `workerId` becomes `selectedWorkerId`, the engine-selected
  execution constraint. Starter owns worker-payload encoding and opaque
  correlation minting; transport copies those values without decoding task
  item structure. Delivery integration resolves `selectedWorkerId` through
  worker-runtime delivery target evidence to an opaque `adapterMailboxKey`.
  Starter does not write adapter, node, route, connection, session, or packet
  facts into command items. Transport consumers drain referenced mailbox
  commands, dispatch by selected worker, and do not reselect workers or decode
  route-key minting rules.
- worker transport-binding resolution from registered worker truth before
  dispatch handoff; transport consumers do not call worker-resource runtime for
  second-stage selection
- consumption of shared dispatch-ready/result-ingest seams from neutral runtime
  contracts rather than direct engine listener/package ownership

Concrete adapters own protocol I/O only:

- protocol connection/session open/close resources for their protocol
- frame or request/response codec
- public worker-channel wire DTO/JSON codec consumption when the adapter uses a
  multiplexed worker protocol. `WorkerChannelFrame` field/kind vocabulary and
  string JSON codec live in `sdk/xa-mass-public-contract`; WebSocket owns only
  WebSocket/JsonObject glue around that contract.
- endpoint connect/disconnect/heartbeat lease refresh into
  transport-owned endpoint lease evidence through adapter-session evidence
  publisher capabilities
- calls into runtime delivery and result-ingest contracts through narrow
  adapter bootstrap capabilities, not broad runtime owner surfaces; concrete
  adapters consume host-assigned mailbox keys and do not mint mailbox keys from
  adapter id or protocol values themselves
- accept/read/write loops submitted through the runtime executor context when they block
- concrete adapter dependencies must match adapter ownership. A concrete adapter
  may depend on transport contracts/support, public worker-channel DTOs/codecs,
  and actually used protocol libraries. It must not reverse-depend on embedded
  SDK API, Java SDK runtime, base domain exception/model taxonomy, Redis
  clients, or stale protocol implementation libraries.

Adapter process identity is intentionally narrow. `adapterId` identifies a
concrete transport binding for final-hop executor/channel resolution after
endpoint evidence exists. It is not a worker API field, not a queue selector,
not a route-key mint rule, and not a Scheduling Plane input. Future remote
adapter registration may make that identity process-scoped, but it still must
remain connectivity evidence rather than worker capability or assignment truth.

Transport-owned final-hop delivery submitters do not read endpoint/session
target hints after worker selection has already produced concrete bindings.
Transport owns only endpoint/session lease evidence and mailbox handoff
consumer evidence, not worker online/offline lifecycle.
Heartbeat expiry is a transport lease rule, not an engine selector heuristic.
Expired endpoint or mailbox consumer evidence is not dispatchable; missing or
stale evidence is a retryable delivery-failure input, not permission to reselect
workers or mark workers offline. Shared-store implementations such as Redis
must preserve the same endpoint lease semantics as the in-memory default. Only
one current endpoint lease may own a given `(deliveryBucketId, workerId)` pair;
dispatch handoff consumer availability decides which local consumer may
drain one adapter mailbox.
Active protocol sessions may refresh endpoint leases through adapter-owned
evidence refreshers such as `WebSocketSessionEvidenceRefresher`; those
refreshers are bounded hygiene for currently indexed local sessions, not worker
online/offline truth, scheduling strategy, adapter health, reconnect/failover,
or message reliability.
SDK/operator worker inspection must not read endpoint lease projections;
it reads worker runtime lifecycle state. Engine and SDK inspection must not
write worker presence from adapter sessions, read adapter sessions, or treat
transport presence as a schedule owner.
Runtime assembly keeps `TransportEndpointLeaseStore` for adapter/session writes
and shutdown ownership. Assigned-delivery producer and listener code do not use
endpoint lease view lookup as a routing engine.

## Worker, Adapter, And Delivery Boundary

For owner decisions, the current mainline boundary is:

```text
WorkerGroup capability
  -> Worker execution identity and scheduling evidence
      -> Adapter endpoint/session lease evidence for final-hop delivery
```

Meanings:

- `WorkerGroup`: event capability declaration and worker scheduling-universe
  entry boundary.
- `Worker`: platform dispatchable execution identity plus attributes, load,
  state, and admission evidence.
- `Adapter`: worker network/session/endpoint-lease carrier for local final-hop
  delivery. It is not a capability owner and not a worker selector.

Transport remains a multi-protocol worker data plane. Polling, WebSocket, and
socket adapters are peer protocol adapters. `adapterId` is an internal concrete
adapter binding identity used after endpoint lease resolution, not an external
worker API, worker capability fact, or engine/starter worker-selection input;
`transportHint` is only a coarse family hint. `routeKey`, `connectionId`,
endpoint lease ids, session handles, and `deliveryQueueKey` are transport-owned
endpoint or queue evidence and must not leak through worker API, worker SDK
dispatch items, diagnostics summaries, or `DispatchOutcome`.

`AdapterNode` / `NodeGroupBinding` surfaces may still appear in worker
registration and topology read models. Treat them as control-plane relation
evidence only. They are not transport runtime process identity, not final-hop
delivery identity, and not event-capability truth.

Owner boundaries:

- `WorkerGroup.eventBindings` is the only worker capability truth.
- Worker rows, adapter endpoint leases, adapter-node topology facts, and raw
  capability reports must not own a second event-capability model.
- relation-level disabled/draining evidence, where still present, may constrain
  new work only through worker-runtime scheduling evidence; it does not delete
  WorkerGroup capability and does not become transport delivery identity.
- `Worker` remains the smallest schedulable execution identity. `Device`,
  `AccountSlot`, adapter topology, and transport sessions must not replace it as
  the scheduling subject.
- `adapterId`, `routeKey`, `connectionId`, endpoint lease ids, session handles,
  and delivery queue keys are transport implementation facts. Engine and
  scheduling code must not select workers by these identifiers. If delivery
  reachability affects scheduling, it must be projected as worker-runtime
  evidence first.
- Attributes such as `deviceId`, `accountId`, `phoneId`, `devicePool`, `route`,
  and `region` are scheduling evidence first. They must not silently create
  device owners, account-slot lifecycle, or implicit locks.

Resolved relation conflict:

- `WorkerGroupRecord.adapterNodeId` is not canonical relation truth.
- `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)` is not a current
  production relation surface.
- `WorkerGroupCompatibilityProjection` is retired from node/group relation
  ownership.
- If operator history or offline query needs adapter-node, group, endpoint,
  load, or reachability facts, emit trace/events and let async materialization
  build durable views. Do not reintroduce direct DB CRUD paths as runtime
  relation truth.

## Distributed Transport V1

The first split deployment is central engine plus independent transport JVMs.
It is queue-first and does not add server-owned transport ingress endpoints.
`xa-mass-server` remains a shell/API validation surface, not the transport data
plane owner.

The split runtime uses three transport/runtime channels:

- dispatch handoff: engine/starter assembly submits flat dispatch batches
  after claim, attempt creation, lease, and worker binding have already
  happened; transport drains only this small assigned window. Delivery
  integration resolves the already selected worker to an opaque
  `adapterMailboxKey` through worker-runtime delivery target evidence and
  submits `AdapterMailboxDispatchBatch(adapterMailboxKey=<key>, items)`.
  Multi-process adapter mode stores flat items under that mailbox queue and wakes
  only the mailbox consumer with current availability proof.
  The consumer boundary destructively polls flat items by mailbox; there is no
  assigned-dispatch inflight claim, ack, requeue, lane, or target-node fact.
  Redis dispatch queue ownership is not routeKey cardinality.
- polling pending pull buffer: polling adapter routes `DispatchMessage`
  values into a polling-adapter-owned buffer by adapter mailbox plus the
  engine-selected `selectedWorkerId`. The mailbox key is supplied by buffer
  context, not repeated in every value. `routeKey` remains opaque
  endpoint/result metadata and must not be the only isolation key for assigned
  polling task delivery.
- result/compensation inboxes: transport writes opaque
  `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)` values and
  retryable delivery-failure events to Redis-backed inboxes; the engine process
  drains those inboxes into starter-owned result callback decoding and
  engine-owned result ingest and assignment compensation ports. Result lifecycle
  ownership is defined in
  [../doc/TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md).

These queues are runtime-state queues. They must be bounded and must preserve
backpressure instead of growing without limit. They also must not be treated as
durable task lifecycle truth. `TaskWorkRuntime` remains the only owner of ready
membership, delayed visibility, active lease, retry timing, result application,
and terminal convergence.

`AdapterMailboxDispatchBatch` is the producer and process-boundary handoff payload.
It contains one `adapterMailboxKey` and flat `DispatchMessage`
values. The Redis/process-boundary codec stores item values under the mailbox
queue and serializes mailbox facts once; per-item records must not repeat
`adapterId`, `deliveryQueueKey`,
`deliveryBucketId`, `targetTransportNodeId`, `routeKey`, `connectionId`, or
`connectionToken`, and must not wrap another encoded task-dispatch batch string
such as `taskBatchJson`. Large item bodies continue to use `payloadRef` when
needed; the handoff queue is not a copy of a million-item task queue.

Worker runtime state is not a queue. Transport endpoint lease state contains
current endpoint metadata for a concrete delivery bucket and worker:

```text
deliveryBucketId, workerId, endpointDriverId, sessionHandle, endpointLeaseId
```

Engine matching still selects a worker from control-plane registration,
capability, rule, lock, admission, and worker-runtime evidence. Only after
assignment has produced a concrete selected worker does delivery integration
resolve `selectedWorkerId -> adapterMailboxKey` from worker-runtime evidence and
submit flat dispatch items to the adapter-mailbox dispatch queue. Mailbox consumer
wakeup is owned by the handoff through finite mailbox availability evidence;
adapter/session connection handles stay inside adapter-owned endpoint
registries. Missing or expired mailbox availability goes through engine-owned
compensation/retry; transport does not re-schedule or mutate task lifecycle.

SDK/starter assembly exposes three runtime roles:

- `EMBEDDED`: engine, local dispatch handoff, transport runtime,
  embedded adapter host set, adapters, and local result ingest run in one JVM.
  Command drain runs through adapter-owned mailbox consumers, not a
  starter-owned global pump.
- `ENGINE_PRODUCER`: engine runs and submits flat dispatch batches into the
  configured handoff; it drains result and delivery-failure inboxes back into
  local engine ports, but does not start transport adapters
- `TRANSPORT_CONSUMER`: transport adapters, endpoint lease store, delivery
  store, embedded adapter host set, and dispatch handoff consumers run
  without starting the engine; results and retryable delivery failures are
  enqueued for engine-side draining. Command drain runs through adapter-owned
  mailbox consumers.

Transport consumers consume already resolved delivery targets. They must not
call worker runtime, engine, or server APIs for worker lookup, worker
selection, result finality, retry, release, or task state mutation.

Endpoint lease semantics are also part of the transport contract:

- `routeKey` is opaque connection address, coarse delivery-domain metadata, or
  protocol correlation; transport treats it as opaque and must not require
  routeKey uniqueness for wrong-worker prevention
- `deliveryBucketId` is upstream scheduling/index context supplied by
  engine/starter or adapter session context. It is opaque to transport and is
  not adapter identity, route-key syntax, worker-runtime scheduling truth, or
  the physical dispatch queue owner.
- `workerId` is execution identity attached to endpoint lease evidence; after
  engine assignment the same value is carried as `selectedWorkerId`, the
  delivery constraint used with `deliveryBucketId`.
- producer-side assigned delivery must not call endpoint lease lookup to choose
  a queue, node, adapter, route, or connection; delivery integration consumes
  worker-runtime delivery target evidence to resolve the selected worker to an
  opaque `adapterMailboxKey`
- `currentEndpointLease(deliveryBucketId, workerId)` is a narrow diagnostic
  view for current endpoint metadata. It is not worker lifecycle truth, not a
  scheduling view, and not producer-side queue selection.
- `endpointLeaseId` identifies one live endpoint lease/session for that worker
  in the bucket; stale heartbeat or disconnect events from an older lease must
  never revoke a newer active endpoint after reconnect or endpoint takeover.
- `claimEndpointLease(...)` replaces the current endpoint lease for exactly
  one `deliveryBucketId + workerId` pair. It does not claim dispatch
  handoff consumer evidence.
- `refreshEndpointLease(...)` extends only the matching current endpoint lease.
- `releaseEndpointLease(...)` removes only the matching current endpoint lease;
  it does not write an offline worker state and must not revoke a replacement
  endpoint.
- stale heartbeat or disconnect events from an older connection must never
  revoke a newer active connection after reconnect or endpoint takeover

## Redis Key Manifest

Transport Redis keys are runtime-state keys only. They are not worker runtime,
admission, scheduling, capacity, reservation, or task lifecycle truth. The
default component namespaces are:

| Component | Default namespace | Retained key families |
| --- | --- | --- |
| endpoint-lease | `xa:mass:transport:endpoint-lease:v1` | `bucket:<encodedDeliveryBucketId>:workers`, `bucket:<encodedDeliveryBucketId>:deadlines` |
| polling-delivery | `xa:mass:transport:polling-delivery:v1` | `polling:<encodedAdapterMailboxKey>:worker:<encodedSelectedWorkerId>:q`, `queues`, `stats` |
| dispatch | `xa:mass:transport:dispatch:v1` | `mailbox:<encodedAdapterMailboxKey>:ready-commands`, `mailbox-consumers`, `mailbox-consumer-deadlines`, `queues` |
| result-inbox | `xa:mass:transport:result-inbox:v1` | engine-drained result inbox entries |
| delivery-failure | `xa:mass:transport:delivery-failure:v1` | engine-drained retryable delivery-failure entries |

Endpoint lease truth is keyed by opaque `deliveryBucketId + workerId`. Redis
stores bucket-local worker metadata plus a bucket-local deadline index. The
general endpoint lease view does not expose the deadline timestamp. Endpoint
lease does not store route-key consumer hashes, route-owner worker projections,
or `bucket:<deliveryBucketId>:worker:<workerId>:owner` pointers. Split
transport handoff availability is represented by handoff-private mailbox
consumer lease evidence.

Forbidden transport key families:

- old route-owner-side `route:<encodedRouteKey>:consumers`, `deadline`,
  `workers`, `owner-shards`, `worker-routes:*`, `worker-route:*`, `routes`,
  and `route-presence:*`
- old adapter/worker or bucket/worker owner pointers such as
  `adapter:<adapterId>:worker:<workerId>:owner` and
  `bucket:<deliveryBucketId>:worker:<workerId>:owner`
- transport-owned worker capacity, reservation, active lease, dispatch gate,
  event-binding ceiling, `group:{groupId}:slots`, `worker:meta:*`, or
  `worker:occupancy:*` keys

Worker runtime aggregates such as `group:{groupId}:slots` remain worker runtime
truth. Transport may maintain endpoint lease, dispatch handoff, result inbox,
and delivery-failure inbox runtime state; polling adapter may maintain its own
pending pull-buffer runtime state. Neither transport nor adapter runtime state
may preserve or derive scheduling/admission truth in its Redis keyspace.

## Model Boundaries

`DispatchMessage` is the internal assigned-dispatch item. It is not a full
transport route, a worker API response, a task item read model, or a packet
envelope. Its stable fields are delivery id, selected worker id, opaque worker
payload, opaque delivery correlation, item deadline, and creation timestamp.
The adapter mailbox is carried once by `AdapterMailboxDispatchBatch.adapterMailboxKey`, not by
each item.

`TaskDispatchContent` and `TaskDispatchExecutionContext` have been removed
from the transport API. Do not recreate them as compatibility aliases, wrapper
payloads, or protected transport models.

`TaskDispatchContext` is no longer a transport runtime handoff object.
SDK/starter assembly consumes the task-level dispatch snapshot plus concrete
`TaskDispatchBinding` values, resolves worker-runtime delivery target evidence,
and translates the already selected worker into flat `DispatchMessage`
values inside an adapter-mailbox `AdapterMailboxDispatchBatch`.
Transport runtime consumes dispatch batches and flat items only.

`PulledDeliveryMessage` is the transport-core pull value. SDK/server worker
polling projects it into `WorkerAction`, not into a task-shaped transport
DTO. `WorkerAction` is not the internal dispatch handoff payload
and not a transport metadata carrier. Worker identity comes from the poll
session/path, and route/session/endpoint facts stay inside transport delivery.
WebSocket/socket worker frames are final-hop wire projections from the opaque
payload and selected-worker endpoint evidence. Task shell metadata such as task
name, project, and user id must not be copied into `DispatchMessage`, transport
pull results, or handoff codecs as parallel truth.

The old generic packet carrier has been removed from transport. Transport does
not keep a catch-all packet model for dispatch, result ingress, or worker-system
events. Result ingress uses
`ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)`; starter-owned
code decodes the opaque payload. Task-dispatch wire frames are assembled at
final-hop delivery from `DispatchMessage`. Starter-side dispatch construction
must not create a packet-backed dispatch item. The dispatch handoff codec and
polling queue codec must not serialize a generic task-dispatch packet as the
item payload.

Dispatch input projection should remain payload-shape driven. If transport
needs to unwrap a worker-facing wrapper such as the current SDK
`type=json,data=...` or `type=text,text=...` shapes, that decision must come
from the dispatch payload itself, not from unrelated task metadata like
`_sdk.payloadType`.

`ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)` is the opaque
transport result-ingress carrier, not a task-result schema. It carries a
partition key, opaque message payload, diagnostics, and creation time.
Adapters and polling sessions may include route key, adapter id, trace id, or
similar facts only as diagnostics; transport must not parse them to decide task
result correctness. Starter-owned `TaskResultCallbackCodec` decodes the opaque
payload, validates that `message.resultCorrelationRef` matches the payload
`resultCorrelationRef`, and creates a `TaskResultCallbackCommand`; engine-owned
result ingress then validates attempt or lease identity before mutating runtime
truth. SDK/server worker submit paths use `WorkerActionReply`, not
transport-owned result DTOs.

When envelope identity validation rejects stale attempt or lease evidence,
starter-owned result ingress returns accepted-noop semantics: the envelope was
handled and intentionally not applied. Transport still does not decide retry,
finality, task terminal convergence, or public result read truth.

`leaseToken` is reserved. Do not enforce it until there is an approved design
for token generation, storage, expiry, retry interaction, old-worker behavior,
and rejection semantics.

## Delivery Addressing

Transport delivery addressing keeps five facts separate:

- `deliveryBucketId`: upstream scheduling/index context retained on commands
  and endpoint lease claims; it is not the dispatch queue owner
- `adapterId`: transport-internal concrete adapter binding identity, used for
  local final-hop executor/channel resolution after endpoint lease evidence is
  known
- `routeKey`: opaque connection address, coarse delivery-domain metadata, or
  protocol correlation value
- `adapterMailboxKey`: runtime queue/storage address for the adapter mailbox
  process or embedded adapter binding that can attempt the final hop
- `selectedWorkerId`: engine-selected execution identity used only as a
  delivery constraint

Current runtime rules:

- `adapterId` is canonicalized by trim + lowercase
- `deliveryBucketId` is canonicalized as an opaque text id and must be explicit
  on assigned-delivery commands and endpoint lease claims
- `routeKey` is canonicalized by trim only; case is preserved
- route-key assembly is outside transport runtime. SDK/starter currently uses
  `CanonicalWorkerGroupRouteKeyCodec` as the default worker-group consumption
  route rule from `workerGroupId`; a future assembly policy may mint a route
  from `adapterId`, worker group, or another lane key. Transport
  runtime/adapters must not import or hard-code any of those rules.
- route-key assembly happens in SDK/starter or an explicit integration layer;
  transport binding and adapter runtime code must not hide `workerId ->
  routeKey` policy behind builder defaults or shared fallback helpers
- adapter ingress may receive an explicit routeKey from adapter-local or legacy
  protocols, but public managed SDK sessions must not expose routeKey and
  adapters must not mint routeKey-derived endpoint addresses for endpoint lease
  truth.
- push adapter command executors must perform selected-worker final-hop sends
  inside the concrete adapter, with no adapter, route, connection, or
  endpoint-owner id in the dispatch input and with no fallback that drops the
  selected worker and falls back to route-only send. Route-only send is
  reserved for explicit raw/manual side-channels.
- push adapter local session lookup uses one unique id only:
  `selectedWorkerId` / worker id. `deliveryBucketId` is upstream
  scheduling/index and endpoint-evidence context; it must not be added as a
  WebSocket/Socket adapter-local session lookup dimension.
- `adapterId` is never a worker-selection or selected-worker send input. It may
  help transport register local adapter metadata or raw/manual side-channel
  diagnostics after a selected worker and endpoint evidence already exist.
- There is no transport-neutral selected-worker endpoint registry for assigned
  push delivery. Concrete push adapter command executors perform worker-id-only
  session lookup and final-hop writes inside the adapter. Worker-id raw sends
  may remain behind `RawWorkerMessageChannel`, but routeKey-only WebSocket
  output queues and raw route registries are not assigned-task delivery
  fallbacks.
- endpoint lease evidence must not require routeKey or route-derived endpoint
  address fields.
  Push assigned delivery must not require routeKey when selected-worker session
  addressing is available. Queued polling delivery must not rely on routeKey as
  the only isolation key.
- assigned dispatch handoff queues are addressed by `adapterMailboxKey`.
  Each queued item carries `selectedWorkerId`; the adapter dispatcher demuxes
  by that field and uses adapter-local session state before final-hop delivery.
  `selectedWorkerId` is not a second physical queue address.
- for assigned task delivery, `selectedWorkerId` is the worker correctness
  constraint, `adapterMailboxKey` is the physical handoff target, and `routeKey`
  is only opaque connection/correlation metadata. Transport runtime must not
  reinterpret routeKey as task, attempt, lease, worker-group, worker, or
  business routing truth.
- route-only endpoint helpers may exist only as explicit raw/manual
  adapter-scoped side-channels. They are not task-dispatch mainline operations
  and must not be used to infer selected-worker ownership from endpoint
  snapshots.
- worker-addressed debug/raw side-channels are not task-dispatch truth; if they
  remain, they must stay separate from selected-worker task dispatch and must
  not reintroduce a route-only fallback for assigned items.
- future Redis/JDBC queue replacements must preserve the same opaque addressing
  rules and must not require hot-path scans to recover queue ownership

WRB convergence note:

- `CanonicalWorkerGroupRouteKeyCodec` names the current SDK/starter default
  route-key mint rule: route identity is minted from `workerGroupId`. This is
  not a transport runtime rule; adapters and shared runtime code receive
  explicit opaque route keys.
- `deliveryBucketId` may still be derived from worker-group context for
  upstream scheduling/index and endpoint lease context, but it is no longer the
  stable queue address between engine/starter and transport. Worker-runtime
  delivery target evidence owns `selectedWorkerId -> adapterMailboxKey`.
- Binding-level `workerId` remains the selected execution identity and
  delivery constraint within the adapter mailbox.
- Redis-backed endpoint lease state uses bucket-local worker metadata plus
  bucket-local deadline indexes. It does not expose route-key owner scans or
  producer-side dispatch lookup.
- Assigned dispatch handoff queues are adapter-mailbox scoped. Adapter
  or route changes must not make routeKey the worker correctness key;
  wrong-worker prevention comes from the `selectedWorkerId` carried by each
  item and the adapter-local final-hop session lookup.
- `adapterId` and connection/session handles remain transport-internal
  endpoint/session evidence. They must stay in endpoint/session values or queue
  metadata, not become the route-key minting rule.

## Forbidden Drift

Do not let transport grow these responsibilities:

- task scheduling, matching, or worker lock ownership
- `TaskMsgAttempt` lifecycle mutation outside engine services
- direct task release, retry, or terminal decisions from adapters
- generic transport delivery stores that hide adapter-local polling pull slots
- worker wire payload changes for internal runtime metadata
- protocol-specific frame/codec types in `transport_api`
- compatibility wrappers that preserve old transport paths as parallel mainlines

Do not add generic-looking transport models such as `TransportTask`,
`TransportTaskMessage`, `WorkerDeliveryState`, or `TaskTransportSnapshot`
without first proving why the existing stable concepts cannot carry the
behavior.

## Hot-Path Rule

Result ingest is a hot path. Transport runtime may buffer, enqueue, and relay
result envelopes, but result validation belongs to starter/engine-owned result
ingestion. That validation may read runtime lease metadata plus a bounded
latest-attempt projection, but it must not load full task history or scan all
attempts. Storage implementations should provide bounded lookups for:

```text
(taskId, messageId) -> active runtime lease
(taskId, messageId) -> latest-attempt compatibility residue view
```

Dispatch is also a hot path. Dispatch handoff queues store
mailbox-targeted flat dispatch items under mailbox-scoped queues. They
must not deep-copy worker pull DTOs, endpoint leases, or generic task-dispatch
packet payloads as the handoff item shape. Adapter delivery receives
`DispatchMessage` with selected-worker opaque payload; concrete push adapters
use the selected worker id as their single local final-hop lookup key, while
polling queue delivery projects directly to opaque pulled delivery messages.
Retryable dispatch outcomes must carry delivery id,
`selectedWorkerId`, opaque correlation, status, retryability, reason, and time;
transport trace ids are diagnostics and must not double as compensation keys.

Assignment-to-transport handoff is also part of the hot path. Embedded and
split runtime both use adapter-mailbox `TransportDispatchHandoff`
contracts: producers offer to an `adapterMailboxKey`, and embedded adapter
hosts contribute adapter-owned consumers that destructively poll only their
mailbox. Producer
handoff is a bounded offer that returns delivery outcomes such as backpressure,
not a blocking engine hot-path call. Do not reintroduce direct synchronous
engine->transport callback coupling as a parallel mainline, do not treat any
handoff queue as a second runtime ready queue, and do not treat transport as a
retry, reassign, compensation, attempt-timeout, or final-recovery owner.
Transport owns only delivery-executor consistency and observable delivery
attempt failure. Offered items are admitted only to the target mailbox queue,
adapter-owned consumers destructively poll their own mailbox, and final-hop
execution returns delivery outcome or failure evidence when the failure is
known. Transport
must not actively discard a known failed offer, unavailable mailbox, missing
endpoint, invalid dispatch item, or adapter final-hop failure without returning
a `DispatchOutcome` or publishing retryable delivery failure evidence. Once an
item has been accepted into the transport attempt path, lack of later worker
consumption, process completion, or task result is not a transport retry loop;
engine-owned task attempt timeout, retry, reassign, and compensation remain the
fallback.

Transport delivery executors must enforce explicit admission control. Dispatch
handoff stores use adapter-mailbox queue admission plus destructive mailbox
poll; polling pending pull buffers keep selected-worker slots as polling
adapter implementation details, but those slots are not the engine-to-transport
handoff address.
Polling pending pull-buffer stats are polling-adapter diagnostics only. Push
adapter final-hop outcomes are returned by concrete adapter command executors
and are not folded into queue diagnostics. Poll
semantics must stay explicit enough to distinguish delivered, empty,
invalid-request, unavailable, and shutdown results without forcing callers to
treat every non-delivery outcome as an empty queue.
`DeliveryPullChannel.pollDeliveryMessagesResult(...)` is the transport mainline
for that statusful view and receives the polling worker's registered
`deliveryBucketId` plus registered worker id as `selectedWorkerId`; SDK
task-shaped pull helpers are convenience wrappers above it. `DELIVERED` status
must always carry one or more pulled items/envelopes;
empty payload sets are `EMPTY`, not a second encoding of delivery. Thread interruption is not a store result contract; store
implementations should throw interruption and let callers handle it above the
store boundary. Store shutdown is
also part of the runtime contract: after shutdown the store rejects new
delivery, clears in-memory backlog, and wakes waiting pollers without changing
engine-owned task lifecycle state.

For Redis-ready queue diagnostics, stats are a side-channel only. They must not
drive command admission, selected-worker correctness, lifecycle, retry,
reassign, or completion proof. Polling pending buffer diagnostics may expose
queued items, queue count, mailbox/worker slot breakdowns, waiting pollers,
age, and admission counters as adapter-local operator evidence, but those
fields are not transport owner truth and are not required by push adapters.

Queue mechanics such as keyed FIFO storage, blocking poll coordination,
per-key/global admission, and queue snapshot counters may live under
`platform_infra` so long as transport semantics remain owned by
`TransportDispatchHandoff`, `DispatchMessage`, `DispatchOutcome`, and the
polling-adapter-owned `PollingPendingDeliveryBuffer`. Embedded runtime
composition may choose between the default in-memory polling pending buffer and
a Redis-backed polling pending buffer, but that selection belongs to
SDK/starter polling adapter assembly rather than transport-facing adapter
contracts. That assembly layer also owns polling buffer cap tuning such as
total queued items and per-worker caps; transport contracts should consume
resolved limits rather than hard-code runtime policy.

## Push vs Pull Delivery

Transport currently has two delivery executor paths:

- push final hop: realtime adapters perform adapter-local selected-worker
  session lookup/write and return
  `DELIVERED` / `NO_ENDPOINT` / `FAILED` / `UNAVAILABLE` / `INVALID`
- pull buffer: polling adapters admit an item into
  `PollingPendingDeliveryBuffer` and return `QUEUED` /
  `BACKPRESSURE_REJECTED` / `UNAVAILABLE` / `INVALID`

These paths intentionally share `DispatchOutcome` identity fields and status
language, but they do not form one richer transport-owned lifecycle model.

Keep these rules:

- `DELIVERED` from a push adapter does not imply durable store ownership, ack
  tracking, or later dequeue
- `QUEUED` means store admission only; engine lifecycle truth still lives outside transport
- queue stats such as `queueByAdapter`, backlog age, waiting pollers, and queued-item counts
  are queue-path diagnostics only; `queueByAdapter` is not canonical queue ownership truth
- push final-hop counters, if added later, belong to concrete adapter
  diagnostics, not polling pending-buffer diagnostics
- do not add a transport-owned retry/lease/state machine that merges push final-hop
  and queued delivery into one second attempt lifecycle

Observability rule:

- use logs, traces, queue stats, and indexed runtime lookups for diagnosis
- do not add full-history, full-task, or full-queue scans to transport hot paths
- delivery submission, result ingest, and shutdown handling should remain safe under duplicate or repeated calls
