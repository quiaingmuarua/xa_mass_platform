# Transport Bucket + Selected Worker Delivery Convergence Roadmap

Status: complete; archived after worker wire residue and active-roadmap residue
cleanup on 2026-06-15.

## Summary

This roadmap was created because transport assigned-task delivery exposed too
many historical routing concepts on the producer-facing handoff path:
`adapterId`, `routeKey`, `transportNodeId`, `connectionId`,
`deliveryQueueKey`, and `DeliveryCommandGroup`. The current convergence target
is that the engine/starter -> assigned-delivery boundary stays smaller:

```text
deliveryBucketId + selectedWorkerId + typed payload/context
```

This roadmap converges assigned-task delivery around that boundary. `routeKey`,
`connectionId`, adapter protocol, endpoint lease, target node evidence, and
delivery lane keys stay inside transport route-consumer records, handoff
mechanics, and final-hop endpoint resolution.

Target principle:

```text
engine/starter output:
  deliveryBucketId      // opaque transport delivery bucket, currently derived from workerGroupId
  selectedWorkerId      // engine-selected execution identity
  TaskDispatchContent   // worker-facing execution payload view
  TaskDispatchExecutionContext
                       // typed attempt/result context

transport internal:
  (deliveryBucketId, selectedWorkerId) -> route consumer / endpoint evidence
```

`deliveryBucketId` is not a worker-runtime scheduling concept. It is a
transport delivery-domain projection. It may currently equal `workerGroupId`,
but transport must treat it as opaque.

`TaskDispatchContent` and `TaskDispatchExecutionContext` are allowed at this
boundary because they are the assigned item's execution payload and typed
attempt/result context. They must not become generic metadata carriers for
route, adapter, node, connection, queue, task-shell, or diagnostic facts.

## Current Implementation State

This roadmap started from the old producer-facing
`adapterId + selectedWorkerId` route-owner lookup. In the current worktree,
the bucket-worker mainline is implemented:

- `DeliveryCommand` now carries `deliveryBucketId + selectedWorkerId` plus
  `TaskDispatchContent`, `TaskDispatchExecutionContext`, command id, deadline,
  and creation time.
- `DeliveryCommandGroup` has been removed from main source.
- `WorkerDispatchRouteOwnerView` no longer exposes
  `activeOwnerForSelectedWorker(...)`; it exposes
  `targetForSelectedWorker(deliveryBucketId, selectedWorkerId)` for producer
  handoff and `endpointForSelectedWorker(deliveryBucketId,
  selectedWorkerId)` for target-node final hop.
- `TransportRouteOwnerStore` route-owner writes use
  `TransportRouteOwnerClaim`, which carries `deliveryBucketId` explicitly.
- `DeliveryCommandBatch` carries `deliveryBucketId`, `deliveryLaneKey`,
  `targetTransportNodeId`, and commands; adapter, route, connection, and lease
  evidence stay out of the batch.
- `TransportAssignedDeliverySubmitter` resolves a narrow target-node hint and
  does not consume full endpoint evidence.
- `TransportDeliveryCommandListener` re-resolves endpoint evidence by
  `deliveryBucketId + selectedWorkerId` on the target node before adapter
  dispatch.
- Redis route-owner storage is converging from the old adapter-worker pointer
  to a bucket-worker current-consumer pointer.
- Transport owner docs and proof registry have been updated for the
  bucket-worker boundary.

Remaining proof/doc work before this roadmap can be marked complete:

- SDK/server/external worker tests must prove bucket propagation through
  embedded SDK, public Java SDK, worker-pack samples, and server
  `ExternalWorkerApiController` paths.
- Public and operator diagnostics must not expose `adapterId`, `routeKey`,
  `transportNodeId`, `connectionId`, or `deliveryQueueKey` as assigned-delivery
  target facts.
- Any remaining external sample or test that manually sends route-owner
  internals must be classified as adapter-local compatibility proof or moved
  behind a managed session API.
- Focused and cross-module verification commands in this roadmap must pass
  without relying on `failIfNoSpecifiedTests=false` for newly named tests.

`TaskDispatchContent` remains the worker-facing execution payload view. It
must not carry route-owner, adapter, node, connection, lane, or cache metadata.
`TaskDispatchExecutionContext` remains the typed attempt/result sidecar:
`attemptId`, `attemptNo`, `retryCount`, and `batchId`. It must not grow route,
adapter, task shell, or generic correlation fields.

## Owner Review

`workerGroupId` belongs to worker-runtime and scheduling as capability and
resource-domain input.

`deliveryBucketId` belongs to transport as an opaque delivery-domain/bucket
projection. Starter or engine assembly may derive it from
`TaskDispatchBinding.workerGroupId` for the current default, but transport must
not interpret worker-group semantics.

`selectedWorkerId` belongs to engine assignment as execution correctness.
Transport consumes it only as a delivery constraint.

`TaskDispatchContent` belongs to the assigned item execution view. It carries
the worker-facing payload needed to invoke the selected worker's handler, not
transport routing facts.

`TaskDispatchExecutionContext` belongs to typed attempt/result correlation. It
is not a routing extension point and must not absorb adapter/session metadata.

`routeKey`, `connectionId`, adapter protocol, endpoint lease, and route
consumer records belong to transport internals. They are connection evidence,
not assignment facts.

`adapterId` is concrete adapter runtime/protocol identity inside transport
assembly. It may appear in adapter configuration, final-hop adapter selection,
and adapter-owned diagnostics, but it is not an engine/starter assigned-delivery
input and not a public worker-session target id.

`adapterNodeId` is a logical worker registration endpoint/deployment identity
used by server/SDK worker registration and node/group binding. It tells the
platform which registered endpoint relation a worker belongs to; it is not the
unique transport endpoint, not a connection id, and not a replacement for
selected worker delivery.

`transportNodeId` is the split transport process/node identity used for
node-local handoff and heartbeat. It is handoff locality evidence inside
transport and must not leak to engine/starter assignment, public worker
sessions, worker pull DTOs, or SDK worker inspection as a caller-controlled
delivery target.

`targetTransportNodeId` is handoff locality evidence. Producer-side code may
resolve it through a narrow target view, but it must not receive full endpoint
evidence or adapter protocol facts.

## Boundary Decision

Replace the producer-facing assigned delivery key:

```text
adapterId + selectedWorkerId
```

with:

```text
deliveryBucketId + selectedWorkerId
```

The full engine/starter -> assigned-delivery command boundary is:

```text
deliveryBucketId
+ selectedWorkerId
+ TaskDispatchContent
+ TaskDispatchExecutionContext
+ command id / timing
```

`adapterId`, `routeKey`, `connectionId`, `transportNodeId`, and
`deliveryQueueKey` must not cross this boundary. A producer-side submitter may
obtain a narrow target-node hint only after it is already inside transport
assigned-delivery mechanics; that hint is not part of the engine/starter
contract.

The wider perimeter has the same rule:

```text
engine / starter / assigned-delivery boundary:
  deliveryBucketId + selectedWorkerId + TaskDispatchContent
  + TaskDispatchExecutionContext + command identity/timing

public worker registration/session boundary:
  workerGroupId + workerId + adapterNodeId + transportHint
```

Public worker APIs, SDK sessions, worker pull DTOs, and worker inspection or
diagnostic summaries must not expose `adapterId`, `routeKey`, `connectionId`,
`transportNodeId`, or `deliveryQueueKey` as delivery target facts. If an
operator-only diagnostic still needs an internal id, it must be explicitly
bounded to the transport diagnostic surface and documented as non-contractual,
not returned from mainline worker/session APIs.

`deliveryBucketId` is required. If starter cannot derive it from
`TaskDispatchBinding.workerGroupId` for the current default rule, the binding is
invalid and must enter the existing failure/compensation path exactly once.
There must be no fallback to `adapterId`, `routeKey`, a default bucket, or an
adapter-specific rule.

Route-owner writes must receive `deliveryBucketId` explicitly from adapter
session registration, heartbeat, and release paths. Do not derive it inside the
route-owner store by decoding route keys, adapter ids, or worker-group rules.

Producer submitter owns only assignment-to-handoff locality:

```text
DeliveryCommand(bucket, selectedWorker, content, executionContext)
  -> SelectedWorkerDeliveryTarget(targetTransportNodeId)
  -> DeliveryCommandBatch(bucket, lane, targetNode, commands)
```

Final-hop listener owns endpoint resolution and adapter protocol dispatch:

```text
DeliveryCommandBatch(bucket, targetNode, commands)
  -> for each command resolve route consumer by bucket + worker
  -> verify route consumer still targets local node
  -> group by adapter protocol
  -> adapter dispatch
```

No dual-write compatibility track is intended. During implementation slices,
old call sites may exist only until the same executable slice compiles and its
acceptance is met; they must not be exposed as a second public path.

## Target Shape

### Minimal Assigned Command

```java
DeliveryCommand {
  commandId
  deliveryBucketId
  selectedWorkerId
  TaskDispatchContent
  TaskDispatchExecutionContext
  deadlineEpochMillis
  createdAtEpochMillis
}
```

`DeliveryCommand` must not carry:

- `adapterId`
- `routeKey`
- `connectionId`
- `transportNodeId`
- `deliveryQueueKey`
- endpoint lease or route-owner records
- task shell metadata such as `taskName`, `project`, or `userId`
- generic correlation maps

`TaskDispatchContent` and `TaskDispatchExecutionContext` are the only allowed
payload/context sidecars in this command. Their field sets must remain typed
and allowlisted; they must not be used to smuggle the forbidden transport
owner/evidence ids listed above.

### Route-Owner Write Contract

Prefer replacing long positional route-owner writes with a typed claim object:

```java
record TransportRouteOwnerClaim(
    String workerId,
    String deliveryBucketId,
    String adapterId,
    String routeKey,
    String connectionId,
    String reason
) {}
```

The store may persist adapter protocol and endpoint evidence, but producer
lookup must be indexed by `deliveryBucketId + selectedWorkerId`.

`adapterId` in this claim is transport-internal protocol evidence. It is kept
only so final-hop adapter selection and adapter-owned session validation can
remain explicit. It must not be used as an engine/starter assignment fact,
producer lookup dimension, command grouping key, or delivery lane owner.

Assigned delivery has one current route consumer per
`deliveryBucketId + selectedWorkerId`. A new claim for the same pair replaces
the current consumer. Heartbeat refreshes and release operations may mutate the
current consumer only when their connection/session identity still matches the
stored current consumer. Stale heartbeat/release from a replaced connection
must be ignored and must not restore or remove the replacement. If the current
consumer is missing, expired, or moved, transport reports a retryable delivery
failure; it must not fallback to another adapter protocol or session for the
same worker.

This is also the multi-protocol rule. If the same worker connects through
polling plus websocket, or through multiple sessions of the same protocol under
the same `deliveryBucketId`, transport does not rank protocols, merge sessions,
or search alternates during assigned delivery. The latest valid claim for that
bucket-worker pair is the only current consumer until it expires or is replaced.

### Producer Target View

Producer-side submitter should depend on a narrow target view:

```java
Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(
    String deliveryBucketId,
    String selectedWorkerId
);

record SelectedWorkerDeliveryTarget(
    String deliveryBucketId,
    String selectedWorkerId,
    String targetTransportNodeId
) {}
```

The submitter must not import, construct, or inspect
`WorkerDispatchRouteOwner`. It must not see `routeKey`, `connectionId`, lease
expiry, or adapter protocol.

### Process-Boundary Batch

`DeliveryCommandBatch` should represent one physical handoff lane:

```java
DeliveryCommandBatch {
  deliveryBucketId
  deliveryLaneKey
  targetTransportNodeId
  List<DeliveryCommand> items
}
```

`adapterId` is not a producer-facing batch fact. If an intermediate slice keeps
`adapterId` on `DeliveryCommandBatch`, that slice is not the mainline target
and must explicitly mark it as residue with a removal acceptance item.

### Final-Hop Endpoint View

Final-hop listener should resolve full endpoint evidence through a listener-only
view:

```java
Optional<RouteConsumerEndpoint> endpointForSelectedWorker(
    String deliveryBucketId,
    String selectedWorkerId
);

record RouteConsumerEndpoint(
    String deliveryBucketId,
    String selectedWorkerId,
    String adapterId,
    String routeKey,
    String connectionId,
    String transportNodeId,
    long leaseExpireAtEpochMillis
) {}
```

This full view is transport-internal endpoint evidence. It is not exposed to
engine/starter assignment and is not a producer submitter dependency.

### Public Worker / SDK Shape

External worker callers should see stable worker topology and session facts:

```text
workerGroupId
workerId
adapterNodeId
transportHint
sessionToken
PulledTaskDispatch
TaskResultReport
```

They should not see transport internal owner/evidence ids as dispatch targets:

- `adapterId`
- `routeKey`
- `connectionId`
- `transportNodeId`
- `deliveryQueueKey`
- `deliveryBucketId` as a caller-configured id

`deliveryBucketId` may be derived internally from worker-group context for
assigned delivery, but external worker sessions declare `workerGroupId`. The
bucket is not a public registration field until a separate product/API decision
proves why callers must name transport delivery buckets directly.

## Non-Goals

- Do not rename worker-runtime or engine `workerGroupId` globally to
  `bucketId`.
- Do not make `deliveryBucketId` a scheduling or capability concept.
- Do not make routeKey unique per worker to solve selected-worker correctness.
- Do not expose `routeKey`, `connectionId`, endpoint lease, adapter protocol,
  or transport-node details to engine assignment.
- Do not expose transport internal ids through public worker/session APIs or
  SDK worker inspection as a workaround for missing proof. `adapterNodeId` and
  `transportHint` are the public registration/session inputs; `adapterId`,
  `routeKey`, `connectionId`, `transportNodeId`, and `deliveryQueueKey` remain
  internal.
- Do not retire `routeKey` globally in this roadmap. This roadmap only prevents
  `routeKey` from leaking into the engine/starter assigned-delivery boundary;
  routeKey retirement across route-owner internals, result ingress, raw route
  APIs, and adapter wire protocols belongs to a follow-up internal-id
  convergence roadmap.
- Do not turn payload/context into generic metadata maps. Payload/context means
  `TaskDispatchContent` plus `TaskDispatchExecutionContext`, with typed fields
  only.
- Do not keep `DeliveryCommandGroup` as a compatibility wrapper after callers
  move.
- Do not rewrite worker lifecycle, admission, reachability, or worker-runtime
  eligibility in this roadmap.
- Do not solve future hash-bucket partitioning here. The current bucket may be
  the worker group derived bucket; finer bucket fanout can be a later roadmap.

## Do Not Start With

Do not start by deleting `adapterId` from adapter registration or final-hop
dispatch. Real adapter dispatch still needs protocol-specific implementations.

Do not start by changing only `DeliveryCommand`. Without route-owner claim
bucket writes and final-hop bucket endpoint resolution, the producer will lose
the adapter key before the listener can recover endpoint evidence.

Do not start by hiding removed owner facts inside payload/context. That keeps
the old ambiguity while making it harder to guard.

Do not add a wrapper or alias around `DeliveryCommandGroup`. The model exists
because `adapterId` leaked into producer grouping; the target is deletion, not
renaming.

## Slice BWD-0: Inventory And Contract Lock

Goal: classify every current adapter-worker lookup and lock the exact contract
that the first executable slice will implement.

Scope:

- `DeliveryCommand`
- `DeliveryCommandGroup`
- `DeliveryCommandBatch`
- `TaskDispatchDeliveryCommandSubmitter`
- `TransportAssignedDeliverySubmitter`
- `TransportDeliveryCommandListener`
- `TransportRouteOwnerStore`
- `WorkerDispatchRouteOwnerView.activeOwnerForSelectedWorker(...)`
- `RedisTransportRouteOwnerStore.adapterWorkerKey(...)`
- in-memory route-owner selected-worker index
- polling/socket/websocket session registration, heartbeat, release, and tests

Acceptance:

- Inventory classifies each caller as producer target lookup, final-hop endpoint
  lookup, route-owner write, adapter/session proof, diagnostic read, or test
  fixture.
- The roadmap locks the name `deliveryBucketId` for transport contracts.
- The locked engine/starter -> assigned-delivery boundary is
  `deliveryBucketId + selectedWorkerId + TaskDispatchContent +
  TaskDispatchExecutionContext`, plus command identity and timing only.
- `payload/context` is defined as typed execution content/context, not as a
  generic extension bag.
- Route-owner write contract is explicit: adapter/session registration,
  heartbeat, and release pass `deliveryBucketId`; the store does not infer it
  from route key or adapter id.
- Current-consumer semantics are explicit: one active assigned-delivery
  consumer per `(deliveryBucketId, selectedWorkerId)`, replacement by newer
  claim, stale heartbeat/release ignored, and no protocol/session fallback.
- Inventory includes embedded SDK polling sessions, public Java SDK polling and
  WebSocket sessions, server `ExternalWorkerApiController`, and external
  polling integration paths, not only transport adapter unit tests.
- BWD-1 is treated as one contract pivot composed of compile-safe checkpoints;
  intermediate checkpoints are not release/completion points and no
  break-now-fix-later phase is allowed.
- No code behavior changes are required unless the inventory is embedded in the
  roadmap in the same change.

## Milestone BWD-1: Bucket-Worker Mainline Pivot

Goal: make the assigned-task delivery mainline compile and run on
`deliveryBucketId + selectedWorkerId + typed payload/context`, without a
producer-visible adapter route, lane, node, connection, or full route-owner
record.

BWD-1 is a milestone, not one oversized implementation commit. Execute the
sub-slices below as compile-safe checkpoints. A checkpoint may leave explicitly
named old call sites in place only as same-milestone residue; it must not add
deprecated aliases, wrappers, fallback behavior, or a second public API. BWD-1
is complete only after BWD-1d removes the producer-facing adapter/route-owner
residue.

### Sub-slice BWD-1a: Bucket Claim And Current Consumer Projection

Goal: route-owner writes and stores know the bucket and can answer bucket-worker
target/endpoint reads before producer handoff moves.

Scope:

- Replace `TransportRouteOwnerStore` positional writes or extend them with an
  explicit `deliveryBucketId`; prefer `TransportRouteOwnerClaim` to prevent
  argument order drift.
- Document `TransportRouteOwnerClaim.adapterId` as internal adapter protocol
  evidence only.
- Update embedded polling, socket, and websocket session registration,
  heartbeat, and release paths to pass `deliveryBucketId` from worker/session
  assembly.
- Update public Java SDK and server/external worker registration paths that
  mint transport sessions or polling presence so they pass the worker-declared
  delivery bucket explicitly; these paths must not rely on route-key decoding or
  adapter defaults.
- Update in-memory and Redis route-owner stores to persist `deliveryBucketId`
  and maintain the current `(deliveryBucketId, selectedWorkerId) -> route
  consumer` projection.
- Add `SelectedWorkerDeliveryTarget targetForSelectedWorker(...)` and
  `RouteConsumerEndpoint endpointForSelectedWorker(...)`.
- Preserve stale heartbeat/release safety under the new bucket-worker current
  consumer rule.

Acceptance:

- Store tests prove one current consumer per `(deliveryBucketId,
  selectedWorkerId)`, latest claim wins, stale heartbeat/release cannot mutate
  a replacement, and no alternate protocol/session fallback is attempted.
- `targetForSelectedWorker(deliveryBucketId, selectedWorkerId)` returns only
  target node evidence, not route key, connection id, lease, or adapter
  protocol.
- `endpointForSelectedWorker(deliveryBucketId, selectedWorkerId)` is
  listener-only and may expose adapter protocol plus endpoint evidence.
- Route-owner claim/write tests cover missing bucket as invalid input.
- Public worker registration/session tests prove bucket propagation reaches the
  route-owner write boundary for embedded SDK, Java SDK, and server API flows.

### Sub-slice BWD-1b: Command Boundary And Missing Bucket Failure

Goal: make the engine/starter -> assigned-delivery command boundary carry the
bucket and reject bindings that cannot produce one.

Scope:

- Add `deliveryBucketId` to `DeliveryCommand`.
- Keep `DeliveryCommand` payload/context as `TaskDispatchContent` and
  `TaskDispatchExecutionContext`; do not introduce generic correlation or
  metadata maps.
- Derive `deliveryBucketId` in starter assembly from current
  `TaskDispatchBinding.workerGroupId`.
- Treat missing or blank derived bucket as an invalid delivery binding and send
  it through the existing failure/compensation path exactly once.
- Do not fallback to `adapterId`, `routeKey`, a default bucket, or an
  adapter-specific derivation rule.

Acceptance:

- `DeliveryCommand` includes `deliveryBucketId` and still excludes route,
  adapter, node, connection, queue, endpoint, task shell metadata, and generic
  correlation fields.
- `DeliveryCommand` exposes only delivery bucket, selected worker, typed
  content/context, command id, deadline, and created-at timing across the
  engine/starter -> assigned-delivery boundary.
- `TaskDispatchContent` and `TaskDispatchExecutionContext` do not gain
  route-owner, adapter, node, connection, lane, task shell metadata, or generic
  correlation fields.
- Submitter translation tests prove missing bucket is compensated once and does
  not use adapter or route fallback.

### Sub-slice BWD-1c: Producer Handoff And Final-Hop Listener Pivot

Goal: remove adapter-derived grouping from the assigned-delivery producer path
and let final-hop transport own endpoint and adapter protocol resolution.

Scope:

- Change `TransportAssignedDeliverySubmitter` to accept
  `List<DeliveryCommand>`, call `targetForSelectedWorker(...)`, group by typed
  `deliveryBucketId + targetTransportNodeId` lane, and offer batches.
- Delete `DeliveryCommandGroup`.
- Change `DeliveryCommandBatch` and its codec to carry bucket/lane/target node,
  not `adapterId`.
- Change handoff implementations to use the new batch shape.
- Change `TransportDeliveryCommandListener` to resolve endpoint evidence by
  `deliveryBucketId + selectedWorkerId`, validate the consumer is still on the
  local target node, then group requests by adapter protocol from endpoint
  evidence.
- Keep missing target, moved endpoint, expired endpoint, unavailable node,
  unavailable adapter, and handoff backpressure as retryable transport
  outcomes. Transport still must not select another worker.

Acceptance:

- `TaskDispatchDeliveryCommandSubmitter` does not read or group by
  `adapterId` for assigned delivery.
- `TransportAssignedDeliverySubmitter` no longer imports or handles
  `WorkerDispatchRouteOwner` and no longer calls
  `activeOwnerForSelectedWorker(...)`.
- `DeliveryCommandGroup` is deleted from main source and test fixtures.
- `DeliveryCommandBatch` no longer carries `adapterId`.
- Listener endpoint resolution uses `deliveryBucketId + selectedWorkerId`, not
  `batch.adapterId() + selectedWorkerId`.
- A worker connected through different adapter protocols resolves by
  bucket-worker for producer handoff; protocol choice remains final-hop
  endpoint evidence.
- Tests cover two buckets, shared route keys, selected-worker isolation,
  missing target, target moved after handoff, expired endpoint, unavailable
  local adapter, and handoff backpressure.

### Sub-slice BWD-1d: BWD-1 Residue Removal

Goal: make BWD-1 releaseable by deleting old producer-facing lookup residue and
locking the new boundary.

Scope:

- Remove producer-path calls to `activeOwnerForSelectedWorker(adapterId,
  selectedWorkerId)`.
- Remove producer-path imports of `WorkerDispatchRouteOwner`.
- Remove `DeliveryCommandGroup` from main source and tests.
- Remove `adapterId` from `DeliveryCommandBatch` tests, fixtures, and codecs.
- Add targeted guards that allow final-hop endpoint evidence but reject
  producer-facing adapter/route-owner leakage.

Acceptance:

- BWD-1 can be considered complete only after this residue removal slice passes.
- Architecture guards fail if assigned-delivery producer code reintroduces
  `adapterId + selectedWorkerId`, `DeliveryCommandGroup`,
  `WorkerDispatchRouteOwner`, or route/connection/node/queue facts in
  `DeliveryCommand`.

## Slice BWD-2: Redis Keyspace And Store Proof Hardening

Goal: make Redis route-owner storage prove the new owner boundary at key and
query-shape level.

Scope:

- Replace Redis producer pointer key shape from adapter-worker to bucket-worker.
- Remove `adapterWorkerKey(...)` and equivalent in-memory helper names.
- Update Redis key manifest in transport docs.
- Keep route consumer records internal and still storing adapter protocol,
  routeKey, connectionId, transportNodeId, and lease evidence.
- Keep bounded route-key diagnostic reads, but ensure they are not dispatch
  hot-path lookup.

Acceptance:

- Redis key manifest rejects producer lookup keys shaped as
  `adapter:<adapterId>:worker:<workerId>:owner`.
- Redis and in-memory route-owner tests prove `(bucketId, workerId)` resolves
  the current target node.
- Redis and in-memory route-owner tests prove stale heartbeat/release cannot
  remove a replacement consumer for the same worker and bucket.
- Redis proof tests assert routeKey/connectionId remain endpoint diagnostics or
  route-consumer evidence, not producer lookup keys.

## Slice BWD-3: Adapter Session And Polling Delivery Proofs

Goal: prove adapter/session behavior still works after bucket-worker delivery
becomes the mainline.

Scope:

- Polling adapter route-owner announcements and poll result isolation.
- Socket session connect, replacement, heartbeat, release, and dispatch tests.
- WebSocket session connect, replacement, heartbeat, release, and dispatch
  tests.
- Embedded SDK `PullWorkerSession` bucket claim, heartbeat, release, poll, and
  result submit tests.
- Public Java SDK `PollingWorkerSession` and `WebSocketWorkerSession`
  registration/presence/poll flows.
- Server `ExternalWorkerApiController` worker group, adapter node, worker
  registration, presence, poll, and result APIs.
- SDK distributed transport assembly and server/external polling integration
  tests that exercise shared route/bucket behavior.

Acceptance:

- Polling workers sharing routeKey or delivery lane cannot consume another
  selected worker's items.
- Socket and websocket session replacement reprojects the current bucket-worker
  consumer and stale disconnect/heartbeat events do not revoke replacements.
- Session registration uses the bucket from worker/session assembly; no adapter
  decodes route keys to invent bucket.
- Public worker APIs and Java SDK sessions do not expose adapter/route/node/
  connection/lane ids as assigned-delivery target facts.
- External polling integration proves bucket-worker delivery works through the
  server/API/SDK path, not only embedded transport unit tests.
- Distributed transport tests prove producer handoff reaches the target node
  through bucket-worker target resolution and final-hop endpoint re-resolution.

## Slice BWD-4: Lane Naming, Docs, And Guards

Goal: remove old vocabulary from active contracts and add precise anti-drift
guards without banning legitimate final-hop endpoint evidence.

Scope:

- Rename assigned-delivery lane helpers and docs from adapter-lane wording to
  bucket/lane wording where they describe producer handoff.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md`.
- Update SDK and public worker docs where they describe external worker
  registration/session inputs, SDK diagnostics, or worker pull DTOs.
- Update active roadmaps that still describe `adapterId + selectedWorkerId` as
  producer lookup target.
- Add architecture guards for command shape, producer submitter dependencies,
  route-owner Redis key shape, public worker/session id exposure, and forbidden
  `DeliveryCommandGroup` residue.

Acceptance:

- Production submitter code contains no `adapterId + selectedWorkerId`
  producer lookup.
- Production submitter code contains no `WorkerDispatchRouteOwner` import.
- Architecture guard fails if engine/starter assigned-delivery code exposes
  route, adapter, node, connection, endpoint, queue, or lane ids outside the
  transport-owned handoff/resolution internals.
- Architecture guard or focused SDK/server tests fail if public worker/session
  APIs or SDK worker inspection expose `adapterId`, `routeKey`,
  `connectionId`, `transportNodeId`, or `deliveryQueueKey` as assigned-delivery
  target facts.
- Architecture guard fails if `DeliveryCommandGroup` reappears in main source.
- Architecture guard fails if `DeliveryCommand` carries route, adapter, node,
  connection, endpoint, queue, task shell metadata, or generic correlation
  fields.
- Guards allow listener-only endpoint evidence and adapter protocol resolution
  behind the final-hop boundary.
- Active docs state that selected-worker correctness is
  `deliveryBucketId + selectedWorkerId`, with `TaskDispatchContent` and
  `TaskDispatchExecutionContext` as typed execution payload/context. routeKey
  is transport-internal endpoint evidence in this roadmap and has a follow-up
  retirement decision.

## Verification Candidates

Compile:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,integrations/xa-mass-worker-pack,xa-mass-server,xa-mass-testing -am -DskipTests test-compile
```

Focused tests after BWD-1:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportRouteOwnerStoreTest,InMemoryTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter -Dtest=PollingWorkerAdapterTest test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/socket-adapter "-Dtest=SocketTaskDispatchChannelTest,SocketSessionManagerTest,SocketTransportServerTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter "-Dtest=WebSocketTaskDispatchChannelTest,WebSocketTransportFrameCodecTest,ServerSessionManagerShutdownTest,DispatcherInboundHandlerTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk "-Dtest=MassApplicationDistributedTransportTest,PullWorkerSessionTest" test
```

Focused tests after BWD-3:

```powershell
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=MassSdkTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
./mvnw -q -pl sdk/xa-mass-java-sdk -am "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
./mvnw -q -pl integrations/xa-mass-worker-pack -am "-Dtest=SampleWorkerWebSocketClientTest,WebSocketClientStarterTest,GeoLookupToolTest,ProbeWorkerPackTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
./mvnw -q -pl xa-mass-server -am "-Dtest=ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

If a new test class is named in slice acceptance, create it in the same slice
before using it in verification. Do not use
`surefire.failIfNoSpecifiedTests=false` to mask a missing new test class; it is
only allowed on `-am` commands so upstream reactor modules without matching
focused tests do not fail the verification command before the target module
runs.

Targeted residue scans:

```powershell
rg -n "DeliveryCommandGroup" transport/transport_runtime/src/main/java sdk/xa-mass-embedded-sdk/src/main/java -g "*.java"
```

```powershell
rg -n "binding\.adapterId\(|new DeliveryCommandGroup" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchDeliveryCommandSubmitter.java
```

```powershell
rg -n "activeOwnerForSelectedWorker\(" sdk/xa-mass-embedded-sdk/src/main/java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery -g "*.java"
```

```powershell
rg -n "WorkerDispatchRouteOwner" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportAssignedDeliverySubmitter.java
```

```powershell
rg -n "adapterWorkerKey|adapter:<.*>:worker:<.*>:owner" transport/transport_runtime/src/main/java transport/transport_runtime/src/test/java -g "*.java"
```

```powershell
rg -n "routeKey|connectionId|transportNodeId|adapterId|deliveryQueueKey|taskName|project|userId|correlation" transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskDispatchExecutionContext.java
```

```powershell
rg -n "adapterId|routeKey|transportNodeId|connectionId|deliveryQueueKey" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker xa-mass-server/src/main/java/com/xa/mass/api/model/worker -g "*.java"
```

```powershell
rg -n "sessionDiagnosticsExposeAdapterIdAndRouteKey|route-public|adapter-public|deliveryQueueKey|targetTransportNodeId" sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/sdk xa-mass-server/src/test/java/com/xa/mass/api/internal -g "*.java"
```

The public worker/API scans are reviewed with an allowlist for
`adapterNodeId`, `transportHint`, `workerGroupId`, `workerId`, and
`sessionToken`. They must not be satisfied by adding deprecated aliases or
wrapping internal ids under friendlier names.

## Roadmap Completion Criteria

- Engine/starter assigned delivery handoff exposes only `deliveryBucketId`,
  `selectedWorkerId`, `TaskDispatchContent`, `TaskDispatchExecutionContext`,
  command identity, and timing.
- Transport route-owner writes receive explicit `deliveryBucketId`.
- Transport route-owner store maintains bucket-worker producer lookup
  internally.
- Route-owner current-consumer semantics are deterministic: one current
  assigned-delivery consumer per `(deliveryBucketId, selectedWorkerId)`, latest
  claim wins, stale heartbeat/release cannot mutate replacements, and no
  protocol/session fallback occurs after target resolution failure.
- Missing or blank derived `deliveryBucketId` is an invalid delivery binding and
  is compensated exactly once without fallback to adapter, route, or default
  bucket.
- Submitter does not see full route-owner records or adapter protocol.
- Final-hop listener owns endpoint evidence resolution and adapter protocol
  dispatch.
- `DeliveryCommandGroup` is removed.
- `adapterId` is no longer a producer-facing assigned-delivery grouping key.
- Public worker APIs, public Java SDK worker sessions, worker pull DTOs, and
  default SDK worker inspection do not expose `adapterId`, `routeKey`,
  `connectionId`, `transportNodeId`, or `deliveryQueueKey` as delivery target
  facts.
- RouteKey and connectionId remain internal endpoint evidence and bounded
  diagnostics for this roadmap; full routeKey retirement is deferred to the
  internal-id convergence follow-up.
- Redis key manifest and guards reject adapter-worker producer lookup residue.
- All affected owner docs and proof registry rows match current code.

## Remaining Decisions

- Physical route consumer id: either add a stable `routeConsumerId` field or
  keep encoded `routeKey + connectionId` internally. This must not leak into
  delivery command contracts.
- Delivery lane storage token: use a typed lane key derived from
  `deliveryBucketId + targetTransportNodeId`; store-specific shard encoding is
  internal.
- Operator-only transport diagnostics: decide whether any route-owner internal
  ids may be exposed through a bounded operator detail surface. The default
  worker/session SDK and public worker API must remain free of those ids.
- Follow-up internal-id convergence: decide how to retire routeKey from
  route-owner records, result ingress envelopes, raw/manual route APIs,
  `TransportPacket`, and adapter wire/session internals after the
  engine/starter assigned-delivery boundary is clean.
