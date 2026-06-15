# Transport Selected-Worker Delivery And Reachability Boundary Roadmap

Status: archived historical roadmap. Worker reachability ownership is now
captured by worker-runtime presence/reachability docs; assigned-delivery
transport ownership is superseded by the bucket-worker boundary in
`transport/TRANSPORT_BOUNDARY_BASELINE.md`.

Redis physical key compaction/bucketing remains deferred to a successor slice.

Predecessors:

- `doc/archive/transport/2026-06-12_TRANSPORT_DELIVERY_EXECUTOR_CONVERGENCE_ROADMAP.md`
- `doc/archive/transport/2026-06-12_TRANSPORT_ROUTE_DOMAIN_SELECTED_WORKER_DELIVERY_CONVERGENCE_ROADMAP.md`
- `doc/archive/transport/2026-06-12_TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `roadmap/RUNTIME_WORKER_SELECTION_RESIDUE_CONVERGENCE_ROADMAP.md`

## Purpose

Converge selected-worker delivery, SDK worker reachability, transport failure
feedback, and transport Redis key ownership into one proofable boundary.

The previous narrow framing only moved
`activeOwnerForSelectedWorker(adapterId, selectedWorkerId)` out of assignment
translation. That is not enough. Current code still has a public SDK inspection
path that reads route-owner evidence as worker reachability, and transport
still writes independent Redis key families whose shape must be justified with
strong proof.

The target boundary is:

```text
worker runtime
  owns worker lifecycle, schedulability, admission, capacity, worker resource
  metadata, and the reachability/lifecycle view consumed by scheduling and SDK
  inspection after the owner decision in this roadmap lands

engine
  consumes worker runtime views, selects a concrete worker, binds
  TaskDispatchBinding.workerId, and owns retry/compensation after transport
  reports delivery failure

starter assembly
  translates immutable engine assignment facts into delivery commands and wires
  transport-owned components, but does not read transport route-owner evidence
  as worker lifecycle or worker reachability truth

transport
  owns final-hop delivery execution, route-owner connection leases, transport
  node evidence, dispatch outcomes, delivery-failure emission, and only the
  Redis runtime keys that have a manifest-backed proof
```

Missing route-owner evidence, stale connection evidence, or offline transport
node evidence is a transport delivery failure. It is not worker offline, worker
unschedulable, or a second worker selection step.

## Original Pre-Implementation Code Observations

These observations captured the 2026-06-12 starting point for this roadmap.
They are retained as historical input, not as current implementation truth after
`WORKER_RUNTIME_TRANSPORT_SESSION_PRESENCE_INGRESS_ROADMAP.md` landed.

- `TaskDispatchDeliveryCommandSubmitter` is a starter-owned
  `TaskDispatchBatchListener` that translates `TaskDispatchContext +
  TaskDispatchBinding` into `DeliveryCommand`.
- The same class directly injected `WorkerDispatchRouteOwnerView` and
  `TransportNodeRegistry`, called
  `activeOwnerForSelectedWorker(adapterId, selectedWorkerId)`, filtered by node
  usability, grouped commands into `DeliveryCommandBatch`, and called
  `TransportDeliveryFailureHandler` for retryable outcomes.
- `DeliveryCommandBatch` required `targetTransportNodeId`; Redis
  split-runtime handoff queues are keyed by shared `deliveryQueueKey` and target
  transport node lane. Therefore, distributed handoff still needed a
  transport owner resolution step before writing a node-local batch.
- `MassSdkApplication#isWorkerReachable(...)` formerly read
  `delegate.getWorkerRouteOwnerView()` and called
  `activeOwnerForSelectedWorker(adapterId, workerId)`.
- `MassApplication` formerly kept `workerRouteOwnerView`, required the
  route-owner store to implement `WorkerDispatchRouteOwnerView`, and exposes
  `getWorkerRouteOwnerView()`.
- `WorkerHeartbeatProjectionListener` refreshed heartbeat timestamps
  only. Its tests asserted that worker online/heartbeat/offline events do
  not update worker model status.
- `WorkerReachabilityView` lived under `xa-mass-worker-runtime`, but
  its javadoc still describes a transport-owned reachability read seam consumed
  by engine matching. That wording and its assembly must be reconciled before
  SDK reachability can safely stop reading route-owner evidence.
- Redis route-owner state included route consumer hashes, deadline
  index, and the derived
  `adapter:<adapterId>:worker:<workerId>:owner` pointer.
- `doc/PROOF_REGISTRY.md` already forbids `workers`, `owner-shards`,
  `worker-routes:*`, `worker-route:*`, `routes`, `route-presence:*`, and worker
  runtime/admission facts in transport Redis truth.

## Implementation Summary

Implemented on 2026-06-12:

- `WorkerHeartbeatProjectionListener` projected explicit worker
  online/heartbeat/offline events into worker runtime status/heartbeat
  evidence. Transport route-owner leases were not used for worker lifecycle.
  This interim projection was replaced on 2026-06-13 by
  `WorkerPresenceIngress` / `WorkerRuntimePresenceIngress`, which writes
  worker-runtime presence/reachability evidence and registry-owned slot
  heartbeat freshness without mutating worker resource status or dispatch
  gates.
- `MassSdkApplication#isWorkerReachable(...)` and
  `listReachableWorkerIds()` now read worker runtime status only. SDK worker
  inspection no longer calls `WorkerDispatchRouteOwnerView` or
  `activeOwnerForSelectedWorker(...)`.
- `MassApplication#getWorkerRouteOwnerView()` was removed. `MassApplication`
  still wires `WorkerDispatchRouteOwnerView` internally into transport-owned
  delivery components.
- `TaskDispatchDeliveryCommandSubmitter` now translates assignment bindings
  into `DeliveryCommand` records only. It does not import route-owner lookup or
  transport-node registry.
- `TransportAssignedDeliverySubmitter` owns final-hop selected-worker owner
  resolution, transport-node verification, delivery-command batch grouping,
  handoff offer, and producer-side retryable delivery-failure emission.
- `TransportDeliveryCommandListenerTest` proves consumer-side adapter
  unavailable failure emission remains owned by the transport consumer.
- The transport Redis key manifest is guarded by
  `TransportRedisKeyspaceGuardTest`; route-owner, delivery,
  delivery-command, delivery-failure, and node Redis proof tests were run
  against the local Redis URI.

## Owner Review

Worker runtime owns:

- worker lifecycle and schedulability,
- worker group membership and scheduling views,
- worker resource metadata used by SDK inspection,
- capacity, admission, reservation, dispatch gates, and related runtime
  evidence.

Engine owns:

- task scheduling orchestration,
- worker selection from worker-runtime views,
- dispatch binding,
- task lifecycle retry/compensation after transport reports delivery failure.

Transport owns:

- route-owner connection leases,
- transport node availability evidence,
- final-hop delivery owner resolution,
- producer-side dispatch outcomes for missing owner, stale connection, node
  unavailable, and handoff backpressure,
- consumer-side dispatch outcomes for adapter unavailable and adapter dispatch
  failures,
- transport runtime Redis keys whose owner, shape, lifecycle, and proof are
  explicitly documented.

SDK/starter owns:

- public SDK operation shape,
- runtime assembly and wiring,
- assignment-to-command translation,
- worker inspection semantics that must be backed by worker runtime, not
  transport route-owner leases.

## Boundary Decisions

### SDK Reachability Is Not Route-Owner Truth

`MassSdkApplication#isWorkerReachable(...)` and
`listReachableWorkerIds()` must not read `WorkerDispatchRouteOwnerView` or call
`activeOwnerForSelectedWorker(...)`.

If the SDK keeps the name `isWorkerReachable`, it must be backed by
worker-runtime reachability/presence evidence. A direct route-owner read is not
an acceptable fallback.

### Worker Reachability Owner Must Be Settled Before SDK Cutover

This was resolved by
`WORKER_RUNTIME_TRANSPORT_SESSION_PRESENCE_INGRESS_ROADMAP.md`: the selected
path is worker-runtime presence/reachability projection through
`WorkerPresenceIngress` / `WorkerRuntimePresenceIngress`, consumed by SDK
inspection and engine scheduling. Transport delivery feasibility remains a
separate route-owner concern.

Neither the selected path nor any future diagnostic path may use transport
route-owner lease evidence as worker lifecycle truth.

### Route-Owner Lookup Belongs Inside Transport Delivery Execution

`activeOwnerForSelectedWorker(adapterId, selectedWorkerId)` may remain as a
transport-internal lookup, but starter/engine-facing assignment translation and
SDK worker inspection must not call it directly.

Transport may resolve delivery evidence for the selected worker, may fail
delivery for that worker, and may emit a delivery-failure event. Transport must
not choose another worker or mutate worker lifecycle truth.

### Failure Emission Has One Owner Per Failure Site

The component that observes a retryable transport delivery failure is the only
component allowed to emit the corresponding delivery-failure event for that
failure site.

Callers may observe returned `DispatchOutcome` values for metrics or tests, but
must not call compensation a second time after the transport delivery component
has emitted a failure event.

### Transport Redis Keys Require A Strong Proof Gate

Transport may write Redis keys independently only when the key family passes the
Redis key proof bar in this roadmap. A transport Redis key is not accepted
because it is convenient, because it makes a lookup O(1), or because a unit test
passes against an in-memory store.

Every retained transport Redis key family must prove:

- logical owner,
- writer set,
- reader set,
- truth versus derived status,
- lifecycle and cleanup rule,
- cardinality formula,
- stale/replacement behavior,
- atomicity or tolerated drift model,
- forbidden fact boundary,
- actual Redis behavior,
- source guard coverage,
- manifest/proof-registry coverage.

## Redis Key Proof Bar

Before adding, retaining, or changing a transport Redis key family, the slice
must update a manifest table and tests that cover this minimum shape:

| Field | Required Answer |
| --- | --- |
| Key family | Exact namespace and pattern, including encoded tokens |
| Owner | Transport component that owns writes and cleanup |
| Truth class | Primary truth, derived pointer, queue, index, stats, or diagnostics |
| Writer | Exact production writer methods/classes |
| Reader | Exact production reader methods/classes |
| Forbidden facts | Worker lifecycle, capacity, reservation, admission, event binding, group slots, task lifecycle, or control-plane data that must not appear |
| Cardinality | Formula such as per route consumer, per selected worker and adapter, per target transport node lane |
| Lifetime | Lease, queue retention, prune, release, shutdown, or bounded stats lifecycle |
| Replacement rule | How stale heartbeat/release cannot revoke a newer consumer |
| Failure rule | What happens when the key is missing, stale, corrupt, or points to missing owner evidence |
| Atomicity model | Lua/transaction/multi-command drift tolerance and repair behavior |
| Proof | Actual Redis tests, source guard, manifest scan, and optional local probe |

Minimum proof for a retained route-owner Redis key family:

- actual Redis test proves writes only the retained families,
- actual Redis test proves forbidden families do not exist,
- stale heartbeat cannot move `adapter + selectedWorkerId` back to an old
  consumer,
- old release cannot remove a newer consumer pointer,
- expired owner evidence is not dispatchable,
- missing/corrupt pointer produces transport delivery failure, not worker
  offline,
- source guard blocks deprecated key family strings in non-test runtime source,
- proof registry names the key family and states why it is transport-owned.

## Target Shape

### SDK / Worker Inspection

SDK worker inspection should read worker runtime/resource evidence only.

Transport-specific connection evidence may be available through a separate
transport diagnostics surface, but it must not be named or consumed as worker
lifecycle truth.

### Assignment Translation

Assignment translation should produce `DeliveryCommand` values from:

- `TaskDispatchContext`,
- `TaskDispatchBinding`,
- worker runtime adapter metadata already bound to the selected worker,
- opaque route metadata when required by current packet shape.

It should not resolve route-owner leases or transport-node liveness.

### Transport Delivery Submitter

Introduce or converge to a transport-owned component with a narrow shape such
as:

```java
interface TransportAssignedDeliverySubmitter {
    List<DispatchOutcome> submit(List<DeliveryCommand> assignedCommands);
}
```

or an equivalent concrete runtime class under the transport delivery package.

Responsibilities:

- resolve `adapterId + selectedWorkerId` through route-owner evidence,
- validate transport-node usability,
- enrich or copy commands with `targetTransportNodeId` / route-owner evidence
  needed by the existing handoff batch,
- write resolved batches to `TransportDeliveryCommandHandoff`,
- emit retryable delivery-failure events exactly once for its failure sites,
- return outcomes only as observation, not as a second compensation trigger.

Non-responsibilities:

- no worker runtime reads,
- no worker lifecycle mutation,
- no worker selection,
- no task lifecycle mutation,
- no routeKey decoding or routeKey-as-worker-routing behavior.

## Do Not Start With

Do not start by compacting Redis keys into buckets. Bucket/hash compaction is
important, but it is a physical shape decision that should follow the proof bar
and logical owner cleanup.

Do not start by deleting the
`adapter:<adapterId>:worker:<workerId>:owner` key family without replacing the
logical selected-worker-to-owner lookup needed by the current split-runtime
handoff.

Do not start with a broad rename-only pass. Move the call boundary first, then
rename old symbols when their call sites have a new owner.

Do not turn missing route-owner evidence into worker offline or worker
unschedulable semantics. It is a transport delivery failure.

Do not add compatibility aliases for removed internal names or keep old/new
Redis key families live in parallel.

## Non-Goals

- No Redis bucket/hash compaction in the first implementation slice.
- No unscoped change to worker-runtime lifecycle or scheduling evidence
  semantics. Any reachability/lifecycle projection change must happen only
  inside TSDR-1 and must update its proof and docs in the same slice.
- No worker-runtime dependency from transport runtime.
- No engine-side worker re-selection on missing transport owner.
- No routeKey semantic upgrade; routeKey remains opaque connection metadata.
- No public API expansion to expose transport route-owner as worker truth.

## TSDR-0: Caller, Failure, And Redis Key Inventory

Goal:

Create the executable inventory required before changing behavior.

Scope:

- Inventory production imports/calls of:
  - `WorkerDispatchRouteOwnerView`,
  - `activeOwnerForSelectedWorker(...)`,
  - `MassApplication#getWorkerRouteOwnerView()`,
  - `MassSdkApplication#isWorkerReachable(...)`,
  - `WorkerReachabilityView`,
  - `WorkerPresenceIngress` / `WorkerRuntimePresenceIngress`,
  - `TransportNodeRegistry` in assignment-to-delivery code,
  - `DeliveryCommandBatch` creation,
  - `TransportDeliveryFailureHandler` emitters.
- Create a transport Redis key manifest covering route-owner, delivery,
  delivery-command, nodes, result-inbox, and delivery-failure key families.
- Classify each key family as primary truth, derived pointer, queue, index,
  stats, or diagnostics.
- Record current proof coverage and proof gaps for each key family.

Acceptance:

- Inventory identifies assignment, SDK inspection, transport runtime, and test
  callers separately.
- Inventory marks SDK route-owner reachability lookup as boundary residue.
- Manifest states writer, reader, lifecycle, cardinality, and proof gap for
  every retained transport Redis key family.
- No code behavior changes.

## TSDR-1: Worker Reachability Owner Decision And Projection

Goal:

Settle the worker reachability owner before changing SDK worker inspection.

Scope:

- Decide whether `isWorkerReachable(...)` remains a real reachability API or is
  renamed/downgraded to worker-resource availability.
- If it remains reachability, implement a worker-runtime-owned
  reachability/lifecycle projection from explicit worker system events or an
  equivalent worker-runtime input.
- If it is downgraded, update SDK API wording, tests, and E2E expectations so
  online/offline events are not expected to drive reachability.
- Reconcile `WorkerReachabilityView` wording and assembly so it is not described
  as transport-owned route-owner truth when consumed by engine scheduling.
- Keep transport route-owner lease evidence out of worker lifecycle truth.

Acceptance:

- The slice records one chosen semantic path and removes the old ambiguous
  "transport-owned worker reachability" wording from active code/docs.
- If worker-runtime projection is chosen:
  - `workerOnline`, `workerHeartbeat`, and `workerOffline` behavior is covered
    by worker-runtime or starter integration tests, or is retargeted to
    session-presence ingress through `WorkerPresenceIngress`,
  - stale `WorkerHeartbeatProjectionListenerTest` references are removed and
    replaced by `WorkerRuntimePresenceIngressTest` /
    `WorkerRuntimeSelectionIntegrationTest`,
  - engine scheduling and SDK inspection consume the same worker-runtime-owned
    reachability/lifecycle view.
- If SDK semantic downgrade is chosen:
  - public SDK docs and tests stop calling the result worker reachability,
  - any transport delivery feasibility view is separate diagnostics,
  - engine scheduling expectations are updated to the chosen reachability owner.
- No transport route-owner lookup is used to satisfy this acceptance.

## TSDR-2: SDK Worker Reachability Convergence

Goal:

Remove route-owner evidence from SDK worker inspection.

Scope:

- Remove `WorkerDispatchRouteOwnerView` and
  `activeOwnerForSelectedWorker(...)` usage from `MassSdkApplication`.
- Remove or narrow `MassApplication#getWorkerRouteOwnerView()` so SDK
  inspection cannot read transport route-owner evidence directly.
- Implement `isWorkerReachable(...)` / `listReachableWorkerIds()` according to
  the semantic path chosen in TSDR-1.
- If transport delivery diagnostics remain necessary, split them into a
  separate non-worker-lifecycle surface.
- Update SDK README and worker inspection javadocs.

Acceptance:

- `MassSdkApplication` main source does not import
  `WorkerDispatchRouteOwnerView`.
- SDK worker inspection tests do not mock route-owner view to prove worker
  reachability.
- SDK docs do not describe route-owner leases as worker reachability truth.
- SDK tests prove the TSDR-1 chosen semantic path.
- If a transport diagnostic remains, it is named as transport delivery
  feasibility/diagnostics, not worker lifecycle.

## TSDR-3: Transport-Owned Assigned Delivery Submitter

Goal:

Move final-hop owner resolution and target-node grouping from starter
assignment translation into a transport-owned delivery component.

Scope:

- Add a transport delivery submitter/resolver class in the transport delivery
  runtime package, or an equivalent narrow runtime-owned component.
- Move `WorkerDispatchRouteOwnerView` and `TransportNodeRegistry` use from
  `TaskDispatchDeliveryCommandSubmitter` into that component.
- Preserve current `DeliveryCommandBatch` and Redis lane shape.
- Preserve selected-worker immutability.
- Make failure emission ownership explicit.

Acceptance:

- `TaskDispatchDeliveryCommandSubmitter` no longer imports
  `WorkerDispatchRouteOwnerView`, `WorkerDispatchRouteOwner`, or
  `TransportNodeRegistry`.
- Assignment translation creates commands from `TaskDispatchBinding.workerId`
  as `selectedWorkerId` without interpreting missing route-owner evidence.
- Transport delivery submitter is the only production owner lookup path for
  assigned task delivery.
- Producer-side missing owner, stale owner, node unavailable, and handoff
  backpressure each produce at most one retryable delivery-failure event.
- Consumer-side adapter unavailable remains owned by
  `TransportDeliveryCommandListener` and is proved in TSDR-7.

## TSDR-4: Direct Submitter Proof

Goal:

Prove the new transport submitter behavior directly instead of relying only on
assembly tests.

Scope:

- Add `TransportAssignedDeliverySubmitterTest` or equivalent direct tests.
- Cover:
  - missing owner,
  - stale pointer,
  - node unavailable,
  - handoff backpressure,
  - batch grouping by `deliveryQueueKey + targetTransportNodeId`,
  - selected worker is not replaced,
  - failure event emitted exactly once per retryable failure.

Acceptance:

- The direct test class/file exists and is part of the Maven test source set.
- Direct tests fail if caller-side compensation is reintroduced.
- Direct tests fail if selected worker can be replaced by routeKey or another
  route consumer.
- Direct tests fail if missing owner is asserted as worker offline.
- The focused verification command for this test does not use
  `-Dsurefire.failIfNoSpecifiedTests=false`.

## TSDR-5: Redis Route-Owner Proof Hardening

Goal:

Make the retained route-owner Redis key families defensible at scale and
owner-clean before physical compaction.

Scope:

- Update actual Redis tests for:
  - retained route consumer hash,
  - deadline index,
  - derived `adapter + worker -> owner` pointer,
  - absent forbidden key families,
  - stale heartbeat/release replacement safety,
  - corrupt/missing pointer behavior.
- Add or update source guards for deprecated Redis key families and forbidden
  worker-runtime/admission facts in transport Redis runtime code.
- Add a manifest/proof test that ties key families to the Redis key manifest.
- Keep bucket/hash compaction deferred.

Acceptance:

- Actual Redis proof covers every retained route-owner key family.
- Source guard blocks forbidden route-owner families and worker-runtime facts.
- Manifest/proof registry explains why the derived adapter-worker pointer is
  transport delivery evidence only.
- No test relies on `worker-route`, `workers`, `routes`, `worker-routes:*`, or
  `route-presence:*` as current truth.

## TSDR-6: Delivery/Inbox Redis Key Proof Hardening

Goal:

Apply the same proof bar to transport delivery, delivery-command, result-inbox,
delivery-failure, and node key families.

Scope:

- Extend the manifest to cover:
  - delivery queues and selected-worker worker-index keys,
  - delivery-command node-local lanes,
  - transport node registry keys,
  - result-inbox entries,
  - delivery-failure entries,
  - bounded stats keys.
- Verify writer/reader/cleanup behavior with actual Redis tests where Redis is
  the production implementation.
- Guard against task lifecycle, worker lifecycle, admission, capacity, or
  control-plane data leaking into transport Redis keys.

Acceptance:

- Every transport Redis namespace in `xa:mass:transport:*` is represented in
  the manifest or explicitly classified as removed residue.
- Redis tests prove key creation and cleanup for each production Redis-backed
  family.
- Guard fails on new unmanifested `xa:mass:transport` key families in
  production source.

## TSDR-7: Failure Feedback And Engine Compensation Proof

Goal:

Prove transport failure feedback reaches engine compensation exactly once
without moving task lifecycle ownership into transport.

Scope:

- Add/update integration tests for producer-side missing owner, unavailable
  node, handoff backpressure, and consumer-side adapter unavailable.
- Assert the engine compensation port receives one
  `TaskDispatchDeliveryFailure` per retryable transport failure.
- Assert worker runtime lifecycle is not mutated by transport delivery failure.
- Assert transport does not call retry/release/task lifecycle APIs directly.

Acceptance:

- Failure feedback produces retry/compensation through engine-owned ports.
- Adapter unavailable is proved as a consumer-side
  `TransportDeliveryCommandListener` failure, not as a producer-side owner
  resolver failure.
- Transport runtime code has no direct dependency on engine task lifecycle
  mutation APIs.
- Duplicate failure emission is covered by tests.

## TSDR-8: Documentation And Guard Convergence

Goal:

Move the final boundary into active owner docs and prevent regression.

Scope:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `sdk/xa-mass-embedded-sdk/README.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Add architecture/source guards for:
  - SDK worker inspection cannot import route-owner lookup,
  - assignment translator cannot import route-owner lookup or node registry,
  - only transport-owned resolver/submitter may call
    `activeOwnerForSelectedWorker(...)`,
  - production transport Redis key families must be manifest-backed.

Acceptance:

- Active docs state:
  - engine selects workers,
  - worker runtime owns lifecycle/scheduling availability,
  - SDK worker inspection reads worker runtime truth,
  - transport delivery submitter resolves final-hop owner and emits transport
    delivery failures,
  - route-owner lease is not worker lifecycle truth,
  - transport Redis keys require manifest-backed proof.
- No active doc describes route-owner lookup as engine lifecycle pre-check or
  SDK worker reachability truth.

## Deferred Phase: Physical Redis Key Compaction

This roadmap intentionally does not make bucket/hash compaction the first
implementation slice.

After logical owners, SDK reachability, failure feedback, and Redis proof gates
are stable, a successor slice or roadmap may compact
`adapter:<adapterId>:worker:<workerId>:owner` from one key per worker/adapter
into a bucketed or hashed physical shape. That successor must preserve the same
logical contract:

```text
adapterId + selectedWorkerId -> current route consumer pointer
```

It must also update the Redis key manifest, actual Redis proof, probe tooling,
and source guards in the same slice.

## Verification Candidates

Focused implementation verification:

```powershell
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-worker-runtime,xa-mass-engine -am -DskipTests install
./mvnw -q -pl transport/transport_runtime test "-Dtest=TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest"
./mvnw -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=WorkerRuntimePresenceIngressTest,WorkerRuntimeSelectionIntegrationTest,MassSdkTest#sdkWorkerReachableReadsWorkerRuntimeStatus+sdkWorkerReachableTreatsUnavailableWorkerRuntimeStatusAsOffline"
./mvnw -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassApplicationDistributedTransportTest"
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

Direct submitter proof after TSDR-4 lands:

```powershell
./mvnw -q -pl transport/transport_runtime test "-Dtest=TransportAssignedDeliverySubmitterTest"
```

Redis proof candidates:

```powershell
./mvnw -q -pl transport/transport_runtime test "-Dtest=RedisTransportRouteOwnerStoreTest,RedisTransportDeliveryStoreTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportDeliveryFailureChannelTest,TransportNodeRegistryTest"
```

Residue scan candidates:

```powershell
rg -n "activeOwnerForSelectedWorker|WorkerDispatchRouteOwnerView|getWorkerRouteOwnerView|WorkerReachabilityView|WorkerPresenceIngress|engine/starter assembly resolves|delivery feasibility before handoff|worker offline|worker reachability" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-worker-runtime/src/main/java sdk/xa-mass-embedded-sdk/README.md transport/TRANSPORT_BOUNDARY_BASELINE.md doc/PROOF_REGISTRY.md
rg -n "WorkerDispatchRouteOwnerView|TransportNodeRegistry" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchDeliveryCommandSubmitter.java
rg -n "xa:mass:transport|worker-route|worker-routes|route-presence|owner-shards|:workers|:routes" transport/transport_runtime/src/main/java sdk/xa-mass-embedded-sdk/src/main/java doc/PROOF_REGISTRY.md transport/TRANSPORT_BOUNDARY_BASELINE.md
```

After a probe tool exists:

```powershell
python tools/probe_transport_redis_keys.py --redis-uri redis://127.0.0.1:6379/0 --namespace xa:mass:transport
```

## Roadmap Completion Criteria

- Worker reachability/lifecycle owner is settled, implemented, documented, and
  no longer described as transport route-owner truth.
- SDK worker inspection no longer reads transport route-owner evidence.
- Assignment translation code no longer owns direct route-owner lookup.
- Transport-owned delivery submission owns final-hop owner resolution and
  target transport node grouping.
- Producer-side missing/stale owner, node unavailable, and handoff backpressure
  are expressed only as transport delivery failures and emitted exactly once.
- Consumer-side adapter unavailable is expressed only as transport delivery
  failure and emitted exactly once by the consumer-side delivery listener.
- Engine compensation consumes transport delivery failure feedback without
  moving task lifecycle ownership into transport.
- Every retained transport Redis key family has manifest-backed proof, actual
  Redis tests, source guards, and proof registry coverage.
- Forbidden worker lifecycle/admission/capacity/task lifecycle facts cannot be
  written under `xa:mass:transport:*`.
- Active docs reflect the boundary.
- Redis physical key compaction remains explicitly deferred or is covered by a
  successor roadmap/slice with its own proof.

## Implementation Verification Evidence

Run on 2026-06-12:

```bash
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-worker-runtime,xa-mass-engine -am -DskipTests compile
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk -am -DskipTests install
./mvnw -q -pl transport/transport_runtime test "-Dtest=TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest"
./mvnw -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=WorkerRuntimePresenceIngressTest,WorkerRuntimeSelectionIntegrationTest,MassSdkTest#sdkWorkerReachableReadsWorkerRuntimeStatus+sdkWorkerReachableTreatsUnavailableWorkerRuntimeStatusAsOffline"
./mvnw -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassApplicationDistributedTransportTest"
./mvnw -q -pl transport/transport_runtime test "-Dtest=RedisTransportRouteOwnerStoreTest,RedisTransportDeliveryStoreTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportDeliveryFailureChannelTest,TransportNodeRegistryTest"
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
```
