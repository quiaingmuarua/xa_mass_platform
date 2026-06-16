# Transport Node Id Removal Convergence Roadmap

Status: archived; completed on 2026-06-16.

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
deliveryQueueKey                   = storage / batching queue derived from deliveryBucketId
adapterId                          = optional adapter endpoint label / future extension metadata
adapterKind / transportHint        = protocol / endpoint kind
deliveryCommandConsumerKey         = legacy node-targeted handoff name; target removed
sessionHandle / endpointLeaseId    = adapter-local session lease facts
```

Adapter endpoint handle rule for this roadmap:

```text
adapterId = supplied by adapter bootstrap / registration
```

The value may be chosen by the adapter registration path, for example
`ws-public`, `ws-internal`, `socket-edge`, or a test label. Transport treats it
as endpoint metadata / local adapter binding metadata. It is not an
engine/starter assignment fact, not a worker public API parameter, not a durable
placement strategy key, not a queue selector, not a consumer key, and not a
replacement for `transportNodeId`.

If a future deployment needs richer adapter placement constraints, they should
be expressed by a later adapter strategy/topology roadmap, not by generic
transport-node routing identity. This roadmap does not implement adapter
attributes, adapter placement, or adapter capacity strategy.

Multi-instance reservation:

- current embedded mode may keep one transport runtime in the same JVM that
  hosts multiple adapter endpoints
- an adapter endpoint may expose an `adapterId` label supplied by its bootstrap /
  registration, but delivery correctness must not depend on that label
- future distributed mode may run many transport consumer JVMs; the current
  mechanism should need selected-worker endpoint/session lease evidence, not a
  durable adapter topology identity
- business sharing happens through `workerGroupId` / `deliveryBucketId`; many
  adapter ids may serve the same bucket
- `adapterId` is not the protocol kind. `websocket`, `socket`, and `polling`
  belong in `adapterKind` / `transportHint`
- worker APIs bind by delivery bucket, worker id, transport hint, and session
  evidence; they do not receive adapter ids, connection ids, or node ids
- stable cross-restart adapter identity, adapter placement policy, adapter
  capacity strategy, and adapter topology CAS are deferred until a concrete
  adapter strategy caller exists
- tests may use distinct adapter ids for readability, but the transport
  mechanism must not require adapter-id uniqueness for delivery correctness

Scope compression decision:

- do not create a second top-level roadmap for `adapterId` / `transportNodeId`
  leakage
- add a front-loaded exposure allowlist phase in this roadmap instead
- remove `transportNodeId` / `runtimeNodeId` as process-node identity
- keep `adapterId` only where it is adapter binding metadata, final-hop local
  implementation metadata, or bounded diagnostics
- do not pull `adapterNodeId` into this roadmap. `adapterNodeId` belongs to
  worker/resource control-plane declaration surfaces and should be handled by a
  worker-runtime or external-surface roadmap if it remains too public.

## Before-Convergence Observations

These observations describe the pre-convergence implementation shape. They are
kept here only to explain why the roadmap exists and must not be read as
current implementation truth after this roadmap lands.

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
  `TransportRuntimeComposition` can derive the real delivery queue / session
  lease wiring from resolved runtime state.
- `RedisTransportDeliveryCommandHandoff` stores that value as
  `localTransportNodeId`, but uses it as the local delivery-command consumer
  key for ready/inflight claim. This is a naming and ownership problem: the
  runtime should not replace it with adapter-owned queue consumer keys. The
  handoff queue should be bucket-derived `deliveryQueueKey`; each item carries
  `selectedWorkerId` as a demux and final-hop correctness constraint.
- `TransportAdapterBootstrapContext`, `TransportRuntimeRegistry`,
  `ResolvedPullWorkerTransport`, adapters, and `PullWorkerSession` used the
  transitional name `deliveryCommandConsumerKey`. The source of that key was
  `TransportConfig.transportNodeId`; the target shape has no shared
  runtime-wide key field. Target delivery correctness comes from
  `deliveryBucketId + selectedWorkerId`, and queue placement comes from the
  bucket-derived delivery queue.
- `TransportRuntimeComposition.resolveTransportAdapterBootstraps()` builds
  adapter bootstraps from built-in and supplemental configuration. Current code
  enforces unique adapter ids only inside the local runtime. That validation may
  remain as a local binding-map constraint, but it must not become a delivery
  correctness rule or distributed queue ownership rule.
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
  endpoint/session lease evidence, not adapter-id queue ownership.
- `WorkerClientOperations.getWorkerAdapterId(...)` is still on the external
  worker runtime interaction surface and returns the transport runtime adapter
  id. This is classified as worker-facing adapter-id leakage, not a stable
  worker API.
- `WorkerAdapter.adapterId()` currently has a default implementation that
  returns `protocol()`. That makes protocol labels such as `websocket`,
  `socket`, or `polling` silently become endpoint handles when an adapter does
  not override the method.
- `PollingWorkerAdapter` used to let its protocol / adapter id shape delivery
  enqueue/poll storage and selected-worker consumer evidence. That was not just
  a store-key naming concern: it made handoff / final-hop delivery depend on an
  adapter-shaped identity. The target keeps adapter id only as endpoint driver
  metadata.
- `TransportRuntimeRegistry` resolves final-hop adapters from local
  `TransportBinding` entries keyed by adapter id. It is a local runtime registry,
  not a cluster-wide adapter discovery service. In the target shape, local
  adapter lookup is an implementation detail behind endpoint/session lease
  delivery, not a distributed handoff consumer identity.
- `TransportRuntimeComposition` also passes `transportNodeId` into the default
  endpoint lease store. Endpoint lease metadata/view therefore still carry
  `runtimeNodeId` as diagnostic payload.
- Assigned delivery no longer needs a node target.
  `TransportAssignedDeliverySubmitter` groups by
  `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)` and offers
  `DeliveryQueueOffer(deliveryQueueKey, commands)`.
- `DeliveryCommandBatch` no longer carries `targetTransportNodeId`; it only
  carries `deliveryQueueKey`, handoff-owned references, and command items.
- `TransportDeliveryCommandListener` currently resolves the final-hop adapter
  from handoff-private command references. It does not require
  `transportNodeId`, but target final-hop delivery should resolve through
  selected-worker endpoint/session lease rather than adapter id queue ownership.
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
- `adapterId` as optional adapter endpoint metadata supplied by adapter
  bootstrap / registration
- adapter/session endpoint leases keyed by `deliveryBucketId + workerId`
- selected-worker consumer evidence for delivery-command handoff
- delivery queue selection from `deliveryBucketId` / `deliveryQueueKey`
- selected-worker demux and final-hop correctness after bucket queue drain
- delivery queues, handoff claim/ack/requeue, and dispatch outcomes
- local adapter descriptor validation where current in-memory registries still
  require a unique local binding label
- adapter-local diagnostics that explain live connection/session behavior

### Transport Must Not Own

- worker lifecycle or reachability truth
- scheduling, reassignment, retry, or compensation decisions
- generic process-node identity as a delivery target
- a second routing plane based on `transportNodeId + adapterId`
- adapter id as delivery queue key, consumer key, correctness identity, or
  replacement process-node identity
- a public or operator-required id for delivery-command consumer internals
- a cluster-wide adapter registry under a renamed node concept
- a first-slice cluster-wide duplicate adapter registry; adapter labels are not
  delivery correctness identities, so duplicate policing is not a delivery
  executor concern unless a later adapter topology roadmap proves it
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

Do not replace `transportNodeId` with `adapterId`.

Assigned delivery should use `deliveryBucketId` to derive queue storage
placement. Each queued command carries `selectedWorkerId` as an item-level
delivery constraint used by the adapter dispatcher to demux and enforce
final-hop correctness. Existing
`deliveryCommandConsumerKey` / `queueConsumerKey` names are legacy
node-targeted vocabulary and should be removed from the assigned-delivery
mainline or reduced to private claim metadata that is never minted from
`adapterId`, `transportNodeId`, connection id, or route key:

- `adapterId` is supplied by adapter bootstrap / registration. The registration
  owner may choose a human-readable handle or a generated value, but assigned
  delivery must not depend on the minting strategy.
- `adapterId` is not a worker session, connection, delivery bucket, queue,
  consumer, or selected-worker identity.
- cross-restart adapter identity is not a current delivery-executor contract.
  If adapter strategy later needs stable endpoint identity, that belongs in a
  separate adapter topology roadmap.
- a process that hosts multiple adapter endpoints may expose multiple adapter
  labels, but those labels do not become handoff consumer identities.
- `deliveryCommandConsumerKey` must not be generated independently from
  runtime/node/process state, and must not be retargeted to `adapterId`.
- `deliveryCommandConsumerKey` must not remain as a runtime-wide field on
  bootstrap context, runtime registry, or resolved pull transport.
- command references should not carry adapter id as queue-consumer metadata.
  If the handoff implementation still needs private claim metadata, it must be
  derived from the selected-worker endpoint/session lease and kept inside the
  handoff store.
- worker APIs, engine/starter assignment, endpoint lease views, and public
  config must not expose a second consumer id.

`adapterId` is not added to `DeliveryCommand`, SDK assignment calls, engine
assignment records, public worker registration, or delivery queue selection. It
may appear only as adapter binding metadata or bounded diagnostics.

Current roadmap decision: do not introduce a new private stable consumer id, do
not revive a node registry to own adapter uniqueness, and do not retarget the
old handoff consumer key to `adapterId`. Remove runtime-wide
`deliveryCommandConsumerKey` state in Phase 1. Redis/in-memory handoff ready /
inflight storage should be keyed by bucket-derived `deliveryQueueKey`; the
queued record carries `selectedWorkerId`. Any private in-flight claim token is
not part of the delivery address and must not force a selected-worker physical
queue split in this roadmap.

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
  endpointLeaseId
  sessionHandle / local endpoint handle
  lease deadline

Endpoint lease diagnostic view
  deliveryBucketId
  workerId
  endpointDriverId
  endpointAddress
  sessionHandle
  endpointLeaseId

Adapter binding registry
  adapterId metadata
  adapterKind / transportHint
  protocol/server configuration
  no runtime-wide deliveryCommandConsumerKey

Handoff listener / pump
  drains bucket-derived deliveryQueueKey work
  demuxes by selectedWorkerId
  resolves final-hop by selected-worker endpoint/session lease
```

No delivery command, dispatch outcome, worker public API, engine/starter
contract, endpoint lease view, or public config should require or expose
`transportNodeId`.

This decision is scoped to the transport delivery executor boundary, including
polling because polling participates in assigned-delivery consumer evidence and
final-hop command references. Polling may keep its internal store-key type names
temporarily, but selected-worker delivery evidence, handoff references, and
final-hop delivery must not use `PollingWorkerAdapter.PROTOCOL` or `adapterId`
as queue / consumer identity. Endpoint lease records may still carry adapter id
as endpoint driver metadata.

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
adapterId, when present, is endpoint metadata and may be used for diagnostics
selected-worker consumer evidence names the endpoint/session lease, not adapterId
final-hop listener resolves delivery through selected-worker session evidence
handoff listener drains bucket-derived queue and demuxes by selectedWorkerId
```

`adapterId` does not replace the generic node id in assigned delivery. The
replacement is the stricter delivery address:

```text
deliveryQueueKey derived from deliveryBucketId
selectedWorkerId item field / demux constraint
endpoint/session lease for final-hop feasibility
```

If the adapter is websocket, socket, or polling, that belongs in `adapterKind` /
`transportHint`. Tests may still choose readable adapter labels such as
`ws-edge-a`, `ws-edge-b`, or `socket-edge`, but the test must not rely on those
labels for delivery queue selection or selected-worker correctness.

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

The target mechanism requires selected-worker endpoint/session lease evidence,
not distinct active adapter handles, while claiming and draining handoff queues.
A selected worker is deliverable through the active endpoint/session lease that
currently owns that worker's final-hop connection:

```text
deliveryBucketId + selectedWorkerId
  -> deliveryQueueKey = keyFor(deliveryBucketId)
  -> bucket queue entry carrying selectedWorkerId
  -> endpoint/session lease evidence
       endpointLeaseId / sessionHandle
       optional adapter metadata
  -> local final-hop session manager
```

This preserves multi-instance capability without exposing a node identity:

- `TransportRegistrationResolver` may keep validating local runtime descriptors
  while current registries need it, but global duplicate `adapterId` CAS is not
  required for this slice because adapter id is not delivery correctness.
- Distributed producer routing uses bucket-derived queue placement and
  selected-worker endpoint/session evidence, not a node table and not a stored
  adapter id.
- A worker connection can move between adapter endpoints by replacing endpoint
  lease / consumer evidence. Transport does not need to name the old adapter id,
  new adapter id, or JVM as a public target.
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
  -> adapter registration supplies / resolves adapter metadata
  -> endpoint attributes + lease evidence
  -> transport adapter topology diagnostics, not assigned-delivery routing

engine
  -> assigned delivery facts
  -> deliveryBucketId + selectedWorkerId + opaque payload / correlation
  -> transport delivery executor
```

Owner split:

- adapter process owns protocol server/client lifecycle, connection/session
  manager, local endpoint health, and any adapter-local capacity diagnostics.
- transport owns endpoint/session lease expiry, bucket-derived delivery queues,
  selected-worker final-hop delivery evidence, and delivery executor mechanics.
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
adapterId = optional adapter endpoint metadata / operator handle
adapterKind / transportHint = protocol / endpoint kind
deliveryBucketId / workerGroupId = business delivery bucket
selectedWorkerId = assigned execution identity
deliveryQueueKey = derived from deliveryBucketId
```

More precisely for this roadmap:

```text
adapterId = registration-supplied endpoint metadata, not queue or consumer identity
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
- Supersedes earlier broad internal-id direction that kept `transportNodeId` by
  renaming it to `runtimeNodeId`. The stronger decision is deletion from
  public/config/endpoint views, while assigned delivery is keyed by delivery
  bucket and selected worker rather than adapter id.
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
- Do not make duplicate `adapterId` values a delivery correctness concern.
  Local duplicate validation may remain only where the current local registry
  implementation cannot distinguish bindings otherwise.
- Do not change worker scheduling, worker-runtime reachability, or engine
  assignment behavior.
- Do not change routeKey, deliveryBucketId, or selectedWorkerId semantics.
  `adapterId` is narrowed by this roadmap to adapter metadata / diagnostics,
  not queue or consumer selection.
- Do not collapse multiple adapter instances into one default adapter.
- Do not derive queue-consumer ownership from adapter id, route key, connection
  id, endpoint lease id, or node id. Assigned delivery should use
  bucket-derived queue placement plus selected-worker demux/correctness; any private
  claim token stays inside the handoff implementation.
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

1. assigned-delivery queue ownership (`deliveryBucketId`)
   plus selected-worker item demux
2. adapter binding / multi-adapter registration metadata (`adapterId + adapterKind`)
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
- multi-consumer assumptions where current tests use `node-1` / `node-2` or
  adapter labels as fake queue-consumer keys; those should become
  bucket queue plus `selectedWorkerId` demux proof
- `adapterNodeId` occurrences are classified only to mark them out of scope;
  do not clean them up in this roadmap

Transport id exposure allowlist:

| Id / Term | Allowed In This Roadmap | Forbidden In This Roadmap |
| --- | --- | --- |
| `transportNodeId` | archived docs and this roadmap until completion | production config, SDK/server public builders, delivery handoff, endpoint lease metadata/view, Redis runtime values |
| `runtimeNodeId` | none after endpoint lease cleanup | endpoint lease metadata/view, public DTOs, diagnostics that imply process-node truth |
| `adapterId` | adapter binding config, local transport registry while needed, final-hop implementation metadata, bounded diagnostics | worker registration/session public selector, worker client API, engine/starter assignment, `DeliveryCommand`, dispatch outcome contract, queue key, consumer key, replacement for node registry, protocol-shaped fallback identity |
| `deliveryCommandConsumerKey` / `queueConsumerKey` | temporary legacy vocabulary during migration, only if private to the handoff store and not derived from adapter/node/process ids | runtime-wide bootstrap/registry field, public API field, separately generated id, alias for `adapterId` |
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
| `MassApplicationBuilder.redisDeliveryCommandHandoff(...)` | passes node id as Redis handoff local consumer key | retarget so handoff no longer needs a process/adapter consumer key; queue placement is bucket-derived and correctness is selected-worker |
| `TransportConfig.deliveryCommandHandoffFactory` | no-arg supplier closes over old config state | replace with structured Redis handoff options or composition-owned construction that does not require node/adapter consumer identity |
| `MassApplicationBuilder.redisDistributedChannels(...)` | wires obsolete node registry namespace | remove node registry wiring |
| `MassApplication.startTransportNodeHeartbeat(...)` | registers process-level heartbeat | delete |
| `XaMassServerApplication.transportNodeId` | server bootstrap property and endpoint lease constructor input | delete after endpoint lease no longer needs runtime node |
| `TransportRuntimeComposition.getTransportNodeId()` | source for adapter bootstrap, runtime registry, endpoint lease, handoff | remove; handoff owner is delivery bucket + selected worker, not adapter id |
| `TransportAdapterBootstrapContext.deliveryCommandConsumerKey` | transitional runtime-wide handoff field currently sourced from node id | remove as runtime-wide state; adapter bootstraps publish endpoint/session evidence only |
| `TransportRuntimeRegistry.deliveryCommandConsumerKey` | transitional runtime-wide pull-session consumer owner | remove; resolved pull transport should not expose a second consumer key |
| `ResolvedPullWorkerTransport.deliveryCommandConsumerKey` | internal SDK session handoff owner | remove; callers use selected worker polling / endpoint lease evidence |
| `TransportAdapterDescriptor` | local adapter descriptor containing `adapterId + transportHint` | keep as adapter metadata / local binding description; future strategy attributes belong in a later adapter topology roadmap, not node registry |
| `TransportRegistrationResolver` | local worker registration adapter resolver | narrow; registration may validate transport hint availability, but realtime registration must not require a delivery handoff `adapterId`; the accepted session endpoint attaches endpoint/session evidence |
| `WorkerClientOperations.getWorkerAdapterId(...)` | worker-facing SDK surface returning transport runtime adapter id | delete or move behind an operator/transport diagnostics surface; not part of worker runtime interaction |
| `WorkerAdapter.adapterId()` default | SPI default turns protocol label into adapter identity | delete default or narrow to diagnostics; protocol must not become delivery identity |
| `PollingWorkerAdapter.PROTOCOL` as adapter id | protocol label used for polling queue and selected-worker consumer identity | remove from assigned-delivery queue/consumer identity; protocol remains only adapter kind/transport hint, while adapter id may remain endpoint driver metadata |
| `RedisTransportDeliveryCommandHandoff.localTransportNodeId` | misnamed local ready/inflight consumer key | remove as a required consumer key; Redis handoff should claim bucket queue entries and demux by selectedWorkerId |
| `DeliveryCommandConsumerClaim.queueConsumerKey` | handoff-private selected-worker consumer owner | remove or narrow to private claim metadata; value must not be adapter id |
| `AdapterDispatchLane.forTransportNode(...)` | older node-targeted lane vocabulary | delete if unused, otherwise replace owner |
| `AdapterEndpoint.transportNodeId` | endpoint DTO residue | delete with `AdapterEndpoint` residue |
| `TransportEndpointLeaseMetadata.runtimeNodeId` | diagnostic endpoint lease field | remove from metadata/view/codec |
| `TransportEndpointLeaseConsumerEvidence` | endpoint lease claim/refresh result for final-hop delivery | keep; `endpointDriverId` is endpoint driver metadata only, not queue, handoff consumer, or worker correctness identity |
| `adapterId` / `TransportBinding` / `addWebSocketAdapter(...)` / `socketAdapter(...)` | adapter binding metadata and multi-adapter support | keep as configuration/diagnostic metadata; do not turn this into public worker/engine input or delivery queue ownership |
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
  consumer identity are not converging to the same value. `adapterId` must not
  be the queue / consumer key.
- `adapterNodeId` hits are not fixed here unless they are accidentally used as
  transport delivery executor identity; otherwise they are left to the worker /
  control-plane surface roadmap.
- Guard candidates are updated from this allowlist before Phase 1 starts.

## Phase 1 - Runtime-Wide Consumer Key And Endpoint Lease Decoupling

Goal: remove `transportNodeId` as the source for live delivery internals before
deleting public config and node registry, without replacing it with `adapterId`.

Scope:

- stop generating an independent `deliveryCommandConsumerKey`
- remove runtime-wide `deliveryCommandConsumerKey` fields from
  `TransportAdapterBootstrapContext` and `TransportRuntimeRegistry`
- make assigned-delivery handoff queue ownership bucket-scoped:
  `deliveryQueueKey = keyFor(deliveryBucketId)`. Bucket meaning and bucket
  splitting belong to engine/starter, not transport.
- keep `selectedWorkerId` as a required field on every queued command; it is
  used by the adapter dispatcher for demux and final-hop correctness, not as a
  required physical queue key
- make pull-session ownership selected-worker scoped: polling workers poll by
  selected worker and never by adapter id
- make `PollingWorkerAdapter` stop using `PROTOCOL` or adapter id for
  assigned-delivery queue or selected-worker consumer identity. `PROTOCOL`
  remains only `protocol()` / transport hint vocabulary; adapter id may remain
  endpoint driver metadata.
- change the delivery-command handoff factory/listener contract so runtime
  composition can construct bucket-derived handoff storage without a process,
  node, worker-specific physical lane, or adapter consumer key
- preferred implementation: store Redis handoff options in `TransportConfig`
  and instantiate `RedisTransportDeliveryCommandHandoff` inside
  `TransportRuntimeComposition`, without reading adapter ids for queue
  ownership
- Redis handoff should store ready/inflight by bucket-derived delivery queue.
  The dispatcher drains that queue and demuxes each command by
  `selectedWorkerId`. This roadmap does not require implementing asynchronous
  final-hop delivery; it only fixes the queue identity model so async delivery
  can be added without changing the address contract.
- explicitly forbid `redisDeliveryCommandHandoff(...)` closures from reading
  `config.getTransportNodeId()`
- remove `deliveryCommandConsumerKey` from `TransportAdapterBootstrapContext`;
  the context may expose endpoint/session lease support, but adapter bootstrap
  must not claim a queue using adapter id
- remove `deliveryCommandConsumerKey` from `TransportRuntimeRegistry`
- remove `deliveryCommandConsumerKey` from `ResolvedPullWorkerTransport`; do
  not keep a compatibility accessor that hides a second consumer identity
- construct `PullWorkerSession`, adapter session managers, and Redis handoff
  claims around bucket queue entries plus `selectedWorkerId` endpoint/session
  evidence
- remove or rename `RedisTransportDeliveryCommandHandoff.localTransportNodeId`
  so it is not replaced by `localAdapterId`
- if one runtime hosts multiple adapters, do not create one handoff queue per
  adapter id. The handoff poller drains bucket queues and lets adapter/session
  dispatch logic demux by selected worker.
- define profile semantics explicitly: adapter ids are registration-owned
  metadata only; stale ready/inflight residue is handled by delivery timeout,
  retention/cleanup, or engine compensation paths, not by a revived node id or
  adapter-id queue owner
- remove `DeliveryCommandConsumerClaim.queueConsumerKey` from the mainline, or
  narrow it to private handoff claim metadata that cannot equal `adapterId`
- remove `runtimeNodeId` from `TransportEndpointLeaseMetadata`
- remove `runtimeNodeId` from `TransportEndpointLeaseViewRecord`
- remove runtime-node constructor parameters and getters from in-memory and
  Redis endpoint lease stores
- update Redis endpoint lease serialization to stop writing/reading
  `runtimeNodeId`
- update websocket/socket/polling tests that assert runtime-node metadata
- prove multiple adapter bindings in one runtime do not create adapter-id queue
  ownership
- prove two active transport consumer runtimes may serve the same
  `deliveryBucketId` through selected-worker endpoint/session lease evidence
- prove tests/distributed examples do not rely on adapter id or protocol
  constants such as `websocket`, `socket`, or `polling` as queue owners
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
- Handoff consumer evidence is narrowed to selected-worker endpoint/session
  lease evidence: `deliveryBucketId`, `selectedWorkerId`, `endpointLeaseId`,
  and `leaseExpireAtEpochMillis`. It must not contain adapter id, queue
  consumer id, connection id, route key, or transport node id.
- `TransportEndpointLeaseConsumerEvidence` may include `endpointDriverId`
  only as endpoint lease / final-hop driver metadata. It is not handoff
  consumer identity, queue identity, worker correctness, or public worker API.
- Redis and in-memory endpoint lease stores expose the same endpoint lease view
  shape.
- No endpoint lease view or metadata class contains `runtimeNodeId` or
  `transportNodeId`.
- Redis delivery command handoff no longer has a `localTransportNodeId` field,
  constructor parameter, test fixture vocabulary, or `localAdapterId`
  replacement.
- `TransportConfig` no longer stores delivery-command handoff as a no-arg
  supplier that can close over `getTransportNodeId()`.
- `MassApplicationBuilder.redisDeliveryCommandHandoff(...)` does not read
  `config.getTransportNodeId()` directly or indirectly.
- `TransportAdapterBootstrapContext` and `TransportRuntimeRegistry` no longer
  contain a single runtime-wide `deliveryCommandConsumerKey`.
- `ResolvedPullWorkerTransport` does not preserve a second stored consumer id;
  polling uses selected worker / endpoint lease evidence instead.
- `PollingWorkerAdapter` no longer uses `PROTOCOL` or `adapterId` as
  assigned-delivery queue identity, selected-worker consumer identity, or
  handoff command reference owner. It may still write adapter id as endpoint
  driver metadata on endpoint leases.
- Redis handoff supports bucket-derived queue placement and selected-worker
  demux, and does not require one JVM/process/adapter identity to select ready
  queues.
- Polling pull delivery store uses bucket-scoped physical queues; Redis keys
  are `q:<encodedDeliveryQueueKey>` / `meta:<encodedDeliveryQueueKey>`, and
  `selectedWorkerId` is a queued value demux constraint rather than a
  `worker-index` key component.
- Adapter ids are supplied by adapter bootstrap / registration and are not
  supplied by engine/starter assignment, worker APIs, queue keys, or consumer
  keys.
- Cross-restart adapter id stability is not required for Phase 1 proof; stale
  ready/inflight residue is handled by existing delivery timeout,
  retention/cleanup, or engine compensation paths, not by a revived node id.
- Distributed handoff tests still prove two transport consumers cannot
  destructively claim each other's selected-worker commands.
- Distributed handoff tests prove same-bucket active multi-consumer delivery:
  commands for worker A are demuxed only to worker A's endpoint/session lease,
  not to another selected worker and not to a shared adapter queue.
- No new cluster-wide adapter/node registry is introduced to solve duplicate
  adapter ids in this slice.
- Multi-adapter application assembly still registers distinct adapter bindings
  where currently required, but dispatch listener does not use adapter id as the
  distributed queue selector.

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
  silently becomes delivery identity.
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
  `adapterId` as an endpoint selector. Realtime session evidence is attached by
  the accepted WebSocket/socket endpoint; polling delivery is resolved by
  selected worker and local runtime assembly.
- Tests that previously used node ids for delivery handoff are rewritten around
  deliveryBucketId plus selected-worker consumer evidence.
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
  adapter/binding tests prove adapter labels are not delivery queue selectors.

## Phase 3 - Remove DTO And Lane Residue

Goal: delete node-shaped DTO residue from transport delivery surfaces.

Scope:

- delete `AdapterEndpoint` if it has no remaining non-assigned-delivery owner
- delete `AdapterDispatchLane` if it only models `adapterId + transportNodeId`
- remove node-id assertions from architecture guard tests and replace them with
  explicit no-node-id guards
- update `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md` if its
  residue list becomes closed by this roadmap
- update active owner docs so they no longer recommend retaining
  `runtimeNodeId`

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
  ResolvedPullWorkerTransport does not store a second consumer id
  PollingWorkerAdapter does not use PROTOCOL or adapterId as assigned-delivery
  queue / consumer identity

SDK/server guard:
  no public builder/config/server property contains transportNodeId
  adapterId builder methods are allowed only for adapter binding configuration
  worker-facing registration/session APIs do not expose delivery handoff
  adapterId as the endpoint selector

assigned delivery guard:
  DeliveryCommand, DeliveryCommandBatch, AdapterDispatchRequest, DispatchOutcome
  do not expose transportNodeId/runtimeNodeId/AdapterEndpoint
  deliveryQueueKey is derived from deliveryBucketId
  selectedWorkerId is a required queued-command field and the only worker
  correctness identity
  adapterId is not used as deliveryQueueKey, queueConsumerKey, or result
  partitionKey

multi-adapter guard:
  WebSocket + supplemental WebSocket + socket adapter registration remains
  available and does not require transportNodeId

multi-consumer guard:
  distributed handoff tests include two selected workers in the same delivery
  bucket and prove dispatcher demux prevents cross-worker delivery even when
  adapter labels are identical or irrelevant
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
- Active docs describe adapter id as adapter metadata / diagnostics, not as
  queue-consumer identity or protocol kind.
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
3. Retarget Redis handoff / adapter bootstrap / pull session delivery wiring so
   queue placement is derived from deliveryBucketId and dispatcher correctness
   is enforced by selectedWorkerId.
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
rg -n "queueConsumerKey.*adapterId|adapterId.*queueConsumerKey|localAdapterId|localAdapterIds|ready.*adapterId|adapterId.*ready" transport sdk xa-mass-server -g "*.java"
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
- Redis delivery-command handoff uses deliveryBucketId-derived queue placement
  and selectedWorkerId demux/correctness, not `localTransportNodeId` or adapter
  id
- delivery-command handoff construction cannot close over public
  `transportNodeId` config or adapter-id consumer ownership
- no runtime-wide `deliveryCommandConsumerKey` remains on transport bootstrap
  context, runtime registry, or resolved pull transport, including accessor
  methods that would preserve it as a second name
- adapter id semantics are documented and tested: adapter bootstrap /
  registration may supply metadata, but that metadata is not exposed to
  engine/starter assigned delivery, is not a queue selector, and cross-restart
  stability is not part of this roadmap's completion gate
- polling adapter delivery uses bucket queue entries plus selectedWorkerId
  demux/correctness, not a binding-supplied adapter id or
  `PollingWorkerAdapter.PROTOCOL`
- `WorkerAdapter.adapterId()` cannot silently default to `protocol()`
- SDK/server public config no longer has `transportNodeId`
- worker-facing registration/session APIs no longer require delivery handoff
  `adapterId` as a public endpoint selector
- worker-facing SDK surfaces no longer expose `getWorkerAdapterId(...)`; any
  remaining adapter id visibility is explicit operator/transport diagnostics
- multi-adapter registration still works through adapter bindings and endpoint
  metadata, without making adapter id a delivery address
- same-bucket multi-consumer delivery through selected-worker demux/correctness
  is covered by a focused handoff test or contract test
- no new cluster-wide node/adapter registry is introduced solely to police
  duplicate adapter ids in this roadmap
- active transport owner docs describe adapter/session lease facts without
  generic node identity
- active transport owner docs describe any remaining `queueConsumerKey` /
  `deliveryCommandConsumerKey` names as transitional/private storage names that
  are not adapter id
- guards prevent reintroducing node-id delivery or lease metadata
- residue scan shows only archived historical docs or this roadmap, and this
  roadmap is archived after owner docs are updated
