# Transport Internal ID Boundary Convergence Roadmap

Status: proposed direction document.

## Summary

Transport has already converged assigned task delivery toward:

```text
deliveryBucketId + selectedWorkerId
```

The remaining problem is that many transport-internal ids still carry historical
names that do not explain their function. `adapterId`, `routeKey`,
`transportNodeId`, `connectionId`, and `deliveryQueueKey` are all real runtime
facts, but the current names make callers guess whether an id means protocol,
runtime process, endpoint lease, worker registration topology, queue partition,
or connection address.

This roadmap narrows transport around functional layers:

```text
public worker/session boundary
  -> assignment delivery boundary
  -> runtime-node handoff boundary
  -> endpoint lease boundary
  -> endpoint driver/final-hop boundary
  -> queue/store internals
```

The target is not to collapse everything into one string id. The target is that
each id answers one question, stays inside the layer that owns that question,
and does not leak into public worker APIs, engine/starter assignment, or
unrelated diagnostics.

## Current Confusion

Current names that invite misreads:

- `adapterId` is used as concrete runtime adapter binding, final-hop adapter
  selector, worker registration resolved value, diagnostics text, and some SDK
  worker lookup vocabulary.
- `routeKey` is an endpoint address/correlation value, but the name sounds like
  a dispatch correctness route.
- `transportNodeId` and `targetTransportNodeId` are runtime process locality,
  but the names read like final endpoint identity.
- `connectionId` is a lease/session handle, but it is not the public
  `sessionToken` contract and not a worker identity.
- `deliveryQueueKey` is a store partition key, but the name often gets treated
  as a delivery target.
- `getWorkerAdapterId(workerId)` looks like it returns the final-hop transport
  driver for delivery, while the public worker layer should talk in
  `adapterNodeId` and `transportHint`.

## Target Vocabulary

Every id name should answer one question:

| Functional question | Target name | Owner |
| --- | --- | --- |
| Which worker did engine select? | `selectedWorkerId` | engine assignment / transport delivery constraint |
| Which assigned-delivery domain should be searched? | `deliveryBucketId` | starter/transport projection |
| Which transport runtime process owns the handoff lane? | `runtimeNodeId` | transport runtime locality |
| Which current endpoint lease is being used? | `endpointId` | transport endpoint lease |
| Which transport implementation sends the final hop? | `endpointDriverId` | adapter/driver registry |
| What address/correlation does the driver need? | `endpointAddress` | endpoint lease / driver internals |
| What connection/session handle does the driver need? | `sessionHandle` | endpoint lease / driver internals |
| Which queue/store partition should hold polling items? | `storePartitionKey` | delivery store internals |

`endpointDriverId` does not have to be an enum. A concrete adapter or driver may
self-maintain ids such as `websocket-main`, `socket-edge-a`, or `polling`.
Transport should not require a global enum unless a future product decision
needs it.

## Boundary Rules

### Public Worker And SDK Boundary

Allowed public worker/session facts:

```text
workerId
workerGroupId
adapterNodeId
transportHint
sessionToken
```

Forbidden as public assigned-delivery target facts:

```text
adapterId
routeKey
deliveryBucketId
transportNodeId / runtimeNodeId
connectionId / sessionHandle
deliveryQueueKey / storePartitionKey
endpointId
endpointDriverId
```

Public worker APIs may expose `adapterNodeId` because it is worker registration
topology. They must not expose endpoint driver/runtime ids as worker delivery
targets.

### Assignment And Producer Boundary

Engine/starter assigned delivery still exposes only:

```text
deliveryBucketId
selectedWorkerId
TaskDispatchContent
TaskDispatchExecutionContext
command id / timing
```

Producer-side transport code may resolve a narrow locality hint, but it must
not receive endpoint address, session handle, lease expiry, or driver details.

Target shape:

```java
record SelectedWorkerEndpointTarget(
    String deliveryBucketId,
    String selectedWorkerId,
    String endpointId,
    String runtimeNodeId
) {}
```

`endpointId` is a transport-owned lease identity. It is a hint for validation
and diagnostics, not a worker-selection fact and not a public API field.

### Runtime Handoff Boundary

`DeliveryCommandBatch` should describe one runtime handoff lane:

```java
DeliveryCommandBatch {
  deliveryBucketId
  handoffLaneKey
  targetRuntimeNodeId
  List<DeliveryCommand> items
}
```

The batch may target a runtime node. It must not carry endpoint address,
session handle, endpoint lease, or driver payload. If an endpoint id is later
needed to avoid an extra lookup, add it as a transport-owned target hint in a
batch-local wrapper, not as a `DeliveryCommand` field.

### Endpoint Lease Boundary

Route-owner records should converge toward endpoint lease language:

```java
record TransportEndpointLease(
    String endpointId,
    String deliveryBucketId,
    String selectedWorkerId,
    String runtimeNodeId,
    String endpointDriverId,
    String endpointAddress,
    String sessionHandle,
    long leaseExpireAtEpochMillis,
    long updatedAtEpochMillis
) {}
```

Current `routeKey` maps to `endpointAddress`. Current `connectionId` maps to
`sessionHandle`. Current `adapterId` maps to `endpointDriverId`. Current
`transportNodeId` maps to `runtimeNodeId`.

The lease store may keep old physical Redis or in-memory fields during a slice,
but the API and docs should move to endpoint lease vocabulary first. The store
must not derive bucket, selected worker, or driver from endpoint address syntax.

### Final-Hop Driver Boundary

Final-hop dispatch should be driven by endpoint lease facts:

```text
endpointDriverId -> TransportEndpointDriver / WorkerAdapter
endpointAddress + sessionHandle -> concrete send
```

`WorkerAdapter.adapterId()` and `TransportRuntimeRegistry.*AdapterId*` should
converge toward driver vocabulary only after callers are inventoried. The
target name is `endpointDriverId`, not `adapterId`.

### Queue And Store Boundary

`deliveryQueueKey` should be renamed where it means storage partition:

```text
deliveryQueueKey -> storePartitionKey
deliveryLaneKey  -> handoffLaneKey
```

Polling delivery still preserves selected-worker correctness with:

```text
storePartitionKey + selectedWorkerId
```

`storePartitionKey` must not contain worker identity and must not be treated as
an endpoint target.

## Non-Goals

- Do not rename engine or worker-runtime `workerGroupId` to bucket terminology.
- Do not remove `routeKey` physical storage in the first slice. It may remain
  endpoint address storage while APIs converge.
- Do not collapse runtime node, endpoint lease, driver, session handle, and
  store partition into one opaque id.
- Do not make `endpointDriverId` a public worker API.
- Do not rewrite worker lifecycle, worker admission, or scheduling selection.
- Do not add compatibility wrappers or deprecated aliases for old internal
  names unless an external public contract forces it.

## Do Not Start With

Do not start with a global rename of every `adapterId`, `routeKey`,
`transportNodeId`, or `connectionId`. That would create churn without fixing
owner boundaries.

Start with caller inventory and public/producers guards. Then rename the
smallest contract surface at each layer while preserving compile-safe slices.

Do not put endpoint lease facts back into `DeliveryCommand`,
`TaskDispatchContent`, or `TaskDispatchExecutionContext`.

## Phase TID-0: Inventory And Naming Lock

Goal: classify every current internal id by functional layer before renaming.

Scope:

- `WorkerClientOperations#getWorkerAdapterId`
- `MassSdkApplication#getWorkerAdapterId`
- `TransportRuntimeRegistry`
- `TransportBinding`
- `WorkerAdapter`
- `AdapterDispatchRequest`
- `AdapterEndpoint`
- `RouteConsumerEndpoint`
- `TransportRouteOwnerRecord`
- `TransportRouteOwnerClaim`
- `SelectedWorkerDeliveryTarget`
- `DeliveryCommandBatch`
- `TransportDeliveryStore`
- `TransportDeliveryService`
- socket/websocket frame codecs and session managers
- public Java SDK worker sessions and worker-pack samples
- server worker registration and polling/realtime controllers

Acceptance:

- Inventory table classifies every `adapterId`, `routeKey`,
  `transportNodeId`, `connectionId`, and `deliveryQueueKey` as public topology,
  assignment, handoff locality, endpoint lease, final-hop driver, raw
  side-channel, or store partition.
- The locked target names are:
  `runtimeNodeId`, `endpointId`, `endpointDriverId`, `endpointAddress`,
  `sessionHandle`, `storePartitionKey`, and `handoffLaneKey`.
- The roadmap states which current names are allowed to remain temporarily in
  adapter-local raw side-channels.
- No behavior change is required in this phase.

## Phase TID-1: Public Worker Boundary Cleanup

Goal: stop exposing transport internal ids through public worker/session and
SDK worker-client surfaces.

Scope:

- Replace `getWorkerAdapterId(workerId)` with a correctly named public method,
  or remove the method if callers only need `transportHint` or
  `adapterNodeId`.
- Keep `getWorkerTransportHint(workerId)` only as coarse public transport
  family evidence.
- WebSocket and socket public/worker wire should send `workerGroupId`, not
  `deliveryBucketId`, when the caller is a worker client. Adapter internals may
  map worker-group context to `deliveryBucketId`.
- Public Java SDK, worker-pack samples, and server DTOs must not expose
  `routeKey`, `endpointAddress`, `sessionHandle`, `runtimeNodeId`,
  `endpointDriverId`, or `storePartitionKey` as assigned-delivery target facts.

Acceptance:

- Worker-facing APIs expose only `workerId`, `workerGroupId`,
  `adapterNodeId`, `transportHint`, and `sessionToken` for registration/session
  topology.
- `getWorkerAdapterId` is gone from the public SDK mainline or renamed to a
  topology-correct name with no final-hop driver semantics.
- WebSocket/socket tests use `workerGroupId` on worker-facing handshakes; any
  `deliveryBucketId` use is adapter-internal only and guarded.
- Architecture guard fails if public worker packages or server worker DTOs
  expose `adapterId`, `routeKey`, `connectionId`, `transportNodeId`,
  `deliveryQueueKey`, `endpointId`, `endpointDriverId`, `endpointAddress`, or
  `sessionHandle`.

## Phase TID-2: Endpoint Lease API Convergence

Goal: make route-owner API names describe endpoint leases instead of route keys
and adapters.

Scope:

- Introduce or rename toward `TransportEndpointLease`.
- Rename `RouteConsumerEndpoint` or add a successor with endpoint lease
  vocabulary.
- Replace `SelectedWorkerDeliveryTarget` with
  `SelectedWorkerEndpointTarget` carrying `endpointId + runtimeNodeId`.
- Route-owner store/view APIs should talk in bucket-worker current endpoint
  terms, not adapter-worker or route-owner terms.
- Keep route-key diagnostic reads only as bounded maintenance/raw side-channel
  reads.

Acceptance:

- Producer lookup returns only `deliveryBucketId`, `selectedWorkerId`,
  `endpointId`, and `runtimeNodeId`.
- Listener lookup returns endpoint lease evidence and validates currentness
  before final-hop send.
- Endpoint lease tests prove latest claim wins, stale heartbeat/release cannot
  mutate replacements, endpoint moved after handoff is retryable, and no driver
  fallback is attempted.
- Store implementations do not infer bucket or selected worker from
  endpointAddress/routeKey syntax.

## Phase TID-3: Driver Vocabulary And Final-Hop Isolation

Goal: rename final-hop adapter identity to driver vocabulary without changing
worker scheduling or public topology.

Scope:

- `adapterId` in final-hop dispatch becomes `endpointDriverId`.
- `TransportRuntimeRegistry.resolveDispatchAdapterByAdapterId(...)` converges
  toward `resolveEndpointDriver(...)`.
- `WorkerAdapter.adapterId()` converges toward `endpointDriverId()`.
- `AdapterDispatchRequest` carries driver id only inside final-hop transport,
  not producer assignment or public SDK contracts.
- `adapterNodeId` remains worker registration topology and must not be
  collapsed into endpoint driver id.

Acceptance:

- Final-hop dispatch code reads driver id from endpoint lease evidence.
- No producer submitter, engine/starter translator, public SDK worker client, or
  server worker DTO depends on endpoint driver id.
- Tests cover multiple drivers on one runtime node and one selected worker
  reconnecting through a different driver under the same bucket.

## Phase TID-4: Handoff And Store Key Naming

Goal: separate runtime-node handoff lanes from polling/store partitions.

Scope:

- Rename `targetTransportNodeId` to `targetRuntimeNodeId` or `runtimeNodeId`
  where it means process locality.
- Rename `deliveryLaneKey` to `handoffLaneKey` where it means command handoff
  queue lane.
- Rename `deliveryQueueKey` to `storePartitionKey` where it means polling
  delivery store partition.
- Remove `adapterId` parameters from delivery store APIs unless they are truly
  needed as endpoint-driver diagnostics; if kept, rename them to
  `endpointDriverId` and keep them final-hop/internal only.

Acceptance:

- Handoff batch shape uses `targetRuntimeNodeId` and `handoffLaneKey`.
- Polling delivery store APIs use `storePartitionKey + selectedWorkerId`.
- No code or docs describe queue/store partition as a delivery target.
- Redis key manifest uses runtime-node, handoff-lane, and store-partition
  wording consistently.

## Phase TID-5: Docs, Guards, And Archive

Goal: make the new vocabulary durable and remove stale parallel narratives.

Scope:

- Update `transport/AGENTS.md`.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `doc/AGENT_BASELINE.md` if global transport id vocabulary changes.
- Update `doc/PROOF_REGISTRY.md`.
- Archive superseded transport roadmaps or mark them as superseded.
- Add architecture guards for public boundary leakage, producer boundary
  leakage, endpoint lease field placement, and store partition naming.

Acceptance:

- Active docs have one vocabulary table and no competing `adapterId` /
  `routeKey` explanation for assigned delivery.
- Guards fail if `DeliveryCommand`, `TaskDispatchContent`, or
  `TaskDispatchExecutionContext` regain endpoint lease, driver, runtime-node,
  route/address, session, or store partition fields.
- Guards fail if public worker APIs expose endpoint lease or driver ids.
- Guards allow adapter-local raw/debug side-channels only in explicitly
  allowlisted classes.

## Verification Candidates

Compile:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,integrations/xa-mass-worker-pack,xa-mass-server -am -DskipTests test-compile
```

Focused transport tests:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,PollingWorkerAdapterTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/socket-adapter "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,SocketTransportServerTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,WebSocketTaskDispatchChannelTest,WebSocketTransportFrameCodecTest,DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest" test
```

Public/SDK/server tests:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,sdk/xa-mass-embedded-sdk "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,MassSdkTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest" test
./mvnw -q -pl sdk/xa-mass-java-sdk "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest" test
./mvnw -q -pl integrations/xa-mass-worker-pack "-Dtest=SampleWorkerWebSocketClientTest,WebSocketClientStarterTest" test
./mvnw -q -pl transport/transport_api,transport/transport_runtime,xa-mass-server "-Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,TransportRouteOwnerViewTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems" test
```

When a slice needs `-am`, do not combine it with adapter-only `-Dtest` names
unless every upstream module in the reactor also has at least one matching test
name. Do not rely on `-Dsurefire.failIfNoSpecifiedTests=false` as roadmap
proof.

Residue scans:

```powershell
rg -n "getWorkerAdapterId|adapterId\\(|routeKey\\(|connectionId\\(|transportNodeId\\(|deliveryQueueKey\\(" sdk/xa-mass-java-sdk/src/main/java xa-mass-server/src/main/java/com/xa/mass/api/model/worker -g "*.java"
rg -n "adapterId|routeKey|connectionId|transportNodeId|deliveryQueueKey|endpointDriverId|endpointAddress|sessionHandle|endpointId" transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskDispatchExecutionContext.java
rg -n "adapterId \\+ selectedWorkerId|activeOwnerForSelectedWorker|adapterWorkerKey|DeliveryCommandGroup" transport sdk xa-mass-server roadmap -g "*.java" -g "*.md"
```

## Completion Criteria

- Public worker/session APIs expose only worker topology and coarse transport
  hints, not endpoint lease or driver ids.
- Assigned delivery producer boundary remains
  `deliveryBucketId + selectedWorkerId + typed content/context`.
- Runtime handoff uses runtime-node locality vocabulary, not endpoint or driver
  identity.
- Endpoint lease vocabulary owns endpoint id, runtime node, driver id, address,
  session handle, and lease expiry.
- Final-hop dispatch resolves drivers from endpoint lease evidence only.
- Polling/store partition vocabulary is isolated from endpoint routing and
  worker correctness.
- Active docs and proof registry use one vocabulary and archive superseded
  narratives.
