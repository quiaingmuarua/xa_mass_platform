# Transport Dispatch Envelope / Endpoint / Outcome Convergence Roadmap

Status: implemented mainline; keep active until archive/residue handoff
Owner: transport runtime + transport adapters + SDK/starter assembly
Created: 2026-06-12

## Summary

`TaskDispatchItem` has been removed from the transport core and polling public
pull contract. This roadmap executed the next transport-thinning slice: task
delivery no longer pivots around:

- `TransportPacket` as an internal queue/envelope payload,
- `TransportDispatchEnvelope` as the adapter SPI and polling queue carrier,
- `EndpointLease` as cross-process handoff payload, and
- `DispatchOutcome + DeliveryObservation* + TransportDeliveryFailureEvent` as
  overlapping delivery/failure fact containers.

Implemented direction: transport delivery handoff carries a minimal
assigned-item intent until the final-hop adapter boundary. Endpoint evidence is
resolved on the consumer transport node immediately before send/enqueue.
`TransportPacket` is assembled only by adapter-owned wire codecs when a
protocol frame requires it. Delivery outcome/failure has one owning fact shape,
not three parallel copies.

## Original Code Shape Before Implementation

Before this roadmap was implemented, code said:

- `TransportDeliveryCommandBatch` carries `ResolvedDeliveryItem` values.
  `ResolvedDeliveryItem` is `DeliveryCommand + EndpointLease`.
- `EndpointLease` contains `selectedWorkerId`, `routeKey`, `transportNodeId`,
  `connectionId`, and `leaseExpireAtEpochMillis`.
- `TransportDeliveryCommandBatchCodec` serializes `EndpointLease` across the
  handoff boundary.
- `TransportDeliveryCommandListener` converts each resolved item into a
  `TransportPacket`, wraps it in `TransportDispatchEnvelope`, and calls
  `WorkerAdapter.dispatchEnvelopes(...)`.
- `TaskDispatchChannel` / `WorkerAdapter` still expose
  `dispatchEnvelopes(List<TransportDispatchEnvelope>)`.
- socket and websocket adapters encode worker frames from
  `TransportDispatchEnvelope#getPacket()`.
- polling adapter enqueues `TransportDispatchEnvelope`; the Redis polling queue
  serializes a full `TransportPacket`, and the polling boundary projects it
  back into `PulledTaskDispatch`.
- `DispatchOutcome` contains adapter, selected worker, queue, route, attempt,
  target node, connection, status, retryability, and reason.
- `TransportDeliveryFailureEvent` separately contains
  `DeliveryObservationGroupContext`, `DeliveryObservationItemSnapshot`, and
  `DispatchOutcome`.

Current target docs say:

- `selectedWorkerId` is the delivery correctness target.
- `routeKey` is opaque endpoint metadata, not worker routing truth.
- `deliveryQueueKey` is storage/batching/lane metadata.
- transport must not decide worker lifecycle or reselect workers.

The gap was that endpoint and packet facts were resolved too early and then
copied through multiple internal carriers.

## Implemented Current Shape

The mainline implementation now uses:

- `DeliveryCommandBatch` with `adapterId`, `deliveryQueueKey`,
  `targetTransportNodeId`, and minimal `DeliveryCommand` items only.
- producer-side `TransportAssignedDeliverySubmitter` owner lookup only for
  node-hint grouping and node availability, not endpoint serialization.
- consumer-side `TransportDeliveryCommandListener` endpoint re-resolution by
  `adapterId + selectedWorkerId` before adapter dispatch.
- `AdapterDispatchRequest` as the final-hop adapter SPI payload.
- `QueuedPulledDispatch` and `RedisQueuedPulledDispatchCodec` as the polling
  queue value/codec, without task-dispatch packets or endpoint facts.
- `DispatchOutcome` as the single delivery failure fact owner, with
  `TransportDeliveryFailureEvent` wrapping only outcome plus detail.

Removed mainline artifacts:

- `TaskDispatchChannel`
- `TransportDispatchEnvelope`
- `EndpointLease`
- `ResolvedDeliveryItem`
- `DeliveryObservationGroupContext`
- `DeliveryObservationItemSnapshot`
- `DeliveryObservationSupport`
- `RedisTransportDispatchEnvelopeCodec`
- `RedisTransportDispatchEnvelopeRecord`
- `WebSocketRealtimeWorkerAdapter`
- `SocketRealtimeWorkerAdapter`
- `TransportPacketFactory#fromDispatchContent`

Execution strategy for the first slice:

- Use the small node-hint slice. Producer-side submitter may continue reading
  selected-worker owner evidence only to choose `targetTransportNodeId` for the
  existing node-lane handoff contract.
- Producer-side submitter must not serialize `routeKey`, `connectionId`, or
  lease expiry facts.
- Consumer-side listener must re-resolve the current endpoint for the selected
  worker and verify it still belongs to the target/local node before final-hop
  dispatch.
- Do not switch to unresolved/broadcast/partition lanes in this roadmap. That
  would be a different handoff design.

## Target Boundary

### Assigned Delivery Handoff

The process-boundary delivery handoff should carry:

- `adapterId`
- `deliveryQueueKey`
- `targetTransportNodeId`
- items, each containing only:
  - `deliveryId`
  - `selectedWorkerId`
  - `TaskDispatchContent`
  - `TaskDispatchExecutionContext`
  - deadline/created timestamps if still required

It must not carry:

- `EndpointLease`
- `routeKey`
- `connectionId`
- `leaseExpireAtEpochMillis`
- `TransportPacket`
- `TransportDispatchEnvelope`

For the current Redis node-lane handoff, `targetTransportNodeId` is still a
required routing contract. It is a node hint selected from current owner
evidence, not an endpoint lease and not worker lifecycle truth.

### Consumer-Side Endpoint Resolution

The consumer transport node owns final endpoint resolution:

1. Receive or drain a `DeliveryCommandBatch`.
2. Verify the batch is for the local `targetTransportNodeId` when the runtime
   has local-node identity available.
3. For each item, resolve current endpoint evidence by
   `adapterId + selectedWorkerId`.
4. Verify the resolved endpoint owner still points at the batch
   `targetTransportNodeId` and, when known, the local transport node id.
5. If no current endpoint exists, emit one retryable delivery failure.
6. If the endpoint points at another node or stale owner evidence, emit one
   retryable delivery failure.
7. Build a final-hop adapter request using the current endpoint evidence.

Endpoint evidence may exist as a local `ResolvedEndpoint` or equivalent
runtime-only value immediately before send/enqueue. It must not be serialized
inside distributed delivery batches.

### Adapter Final-Hop Boundary

Adapter dispatch input should be a typed final-hop request, not a generic packet
envelope. A target shape is:

```text
AdapterDispatchRequest
  deliveryId
  adapterId
  selectedWorkerId
  content
  executionContext
  endpoint
  createdAtEpochMillis
```

`endpoint` is runtime-local evidence needed for adapter send or diagnostic
failure. It is not command truth and must not be stored in producer-side
handoff.

`TransportPacket` remains allowed only inside adapter wire assembly and
protocol codecs. Preferred final shape is that socket/websocket codecs encode
directly from `AdapterDispatchRequest` or a typed worker-frame view. If a
`TransportPacket` is retained as an adapter-local implementation detail, the
task-dispatch builder must live in the adapter boundary and
`TransportPacketFactory#fromDispatchContent` must not remain a transport-runtime
delivery entry point. Polling should not serialize or deserialize a
`TransportPacket` for its worker queue.

### Polling Queue Payload

Polling delivery queue values should store a typed dispatch payload:

```text
QueuedPulledDispatch
  deliveryId
  selectedWorkerId
  content
  pullExecutionContext
  createdAtEpochMillis
```

`PollingWorkerAdapter` should project this directly to `PulledTaskDispatch`.
It should not rehydrate a packet and then reverse-map packet payload fields.
`pullExecutionContext` is limited to `attemptId`, `attemptNo`, `retryCount`,
and `batchId`; worker-frame compatibility fields such as `taskName`, `project`,
and `userId` belong to push adapter wire assembly, not polling queue storage.

### Outcome And Failure

Delivery outcome/failure should have one fact owner.

Selected implementation:

- slim `DispatchOutcome` is the single delivery/failure fact owner.
- `TransportDeliveryFailureEvent` wraps only that outcome plus optional detail.
- `DeliveryObservationGroupContext` and `DeliveryObservationItemSnapshot` are
  removed instead of preserved as sibling fact records.
- introducing a separate `DeliveryFailure` / `DeliveryAttemptOutcome` record is
  a non-goal for this roadmap.

The selected option must satisfy:

- caller-visible dispatch results still expose status, retryability, delivery
  id, selected worker, and reason;
- failure handler has task/message/attempt context needed for engine retry or
  compensation;
- endpoint diagnostic facts are optional and copied once;
- no event contains the same adapter/worker/route/connection/attempt facts in
  multiple sibling records.

## Do Not Start With

Do not start by deleting `TransportDispatchEnvelope`, `EndpointLease`, or
`DispatchOutcome` in place. First move the adapter dispatch SPI and
consumer-side endpoint resolution to a typed final-hop request. Otherwise the
repo will either fail to compile or preserve fake/null endpoint fields as a
temporary second truth.

Do not start by moving all `activeOwnerForSelectedWorker(...)` reads to the
consumer while the handoff still requires `targetTransportNodeId`. The first
slice must either keep producer-side node-hint resolution or redesign the
handoff to support unresolved lanes. This roadmap chooses node-hint resolution.

Do not start with Redis route-owner hash sharding. The top-level
`adapter:<adapterId>:worker:<workerId>:owner` key shape is a scale concern, but
this roadmap first removes stale endpoint evidence from hot-path handoff.

Do not start by changing worker lifecycle or worker runtime scheduling. A
selected worker is still chosen before transport. Transport only determines
whether a current delivery mechanism exists for that selected worker.

Do not preserve existing interfaces just because callers already use them.
`TaskDispatchChannel`, realtime adapter forwarding classes, and
`TransportDeliveryService` queue/direct-send helpers must each be deleted,
merged, or re-proven as a real owner boundary. A same-name interface with a new
parameter, or a pass-through wrapper that hides the old packet/envelope model
one layer deeper, does not satisfy this roadmap.

## Phases

### TDEO-0: Inventory And Guards

Classify every production use of:

- `TransportDispatchEnvelope`
- `TransportPacketFactory#fromDispatchContent`
- `EndpointLease`
- `ResolvedDeliveryItem`
- `TransportDeliveryCommandBatchCodec`
- `DispatchOutcome`
- `DeliveryObservationGroupContext`
- `DeliveryObservationItemSnapshot`
- `TransportDeliveryFailureEvent`
- `TaskDispatchChannel#dispatchEnvelopes`
- `WebSocketRealtimeWorkerAdapter`
- `SocketRealtimeWorkerAdapter`
- `TransportDeliveryService#enqueue`
- `TransportDeliveryService#sendDirect`

Acceptance:

- Inventory separates producer-side handoff, consumer-side listener, adapter
  final-hop, polling queue, failure channel, and tests.
- Inventory classifies `TaskDispatchChannel`, websocket/socket realtime worker
  adapters, and `TransportDeliveryService` methods as one of: delete, merge
  into an existing owner, or retain with explicit owner-boundary proof.
- Guard candidates are listed before implementation.
- The current executable slice is explicitly the node-hint slice from TDEO-1,
  not unresolved/broadcast/partition lane redesign.

Proof:

```powershell
rg -n "TransportDispatchEnvelope|EndpointLease|ResolvedDeliveryItem|fromDispatchContent\\(|dispatchEnvelopes\\(|DeliveryObservation|TransportDeliveryFailureEvent|DispatchOutcome" transport sdk xa-mass-server xa-mass-testing -g "*.java"
```

### TDEO-1: Keep Producer Node Hint, Move Endpoint Resolution To Consumer Node

Replace cross-process resolved items with node-targeted assigned items:

- Keep `DeliveryCommandBatch.targetTransportNodeId` because Redis handoff uses
  node ready-lanes.
- Change `DeliveryCommandBatch` items to carry `DeliveryCommand` or a minimal
  batch item record without `EndpointLease`.
- Change `TransportDeliveryCommandBatchCodec` so JSON contains no route,
  connection, or lease-expiry endpoint facts.
- Producer-side submitter may keep
  `activeOwnerForSelectedWorker(adapterId, selectedWorkerId)` only to derive
  `targetTransportNodeId` and group lane hints. It must not materialize or
  serialize endpoint lease facts.
- `TransportDeliveryCommandListener` or a listener-owned resolver must receive
  explicit dependencies on `WorkerDispatchRouteOwnerView` and local transport
  node identity.
- `MassApplication` and distributed transport assembly must pass route-owner
  view and `transportRuntimeComposition.getTransportNodeId()` into that
  listener/resolver.
- `TransportDeliveryCommandListener` must re-read
  `activeOwnerForSelectedWorker(adapterId, selectedWorkerId)` before adapter
  dispatch and verify the resolved owner still belongs to
  `batch.targetTransportNodeId()` and, when known, the local transport node id.
- Producer-side submitter remains the only owner for pre-handoff failures:
  missing owner while deriving node hint, node unavailable while validating the
  node hint, and handoff backpressure or shutdown.
- Consumer-side listener remains the only owner for post-handoff failures:
  owner disappeared after handoff, stale owner, wrong-node drift, unavailable
  adapter, or local send/enqueue failure.

Acceptance:

- `TransportAssignedDeliverySubmitter` no longer builds `EndpointLease`.
- `TransportAssignedDeliverySubmitter` may depend on
  `WorkerDispatchRouteOwnerView` only for node-hint resolution and may depend
  on `TransportNodeRegistry` only for node availability checks. It does not
  serialize route, connection, or lease facts.
- `TransportDeliveryCommandBatchCodec` has no `EndpointLeaseRecord`, no
  `routeKey`, no `connectionId`, and no `leaseExpireAtEpochMillis`.
- `TransportDeliveryCommandListener` owns endpoint re-resolution immediately
  before adapter dispatch.
- `TransportDeliveryCommandListener` constructor or resolver constructor makes
  `WorkerDispatchRouteOwnerView` and local node identity visible in production
  assembly.
- Missing owner before a node hint exists is handled by producer-side failure
  handling because no node-targeted batch can be offered.
- Missing owner after a node-targeted batch is drained is handled by
  consumer-side failure handling.
- Wrong-node owner drift after producer handoff is treated as one retryable
  transport delivery failure, not as permission to reselect a worker.
- Tests assert producer-side and consumer-side failure classes are emitted
  exactly once and are not double-compensated by callers.
- Existing selected-worker correctness remains: one selected worker cannot
  consume another selected worker's polling item even when route and queue are
  shared.

Tests:

- `TransportAssignedDeliverySubmitterTest`
- `TransportDeliveryCommandBatchCodecTest`
- `TransportDeliveryCommandListenerTest`
- `RedisTransportDeliveryCommandHandoffTest` or equivalent handoff
  backpressure/shutdown test
- `ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems`

### TDEO-2: Replace Adapter SPI And Polling Queue Payload Together

Change the adapter dispatch boundary and polling queue value in one executable
slice. Do not create a long-lived intermediate path where adapters accept a
typed request but polling immediately converts it back into a packet envelope.

- Replace `TaskDispatchChannel#dispatchEnvelopes(...)` with a request shape
  that carries command content/context and local endpoint evidence.
- Review whether `TaskDispatchChannel` should continue to exist. Default
  outcome is deletion unless it protects a real protocol seam beyond
  `WorkerAdapter` itself.
- Update `WorkerAdapter` implementations for polling, websocket, and socket.
- Delete or merge websocket/socket realtime worker adapter forwarding classes
  if they only delegate to another dispatch channel. If retained, document the
  concrete boundary they own.
- Replace `TransportDeliveryStore` queue value with a typed polling dispatch
  payload such as `QueuedPulledDispatch` whose context is the polling pull
  subset, not full worker-frame compatibility context.
- Replace `RedisTransportDispatchEnvelopeCodec` with a typed queue codec that
  serializes content/context directly.
- Polling adapter stores typed queue payloads and projects directly to
  `PulledTaskDispatch`.
- Keep task-dispatch packet construction only inside socket/websocket adapter
  wire assembly, or remove packet construction entirely by teaching frame
  codecs to encode the typed request directly.
- If socket/websocket still use `TransportPacket`, the builder is adapter-local
  and `TransportPacketFactory#fromDispatchContent` is moved out of
  `transport_runtime` delivery or deleted.
- Keep worker wire compatibility fields in adapter codecs when required; do
  not move `taskName/project/userId` into routing or queue truth.
- Replace `TransportDeliveryService#enqueue` and `#sendDirect` inputs with the
  new owner shape or move the behavior into the owning queue/direct executor.
  These methods must not become typed-request-to-old-envelope bridges.

Acceptance:

- `TransportDeliveryCommandListener` no longer calls
  `TransportPacketFactory#fromDispatchContent(...)`.
- `TaskDispatchChannel` is removed, or its retained name and methods are
  justified as a real owner/protocol boundary. A same-name pass-through with
  only a new parameter type is not accepted.
- `WorkerAdapter` production implementations do not accept
  `List<TransportDispatchEnvelope>`.
- `WebSocketRealtimeWorkerAdapter` and `SocketRealtimeWorkerAdapter` are removed
  or proven not to be pure forwarding wrappers.
- `TransportDeliveryService` does not accept `TransportDispatchEnvelope` and
  does not construct one internally from the new request shape.
- `TransportDeliveryStore` does not enqueue, drain, or poll
  `TransportDispatchEnvelope`.
- `RedisTransportDispatchEnvelopeCodec` is removed or replaced by a typed
  queue codec.
- socket/websocket frame tests still prove the outbound wire packet contains
  required compatibility fields.
- polling Redis queue codec does not serialize `TransportPacket`.
- polling Redis queue JSON does not contain `packet`, `routeKey`,
  `transportPayload`, `workerId`, `taskName`, `project`, or `userId`.

Tests:

- `WebSocketTaskDispatchChannelTest`
- `SocketTaskDispatchChannelTest`
- `PollingWorkerAdapterTest`
- `RedisTransportDispatchEnvelopeCodecTest` or successor typed queue codec test
- `InMemoryTransportDeliveryStoreTest`
- `RedisTransportDeliveryStoreTest`
- `TransportDeliveryPollResultTest`
- `TransportRuntimeRegistryTest`
- `TransportRegistrationResolverTest`
- `PulledTaskDispatchTest`
- `TaskPullResultTest`

### TDEO-3: Remove Obsolete Packet Envelope Artifacts

After TDEO-2 lands, delete or narrow the old packet-envelope artifacts rather
than preserving them as compatibility surfaces:

- Remove `TransportDispatchEnvelope` if no production caller remains.
- If a narrow envelope is still needed for adapter-local diagnostics, rename it
  to its real owner and keep it out of transport-api adapter SPI.
- Remove `TransportPacketFactory#fromDispatchContent` from transport-runtime
  delivery. Keep result/system-event packet construction only if those paths
  still require it.
- Remove packet payload reverse-mapping from `PollingWorkerAdapter` if any
  transitional helper remains after TDEO-2.

Acceptance:

- No production polling queue path imports `TransportPacket`.
- `PollingWorkerAdapter` builds `PulledTaskDispatch` from typed content/context.
- The only remaining task-dispatch packet construction is adapter wire
  construction for protocols that require it.
- `TransportPacketFactory#fromDispatchContent` has no production caller.

Tests:

- `PollingWorkerAdapterTest`
- `TransportConvergenceArchitectureGuardTest`

### TDEO-4: Collapse Outcome / Failure Fact Duplication

Choose and implement the single delivery failure fact owner. The first TDEO-4
change must record the concrete choice; implementation must not start by adding
a wrapper around the existing three-record event shape.

Required behavior:

- missing owner, stale owner, wrong node, adapter unavailable, direct endpoint
  unavailable, queue backpressure, and executor rejection each produce one
  retryable failure event when retryable;
- invalid command/frame construction failures are non-retryable unless the
  existing policy says otherwise;
- callers can observe dispatch outcomes without becoming compensation owners;
- failure handler payload contains task/message/attempt context needed by
  engine/starter compensation.

Acceptance:

- The chosen main fact structure is named before field migration starts.
- The rejected option is removed from the roadmap or recorded as a non-goal for
  this roadmap.
- `TransportDeliveryFailureEvent` no longer contains
  `groupContext + itemSnapshot + outcome` as three sibling fact copies.
- `DeliveryObservationSupport` is removed or becomes a trivial constructor for
  the single fact owner.
- `TransportDeliveryFailureEventCodec` serializes the single fact owner once.
- Tests assert exactly-once failure handling for each retryable failure class.

Tests:

- `TransportDeliveryFailureEventCodecTest`
- `RedisTransportDeliveryFailureChannelTest`
- `TransportAssignedDeliverySubmitterTest`
- `TransportDeliveryCommandListenerTest`
- `TransportConvergenceArchitectureGuardTest`

### TDEO-5: Route-Owner Read Surface Cleanup

Keep route-owner reads aligned with selected-worker delivery:

- `activeOwnerForSelectedWorker(adapterId, selectedWorkerId)` remains the
  assigned-delivery lookup surface. Under the current node-lane handoff it has
  two transport-owned callers: producer node-hint resolution and consumer
  final-hop endpoint resolution. It is not a worker lifecycle or SDK inspection
  view.
- `currentOwners(routeKey)` and route-key diagnostic helpers are either moved
  to a diagnostic interface or guarded so SDK/starter inspection cannot treat
  route-owner as worker lifecycle truth.
- Redis key shape optimization, including sharded hashes for selected-worker
  pointers, is deferred to a dedicated Redis keyspace roadmap after the
  endpoint handoff shape is stable.

Acceptance:

- No SDK public/inspection path reads route-owner to answer worker lifecycle or
  reachability.
- Dispatch hot path does not scan `currentOwners(routeKey)`.
- Diagnostic route-owner APIs are named and documented as diagnostics.

Tests:

- `RedisTransportRouteOwnerStoreTest`
- `InMemoryTransportRouteOwnerStoreTest`
- `TransportConvergenceArchitectureGuardTest`
- `TransportRedisKeyspaceGuardTest`

### TDEO-6: Remove Residue And Update Owner Docs

Remove stale terminology and lock the new boundary:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `transport/AGENTS.md`.
- Update SDK/server docs that mention `TaskDispatchItem`,
  `TransportDispatchEnvelope`, or packet-backed polling queues.
- Update `doc/PROOF_REGISTRY.md`.
- Add guards for the final shape.

Acceptance:

- Active non-archive docs no longer describe `TaskDispatchItem` or
  packet-backed polling queue as current truth.
- `TransportPacket` is absent from transport runtime delivery handoff/store
  code except adapter wire assembly allowlist.
- `EndpointLease` is absent from process-boundary batch codecs.
- `TaskDispatchChannel` and `WorkerAdapter` do not expose packet envelopes.
- `DispatchOutcome` and failure event facts do not duplicate the same fields in
  parallel records.

Guard scans:

```powershell
# Old public/core pull residue.
rg -n "TaskDispatchItem|getDispatchViews|pollDispatchViews|toDispatchView|toDispatchViews" transport sdk xa-mass-server xa-mass-testing doc xa-mass-engine -g "*.java" -g "*.md" -g "!doc/archive/**"

# Process-boundary delivery command codec must not serialize endpoint facts.
rg -n "EndpointLeaseRecord|routeKey|connectionId|leaseExpireAtEpochMillis|endpoint\\." transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchCodec.java

# Adapter SPI and worker adapter implementations must not expose packet envelopes.
rg -n "dispatchEnvelopes\\(|List<TransportDispatchEnvelope>|TransportDispatchEnvelope" transport/transport_api/src/main/java/com/xa/mass/transport/channel transport/transport_api/src/main/java/com/xa/mass/transport/worker transport/polling-adapter/src/main/java transport/socket-adapter/src/main/java transport/websocket-adapter/src/main/java -g "*.java"

# Existing dispatch interfaces and realtime adapters must not remain as pass-through wrappers.
rg -n "interface TaskDispatchChannel|extends TaskDispatchChannel|TaskDispatchChannel taskDispatchChannel|return taskDispatchChannel\\." transport/transport_api/src/main/java transport/socket-adapter/src/main/java transport/websocket-adapter/src/main/java -g "*.java"

# Delivery service must not bridge the new shape back to old packet envelopes.
rg -n "TransportDispatchEnvelope|List<TransportDispatchEnvelope>|new TransportDispatchEnvelope|RuntimeDispatchOutcomes\\.missingRoute" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryService.java

# Polling queue codec/store paths must not serialize TransportPacket.
rg -n "TransportPacket|packet" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery -g "*Dispatch*Codec.java" -g "*DeliveryStore.java"

# Failure codec must not preserve sibling group/item duplicate records.
rg -n "DeliveryObservationGroupContextRecord|DeliveryObservationItemSnapshotRecord|groupContext|itemSnapshot" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureEventCodec.java
```

## Non-Goals

- Do not change engine worker selection, retry policy, task lifecycle, or worker
  runtime scheduling.
- Do not change worker online/offline lifecycle semantics.
- Do not change the `PulledTaskDispatch` public pull DTO in this roadmap unless
  a proof fails because of the new queue payload.
- Do not remove socket/websocket worker wire compatibility fields from protocol
  frames.
- Do not implement Redis sharded hashes for route-owner selected-worker
  pointers in this roadmap.
- Do not preserve old transport packet/envelope paths through deprecated
  aliases, compatibility wrappers, or dual-write codecs.

## Verification Commands

Current slice compile:

```powershell
.\mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

Transport focused tests:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am test "-Dtest=DispatchOutcomeTest,DeliveryCommandTest,PulledTaskDispatchTest,TaskPullResultTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,RedisQueuedPulledDispatchCodecTest,InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,TransportDeliveryServiceTest,TransportDeliveryPollResultTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryFailureEventCodecTest,RedisTransportDeliveryFailureChannelTest,RedisTransportRouteOwnerStoreTest,InMemoryTransportRouteOwnerStoreTest,TransportRedisKeyspaceGuardTest,PollingWorkerAdapterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,SocketTransportFrameCodecTest,WebSocketTransportFrameCodecTest,WebSocketInputProcessorTest,DispatcherInboundHandlerTest,SocketTransportServerTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

SDK/server polling proof:

```powershell
.\mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=PullWorkerSessionTest,MassSdkTest#pullWorkerSessionCompletesTaskWithoutWebsocketPush,ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems,CrawlerPullWorkerSdkRegistrationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

SDK/starter distributed transport proof:

```powershell
.\mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Full compile before completion:

```powershell
.\mvnw -q -DskipTests compile
```

## Completion Criteria

This roadmap is complete only when:

- `TransportPacket` is adapter-wire-only for task dispatch.
- process-boundary delivery batches do not serialize endpoint leases.
- endpoint resolution happens on the consumer transport node immediately before
  final-hop send/enqueue.
- polling delivery queues store typed dispatch payloads, not packets.
- `TransportDispatchEnvelope` is removed or narrowed so it is no longer the
  adapter SPI and polling queue carrier.
- delivery failure facts are represented once.
- active docs and guards reflect the new boundary.
- the verification commands above pass or are replaced by equivalent current
  proof commands in the roadmap before archive.
