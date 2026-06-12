# Transport Delivery Executor Convergence Roadmap

Status: completed and archived on 2026-06-12.

Related current truth:

- `transport/AGENTS.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `doc/TASK_LIFECYCLE_BASELINE.md`
- `doc/INFRA_TRUTH_LAYERS.md`
- `doc/PROOF_REGISTRY.md`

Current implementation progress:

- DEX-0 inventory is archived beside this roadmap at
  `2026-06-12_TRANSPORT_DELIVERY_EXECUTOR_CONVERGENCE_INVENTORY.md`.
- DEX-1 introduced `DeliveryCommand` and `InboundEnvelope` in `transport_api`.
- DEX-1 converged outcome semantics in place through `DispatchOutcome` /
  `DispatchOutcomeStatus`; no production `DeliveryOutcome` type exists.
- DEX-2 moved task-dispatch-to-delivery translation into SDK/starter assembly.
  Engine core remains transport-free and still emits neutral assignment/binding
  truth.
- DEX-3 retargeted in-memory and Redis handoff to `DeliveryCommandBatch` and
  bounded offer semantics. The old route-targeted dispatch handoff/listener/
  failure bridge was removed from transport runtime mainline.
- DEX-4 moved `RuntimeTaskResultIngestChannel` into SDK/starter assembly.
  Transport runtime no longer imports `TaskResultIngestFacade` or
  `TaskResultCorrelation`.
- DEX-5 landed fully: WebSocket/socket replacement is selected-worker scoped
  (`adapterId + selectedWorkerId` inside each adapter), not routeKey scoped.
  `WorkerEndpointRegistry` is selected-worker only; raw/manual route sends use
  `RawWorkerRouteEndpointRegistry` or adapter-local `RawWorkerMessageChannel`.

## Purpose

Transport has already converged important selected-worker delivery facts:
`selectedWorkerId` is the delivery target, `routeKey` is opaque connection or
domain metadata, and `deliveryQueueKey` is a batching/sharding primitive.

That convergence is necessary but not sufficient. This roadmap removes
task-dispatch and result-lifecycle vocabulary from transport runtime core
handoff, listener, failure, and result-ingest paths. It also removes
producer-side blocking handoff behavior and route-shaped endpoint replacement
residue that kept transport larger than its real owner boundary.

This roadmap moves transport away from building a complex runtime around task
dispatch. Transport should be a delivery executor:

```text
engine binds selected worker
  -> transport delivers to a selected connection or queue lane
  -> transport reports delivery or inbound frames
  -> engine compensates, retries, or converges results
```

The target slogan is:

```text
Engine binds. Transport delivers. Engine compensates.
```

This is a reduction goal, not a new transport abstraction layer. A proposed
delivery contract is valid only if it removes task lifecycle decisions from
transport hot paths, keeps queueing bounded, and makes the transport execution
surface easier to reason about.

## Current Code Observations

- SDK/starter assembly owns `TaskDispatchDeliveryCommandSubmitter`, the single
  translator from `TaskDispatchContext + TaskDispatchBinding` into
  `DeliveryCommand`. Engine core does not import transport contracts.
- `DeliveryCommand` and `InboundEnvelope` live in `transport_api`.
  `DispatchOutcome` remains the one adapter-neutral outcome contract; no
  production `DeliveryOutcome` track exists.
- `TransportDeliveryCommandHandoff` is the current handoff contract. In-memory
  and Redis implementations accept `DeliveryCommandBatch` and return delivery
  outcomes; full queues return backpressure rather than blocking producer
  threads.
- Redis delivery-command offer updates queue, lane catalog, and node-local
  ready-lane visibility in one producer-side Lua unit.
- Retryable delivery failures flow through delivery-shaped failure events and
  are drained by SDK/starter into engine-owned assignment compensation using a
  neutral `TaskDispatchDeliveryFailure` record.
- Transport runtime main sources no longer import `TaskDispatchBatchListener`,
  `TaskDispatchContext`, `TaskDispatchBinding`, `TaskResultIngestFacade`, or
  `TaskResultCorrelation`.
- `RuntimeTaskResultIngestChannel` lives in SDK/starter assembly. It may
  validate result identity against engine result correlation; transport runtime
  only buffers, queues, or relays result envelopes.
- `TaskDispatchItem` and `TaskResultReport` are still worker-facing protocol
  payloads under `transport_api`. They are compatibility payloads and cannot be
  deleted before adapter and SDK protocol ownership is deliberately replaced.
- `TransportDeliveryStore` already models queued polling delivery as shared
  `deliveryQueueKey` plus selected-worker sub-lane. This should be preserved.
- Direct WebSocket and socket dispatch already use selected-worker addressing
  through `sendToSelectedWorker(...)`. Route-only send must stay a raw/manual
  side channel, not assigned task delivery.
- WebSocket and socket session replacement now searches by selected worker
  across routeKey changes inside each adapter. `routeKey` remains endpoint
  metadata and raw/manual route side-channel input, not selected-task endpoint
  replacement scope.
- `WorkerEndpointRegistry` exposes only selected-worker task endpoint delivery.
  Raw/manual route helpers live on `RawWorkerRouteEndpointRegistry` or
  adapter-local raw channels, so assigned task delivery callers cannot fallback
  to `sendToAdapterRoute(...)` through the same interface.

These facts mean the next convergence is not another `routeKey` rename. It is
a contract and owner split between task assignment/result lifecycle and
transport delivery execution.

## Owner Review

Engine owns:

- task lifecycle state,
- selected worker binding,
- task attempt creation and active lease truth,
- retry, release, compensation, and terminal policy,
- result finality and result convergence.

Worker runtime owns:

- worker registry and WorkerGroup membership,
- worker capability and event binding truth,
- worker admission, load, slot, dispatch gate, and candidate evidence,
- worker state reports used by scheduling.

Transport owns:

- adapter registration and adapter selection by `adapterId`,
- endpoint sessions, connection ids, route-owner lease evidence, and session
  metadata,
- frame encoding/decoding and send/receive mechanics,
- bounded delivery queueing, polling, draining, backpressure, and delivery
  outcomes,
- inbound envelope relay for worker result/event frames.

Transport may carry `selectedWorkerId` as an assigned delivery constraint. It
must not choose a worker, rank candidates, decide worker online/offline, validate
task leases, decide retry, or apply task results.

## Boundary Decision

Transport core becomes a delivery executor. Its core input and output contracts
should be delivery-shaped, not task-lifecycle-shaped:

```text
DeliveryCommand
  commandId / deliveryId
  adapterId
  selectedWorkerId
  deliveryQueueKey
  routeKey                 optional opaque connection/domain metadata
  connectionToken          optional precise session evidence
  payload                  opaque transport payload or TransportPacket
  correlation              opaque ids such as task/message/attempt/trace
  deadline / createdAt

DeliveryOutcome / converged DispatchOutcome
  commandId / deliveryId
  adapterId
  selectedWorkerId
  status                   delivered, queued, no-endpoint, unavailable,
                           backpressure, invalid, shutdown
  retryable
  reason
  transportNodeId
  connectionId
  occurredAt

InboundEnvelope
  envelopeId
  adapterId
  sourceWorkerId
  routeKey                 opaque connection/domain metadata
  connectionId
  payload                  opaque frame payload
  correlation              opaque ids if supplied by the frame
  receivedAt
```

Task-shaped worker protocol payloads may remain during convergence, but they
must be carried as opaque payloads or adapter codec outputs. Transport core must
not interpret them as scheduling, lease, retry, or result-finality truth.

Outcome convergence is part of this boundary. Current code already has
`DispatchOutcome`; this roadmap must not create a second mainline
`DeliveryOutcome` track beside it. DEX-1 must either evolve `DispatchOutcome`
in place toward delivery-executor semantics, or rename/replace it in the same
slice that migrates adapter SPI callers.

Assigned task delivery has these hard rules:

- `selectedWorkerId` is the correctness identity for worker delivery.
- `routeKey` is optional opaque connection/domain/correlation metadata.
- `deliveryQueueKey` and adapter lanes are batching, storage, or locality
  primitives only; they must never encode worker selection.
- delivery submission must be bounded. Full queues, unavailable endpoints,
  invalid commands, and shutdown are delivery outcome statuses, not reasons for
  the engine producer thread to block indefinitely.
- route-only send is a raw/manual side channel. It must not be reachable as a
  fallback from assigned task delivery.

Route-owner state remains transport-owned endpoint evidence. It should be
narrowed to delivery feasibility and endpoint lookup:

```text
adapterId + selectedWorkerId -> current endpoint evidence
```

`routeKey` may remain in endpoint records and envelopes as opaque metadata, but
it must not be the worker correctness key for assigned task delivery.
Endpoint replacement for assigned delivery must also follow this rule: a new
active endpoint for the same `adapterId + selectedWorkerId` replaces the prior
active endpoint even when the opaque `routeKey` changed.

## Non-Goals

- No worker scheduling, ranking, admission, or replacement selection in
  transport.
- No transport-owned worker lifecycle truth or worker online/offline state.
- No transport-owned task attempt, lease, retry, release, terminal, or result
  convergence policy.
- No transport-owned task-dispatch runtime that mirrors engine lifecycle,
  assignment, retry, or compensation state.
- No second live outcome contract. `DeliveryOutcome` and `DispatchOutcome` must
  not exist as parallel mainline APIs.
- No immediate public worker protocol break solely to rename
  `TaskDispatchItem` or `TaskResultReport`.
- No second live dispatch mainline kept through compatibility wrappers,
  aliases, or route-targeted fallbacks after callers move.
- No Redis durability claim for task lifecycle. Transport queues remain runtime
  delivery state, not task truth.
- No change to worker-runtime `group:{groupId}:slots` or worker admission truth.

## Do Not Start With

Do not start by deleting `TaskDispatchItem`, `TaskResultReport`, route-owner
stores, or route-targeted classes. First classify current call sites and add the
delivery-shaped contracts. Deleting names before moving callers would either
break worker protocol compatibility or force a fake compatibility layer.

Do not start with a broad rename. Rename only after the new owner boundary is
real in code and the old caller path has moved.

## DEX-0: Inventory And Classification

Goal:

Classify every task-shaped or lifecycle-shaped symbol currently used under
transport before changing behavior.

Scope:

- Inventory production and test usage of:
  - `TaskDispatchBatchListener`,
  - `TaskDispatchContext`,
  - `TaskDispatchBinding`,
  - `RouteTargetedTaskDispatch*`,
  - `TransportDispatchFailureHandler`,
  - `TaskResultIngestChannel`,
  - `RuntimeTaskResultIngestChannel`,
  - `TaskResultIngestFacade`,
  - `TaskResultCorrelation`,
  - `TaskDispatchItem`,
  - `TaskResultReport`,
  - `TransportResultEnvelope`,
  - `DispatchOutcome`,
  - `DispatchOutcomeStatus`.
- Inventory production and test usage of:
  - route-key-owned batch fields and validation,
  - blocking handoff submit implementations,
  - endpoint replacement keyed by routeKey,
  - `WorkerEndpointRegistry#sendToAdapterRoute`,
  - raw/manual worker message channels.
- Classify each usage as:
  - worker protocol compatibility payload,
  - engine-to-transport boundary translator,
  - transport delivery executor core,
  - result lifecycle leak,
  - dispatch compensation bridge,
  - blocking/backpressure residue,
  - route-key routing residue,
  - raw/manual side channel,
  - adapter codec,
  - test fixture,
  - stale documentation or guard residue.
- Record which Maven modules currently require `xa-mass-base` dispatch/result
  types from transport main sources and why.

Acceptance:

- Add `roadmap/TRANSPORT_DELIVERY_EXECUTOR_CONVERGENCE_INVENTORY.md`.
- The inventory states which usages must move before code deletion.
- No production behavior changes.
- Verification is source-scan only for this slice.

## DEX-1: Minimal Delivery Protocol And Outcome Convergence

Goal:

Introduce delivery-shaped transport contracts without moving runtime behavior,
and converge the existing outcome contract so the roadmap does not create a
new dual-track API.

Scope:

- Add minimal `DeliveryCommand` and `InboundEnvelope` contracts in the owning
  transport contract layer.
- Converge the outcome contract deliberately:
  - preferred first slice: evolve existing `DispatchOutcome` /
    `DispatchOutcomeStatus` in place toward delivery-executor semantics;
  - allowed alternative: introduce `DeliveryOutcome` only if the same slice
    migrates adapter SPI callers and removes the old `DispatchOutcome` mainline.
- Decide whether `payload` is `TransportPacket`, bytes, JSON text, or a small
  opaque value wrapper.
- Keep `selectedWorkerId`, `adapterId`, optional `connectionToken`,
  `deliveryQueueKey`, and opaque correlation explicit.
- Do not embed `TaskDispatchContext`, `TaskDispatchBinding`,
  `TaskResultIngestFacade`, worker admission, retry policy, or result-finality
  types in these contracts.
- Define explicit outcome statuses for at least `delivered`, `queued`,
  `no-endpoint`, `unavailable`, `backpressure`, `invalid`, and `shutdown`.
  These statuses are transport facts only; engine decides compensation.
- Keep existing `TaskDispatchItem` and `TaskResultReport` as compatibility
  payloads until later slices move protocol ownership.

Acceptance:

- New contract tests prove required fields, normalization, and invalid input
  behavior.
- Outcome tests prove there is one mainline outcome contract for adapter dispatch
  and delivery executor results.
- No production adapter SPI returns one outcome type while transport executor
  returns another.
- Transport contract docs state that correlation fields are opaque to transport.
- No runtime behavior changes yet; any outcome rename in this slice is a
  compile-proven API convergence, not a delivery behavior change.

## DEX-2: Engine Boundary Translation

Goal:

Move task-assignment knowledge to a single engine/assembly boundary that emits
`DeliveryCommand` values.

Current landed shape:

- `TaskDispatchDeliveryCommandSubmitter` lives in SDK/starter assembly.
- Engine core remains transport-free; architecture guard covers this.
- Delivery failures map back to engine-owned compensation through
  `TaskDispatchDeliveryFailure`, not through transport task-dispatch bindings.

Scope:

- Replace the transport-core dependency on `TaskDispatchContext +
  TaskDispatchBinding` with a boundary translator that is explicitly owned by
  starter/integration assembly, not engine core.
- Engine core must not import transport contracts to perform this translation.
  It continues to produce assignment/binding truth through neutral engine/base
  boundaries. The translator belongs in starter/runtime assembly or behind a
  neutral base contract if a shared contract is truly required.
- The translator maps the already selected worker binding into
  `DeliveryCommand`.
- The translator may preserve current task-shaped worker payload as opaque
  command payload.
- Delivery infeasibility becomes a delivery outcome or delivery-failure record
  consumed by engine-owned compensation. Transport does not call retry/release
  lifecycle APIs.
- `TransportDispatchFailureHandler` was a transitional bridge while existing
  route-targeted callers moved. It is now removed from transport runtime core
  and must not be reintroduced as the delivery executor failure contract.
- Before switching the local adapter listener to delivery commands, define the
  engine-owned outcome/failure drain. Current engine compensation expects
  assignment binding truth; the new path must either keep that mapping in
  starter/integration assembly or add a neutral engine-owned failure record.
  Do not replace post-adapter compensation with log-only delivery outcomes.
- `routeKey` resolution, if still required for adapter metadata, happens before
  or inside the translator and is stored only as opaque metadata.
- The translator must not become a same-module pass-through wrapper. It exists
  only at the starter/integration assembly boundary where task assignment is
  converted into delivery execution.
- Batch grouping should be based on adapter lane, delivery queue, or transport
  node locality. It must not require all commands in a process-boundary batch to
  share the same `routeKey`.

Acceptance:

- Transport delivery executor/core packages no longer import
  `TaskDispatchBatchListener`, `TaskDispatchContext`, or `TaskDispatchBinding`.
- Any remaining production import of those types is in a named boundary
  translator or starter/integration assembly package and is recorded in the
  inventory.
- `xa-mass-engine` main sources do not import transport API/runtime packages as
  part of this translation.
- The new delivery executor path does not import or call
  `TransportDispatchFailureHandler`; it emits outcome/failure records and lets
  engine-owned code decide compensation.
- A failed post-adapter delivery has a verified path to engine-owned
  compensation or an explicitly accepted deferred safety gap; it is not silently
  dropped by the transport consumer.
- Existing embedded and distributed dispatch tests still pass.

## DEX-3: Delivery Executor And Handoff Retarget

Goal:

Make the handoff and adapter path consume `DeliveryCommand` and emit the
converged delivery outcome contract.

Current landed shape:

- In-memory and Redis handoff use `DeliveryCommandBatch`.
- Producer submission is bounded offer; full queues return backpressure
  outcomes.
- Redis command offer updates queue, lane catalog, and ready-lane visibility in
  one Lua unit.
- Route-targeted handoff/listener classes have been deleted from production.

Scope:

- Introduce `TransportDeliveryExecutor` as the real delivery protocol seam:
  command in, outcome out.
- Retarget in-memory and Redis dispatch handoff from
  `RouteTargetedTaskDispatchBatch` to `DeliveryCommand` batches or equivalent
  command records.
- Preserve adapter-lane or node-local queue partitioning as a performance
  primitive, not a worker identity primitive.
- Replace blocking `submit` semantics with bounded offer/execution semantics.
  Backpressure, no endpoint, invalid command, and shutdown must be returned as
  delivery outcome values or equivalent outcome records.
- Redis handoff capacity checks must be atomic for the producer path. Do not use
  a non-atomic `LLEN` followed by `RPUSH` loop with producer sleeps as the
  final design.
- In-memory handoff must not use blocking `put` in the engine producer path.
- Preserve polling selected-worker isolation under shared `deliveryQueueKey`.
- Preserve direct WebSocket/socket delivery through selected-worker endpoint
  lookup.
- Keep route-owner state only as current endpoint evidence and delivery
  feasibility, not task-dispatch routing truth.
- Endpoint lookup for assigned delivery must use `adapterId +
  selectedWorkerId`. Endpoint replacement changes may stay deferred until DEX-5
  if raw/manual route dispatch has not yet been split from assigned task
  dispatch.

Acceptance:

- Redis and in-memory handoff tests use delivery-command records as the stored
  shape and assert non-blocking backpressure behavior.
- Redis handoff tests prove bounded offer is atomic and does not overfill the
  queue under concurrent producers.
- Redis bounded offer updates the command queue, lane catalog, and ready-lane
  wakeup index in one Lua or transaction unit so a queued command is visible to
  the intended transport node.
- In-memory handoff tests prove a full queue returns `backpressure` or an
  equivalent outcome without blocking the producer thread.
- Dispatch outcome tests assert delivery-outcome semantics rather than
  task-lifecycle compensation semantics.
- `RouteTargetedTaskDispatchHandoff` is removed from production mainline.
- Process-boundary batch records do not require a top-level shared `routeKey`.
  `routeKey` is present only per command/envelope when needed as opaque
  metadata.
- No queue keyed only by `routeKey` or `deliveryQueueKey` is valid for assigned
  polling delivery.

## DEX-4: Inbound Envelope And Result Boundary

Goal:

Make adapter ingress transport-owned and result lifecycle engine-owned.

Current landed shape:

- `RuntimeTaskResultIngestChannel` moved to SDK/starter assembly.
- Transport runtime core no longer imports `TaskResultIngestFacade` or
  `TaskResultCorrelation`.
- Transport runtime still owns result inbox/buffer mechanics, not result
  lifecycle validation.

Scope:

- Adapters decode frames into `InboundEnvelope` or adapter-specific protocol
  payloads carried by an inbound envelope.
- Engine-side result ingestion validates task id, message id, attempt id, lease
  token, stale result, duplicate result, and terminal convergence.
- Move or retire `RuntimeTaskResultIngestChannel` from transport runtime core.
- Transport may validate frame shape and adapter identity. It must not query
  task result correlation, active lease, or projected attempt state.
- `TaskResultReport` may remain a worker protocol payload during this slice,
  but transport core treats it as payload, not lifecycle truth.

Acceptance:

- Transport runtime core no longer imports `TaskResultIngestFacade` or
  `TaskResultCorrelation`.
- Result identity and lease validation tests live with the engine/result
  ingestion owner, not transport runtime core.
- WebSocket, socket, and polling result submission still reach engine result
  convergence through the new boundary.

## DEX-5: Adapter Protocol Cleanup

Goal:

Narrow adapter responsibilities after the executor boundary is real.

Current landed shape:

- WebSocket and socket selected-worker endpoint replacement is keyed by worker
  identity inside the adapter, so routeKey changes retire the previous selected
  endpoint.
- Raw/manual route-only side channels remain separate and allowed through
  `RawWorkerRouteEndpointRegistry` or adapter-local `RawWorkerMessageChannel`.
  Task delivery callers use the selected-worker `WorkerEndpointRegistry`
  surface and cannot see route-only send helpers through that interface.

Scope:

- WebSocket and socket adapters send command payloads through
  `sendToSelectedWorker(...)` and return the converged delivery outcome
  contract.
- Split assigned-task endpoint dispatch from raw/manual route dispatch. The task
  delivery path should depend on a selected-worker endpoint contract, while
  route-only helpers remain behind `RawWorkerRouteEndpointRegistry` or
  adapter-local `RawWorkerMessageChannel`.
- After that split, endpoint replacement for assigned task delivery must be
  keyed by `adapterId + selectedWorkerId`. `routeKey` may be stored on the
  endpoint record and used by raw/manual route dispatch, but it must not scope
  replacement for selected-worker task delivery.
- Polling adapter polls by `deliveryQueueKey + selectedWorkerId` and returns
  worker-facing payload views without exposing delivery queue keys publicly.
- Raw/manual route-only side channels remain separate from assigned task
  delivery and cannot be used as fallback for selected-worker delivery.
- Worker-facing compatibility DTOs stay only where worker protocol requires
  them.

Acceptance:

- Adapter tests prove selected-worker direct delivery and selected-worker
  polling isolation.
- Raw/manual route send tests are explicitly separate from task dispatch tests.
- No adapter mainline recovers the selected worker by decoding `routeKey` or
  task payload.
- No assigned task dispatch caller can invoke `sendToAdapterRoute(...)` through
  the same interface it uses for selected-worker delivery.
- WebSocket and socket tests prove that reconnecting the same
  `selectedWorkerId` with a different `routeKey` retires the previous endpoint
  for selected-worker delivery without removing raw/manual route dispatch as an
  adapter-local side channel.

## DEX-6: Residue Removal And Guards

Goal:

Delete old route-targeted/task-aware transport runtime residue and prevent
regression.

Current landed shape:

- Production route-targeted dispatch classes and dispatch-failure bridge
  classes are removed.
- Guards cover no transport imports in engine core, no task-dispatch/result
  lifecycle imports in transport runtime core, no route-only fallback in
  assigned task dispatch channels, and no nested `taskBatchJson` command codec.
- Guards also cover that `WorkerEndpointRegistry` does not expose route-only
  raw send helpers.

Scope:

- Remove `RouteTargetedTaskDispatch*` classes after all production callers move.
- Remove or move `TransportDispatchFailureHandler` out of transport runtime core
  after engine-owned outcome/failure draining exists.
- Remove transport-runtime task result lifecycle validation residue.
- Move route-scanning owner reads such as `currentOwners(routeKey)` out of the
  dispatch executor SPI and into inspection/maintenance-only APIs.
- Remove or rename task-dispatch-facing endpoint registries so route-only raw
  send cannot look like a task delivery fallback.
- Update `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  from route-targeted wording to delivery-executor wording.
- Update `doc/PROOF_REGISTRY.md` and transport architecture guards.
- Archive or rewrite old roadmaps that describe route-targeted handoff as the
  target state.

Acceptance:

- Non-test transport runtime main sources no longer contain:
  - `TaskDispatchContext`,
  - `TaskDispatchBinding`,
  - `TaskDispatchBatchListener`,
  - `TaskResultIngestFacade`,
  - `TaskResultCorrelation`,
  - `RouteTargetedTaskDispatch`,
  - `TransportDispatchFailureHandler`.
- Guards fail if assigned task delivery calls route-only endpoint send helpers.
- Guards fail if transport delivery executor packages call route-owner
  `currentOwners(routeKey)` or other route-scan APIs for assigned delivery.
- Guards fail if engine producer handoff paths use blocking queue `put` or
  producer sleep loops for delivery backpressure.
- Guards fail if transport delivery executor/core code invokes compensation
  APIs or `compensate(...)` methods over `TaskDispatchContext` and
  `TaskDispatchBinding`.
- Guards fail if transport runtime core validates task lease, terminal, result
  correlation, or result finality state. Do not ban generic `retry` vocabulary
  globally because delivery outcomes and legacy payloads may still expose
  retryable transport facts during convergence.
- Guards fail if delivery command/batch process-boundary records require a
  top-level shared route key.
- Roadmap residue scan finds no active docs that present route-targeted handoff
  as the final architecture.

## Suggested Implementation Order

1. Complete DEX-0 inventory.
2. Land DEX-1 protocol contracts with tests and no behavior change.
3. Move dispatch producer assembly through DEX-2 while preserving current
   worker protocol payloads.
4. Retarget handoff and delivery stores through DEX-3, including bounded
   non-blocking producer behavior.
5. Move result lifecycle validation out through DEX-4.
6. Clean adapter mainlines through DEX-5, including raw route side-channel
   separation and worker-global selected endpoint replacement.
7. Remove old names, update docs, and add guards through DEX-6.

Each slice must compile and pass focused transport tests before the next slice
starts. Do not merge slices by deleting old classes before replacement callers
exist.

## Verification Candidates

Focused compile:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-engine -am -DskipTests compile
```

Transport runtime guard and delivery tests:

```bash
./mvnw -q -pl transport/transport_runtime -am test -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TransportConvergenceArchitectureGuardTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest,TransportDeliveryServiceTest,InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,InMemoryTransportDeliveryCommandHandoffTest,TransportDeliveryCommandBatchCodecTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportDeliveryFailureChannelTest
```

Adapter protocol tests:

```bash
./mvnw -q -pl transport/transport_api,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskDispatchItemTest,TaskResultReportTest,TransportResultEnvelopeTest,WebSocketTaskDispatchChannelTest,WebSocketInputProcessorTest,SocketTaskDispatchChannelTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest,WebSocketOutputProcessorTest
```

SDK embedded/distributed proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -DskipITs -Dtest=MassSdkTest,MassApplicationDistributedTransportTest,RuntimeTaskResultIngestChannelTest -Dsurefire.failIfNoSpecifiedTests=false
```

Guard scans to add or tighten as slices land:

```bash
rg -n "TaskDispatchContext|TaskDispatchBinding|TaskDispatchBatchListener|TaskResultIngestFacade|TaskResultCorrelation|RouteTargetedTaskDispatch" transport/transport_runtime/src/main/java
rg -n "TransportDispatchFailureHandler|compensate\\(" transport/transport_runtime/src/main/java
rg -n "com\\.xa\\.mass\\.transport" xa-mass-engine/src/main/java
rg -n "sendToAdapterRoute\\(" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/worker
rg -n "TaskResultIngestFacade|getResultCorrelation|TaskResultCorrelation|projectedAttemptId|leaseToken" transport/transport_runtime/src/main/java
rg -n "queue\\.put\\(|Thread\\.sleep\\(" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery
rg -n "currentOwners\\(" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime
```

The `sendToAdapterRoute` scan is intentionally scoped to assigned task dispatch
paths. Adapter-local raw/manual side channels such as `RawWorkerMessageChannel`
remain allowed until DEX-5 splits the interfaces. Result-lifecycle scans should
target concrete owner leaks such as `TaskResultIngestFacade`,
`getResultCorrelation`, `TaskResultCorrelation`, `projectedAttemptId`, and
lease-token validation, not every occurrence of generic retryable transport
outcome vocabulary.

`WorkerEndpointRegistry` itself must not expose `sendToAdapterRoute(...)` or
`isAdapterRouteOnline(...)`; those route-only operations belong to
`RawWorkerRouteEndpointRegistry` or adapter-local raw channels.

## Roadmap Completion Criteria

This roadmap can be marked complete only when all are true:

- Transport core accepts delivery-shaped commands and emits delivery-shaped
  outcomes.
- There is one mainline delivery outcome contract. The roadmap did not leave
  `DispatchOutcome` and `DeliveryOutcome` as parallel production APIs.
- Delivery producer paths are bounded and express backpressure as delivery
  outcome instead of blocking engine hot paths.
- Transport adapter ingress emits inbound envelopes or opaque payload records
  without applying task lifecycle decisions.
- Engine or engine-owned assembly owns task-dispatch-to-delivery translation and
  result lifecycle validation.
- Engine-owned code consumes delivery outcomes or failure records and decides
  compensation; transport runtime core does not own post-assignment task
  compensation.
- Worker runtime remains the owner of worker registry, group, capability,
  admission, load, candidate, and state-report facts.
- `selectedWorkerId` remains the delivery target. Transport may use it to find
  or replace that worker's endpoint lease, but must not use it to schedule,
  rank, admit, or replace the engine-selected worker.
- `routeKey` remains opaque metadata and is not the worker correctness key.
- `deliveryQueueKey` remains a batching/sharding primitive and selected-worker
  polling isolation is preserved.
- Endpoint evidence and endpoint replacement for assigned delivery are keyed by
  `adapterId + selectedWorkerId`; routeKey changes do not leave stale selected
  endpoints active.
- Route-targeted/task-aware transport runtime residue is removed from production
  mainline.
- Guards and proof registry entries prevent regression.
- Active docs describe the delivery-executor model; archived roadmaps remain
  historical only.

## Deferred Decisions
- Whether `TaskDispatchItem` and `TaskResultReport` remain long-term worker
  protocol DTO names or move under a worker-protocol owner after this roadmap.
- How much result correlation metadata should remain in opaque transport
  correlation versus engine-owned result application input.
- Whether `InboundEnvelope` becomes the primary adapter ingress shape in a
  later worker-protocol cleanup slice or remains a transport contract reserved
  for the next ingress convergence.
