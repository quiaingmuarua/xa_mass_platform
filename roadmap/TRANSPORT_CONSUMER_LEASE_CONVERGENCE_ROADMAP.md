# Transport Consumer Lease Convergence Roadmap

Status: proposed direction document.

## Summary

Transport assigned delivery has already converged toward:

```text
DeliveryCommand(deliveryBucketId, selectedWorkerId, opaque payload, opaque correlation)
  -> bucket-derived handoff queue
  -> selected-worker consumer evidence
  -> adapter-local final-hop send
```

The remaining transport problem is that route-owner vocabulary and physical
Redis layout still preserve an older route-first model:

```text
routeKey -> connectionId -> TransportRouteOwnerRecord
```

That shape is no longer the assigned-delivery mainline. It forces
`RedisTransportRouteOwnerStore` to scan route-owner records when answering
`(deliveryBucketId, selectedWorkerId)` questions, and it keeps `routeKey`
looking like a delivery selector.

This roadmap replaces route-owner semantics with consumer-lease semantics:

```text
deliveryBucketId -> shard(selectedWorkerId) -> selectedWorkerId -> consumer lease
```

`routeKey` is demoted to optional endpoint/raw-route metadata. The assigned
task delivery truth is `deliveryBucketId + selectedWorkerId`.

This is not a worker-runtime roadmap. Worker-runtime reachability and slot
heartbeat remain separate truth, updated only through explicit presence
observations.

## Relationship To Existing Roadmaps

This roadmap is a focused successor/sibling to:

- `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md`
- `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md`

It owns only the route-owner to consumer-lease convergence:

- `TransportRouteOwnerStore`
- `TransportRouteOwnerRecord`
- `RedisTransportRouteOwnerStore`
- `InMemoryTransportRouteOwnerStore`
- route-owner read view and route-owner Redis key shape
- transport lease vs worker-runtime reachability boundary

It does not reopen opaque payload convergence, task result convergence,
worker-runtime candidate acquisition, or embedded SDK module layout.

## Current Problem

Current concepts:

```text
TransportRouteOwnerClaim(
  workerId,
  deliveryBucketId,
  adapterId,
  routeKey,
  connectionId,
  reason
)

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

Current Redis shape:

```text
{prefix}:route:{routeKey}:consumers
  HASH connectionId -> encoded TransportRouteOwnerRecord

{prefix}:deadline
  ZSET routeKey + connectionId -> leaseExpireAt
```

This shape is efficient for:

```text
routeKey -> active connections
```

It is inefficient for:

```text
deliveryBucketId + selectedWorkerId -> active consumer lease
```

As a result, Redis implementation currently has scan-heavy helpers:

```text
endpointForSelectedWorker(...)
isLatestSelectedWorkerConsumer(...)
nextUpdatedAt(...)
```

That is acceptable as temporary convergence residue, but not as production
runtime shape.

## Target Truth Lane

### Assigned Delivery Truth

Assigned delivery lookup is:

```text
deliveryBucketId + selectedWorkerId
```

Meaning:

- `deliveryBucketId` is the transport delivery bucket/domain.
- `selectedWorkerId` is the engine-selected execution identity.
- Together they locate the current selected-worker consumer lease.

The logical identity remains `(deliveryBucketId, selectedWorkerId)`, but Redis
physical storage must be bucket/shard partitioned to avoid one global key or one
large bucket key.

### Transport Consumer Lease Truth

Target type:

```java
record TransportConsumerLeaseRecord(
    String deliveryBucketId,
    String selectedWorkerId,
    String adapterId,
    String transportInstanceId,
    String connectionId,
    String consumerEvidenceId,
    String endpointRouteKey,
    long claimedAtEpochMillis,
    long generation
) {}
```

Important:

- This record should hold identity and relatively stable connection facts.
- It should not be rewritten on every heartbeat just to update timestamps.
- `endpointRouteKey` is optional metadata for diagnostics/raw side channels,
  not an assigned-delivery key.
- `generation` or `consumerEvidenceId` prevents stale heartbeat/disconnect from
  deleting or refreshing a newer connection.

### Timestamp Truth

High-frequency timestamp updates must not be embedded in a JSON/string record
that is rewritten on every heartbeat.

Target dynamic timestamp indexes:

```text
consumerLeaseDeadline
  ZSET selectedWorkerId -> consumerLeaseExpireAtEpochMillis

consumerHeartbeat
  ZSET selectedWorkerId -> lastTransportHeartbeatAtEpochMillis
```

If first-slice diagnostics do not need separate last heartbeat, it may be
derived from the deadline and configured lease duration. But if the timestamp is
stored, it must be in a separate per-bucket/per-shard index, not inside the
fixed lease record payload.

### Worker Runtime Truth

Worker-runtime owns worker reachability:

```text
workerId -> reachability state / worker heartbeat freshness
```

Transport consumer lease expiry means:

```text
selected-worker delivery endpoint is infeasible
```

Worker-runtime reachability expiry means:

```text
worker is not currently reachable for scheduling
```

The bridge is only an event observation:

```text
adapter/session heartbeat
  -> transport updates consumer lease
  -> transport emits WorkerSessionPresenceEvent
  -> worker-runtime updates reachability / slot heartbeat
```

Transport must not set worker-runtime reachability state directly. Worker
runtime must not write transport consumer lease deadlines directly.

Shared timeout configuration is allowed; shared truth is not.

## Target Redis Shape

Use `deliveryBucketId` as the first physical bucket and shard by
`selectedWorkerId`.

Recommended first target:

```text
{prefix}:bucket:{bucket}:shard:{shard}:consumer-meta
  HASH selectedWorkerId -> TransportConsumerLeaseRecord payload

{prefix}:bucket:{bucket}:shard:{shard}:consumer-deadlines
  ZSET selectedWorkerId -> consumerLeaseExpireAtEpochMillis

{prefix}:bucket:{bucket}:shard:{shard}:consumer-heartbeats
  ZSET selectedWorkerId -> lastTransportHeartbeatAtEpochMillis

{prefix}:bucket:{bucket}:shard:{shard}:consumer-ids
  HASH selectedWorkerId -> consumerEvidenceId

{prefix}:buckets
  SET deliveryBucketId

{prefix}:bucket:{bucket}:shards
  SET shard
```

`consumer-ids` may be omitted if `consumerEvidenceId` is cheap to read from
`consumer-meta` during heartbeat/release. Do not add it unless it removes a
real hot-path read/parse cost.

`buckets` and `bucket:{bucket}:shards` are bounded partition inventories for
maintenance and pruning. They must not contain per-worker entries and must not
be used as candidate or delivery lookup truth.

Shard rule:

```text
shard = stableHash(selectedWorkerId) % configuredShardCount
```

First slice default:

```text
configuredShardCount = 128
```

The value must be configurable before broad production load tests, but the first
implementation can hardcode the default behind a constructor/config field.

### Raw Route Diagnostics

If route diagnostics remain useful, keep them separate:

```text
{prefix}:raw-route:{endpointRouteKey}:connections
  HASH connectionId -> selectedWorkerId + adapterId + transportInstanceId + lastSeen
```

This raw-route index is not part of assigned task delivery. It may be omitted in
the first implementation if no production caller needs it.

## Target API Shape

Replace route-owner language with consumer-lease language.

Target write interface:

```java
interface TransportConsumerLeaseStore {
    TransportConsumerLeaseRecord claimConsumer(TransportConsumerLeaseClaim claim);
    TransportConsumerLeaseRecord refreshConsumer(TransportConsumerLeaseHeartbeat heartbeat);
    TransportConsumerLeaseRecord releaseConsumer(TransportConsumerLeaseRelease release);
    int pruneExpired();
}
```

Target read interface:

```java
interface TransportConsumerLeaseView {
    Optional<TransportConsumerLeaseRecord> currentConsumer(
        String deliveryBucketId,
        String selectedWorkerId
    );
}
```

Route/raw diagnostics, if retained, use a separate view:

```java
interface RawRouteEndpointView {
    List<RawRouteEndpointRecord> currentEndpoints(String endpointRouteKey);
}
```

Do not keep `WorkerDispatchRouteOwnerView` as the assigned-delivery read model.

## Mechanism And Policy Boundary

Mechanism:

- consumer lease claim / refresh / release
- stale connection protection
- lease deadline index
- bucket/shard key calculation
- pruning expired leases
- selected-worker consumer lookup
- Redis atomic multi-key update

Policy:

- lease duration
- shard count
- raw-route diagnostics retention
- heartbeat interval
- whether last heartbeat is materialized separately or derived

Lua rule:

- Lua may update a small fixed set of keys atomically.
- Lua must not embed dispatch strategy, worker selection, retry policy, or
  worker-runtime reachability policy.

## Non-Goals

- Do not change public worker session APIs in this roadmap.
- Do not change task dispatch payload or result payload shape.
- Do not make `routeKey` a scheduling or delivery selector.
- Do not move worker-runtime reachability into transport.
- Do not move transport consumer lease into worker-runtime.
- Do not add a global consumer index that becomes a large key.
- Do not add compatibility wrappers for old internal route-owner names after
  callers are migrated.
- Do not implement a broad transport id rename sweep. Keep this focused on
  route-owner to consumer-lease convergence.

## Do Not Start With

Do not start by deleting `TransportRouteOwnerStore` or `routeKey` from adapters.
First introduce the consumer-lease owner shape and migrate adapter session
writes to it.

Do not start by writing route diagnostics as the first key family. The mainline
key shape is bucket/shard selected-worker consumer lease.

Do not start by letting worker-runtime set `consumerLeaseExpireAtEpochMillis`.
Worker-runtime gets presence observations and owns its own reachability
deadline.

Do not start by replacing scan-heavy Redis code with a global
`selectedWorkerId -> lease` key. That solves scans by creating a future large
key and weakens `deliveryBucketId` partitioning.

## Phase TCL-0 - Inventory And Boundary Freeze

Goal: verify current callers and prevent hidden scope expansion.

Scope:

- Inventory production callers of:
  - `TransportRouteOwnerStore`
  - `WorkerDispatchRouteOwnerView`
  - `TransportRouteOwnerRecord`
  - `TransportRouteOwnerClaim`
  - `RedisTransportRouteOwnerStore`
  - `InMemoryTransportRouteOwnerStore`
- Classify each caller:
  - assigned-delivery mainline
  - adapter/session lease write
  - worker-runtime presence projection
  - raw/manual route side-channel
  - diagnostics/test only
- Confirm assigned delivery producer/listener does not read route-owner view.
- Confirm worker-runtime reachability is updated only through
  `WorkerPresenceIngress` / `WorkerSessionPresenceEvent`.

Acceptance:

- Current route-owner scan points are named explicitly.
- Current worker-runtime presence bridge is documented as event observation,
  not shared lease truth.
- No behavior change.

Verification:

```bash
rg -n "TransportRouteOwner|WorkerDispatchRouteOwnerView|endpointForSelectedWorker|targetForSelectedWorker" transport sdk xa-mass-server xa-mass-worker-runtime -g "*.java"
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,RedisTransportRouteOwnerStoreTest,InMemoryTransportRouteOwnerStoreTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Phase TCL-1 - Introduce Consumer Lease Contracts

Goal: replace route-owner vocabulary at the transport API boundary.

Scope:

- Add:
  - `TransportConsumerLeaseClaim`
  - `TransportConsumerLeaseHeartbeat`
  - `TransportConsumerLeaseRelease`
  - `TransportConsumerLeaseRecord`
  - `TransportConsumerLeaseStore`
  - `TransportConsumerLeaseView`
- Record fields must not include high-frequency heartbeat/deadline timestamps
  unless explicitly marked as read projection fields.
- Add a clear Javadoc boundary:
  - transport consumer lease is delivery feasibility evidence
  - worker-runtime reachability is separate scheduling evidence
- Keep old route-owner contracts only until production callers move in TCL-2.

Acceptance:

- New contracts compile.
- No production caller has been migrated yet unless the migration is small and
  keeps tests green.
- No public worker API exposes consumer lease ids.

Verification:

```bash
./mvnw -q -pl transport/transport_api -am test -DskipITs
```

## Phase TCL-2 - Migrate Adapter Session Writes

Goal: make polling/websocket/socket session managers write consumer leases
instead of route-owner records.

Scope:

- Replace adapter calls:
  - `claimRouteOwner(...)` -> `claimConsumer(...)`
  - `refreshHeartbeat(...)` -> `refreshConsumer(...)`
  - `releaseRouteOwner(...)` -> `releaseConsumer(...)`
- Map old fields:
  - `workerId` -> `selectedWorkerId`
  - `routeKey` -> `endpointRouteKey`
  - `connectionId` -> `connectionId`
  - `adapterId` remains adapter id / endpoint driver id for now
  - `transportInstanceId` remains transport runtime instance id
- Continue emitting `WorkerSessionPresenceEvent` to worker-runtime presence
  ingress.
- Do not let consumer lease refresh directly mutate worker-runtime state.

Acceptance:

- Adapter connect/heartbeat/disconnect tests pass.
- Worker presence tests still prove reachability through presence events.
- Selected-worker delivery still uses handoff-private consumer evidence.

Verification:

```bash
./mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am test -Dtest=PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=PullWorkerSessionTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Phase TCL-3 - Redis Bucket/Shard Consumer Lease Store

Goal: remove scan-heavy selected-worker lookup from Redis route-owner shape.

Scope:

- Implement Redis consumer lease store using bucket/shard keys:
  - `consumer-meta`
  - `consumer-deadlines`
  - `consumer-heartbeats`
- Use `deliveryBucketId` as primary partition.
- Use stable hash of `selectedWorkerId` for shard.
- Heartbeat refresh updates deadline/heartbeat indexes, not the full metadata
  record.
- Claim/reconnect updates metadata plus dynamic indexes atomically.
- Release removes metadata and dynamic indexes only if
  `connectionId + consumerEvidenceId` still match.
- Prune expired leases by scanning bounded deadline ranges within known
  bucket/shard partitions from the partition inventory.
- Avoid a global all-consumers key as mainline truth.

Acceptance:

- `currentConsumer(deliveryBucketId, selectedWorkerId)` is O(1) within a
  bucket/shard and does not call `readAllOwnerRecords`.
- `claim`, `refresh`, and `release` do not scan all routes or all workers.
- Stale heartbeat from old connection cannot refresh new connection.
- Stale disconnect from old connection cannot delete new connection.
- Heartbeat path does not rewrite the fixed metadata payload.

Verification:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=RedisTransportConsumerLeaseStoreTest,TransportRedisKeyspaceGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Phase TCL-4 - In-Memory Store Parity

Goal: keep memory and Redis runtime behavior aligned.

Scope:

- Update in-memory implementation to mirror logical contract:
  - `deliveryBucketId -> shard/worker -> lease`
  - connection generation / evidence id checks
  - separate heartbeat/deadline fields in internal state or clearly derived
    read projection
- Keep implementation simple; memory does not need Redis physical key mimicry,
  but it must satisfy the same contract tests.

Acceptance:

- Shared contract tests run against memory and Redis stores.
- Memory tests do not mask Redis-only scan or stale-connection bugs.

Verification:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConsumerLeaseStoreContractTest,InMemoryTransportConsumerLeaseStoreTest,RedisTransportConsumerLeaseStoreTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Phase TCL-5 - Route-Owner Residue Removal

Goal: delete old route-owner contracts and stale vocabulary after migration.

Scope:

- Delete or rename:
  - `TransportRouteOwnerStore`
  - `TransportRouteOwnerRecord`
  - `TransportRouteOwnerClaim`
  - `WorkerDispatchRouteOwnerView`
  - `WorkerDispatchRouteOwner`
  - `SelectedWorkerDeliveryTarget` if it remains tied to route-owner semantics
- Remove Redis methods:
  - `readAllOwnerRecords`
  - route-first selected-worker lookup
  - route-first latest-consumer generation scan
- Add source guards:
  - assigned delivery must not call raw route view
  - Redis consumer lease store must not contain `readAllOwnerRecords`
  - routeKey/endpointRouteKey must not appear in `DeliveryCommand`,
    `AdapterDispatchRequest`, `DeliveryCommandBatch`, or polling queue values
  - worker-runtime must not import transport consumer lease store
  - transport must not import worker-runtime reachability mutation owner

Acceptance:

- No production source imports old route-owner contracts.
- `routeKey` appears only as `endpointRouteKey` or adapter-local/raw-route
  vocabulary.
- Existing selected-worker delivery proofs still pass.

Verification:

```bash
rg -n "TransportRouteOwner|WorkerDispatchRouteOwner|SelectedWorkerDeliveryTarget|readAllOwnerRecords" transport sdk xa-mass-server xa-mass-worker-runtime -g "*.java"
./mvnw -q -pl transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportConsumerLeaseStoreContractTest,PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Phase TCL-6 - Owner Docs And Proof Registry Update

Goal: move current truth out of the roadmap after implementation.

Scope:

- Update:
  - `transport/AGENTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  - `xa-mass-worker-runtime/README.md`
  - `xa-mass-worker-runtime/CONTRACTS.md`
  - `doc/INFRA_TRUTH_LAYERS.md`
  - `doc/PROOF_REGISTRY.md`
- Archive this roadmap only after all residue scans pass.

Acceptance:

- Owner docs describe consumer lease, not route-owner, as current transport
  delivery feasibility evidence.
- Worker-runtime docs explicitly separate presence/reachability deadline from
  transport consumer lease deadline.
- Proof registry names the deterministic and distributed proof lanes.

## Proof Plan

Deterministic proof:

- Consumer lease claim/refresh/release contract.
- Stale heartbeat cannot refresh newer connection.
- Stale disconnect cannot delete newer connection.
- Expired lease pruned through bounded deadline scan.
- Heartbeat updates dynamic timestamp index, not metadata payload.
- Redis and memory stores share the same contract tests.

Transport integration proof:

- Polling worker connect/heartbeat/disconnect updates consumer lease and
  selected-worker handoff consumer evidence.
- WebSocket/socket reconnect replaces consumer lease without route-only fallback.
- Shared delivery bucket with multiple workers cannot cross-consume selected
  worker delivery.

Boundary guards:

- No assigned delivery call path reads route/raw endpoint view.
- No `routeKey` or `endpointRouteKey` in delivery command/handoff/pull value.
- No worker-runtime import of transport consumer lease store.
- No transport mutation of worker-runtime reachability except through
  `WorkerPresenceIngress`.
- No global all-worker/all-consumer Redis key used as mainline lookup.

Operational proof:

- Redis key scan after distributed transport proof shows only bucket/shard
  consumer lease keys and optional raw-route diagnostics keys.
- Cleanup proof shows expired leases leave no stale meta/deadline/heartbeat
  residue in the shard.

## Completion Criteria

This roadmap is complete only when:

- route-owner contracts are removed or fully demoted to raw-route diagnostics;
- assigned delivery uses consumer lease vocabulary and bucket/shard physical
  shape;
- Redis selected-worker consumer lookup has no full route/owner scan;
- high-frequency heartbeat/deadline timestamps are separate dynamic indexes;
- worker-runtime reachability remains separate and proof-covered;
- owner docs and proof registry describe the new current truth;
- residue scan shows old route-owner names do not remain in production
  mainline.
