# Transport Consumer Lease Convergence Roadmap

Status: mainline implemented; endpoint lease contract residue scan complete.
The remaining `TransportNodeRegistry.releaseRouteOwner(...)` vocabulary is out
of this roadmap and belongs to broader transport internal-id cleanup.

## Summary

Transport assigned delivery has already converged toward:

```text
DeliveryCommand(deliveryBucketId, selectedWorkerId, opaque payload, opaque correlation)
  -> bucket-derived handoff queue
  -> DeliveryCommandConsumerRegistry selected-worker evidence
  -> adapter-local final-hop send
```

That assigned-delivery consumer evidence already exists and remains the only
task-delivery feasibility truth:

```text
DeliveryCommandConsumerRegistry
  claimConsumer(deliveryBucketId, selectedWorkerId, queueConsumerKey, consumerEvidenceId, endpointDriverId, leaseExpireAt)
```

This roadmap does not introduce a second assigned-delivery routing store.

The remaining transport problem is narrower: the old route-owner endpoint/session
lease plane is still named and physically stored as route-first state:

```text
routeKey -> connectionId -> TransportRouteOwnerRecord
```

That shape keeps `routeKey` looking like a delivery selector and makes
`RedisTransportRouteOwnerStore` use full route-owner scans for selected-worker
diagnostics and currentness checks:

```text
endpointForSelectedWorker(...)
isLatestSelectedWorkerConsumer(...)
nextUpdatedAt(...)
readAllOwnerRecords()
```

Target direction:

```text
deliveryBucketId + workerId -> endpoint/session lease
```

`routeKey` is demoted to optional endpoint address metadata for raw-route
diagnostics and adapter-local protocols. Worker-runtime reachability remains a
separate truth. Assigned task delivery continues to use handoff-private
selected-worker consumer evidence, not endpoint lease lookup.

## Implementation Status - 2026-06-16

Implemented mainline:

- Added `TransportEndpointLeaseStore`, `TransportEndpointLeaseView`, and
  bucket-scoped endpoint lease records under `transport_api`.
- Added in-memory and Redis endpoint lease stores keyed by
  `deliveryBucketId + workerId`.
- Replaced adapter/session writes in polling, WebSocket, socket, SDK pull
  sessions, starter composition, and server profile assembly.
- Deleted old route-owner contracts and stores:
  `TransportRouteOwnerStore`, `TransportRouteOwnerRecord`,
  `TransportRouteOwnerClaim`, `WorkerDispatchRouteOwnerView`,
  `RedisTransportRouteOwnerStore`, and `InMemoryTransportRouteOwnerStore`.
- Updated transport Redis manifest to
  `xa:mass:transport:endpoint-lease:v1` with
  `bucket:<encodedDeliveryBucketId>:workers` and
  `bucket:<encodedDeliveryBucketId>:deadlines`.
- Proved assigned delivery still uses bucket-derived handoff and
  handoff-private selected-worker consumer evidence, not endpoint lease lookup.

Verified:

```bash
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,TransportRedisKeyspaceGuardTest,TransportConvergenceArchitectureGuardTest,RedisTransportNamespacesTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test "-Dtest=ServerSessionManagerShutdownTest,SocketSessionManagerTest,PollingWorkerAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=PullWorkerSessionTest,MassApplicationDistributedTransportTest,MassSdkTest,XaMassServerApplicationTransportRuntimeConfigTest,ServerMainSourceArchitectureGuardTest,ServerDurableLocalProfileContextTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am clean test-compile
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
./mvnw -q -pl integrations/xa-mass-scenario-launcher -am -DskipTests compile
```

## Relationship To Existing Roadmaps

This roadmap is a focused successor/sibling to:

- `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md`
- `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md`

It owns only the old route-owner endpoint/session lease convergence:

- `TransportRouteOwnerStore`
- `TransportRouteOwnerRecord`
- `TransportRouteOwnerClaim`
- `WorkerDispatchRouteOwnerView`
- `RedisTransportRouteOwnerStore`
- `InMemoryTransportRouteOwnerStore`
- SDK/starter route-owner store assembly surface
- route-owner Redis key shape and stale-session protection

It does not reopen:

- opaque payload convergence
- task result convergence
- worker-runtime candidate acquisition
- assigned-delivery handoff consumer evidence
- public worker session behavior
- broad transport internal-id cleanup beyond the endpoint lease facts touched
  here

This roadmap adopts the functional naming direction from
`TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md` for the endpoint lease
facts it touches:

```text
adapterId           -> endpointDriverId
transportInstanceId -> runtimeNodeId
connectionId        -> sessionHandle
routeKey            -> endpointAddress
```

The broader internal-id roadmap still owns remaining transport id vocabulary
outside this endpoint lease slice.

## Before Convergence Observations

Current route-owner write shape:

```java
TransportRouteOwnerClaim(
    workerId,
    deliveryBucketId,
    adapterId,
    routeKey,
    connectionId,
    reason
)
```

Current route-owner record shape:

```java
TransportRouteOwnerRecord(
    workerId,
    deliveryBucketId,
    adapterId,
    routeKey,
    lastHeartbeatEpochMillis,
    leaseExpireAtEpochMillis,
    transportInstanceId,
    connectionId,
    updatedAtEpochMillis
)
```

Current Redis route-owner shape:

```text
{prefix}:route:{routeKey}:consumers
  HASH connectionId -> encoded TransportRouteOwnerRecord

{prefix}:deadline
  ZSET routeKey + connectionId -> leaseExpireAt
```

Current delivery-command handoff already has its own selected-worker consumer
evidence:

```text
{delivery-command-prefix}:selected-worker-consumers:{deliveryQueueKey}
  HASH selectedWorkerId -> queueConsumerKey + consumerEvidenceId + endpointDriverId + leaseExpireAt

{delivery-command-prefix}:selected-worker-consumer-deadlines:{deliveryQueueKey}
  ZSET selectedWorkerId -> leaseExpireAt
```

That handoff evidence is the assigned task delivery lookup. Route-owner /
endpoint lease state must not be reintroduced into that hot path.

Current issue in the old route-owner plane:

- selected-worker diagnostic lookup is implemented by scanning all owner records
- claim and refresh currentness use `readAllOwnerRecords()`
- heartbeat rewrites the full encoded owner record
- SDK/starter still exposes `routeOwnerStoreFactory`
- active tests still use route-owner vocabulary for endpoint/session lease
  behavior

## Owner Review

Endpoint/session lease belongs to transport.

Assigned-delivery consumer evidence belongs to the delivery-command handoff
mechanism. Adapter/session ingress may write it, and producers may use it
through `TransportDeliveryCommandHandoff.offer(...)`, but endpoint lease storage
must not redefine or duplicate it.

Worker-runtime owns worker reachability and scheduling evidence. Transport may
emit session presence observations through `WorkerPresenceIngress`, but it must
not write worker-runtime reachability state directly.

SDK/starter owns runtime assembly and external configuration surfaces. If the
transport route-owner store contract is replaced, SDK/starter factory methods
must move in the same implementation slice; do not leave old and new internal
configuration tracks live.

## Boundary Decision

This roadmap replaces route-owner endpoint/session lease vocabulary with
endpoint lease vocabulary.

Target write contract:

```java
interface TransportEndpointLeaseStore {
    TransportEndpointLeaseConsumerEvidence claimEndpointLease(TransportEndpointLeaseClaim claim);
    Optional<TransportEndpointLeaseConsumerEvidence> refreshEndpointLease(
        TransportEndpointLeaseHeartbeat heartbeat
    );
    boolean releaseEndpointLease(TransportEndpointLeaseRelease release);
}
```

Optional bucket-scoped maintenance port, only if an operator or test caller is
inventoried:

```java
interface TransportEndpointLeaseMaintenance {
    int pruneExpired(String deliveryBucketId, int maxItems);
}
```

Target diagnostic/read contract:

```java
interface TransportEndpointLeaseView {
    Optional<TransportEndpointLeaseViewRecord> currentEndpointLease(
        String deliveryBucketId,
        String workerId
    );
}
```

Optional raw-route diagnostics, if retained:

```java
interface RawRouteEndpointView {
    List<RawRouteEndpointRecord> currentEndpoints(String endpointAddress);
}
```

Hard rules:

- `TransportEndpointLeaseView` is diagnostics and session maintenance only.
- Assigned task delivery must not call `TransportEndpointLeaseView`.
- `DeliveryCommandConsumerRegistry` remains the assigned-delivery consumer
  evidence owner.
- Endpoint lease refresh may update handoff consumer evidence only through the
  adapter/session ingress path, not through a route-owner projection bridge.
- No public worker/session API should expose endpoint lease ids, session
  handles, endpoint addresses, delivery queue keys, or raw route keys.

## Target Shape

Endpoint lease has two shapes:

- fixed metadata persisted in `endpoint-meta`
- minimal consumer evidence returned by claim/refresh operations

The split is intentional. Most diagnostic view results do not need timestamps
and must not grow into broad observability records. High-frequency
heartbeat/deadline values must not force a rewrite of the fixed endpoint
metadata payload. Only claim/refresh operations need to return a lease deadline,
because adapter/session ingress uses that deadline to update selected-worker
handoff consumer evidence.

Endpoint lease metadata payload:

```java
record TransportEndpointLeaseMetadata(
    String deliveryBucketId,
    String workerId,
    String endpointDriverId,
    String runtimeNodeId,
    String sessionHandle,
    String endpointLeaseId,
    String endpointAddress
) {}
```

Endpoint lease diagnostic view record:

```java
record TransportEndpointLeaseViewRecord(
    String deliveryBucketId,
    String workerId,
    String endpointDriverId,
    String runtimeNodeId,
    String sessionHandle,
    String endpointLeaseId,
    String endpointAddress
) {}
```

Endpoint lease consumer evidence returned by claim/refresh:

```java
record TransportEndpointLeaseConsumerEvidence(
    String deliveryBucketId,
    String workerId,
    String endpointDriverId,
    String endpointLeaseId,
    long leaseExpireAtEpochMillis
) {}
```

Notes:

- `workerId` is the worker execution identity attached to a session. At the
  delivery-command boundary, the assigned command calls the same value
  `selectedWorkerId`.
- `endpointDriverId` is the concrete adapter/final-hop driver identity.
- `runtimeNodeId` is the transport runtime process/node id.
- `sessionHandle` is adapter-private connection/session identity.
- `endpointLeaseId` prevents stale heartbeat/disconnect from mutating a newer
  session. It may equal `sessionHandle` only when that handle is unique per
  live connection; otherwise the store or adapter must generate an independent
  lease id.
- `endpointAddress` is optional raw-route/correlation metadata. It is not an
  assigned-delivery key.
- `TransportEndpointLeaseViewRecord` is a narrow diagnostic/session-maintenance
  view. It does not expose heartbeat or deadline timestamps by default.
- `TransportEndpointLeaseConsumerEvidence` is not a broad view. It is the
  minimum strategy output needed by adapter/session ingress to claim
  `DeliveryCommandConsumerRegistry` evidence.
- Endpoint lease store owns lease deadline calculation and refresh. Adapter and
  SDK session code must use `leaseExpireAtEpochMillis` from the returned
  consumer evidence when claiming `DeliveryCommandConsumerRegistry` evidence;
  they must not recompute `now + leaseMillis` as a second policy owner.

High-frequency timestamps must not live only inside the fixed endpoint metadata
payload. Heartbeat and deadline updates should touch dynamic indexes:

```text
endpoint-deadlines
  ZSET workerId -> endpointLeaseExpireAtEpochMillis

endpoint-heartbeats
  ZSET workerId -> lastEndpointHeartbeatAtEpochMillis
```

If a first slice does not need separate last heartbeat diagnostics, heartbeat
may be derived from deadline and lease duration. If stored, it must be outside
the fixed metadata payload.

## Target Redis Shape

`deliveryBucketId` is supplied by engine/starter/assembly as the assigned
delivery bucket. Transport must not mint, split, merge, or reinterpret that
bucket as a routing rule.

First target: keep endpoint lease storage directly scoped by the supplied
`deliveryBucketId` and index worker leases as fields inside that bucket. The
bucket value is opaque to transport. Redis keys must use only transport-safe
token encoding of the supplied bucket, not canonical bucket-id validation or
raw string path interpolation.

```text
{prefix}:delivery-bucket:<encodedDeliveryBucketId>:endpoint-meta
  HASH workerId -> TransportEndpointLeaseMetadata payload

{prefix}:delivery-bucket:<encodedDeliveryBucketId>:endpoint-deadlines
  ZSET workerId -> endpointLeaseExpireAtEpochMillis

{prefix}:delivery-bucket:<encodedDeliveryBucketId>:endpoint-heartbeats
  ZSET workerId -> lastEndpointHeartbeatAtEpochMillis
```

No global bucket inventory is part of the first target. Expired lease cleanup is
bucket-local and can run when a bucket is touched by claim, refresh, release,
diagnostic read, or operator maintenance. A future operator-owned cleanup lane
may pass explicit bucket ids to prune; transport should not create a hidden
global bucket truth just to make pruning convenient.

There is no unscoped `pruneExpired()` on the main store contract. An unscoped
method would either scan Redis keyspace or reintroduce a global bucket
inventory. The first implementation should prefer opportunistic cleanup inside
touched-bucket operations. If explicit pruning is needed, it must be
bucket-scoped with a bounded `maxItems`.

Optional raw-route diagnostics:

```text
{prefix}:raw-route:<encodedEndpointAddress>:sessions
  HASH sessionHandle -> workerId + deliveryBucketId + endpointDriverId + runtimeNodeId + lastSeen
```

Raw-route diagnostics are not assigned delivery truth and may be omitted unless
a production caller is inventoried.

Forbidden shapes:

- one global `workerId -> endpoint` key
- one global all-consumers/all-sessions hash
- transport-minted bucket ids, group ids, or route ids
- raw `{deliveryBucketId}` key interpolation that lets external bucket syntax
  define Redis hierarchy
- `routeKey`-first lookup as the selected-worker currentness check
- Redis keys that store `bucket:<deliveryBucketId>:worker:<workerId>:owner`
  outside the endpoint lease key shape

## Mechanism And Policy Boundary

Mechanism:

- endpoint lease claim / refresh / release
- stale session protection
- lease deadline index
- key calculation from externally supplied `deliveryBucketId`
- opportunistic or bucket-scoped pruning of expired endpoint leases
- diagnostic current endpoint lookup
- small Redis atomic multi-key updates

Policy:

- lease duration
- raw-route diagnostics retention
- heartbeat interval
- whether last heartbeat is materialized separately or derived

Lua rule:

- Lua may update a small fixed set of keys atomically.
- Lua must not embed dispatch strategy, worker selection, retry policy,
  assigned-delivery consumer selection, or worker-runtime reachability policy.

## Non-Goals

- Do not create a second assigned-delivery consumer truth.
- Do not change public worker session APIs.
- Do not change task dispatch payload or result payload shape.
- Do not make `endpointAddress` / `routeKey` a scheduling or delivery selector.
- Do not move worker-runtime reachability into transport.
- Do not move endpoint lease storage into worker-runtime.
- Do not add a global consumer index that becomes a large key.
- Do not add compatibility wrappers for old internal route-owner names after
  callers are migrated.
- Do not implement a broad transport id rename sweep outside endpoint/session
  lease facts.

## Do Not Start With

Do not start by migrating adapter/session writes before new memory and Redis
endpoint lease stores are implemented and injectable. That creates a compile
gap or invites a compatibility bridge.

Do not start by deleting `TransportRouteOwnerStore` from SDK/starter assembly
without replacing `routeOwnerStoreFactory` and all adapter/session callers in
the same slice.

Do not start by routing assigned delivery through endpoint lease lookup.
`DeliveryCommandConsumerRegistry` already owns selected-worker handoff
feasibility.

Do not start by writing raw-route diagnostics. The mainline key shape is
delivery-bucket-scoped endpoint lease using the externally supplied
`deliveryBucketId`.

Do not start by letting worker-runtime set endpoint lease deadlines.
Worker-runtime gets presence observations and owns its own reachability
deadline.

## Phase TCL-0 - Inventory And Boundary Freeze

Goal: verify current callers and freeze the owner split before changing
contracts.

Scope:

- Inventory production and test callers of:
  - `TransportRouteOwnerStore`
  - `WorkerDispatchRouteOwnerView`
  - `TransportRouteOwnerRecord`
  - `TransportRouteOwnerClaim`
  - `RedisTransportRouteOwnerStore`
  - `InMemoryTransportRouteOwnerStore`
  - `routeOwnerStoreFactory`
  - `DeliveryCommandConsumerRegistry`
  - `DeliveryCommandConsumerClaim`
  - `currentOwners(routeKey)`
  - `hasActiveRouteOwner(...)`
- Classify each caller:
  - adapter/session endpoint lease write
  - handoff selected-worker consumer evidence
  - worker-runtime presence observation
  - raw/manual route side-channel
  - diagnostics/test only
  - SDK/starter assembly surface
  - server profile / property / Spring assembly surface
- Confirm assigned delivery producer/listener does not read endpoint lease view.
- Confirm worker-runtime reachability is updated only through presence ingress
  observations.
- Decide raw-route diagnostic fate before route-owner deletion:
  - keep as `RawRouteEndpointView`,
  - move to adapter-local diagnostic view, or
  - delete if no production caller remains.

Acceptance:

- Current route-owner scan points are named explicitly.
- Current delivery-command handoff consumer evidence is named as the assigned
  delivery truth and must not be duplicated.
- SDK/starter route-owner configuration surfaces are included in the migration
  inventory.
- Server profile/property route-owner surfaces are included in the migration
  inventory.
- Raw-route diagnostics have an explicit keep/move/delete decision before
  TCL-2 starts deleting route-owner contracts.
- No behavior change.

Verification:

```bash
rg -n "TransportRouteOwner|WorkerDispatchRouteOwnerView|endpointForSelectedWorker|targetForSelectedWorker|routeOwnerStoreFactory|DeliveryCommandConsumer|currentOwners\\(|hasActiveRouteOwner\\(" transport sdk xa-mass-server xa-mass-worker-runtime -g "*.java"
./mvnw -q -pl transport/transport_api -DskipTests install
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportConvergenceArchitectureGuardTest,InMemoryTransportEndpointLeaseStoreContractTest,RedisTransportEndpointLeaseStoreContractTest,RedisTransportEndpointLeaseStoreTest
```

## Phase TCL-1 - Add Endpoint Lease Contracts And Stores

Goal: add the replacement endpoint lease contract and memory/Redis stores before
any production caller migration.

Scope:

- Add:
  - `TransportEndpointLeaseClaim`
  - `TransportEndpointLeaseHeartbeat`
  - `TransportEndpointLeaseRelease`
  - `TransportEndpointLeaseMetadata`
  - `TransportEndpointLeaseViewRecord`
  - `TransportEndpointLeaseConsumerEvidence`
  - `TransportEndpointLeaseStore`
  - `TransportEndpointLeaseView`
- Implement:
  - `InMemoryTransportEndpointLeaseStore`
  - `RedisTransportEndpointLeaseStore`
- Redis implementation uses delivery-bucket-scoped keys:
  - `endpoint-meta`
  - `endpoint-deadlines`
  - optional `endpoint-heartbeats`
- Claim/reconnect updates metadata plus dynamic indexes atomically.
- Refresh updates only dynamic deadline/heartbeat indexes after validating
  `sessionHandle + endpointLeaseId`.
- Release removes metadata and dynamic indexes only if
  `sessionHandle + endpointLeaseId` still match.
- Claim and refresh return minimal `TransportEndpointLeaseConsumerEvidence`
  assembled from metadata plus the current deadline index.
- Current read returns `TransportEndpointLeaseViewRecord` and does not expose
  heartbeat/deadline timestamps unless a separately approved strategy or
  operator diagnostic requires them.
- Expired lease cleanup is opportunistic inside touched-bucket claim, refresh,
  release, and read operations.
- If an explicit maintenance port is added, it is bucket-scoped:
  `pruneExpired(deliveryBucketId, maxItems)`.
- Do not wire production callers yet.

Acceptance:

- New contracts and stores compile.
- `currentEndpointLease(deliveryBucketId, workerId)` is O(1) against the
  delivery-bucket-scoped hash/zset and does not call `readAllOwnerRecords`.
- Claim, refresh, and release do not scan all routes or all workers.
- Stale heartbeat from old session cannot refresh a newer session.
- Stale disconnect from old session cannot delete a newer session.
- Heartbeat path does not rewrite the fixed metadata payload.
- Claim/refresh consumer evidence includes `leaseExpireAtEpochMillis`; callers
  do not compute selected-worker consumer evidence deadlines themselves.
- Diagnostic endpoint lease view records do not return timestamps by default.
- Redis keys encode opaque `deliveryBucketId` and `endpointAddress` tokens
  instead of interpolating raw external values into key hierarchy.
- No endpoint lease store contract exposes unscoped `pruneExpired()`.
- Existing route-owner stores remain the live production path for this slice.

Verification:

```bash
./mvnw -q -pl transport/transport_api -DskipTests install
./mvnw -q -pl transport/transport_runtime test -Dtest=InMemoryTransportEndpointLeaseStoreContractTest,RedisTransportEndpointLeaseStoreContractTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,TransportRedisKeyspaceGuardTest
```

## Phase TCL-2 - Migrate Adapter/Session Writes And Assembly

Goal: replace live route-owner writes with endpoint lease writes and remove the
old SDK/starter route-owner assembly surface in the same stable slice.

Scope:

- Replace adapter/session writes:
  - `claimRouteOwner(...)` -> `claimEndpointLease(...)`
  - `refreshHeartbeat(...)` -> `refreshEndpointLease(...)`
  - `releaseRouteOwner(...)` -> `releaseEndpointLease(...)`
- Map fields:
  - `workerId` -> `workerId`
  - `adapterId` -> `endpointDriverId`
  - `transportInstanceId` -> `runtimeNodeId`
  - `connectionId` -> `sessionHandle`
  - `connectionId` or generated unique lease id -> `endpointLeaseId`
  - `routeKey` -> optional `endpointAddress`
- Continue emitting `WorkerSessionPresenceEvent` to worker-runtime presence
  ingress.
- Continue claiming/releasing `DeliveryCommandConsumerRegistry` selected-worker
  evidence from adapter/session ingress. Do not route that through endpoint
  lease store.
- When endpoint lease claim/refresh succeeds, adapter/session ingress uses the
  returned `TransportEndpointLeaseConsumerEvidence.leaseExpireAtEpochMillis` for
  `DeliveryCommandConsumerClaim`. It may use `endpointLeaseId` as
  `consumerEvidenceId` and `endpointDriverId` as the consumer endpoint driver
  fact. It must not recompute the deadline from local time.
- Replace SDK/starter config:
  - `routeOwnerStoreFactory` becomes `endpointLeaseStoreFactory`, or is removed
    if no caller still needs custom endpoint lease storage.
  - `TransportRuntimeComposition` resolves endpoint lease store.
  - `TransportRuntimeRegistry`, `ResolvedPullWorkerTransport`, and
    `TransportAdapterBootstrapContext` carry endpoint lease store, not
    route-owner store.
  - `WorkerTransportRuntimeFactory`, `DefaultWorkerTransportRuntimeFactory`,
    `TransportBinding`, public `PullWorkerSession` constructors, and
    `MassSdk.TransportOptions` / `MassApplicationBuilder.TransportBuilder`
    route-owner factory methods are migrated or removed in the same slice.
- Replace server assembly/profile config:
  - `XaMassServerApplication` no longer imports `TransportRouteOwnerStore` or
    `RedisTransportRouteOwnerStore`.
  - `mass.transport.route-owner.*` properties are renamed or removed with the
    endpoint lease owner decision.
  - `application.yml`, `application-memory-local.yml`, and
    `application-durable-local.yml` no longer preserve route-owner store
    configuration as current truth.
  - Server profile tests prove memory-local and durable-local Spring context
    wiring after the config change.
- Delete old route-owner interfaces and records only after all production
  callers are migrated in this same slice.

Acceptance:

- No production source imports `TransportRouteOwnerStore`,
  `TransportRouteOwnerRecord`, or `TransportRouteOwnerClaim`.
- No production source exposes `routeOwnerStoreFactory`.
- Server source and profile YAML no longer expose route-owner store config as
  current transport assembly.
- Adapter connect/heartbeat/disconnect tests pass.
- Worker presence tests still prove reachability through presence events.
- Assigned delivery still uses `DeliveryCommandConsumerRegistry` evidence.
- Public worker/session APIs do not expose endpoint lease ids or session
  handles.
- No adapter/session caller computes endpoint lease deadlines outside
  `TransportEndpointLeaseStore`.

Verification:

```bash
rg -n "TransportRouteOwnerStore|TransportRouteOwnerRecord|TransportRouteOwnerClaim|routeOwnerStoreFactory|redisRouteOwnerStore|claimRouteOwner\\(|refreshHeartbeat\\(" transport sdk xa-mass-server xa-mass-worker-runtime -g "*.java"
rg -n "mass\\.transport\\.route-owner|route-owner:" xa-mass-server/src/main/resources sdk/xa-mass-embedded-sdk/README.md -g "*.yml" -g "*.md"
./mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest,MassSdkTest,XaMassServerApplicationTransportRuntimeConfigTest,ServerDurableLocalProfileContextTest -Dsurefire.failIfNoSpecifiedTests=false
```

The `releaseRouteOwner(String transportNodeId)` naming residue in
`TransportNodeRegistry` is not part of TCL-2. It belongs to the broader
transport internal-id cleanup unless it is separately renamed there.

## Phase TCL-3 - Raw Route Diagnostics Decision Execution

Goal: execute the raw-route diagnostics decision made in TCL-0 and keep it
separate from endpoint lease and assigned delivery.

Scope:

- If TCL-0 found production raw-route use, introduce `RawRouteEndpointView` with
  `endpointAddress` vocabulary and a separate optional Redis key family.
- If TCL-0 found no production use, remove route-owner diagnostic reads instead of
  preserving a second read model.
- If TCL-0 classified callers as adapter-local diagnostics, move them out of
  shared route-owner contracts before deleting those contracts.
- Tests that need endpoint lease currentness should use
  `TransportEndpointLeaseView`, not raw route view.

Acceptance:

- Assigned delivery code does not import raw route diagnostics.
- Endpoint lease tests do not assert route-key ownership as delivery
  correctness.
- Raw-route diagnostics are either absent, adapter-local, or explicitly
  separated behind `RawRouteEndpointView` and allowlisted.
- TCL-2 route-owner deletion is not blocked by newly discovered raw-route
  diagnostic callers.

Verification:

```bash
rg -n "currentOwners\\(|hasActiveRouteOwner\\(|RawRouteEndpointView|endpointAddress" transport sdk xa-mass-server -g "*.java"
./mvnw -q -pl transport/transport_api,transport/transport_runtime -DskipTests install
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportConvergenceArchitectureGuardTest,InMemoryTransportEndpointLeaseStoreContractTest,RedisTransportEndpointLeaseStoreContractTest
./mvnw -q -pl transport/socket-adapter,transport/websocket-adapter test -Dtest=SocketSessionManagerTest,ServerSessionManagerShutdownTest
```

## Phase TCL-4 - Route-Owner Residue Removal And Guards

Goal: remove stale route-owner vocabulary after the endpoint lease migration is
complete.

Scope:

- Delete or rename remaining stale types:
  - `WorkerDispatchRouteOwnerView`
  - `WorkerDispatchRouteOwner`
  - `RouteConsumerEndpoint`
  - `SelectedWorkerDeliveryTarget` if it remains tied to route-owner semantics
- Remove Redis methods:
  - `readAllOwnerRecords`
  - route-first selected-worker lookup
  - route-first latest-consumer generation scan
- Add architecture guards:
  - assigned delivery code must not import endpoint lease or raw route views
  - endpoint lease store must not contain `readAllOwnerRecords`
  - `DeliveryCommand`, `AdapterDispatchRequest`, `DeliveryCommandBatch`,
    handoff command references, and polling queue values must not contain
    `endpointAddress`, `routeKey`, `sessionHandle`, or endpoint lease records
  - worker-runtime must not import endpoint lease store
  - transport must not mutate worker-runtime reachability except through
    `WorkerPresenceIngress`
  - SDK/public worker APIs must not expose endpoint lease internals

Acceptance:

- No production source imports old route-owner contracts.
- `routeKey` appears only in archived docs, migration notes, or explicitly
  allowlisted raw-route adapter internals.
- Existing selected-worker delivery proofs still pass.
- Redis keyspace guard forbids old route-owner and worker-owner pointer keys.

Verification:

```bash
rg -n "TransportRouteOwner|WorkerDispatchRouteOwner|RouteConsumerEndpoint|SelectedWorkerDeliveryTarget|readAllOwnerRecords|bucket:.*worker:.*owner|route-owner" transport sdk xa-mass-server xa-mass-worker-runtime -g "*.java"
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -DskipTests install
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,InMemoryTransportEndpointLeaseStoreContractTest,RedisTransportEndpointLeaseStoreContractTest
./mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter test -Dtest=PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk test -Dtest=PullWorkerSessionTest,MassApplicationDistributedTransportTest
```

## Phase TCL-5 - Owner Docs And Proof Registry Update

Goal: move current truth out of the roadmap after implementation.

Scope:

- Update:
  - `transport/AGENTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  - `xa-mass-worker-runtime/README.md`
  - `xa-mass-worker-runtime/CONTRACTS.md`
  - `doc/INFRA_TRUTH_LAYERS.md`
  - `doc/PROOF_REGISTRY.md`
  - SDK README / integration boundary docs if the config surface changes
- Archive this roadmap only after residue scans pass.

Acceptance:

- Owner docs describe endpoint lease, not route-owner, as current transport
  session/endpoint feasibility evidence.
- Owner docs describe `DeliveryCommandConsumerRegistry` as the assigned-delivery
  consumer evidence owner.
- Worker-runtime docs explicitly separate presence/reachability deadline from
  transport endpoint lease deadline.
- Proof registry names deterministic, integration, and Redis keyspace proof
  lanes.

## Proof Plan

Deterministic proof:

- Endpoint lease claim/refresh/release contract.
- Stale heartbeat cannot refresh newer session.
- Stale disconnect cannot delete newer session.
- Expired lease pruned through bounded deadline scan.
- Heartbeat updates dynamic timestamp index, not metadata payload.
- Redis and memory stores share the same contract tests.

Transport integration proof:

- Polling worker connect/heartbeat/disconnect updates endpoint lease and
  selected-worker handoff consumer evidence through separate owners.
- WebSocket/socket reconnect replaces endpoint lease without route-only
  fallback.
- Shared delivery bucket with multiple workers cannot cross-consume selected
  worker delivery through the existing delivery-command handoff evidence.

Boundary guards:

- No assigned delivery call path reads endpoint lease or raw route view.
- No endpoint address / route key / session handle in delivery command,
  handoff command, final-hop request, or pull value.
- No worker-runtime import of endpoint lease store.
- No transport mutation of worker-runtime reachability except through
  `WorkerPresenceIngress`.
- No global all-worker/all-consumer Redis key used as mainline lookup.
- No SDK/public worker API exposes endpoint lease or raw route internals.

Operational proof:

- Redis key scan after distributed transport proof shows only
  delivery-bucket-scoped endpoint lease keys, delivery-command handoff keys,
  and optional raw-route diagnostics keys.
- Cleanup proof shows expired endpoint leases leave no stale
  meta/deadline/heartbeat residue in the touched delivery bucket.

## Completion Criteria

Current status: the endpoint lease mainline criteria below are satisfied by the
2026-06-16 implementation and proof runs listed above. The only remaining
`releaseRouteOwner` production symbol is `TransportNodeRegistry` node-presence
vocabulary and is explicitly owned by the broader transport internal-id cleanup,
not by this endpoint/session lease convergence.

This roadmap is complete only when:

- route-owner contracts are removed or fully replaced by endpoint lease
  contracts;
- assigned delivery still uses delivery-command handoff consumer evidence, not
  endpoint lease lookup;
- endpoint lease Redis storage uses the externally supplied `deliveryBucketId`
  as the physical key scope;
- selected-worker diagnostic/currentness lookup has no full route/owner scan;
- high-frequency heartbeat/deadline timestamps are separate dynamic indexes or
  explicitly derived without rewriting metadata;
- SDK/starter no longer exposes route-owner store factory/configuration;
- worker-runtime reachability remains separate and proof-covered;
- owner docs and proof registry describe the new current truth;
- residue scan shows old route-owner names do not remain in production
  mainline.
