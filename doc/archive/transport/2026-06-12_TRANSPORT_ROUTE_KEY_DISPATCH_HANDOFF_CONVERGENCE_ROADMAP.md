# Transport Message And Route-Key Handoff Convergence Roadmap

Status: superseded and archived audit record.

Successor archive:
`doc/archive/transport/2026-06-12_TRANSPORT_ROUTE_DOMAIN_SELECTED_WORKER_DELIVERY_CONVERGENCE_ROADMAP.md`.

This record remains as audit history for the shared/node-targeted handoff
removal and route-targeted Redis implementation. New work on the routeKey plus
selected worker delivery boundary should use the successor roadmap.

Last verified against current code/worktree: 2026-06-11.

Successor update, 2026-06-12:

- The successor selected-worker delivery roadmap has moved Redis dispatch
  physical keys from route-domain queues to adapter-lane queues. The route-key
  dispatch key manifest retained below is historical audit evidence for this
  superseded roadmap, not current implementation truth.

Implementation outcome:

- SDK/starter default routeKey minting is currently worker-group-level through
  `CanonicalWorkerGroupRouteKeyCodec`, but routeKey remains an opaque delivery
  domain key and may later be minted from adapter lane, worker group, or another
  assembly-owned routing policy.
- Distributed dispatch handoff is route-targeted through
  `RouteTargetedTaskDispatchHandoff`; old shared/node-targeted handoff classes
  are removed from mainline.
- Transport consumers drain already resolved `routeKey + adapterId` delivery
  targets and binding-level selected worker constraints; they do not call
  worker-resource runtime for second-stage selection.
- At this roadmap's closure point, Redis route-targeted handoff used
  node-local physical drain lanes under the route-targeted namespace after the
  selected route consumer was resolved. The successor roadmap later moved
  current Redis dispatch physical keys to adapter-lane queues.
- Transport route-owner state is routeKey/consumer based; worker id is optional
  metadata for inspection/projection.
- Adapter session connect/disconnect/heartbeat updates route-owner lease
  evidence only and does not publish worker online/offline/heartbeat.
- Distributed result inbox accepts `TransportResultEnvelope`; report-only Redis
  ingress is rejected instead of creating an `unknown` envelope.

## Summary

The root problem is not only that distributed dispatch currently uses
`transportNodeId`. The deeper drift is that transport message handling, worker
result ingress, and route-owner evidence evolved through several concepts but
the source contracts were not corrected after the concepts changed.

Target correction:

- `routeKey` is the opaque worker-consumption address for transport.
- `transportNodeId` is only a connection/process locality concept.
- `adapterId` is protocol/endpoint metadata.
- current SDK/starter default assembly mints routeKey as a worker-group
  consumption address, but routeKey semantically means a transport delivery
  universe/lane. A future assembly policy may include adapter lane or another
  owner-level key. Transport still treats the value as opaque and must not know
  or enforce the minting rule.
- worker lifecycle and worker-runtime reachability/admission are not transport
  truth.
- result ingress is transport envelope handling followed by engine-owned result
  convergence, not a second transport-owned result lifecycle.

The target dispatch model is:

```text
engine assignment result
  -> resolve opaque routeKey outside transport data-plane
  -> keep selected target worker as item-level delivery constraint
  -> enqueue dispatch work by routeKey plus selected route consumer locality
  -> active route consumer drains local route work
  -> adapter endpoint filters the route's connections by selected worker
```

Not:

```text
engine assignment result
  -> resolve routeKey
  -> extract transportNodeId
  -> submit to node inbox as mainline truth
```

And not:

```text
transport data-plane
  -> decode routeKey as workerGroupId + workerId
  -> infer worker online/offline or worker selection
```

And not:

```text
transport data-plane
  -> read TaskDispatchBinding.workerId
  -> reinterpret a group route as a worker route
  -> decide which worker should consume the item
```

## Pre-Convergence Observations

The following was the starting-state drift that this slice removed. These names
are retained here only as audit evidence and must not be reintroduced as active
transport mainline:

- `RedisTaskDispatchHandoff` implements `TaskDispatchHandoff` as one shared
  Redis queue: `<namespace>:queue`. It has no routeKey partition.
- `RedisNodeTargetedTaskDispatchHandoff` implements
  `NodeTargetedTaskDispatchHandoff` as per-node queues:
  `<namespace>:node:<transportNodeId>:queue`. It makes node locality the
  submit target.
- `TaskDispatchHandoffPump` drains a handoff queue and forwards whole
  `TaskDispatchBatch` values to `TransportRoutingTaskDispatchListener`.
- `TransportRoutingTaskDispatchListener` resolves each binding to
  `TransportDispatchTarget`, builds `TransportDispatchEnvelope`, groups by
  adapter, and dispatches to adapters.
- `TransportDeliveryStore` is already closer to the target: its documented queue
  ownership is canonical `routeKey`; `adapterId` is metadata.
- Pre-convergence `TaskPullChannel` made worker consumption route-key based
  instead of selected-worker based.

Current result ingress also has a split source contract:

- `TaskResultIngestChannel.ingest(TaskResultReport)` is report-first.
- `TaskResultIngestChannel.ingest(TransportResultEnvelope)` defaults to dropping
  transport envelope metadata and forwarding only the report.
- `RedisTaskResultIngestChannel` overrides the envelope path and stores
  `TransportResultEnvelope` in one shared result inbox queue.
- `RuntimeTaskResultIngestChannel` is the engine-facing bridge: it validates
  envelope identity and then applies the `TaskResultReport` through
  `TaskResultIngestFacade`.

Current route-owner evidence still carries worker-specific assumptions:

- `TransportRouteOwnerStore` writes `workerId`, `adapterId`, `routeKey`, and
  `connectionId`.
- `TransportRouteOwnerRecord` requires `workerId`.
- `WorkerDispatchRouteOwnerView.currentOwner(routeKey)` is singular.
- `TransportRouteOwnerInspectionView` exposes `getLatestOwnerByWorker`,
  `isWorkerReachable`, and `findRouteOwners(workerId)`.

Those shapes reflect the recent worker-level routeKey drift. They are wrong as
transport mainline because routeKey is a delivery domain/lane in assembly and
opaque inside transport. A worker id that appears in dispatch binding remains
engine-selected execution identity and item-level delivery constraint, not the
routeKey minting input or worker lifecycle truth for transport.

## Owner Review

Dispatch assignment belongs to engine.

RouteKey minting belongs to engine/starter/SDK assembly, or a future explicit
routing policy owner. The current SDK/starter default is worker-group
consumption routes, but routeKey may also represent an adapter lane or another
delivery universe selected by assembly. Transport consumes routeKey as an
opaque string and may only normalize/encode it for protocol or physical
storage.

Worker lifecycle, resource/admission, and reachability interpretation belong to
worker-runtime or explicit worker system event projection owners. Transport may
write route heartbeat/connection evidence, but it must not decide worker
online/offline or expose worker reachability as data-plane truth.

Transport owns:

- adapter frame parsing and emission,
- route-key dispatch delivery queueing,
- route-key task pull consumption,
- transport result ingress envelope capture,
- route consumer heartbeat evidence needed to deliver work.

Engine/result runtime owns:

- task assignment,
- dispatch compensation decisions,
- result identity validation semantics,
- result convergence/finality.

## Boundary Decisions

1. `routeKey` is the dispatch delivery-domain address visible to transport.
   Current SDK/starter default assembly mints it as a worker-group consumption
   route, but transport must not decode or enforce that rule.
2. `transportNodeId` is not a routeKey semantic or scheduling target. It may
   appear only as route consumer locality, derived wakeup index, or node-local
   drain lane after assignment and route-owner resolution.
3. Route consumer evidence must not assume one routeKey has exactly one worker.
   A worker-group default routeKey or any other route-domain key may have
   multiple active workers or consumers.
4. Transport message source should use per-route dispatch envelopes/bindings,
   not a worker batch that transport must resolve again.
5. Result ingress source should preserve `TransportResultEnvelope` until the
   engine-facing result channel explicitly unwraps and applies the report.
6. `adapterId` may participate in local endpoint delivery, but it is not queue
   ownership truth.
7. `TaskDispatchBinding.workerId` is engine-selected execution identity and
   item-level delivery constraint when present. It may be used to filter the
   already selected route's concrete connections, but it must not be used by
   transport as route ownership, route eligibility, lifecycle truth, or routeKey
   minting input.

## Target Route Consumer Evidence Shape

Transport route evidence should be routeKey-first and multi-consumer:

```text
routeKey
  -> consumerId / connectionId
       adapterId
       transportNodeId
       leaseExpireAtEpochMillis
       lastHeartbeatEpochMillis
       optional worker metadata for SDK/operator projection only
```

Rules:

- `routeKey` is the canonical evidence partition.
- `consumerId` or `connectionId` distinguishes multiple active consumers for the
  same group-level route.
- `transportNodeId` is locality evidence for wakeup/drain, not queue ownership.
- Worker metadata, when present, is diagnostic/projection metadata. It is not
  dispatch routing truth.
- The dispatch hot path must read active consumers by routeKey and local
  transportNodeId; it must not reverse-map workerId to routeKey.

## Non-Goals

- Do not move worker capacity, reservation, lease, readiness, dispatch gate, or
  group slot truth into transport.
- Do not change task assignment ownership or retry/finality ownership.
- Do not implement routeKey minting rules inside transport runtime or adapters.
- Do not keep old shared, node-targeted, and route-key-targeted paths as
  permanent parallel mainlines.
- Do not add startup cleanup that deletes old Redis keys automatically.
- Do not turn result ingress into a transport-owned result lifecycle.

## Do Not Start With

Do not start by changing Redis physical queue names from
`node:<transportNodeId>:queue` to `node:<transportNodeId>:route:<routeKey>`
while keeping `NodeTargetedTaskDispatchHandoff` as the mainline interface. That
preserves the wrong owner boundary and only makes the key name more specific.

Do not start by encoding the default worker-group routeKey minting rule in
transport. The current group-based codec is assembly policy; opacity remains
the transport contract.

Do not start by renaming worker presence methods while keeping worker-id
reachability and singular route-owner APIs in the transport data-plane.

Start by correcting the message/result source contracts and route consumer
evidence model. Redis route-key queue implementation comes after those source
contracts are fixed.

## Target Physical Redis Direction

Dispatch source should converge to route-targeted queues with derived
node-local drain lanes:

```text
xa:mass:transport:dispatch-route:v1:route:<encodedRouteKey>:node:<encodedTransportNodeId>:q
xa:mass:transport:dispatch-route:v1:routes
xa:mass:transport:dispatch-route:v1:node:<transportNodeId>:ready-routes
```

Semantics:

- `route:<encodedRouteKey>:node:<encodedTransportNodeId>:q` is dispatch work for
  an already resolved route-domain plus selected route consumer locality.
- `node:<transportNodeId>:ready-routes` is a derived wakeup/index. It is safe to
  duplicate or rebuild from the node-local lane.
- The node id is not routeKey minting truth and not scheduling truth; it is the
  physical consumer locality selected after engine assignment and route-owner
  lookup.
- Consumers still validate adapter/session availability during delivery.
- If route consumers move or a transport node disappears after enqueue, recovery
  and compensation are residual reliability work; this slice prioritizes
  avoiding wrong-node dispatch and avoiding scan-heavy route queues.

Result ingress may remain a shared result inbox because result convergence is
engine-side, not route consumption. If it is later sharded, the sharding key
must be result-runtime oriented, such as task/attempt identity, not
`transportNodeId` or route owner state.

## Phase 0: Source Inventory And Cardinality Decision

Goal: freeze the real source contracts before changing implementation.

Scope:

1. Inventory all production and test call sites for:
   - `TaskDispatchHandoff`
   - `NodeTargetedTaskDispatchHandoff`
   - `TransportRoutingTaskDispatchListener`
   - `TransportDeliveryStore`
   - `TaskPullChannel`
   - `TaskResultIngestChannel`
   - `RedisTaskResultIngestChannel`
   - `TransportRouteOwnerStore`
   - `WorkerDispatchRouteOwnerView`
   - `TransportRouteOwnerInspectionView`
2. Classify each usage as source contract, transport adapter, engine bridge,
   SDK/operator read model, test fixture, or residue.
3. Decide route consumer cardinality explicitly: routeKey is a delivery-domain
   route in assembly and may have multiple active workers/consumers; transport
   must not model it as a worker identity.
4. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` with the corrected
   ownership wording without claiming implementation completion.

Acceptance:

1. Inventory separates production callers, tests, builder/server config
   surfaces, and docs.
2. The roadmap and boundary baseline state that routeKey is minted outside
   transport, currently defaults to worker-group consumption in SDK/starter, and
   remains opaque to transport.
3. Existing shared and node-targeted dispatch handoffs are classified as current
   code, not target truth.
4. Singular `currentOwner(routeKey)` and worker-id inspection APIs are recorded
   as convergence targets, not accepted data-plane shape.

Verification:

```powershell
rg -n "TaskDispatchHandoff|NodeTargetedTaskDispatchHandoff|TransportRoutingTaskDispatchListener|TransportDeliveryStore|TaskPullChannel|TaskResultIngestChannel|RedisTaskResultIngestChannel|TransportRouteOwnerStore|WorkerDispatchRouteOwnerView|TransportRouteOwnerInspectionView" xa-mass-base transport sdk xa-mass-server -g "*.java" -g "*.md" -g "*.yml"
```

## Phase 1: Correct Message And Result Source Contracts

Goal: make dispatch source and result source carry the right owner information
before Redis route-key implementation work starts.

Scope:

1. Introduce a dispatch source value, such as
   `TransportDispatchDeliveryBinding`, that includes:
   - dispatch identity and engine correlation fields derived from the assigned
     binding
   - opaque `routeKey`
   - `adapterId`
   - task dispatch context needed to build `TaskDispatchItem`
2. Make distributed dispatch handoff submit per-route delivery values instead
   of whole `TaskDispatchBatch` values that require transport to resolve worker
   targets again.
3. Keep routeKey resolution outside transport data-plane. The resolver may live
   in starter/engine assembly, but transport runtime must not decode routeKey.
4. Make `TransportResultEnvelope` the transport ingress source shape for
   adapters and distributed result inboxes.
5. Keep `TaskResultReport` as the engine result apply payload behind
   `RuntimeTaskResultIngestChannel` / `TaskResultIngestFacade`.
6. Remove or narrow production reliance on the default
   `TaskResultIngestChannel.ingest(TransportResultEnvelope)` behavior that drops
   envelope metadata.

Acceptance:

1. Dispatch handoff source values are routeKey-addressed and do not expose
   `transportNodeId` as submit target.
2. Dispatch handoff source values do not require worker-id reverse lookup inside
   transport runtime.
3. If workerId is carried from an engine binding, transport treats it as the
   selected execution identity and item-level delivery constraint inside the
   already resolved route, never as the route address or routeKey minting rule.
4. Adapter result ingress preserves adapterId, routeKey, attemptId, leaseToken,
   and traceId until engine-facing ingest validates or unwraps it.
5. Result ingest source docs distinguish transport envelope handling from engine
   result convergence.

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-base,transport/transport_api,transport/transport_runtime -am -DskipTests compile
```

## Phase 2: Route Consumer Evidence Convergence

Goal: replace worker-presence-shaped transport evidence with route-consumer
evidence.

Scope:

1. Replace route-owner write APIs with routeKey-first route consumer/heartbeat
   APIs.
2. Remove workerId as required canonical state from transport route evidence.
   Worker identity may be optional metadata only when supplied by SDK or worker
   protocol.
3. Replace singular `currentOwner(routeKey)` data-plane assumptions with active
   route consumer evidence that can represent multiple consumers.
4. Move worker-id reachability inspection out of transport data-plane. SDK or
   operator read models must not be used by dispatch hot paths.
5. Keep `transportNodeId` only as local process evidence for route consumers and
   derived wakeup indexes.

Acceptance:

1. Transport route evidence APIs are routeKey-first and do not require workerId.
2. Dispatch hot path does not call `getLatestOwnerByWorker`,
   `isWorkerReachable`, or `findRouteOwners(workerId)`.
3. A routeKey with more than one active consumer can be represented without
   overwriting another consumer's heartbeat.
4. WebSocket/socket/session connect-disconnect does not publish worker
   online/offline as an automatic transport decision.

Verification:

```powershell
rg -n "getLatestOwnerByWorker|isWorkerReachable|findRouteOwners|currentOwner\\(|publishWorkerOnline|publishWorkerOffline" transport sdk xa-mass-server -g "*.java"
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am -DskipTests compile
```

## Phase 3: Redis Route-Key Dispatch Implementation

Goal: implement route-key-owned distributed dispatch and keep node fan-out as a
derived optimization.

Scope:

1. Add a route-targeted dispatch handoff implementation under
   `xa:mass:transport:dispatch-route:v1`.
2. Encode routeKey safely for Redis physical keys without decoding its logical
   meaning.
3. Enqueue dispatch work to route-domain/node-local lanes after selected route
   consumer locality has been resolved.
4. Maintain derived ready-route indexes for transport nodes that have local
   pending route work.
5. Consumers drain only route work for their local transport node.
6. Backpressure is per route queue plus bounded ready-index growth.

Acceptance:

1. Producer submits by routeKey, not transportNodeId.
2. Queue partitioning uses route-domain plus resolved consumer locality, not
   worker-resource lookup inside transport consumers.
3. Stale node ready entries are ignored or cleaned without dispatching to the
   wrong consumer.
4. Redis tests prove physical dispatch keys are route-owned and node indexes are
   derived.
5. No transport Redis key stores worker capacity, reservation, runtime lease,
   dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.

Verification:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -Dtest=RedisRouteTargetedTaskDispatchHandoffTest test
```

## Phase 4: Move Distributed Assembly

Goal: make distributed runtime assembly use the route-key message source.

Scope:

1. Replace `NodeTargetedTaskDispatchSubmitter` in distributed producer assembly
   with a route-key submitter.
2. The producer resolves routeKey before handoff and submits route-key delivery
   values.
3. The Redis implementation resolves local route consumer evidence only
   for derived wakeup/indexing.
4. Transport consumers poll route-key handoff work and feed adapter delivery.
5. Compensation remains engine-owned when route consumer evidence is absent,
   stale, or Redis enqueue fails.

Acceptance:

1. `MassApplication` no longer extracts `transportNodeId` before handoff submit.
2. Distributed producer code does not depend on
   `NodeTargetedTaskDispatchHandoff`.
3. Transport runtime main sources do not import worker-runtime resource query
   APIs for dispatch handoff.
4. Existing result inbox and dispatch-failure inbox semantics do not change.
5. Split transport tests cover at least two routeKeys and at least one routeKey
   with multiple active consumers where selected worker constraints partition
   delivery to the correct consumer node.

Verification:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk,transport/transport_runtime -am -Dtest=MassApplicationDistributedTransportTest,RedisRouteTargetedTaskDispatchHandoffTest test
```

## Phase 5: Result Ingress Cleanup

Goal: remove result-ingress ambiguity introduced by report-first and
envelope-first paths.

Scope:

1. Make adapter and distributed transport result paths call envelope-preserving
   ingress APIs.
2. Keep direct report ingest only for engine-local/simple embedded cases where
   there is no transport envelope.
3. Ensure Redis result inbox stores `TransportResultEnvelope`, not naked
   `TaskResultReport`.
4. Keep result identity validation and final apply in
   `RuntimeTaskResultIngestChannel` / engine result facade.
5. Add tests proving routeKey/adapter/attempt/trace metadata survives transport
   inbox enqueue, dequeue, and engine-facing validation.

Acceptance:

1. Production adapter result paths preserve envelope metadata until
   `RuntimeTaskResultIngestChannel`.
2. No production distributed result inbox path materializes adapter/route as
   `"unknown"` when the transport envelope was available.
3. Result convergence remains engine-owned; transport tests assert forwarding
   and metadata preservation, not task finality semantics.

Verification:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -Dtest=RedisTaskResultIngestChannelTest,TaskResultIngestInboxPumpTest,WebSocketInputProcessorTest,SocketTransportServerTest,PullWorkerSessionTest test
```

## Phase 6: Retire Shared/Node-Targeted Residue

Goal: remove old dispatch handoff shapes from distributed mainline.

Scope:

1. Remove or demote `NodeTargetedTaskDispatchHandoff` to an internal test
   fixture if no production caller remains.
2. Remove `redisNodeTargetedDispatchHandoff(...)` from default distributed
   helper wiring.
3. Keep `RedisTaskDispatchHandoff` only for simple/local fixture use if it still
   has a real caller; otherwise remove it.
4. Update SDK/server docs and transport baseline.
5. Add source guards against reintroducing node-targeted or shared Redis
   dispatch as distributed truth.

Acceptance:

1. `redisDistributedChannels(redisUri)` wires route-key dispatch handoff.
2. Non-archive production code has no distributed mainline caller of
   `NodeTargetedTaskDispatchHandoff`.
3. Active docs do not describe `transportNodeId` as the dispatch handoff target.
4. Route consumer evidence key manifest and dispatch-route Redis key manifest
   are documented in transport baseline.

Verification:

```powershell
rg -n "NodeTargetedTaskDispatchHandoff|redisNodeTargetedDispatchHandoff|dispatch-node" xa-mass-base transport sdk xa-mass-server -g "*.java" -g "*.md" -g "*.yml"
.\mvnw.cmd -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
```

## Phase 7: Proof And Residue Scan

Goal: make the convergence hard to regress.

Scope:

1. Update `doc/PROOF_REGISTRY.md` so route-key dispatch handoff proof is tied
   to route consumer evidence, not worker presence.
2. Update `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` to include
   dispatch-route key families as route-owned, not node-owned.
3. Add or update source guards:
   - distributed default must not use `dispatch-node` as mainline truth,
   - transport Redis must not introduce worker runtime/admission key families,
   - routeKey must stay opaque and encoded before becoming a Redis key token,
   - transport data-plane must not depend on worker-id reachability inspection,
   - adapter result ingress must preserve `TransportResultEnvelope` metadata.
4. Run residue scan for old node-targeted, shared-handoff, presence, and
   worker-online/offline vocabulary.

Acceptance:

1. Proof registry names representative route-key dispatch, route consumer
   evidence, and result envelope preservation tests.
2. Redis proof roadmap forbids node-owned dispatch queue truth for mainline
   distributed transport.
3. Active docs use route-key consumption and route consumer evidence vocabulary
   consistently.
4. Completed facts move to owning baseline docs before this roadmap is
   archived.

Verification:

```powershell
rg -n "presence|WorkerPresence|NodeTargetedTaskDispatchHandoff|dispatch-node|transportNodeId.*handoff|worker online|worker offline|getLatestOwnerByWorker|isWorkerReachable|TransportResultEnvelope.*unknown" transport sdk xa-mass-server doc roadmap -g "*.java" -g "*.md" -g "*.yml"
.\mvnw.cmd -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
```

## Remaining Decisions And Residue

Resolved in the current slice:

- dispatch source value names are `RouteTargetedTaskDispatchBinding` and
  `RouteTargetedTaskDispatchBatch`
- Redis dispatch physical lanes are
  `route:<encodedRouteKey>:node:<encodedTransportNodeId>:q` plus per-node
  ready-route indexes
- dispatch batches are small route-domain/node-local batches after selected
  route consumer locality is resolved

Still open:

1. Whether the engine-owned `TaskDispatchBinding.workerId` field remains as
   worker-facing/correlation metadata in transport payloads, or is split/renamed
   in a later engine binding cleanup so route-domain semantics are not obscured.
2. Whether direct report ingest remains on `TaskResultIngestChannel` or moves
   behind an engine-local result channel name after transport callers converge
   on envelope ingest.
3. Whether the public SDK keeps worker online/offline protocol names while
   internal transport event names converge to route consumer claim/release and
   explicit worker system event ingress.
4. Whether node-local dispatch lanes need a recovery/rebalance owner for route
   consumer movement or transport-node disappearance after enqueue. Current
   slice intentionally favors throughput and no scan-heavy route queues; this
   reliability policy remains separate from routeKey ownership.

## Completion Criteria

This roadmap is complete only when:

1. Distributed dispatch handoff submits by opaque routeKey delivery domain in
   production assembly; current SDK/starter default remains worker-group route
   minting but transport does not know the rule.
2. Redis dispatch handoff truth is route-targeted with node-local drain lanes
   derived from selected route consumer locality.
3. Node wakeup/index data is derived and safe to rebuild/drop.
4. Route consumer evidence no longer requires worker identity or singular owner
   semantics in transport data-plane.
5. Transport dispatch source contracts do not use `TaskDispatchBinding.workerId`
   as routeKey minting input, route ownership, or lifecycle truth; they only use
   it as the already selected execution constraint for adapter connection
   filtering.
6. Production result ingress preserves transport envelope metadata until
   engine-facing result validation/apply.
7. `NodeTargetedTaskDispatchHandoff` is removed from mainline distributed
   wiring or explicitly archived as legacy/simple support.
8. Transport docs, SDK/server docs, proof registry, and Redis proof roadmap all
   agree on route-key consumption, derived node locality, and result-ingress
   ownership.
9. Focused transport, SDK distributed, and server startup/context verification
   pass after residue cleanup.
