# Transport Route-Domain Selected-Worker Delivery Convergence Roadmap

Status: proposed successor roadmap.

Supersedes the remaining dispatch-semantics work in
`roadmap/TRANSPORT_ROUTE_KEY_DISPATCH_HANDOFF_CONVERGENCE_ROADMAP.md`. The old
roadmap remains useful as an audit record for removing shared/node-targeted
handoff paths, but its remaining routeKey-only wording is no longer the current
target.

## Summary

The current transport convergence should not remove `workerId` from dispatch.
By the time a task item reaches assignment-to-transport handoff, Scheduling
Plane has already selected a concrete worker. Transport must preserve that
selection as a delivery constraint while still keeping `routeKey` opaque and
route-domain owned.

Target model:

```text
engine assignment
  -> selectedWorkerId
  -> routeKey delivery domain resolved outside transport data-plane
  -> transport handoff keyed by routeKey plus selected consumer locality
  -> adapter sends only to the session under routeKey that matches selectedWorkerId
```

Not:

```text
transport
  -> choose any active worker under the routeKey
```

And not:

```text
transport
  -> use workerId as lifecycle truth, capacity truth, routeKey minting input,
     or a fallback scheduler when the selected worker is unavailable
```

## Current Code Observations

- `TaskDispatchBinding.workerId()` is current engine-selected execution identity
  carried across the engine -> transport handoff seam.
- SDK/starter default routeKey minting is worker-group-level through
  `CanonicalWorkerGroupRouteKeyCodec`, but transport runtime and adapters still
  treat the routeKey value as opaque.
- `RouteTargetedTaskDispatchSubmitter` already resolves routeKey, reads active
  route owners by `routeKey + adapterId`, filters them by
  `TaskDispatchBinding.workerId()`, and then submits to the selected
  `transportNodeId` drain lane.
- `RouteTargetedTaskDispatchBinding` currently stores `routeKey`, `adapterId`,
  and the original `TaskDispatchBinding`; it does not expose
  `selectedWorkerId` as a named transport delivery constraint.
- `TransportDispatchEnvelope` exposes adapter, route, attempt, and packet, but
  not a first-class selected-worker constraint. WebSocket and socket dispatch
  currently recover the worker constraint from
  `TransportPacket.PAYLOAD_WORKER_ID`.
- `WorkerEndpointRegistry` has a route-only send method plus a worker-filtered
  overload. The overload is behaviorally needed for group routeKeys, but the
  name and default fallback make the selected-worker constraint look optional.
- WebSocket and socket session managers keep route-local endpoint indexes keyed
  by routeKey plus workerId and use workerId to filter the final hop.
- Route-owner stores are already routeKey -> consumer-record stores. The
  `worker-route:<workerId>` Redis key is a derived SDK/operator projection, not
  route-owner truth.

## Owner Review

Scheduling Plane owns concrete worker selection. The selected worker id is part
of assignment output and remains task-lifecycle / scheduling evidence owned by
engine and worker runtime.

RouteKey minting belongs to engine/starter/SDK assembly or a future explicit
routing-policy owner. Transport consumes routeKey as an opaque delivery-domain
address and must not decode worker group or worker identity from it.

Transport owns delivery feasibility and delivery mechanics:

- route-consumer heartbeat/lease evidence,
- route-domain dispatch handoff and drain lanes,
- adapter selection by adapterId,
- final-hop session filtering for the already selected worker,
- delivery outcome reporting.

Transport does not own:

- worker matching,
- worker online/offline lifecycle,
- capacity/admission/reservation,
- retry and compensation policy,
- worker fallback selection when the selected worker cannot be reached.

## Boundary Decisions

1. `routeKey` is the opaque transport delivery domain. Current default is
   worker-group routeKey, but transport must not know that rule.
2. `selectedWorkerId` is a first-class delivery constraint derived from engine
   assignment. It is allowed inside transport because it prevents route-domain
   delivery from becoming random worker selection.
3. Transport may use `selectedWorkerId` only to narrow route-consumer evidence
   and final-hop adapter sessions. It must not use it for lifecycle state,
   scheduling, fallback selection, capacity, routeKey minting, or route-owner
   truth.
4. `transportNodeId` is locality and wakeup/drain partitioning after route-owner
   lookup. It is not business target truth.
5. `adapterId` is concrete endpoint/protocol identity. It may select the adapter
   implementation and local registry, but it is not queue ownership truth.
6. Missing route owner, missing selected-worker session, or offline transport
   node is delivery infeasible evidence. Compensation/retry stays engine-owned.

## Target Shape

Use an explicit transport delivery binding shape:

```text
routeKey
adapterId
selectedWorkerId
task dispatch context / binding identity
targetTransportNodeId
```

The exact class name can be decided during implementation. Acceptable paths:

- evolve `RouteTargetedTaskDispatchBinding` to expose
  `selectedWorkerId()` explicitly, or
- introduce a narrowly named value such as
  `RouteDeliveryConstraint` / `AssignedRouteDeliveryBinding` if that removes
  ambiguity without adding a pass-through layer.

Target call chain:

```text
RouteTargetedTaskDispatchSubmitter
  -> activeOwners(routeKey, adapterId)
  -> filter by selectedWorkerId
  -> submit route-domain/node-local handoff batch

RouteTargetedTaskDispatchListener
  -> create TransportDispatchEnvelope with explicit selectedWorkerId
  -> dispatch to adapterId

WebSocket/Socket adapter
  -> send to routeKey session matching selectedWorkerId
```

Route-only send remains valid only for explicit raw/debug/manual side-channels
where the caller intentionally does not target an engine-selected worker. It
must not be the task-dispatch fallback path.

## Non-Goals

- Do not remove `workerId` from engine dispatch binding merely to make transport
  look routeKey-only.
- Do not mint worker-specific routeKeys as a shortcut unless a separate owner
  decision changes the routing policy.
- Do not move worker scheduling, fallback selection, capacity, reservation, or
  lifecycle state into transport.
- Do not make polling, WebSocket, or socket adapter-specific sessions part of
  transport API.
- Do not change worker-facing task payload JSON in the first slice.
- Do not auto-delete old Redis keys at startup.

## Do Not Start With

Do not start by deleting the worker-filtered session send path. Under a
worker-group routeKey, that path is necessary to deliver the assigned item to
the worker selected by Scheduling Plane.

Do not start by encoding workerId into routeKey. That hides the selected-worker
constraint inside the route value and weakens routeKey opacity.

Do not start by renaming online/offline methods without first separating
delivery-feasibility evidence from worker lifecycle truth.

## Phase 0: Inventory And Vocabulary Freeze

Goal: freeze the actual selected-worker delivery path before changing code.

Scope:

1. Inventory all production and test call sites for:
   - `TaskDispatchBinding.workerId()`
   - `RouteTargetedTaskDispatchBinding`
   - `TransportDispatchEnvelope`
   - `TransportPacket.PAYLOAD_WORKER_ID`
   - `WorkerEndpointRegistry.sendToAdapterRoute(...)`
   - `RouteEndpointIndex`
   - `TransportRouteOwnerInspectionView`
   - `WorkerSystemEventChannel`
2. Classify each workerId read as:
   - engine-selected delivery constraint,
   - worker-facing payload field,
   - SDK/operator projection,
   - lifecycle/event ingress,
   - stale route/lifecycle residue.
3. Update owner docs so `workerId` in transport dispatch means
   `selectedWorkerId` constraint, not worker online/offline truth.

Acceptance:

1. Inventory proves task dispatch does not require transport to call worker
   runtime for second-stage selection.
2. Active docs distinguish `routeKey`, `selectedWorkerId`, `adapterId`, and
   `transportNodeId`.
3. Old wording that says transport data-plane must never read workerId is
   replaced with the narrower rule: transport may read selectedWorkerId only as
   delivery constraint.

Verification:

```powershell
rg -n "TaskDispatchBinding|PAYLOAD_WORKER_ID|sendToAdapterRoute|RouteEndpointIndex|isWorkerReachable|WorkerSystemEventChannel" transport sdk xa-mass-base xa-mass-engine -g "*.java" -g "*.md"
```

## Phase 1: Make Selected Worker Explicit In Delivery Contracts

Goal: remove the hidden dependency on packet payload workerId for task-dispatch
addressing.

Scope:

1. Add `selectedWorkerId()` to the route-targeted dispatch binding/envelope path
   or introduce an equivalent explicit delivery constraint value.
2. Keep worker-facing `TaskDispatchItem.workerId` JSON unchanged.
3. Make `TransportDispatchEnvelope` or a dispatch-only accessor expose the
   selected-worker constraint without adapters scraping packet payload.
4. Update `RouteTargetedTaskDispatchBatchCodec` so distributed JSON preserves
   the explicit selected-worker constraint and fails fast if it conflicts with
   the underlying assigned binding.
5. Keep `TaskDispatchBinding.workerId()` as the engine-owned source until a
   separate engine binding cleanup renames it.

Acceptance:

1. WebSocket/socket task dispatch no longer obtains final-hop target by reading
   `TransportPacket.PAYLOAD_WORKER_ID` directly.
2. The selected-worker constraint is visible at the route-targeted handoff
   contract.
3. Worker-facing payload still contains `workerId` for existing protocol/API
   consumers.
4. Compile passes for transport API/runtime and adapters.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -DskipTests compile
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=RouteTargetedTaskDispatchHandoffPumpTest,RouteTargetedTaskDispatchSubmitterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest test
```

## Phase 2: Narrow Endpoint Send Semantics

Goal: make final-hop adapter delivery say what it actually does.

Scope:

1. Replace the ambiguous worker-filtered overload with a named task-dispatch
   method, for example:

   ```java
   sendToSelectedWorkerRoute(adapterId, routeKey, selectedWorkerId, message)
   ```

2. Remove any default implementation that silently degrades selected-worker
   dispatch to route-only send.
3. Keep route-only send for explicit raw/debug/manual side-channels under a
   separate name or clearly documented method.
4. Update `CompositeWorkerEndpointRegistry`, `ServerSessionManager`, and
   `SocketSessionManager` to implement the explicit method.
5. Adapter dispatch must fail delivery when selectedWorkerId is present but no
   matching active session exists; it must not send to another worker under the
   routeKey.

Acceptance:

1. Task dispatch cannot accidentally fall back to route-only send when
   selectedWorkerId is present.
2. WebSocket and socket tests cover same routeKey with two worker sessions and
   prove only the selected worker receives the item.
3. Raw/debug route-only send tests remain separate from task dispatch tests.

Verification:

```powershell
rg -n "sendToAdapterRoute\\([^,]+,[^,]+,[^,]+,[^,]+\\)|PAYLOAD_WORKER_ID" transport -g "*.java"
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter -am -Dtest=CompositeWorkerEndpointRegistryTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest test
```

## Phase 3: Route-Owner Feasibility Semantics

Goal: keep route-owner truth route-domain based while allowing selected-worker
feasibility narrowing.

Scope:

1. Keep Redis route-owner primary truth as:

   ```text
   route:<encodedRouteKey>:consumers
   routes
   deadline
   worker-route:<workerId> projection only
   ```

2. Rename or document route-owner reads so producer-side dispatch is doing
   feasibility narrowing, not worker reachability or lifecycle judgment.
3. Ensure producer-side `RouteTargetedTaskDispatchSubmitter` does not fallback
   to any other worker if the selected worker has no active route consumer.
4. Keep `worker-route:<workerId>` as SDK/operator projection only. It must not
   be used to mint routeKey or perform worker selection.
5. Add pruning tests that remove stale consumer records and derived projection
   keys without touching worker-runtime/admission keys.

Acceptance:

1. Route-owner store tests prove multiple workers can share one routeKey and
   selected-worker filtering selects the correct consumer.
2. Missing selected-worker consumer produces retryable delivery failure or
   engine-owned compensation, not transport fallback.
3. No transport Redis key stores worker capacity, reservation, active lease,
   dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime -Dtest=InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,RouteTargetedTaskDispatchSubmitterTest test
rg -n "group:\\{groupId\\}:slots|worker:meta|worker:occupancy|owner-shards|worker-routes|route-presence" transport -g "*.java" -g "*.md"
```

## Phase 4: Lifecycle Vocabulary Split

Goal: stop transport route evidence from reading as worker lifecycle truth.

Scope:

1. Separate explicit worker system-event ingress from adapter route-consumer
   claim/release/heartbeat.
2. Decide whether `WorkerSystemEventChannel` remains transport-neutral ingress
   or moves/renames under a worker event owner.
3. Rename internal adapter route-owner operations away from online/offline
   vocabulary where they only mean connection lease evidence.
4. Keep public SDK worker APIs stable unless a separate SDK breaking-change
   decision is approved.
5. Reword `isWorkerReachable` docs/tests as SDK/operator inspection projection,
   not transport lifecycle truth.

Acceptance:

1. Adapter connect/disconnect/heartbeat tests do not assert worker lifecycle
   mutation from route-owner writes.
2. Active transport docs do not call route-owner lease evidence worker
   online/offline truth.
3. If `WorkerSystemEventChannel` remains, its docs state that it is explicit
   event ingress and not automatic adapter session lifecycle.

Verification:

```powershell
rg -n "worker online|worker offline|publishWorkerOnline|publishWorkerOffline|isWorkerReachable" transport sdk doc roadmap -g "*.java" -g "*.md"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -Dtest=TransportRouteOwnerInspectionViewTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,MassSdkTest test
```

## Phase 5: Docs, Guards, And Proof Registry

Goal: make the new boundary hard to regress.

Scope:

1. Update `transport/AGENTS.md` and
   `transport/TRANSPORT_BOUNDARY_BASELINE.md` with the final wording:

   ```text
   routeKey = delivery domain
   selectedWorkerId = engine-selected execution target constraint
   transportNodeId = locality / wakeup / drain partition
   adapterId = endpoint protocol/runtime identity
   ```

2. Update SDK README and `doc/INFRA_TRUTH_LAYERS.md` to stop describing
   transportNodeId as dispatch target truth.
3. Add architecture/source guards:
   - task dispatch adapters must not read `PAYLOAD_WORKER_ID` directly for
     endpoint addressing once explicit selectedWorkerId exists,
   - task dispatch selected-worker send must not fallback to route-only send,
   - transport dispatch must not use `getLatestOwnerByWorker`,
     `isWorkerReachable`, or `findRouteOwners(workerId)` in hot paths,
   - routeKey codecs stay outside transport runtime/adapters.
4. Update `doc/PROOF_REGISTRY.md` with the representative selected-worker
   delivery proof.
5. Mark the old route-key handoff roadmap as historical/superseded once this
   roadmap's proof and docs are current.

Acceptance:

1. Active docs use route-domain plus selected-worker delivery vocabulary
   consistently.
2. Guard tests fail on route-only fallback for task dispatch.
3. Proof registry points to route-owner, route-targeted handoff, and adapter
   selected-worker filtering tests.
4. No active roadmap claims routeKey-only transport dispatch when selected
   worker constraints are still required.

Verification:

```powershell
rg -n "routeKey-only|node-targeted inbox|transportNodeId.*target|PAYLOAD_WORKER_ID|getLatestOwnerByWorker|isWorkerReachable|findRouteOwners" transport sdk doc roadmap -g "*.java" -g "*.md"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am test
```

## Completion Criteria

This roadmap is complete only when:

1. Assignment-to-transport handoff has an explicit selected-worker delivery
   constraint.
2. Redis dispatch handoff remains route-domain owned with node-local lanes only
   as derived locality/wakeup partitions.
3. WebSocket/socket adapters deliver task dispatch only to the selected worker
   session under the routeKey and cannot fallback to an arbitrary route session.
4. Transport route-owner stores remain routeKey -> consumer evidence; worker id
   projections are inspection/feasibility metadata only.
5. Transport does not decide worker online/offline lifecycle, capacity,
   admission, retry, or replacement worker selection.
6. Active docs, proof registry, and guards all describe the same boundary.
