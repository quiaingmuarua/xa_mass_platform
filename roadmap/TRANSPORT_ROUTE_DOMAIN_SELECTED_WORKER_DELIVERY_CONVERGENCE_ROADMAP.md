# Transport Adapter-Lane Selected-Worker Delivery Convergence Roadmap

Status: active roadmap; route-targeted handoff is an intermediate slice,
adapter-lane selected-worker delivery is the target.

Supersedes the remaining dispatch-semantics work in
`roadmap/TRANSPORT_ROUTE_KEY_DISPATCH_HANDOFF_CONVERGENCE_ROADMAP.md`. The old
roadmap remains an audit record for removing shared/node-targeted handoff
paths. This roadmap owns the current correction: transport delivery should be
driven by adapter dispatch locality plus the engine-selected worker, not by
routeKey semantics.

The file name keeps the earlier route-domain wording for continuity. The target
inside this document is adapter-lane selected-worker delivery.

Last reviewed against current worktree: 2026-06-11.

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
- `RouteTargetedTaskDispatchSubmitter` currently resolves routeKey, reads active
  route owners by `routeKey + adapterId`, filters them by
  `TaskDispatchBinding.workerId()`, and submits to a selected
  `transportNodeId` drain lane. This is a useful intermediate shape, but it
  still makes routeKey the producer lookup center.
- The current route-owner filter reads a route owner list and then filters by
  workerId. That is not acceptable for large groups or shared routeKeys.
- `RouteTargetedTaskDispatchBinding` stores `routeKey`, `adapterId`, and the
  original `TaskDispatchBinding`; selected worker is still reached through the
  original binding, and `adapterNodeId` / target dispatch lane are not yet
  named transport delivery facts on the route-targeted binding.
- `TransportDispatchEnvelope` now carries `deliveryQueueKey` and
  `selectedWorkerId` as first-class transport fields. WebSocket and socket task
  dispatch use the envelope selected-worker constraint for endpoint filtering;
  the packet payload worker id remains worker-facing wire metadata.
- `WorkerEndpointRegistry` has a route-only send method plus a worker-filtered
  overload. The overload can silently degrade selected-worker task dispatch to
  route-only send through the default implementation.
- WebSocket and socket session managers keep route-local endpoint indexes and
  then filter entries by workerId. That becomes O(route sessions) when one
  adapter node uses a shared routeKey.
- Polling assigned-task delivery now pulls by selectedWorkerId. The runtime
  resolves an internal shared deliveryQueueKey, and the store drains by
  `deliveryQueueKey + selectedWorkerId` rather than routeKey.
- `RouteTargetedTaskDispatchBatchCodec` currently wraps an encoded
  `TaskDispatchBatch` JSON string inside another route-targeted JSON record.
  That nested JSON is a convergence target because distributed handoff should
  serialize the process-boundary payload once.

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
2. `adapterNodeId` / `transportNodeId` is the physical dispatch locality. It may
   own queues, wakeup indexes, and dispatch threads.
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
  -> resolve adapter dispatch lane from binding adapterNodeId / endpoint evidence
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
adapter-lane:<adapterId>:<adapterNodeId-or-transportNodeId>:worker:<selectedWorkerId>
  -> connectionId / consumerId
```

Endpoint evidence record:

```text
consumer:<connectionId>
  adapterId
  adapterNodeId
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

Do not start by fixing only WebSocket/socket. Pulling by routeKey only has the
same correctness issue under shared or group-level routeKey.

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

## Phase 1: Make Selected Worker And Adapter Lane Explicit

Goal: stop recovering delivery facts from packet payload or routeKey shape.

Scope:

1. Add `selectedWorkerId()` and adapter-lane fields to the route-targeted
   binding or introduce a successor delivery binding.
2. Add `selectedWorkerId()` and adapter-lane fields to
   `TransportDispatchEnvelope` or an equivalent dispatch-only value consumed by
   adapters.
3. Keep worker-facing `TaskDispatchItem.workerId` JSON unchanged.
4. Update the distributed handoff codec so the explicit selected-worker and
   adapter-lane facts are preserved and validated.
5. Remove nested `taskBatchJson` double serialization from the distributed
   payload. Serialize the process-boundary record once.
6. Keep `TaskDispatchBinding.workerId()` as the engine-owned source until a
   separate engine binding cleanup renames it.

Acceptance:

1. WebSocket/socket task dispatch no longer obtains final-hop target by reading
   `TransportPacket.PAYLOAD_WORKER_ID` directly.
2. Pull task dispatch has an explicit selected-worker argument or envelope field
   available before polling.
3. Adapter-lane locality is visible at the handoff contract and is not inferred
   from routeKey.
4. Worker-facing payload still contains `workerId` for existing protocol/API
   consumers.
5. Distributed codec serializes the process-boundary payload once.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am -DskipTests compile
.\mvnw.cmd -q -pl transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am -Dtest=RouteTargetedTaskDispatchHandoffPumpTest,RouteTargetedTaskDispatchSubmitterTest,PollingWorkerAdapterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest test
```

## Phase 2: Adapter-Lane Dispatch Queues And Threads

Goal: move physical dispatch ownership from routeKey-centered queues to
adapter-node / transport-node local drain lanes.

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

## Phase 3: Indexed Endpoint Feasibility Lookup

Goal: make producer and adapter final-hop lookup direct by adapter lane plus
selected worker.

Scope:

1. Add or narrow route/endpoint-owner read APIs so dispatch can look up active
   consumers by adapter lane + selectedWorkerId.
2. Keep endpoint evidence primary truth as connection/consumer records.
3. Maintain selected-worker lookup as a derived delivery-feasibility index.
4. Validate derived index hits against endpoint evidence before delivery.
5. Ensure producer and adapters do not fallback to any other worker if the
   selected worker has no active consumer.
6. Keep worker-route or routeKey projections as SDK/operator projection only.

Acceptance:

1. Producer hot path does not read all active owners for a route and then
   linearly filter by workerId.
2. Push final-hop lookup does not iterate every endpoint under a shared
   routeKey to find selectedWorkerId.
3. Missing selected-worker consumer produces retryable delivery failure or
   engine-owned compensation, not transport fallback.
4. No transport Redis key stores worker capacity, reservation, active lease,
   dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,RouteTargetedTaskDispatchSubmitterTest,RouteEndpointIndexTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest test
rg -n "activeOwners\\([^\\n]*null\\)|entriesForRoute\\([^\\n]*routeKey\\).*worker|group:\\{groupId\\}:slots|worker:meta|worker:occupancy|owner-shards|worker-routes|route-presence" transport -g "*.java" -g "*.md"
```

## Phase 4: Selected-Worker Push Delivery

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

Verification:

```powershell
rg -n "sendToAdapterRoute\\([^,]+,[^,]+,[^,]+,[^,]+\\)|PAYLOAD_WORKER_ID" transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java -g "*.java"
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=CompositeWorkerEndpointRegistryTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest test
```

## Phase 5: Polling Selected-Worker Delivery Acceptance

Goal: complete the polling-worker pull path as a standalone correctness and
throughput acceptance surface.

Owner roadmap:
`roadmap/TRANSPORT_POLLING_SELECTED_WORKER_DELIVERY_CONVERGENCE_ROADMAP.md`.

Why separate:

- polling worker has no long-lived push session registry equivalent to
  WebSocket/socket;
- current polling delivery is routeKey queue plus worker-initiated poll, so
  routeKey-only pull can consume another worker's assigned item under shared or
  group-level routeKey;
- fixing polling is queue ownership convergence, not only endpoint-index
  convergence.

This parent roadmap only depends on the polling roadmap's completion criteria.
It must not duplicate polling implementation slices.

Acceptance:

1. The standalone polling roadmap is complete.
2. Mainline task pull is not routeKey-only for assigned task items.
3. Shared routeKey plus shared deliveryQueueKey polling is proven safe for at
   least two selected workers in memory and Redis-backed delivery stores.

## Phase 6: Lifecycle Vocabulary Split

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
   - task dispatch selected-worker send must not fallback to route-only send,
   - task pull must not poll routeKey-only for assigned task items,
   - producer endpoint lookup must not scan all consumers for a route in the
     dispatch hot path,
   - routeKey codecs stay outside transport runtime/adapters,
   - distributed codecs must not nest encoded JSON payloads merely to re-decode
     them in the next transport stage.
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
rg -n "routeKey-only|routeKey.*correctness|node-targeted inbox|transportNodeId.*target|PAYLOAD_WORKER_ID|getLatestOwnerByWorker|isWorkerReachable|findRouteOwners|taskBatchJson" transport sdk doc roadmap -g "*.java" -g "*.md"
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
