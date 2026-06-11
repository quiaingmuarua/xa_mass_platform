# Transport Polling Selected-Worker Delivery Convergence Roadmap

Status: implemented and verified in current worktree.

Parent:
`roadmap/TRANSPORT_ROUTE_DOMAIN_SELECTED_WORKER_DELIVERY_CONVERGENCE_ROADMAP.md`.

Last reviewed against current worktree: 2026-06-11.

## Summary

Polling worker delivery is not a small adapter cleanup. It is the full
acceptance surface for proving that transport preserves the worker selected by
Scheduling Plane when there is no long-lived push session.

Current polling delivery is assembled from four facts:

```text
registered worker identity
  + route-owner lease evidence
  + routeKey-owned delivery queue
  + worker-initiated poll(routeKey)
```

The missing fact is the final-hop selected-worker constraint. By the time a task
item reaches transport, engine has already selected:

```text
itemId -> selectedWorkerId
```

If polling drains only by `routeKey`, then a shared or group-level routeKey means
the worker that polls first can consume another worker's assigned item. That
moves concrete worker selection out of Scheduling Plane and into queue timing.

Target:

```text
TaskDispatchBinding.workerId
  -> selectedWorkerId on transport delivery binding/envelope
  -> enqueue(deliveryQueueKey, selectedWorkerId, envelope)
  -> PullWorkerSession polls with its own registered workerId
  -> runtime resolves deliveryQueueKey(s)
  -> store polls by deliveryQueueKey + selectedWorkerId
  -> worker receives only items assigned to itself
```

RouteKey may remain as opaque metadata, coarse partition, diagnostics, or
protocol correlation. It must not be the only queue isolation key for assigned
task delivery.

The target acceptance is stronger than "one worker, one physical queue key":
two polling workers may share the same `routeKey` and the same
`deliveryQueueKey`, and still must not consume each other's selected items.
Correctness comes from a selected-worker selector or sub-lane inside the shared
queue owner, not from high-cardinality routeKey or worker-specific top-level
queue keys.

`deliveryQueueKey` is a batching, shard, or storage partition primitive. It may
be derived from adapter id, a fixed shard, a hash shard, or transport-node-local
queue ownership, but it must never be interpreted as worker routing identity.

Presence / route-owner evidence may prove that a selected polling worker has a
consumer lease. It must not become a post-assignment routing engine that
reselects, infers, or replaces the selected worker.

## Current Code Observations

- `TaskPullChannel.pollTaskMessagesResult(...)` accepts the polling worker's
  registered `selectedWorkerId`, `maxMessages`, and `timeoutMillis`.
- `PullWorkerSession.pollResult(...)` passes its own registered `workerId` to
  `TaskPullChannel`; it does not pass or compute a deliveryQueueKey.
- `PollingWorkerAdapter.pollTaskMessagesResult(...)` validates
  `selectedWorkerId` and calls
  `TransportDeliveryService.pollEnvelopeResult(PROTOCOL, selectedWorkerId, ...)`.
- `TransportDeliveryService.pollEnvelopeResult(...)` resolves the internal
  deliveryQueueKey from adapter id and delegates to
  `TransportDeliveryStore.poll(deliveryQueueKey, selectedWorkerId, ...)`.
- `TransportDeliveryStore` exposes selected-worker store semantics through
  `drain(deliveryQueueKey, selectedWorkerId, ...)` and
  `poll(deliveryQueueKey, selectedWorkerId, ...)`.
- `QueueBackedTransportDeliveryStore` builds
  `DeliveryQueueKey(deliveryQueueKey, selectedWorkerId)`, and key equality /
  ordering include both fields. The first slice uses adapter id, for example
  `polling`, as the shared deliveryQueueKey.
- `RedisTransportDeliveryStore` delegates to the same queue-backed semantics.
  Redis queue key parts are encoded as
  `<encodedDeliveryQueueKey>:worker-index:<encodedSelectedWorkerId>`.
- `TransportDispatchEnvelope` carries `deliveryId`, `deliveryQueueKey`,
  `selectedWorkerId`, `packet`, and `createdAtEpochMillis`.
- `TransportDispatchEnvelopeFactory` creates envelopes from `adapterId`,
  `routeKey`, `traceId`, and a worker-facing `TaskDispatchItem`, setting
  `deliveryQueueKey` from adapter id and `selectedWorkerId` from
  `TaskDispatchItem.workerId`.
- WebSocket/socket direct dispatch channels use `TransportDispatchEnvelope`
  `selectedWorkerId` as the final-hop worker filter while preserving the
  worker-facing packet payload.
- `PollingWorkerAdapter.announceWorkerOnline/offline/heartbeat` updates
  route-owner lease evidence only. It does not publish worker lifecycle events.
- External worker polling enters through
  `/worker-api/v1/workers/{workerId}:poll`; the server binds the authenticated
  worker id and calls SDK worker polling with that concrete worker id.
- `ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems`
  proves that two external polling workers sharing one worker-group routeKey,
  one adapter node, and one internal deliveryQueueKey cannot cross-consume a
  task item selected for only one worker.

## Owner Review

Scheduling Plane owns concrete worker selection. `selectedWorkerId` is the
engine-selected execution target and must be preserved through transport.

Transport owns polling delivery mechanics:

- delivery queue partitioning,
- poll request validation,
- selected-worker selector / sub-lane drain,
- mapping delivered envelopes into worker-facing `TaskDispatchItem` views,
- delivery outcome/status mapping.

Transport does not own:

- worker matching,
- worker online/offline lifecycle truth,
- worker capacity/admission/reservation,
- fallback worker selection when selected-worker delivery is infeasible,
- task retry or compensation policy.

Polling worker identity is allowed in transport only as a delivery constraint:
the worker that polls may receive work only when its registered worker identity
matches the delivery's `selectedWorkerId`.

## Boundary Decisions

1. Polling task delivery must be selected-worker keyed. RouteKey-only task poll
   is not acceptable for assigned task items.
2. `routeKey` remains opaque and may be shared. Transport must not decode it and
   must not mint worker-specific routeKeys to recover correctness.
3. `deliveryQueueKey` is a transport runtime partition primitive. It may be
   adapter-level, fixed-shard, hash-shard, or transport-node-local, but it must
   never be interpreted as worker-routing identity.
4. Mainline polling APIs expose `selectedWorkerId`, not `deliveryQueueKey`.
   Runtime may resolve one or more deliveryQueueKeys internally before calling
   the store.
5. `deliveryQueueKey` must not include `selectedWorkerId`. Selected worker
   isolation is a sub-lane, direct index, or equivalent selector under the
   shared queue owner.
6. A queue keyed only by routeKey or deliveryQueueKey is invalid for assigned
   polling task delivery.
7. Polling must not use poll-and-discard from a shared queue. That steals queue
   order, wastes throughput, and can starve selected workers.
8. Missing selected-worker sub-lane/index, stale endpoint evidence, or empty
   selected lane is delivery state. It is not permission for transport to
   select a different worker.
9. Route-owner lease evidence is connection/consumer evidence. Updating it must
   not publish worker online/offline lifecycle from adapter lease state. The
   explicit public worker event path may still publish worker system events.
10. Route-owner / presence lookup may only answer delivery feasibility for an
   already selected worker. It must not become a post-assignment routing engine.

## Target Shape

The exact class names can change, but the polling boundary must expose these
facts:

```text
AssignedPollingDelivery
  adapterId
  deliveryQueueKey
  selectedWorkerId = TaskDispatchBinding.workerId
  optional routeKey
  TaskDispatchBinding / dispatch identity

StoreDeliveryAddress
  deliveryQueueKey
  selectedWorkerId
  optional routeKey

TransportDispatchEnvelope
  adapterId
  deliveryQueueKey
  selectedWorkerId
  optional routeKey
  packet
  delivery identity / attempt identity

TaskPullChannel
  pollTaskMessagesResult(selectedWorkerId, maxMessages, timeout)
```

Valid polling flow:

```text
PullWorkerSession
  -> knows registered workerId
  -> pollTaskMessagesResult(workerId, ...)
  -> runtime resolves deliveryQueueKey(s)
  -> TransportDeliveryStore.poll(deliveryQueueKey, selectedWorkerId, ...)
  -> returns only envelopes assigned to that selectedWorkerId
```

Route-only polling remains valid only for explicit diagnostics or raw/manual
side channels. It must not be the mainline task-dispatch pull path.

## Target Runtime Keys

Shared polling queue owner:

```text
delivery:q:<encodedDeliveryQueueKey>
```

Selected-worker selector under the shared owner:

```text
delivery:q:<encodedDeliveryQueueKey>:worker-index:<selectedWorkerId>
```

Allowed metadata:

```text
routeKey
adapterId
adapterNodeId
targetTransportNodeId
deliveryQueueKey
deliveryId
attemptId
traceId
```

Optional secondary index:

```text
route:<encodedRouteKey>:delivery-lanes
```

Rules:

- selected-worker sub-lanes or indexes are delivery-feasibility state, not
  worker lifecycle truth;
- `selectedWorkerId` must not be part of the top-level `deliveryQueueKey` in
  the first slice;
- `deliveryQueueKey` may partition or batch delivery queues, but it must never
  be interpreted as a worker-routing identity;
- a queue keyed only by routeKey or deliveryQueueKey is invalid for assigned
  polling task delivery;
- per-worker queue stats are transport queue stats, not capacity or admission;
- no polling Redis key may store worker runtime lease, reservation, dispatch
  gate, event-binding ceiling, or `group:{groupId}:slots`.

## Non-Goals

- Do not change worker scheduling or claim/lease semantics in this roadmap.
- Do not rename `TaskDispatchBinding.workerId()` as part of this roadmap.
- Do not remove worker-facing `TaskDispatchItem.workerId` payload in the first
  implementation slice.
- Do not make routeKey worker-specific.
- Do not implement polling correctness by draining a route queue and filtering
  unmatched envelopes in memory.
- Do not make polling worker heartbeat a worker online/offline lifecycle owner.
- Do not leak polling adapter session/runtime types into engine APIs.

## Do Not Start With

Do not start by optimizing WebSocket/socket endpoint indexes. Polling has a
different correctness failure because the worker actively drains a queue.

Do not start by adding a workerId filter after routeKey poll. Once an item is
removed from a shared route queue, the queue order and ownership have already
been violated.

Do not start by encoding workerId into routeKey. That hides the selected-worker
constraint inside the route value and reverses routeKey opacity.

## Phase 0: Inventory And Contract Freeze

Goal: identify every polling task delivery caller and freeze which paths are
mainline task dispatch versus raw/manual diagnostics.

Scope:

1. Inventory production and test call sites for:
   - `TaskPullChannel.pollTaskMessages(...)`
   - `TaskPullChannel.pollTaskMessagesResult(...)`
   - `PollingWorkerAdapter.pollTaskMessagesResult(...)`
   - `PullWorkerSession`
   - `TransportDeliveryService.pollEnvelopeResult(...)`
   - `TransportDeliveryStore.poll(...)`
   - `TransportDeliveryStore.drain(...)`
   - `TransportDeliveryStoreStats`
2. Classify each routeKey-only poll as:
   - mainline assigned task dispatch,
   - raw/manual diagnostic channel,
   - test fixture,
   - stale compatibility residue.
3. Inventory where polling worker identity is currently known before poll.
4. Record the first-slice internal queue-owner strategy. It may start as a
   single adapter-level or fixed-shard `deliveryQueueKey`, but the value must
   be resolved inside transport runtime, not by worker-facing callers.
5. Inventory any caller or test that currently treats routeKey or
   deliveryQueueKey as worker-routing identity.

Acceptance:

1. Mainline task pull callers are identified and cannot remain routeKey-only in
   later phases.
2. Raw/manual route-only polling, if kept, has a separate name and does not sit
   on the assigned task-dispatch path.
3. The first implementation slice has a concrete selected-worker poll method
   signature.
4. The first implementation slice has one explicit internal deliveryQueueKey
   resolution strategy and does not put `selectedWorkerId` into the top-level
   queue key.
5. Mainline poll callers do not receive or compute deliveryQueueKey.

Verification:

```powershell
rg -n "pollTaskMessages|pollTaskMessagesResult|pollEnvelopeResult|TransportDeliveryStore|PullWorkerSession|PollingWorkerAdapter" transport sdk xa-mass-server -g "*.java" -g "*.md"
```

## Phase 1: Delivery Contract And Poll API

Goal: stop losing the engine-selected worker before either enqueue or poll
touches the delivery store.

Scope:

1. Introduce or evolve a transport delivery value for polling-targeted task
   dispatch, for example:

   ```text
   AssignedPollingDelivery
     adapterId
     deliveryQueueKey
     selectedWorkerId = TaskDispatchBinding.workerId
     optional routeKey
     dispatch identity / attempt identity
   ```

2. Add explicit `selectedWorkerId` and internal `deliveryQueueKey` fields to
   `TransportDispatchEnvelope` or an equivalent dispatch-only envelope used by
   delivery store enqueue and polling.
3. Update `TransportDispatchEnvelopeFactory` and distributed delivery codecs so
   the new fields are preserved before any selected-worker selector store is
   introduced.
4. Add a selected-worker polling method to `TaskPullChannel`, for example:

   ```java
   TaskPullResult pollTaskMessagesResult(
       String selectedWorkerId,
       int maxMessages,
       long timeoutMillis
   );
   ```

5. Update `PullWorkerSession` so it passes its registered worker id as
   `selectedWorkerId` and does not compute deliveryQueueKey.
6. Update `PollingWorkerAdapter` to validate selected-worker poll inputs before
   calling delivery service.
7. Update transport runtime polling service so it resolves deliveryQueueKey(s)
   internally before calling the delivery store.
8. Keep routeKey as optional metadata or explicit raw polling input where still
   needed.
9. Keep worker-facing `TaskDispatchItem` JSON stable.

Acceptance:

1. Enqueue-side delivery objects and poll-side APIs both carry explicit
   `selectedWorkerId`.
2. Mainline poll API does not expose deliveryQueueKey.
3. Runtime resolves deliveryQueueKey internally and does not derive it from
   routeKey worker/group semantics.
4. `deliveryQueueKey` is shared by multiple selected workers and does not
   include selectedWorkerId.
5. Mainline polling task dispatch cannot call a routeKey-only poll method.
6. Polling worker identity is carried as `selectedWorkerId`, not inferred from
   routeKey.
7. Route-only polling remains separate from assigned task pull.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
.\mvnw.cmd -q -pl transport/transport_runtime,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -Dtest=RedisTransportDispatchEnvelopeCodecTest,TransportDeliveryServiceTest,PollingWorkerAdapterTest,PullWorkerSessionTest test
```

## Phase 2: Shared Queue Owner With Selected-Worker Selector

Goal: make a shared deliveryQueueKey preserve selected-worker polling
semantics without routeQueue poll-and-discard.

Scope:

1. Add internal delivery store enqueue/poll support for a shared queue owner plus
   selected-worker selector:

   ```text
   enqueue(envelope with deliveryQueueKey + selectedWorkerId)
   poll(deliveryQueueKey, selectedWorkerId, maxItems, timeout)
   drain(deliveryQueueKey, selectedWorkerId, maxItems)
   ```

2. Update in-memory and Redis-backed delivery stores so selected-worker items
   under one `deliveryQueueKey` are addressable by direct selected-worker
   sub-lane, index, bucket, or equivalent keyed structure.
3. Keep `selectedWorkerId` out of the top-level `deliveryQueueKey`.
4. Remove shared queue poll-and-discard from mainline task delivery.
5. Update queue stats so selected-worker sub-lanes/indexes aggregate under the
   shared `deliveryQueueKey` without presenting worker load/admission facts.
6. Preserve routeKey as envelope metadata.
7. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` and the Redis key manifest
   in the same slice as the key-shape change. The active baseline must not keep
   describing assigned polling delivery as `q:<routeKey>` after this slice.

Acceptance:

1. Two workers sharing one routeKey and one deliveryQueueKey cannot drain each
   other's assigned items.
2. Polling selected-worker delivery does not scan, drain, or discard a whole
   shared queue to find matching workerId.
3. Redis and memory delivery stores expose equivalent selected-worker selector
   behavior under the same shared deliveryQueueKey.
4. Queue stats remain transport delivery stats and do not become worker runtime
   truth.
5. Active transport baseline and Redis key manifest describe assigned polling
   delivery as shared queue owner plus selected-worker selector, with routeKey
   only metadata/secondary partition.

Verification:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/polling-adapter -am -Dtest=InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,TransportDeliveryServiceTest,PollingWorkerAdapterTest test
rg -n "poll\\([^,]+,[^,]+,[^,]+,[^,]+\\)|drain\\([^,]+,[^,]+,[^,]+\\)" transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java -g "*.java"
rg -n "q:<routeKey>|delivery:q:|selected-worker|deliveryQueueKey" transport/TRANSPORT_BOUNDARY_BASELINE.md
```

## Phase 3: Server And Public SDK Polling Proof

Goal: prove selected-worker polling through the real worker-facing entry
points, not only transport unit tests.

Scope:

1. Update server worker API tests for
   `/worker-api/v1/workers/{workerId}:poll` so the authenticated/bound
   `workerId` is the selected-worker poll constraint.
2. Update external worker polling integration tests so a polling worker cannot
   receive an item assigned to another worker sharing the same routeKey and
   internal deliveryQueueKey.
3. Update public Java SDK worker client/session tests so polling uses worker
   identity as selected-worker delivery constraint while preserving public
   response payloads.
4. Keep explicit worker online/heartbeat/offline APIs as worker event ingress;
   this phase must not remove public worker lifecycle reporting.

Acceptance:

1. `ExternalWorkerApiController` still binds credentials to one concrete
   worker id before polling, and that id reaches selected-worker pull.
2. Public Java SDK polling session tests cover selected-worker delivery through
   the public API shape.
3. Shared routeKey + shared deliveryQueueKey / two-worker integration proof
   exists outside pure transport unit tests.
4. Public server and Java SDK worker polling APIs do not expose deliveryQueueKey.
5. Public worker API response shape remains stable unless a separate public
   contract change is approved.

Verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server,sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk -am "-Dtest=ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest,JavaExternalSdkPollingSessionIntegrationTest,WorkerClientTest,PollingWorkerSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "workers/\\{workerId\\}:poll|pollTasks\\(|pollTasksResult\\(|selectedWorkerId" xa-mass-server sdk -g "*.java" -g "*.md"
rg -n "deliveryQueueKey" xa-mass-server/src/main/java sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker -g "*.java"
```

## Phase 4: Guards And Shared RouteKey Proof

Goal: make polling selected-worker delivery a hard regression boundary.

Scope:

1. Add focused tests for:
   - one routeKey, one deliveryQueueKey, two polling workers, two
     selected-worker sub-lanes/index entries,
   - empty selected-worker lane while another worker has backlog,
   - selected-worker lane timeout without draining another worker's lane,
   - Redis-backed selected-worker polling behavior.
2. Add source/architecture guards:
   - mainline task pull must not call routeKey-only polling,
   - worker-facing polling APIs must not expose deliveryQueueKey,
   - delivery store must not implement selected-worker pull by route queue
     poll-and-discard,
   - routeKey codec must not be used by polling delivery to derive worker id,
   - transport adapters and `sdk/.../worker/PullWorkerSession` must not publish
     worker lifecycle events from route-owner lease updates.
3. Preserve explicit public worker event ingress. Guards must not forbid
   `WorkerClientOperations.workerOnline/heartbeat/offline` or
   `MassSdkApplication.workerOnline/heartbeat/offline`.
4. Update `transport/AGENTS.md` and `doc/PROOF_REGISTRY.md` with
   polling-specific selected-worker proof.
5. Run residue scans for routeKey-only polling, worker-specific routeKey
   minting, and route-owner-as-routing-engine wording.

Acceptance:

1. The shared-routeKey + shared-deliveryQueueKey two-worker test fails on old
   routeKey-only polling and passes on selected-worker selector polling.
2. Guards fail if mainline task pull becomes routeKey-only again.
3. Guards fail if deliveryQueueKey leaks into server or public SDK worker
   polling APIs.
4. Guards do not fail on explicit public worker system-event ingress.
5. Proof registry names the selected-worker polling proof as a transport
   acceptance surface.

Verification:

```powershell
rg -n "pollTaskMessagesResult\\([^\\n]*routeKey|pollEnvelopeResult\\([^\\n]*routeKey|poll-and-discard|route-owner.*routing|presence.*routing" transport sdk doc roadmap -g "*.java" -g "*.md"
rg -n "payloadString\\(TransportPacket.PAYLOAD_WORKER_ID\\)" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher -g "*.java"
rg -n "deliveryQueueKey" xa-mass-server/src/main/java sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker -g "*.java"
rg -n "publishWorkerOnline|publishWorkerOffline|publishWorkerHeartbeat" transport/polling-adapter/src/main/java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker -g "*.java"
.\mvnw.cmd -q -pl transport/transport_runtime -am "-Dtest=TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Completion Criteria

This roadmap is complete only when:

1. Mainline polling task pull carries explicit `selectedWorkerId` from the
   polling worker's registered identity.
2. Assigned polling delivery has a shared `deliveryQueueKey` that does not
   include selectedWorkerId, plus a direct selected-worker sub-lane/index or
   equivalent selector under that shared queue owner.
3. RouteKey-only task polling is removed from the mainline assigned task path.
4. Polling delivery never drains a shared route queue and filters/discards
   unmatched worker items.
5. Two polling workers sharing one routeKey and one deliveryQueueKey cannot
   consume each other's selected task items in memory and Redis-backed stores.
6. Empty or unavailable selected-worker selector/sub-lane entries do not trigger
   transport-side fallback to another worker.
7. Polling route-owner heartbeat/lease evidence remains delivery evidence and
   does not become worker online/offline lifecycle truth or a post-assignment
   routing engine.
8. Worker-facing polling APIs do not expose deliveryQueueKey, and worker-facing
   dispatch item JSON remains stable unless a separate public protocol change is
   approved.
9. Active transport baseline, Redis key manifest, transport docs, and proof
   registry list polling selected-worker delivery as a required acceptance
   proof, not an adapter detail.
