# Transport Node Id Removal Convergence Roadmap

Status: proposed direction document.

## Summary

`transportNodeId` was useful when transport still looked like a node-targeted
handoff runtime. The current delivery model has moved away from that shape:

```text
engine/starter assignment facts
  -> deliveryBucketId + selectedWorkerId + opaque DeliveryCommand
  -> bucket-derived delivery queue
  -> selected-worker consumer evidence
  -> adapter-local final-hop session send
```

In this model, `transportNodeId` is not a delivery fact, endpoint identity,
worker lifecycle truth, queue key, or adapter selector. It mostly names the JVM
or process that happens to host transport runtime code. That generic process
identity now costs more than it explains.

This roadmap removes `transportNodeId` and `TransportNodeRegistry` from the
transport mainline. It does not remove adapter binding or multiple adapter
instances. Application assembly must continue to support configurations such
as one runtime hosting `ws-public`, `ws-internal`, and `socket-edge` adapters.
Those are concrete adapter bindings, not transport nodes.

Target principle:

```text
workerGroup / deliveryBucketId     = business delivery bucket
workerId / selectedWorkerId        = execution and delivery correctness identity
adapterId                          = transport-internal adapter endpoint handle
adapterKind / transportHint        = protocol / endpoint kind
deliveryCommandConsumerKey         = transitional handoff name; value is adapterId
sessionHandle / endpointLeaseId    = adapter-local session lease facts
```

Adapter endpoint handle rule for this roadmap:

```text
adapterId = supplied by adapter bootstrap / registration
```

The value is chosen by the adapter registration path, for example `ws-public`,
`ws-internal`, `socket-edge`, or a test-chosen unique value. Transport treats
it as an internal endpoint handle. It is not an engine/starter assignment fact,
not a worker public API parameter, not a durable placement strategy key, and not
a replacement for `transportNodeId`.

If a future deployment needs richer adapter placement constraints, they should
be expressed by a later adapter strategy/topology roadmap, not by generic
transport-node routing identity. This roadmap does not implement adapter
attributes, adapter placement, or adapter capacity strategy.

Multi-instance reservation:

- current embedded mode may keep one transport consumer runtime in the same JVM
  that hosts multiple adapter endpoints
- each adapter endpoint has a transport-internal `adapterId` supplied by its
  bootstrap / registration
- future distributed mode may run many transport consumer JVMs; the current
  mechanism only needs distinct active adapter endpoint handles, not a durable
  adapter topology identity
- business sharing happens through `workerGroupId` / `deliveryBucketId`; many
  adapter ids may serve the same bucket
- `adapterId` is not the protocol kind. `websocket`, `socket`, and `polling`
  belong in `adapterKind` / `transportHint`
- worker APIs bind by delivery bucket, worker id, transport hint, and session
  evidence; they do not receive adapter ids, connection ids, or node ids
- stable cross-restart adapter identity, adapter placement policy, adapter
  capacity strategy, and adapter topology CAS are deferred until a concrete
  adapter strategy caller exists
- tests and distributed examples choose distinct adapter ids explicitly; the
  transport mechanism does not need a cluster-wide uniqueness algorithm

Scope compression decision:

- do not create a second top-level roadmap for `adapterId` / `transportNodeId`
  leakage
- add a front-loaded exposure allowlist phase in this roadmap instead
- remove `transportNodeId` / `runtimeNodeId` as process-node identity
- keep `adapterId` only where it is an adapter binding / final-hop / consumer
  evidence handle
- do not pull `adapterNodeId` into this roadmap. `adapterNodeId` belongs to
  worker/resource control-plane declaration surfaces and should be handled by a
  worker-runtime or external-surface roadmap if it remains too public.

## Current Code Observations

These observations are from the current work tree and must be rechecked before
implementation.

- `TransportNodeRegistry` is in `transport_runtime`, not `transport_api`; it
  exposes `register`, `heartbeat`, `releaseRouteOwner`, `getNode`,
  `listNodes`, and `isNodeOnline`.
- `TransportNodePresence` stores `transportNodeId`, `adapterIds`, state,
  heartbeat timestamps, lease expiration, and connection count.
- `MassApplication` starts `TransportNodeRegistryHeartbeat` by collecting all
  configured adapter binding ids and registering them under one
  `transportNodeId`.
- `TransportConfig` exposes `transportNodeId`, defaulting to a UUID, and SDK /
  server builders allow callers to set it.
- `MassApplicationBuilder.redisDistributedChannels(...)` currently wires a
  Redis node registry namespace. That namespace becomes obsolete when generic
  node heartbeat is removed.
- `MassApplicationBuilder.redisDeliveryCommandHandoff(...)` currently passes
  `config.getTransportNodeId()` into `RedisTransportDeliveryCommandHandoff`.
- The delivery handoff factory is currently stored as a no-arg
  `Supplier<TransportDeliveryCommandHandoff>` in `TransportConfig`, so the
  builder closure can read `config.getTransportNodeId()` before
  `TransportRuntimeComposition` can derive adapter-owned queue consumers from
  resolved adapter bindings.
- `RedisTransportDeliveryCommandHandoff` stores that value as
  `localTransportNodeId`, but uses it as the local delivery-command consumer
  key for ready/inflight claim. This is a naming and ownership problem: the
  runtime needs adapter-owned queue consumer keys, not a transport node id.
- `TransportAdapterBootstrapContext`, `TransportRuntimeRegistry`,
  `ResolvedPullWorkerTransport`, adapters, and `PullWorkerSession` already use
  the transitional name `deliveryCommandConsumerKey`. The source of that key is
  still `TransportConfig.transportNodeId`; the target shape has no shared
  runtime-wide key field. Queue-consumer claims use the binding-scoped
  `adapterId` at the point the adapter/session writes consumer evidence.
- `TransportRuntimeComposition.resolveTransportAdapterBootstraps()` builds
  adapter bootstraps from built-in and supplemental configuration. Current code
  enforces unique adapter ids only inside the local runtime. The target contract
  should keep local duplicate validation and require distributed/focused tests
  to configure distinct adapter ids explicitly, instead of adding a cluster-wide
  uniqueness algorithm.
- `TransportAdapterDescriptor` currently contains only `adapterId` and
  `transportHint`. `TransportRegistrationResolver` uses those descriptors to
  resolve worker registration adapter binding, and requires explicit
  `adapterId` when a realtime transport hint would be ambiguous. In the target
  shape this is classified as worker-facing adapter-id leakage: ambiguity should
  be resolved by the actual adapter endpoint that accepts the worker session,
  not by exposing the delivery handoff adapter id during worker registration.
- Public worker registration DTOs currently carry worker id, worker group,
  transport hint, and attributes, not endpoint address. Realtime WebSocket /
  socket clients choose a concrete endpoint by URL/path/port at connection
  time. The target shape should use that accepted connection endpoint to attach
  the local adapter id to endpoint lease and consumer evidence.
- `WorkerClientOperations.getWorkerAdapterId(...)` is still on the external
  worker runtime interaction surface and returns the transport runtime adapter
  id. This is classified as worker-facing adapter-id leakage, not a stable
  worker API.
- `WorkerAdapter.adapterId()` currently has a default implementation that
  returns `protocol()`. That makes protocol labels such as `websocket`,
  `socket`, or `polling` silently become endpoint handles when an adapter does
  not override the method.
- `PollingWorkerAdapter` currently uses its `PROTOCOL` value for delivery
  enqueue/poll storage, endpoint lease driver id, and consumer evidence adapter
  id. Redis delivery handoff later copies that evidence adapter id into command
  references. This means polling is not just a store-key naming concern; it can
  route final-hop delivery with a protocol-shaped adapter identity.
- `TransportRuntimeRegistry` resolves final-hop adapters from local
  `TransportBinding` entries keyed by adapter id. It is a local runtime registry,
  not a cluster-wide adapter discovery service. In the target shape, the local
  binding's `adapterId` is also the distributed handoff consumer identity.
- `TransportRuntimeComposition` also passes `transportNodeId` into the default
  endpoint lease store. Endpoint lease metadata/view therefore still carry
  `runtimeNodeId` as diagnostic payload.
- Assigned delivery no longer needs a node target.
  `TransportAssignedDeliverySubmitter` groups by
  `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)` and offers
  `DeliveryQueueOffer(deliveryQueueKey, commands)`.
- `DeliveryCommandBatch` no longer carries `targetTransportNodeId`; it only
  carries `deliveryQueueKey`, handoff-owned references, and command items.
- `TransportDeliveryCommandListener` resolves the final-hop adapter from
  handoff-private command references. It does not require `transportNodeId`.
- Endpoint lease stores still persist `runtimeNodeId` in diagnostic metadata,
  but `TransportEndpointLeaseConsumerEvidence` deliberately omits it. This is
  evidence that `runtimeNodeId` is not a hot-path consumer fact.
- `AdapterDispatchLane.forTransportNode(...)`, `AdapterEndpoint.transportNodeId`,
  and several tests preserve the older node-targeted vocabulary.
- Active direction docs still mention `runtimeNodeId` or `transportNodeId` as a
  retained internal id. This roadmap supersedes those retained-node parts where
  the only owner is generic transport process identity.

## Owner Review

### Transport Owns

- adapter binding registration and final-hop adapter dispatch
- multiple adapter instances in one application/runtime, each with its own
  `adapterId` and protocol/server configuration
- `adapterId` as a transport-internal adapter endpoint handle supplied by
  adapter bootstrap / registration
- adapter/session endpoint leases keyed by `deliveryBucketId + workerId`
- selected-worker consumer evidence for delivery-command handoff
- delivery queues, handoff claim/ack/requeue, and dispatch outcomes
- handoff queue-consumer ownership, with `adapterId` as the target consumer key
- local adapter descriptor validation, including local duplicate `adapterId`
  rejection
- adapter-local diagnostics that explain live connection/session behavior

### Transport Must Not Own

- worker lifecycle or reachability truth
- scheduling, reassignment, retry, or compensation decisions
- generic process-node identity as a delivery target
- a second routing plane based on `transportNodeId + adapterId`
- a public or operator-required id for delivery-command consumer internals
- a cluster-wide adapter registry under a renamed node concept
- a first-slice cluster-wide duplicate adapter registry; registration-supplied
  ids plus local duplicate validation are the current mechanism unless a later
  adapter topology roadmap proves the need for CAS ownership
- adapter placement policy, adapter capacity strategy, or stable cross-restart
  adapter identity
- worker/resource control-plane `adapterNodeId` declarations

### Server / SDK Own

- bootstrap and runtime role configuration
- adapter binding configuration and operator-facing diagnostics
- product/API surfaces that may show adapter health later

SDK and server should not expose `transportNodeId` to workers or to ordinary
transport users. If an operator needs deployment visibility, expose it as
adapter-owned diagnostics, not as delivery contract.

## Boundary Decision

Remove `transportNodeId` as a first-class transport concept.

Do not replace it with a public `runtimeNodeId`, `adapterInstanceId`, or
`endpointNodeId` in this roadmap. A future adapter-instance heartbeat may be
added only after a concrete caller, storage shape, attributes, and proof set are
defined.

Use `adapterId` as the adapter endpoint handle and assigned-delivery handoff
consumer handle inside transport. Existing `deliveryCommandConsumerKey` /
`queueConsumerKey` names may remain temporarily where current storage APIs still
require those field names, but their value must be `adapterId`. They are
transitional storage names, not an independent identity:

- `adapterId` is supplied by adapter bootstrap / registration. The registration
  owner may choose a human-readable handle or a generated value, but the
  delivery executor does not define the minting strategy.
- `adapterId` is only stable for the lifetime of the local adapter binding /
  runtime process that owns the handle. It is not a worker session, connection,
  or delivery bucket identity.
- cross-restart adapter identity is not a current delivery-executor contract.
  If adapter strategy later needs stable endpoint identity, that belongs in a
  separate adapter topology roadmap.
- a process that hosts multiple adapter endpoints owns multiple `adapterId`
  values and therefore multiple handoff consumer identities.
- `deliveryCommandConsumerKey` must not be generated independently from
  runtime/node/process state.
- `deliveryCommandConsumerKey` must not remain as a runtime-wide field on
  bootstrap context, runtime registry, or resolved pull transport. It may remain
  only as a storage/interface field name whose value is copied from the
  binding-scoped adapter id.
- command references may carry `queueConsumerKey` as handoff-owned storage
  metadata, but the value should be the target `adapterId`.
- worker APIs, engine/starter assignment, endpoint lease views, and public
  config must not expose a second consumer id.

`adapterId` is not added to `DeliveryCommand`, SDK assignment calls, engine
assignment records, or public worker registration as a dispatch selector. It is
introduced by transport-owned consumer evidence and final-hop dispatch only.

Current roadmap decision: do not introduce a new private stable consumer id and
do not revive a node registry to own adapter uniqueness. Retarget the existing
handoff consumer key value to `adapterId`, remove runtime-wide
`deliveryCommandConsumerKey` state in Phase 1, and leave only storage-level
`queueConsumerKey` vocabulary where the Redis/in-memory handoff contract still
needs that field name. Redis handoff ready/inflight keys remain partitioned by
consumer key, but the consumer key is the adapter endpoint handle.

The current mainline should converge to:

```text
DeliveryCommand
  commandId
  deliveryBucketId
  selectedWorkerId
  opaque payload
  opaque correlation
  deadline / createdAt

Handoff consumer evidence
  deliveryBucketId
  selectedWorkerId
  queueConsumerKey = adapterId
  adapterId
  endpointLeaseId
  lease deadline

Endpoint lease diagnostic view
  deliveryBucketId
  workerId
  endpointDriverId
  endpointAddress
  sessionHandle
  endpointLeaseId

Adapter binding registry
  adapterId
  adapterKind / transportHint
  protocol/server configuration
  no runtime-wide deliveryCommandConsumerKey

Handoff listener / pump
  polls one or more local adapterId consumer queues
  resolves final-hop adapter by adapterId
```

No delivery command, dispatch outcome, worker public API, engine/starter
contract, endpoint lease view, or public config should require or expose
`transportNodeId`.

This decision is scoped to the transport delivery executor boundary, including
polling because polling participates in assigned-delivery consumer evidence and
final-hop command references. Polling may keep its internal store-key type names
temporarily, but selected-worker delivery evidence, endpoint lease driver id,
handoff references, and final-hop adapter resolution must use the binding
adapter id rather than `PollingWorkerAdapter.PROTOCOL`.

## Multi-Adapter Invariant

Removing `transportNodeId` must not collapse application adapter registration.
The following application shape remains valid:

```java
MassApplicationBuilder.create()
        .transport(transport -> transport
                .webSocketAdapter(webSocket -> webSocket
                        .adapterId("ws-public")
                        .server(8081, "/ws")
                        .enabled(true))
                .addWebSocketAdapter(webSocket -> webSocket
                        .adapterId("ws-internal")
                        .server(8083, "/internal-ws")
                        .enabled(true)
                        .serverEnabled(true))
                .socketAdapter(socket -> socket
                        .adapterId("socket-edge")
                        .server(8082)
                        .enabled(true)
                        .serverEnabled(true)))
        .build();
```

The invariant is:

```text
one runtime process may host many adapter endpoint bindings
each adapter endpoint has its own adapterId handle supplied by registration
each selected-worker consumer evidence record still names the serving adapterId
final-hop listener resolves the correct adapter from command reference context
handoff listener polls the adapterId consumer queue for each local adapter endpoint
```

`adapterId` becomes the concrete final-hop adapter endpoint handle. It replaces the
need for a generic node id in assigned delivery, but it is not a protocol type.
If the adapter is websocket, socket, or polling, that belongs in
`adapterKind` / `transportHint`. Test and distributed configurations should
choose distinct handles such as `ws-edge-a`, `ws-edge-b`, or `socket-edge`,
instead of relying on one protocol constant for every adapter endpoint.

## Multi-Instance Reservation

The deletion must not make a future multi-instance transport deployment require
another large boundary rewrite.

Current executable shape:

```text
JVM A
  adapterId = ws-edge-a
  adapterKind = websocket

JVM B
  adapterId = ws-edge-b
  adapterKind = websocket

Both can serve deliveryBucketId = phone-device-probe
```

The current mechanism only requires distinct active adapter handles while they
are claiming and draining handoff queues. A selected worker is deliverable
through the adapter endpoint that currently holds selected-worker consumer
evidence:

```text
deliveryBucketId + selectedWorkerId
  -> consumer evidence
       adapterId = ws-edge-a
       queueConsumerKey = ws-edge-a
       consumerEvidenceId = endpointLeaseId / session evidence
  -> handoff queue owned by adapterId
  -> local adapter endpoint adapterId
```

This preserves multi-instance capability without exposing a node identity:

- `TransportRegistrationResolver` keeps validating only local runtime
  descriptors. Global duplicate `adapterId` CAS is not required for this slice.
  If adapter placement strategy later needs stable endpoint ids, that topology
  owner must define the lease/CAS proof.
- Distributed producer routing uses selected-worker consumer evidence and the
  stored adapter id, not a node table.
- A worker connection can move between adapter endpoints by replacing endpoint
  lease / consumer evidence from one adapter id to another. Transport does not
  need to name the old or new JVM as a public target.
- If adapter placement constraints are needed later, add adapter-owned
  attributes to descriptor/topology records and prove their caller; do not use
  `transportNodeId` as an untyped catch-all.
- A future external adapter process may contribute a bootstrap/descriptor
  through startup assembly or an explicit adapter topology owner, but this
  roadmap does not add dynamic remote adapter discovery or stable adapter
  placement semantics.

## Deferred Adapter Topology Reservation

Future external adapter registration is deliberately out of scope for this
roadmap. If it appears later, it is a transport / operator-topology concern, not
an engine concern.

One possible future shape is:

```text
external adapter process
  -> HTTP registration / heartbeat
  -> adapterKind + optional operator adapter name
  -> adapter registration supplies / resolves adapter endpoint handle
  -> endpoint attributes + lease evidence
  -> transport adapter topology / handoff consumer ownership

engine
  -> assigned delivery facts
  -> deliveryBucketId + selectedWorkerId + opaque payload / correlation
  -> transport delivery executor
```

Owner split:

- adapter process owns protocol server/client lifecycle, connection/session
  manager, local endpoint health, and any adapter-local capacity diagnostics.
- transport owns endpoint handle resolution, lease expiry, handoff consumer
  queues, and final-hop delivery executor mechanics.
- engine owns assignment facts and retry/compensation decisions; it does not
  register, discover, heartbeat, or select adapter instances.
- worker-runtime owns worker execution identity, worker-group membership,
  worker admission/reachability evidence, and any scheduling-facing worker
  facts.
- server / SDK may host the HTTP registration API and bootstrap wiring, but they
  are product/assembly entry points for transport-owned endpoint facts.

Current roadmap slice:

- keep built-in adapter bootstraps as the implementation mechanism
- treat built-in bootstrap descriptors as local adapter bindings, not as a
  completed adapter topology contract
- do not add the HTTP registration endpoint yet
- do not make engine import or query an adapter registry
- do not reintroduce `transportNodeId` as the external adapter process id
- do not require adapter ids to be stable across restart

Future registration must still obey the current identity decision:

```text
adapterId = transport-internal adapter endpoint handle
adapterKind / transportHint = protocol / endpoint kind
deliveryBucketId / workerGroupId = business delivery bucket
selectedWorkerId = assigned execution identity
```

More precisely for this roadmap:

```text
adapterId = registration-supplied endpoint handle, owned inside transport runtime/evidence
```

External workers and engine-facing assignment code must not provide adapter ids
as dispatch targets.

If adapter placement constraints become necessary later, they should be added
as adapter-owned attributes with an explicit scheduling consumer. They must not
be smuggled through `adapterId`, `routeKey`, or a revived node id.

## Relationship To Existing Roadmaps

- Compatible with
  `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md`: that roadmap
  already forbids `transportNodeId` on assigned-delivery command/request/outcome
  paths.
- Supersedes the parts of
  `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md` that keep
  `transportNodeId` by renaming it to `runtimeNodeId`. The stronger decision is
  deletion from public/config/endpoint views, while using adapter id as the
  delivery-command queue consumer owner.
- Future result-ingress work should not introduce `transportNodeId` or
  `adapterId` as a result partition. Result partitioning should follow
  result/task correlation semantics unless a later, explicit adapter strategy
  proves a need for adapter-aware result routing.
- Graceful shutdown docs/tests that mention `TransportNodeRegistryHeartbeat`
  must be updated in the slice that removes the heartbeat.

## Non-Goals

- Do not implement adapter attributes in this roadmap.
- Do not implement HTTP adapter registration or dynamic external adapter
  discovery in this roadmap. Only reserve the owner boundary.
- Do not add a replacement `AdapterInstanceRegistry` just to preserve the old
  node registry under a new name.
- Do not treat `adapterId` as `websocket` / `socket` / `polling`. Those are
  adapter kinds / transport hints, not endpoint handles.
- Do not configure two active transport consumers with the same explicit
  `adapterId`; registration-owned ids and local duplicate validation are the
  current mechanism, not a new cluster registry.
- Do not change worker scheduling, worker-runtime reachability, or engine
  assignment behavior.
- Do not change routeKey, deliveryBucketId, or selectedWorkerId semantics.
  `adapterId` is narrowed by this roadmap only at the transport endpoint /
  handoff consumer boundary.
- Do not collapse multiple adapter instances into one default adapter.
- Do not derive queue-consumer ownership from worker id, bucket id, route key,
  connection id, endpoint lease id, or node id. It is the adapter endpoint id.
- Do not add compatibility aliases for `transportNodeId` inside the repo.
- Do not expose connection/session internals in public worker APIs while
  removing node ids.
- Do not include `adapterNodeId` / adapter-node declaration cleanup in this
  roadmap. That is a worker/resource control-plane surface, not transport
  delivery executor internals.

## Do Not Start With

Do not start by creating a new generic instance registry.

That would keep the same abstraction cost under a better name. First separate
the real remaining concerns:

1. adapter endpoint identity and handoff consumer ownership (`adapterId`)
2. adapter binding / multi-adapter registration (`adapterId + adapterKind`)
3. endpoint lease session evidence (`endpointLeaseId`, `sessionHandle`)
4. obsolete generic node heartbeat (`TransportNodeRegistry`)

Only after those are separated should the obsolete node registry and public
node id be deleted.

Also do not start by removing adapter ids or adapter registration APIs.
Multiple adapter instances are a current application capability and must remain
covered by tests.

Also do not start by replacing worker-facing `adapterId` with a new public
endpoint label. Realtime adapter selection should first use the concrete
WebSocket/socket endpoint that accepted the session. Add a label only after a
separate caller proves that the physical endpoint is insufficient.

## Phase 0 - Transport Id Exposure Allowlist And Inventory

Goal: classify every current transport id use before deleting code, and install
the rule that shrinks this roadmap's blast radius.

Scope:

- production source in `transport`, `sdk`, and `xa-mass-server`
- tests that preserve node-targeted behavior
- Redis key/value codecs that serialize node/runtime ids
- active docs and roadmaps that describe node ids as current truth
- public worker API, engine/starter assignment, SDK/server adapter binding
  configuration, and transport-internal runtime usage classified separately
- multi-adapter builder examples/tests that must keep working
- multi-consumer assumptions where current tests use `node-1` / `node-2` as
  fake queue-consumer keys; those should become adapter ids such as
  `ws-edge-a` / `ws-edge-b`
- `adapterNodeId` occurrences are classified only to mark them out of scope;
  do not clean them up in this roadmap

Transport id exposure allowlist:

| Id / Term | Allowed In This Roadmap | Forbidden In This Roadmap |
| --- | --- | --- |
| `transportNodeId` | archived docs and this roadmap until completion | production config, SDK/server public builders, delivery handoff, endpoint lease metadata/view, Redis runtime values |
| `runtimeNodeId` | none after endpoint lease cleanup | endpoint lease metadata/view, public DTOs, diagnostics that imply process-node truth |
| `adapterId` | adapter binding config, local transport registry, final-hop adapter selection, consumer evidence, handoff storage references, bounded diagnostics | worker registration/session public selector, worker client API, engine/starter assignment, `DeliveryCommand`, dispatch outcome contract, replacement for node registry, protocol-shaped fallback identity |
| `deliveryCommandConsumerKey` / `queueConsumerKey` | handoff-private storage/interface vocabulary whose value is binding-scoped `adapterId` | runtime-wide bootstrap/registry field, public API field, separately generated id |
| `adapterNodeId` | out of scope; worker/resource control-plane declaration only | do not convert into transport delivery executor identity in this roadmap |

Initial classification:

| Symbol / Site | Current Role | Target |
| --- | --- | --- |
| `TransportNodeRegistry` | generic transport process heartbeat registry | delete |
| `TransportNodePresence` | node heartbeat read model | delete |
| `TransportNodeRegistryHeartbeat` | startup helper for process heartbeat | delete |
| `RedisTransportNodeRegistry` / `InMemoryTransportNodeRegistry` | storage adapters for node heartbeat | delete |
| `TransportConfig.transportNodeId` | public-ish SDK/server bootstrap config and accidental source for internal ids | delete after replacement consumers are moved |
| `MassSdk.TransportOptions.transportNodeId(...)` | external SDK builder exposure | delete |
| `MassApplicationBuilder.TransportBuilder.transportNodeId(...)` | starter builder exposure | delete |
| `MassApplicationBuilder.redisDeliveryCommandHandoff(...)` | passes node id as Redis handoff local consumer key | retarget so local queue-consumer key(s) are adapter ids |
| `TransportConfig.deliveryCommandHandoffFactory` | no-arg supplier closes over old config state | replace with adapter-id-aware factory, structured Redis handoff options, or per-adapter handoff listener construction |
| `MassApplicationBuilder.redisDistributedChannels(...)` | wires obsolete node registry namespace | remove node registry wiring |
| `MassApplication.startTransportNodeHeartbeat(...)` | registers process-level heartbeat | delete |
| `XaMassServerApplication.transportNodeId` | server bootstrap property and endpoint lease constructor input | delete after endpoint lease no longer needs runtime node |
| `TransportRuntimeComposition.getTransportNodeId()` | source for adapter bootstrap, runtime registry, endpoint lease, handoff | remove; handoff owner is adapter id |
| `TransportAdapterBootstrapContext.deliveryCommandConsumerKey` | transitional runtime-wide handoff field currently sourced from node id | remove as runtime-wide state; bootstraps must use their binding/config adapter id when claiming consumer evidence |
| `TransportRuntimeRegistry.deliveryCommandConsumerKey` | transitional runtime-wide pull-session consumer owner | remove; resolved pull transport derives the consumer key from the selected `TransportBinding.adapterId` |
| `ResolvedPullWorkerTransport.deliveryCommandConsumerKey` | internal SDK session handoff owner | remove; callers use the resolved binding `adapterId` directly instead of a second accessor |
| `TransportAdapterDescriptor` | local adapter descriptor containing `adapterId + transportHint` | keep; `adapterId` is transport-internal endpoint handle supplied by adapter bootstrap / registration; future strategy attributes belong in a later adapter topology roadmap, not node registry |
| `TransportRegistrationResolver` | local worker registration adapter resolver | narrow; registration may validate transport hint availability, but realtime registration must not require a delivery handoff `adapterId`; the accepted session endpoint attaches local adapter id |
| `WorkerClientOperations.getWorkerAdapterId(...)` | worker-facing SDK surface returning transport runtime adapter id | delete or move behind an operator/transport diagnostics surface; not part of worker runtime interaction |
| `WorkerAdapter.adapterId()` default | SPI default turns protocol label into adapter identity | delete default or require binding-provided endpoint handle; adapters/tests must be explicit |
| `PollingWorkerAdapter.PROTOCOL` as adapter id | protocol label used for polling queue/lease/evidence identity | replace delivery identity usage with binding-supplied adapter id; protocol remains only adapter kind/transport hint |
| `RedisTransportDeliveryCommandHandoff.localTransportNodeId` | misnamed local ready/inflight consumer key | rename/retarget to adapter id consumer key; support one or more local adapter ids if one JVM hosts multiple endpoints |
| `DeliveryCommandConsumerClaim.queueConsumerKey` | handoff-private selected-worker consumer owner | keep field name only as storage residue; value must be adapter id |
| `AdapterDispatchLane.forTransportNode(...)` | older node-targeted lane vocabulary | delete if unused, otherwise replace owner |
| `AdapterEndpoint.transportNodeId` | endpoint DTO residue | delete with `AdapterEndpoint` residue |
| `TransportEndpointLeaseMetadata.runtimeNodeId` | diagnostic endpoint lease field | remove from metadata/view/codec |
| `TransportEndpointLeaseConsumerEvidence` | hot-path consumer evidence | already does not carry node id; keep |
| `adapterId` / `TransportBinding` / `addWebSocketAdapter(...)` / `socketAdapter(...)` | concrete adapter endpoint handle and multi-adapter support | keep; validate local duplicates; tests and distributed examples supply distinct handles; do not turn this into public worker/engine input |
| archived roadmaps | historical node-targeted context | leave archived; do not execute |

Acceptance:

- Inventory is updated if the actual implementation finds additional production
  call sites.
- Production and test-only call sites are separated.
- Public worker API, engine/starter assignment, adapter binding config, and
  transport-internal runtime usage are separated before implementation.
- Any use that still claims to need node identity names its caller and owner
  before implementation proceeds.
- Inventory explicitly verifies that adapter binding identity and handoff
  consumer identity are converging to the same value: `adapterId`.
- `adapterNodeId` hits are not fixed here unless they are accidentally used as
  transport delivery executor identity; otherwise they are left to the worker /
  control-plane surface roadmap.
- Guard candidates are updated from this allowlist before Phase 1 starts.

## Phase 1 - Runtime-Wide Consumer Key And Endpoint Lease Decoupling

Goal: remove `transportNodeId` as the source for live delivery internals before
deleting public config and node registry.

Scope:

- stop generating an independent `deliveryCommandConsumerKey`
- make the assigned-delivery queue-consumer key equal the resolved `adapterId`
- remove runtime-wide `deliveryCommandConsumerKey` fields from
  `TransportAdapterBootstrapContext` and `TransportRuntimeRegistry`
- make pull-session consumer ownership binding-scoped: the selected
  `TransportBinding.adapterId` is the only source for queue consumer ownership
- make `PollingWorkerAdapter` binding-scoped: it must receive or derive the
  local binding adapter id and use it for `adapterId()`, consumer evidence,
  endpoint lease driver id, handoff command references, and final-hop delivery
  routing. `PROTOCOL` remains only `protocol()` / transport hint vocabulary.
- change the delivery-command handoff factory/listener contract so runtime
  composition can construct polling for the local adapter id set
- preferred implementation: store Redis handoff options in `TransportConfig`
  and instantiate `RedisTransportDeliveryCommandHandoff` / listener ownership
  inside `TransportRuntimeComposition`, where the local adapter ids are known
- the Redis handoff constructor or factory should receive
  `Collection<String> localAdapterIds` (or an equivalent typed local adapter
  endpoint set) and poll ready/inflight keys with a bounded fair loop
- explicitly forbid `redisDeliveryCommandHandoff(...)` closures from reading
  `config.getTransportNodeId()`
- remove `deliveryCommandConsumerKey` from `TransportAdapterBootstrapContext`;
  the context may expose the consumer registry, but each adapter bootstrap must
  claim consumer evidence with its own configured/binding adapter id
- remove `deliveryCommandConsumerKey` from `TransportRuntimeRegistry`; when it
  resolves a `TransportBinding`, the binding's `adapterId` is the consumer key
- remove `deliveryCommandConsumerKey` from `ResolvedPullWorkerTransport`; do
  not keep a compatibility accessor that hides `adapterId` under a second name
- use the resolved binding `adapterId` as the queue-consumer key when
  constructing `PullWorkerSession`, adapter session managers, and Redis handoff
  claims
- rename `RedisTransportDeliveryCommandHandoff.localTransportNodeId` and its
  constructors to adapter-owned queue consumer key vocabulary
- if one runtime hosts multiple adapter ids, make the handoff poller drain a
  bounded set of local adapter-id queues; do not split into one unrelated
  process/node lifecycle per adapter
- define profile semantics explicitly: adapter ids are registration-owned
  runtime endpoint handles; this roadmap does not require cross-restart
  stability and does not solve durable recovery of stale consumer queues by
  reintroducing `transportNodeId`
- keep `DeliveryCommandConsumerClaim.queueConsumerKey` as the storage-level name
  for now, but document that its value is the adapter id
- remove `runtimeNodeId` from `TransportEndpointLeaseMetadata`
- remove `runtimeNodeId` from `TransportEndpointLeaseViewRecord`
- remove runtime-node constructor parameters and getters from in-memory and
  Redis endpoint lease stores
- update Redis endpoint lease serialization to stop writing/reading
  `runtimeNodeId`
- update websocket/socket/polling tests that assert runtime-node metadata
- prove multiple adapter bindings in one runtime get distinct adapter-id
  consumer ownership
- prove two active transport consumer runtimes may serve the same
  `deliveryBucketId` through distinct registration-supplied adapter endpoint
  handles
- prove tests/distributed examples do not rely on a shared protocol constant
  such as `websocket`, `socket`, or `polling` as the only adapter endpoint id
- leave cluster-wide duplicate adapter-id CAS to a later adapter topology
  roadmap if adapter strategy needs stable endpoint identity

Target endpoint lease view:

```text
deliveryBucketId
workerId
endpointDriverId
endpointAddress
sessionHandle
endpointLeaseId
```

Acceptance:

- Endpoint lease claim/heartbeat/release semantics still match on
  `deliveryBucketId + workerId + endpointDriverId + endpointAddress +
  sessionHandle + endpointLeaseId`.
- Consumer evidence remains unchanged unless implementation proves a narrower
  field is possible.
- Redis and in-memory endpoint lease stores expose the same endpoint lease view
  shape.
- No endpoint lease view or metadata class contains `runtimeNodeId` or
  `transportNodeId`.
- Redis delivery command handoff no longer has a `localTransportNodeId` field,
  constructor parameter, or test fixture vocabulary.
- `TransportConfig` no longer stores delivery-command handoff as a no-arg
  supplier that can close over `getTransportNodeId()`.
- `MassApplicationBuilder.redisDeliveryCommandHandoff(...)` does not read
  `config.getTransportNodeId()` directly or indirectly.
- `TransportAdapterBootstrapContext` and `TransportRuntimeRegistry` no longer
  contain a single runtime-wide `deliveryCommandConsumerKey`.
- `ResolvedPullWorkerTransport` does not preserve a second stored consumer id;
  its queue consumer ownership is the selected binding `adapterId`.
- `PollingWorkerAdapter` no longer uses `PROTOCOL` as assigned-delivery adapter
  identity, endpoint lease driver id, or consumer evidence adapter id.
- Redis handoff supports a bounded local adapter-id set for one runtime process
  and does not require one JVM/process identity to select ready queues.
- Adapter ids are supplied by adapter bootstrap / registration and are not
  supplied by engine/starter assignment or worker APIs.
- Cross-restart adapter id stability is not required for Phase 1 proof; stale
  ready/inflight residue is handled by existing delivery timeout,
  retention/cleanup, or engine compensation paths, not by a revived node id.
- Distributed handoff tests still prove two transport consumers cannot
  destructively claim each other's selected-worker commands.
- Distributed handoff tests prove same-bucket active multi-consumer delivery:
  commands for worker A go only to the adapter id claimed by worker A's current
  evidence.
- No new cluster-wide adapter/node registry is introduced to solve duplicate
  adapter ids in this slice.
- Multi-adapter application assembly still registers distinct adapter bindings
  and dispatch listener still resolves final-hop adapters by stored `adapterId`.

## Phase 2 - Remove Node Registry And Bootstrap Surface

Goal: delete generic transport-node heartbeat from runtime and assembly after
all live internals no longer depend on `transportNodeId`.

Scope:

- delete `TransportNodeRegistry`, `TransportNodePresence`,
  `TransportNodeState`, `TransportNodeRegistryHeartbeat`,
  `RedisTransportNodeRegistry`, and `InMemoryTransportNodeRegistry` if no
  production caller remains
- keep startup adapter bootstrap and descriptor resolution; this is not the
  slice that removes adapter binding configuration
- delete `WorkerAdapter.adapterId()` default protocol fallback, or change the
  adapter runtime binding contract so endpoint handles are supplied explicitly
  outside the adapter SPI. This roadmap must not leave a path where `protocol()`
  silently becomes `adapterId`.
- remove `transportNodeRegistryFactory`, `transportNodeId`, and related builder
  methods from SDK/starter config
- remove `MassSdk.TransportOptions.transportNodeId(...)`
- remove server `mass.transport.node-id` property and builder wiring
- remove `redisTransportNodeRegistry(...)` builder methods
- remove node-registry wiring from `redisDistributedChannels(...)`
- remove delivery handoff `adapterId` from worker-facing registration/session
  APIs where it is only used to disambiguate realtime endpoints
- for realtime workers, registration records only worker identity, delivery
  bucket/group, transport hint, and worker attributes; adapter id is attached
  later by the WebSocket/socket endpoint that accepts the session
- do not introduce a generic public endpoint label in this roadmap unless a
  concrete caller proves the physical endpoint cannot identify the local
  adapter binding. A vague label would become `adapterId` under another name.
- for embedded/local polling sessions, adapter resolution may remain local to
  runtime assembly; it must not require a public worker registration `adapterId`
- remove `WorkerClientOperations.getWorkerAdapterId(...)` from the worker
  runtime interaction surface, or move the same information to an explicit
  operator/transport diagnostics view. Do not keep adapter id as a worker-facing
  getter.
- update distributed transport tests that currently assert node heartbeat or
  use node ids to model handoff polling
- remove Redis namespace constants for transport nodes if they become unused

Acceptance:

- SDK/server startup no longer requires or accepts `transportNodeId`.
- No production source imports `com.xa.mass.transport.runtime.node.*`.
- No production source constructs `TransportNodeRegistryHeartbeat`.
- Split runtime roles still compile and start without node registry wiring.
- Redis distributed helper still wires delivery-command, result-inbox,
  delivery-failure, endpoint-lease, and delivery stores as needed, but no
  longer wires `nodes`.
- Worker-facing registration/session APIs do not require delivery handoff
  `adapterId` as an endpoint selector. Realtime adapter id is chosen by the
  accepted WebSocket/socket endpoint; polling adapter id is resolved inside
  local runtime assembly.
- Tests that previously used node ids for delivery handoff are rewritten around
  adapter id plus selected-worker consumer evidence.
- The dual/multiple realtime adapter example remains valid and covered by a
  compile or focused startup test.
- At least one Spring context proof covers the server profile affected by
  removing `mass.transport.node-id`. For Redis endpoint lease wiring, the
  durable-local profile must start with Redis endpoint lease store properties
  and without the old node-id property.
- Direct `ReflectionTestUtils` tests may remain as support tests, but they are
  not sufficient proof for server `@Value` / profile wiring changes.
- `TransportRegistrationResolverTest`, Java SDK worker session tests, and
  external realtime registration integration tests prove realtime worker
  registration no longer requires a public delivery handoff `adapterId`.
- `WorkerClientOperations` no longer exposes `getWorkerAdapterId(...)`.
- `WorkerAdapter.adapterId()` no longer defaults to `protocol()`, and built-in
  adapter/binding tests prove endpoint handles are explicit.

## Phase 3 - Remove DTO And Lane Residue

Goal: delete node-shaped DTO residue from transport delivery surfaces.

Scope:

- delete `AdapterEndpoint` if it has no remaining non-assigned-delivery owner
- delete `AdapterDispatchLane` if it only models `adapterId + transportNodeId`
- remove node-id assertions from architecture guard tests and replace them with
  explicit no-node-id guards
- update `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md` if its
  residue list becomes closed by this roadmap
- update `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md` so it no
  longer recommends retaining `runtimeNodeId`

Acceptance:

- `transport_api` main model/channel/lease packages do not expose
  `transportNodeId` or `runtimeNodeId`.
- Assigned delivery and endpoint lease tests do not preserve node-targeted
  vocabulary as fixture names or helper method names.
- Remaining `transportNodeId` hits, if any, are only in archived docs or this
  active roadmap until archive.

## Phase 4 - Owner Docs, Guards, And Redis Cleanup

Goal: make the deletion hard to regress.

Scope:

- update `transport/AGENTS.md`
- update `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- update SDK/server docs if they mention `transportNodeId`
- remove `RedisTransportNamespaces.NODES` if unused
- update `doc/PROOF_REGISTRY.md`
- add or update architecture guards
- document Redis key cleanup for old node registry keys

Guard candidates:

```text
transport id exposure guard:
  DeliveryCommand, DeliveryCommandBatch, AdapterDispatchRequest, DispatchOutcome
  do not expose transportNodeId/runtimeNodeId/adapterId as assignment facts
  worker-facing registration/session APIs do not require delivery handoff adapterId
  WorkerClientOperations does not expose getWorkerAdapterId
  adapterNodeId is not used as transport delivery executor identity

transport main/test guard:
  no production imports of com.xa.mass.transport.runtime.node

transport API guard:
  no transport_api main symbol contains transportNodeId or runtimeNodeId
  WorkerAdapter.adapterId does not default to protocol()

runtime registry guard:
  TransportAdapterBootstrapContext and TransportRuntimeRegistry do not contain
  a runtime-wide deliveryCommandConsumerKey field
  ResolvedPullWorkerTransport does not store a second consumer id distinct from
  adapterId
  PollingWorkerAdapter does not use PROTOCOL as assigned-delivery adapter id

SDK/server guard:
  no public builder/config/server property contains transportNodeId
  adapterId builder methods are allowed only for adapter binding configuration
  worker-facing registration/session APIs do not expose delivery handoff
  adapterId as the endpoint selector

assigned delivery guard:
  DeliveryCommand, DeliveryCommandBatch, AdapterDispatchRequest, DispatchOutcome
  do not expose transportNodeId/runtimeNodeId/AdapterEndpoint

multi-adapter guard:
  WebSocket + supplemental WebSocket + socket adapter registration remains
  available and does not require transportNodeId

multi-consumer guard:
  distributed handoff tests include two consumers with distinct adapterId values
  serving the same delivery bucket, and tests/configuration do not collapse to
  one shared static protocol name
```

Redis cleanup:

- old node-registry keys such as `xa:mass:transport:nodes:*` are obsolete after
  Phase 2
- old endpoint lease values containing runtime-node fields are obsolete after
  Phase 1
- no compatibility reader is required for this pre-release internal runtime
  unless a later production migration decision says otherwise

Acceptance:

- Active docs do not describe `transportNodeId` as current transport truth.
- Active docs describe adapter id as the adapter endpoint / queue-consumer
  identity, not as protocol kind.
- Guards fail if production code reintroduces node registry or node-id DTO
  fields.
- Redis manifest/baseline no longer lists transport-node registry keys.
- SDK/server docs keep multi-adapter examples but do not mention
  `transportNodeId`.

## Suggested Implementation Order

1. Run Phase 0 allowlist/inventory and update this roadmap if new production
   owners appear.
2. Add or update architecture guards from the Phase 0 allowlist before moving
   runtime code.
3. Retarget Redis handoff / adapter bootstrap / pull session queue-consumer
   wiring so the consumer key is the resolved adapter id.
4. Remove runtime-node fields from endpoint lease metadata and Redis codecs.
5. Remove SDK/server public `transportNodeId` config and node-registry
   heartbeat assembly.
6. Delete node registry implementations and tests.
7. Remove node registry namespaces from distributed Redis helper defaults.
8. Delete `AdapterEndpoint` / `AdapterDispatchLane` residue where unused.
9. Update owner docs and guards.
10. Run residue scan, then archive this roadmap after completion criteria are
   met.

## Verification Candidates

Compile:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
```

Focused tests:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportEndpointLeaseStoreContractTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,RedisTransportDeliveryCommandHandoffTest,TransportConvergenceArchitectureGuardTest,RedisTransportNamespacesTest
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassApplicationDistributedTransportTest,TransportConfigTest,MassSdkTest
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportRegistrationResolverTest,TransportRuntimeRegistryTest
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WebSocketWorkerSessionTest,PollingWorkerSessionTest
./mvnw -q -pl xa-mass-server -am test -Dtest=ExternalWorkerRealtimeRegistrationIntegrationTest
./mvnw -q -pl xa-mass-server -am test -Dtest=XaMassServerApplicationTransportRuntimeConfigTest,ServerDurableLocalProfileContextTest
```

Residue scans:

```bash
rg -n "transportNodeId|TransportNodeRegistry|TransportNodePresence|TransportNodeRegistryHeartbeat|runtimeNodeId|AdapterDispatchLane|AdapterEndpoint" transport sdk xa-mass-server doc roadmap -g "*.java" -g "*.md"
rg -n "RedisTransportNamespaces\\.NODES|:nodes|transport:nodes" transport sdk xa-mass-server doc roadmap -g "*.java" -g "*.md"
rg -n "localTransportNodeId" transport sdk xa-mass-server -g "*.java"
rg -n "deliveryCommandConsumerKey" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrapContext.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportRuntimeRegistry.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/ResolvedPullWorkerTransport.java
rg -n "worker adapterId must be set when transportHint 'realtime' is used|worker adapterId must be set before runtime start" transport sdk xa-mass-server -g "*.java"
rg -n "adapterNodeId" transport/transport_api transport/transport_runtime transport/websocket-adapter transport/socket-adapter transport/polling-adapter -g "*.java"
rg -n "getWorkerAdapterId" sdk/xa-mass-embedded-sdk/src/main/java sdk/xa-mass-embedded-sdk-api/src/main/java sdk/xa-mass-java-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
rg -n "default String adapterId\\(|return protocol\\(\\)" transport/transport_api/src/main/java/com/xa/mass/transport/worker transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java -g "*.java"
rg -n "deliveryService\\.(enqueue|pollItemResult)\\(PROTOCOL|TransportEndpointLease(Claim|Heartbeat|Release)\\([^\\n]*PROTOCOL" transport/polling-adapter/src/main/java -g "*.java"
```

The exact focused tests may need adjustment after Phase 0, especially if a test
currently proves only the removed node registry behavior.

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- Phase 0 allowlist is reflected in guards, verification, and owner docs
- no production code exposes or requires `transportNodeId`
- no production code exposes or requires `runtimeNodeId`
- `TransportNodeRegistry` and its storage implementations are deleted
- endpoint lease metadata/view does not carry process-node identity
- Redis delivery-command handoff uses adapter id as queue-consumer ownership,
  not `localTransportNodeId`
- delivery-command handoff construction is adapter-id-aware and cannot close
  over public `transportNodeId` config
- no runtime-wide `deliveryCommandConsumerKey` remains on transport bootstrap
  context, runtime registry, or resolved pull transport, including accessor
  methods that would preserve it as a second name
- adapter id ownership semantics are documented and tested: adapter bootstrap /
  registration supplies the handle, the handle is not exposed to engine/starter
  assigned delivery, and cross-restart stability is not part of this roadmap's
  completion gate
- polling adapter delivery identity uses a binding-supplied adapter id, not
  `PollingWorkerAdapter.PROTOCOL`
- `WorkerAdapter.adapterId()` cannot silently default to `protocol()`
- SDK/server public config no longer has `transportNodeId`
- worker-facing registration/session APIs no longer require delivery handoff
  `adapterId` as a public endpoint selector
- worker-facing SDK surfaces no longer expose `getWorkerAdapterId(...)`; any
  remaining adapter id visibility is explicit operator/transport diagnostics
- multi-adapter registration still works through adapter bindings and active
  adapter endpoint handles
- same-bucket multi-consumer delivery through distinct active adapter endpoint
  handles is covered by a focused handoff test or contract test
- no new cluster-wide node/adapter registry is introduced solely to police
  duplicate adapter ids in this roadmap
- active transport owner docs describe adapter/session lease facts without
  generic node identity
- active transport owner docs describe any remaining `queueConsumerKey` /
  `deliveryCommandConsumerKey` names as transitional storage names whose value
  is adapter id
- guards prevent reintroducing node-id delivery or lease metadata
- residue scan shows only archived historical docs or this roadmap, and this
  roadmap is archived after owner docs are updated
