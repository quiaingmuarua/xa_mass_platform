# Transport Adapter-Lane Selected-Worker Delivery Convergence Roadmap

Status: completed and archived. Current transport truth is in
`transport/TRANSPORT_BOUNDARY_BASELINE.md`, `transport/AGENTS.md`, and
`doc/PROOF_REGISTRY.md`.

Supersedes the remaining dispatch-semantics work in
`doc/archive/transport/2026-06-12_TRANSPORT_ROUTE_KEY_DISPATCH_HANDOFF_CONVERGENCE_ROADMAP.md`.
The old roadmap remains an audit record for removing shared/node-targeted
handoff paths. This roadmap owns the current correction: transport delivery
should be driven by adapter dispatch locality plus the engine-selected worker,
not by routeKey semantics.

The file name keeps the earlier route-domain wording for continuity. The target
inside this document is adapter-lane selected-worker delivery.

Last reviewed against current worktree: 2026-06-12.

## Summary

The previous convergence correctly removed worker-level routeKey assumptions,
but it still over-promotes `routeKey`. In the target transport model,
`routeKey` is only an opaque coarse lane or protocol partition. It may be
worker-group-level, adapter-lane-level, a shared constant for one adapter node,
or another assembly-owned value. Transport must not rely on routeKey uniqueness
for correctness.

The delivery-critical facts are:

```text
adapterId          endpoint protocol/runtime identity
adapterNodeId      logical adapter deployment / WorkerGroup hosting relation
transportNodeId    runtime process locality / drain thread owner
selectedWorkerId   engine-selected execution target
routeKey           optional/coarse opaque lane metadata
```

Target lifecycle:

```text
Task item
  -> TaskWorkRuntime claim/lease
  -> Scheduling Plane selects WorkerSchedulingCandidate
  -> engine binds TaskDispatchBinding(
       workerId,
       workerGroupId,
       adapterId,
       adapterNodeId,
       ...
     )
  -> transport handoff carries adapter lane + selectedWorkerId
  -> adapter-node / transport-node dispatch loop drains local work
  -> adapter delivers only to selectedWorkerId
```

This means an adapter node may intentionally use one shared routeKey and run
its own dispatch threads. Correctness then comes from `selectedWorkerId` plus
indexed endpoint evidence, not from minting many routeKeys.

The performance target is part of the ownership target:

- no route-level batch scan to find one selected worker,
- no queue poll-and-discard loop for pull workers,
- no worker-resource lookup or second-stage worker selection in transport,
- no routeKey decode to recover worker/group/adapter semantics,
- no routeKey cardinality requirement for correctness,
- no repeated object -> JSON -> object conversions inside one JVM hot path,
- no adapter scraping `TransportPacket.PAYLOAD_WORKER_ID` for endpoint
  addressing once the explicit delivery contract exists.

## Current Code Observations

- `TaskDispatchBinding.workerId()` is the current engine-selected execution
  identity carried across the engine -> transport seam.
- `TaskDispatchBinding.adapterNodeId()` already exists and is populated by the
  engine binding path, but the current transport handoff does not use it as the
  physical dispatch-lane owner.
- SDK/starter default routeKey minting is worker-group-level through
  `CanonicalWorkerGroupRouteKeyCodec`, but transport runtime and adapters must
  treat the routeKey value as opaque and low-significance.
- `RouteTargetedTaskDispatchSubmitter` now validates explicit `adapterId` and
  `selectedWorkerId`, resolves the opaque routeKey only as metadata/domain
  context, and finds the active delivery owner through
  `WorkerDispatchRouteOwnerView.activeOwnerForSelectedWorker(adapterId,
  selectedWorkerId)`. It no longer scans all active route owners under a shared
  routeKey to find the selected worker.
- `RouteTargetedTaskDispatchBinding` now carries `routeKey`,
  `AdapterDispatchLane`, explicit `selectedWorkerId`, and the original
  `TaskDispatchBinding`. The current executable lane formula is
  `AdapterDispatchLane(adapterId, transportNodeId)` where `adapterId` comes
  from `TaskDispatchBinding.adapterId()` and `transportNodeId` comes from the
  selected route-owner evidence.
- `TransportDispatchEnvelope` now carries `deliveryQueueKey` and
  `selectedWorkerId` as first-class transport fields. WebSocket and socket task
  dispatch use the envelope selected-worker constraint for endpoint filtering;
  the packet payload worker id remains worker-facing wire metadata.
- `WorkerEndpointRegistry` now has an explicit `sendToSelectedWorker(...)`
  task-dispatch method with no default route-only fallback. Route-only send
  remains only for raw/manual side channels.
- WebSocket and socket session managers maintain selected-worker endpoint
  indexes through `RouteEndpointIndex.entriesForWorker(...)`. Task dispatch
  no longer iterates every endpoint under a shared routeKey for the final hop.
- Polling assigned-task delivery now pulls by selectedWorkerId. The runtime
  resolves an internal shared deliveryQueueKey, and the store drains by
  `deliveryQueueKey + selectedWorkerId` rather than routeKey.
- `RouteTargetedTaskDispatchBatchCodec` now serializes the process-boundary
  route-targeted payload once. `taskBatchJson` is removed from the mainline
  codec shape and guarded by `RouteTargetedTaskDispatchBatchCodecTest`.

Current physical handoff state:

- Redis dispatch handoff physical keys are adapter-lane queues with
  `transportNodeId` ready-lane indexes. `routeKey` remains batch metadata and
  adapter correlation only; it is no longer the Redis dispatch queue partition.
- The current executable lane formula remains `adapterId + transportNodeId`.
  Adding `adapterNodeId` to endpoint evidence is a separate future refinement,
  not a blocker for selected-worker correctness or routeKey downgrading.

## Owner Review

Scheduling Plane owns concrete worker selection. `selectedWorkerId` is the
assignment output and remains task-lifecycle / scheduling evidence owned by
engine and worker runtime.

Worker runtime / scheduling evidence owns `adapterNodeId` and
`NodeGroupBinding` relation truth. Transport may use adapter-node evidence as
dispatch locality after assignment, but it must not use adapterNodeId to select
or replace a worker.

Transport owns delivery mechanics:

- adapter-node / transport-node dispatch loops and queue draining,
- endpoint connection evidence needed to deliver to selectedWorkerId,
- routeKey as optional/coarse opaque lane metadata,
- adapter selection by adapterId,
- delivery outcome reporting.

Transport does not own:

- worker matching,
- worker online/offline lifecycle,
- capacity/admission/reservation,
- retry and compensation policy,
- replacement worker selection when the selected worker cannot be reached.

## Boundary And Performance Decisions

1. `selectedWorkerId` is the correctness key for the final worker hop.
2. Adapter-lane identity must be typed and deterministic. The first executable
   slice must define exactly which fields form the lane, where each field is
   sourced from, and whether a missing field is a retryable delivery-infeasible
   condition or an engine compensation event. Do not use
   `adapterNodeId-or-transportNodeId` as an implementation rule.
3. `routeKey` is not a correctness key. It can be shared, coarse, or policy
   minted outside transport.
4. Transport may carry routeKey for protocol correlation, backpressure grouping,
   diagnostics, or compatibility, but it must not require high routeKey
   cardinality to avoid wrong-worker delivery.
5. Transport may use selectedWorkerId only for delivery feasibility lookup,
   selected-worker queue sub-lanes, and final-hop endpoint filtering under the
   already assigned adapter lane.
6. Transport must not use selectedWorkerId for lifecycle state, scheduling,
   fallback selection, capacity, routeKey minting, or route-owner truth.
7. Missing endpoint evidence, missing selected-worker pull lane, or offline
   adapter/transport node is delivery infeasible evidence.
   Compensation/retry remains engine-owned.
8. Hot paths must be direct keyed lookups:
   - producer feasibility lookup should be by adapter lane + selectedWorkerId,
   - push final-hop lookup should be by adapter lane + selectedWorkerId,
   - pull poll should be by adapter lane + selectedWorkerId,
   - route-owner pruning may scan bounded maintenance structures, but task
     dispatch and task pull must not depend on full-route scans.
9. Cross-process codecs may encode payloads once. In-memory handoff, adapter
   dispatch, and pull delivery must not re-encode and re-decode payloads merely
   to recover fields already present on the Java delivery object.

## Target Shape

Use explicit delivery values. The current class names may evolve, but the
contract should contain these facts:

```text
AssignedTransportDeliveryBinding
  adapterId
  adapterNodeId
  targetTransportNodeId
  selectedWorkerId
  optional routeKey / coarse lane
  TaskDispatchBinding / assignment identity

TransportDispatchEnvelope
  adapterId
  adapterNodeId
  targetTransportNodeId
  selectedWorkerId
  optional routeKey
  packet
  delivery identity / attempt identity
```

Target producer chain:

```text
RouteTargetedTaskDispatchSubmitter or successor
  -> resolve typed adapter dispatch lane from binding + endpoint evidence
  -> findActiveConsumer(adapterLane, selectedWorkerId)
  -> enqueue to adapter-node / transport-node local drain lane
```

Target push chain:

```text
Adapter-node dispatch loop
  -> creates TransportDispatchEnvelope(selectedWorkerId, adapterNodeId, ...)
  -> dispatches to adapterId
  -> adapter sends to selectedWorkerId endpoint
```

Target pull chain:

```text
PullWorkerSession(workerId, adapterNodeId, optional routeKey)
  -> pollTaskMessagesResult(adapterLane, selectedWorkerId, ...)
  -> transport delivery store drains selected-worker lane
  -> worker receives only items assigned to itself
```

Route-only send and route-only pull remain valid only for explicit raw,
debug, or manual side-channels where the caller intentionally does not target
an engine-selected worker. They must not be task-dispatch fallbacks.

## Target Runtime Indexes

Primary delivery-feasibility index:

```text
adapter-lane:<adapterId>:<lanePartition>:worker:<selectedWorkerId>
  -> connectionId / consumerId
```

`lanePartition` is not an "or" placeholder. The current executable formula is
`AdapterDispatchLane(adapterId, transportNodeId)`, where `adapterId` comes from
`TaskDispatchBinding.adapterId()` and `transportNodeId` is sourced from active
route-owner endpoint evidence. `adapterNodeId` remains binding-side relation
evidence until a separate slice adds it to endpoint evidence writes and reads.

If the selected formula cannot be computed from the engine binding and active
endpoint evidence, dispatch must fail as delivery-infeasible and invoke
engine-owned compensation. It must not fallback to routeKey or worker reselection.

Endpoint evidence record:

```text
consumer:<connectionId>
  adapterId
  adapterNodeId  optional, not part of the current executable lane formula
  transportNodeId
  selectedWorkerId / workerId
  optional routeKey
  leaseExpireAt
  updatedAt
```

Optional routeKey index:

```text
route:<encodedRouteKey>:consumers
```

Rules:

- the adapter-lane + selected-worker index is delivery-feasibility evidence,
  not worker lifecycle truth;
- stale index hits must be validated against the endpoint evidence record before
  delivery;
- routeKey indexes are secondary and must not be the only way to deliver an
  assigned task item;
- no transport Redis key may store worker capacity, reservation, runtime lease,
  dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.

Transport queues should converge to adapter-lane ownership:

```text
dispatch:node:<transportNodeId>:q
delivery:lane:<adapterLane>:worker:<selectedWorkerId>:q
```

If routeKey is needed for backpressure or diagnostics, keep it as metadata or a
coarse secondary partition. Do not make routeKey uniqueness a correctness
precondition.

## Non-Goals

- Do not remove `workerId` from engine dispatch binding merely to make
  transport look routeKey-only.
- Do not mint worker-specific routeKeys as a shortcut.
- Do not make adapterNodeId a scheduler inside transport. AdapterNode is
  selected before transport handoff or derived from endpoint evidence.
- Do not move worker scheduling, fallback selection, capacity, reservation, or
  lifecycle state into transport.
- Do not let polling, WebSocket, or socket adapter-specific session types leak
  into transport API.
- Do not change worker-facing task payload JSON in the first slice.
- Do not auto-delete old Redis keys at startup.
- Do not add a broad observability read model or reconciliation loop to make up
  for missing hot-path indexes.

## Do Not Start With

Do not start by encoding workerId into routeKey. That hides the delivery
constraint inside the route value and weakens routeKey opacity.

Do not restart polling as part of this parent roadmap. Polling selected-worker
delivery is now a completed prerequisite: task pull drains by
`deliveryQueueKey + selectedWorkerId`, and the next work must preserve that
contract while converging push and distributed handoff.

Do not start by adding route-level scans and then promising to optimize later.
The owner boundary and performance boundary are the same boundary here.

Do not start by turning AdapterNode into worker selection inside transport.
AdapterNode is dispatch locality after assignment, not a replacement for
Scheduling Plane.

Do not start by renaming online/offline methods without first separating
delivery-feasibility evidence from worker lifecycle truth.

## Phase 0: Inventory And Hot-Path Classification

Goal: freeze every selected-worker delivery read before changing contracts.

Scope:

1. Inventory production and test call sites for:
   - `TaskDispatchBinding.workerId()`
   - `TaskDispatchBinding.adapterNodeId()`
   - `RouteTargetedTaskDispatchBinding`
   - `TransportDispatchEnvelope`
   - `TransportPacket.PAYLOAD_WORKER_ID`
   - `WorkerEndpointRegistry.sendToAdapterRoute(...)`
   - `TaskPullChannel.pollTaskMessagesResult(...)`
   - `TransportDeliveryStore.poll(...)` / `drain(...)`
   - `RouteEndpointIndex`
   - `TransportRouteOwnerInspectionView`
   - `WorkerSystemEventChannel`
2. Classify each workerId read as:
   - engine-selected delivery constraint,
   - worker-facing payload field,
   - SDK/operator projection,
   - lifecycle/event ingress,
   - stale route/lifecycle residue.
3. Classify each routeKey-only task delivery path as:
   - valid raw/manual side-channel,
   - push task dispatch that must become selected-worker targeted,
   - pull task dispatch that must become selected-worker targeted,
   - test fixture or stale vocabulary.
4. Identify routeKey-centered hot-path scans, routeKey uniqueness assumptions,
   and avoidable encode/decode cycles.

Acceptance:

1. Inventory proves task dispatch does not require transport to call worker
   runtime for second-stage selection.
2. Active docs distinguish `selectedWorkerId`, `adapterId`, `adapterNodeId`,
   `transportNodeId`, and optional/coarse `routeKey`.
3. Pull and push paths are both represented in the implementation plan.
4. The first implementation slice has no dependency on route-level scanning.

Verification:

```powershell
rg -n "TaskDispatchBinding|adapterNodeId|PAYLOAD_WORKER_ID|sendToAdapterRoute|pollTaskMessagesResult|TransportDeliveryStore|RouteEndpointIndex|isWorkerReachable|WorkerSystemEventChannel" transport sdk xa-mass-base xa-mass-engine -g "*.java" -g "*.md"
```

## Phase 1: Complete Explicit Handoff Facts And Codec

Status: landed in the current implementation slice; keep this phase as the
regression checklist for handoff facts and codec shape.

Goal: stop recovering delivery facts from packet payload or routeKey shape.

Already landed baseline:

- `TransportDispatchEnvelope` carries `deliveryQueueKey` and
  `selectedWorkerId`.
- Polling task delivery polls by selectedWorkerId under an internal
  deliveryQueueKey.
- WebSocket/socket task dispatch consumes envelope selected-worker metadata
  instead of scraping `TransportPacket.PAYLOAD_WORKER_ID` for the final hop.

Scope:

1. Add `selectedWorkerId()` and adapter-lane fields to the route-targeted
   binding or introduce a successor delivery binding.
2. Define the first executable adapter-lane formula used by distributed
   handoff as a typed value, for example
   `AdapterLane(adapterId, lanePartition, selectedWorkerId)` or equivalent.
   The formula must list field sources:
   - `adapterId` from `TaskDispatchBinding.adapterId()`,
   - `selectedWorkerId` from `TaskDispatchBinding.workerId()`,
   - `lanePartition` from active endpoint evidence, unless this slice adds and
     proves `adapterNodeId` evidence.
3. Define missing-field behavior explicitly. Missing adapterId,
   selectedWorkerId, or lanePartition must produce delivery-infeasible
   compensation/retry evidence; it must not use routeKey as a fallback lane.
4. Keep worker-facing `TaskDispatchItem.workerId` JSON unchanged.
5. Update the distributed handoff codec so the explicit selected-worker and
   adapter-lane facts are preserved and validated.
6. Remove nested `taskBatchJson` double serialization from the distributed
   payload. Serialize the process-boundary record once.
7. Keep `TaskDispatchBinding.workerId()` as the engine-owned source until a
   separate engine binding cleanup renames it.

Acceptance:

1. Route-targeted handoff payloads carry selectedWorkerId and adapter-lane facts
   as first-class process-boundary fields.
2. Adapter-lane locality is visible at the handoff contract and is not inferred
   from routeKey.
3. Producer and consumer compute the same typed lane from documented field
   sources; target indexes and implementation slices no longer use a
   field-choice placeholder as the lane formula.
4. Missing adapterId, selectedWorkerId, or lanePartition is handled as
   delivery-infeasible evidence and does not fallback to routeKey.
5. Worker-facing payload still contains `workerId` for existing protocol/API
   consumers.
6. Distributed codec serializes the process-boundary payload once.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am -DskipTests compile
.\mvnw.cmd -q -pl transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am -Dtest=RouteTargetedTaskDispatchHandoffPumpTest,RouteTargetedTaskDispatchSubmitterTest,RouteTargetedTaskDispatchBatchCodecTest,PollingWorkerAdapterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest test
rg -n "adapterNodeId-or-transportNodeId|taskBatchJson" transport/transport_runtime/src/main/java transport/transport_runtime/src/test/java -g "*.java"
```

## Phase 2: Indexed Consumer Feasibility Lookup

Status: landed in the current implementation slice for producer route-owner
lookup and WebSocket/socket endpoint indexes. Remaining work belongs to Phase 4
physical queue ownership, not route-level scan-and-filter.

Goal: make producer and adapter final-hop lookup direct by adapter lane plus
selected worker before moving queue ownership.

Scope:

1. Add or narrow route/endpoint-owner read APIs so dispatch can look up active
   consumers by adapter lane + selectedWorkerId.
2. Keep endpoint evidence primary truth as connection/consumer records.
3. Maintain selected-worker lookup as a derived delivery-feasibility index.
4. Validate derived index hits against endpoint evidence before delivery.
5. Ensure producer and adapters do not fallback to any other worker if the
   selected worker has no active consumer.
6. Keep worker-route or routeKey projections as SDK/operator projection only.
7. Keep routeKey lookup available only for bounded maintenance, diagnostics,
   raw side-channels, or transitional proof; it must not be the task-dispatch
   hot-path lookup.

Acceptance:

1. Producer hot path does not read all active owners for a route and then
   linearly filter by workerId.
2. Push final-hop lookup does not iterate every endpoint under a shared
   routeKey to find selectedWorkerId.
3. Missing selected-worker consumer produces retryable delivery failure or
   engine-owned compensation, not transport fallback.
4. No transport Redis key stores worker capacity, reservation, active lease,
   dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.
5. Tests prove two workers sharing one routeKey are addressed by direct
   selected-worker evidence, not by route-local scan-and-filter.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,RouteTargetedTaskDispatchSubmitterTest,RouteEndpointIndexTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest test
rg -n "activeOwners\\([^\\n]*null\\)|entriesForRoute\\([^\\n]*routeKey\\).*worker|group:\\{groupId\\}:slots|worker:meta|worker:occupancy|owner-shards|worker-routes|route-presence" transport -g "*.java" -g "*.md"
```

## Phase 3: Selected-Worker Push Delivery

Status: landed in the current implementation slice; keep this phase as the
guard set for push adapters and endpoint registry contracts.

Goal: make final-hop push adapter delivery say what it actually does.

Scope:

1. Replace the ambiguous worker-filtered overload with a named task-dispatch
   method, for example:

   ```java
   sendToSelectedWorker(adapterLane, selectedWorkerId, message)
   ```

2. Remove any default implementation that silently degrades selected-worker
   dispatch to route-only send.
3. Keep route-only send for explicit raw/debug/manual side-channels under a
   separate documented method.
4. Update `CompositeWorkerEndpointRegistry`, `ServerSessionManager`, and
   `SocketSessionManager` to implement the explicit selected-worker method.
5. Adapter dispatch must fail delivery when selectedWorkerId is present but no
   matching active session exists; it must not send to another worker.

Acceptance:

1. Task dispatch cannot fallback to route-only send when selectedWorkerId is
   present.
2. WebSocket and socket tests cover one shared routeKey with two worker
   sessions and prove only the selected worker receives the item.
3. Raw/debug route-only send tests remain separate from task dispatch tests.
4. A guard fails if task dispatch adapters read `PAYLOAD_WORKER_ID` for
   endpoint addressing after explicit selectedWorkerId exists.
5. A guard fails if `WorkerEndpointRegistry` or a successor selected-worker
   endpoint interface provides a default implementation that drops
   selectedWorkerId and delegates to route-only send.

Verification:

```powershell
rg -n "default boolean sendToAdapterRoute\\([^\\n]*workerId|return sendToAdapterRoute\\([^\\n]*routeKey,[^\\n]*message\\)" transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java
rg -n "PAYLOAD_WORKER_ID|getPayload\\(|payload\\(\\)" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher -g "*.java"
rg -n "sendToAdapterRoute\\(" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher -g "*.java"
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=CompositeWorkerEndpointRegistryTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest test
```

## Phase 4: Adapter-Lane Dispatch Queues And Threads

Status: landed in the current implementation slice for Redis/process-boundary
physical keys. Future adapter-node evidence can refine the lane formula without
making routeKey a correctness key again.

Goal: move physical dispatch ownership from routeKey-centered queues to
adapter-node / transport-node local drain lanes after direct selected-worker
feasibility lookup exists.

Scope:

1. Define adapter dispatch lane identity from `adapterId`,
   `adapterNodeId`, and/or resolved `transportNodeId`.
2. Make producer handoff enqueue to the selected adapter lane after assignment.
3. Keep routeKey as metadata or secondary coarse lane only.
4. Let adapter nodes run bounded dispatch threads over their local lanes.
5. Preserve backpressure per adapter lane and selected-worker sub-lane where
   needed.

Acceptance:

1. Handoff queue ownership no longer requires routeKey uniqueness.
2. One adapter node can use one shared routeKey while dispatching multiple
   selected workers correctly.
3. Dispatch thread count is bounded by adapter-node/runtime config, not by
   routeKey cardinality.
4. Missing/offline adapter lane produces engine-owned compensation, not
   transport worker reselection.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk -am -Dtest=RouteTargetedTaskDispatchSubmitterTest,MassApplicationDistributedTransportTest test
rg -n "route:<.*>:node|routeKey.*thread|new Thread|Executors\\.new.*route" transport sdk -g "*.java" -g "*.md"
```

## Phase 5: Polling Selected-Worker Delivery Prerequisite

Goal: keep the completed polling-worker pull convergence as a prerequisite and
guardrail for the remaining push/handoff work.

Owner roadmap:
`doc/archive/transport/2026-06-12_TRANSPORT_POLLING_SELECTED_WORKER_DELIVERY_CONVERGENCE_ROADMAP.md`.

Current state:

- polling worker has no long-lived push session registry equivalent to
  WebSocket/socket;
- assigned polling delivery now uses worker-initiated poll by
  `selectedWorkerId`;
- the runtime resolves a shared internal `deliveryQueueKey`, and the delivery
  store drains by `deliveryQueueKey + selectedWorkerId`;
- shared routeKey and shared deliveryQueueKey are valid for polling task
  delivery because correctness comes from selectedWorkerId.

This parent roadmap must not duplicate polling implementation slices. It should
only keep regression guards while push and distributed handoff converge.

Acceptance:

1. The standalone polling roadmap remains complete.
2. Mainline task pull remains non-routeKey-only for assigned task items.
3. Shared routeKey plus shared deliveryQueueKey polling remains proven safe for
   at least two selected workers in memory and Redis-backed delivery stores.

## Phase 6: Lifecycle Vocabulary Split

Status: validated by current guards for adapter/session mainline. No additional
code move was needed in this slice because adapter session paths do not publish
worker lifecycle events.

Goal: stop transport endpoint evidence from reading as worker lifecycle truth.

Scope:

1. Separate explicit worker system-event ingress from adapter endpoint
   claim/release/heartbeat.
2. Keep `WorkerSystemEventChannel` as explicit event ingress unless a separate
   worker-event owner move is approved.
3. Rename internal adapter endpoint evidence operations away from online/offline
   vocabulary where they only mean connection lease evidence.
4. Keep public SDK worker APIs stable unless a separate SDK breaking-change
   decision is approved.
5. Reword `isWorkerReachable` docs/tests as SDK/operator inspection projection,
   not transport lifecycle truth.

Acceptance:

1. Adapter connect/disconnect/heartbeat tests do not assert worker lifecycle
   mutation from endpoint evidence writes.
2. Active transport docs do not call endpoint lease evidence worker
   online/offline truth.
3. If `WorkerSystemEventChannel` remains, its docs state that it is explicit
   event ingress and not automatic adapter session lifecycle.

Verification:

```powershell
rg -n "worker online|worker offline|publishWorkerOnline|publishWorkerOffline|isWorkerReachable" transport sdk doc roadmap -g "*.java" -g "*.md"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -Dtest=TransportRouteOwnerInspectionViewTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,MassSdkTest test
```

## Phase 7: Docs, Guards, And Proof Registry

Status: landed in the current implementation slice; keep this phase as the
regression checklist for owner docs, proof registry rows, and source guards.

Goal: make the boundary and performance constraints hard to regress.

Scope:

1. Update `transport/AGENTS.md` and
   `transport/TRANSPORT_BOUNDARY_BASELINE.md` with final wording:

   ```text
   selectedWorkerId = engine-selected execution target constraint
   adapterNodeId = logical adapter deployment / hosting relation
   transportNodeId = runtime locality / wakeup / drain partition
   adapterId = endpoint protocol/runtime identity
   routeKey = optional/coarse opaque lane metadata, not correctness key
   ```

2. Update SDK README and `doc/INFRA_TRUTH_LAYERS.md` to stop describing routeKey
   or transportNodeId as worker-selection truth.
3. Add architecture/source guards:
   - task dispatch adapters must not read `PAYLOAD_WORKER_ID` directly for
     endpoint addressing once explicit selectedWorkerId exists,
   - task dispatch selected-worker send must not fallback to route-only send;
     specifically, endpoint registry interfaces must not provide a default
     selected-worker method that delegates to a route-only method,
   - task pull must not poll routeKey-only for assigned task items,
   - producer endpoint lookup must not scan all consumers for a route in the
     dispatch hot path,
   - routeKey codecs stay outside transport runtime/adapters,
   - distributed codecs must not nest encoded JSON payloads merely to re-decode
     them in the next transport stage; `RouteTargetedTaskDispatchBatchCodecTest`
     or an equivalent guard must assert the serialized process-boundary record
     does not contain `taskBatchJson`.
4. Update `doc/PROOF_REGISTRY.md` with representative selected-worker delivery
   proof for push and pull.
5. Mark the old route-key handoff roadmap as historical/superseded once this
   roadmap's proof and docs are current.

Acceptance:

1. Active docs use adapter-lane plus selected-worker delivery vocabulary
   consistently.
2. Guards fail on route-only task dispatch fallback, route-only task pull, and
   payload-scraping endpoint addressing.
3. Proof registry points to adapter-lane handoff, endpoint lookup, push
   selected-worker filtering, pull selected-worker filtering, and codec
   single-serialization tests.
4. No active roadmap claims routeKey uniqueness is required for task dispatch
   correctness.

Verification:

```powershell
rg -n "routeKey-only|routeKey.*correctness|node-targeted inbox|transportNodeId.*target|PAYLOAD_WORKER_ID|getLatestOwnerByWorker|isWorkerReachable|findRouteOwners|taskBatchJson" transport sdk doc -g "*.java" -g "*.md"
rg -n "default boolean sendToAdapterRoute\\([^\\n]*workerId|return sendToAdapterRoute\\([^\\n]*routeKey,[^\\n]*message\\)" transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am test
```

## Completion Criteria

This roadmap is complete only when:

1. Assignment-to-transport handoff has explicit selected-worker and adapter-lane
   delivery facts.
2. Dispatch handoff ownership no longer requires routeKey uniqueness; routeKey
   may be shared without wrong-worker delivery.
3. Adapter-node / transport-node local dispatch threads are bounded and drain
   local lanes without turning routeKey cardinality into thread cardinality.
4. Producer-side endpoint feasibility lookup is indexed by adapter lane plus
   selectedWorkerId or an equivalent direct key; it does not scan all consumers
   for a route in the dispatch hot path.
5. WebSocket/socket adapters deliver task dispatch only to the selected worker
   endpoint and cannot fallback to an arbitrary route endpoint.
6. The standalone polling selected-worker delivery roadmap is complete:
   polling workers sharing one routeKey and one deliveryQueueKey cannot consume
   another worker's selected item, and the pull path does not poll-and-discard a
   whole shared queue to find matching workerId.
7. Transport endpoint evidence remains connection/consumer evidence; worker id
   indexes are delivery-feasibility or SDK/operator projection metadata only.
8. Transport does not decide worker online/offline lifecycle, capacity,
   admission, retry, or replacement worker selection.
9. Distributed codecs serialize the process-boundary payload once and do not
   preserve nested JSON strings as a mainline contract.
10. Active docs, proof registry, and guards all describe the same adapter-lane,
    selected-worker, and hot-path performance constraints.
