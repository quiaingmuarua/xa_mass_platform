# Transport Delivery Executor Convergence Roadmap

Status: proposed direction document.

Related current truth:

- `transport/AGENTS.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `doc/TASK_LIFECYCLE_BASELINE.md`
- `doc/INFRA_TRUTH_LAYERS.md`
- `doc/PROOF_REGISTRY.md`

## Purpose

Transport has already converged important selected-worker delivery facts:
`selectedWorkerId` is the delivery target, `routeKey` is opaque connection or
domain metadata, and `deliveryQueueKey` is a batching/sharding primitive.

That convergence is necessary but not sufficient. Current transport runtime
still contains task-dispatch and result-lifecycle vocabulary in its core
handoff, listener, failure, and result-ingest paths. It also still has
producer-side blocking handoff behavior and route-shaped endpoint residue that
keeps transport larger than its real owner boundary.

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

- `RouteTargetedTaskDispatchSubmitter` currently implements
  `TaskDispatchBatchListener` and consumes `TaskDispatchContext` plus
  `TaskDispatchBinding` directly. It already looks up delivery feasibility by
  `adapterId + selectedWorkerId`, but it still resolves `routeKey`, builds
  `RouteTargetedTaskDispatchBatch`, and calls a transport failure handler with
  task-dispatch bindings.
- `RouteTargetedTaskDispatchSubmitter` and `RouteTargetedTaskDispatchBatch`
  still group and validate process-boundary batches by top-level `routeKey`.
  Redis physical queues are already adapter-lane keyed, so the route-key batch
  shape is residue, not the desired owner model.
- `RouteTargetedTaskDispatchListener` currently turns route-targeted batches
  into `TransportDispatchEnvelope` values, groups by adapter, dispatches to
  adapters, and compensates retryable failures.
- `DispatchOutcome` is already the current adapter-neutral delivery result
  contract returned by adapter dispatch SPIs. A future `DeliveryOutcome` name is
  semantic target language only unless the same slice migrates all mainline
  callers and removes the old outcome path.
- `TransportDispatchFailureHandler` is currently a transport-runtime bridge that
  accepts task dispatch context and bindings, then performs post-assignment
  compensation. That bridge contradicts the final "Engine compensates" owner
  boundary and must not become the new executor's failure contract.
- Current engine core accepts only the neutral `TaskDispatchBatchListener`
  boundary. SDK/starter assembly creates the transport submitter. The delivery
  translator must not move transport contracts into engine core as a convenience
  dependency.
- `RouteTargetedTaskDispatchHandoff#submit` is a blocking producer call today:
  in-memory handoff uses a blocking queue put, and Redis handoff loops around
  capacity checks and sleeps. That makes transport backpressure a potential
  engine hot-path stall instead of an explicit delivery outcome.
- `RuntimeTaskResultIngestChannel` currently validates result envelope identity
  against `TaskResultIngestFacade#getResultCorrelation`, including active lease,
  lease token, and attempt id checks before forwarding `TaskResultReport`.
- `TaskDispatchItem` and `TaskResultReport` are still worker-facing protocol
  payloads under `transport_api`. They are compatibility payloads and cannot be
  deleted before adapter and SDK protocol ownership is deliberately replaced.
- `TransportDeliveryStore` already models queued polling delivery as shared
  `deliveryQueueKey` plus selected-worker sub-lane. This should be preserved.
- Direct WebSocket and socket dispatch already use selected-worker addressing
  through `sendToSelectedWorker(...)`. Route-only send must stay a raw/manual
  side channel, not assigned task delivery.
- WebSocket and socket session replacement still searches for an existing
  endpoint within the current `routeKey`. Assigned delivery is selected-worker
  delivery, so reconnect replacement must be keyed by `adapterId +
  selectedWorkerId`, with `routeKey` kept as metadata.

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
- `TransportDispatchFailureHandler` is allowed only as a recorded transitional
  bridge while existing route-targeted callers move. It must not be the contract
  for the new delivery executor path.
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
- Existing embedded and distributed dispatch tests still pass.

## DEX-3: Delivery Executor And Handoff Retarget

Goal:

Make the handoff and adapter path consume `DeliveryCommand` and emit the
converged delivery outcome contract.

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
- `RouteTargetedTaskDispatchHandoff` is either removed or reduced to a temporary
  non-mainline migration target with no new callers.
- Process-boundary batch records do not require a top-level shared `routeKey`.
  `routeKey` is present only per command/envelope when needed as opaque
  metadata.
- No queue keyed only by `routeKey` or `deliveryQueueKey` is valid for assigned
  polling delivery.

## DEX-4: Inbound Envelope And Result Boundary

Goal:

Make adapter ingress transport-owned and result lifecycle engine-owned.

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

Scope:

- WebSocket and socket adapters send command payloads through
  `sendToSelectedWorker(...)` and return the converged delivery outcome
  contract.
- Split assigned-task endpoint dispatch from raw/manual route dispatch. The task
  delivery path should depend on a selected-worker endpoint contract, while
  route-only helpers remain adapter-local or behind `RawWorkerMessageChannel`.
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
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am -DskipTests compile
```

Transport runtime guard and delivery tests:

```bash
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRuntimeRegistryTest,TransportDeliveryServiceTest,InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,RouteTargetedTaskDispatchSubmitterTest,RouteTargetedTaskDispatchBatchCodecTest,RouteTargetedTaskDispatchHandoffPumpTest,RedisRouteTargetedTaskDispatchHandoffTest
```

Adapter protocol tests:

```bash
./mvnw -q -pl transport/transport_api,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter test -Dtest=TaskDispatchItemTest,TaskResultReportTest,TransportResultEnvelopeTest,WebSocketTaskDispatchChannelTest,WebSocketInputProcessorTest,SocketTaskDispatchChannelTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest
```

SDK embedded/distributed proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
```

Guard scans to add or tighten as slices land:

```bash
rg -n "TaskDispatchContext|TaskDispatchBinding|TaskDispatchBatchListener|TaskResultIngestFacade|TaskResultCorrelation|RouteTargetedTaskDispatch" transport/transport_runtime/src/main/java
rg -n "TransportDispatchFailureHandler|compensate\\(" transport/transport_runtime/src/main/java
rg -n "com\\.xa\\.mass\\.transport" xa-mass-engine/src/main/java
rg -n "sendToAdapterRoute\\(" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/worker
rg -n "TaskResultIngestFacade|getResultCorrelation|TaskResultCorrelation|projectedAttemptId|leaseToken" transport/transport_runtime/src/main/java
rg -n "queue\\.put\\(|Thread\\.sleep\\(|LLEN|RPUSH" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/dispatch
rg -n "currentOwners\\(" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime
```

The `sendToAdapterRoute` scan is intentionally scoped to assigned task dispatch
paths. Adapter-local raw/manual side channels such as `RawWorkerMessageChannel`
remain allowed until DEX-5 splits the interfaces. Result-lifecycle scans should
target concrete owner leaks such as `TaskResultIngestFacade`,
`getResultCorrelation`, `TaskResultCorrelation`, `projectedAttemptId`, and
lease-token validation, not every occurrence of generic retryable transport
outcome vocabulary.

The command lists above must be corrected after DEX-0 if class names move or
old route-targeted tests are replaced by delivery-executor tests.

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

## Open Decisions

- Whether `DeliveryCommand` and `InboundEnvelope` belong in `transport_api` as
  stable transport contracts or in `transport_runtime` until the external
  worker protocol is cleaned up.
- Whether the final outcome type keeps the current `DispatchOutcome` name or is
  renamed to `DeliveryOutcome` in a single no-dual-track slice.
- Whether command payload should standardize on `TransportPacket`, raw bytes,
  JSON text, or a small opaque payload wrapper.
- Where the engine-to-delivery translator should live so it protects a real
  owner boundary without becoming a same-module pass-through wrapper.
- Whether `TaskDispatchItem` and `TaskResultReport` remain long-term worker
  protocol DTO names or move under a worker-protocol owner after this roadmap.
- How much result correlation metadata should remain in opaque transport
  correlation versus engine-owned result application input.
