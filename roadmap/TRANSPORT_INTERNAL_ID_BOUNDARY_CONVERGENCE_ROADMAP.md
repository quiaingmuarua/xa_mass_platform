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

This roadmap narrows transport around functional layers while reducing the
number of live transport DTOs and lookup shapes:

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
unrelated diagnostics. This is a compression roadmap, not a wrapper roadmap:
new names should replace misleading names in place wherever possible, and any
new type must retire or merge an older type in the same executable slice.

## Current Confusion

Current names that invite misreads:

- `adapterId` is used as concrete runtime adapter binding, final-hop adapter
  selector, worker registration resolved value, diagnostics text, and some SDK
  worker lookup vocabulary.
- `routeKey` is an endpoint address value, but the name sounds like
  a dispatch correctness route.
- `transportNodeId` and `targetTransportNodeId` are runtime process locality,
  but the names read like final endpoint identity.
- `connectionId` is a lease/session handle, but it is not the public
  `sessionToken` contract and not a worker identity.
- `deliveryQueueKey` is a store partition key, but the name often gets treated
  as a delivery target.
- `getWorkerAdapterId(workerId)` looks like it returns the final-hop transport
  driver for delivery, while the public worker layer should not know final-hop
  driver ids.
- `transportHint` behaves like a free-form string, but the intended concept is a
  bounded worker interaction mode: polling worker pulls, realtime transport
  pushes.
- `adapterNodeId` is worker-gateway/control-plane topology, but current worker
  sessions and SDK samples can require workers to pass it as if it were a
  delivery/session id.

## Target Vocabulary

Every id name should answer one question:

| Functional question | Target name | Owner |
| --- | --- | --- |
| Which worker did engine select? | `selectedWorkerId` | engine assignment / transport delivery constraint |
| Which worker interaction mode is requested? | `workerTransportMode` | public worker/session enum |
| Which configured worker access gateway owns topology? | `workerGatewayId` | operator/control-plane topology |
| Which assigned-delivery domain should be searched? | `deliveryBucketId` | starter/transport projection |
| Which transport runtime process owns the handoff lane? | `runtimeNodeId` | transport runtime locality |
| Which current endpoint lease is being used? | `endpointLeaseId` | transport endpoint lease |
| Which transport implementation sends the final hop? | `endpointDriverId` | adapter/driver registry |
| What address does the driver need? | `endpointAddress` | endpoint lease / driver internals |
| What connection/session handle does the driver need? | `sessionHandle` | endpoint lease / driver internals |
| Which queue/store partition should hold polling items? | `storePartitionKey` | delivery store internals |

`workerTransportMode` is a bounded public enum, initially `POLLING` and
`REALTIME`. Protocol names such as `websocket`, `socket`, `grpc`, `ws`, or
`pull` should not become worker transport modes unless they represent a new
worker interaction model rather than a concrete transport implementation.

`endpointDriverId` does not have to be an enum. A concrete adapter or driver may
self-maintain ids such as `websocket-main`, `socket-edge-a`, or `polling`.
Transport should not require a global driver enum unless a future product
decision needs it.

## Boundary Rules

### Public Worker And SDK Boundary

Allowed worker/session facts:

```text
workerId
workerGroupId
workerTransportMode
sessionToken / credential
```

Worker session/connect/poll APIs should not require the worker to pass a
gateway/topology id. The platform should resolve the gateway from credential,
access endpoint, worker group, and worker transport mode.

Allowed operator/control-plane topology facts:

```text
workerGatewayId
workerGroupId
workerTransportMode
gatewayEndpointRef / gatewayAddressRef
enabled / draining / version / attributes
```

Current `adapterNodeId` maps to `workerGatewayId`. Current public adapter-node
registration `endpointId` maps to `gatewayEndpointRef` or `gatewayAddressRef`.
Those are control-plane topology fields, not worker-session fields, not
assigned-delivery fields, and not transport endpoint lease identity.

Forbidden as public assigned-delivery target facts:

```text
adapterId
adapterNodeId / workerGatewayId
transportHint as free-form id
routeKey
deliveryBucketId
transportNodeId / runtimeNodeId
connectionId / sessionHandle
deliveryQueueKey / storePartitionKey
endpointLeaseId
endpointDriverId
```

Worker/session APIs must not expose gateway, endpoint-driver, runtime-node, or
endpoint-lease ids as worker delivery targets. Operator/control-plane APIs may
expose `workerGatewayId` only as topology management, not as session routing or
assigned-delivery input.

### Assignment And Producer Boundary

Engine/starter assigned delivery still exposes only:

```text
deliveryBucketId
selectedWorkerId
opaque worker payload
opaque delivery correlation
command id / timing
```

Producer-side transport code derives only the bucket queue address. It must not
receive endpoint address, session handle, lease expiry, runtime node, or driver
details.

Target shape:

```java
record AssignedDeliveryIntent(
    String deliveryBucketId,
    String selectedWorkerId,
    String payload,
    String correlationRef
) {}
```

The current `SelectedWorkerDeliveryTarget` should be renamed in place or
replaced and deleted in the same slice if it still models producer-visible
runtime-node targeting. The producer-side target must not carry endpoint lease
identity or runtime-node identity. Endpoint lease validation belongs to the
consumer/final-hop boundary after queue claim.

### Runtime Handoff Boundary

`DeliveryCommandBatch` should describe one consumer-local handoff materialization:

```java
DeliveryCommandBatch {
  deliveryQueueKey
  List<DeliveryCommandReference> references
  List<DeliveryCommand> items
}
```

The batch is local to the queue consumer that claimed it. It must not carry
producer-visible runtime-node targeting, endpoint address, session handle,
endpoint lease id, lease expiry, or driver payload. Do not add a batch-local
endpoint lease shortcut in this roadmap; consumer/final-hop endpoint resolution
is the ownership boundary that keeps stale endpoint evidence out of producer
handoff.

### Endpoint Lease Boundary

Route-owner records should converge toward endpoint lease language:

```java
record TransportEndpointLease(
    String endpointLeaseId,
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
Any raw correlation value that an adapter still needs must stay adapter-local or
diagnostic-only; it must not be folded into `endpointAddress` semantics.

### Final-Hop Driver Boundary

Final-hop dispatch should be driven by endpoint lease facts:

```text
endpointDriverId -> TransportEndpointDriver / WorkerAdapter
endpointAddress + sessionHandle -> concrete send
```

`WorkerAdapter.adapterId()` and `TransportRuntimeRegistry.*AdapterId*` should
converge toward driver vocabulary only after callers are inventoried. The
target name is `endpointDriverId`, not `adapterId`.

`endpointDriverId` is a runtime-local final-hop driver binding key. It is not
`workerGatewayId` and not `workerTransportMode`: `workerGatewayId` is
operator/control-plane topology, `workerTransportMode` is a public worker
interaction enum, and `endpointDriverId` resolves the concrete `WorkerAdapter`
binding used by the local runtime for final-hop dispatch.

### Queue And Store Boundary

`deliveryQueueKey` should be renamed where it means storage partition:

```text
deliveryQueueKey -> storePartitionKey
deliveryLaneKey  -> handoffConsumerKey
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
- Do not make `workerGatewayId` a worker session/connect/poll input.
- Do not rewrite worker lifecycle, worker admission, or scheduling selection.
- Do not remove operator/control-plane gateway topology in this roadmap; only
  stop leaking it into worker sessions and assigned delivery.
- Do not add compatibility wrappers or deprecated aliases for old internal
  names unless an external public contract forces it.

## Compression Rules

This roadmap should reduce transport layering, not preserve old layers behind
new names.

- Prefer rename-in-place when the owner, caller set, and lifecycle boundary do
  not change.
- A new type is allowed only when it replaces an old type and deletes or merges
  that old type in the same executable slice.
- Do not introduce pass-through `*Resolver`, `*Facade`, `*Bridge`,
  `*Wrapper`, or successor DTOs that only forward to the previous owner.
- `TransportEndpointLease` may replace the current route-owner endpoint record
  shape; it must not sit beside `RouteConsumerEndpoint`,
  `TransportRouteOwnerRecord`, and `AdapterEndpoint` as a fourth live truth.
- `SelectedWorkerDeliveryTarget` should be renamed in place or replaced by a
  narrower runtime-node target; do not keep both as producer-facing contracts.
- Each implementation slice should have a residue scan proving that the old
  public or producer-facing name was removed from the relevant boundary.
- `transportHint` should be renamed to `workerTransportMode` and backed by a
  bounded enum; do not preserve a free-form string plus enum as two live public
  paths.
- `adapterNodeId` should be renamed to `workerGatewayId` only inside topology
  owners; worker session APIs should remove the field instead of renaming it.

## Do Not Start With

Do not start with a global rename of every `adapterId`, `routeKey`,
`transportNodeId`, or `connectionId`. That would create churn without fixing
owner boundaries.

Start with caller inventory and public/producers guards. Then rename the
smallest contract surface at each layer while preserving compile-safe slices.

Do not put endpoint lease facts back into `DeliveryCommand` or recreate
task-shaped transport command models as compatibility aliases.

Do not start by mechanically renaming every `adapterNodeId` to
`workerGatewayId`. First separate topology owners from worker/session callers.
Renaming the field while workers still have to provide it would preserve the
leak under a better name.

## Phase TID-0: Inventory And Naming Lock

Goal: classify every current internal id by functional layer before renaming.

Scope:

- `WorkerTransportHints` / target `WorkerTransportMode`
- `WorkerClientOperations#getWorkerAdapterId`
- `MassSdkApplication#getWorkerAdapterId`
- `WorkerRegistration` / worker session builders
- `AdapterNodeRegistration`
- `NodeGroupBindingRegistration`
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
- worker-runtime adapter-node and node-group binding records

Acceptance:

- Inventory table classifies every `adapterId`, `routeKey`,
  `adapterNodeId`, `transportHint`, `transportNodeId`, `connectionId`, and
  `deliveryQueueKey` as worker session fact, operator/control-plane topology,
  assignment, handoff locality, endpoint lease, final-hop driver, raw
  side-channel, or store partition.
- The locked target names are:
  `workerTransportMode`, `workerGatewayId`, `gatewayEndpointRef`,
  `runtimeNodeId`, `endpointLeaseId`, `endpointDriverId`, `endpointAddress`,
  `sessionHandle`, `storePartitionKey`, and `handoffConsumerKey`.
- The roadmap states which current names are allowed to remain temporarily in
  adapter-local raw side-channels.
- The inventory records which existing types will be renamed in place, merged,
  or deleted; no successor type is allowed without a deletion target.
- No behavior change is required in this phase.

## Phase TID-1: Public Worker Boundary Cleanup

Goal: stop exposing transport internal ids and control-plane gateway ids through
worker/session and SDK worker-client surfaces.

Scope:

- Replace `getWorkerAdapterId(workerId)` with a correctly named topology/admin
  method, or remove it if callers only need `workerTransportMode`.
- Replace `getWorkerTransportHint(workerId)` with
  `getWorkerTransportMode(workerId)` or remove the read path if worker session
  callers do not need it.
- Rename public `transportHint` request/response fields to
  `workerTransportMode` and validate them as a bounded enum.
- Remove `adapterNodeId` from worker session start/connect/poll builders and
  worker-facing registration APIs. Server/SDK should resolve
  `workerGatewayId` from credential, access endpoint, worker group, and worker
  transport mode.
- Rename `adapterNodeId` to `workerGatewayId` only in operator/control-plane
  topology APIs, bootstrap/catalog views, and worker-runtime topology binding
  records.
- Treat WebSocket and socket public/worker wire `workerGroupId` as the expected
  current shape; keep this as a residue/guard item rather than a new
  implementation slice unless a scan proves drift.
- Rename, remove, or explicitly reclassify public adapter-node `endpointId` so
  it becomes `gatewayEndpointRef` / `gatewayAddressRef` and cannot be confused
  with transport endpoint lease identity.
- Public Java SDK, worker-pack samples, and server DTOs must not expose
  `adapterNodeId`, `workerGatewayId`, `routeKey`, `endpointAddress`,
  `sessionHandle`, `runtimeNodeId`, `endpointLeaseId`, `endpointDriverId`, or
  `storePartitionKey` through worker session or assigned-delivery contracts.

Acceptance:

- Worker session and assigned-delivery APIs expose only `workerId`,
  `workerGroupId`, `workerTransportMode`, and `sessionToken` / credential.
- `workerTransportMode` is a bounded enum; unknown strings such as `websocket`,
  `ws`, `pull`, or `grpc` do not silently become public modes.
- `adapterNodeId` is gone from worker session builders, worker connect/poll
  paths, worker-pack quickstarts, and worker-facing registration contracts.
- `workerGatewayId` appears only in operator/control-plane topology APIs,
  bootstrap/catalog views, and worker-runtime node-group binding ownership.
- `getWorkerAdapterId` is gone from the public SDK mainline or renamed to a
  topology-correct name with no final-hop driver semantics.
- WebSocket/socket tests use `workerGroupId` on worker-facing handshakes; any
  `deliveryBucketId` use is adapter-internal only and guarded.
- Public adapter-node `endpointId`, if retained temporarily, is documented and
  guarded as gateway topology only; target name is `gatewayEndpointRef` /
  `gatewayAddressRef`, and it is not used by assigned delivery, route-owner
  endpoint lease lookup, or final-hop send.
- Architecture guard fails if public worker packages or server worker DTOs
  expose `adapterId`, `adapterNodeId`, `workerGatewayId`, `transportHint`,
  `routeKey`, `connectionId`, `transportNodeId`, `deliveryQueueKey`,
  `endpointLeaseId`, `endpointDriverId`, `endpointAddress`, or `sessionHandle`
  as worker session, assigned-delivery, or final-hop evidence.

## Phase TID-2: Endpoint Lease API Convergence

Goal: make route-owner API names describe endpoint leases instead of route keys
and adapters.

Scope:

- Rename or replace the existing route-owner endpoint record shape with
  `TransportEndpointLease`; the old record/view type must be removed or merged
  in the same slice.
- Rename `RouteConsumerEndpoint` in place where possible. If a replacement type
  is unavoidable, the old type must be deleted in the same slice; do not leave
  a successor pair.
- Rename `SelectedWorkerDeliveryTarget` to a runtime-node target, or replace it
  with a narrower target and delete the old type in the same slice. It carries
  only `deliveryBucketId + selectedWorkerId + runtimeNodeId`.
- Route-owner store/view APIs should talk in bucket-worker current endpoint
  terms, not adapter-worker or route-owner terms.
- Keep route-key diagnostic reads only as bounded maintenance/raw side-channel
  reads.

Acceptance:

- Producer lookup returns only `deliveryBucketId`, `selectedWorkerId`, and
  `runtimeNodeId`.
- Listener lookup returns endpoint lease evidence and validates currentness
  before final-hop send.
- Endpoint lease identity is named `endpointLeaseId` internally and never
  appears on producer-side target or handoff batch contracts.
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
- `WorkerAdapter.transportHint()` converges toward `workerTransportMode()` or a
  `WorkerTransportMode` enum accessor.
- `AdapterDispatchRequest` carries driver id only inside final-hop transport,
  not producer assignment or public SDK contracts.
- `workerGatewayId` remains operator/control-plane topology and must not be
  collapsed into endpoint driver id.
- `endpointDriverId` cardinality is one runtime-local driver binding; multiple
  worker gateways may map to the same driver, and one runtime node may host
  multiple drivers.

Acceptance:

- Final-hop dispatch code reads driver id from endpoint lease evidence.
- No producer submitter, engine/starter translator, public SDK worker client,
  worker session, or server worker DTO depends on endpoint driver id.
- Tests cover multiple drivers on one runtime node and one selected worker
  reconnecting through a different driver under the same bucket.

## Phase TID-4: Handoff Consumer And Store Key Naming

Goal: separate handoff consumer locality from polling/store partitions.

Scope:

- Remove producer-visible `targetTransportNodeId` / `targetRuntimeNodeId`
  handoff targeting. Runtime-node locality may exist only inside
  handoff-private queue-consumer context or endpoint lease evidence.
- Rename any remaining `deliveryLaneKey` to `handoffConsumerKey` where it means
  a command handoff queue consumer, not a producer lane target.
- Rename `deliveryQueueKey` to `storePartitionKey` where it means polling
  delivery store partition.
- Remove `adapterId` parameters from delivery store APIs unless they are truly
  needed as endpoint-driver diagnostics; if kept, rename them to
  `endpointDriverId` and keep them final-hop/internal only.

Acceptance:

- Handoff batch shape uses `deliveryQueueKey` plus handoff-owned command
  references/items only; runtime-node locality stays in queue-consumer context.
- Polling delivery store APIs use `storePartitionKey + selectedWorkerId`.
- No code or docs describe queue/store partition as a delivery target.
- Redis key manifest uses queue-consumer and store-partition
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
- Guards fail if `DeliveryCommand`, opaque payload/correlation carrier, or any
  recreated task-shaped transport command model regains endpoint lease, driver,
  runtime-node, route/address, session, or store partition fields.
- Guards fail if public worker APIs expose endpoint lease or driver ids.
- Guards fail if worker session/connect/poll APIs expose `adapterNodeId` or
  `workerGatewayId`.
- Guards fail if public worker/session APIs accept free-form `transportHint`
  instead of bounded `workerTransportMode`.
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
rg -n "getWorkerAdapterId|getWorkerTransportHint|adapterNodeId|transportHint|adapterId\\(|routeKey\\(|connectionId\\(|transportNodeId\\(|deliveryQueueKey\\(" sdk/xa-mass-java-sdk/src/main/java xa-mass-server/src/main/java/com/xa/mass/api/model/worker -g "*.java"
rg -n "adapterNodeId|workerGatewayId" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session integrations/xa-mass-worker-pack/src/main/java -g "*.java"
rg -n "adapterId|routeKey|connectionId|transportNodeId|deliveryQueueKey|endpointDriverId|endpointAddress|sessionHandle|endpointLeaseId|TaskDispatchContent|TaskDispatchExecutionContext" transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java transport/transport_api/src/main/java/com/xa/mass/transport/model transport/transport_api/src/main/java/com/xa/mass/transport/channel
rg -n "adapterId \\+ selectedWorkerId|activeOwnerForSelectedWorker|adapterWorkerKey|DeliveryCommandGroup" transport sdk xa-mass-server roadmap -g "*.java" -g "*.md"
```

## Completion Criteria

- Worker session APIs expose only worker identity, worker group,
  `workerTransportMode`, and session credential/token, not gateway topology,
  endpoint lease, driver, runtime-node, route/address, session-handle, or store
  partition ids.
- Operator/control-plane topology APIs use `workerGatewayId` and
  `gatewayEndpointRef` / `gatewayAddressRef`; those ids do not enter worker
  session, assigned delivery, or final-hop endpoint lease contracts.
- Assigned delivery producer boundary remains
  `deliveryBucketId + selectedWorkerId + typed content/context`.
- Runtime handoff uses runtime-node locality vocabulary, not endpoint or driver
  identity.
- Endpoint lease vocabulary owns endpoint lease id, runtime node, driver id,
  address, session handle, and lease expiry.
- Final-hop dispatch resolves drivers from endpoint lease evidence only.
- Polling/store partition vocabulary is isolated from endpoint routing and
  worker correctness.
- Active docs and proof registry use one vocabulary and archive superseded
  narratives.
