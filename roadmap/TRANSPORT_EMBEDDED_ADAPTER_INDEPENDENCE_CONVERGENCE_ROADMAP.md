# Transport Embedded Adapter Independence Convergence Roadmap

Status: proposed direction document.

## Summary

The current transport direction is not to implement external or cross-language
adapters now. The current product path is still embedded Java adapters.

The goal of this roadmap is narrower and more important for the next transport
cleanup: make embedded Java adapters an independent runtime flavor instead of
letting Java object registration, session managers, executor callbacks, raw
route helpers, or frame codecs become transport-core truth.

External or cross-language adapters are used only as an architecture pressure
test:

```text
If a fact cannot be stably provided by a cross-language adapter, it must not be
a transport-core contract.

If a fact is needed only by embedded Java runtime assembly, it belongs in the
embedded adapter layer.

If a fact must cross process boundaries, it must be represented as typed
queue / evidence / outcome data, not as Java object wiring.
```

This is a design constraint, not an external-adapter implementation roadmap.

## Current Code Observations

- `transport_runtime` currently contains both transport-core executor
  mechanics and embedded Java adapter assembly.
- Core delivery-executor mechanics live around:
  `TransportDeliveryCommandHandoff`, `DeliveryQueueOffer`,
  `DeliveryCommandBatch`, `TransportDeliveryCommandListener`,
  `TransportAssignedDeliverySubmitter`, `TransportEndpointLeaseStore`,
  `DeliveryCommandConsumerRegistry`, `DispatchOutcome`,
  `TransportDeliveryFailureHandler`, and result ingress envelopes.
- Embedded Java adapter assembly lives around:
  `TransportAdapterBootstrap`, `TransportAdapterBootstrapContext`,
  `TransportAdapterContribution`, `TransportBinding`,
  `TransportRuntimeRegistry`, `TransportRegistrationResolver`,
  `AdapterCommandExecutor`, `ManagedTransportAdapter`,
  `CompositeWorkerEndpointRegistry`, `CompositeWorkerEndpointInspector`,
  and `RawWorkerMessageChannel`.
- `MassApplication` and `TransportRuntimeComposition` assemble both sets in
  one embedded SDK startup path. This is acceptable for the current embedded
  runtime, but it makes it easy for embedded-only facts to drift into core
  contracts.
- Recent adapter-command-executor convergence removed the old `WorkerAdapter`
  mainline and made `AdapterCommandExecutor.dispatch(List<DeliveryCommand>)`
  the local Java final-hop execution seam. That seam is still an embedded Java
  callback, not a cross-process transport contract.
- `TransportBinding` now owns adapter id, transport hint, protocol label, and
  executor binding metadata explicitly. The executor implementation should not
  own adapter metadata.
- Endpoint leases and delivery-command consumer evidence are already closer to
  a future process-boundary shape: they are typed evidence keyed by
  `deliveryBucketId + selectedWorkerId` and endpoint lease identifiers rather
  than Java session objects.
- Result ingress is also close to the desired shape: adapters submit opaque
  `TransportResultIngressEnvelope` values, and transport owns buffer/inbox/ack
  mechanics without parsing task-result correctness.

## Owner Review

Transport core owns delivery executor facts:

```text
delivery command handoff
delivery bucket -> queue address
selected worker delivery constraint
endpoint lease / consumer evidence
claim / ack / requeue consistency
delivery outcome / failure evidence
opaque result ingress envelope
```

Embedded Java adapter layer owns Java runtime wiring facts:

```text
TransportAdapterBootstrap
TransportAdapterContribution
TransportBinding
TransportRuntimeRegistry
AdapterCommandExecutor callback
server/session manager objects
frame codecs
local endpoint indexes
managed adapter lifecycle
diagnostics and raw/manual side-channels
```

Future external adapters, if implemented later, should consume the transport
core through typed data-plane operations:

```text
claim endpoint evidence
refresh / release endpoint evidence
consume delivery commands
ack or release delivery-command claims
emit DispatchOutcome / delivery-failure evidence
submit result ingress envelope
```

They should not need to register a Java object, provide a Java session manager,
instantiate `TransportBinding`, or participate in embedded SDK bootstrap.

## Boundary Decision

`transport_runtime` may continue to contain both core and embedded-support code
for now, but those roles must be explicitly separated in owner rules, package
shape, guards, and tests.

The main boundary is:

```text
transport core = queue / evidence / outcome runtime
embedded Java adapter layer = local Java object assembly and protocol runtime
```

The external-adapter pressure test is mandatory for future transport changes:

- A proposed transport-core field or model is valid only if it can be explained
  as queue data, endpoint evidence, consumer evidence, dispatch outcome,
  failure evidence, or result-ingress envelope.
- A proposed Java object reference, callback, session manager, frame codec,
  server lifecycle, raw channel, or diagnostic inspector must stay in the
  embedded adapter layer.
- A fact may not be copied into a second model just because an adapter needs
  convenience access. The owner must be named, and all other surfaces must be
  projections or references.

## Target Shape

Target mental model:

```text
transport-runtime-core
  DeliveryCommand / DeliveryQueueOffer / DeliveryCommandBatch
  TransportDeliveryCommandHandoff
  TransportDeliveryCommandHandoffPump
  TransportEndpointLeaseStore
  DeliveryCommandConsumerRegistry
  DispatchOutcome / TransportDeliveryFailureEvent
  TransportResultIngressEnvelope channels
  Redis/in-memory implementations for those contracts

embedded-java-adapter-support
  TransportAdapterBootstrap
  TransportAdapterBootstrapContext
  TransportAdapterContribution
  TransportBinding
  TransportRuntimeRegistry
  TransportRegistrationResolver
  AdapterCommandExecutor
  CompositeWorkerEndpointRegistry / Inspector
  ManagedTransportAdapter
  RawWorkerMessageChannel
  RouteEndpointIndex

concrete embedded adapters
  websocket-adapter
  socket-adapter
  polling-adapter
```

This target may start as package and guard separation inside the same Maven
artifact. A physical Maven module split is optional and must not be the first
slice.

## Non-Goals

- Do not implement an external adapter protocol in this roadmap.
- Do not add remote adapter registration, authentication, leasing, discovery,
  or management APIs.
- Do not create a generic `AdapterProcess`, `RemoteAdapter`, or
  `ExternalAdapterRuntime` abstraction without a concrete process-boundary
  contract.
- Do not wrap `AdapterCommandExecutor` in a facade that only forwards to the
  same Java callback.
- Do not preserve old embedded paths as deprecated compatibility aliases.
- Do not move task lifecycle, retry, compensation, worker selection, worker
  lifecycle, or capability truth into transport.
- Do not split Maven modules before package ownership and guards prove the
  dependency direction.

## Do Not Start With

Do not start by designing an external adapter RPC protocol or by moving classes
into new Maven modules.

Start with owner inventory and source guards. The first executable slice should
make it impossible for core delivery models to depend on embedded Java adapter
assembly, while preserving the current embedded Java runtime behavior.

## TEAI-0 Inventory And Classification

Goal:

Classify current `transport_runtime` production classes by owner role.

Scope:

- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime`
- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery`
- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease`
- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/packet`
- SDK assembly callers in `MassApplication`, `TransportRuntimeComposition`,
  `TransportConfig`, and `MassApplicationBuilder`
- Concrete adapter bootstrap callers in websocket/socket/polling adapters

Classification:

| Class family | Current examples | Target owner |
| --- | --- | --- |
| Delivery handoff core | `TransportDeliveryCommandHandoff`, `DeliveryQueueOffer`, `DeliveryCommandBatch`, `TransportDeliveryCommandListener` | transport core |
| Polling pull store core/flavor boundary | `TransportDeliveryStore`, `QueuedPulledDispatch`, Redis/in-memory store | transport core with polling flavor entry |
| Endpoint evidence core | `TransportEndpointLeaseStore`, `TransportEndpointLeasePublisher`, `DeliveryCommandConsumerRegistry` | transport core |
| Result ingress core | `TransportResultIngressEnvelopeCodec`, inbox channels/pumps | transport core |
| Embedded Java assembly | `TransportAdapterBootstrap`, `TransportAdapterContribution`, `TransportBinding`, `TransportRuntimeRegistry` | embedded Java adapter support |
| Embedded Java local endpoint utilities | `CompositeWorkerEndpointRegistry`, `CompositeWorkerEndpointInspector`, `RouteEndpointIndex` | embedded Java adapter support |
| Raw/manual side-channel | `RawWorkerMessageChannel` | embedded diagnostics/manual side-channel |
| Legacy packet helper | `TransportPacketFactory` | review; keep only if result/event wire owner still needs it |

Acceptance:

- Add an inventory section to this roadmap or a sibling
  `TRANSPORT_EMBEDDED_ADAPTER_INDEPENDENCE_INVENTORY.md` if the table becomes
  too large.
- Every `transport_runtime` production class is classified as core,
  embedded-support, concrete adapter helper, storage implementation, or
  residue.
- Production callers from SDK/starter are classified separately from
  transport-core callers.
- Test fixtures are listed separately and must not justify production owner
  leakage.

Verification candidates:

```bash
rg -n "TransportAdapterBootstrap|TransportBinding|TransportRuntimeRegistry|AdapterCommandExecutor|CompositeWorkerEndpoint|RawWorkerMessageChannel" transport/transport_runtime/src/main/java sdk/xa-mass-embedded-sdk/src/main/java
rg -n "TransportDeliveryCommandHandoff|TransportEndpointLeaseStore|DeliveryCommandConsumerRegistry|TransportResultIngressEnvelope|DispatchOutcome" transport/transport_runtime/src/main/java sdk/xa-mass-embedded-sdk/src/main/java
```

## TEAI-1 Package-Level Owner Separation

Goal:

Make the owner split visible in code paths before any module split.

Scope:

- Introduce package-level boundaries or naming that separates:
  - delivery/evidence/result core
  - embedded Java adapter support
  - diagnostics/raw side-channel support
- Update imports in SDK/starter assembly and concrete adapters accordingly.
- Do not change runtime behavior.

Target options:

Option A, package-only split inside `transport_runtime`:

```text
com.xa.mass.transport.runtime.core.delivery
com.xa.mass.transport.runtime.core.lease
com.xa.mass.transport.runtime.core.result
com.xa.mass.transport.runtime.embedded
com.xa.mass.transport.runtime.embedded.diagnostics
```

Option B, lighter first slice:

```text
keep current packages
add architecture guards classifying allowed references
move only the most misleading embedded classes under an embedded package
```

Recommendation:

Start with Option B unless current imports show a clean mechanical package move
with low blast radius. The mechanism matters more than package aesthetics.

Acceptance:

- Core delivery/evidence/result classes do not import:
  `TransportAdapterBootstrap`, `TransportAdapterContribution`,
  `TransportBinding`, `TransportRuntimeRegistry`, `ManagedTransportAdapter`,
  `CompositeWorkerEndpointRegistry`, `CompositeWorkerEndpointInspector`,
  `RawWorkerMessageChannel`, or concrete adapter packages.
- Embedded adapter support may import core contracts.
- Concrete adapters may import embedded support and core contracts.
- SDK/starter assembly may compose both, but must not force core contracts to
  depend on embedded Java classes.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRuntimeRegistryTest,TransportDeliveryCommandListenerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

## TEAI-2 Core Contract Shape Guard

Goal:

Codify the pressure-test rule as failing architecture tests.

Scope:

- Extend `TransportConvergenceArchitectureGuardTest`.
- Add allowlist-style checks for core contracts.
- Update `doc/PROOF_REGISTRY.md` with an explicit
  `transport.embedded-adapter-independence` row.
- Add a short current-rule note to `transport/AGENTS.md`.

Core contract allowlist:

Core delivery/evidence/result records and codecs may contain:

```text
deliveryId / commandId
deliveryBucketId
deliveryQueueKey
selectedWorkerId
payload / correlationRef
endpointLeaseId
endpointDriverId only inside endpoint lease evidence
DispatchOutcome status / retryable / reason / timestamp
result ingress id / payload / correlation / partition key / diagnostics
```

Core contracts must not contain:

```text
TransportAdapterBootstrap
TransportBinding
TransportRuntimeRegistry
AdapterCommandExecutor
WorkerEndpointRegistry
RawWorkerMessageChannel
ManagedTransportAdapter
frame codec types
session manager types
server lifecycle types
route-only endpoint registry types
TransportPacket for assigned delivery
```

Acceptance:

- Guard fails if delivery-command handoff, endpoint lease store, delivery
  store, dispatch outcome, failure event, or result ingress core starts
  importing embedded Java adapter assembly.
- Guard fails if `DeliveryCommand`, `DeliveryCommandBatch`,
  `QueuedPulledDispatch`, `DispatchOutcome`, or failure/result inbox codecs add
  adapter/session/route/server/codec object facts.
- Guard allows `endpointDriverId` only as endpoint evidence/final-hop metadata,
  not as queue selection or command correctness.
- Guard text names the external-adapter pressure test so future agents see why
  the restriction exists.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest
```

## TEAI-3 Embedded Java Adapter Assembly Narrowing

Goal:

Make embedded Java adapter assembly clearly optional to transport core.

Scope:

- Review `TransportRuntimeRegistry`, `TransportBinding`,
  `TransportRegistrationResolver`, `WorkerTransportRuntimeFactory`, and
  `TransportAdapterBootstrap*`.
- Keep `AdapterCommandExecutor` as embedded Java local callback only.
- Ensure the delivery listener uses adapter binding only after endpoint
  evidence resolved an endpoint driver.
- Keep polling/WebSocket/socket adapter behavior unchanged.

Acceptance:

- Core handoff producer and Redis/in-memory delivery-command stores do not
  instantiate or require `TransportRuntimeRegistry`.
- `TransportDeliveryCommandListener` is the only core-adjacent class that may
  bridge endpoint evidence to an embedded Java `AdapterCommandExecutor`, and
  its bridge role is documented and guarded.
- Embedded adapter contribution output remains append-only and explicit.
- No command, queue, endpoint lease, result ingress, or failure event model adds
  `TransportBinding` or Java object references.
- Same executor instance cannot be shared across multiple adapter bindings.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportDeliveryCommandListenerTest,TransportRuntimeRegistryTest,TransportAdapterContributionTest,TransportRegistrationResolverTest,TransportConvergenceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,TransportConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```

## TEAI-4 Diagnostics And Raw Side-Channel Containment

Goal:

Keep diagnostics/raw/manual helpers out of assigned delivery core.

Scope:

- `CompositeWorkerEndpointInspector`
- `RawWorkerMessageChannel`
- `RouteEndpointIndex`
- WebSocket/socket raw route registries
- `MassApplication.sendRawTransportMessage(...)` and diagnostics operations

Acceptance:

- Assigned delivery core cannot call raw route send.
- Raw/manual side-channel remains adapter-scoped and may not mutate task
  lifecycle state.
- Diagnostics snapshots cannot become endpoint evidence or scheduling truth.
- Worker-facing public APIs do not expose `adapterId`, `routeKey`,
  `connectionId`, endpoint lease ids, or delivery queue ids as required
  command-delivery fields.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk -am test -Dtest=TransportConvergenceArchitectureGuardTest,MassSdkTest#sessionDiagnosticsHideTransportInternalIds -Dsurefire.failIfNoSpecifiedTests=false
```

## TEAI-5 Optional Module Split Decision

Goal:

Decide whether package-level separation should become a physical Maven module
split.

Decision gate:

Only consider a module split after TEAI-1 through TEAI-4 prove stable import
direction.

Possible target modules:

```text
transport-runtime-core
transport-embedded-adapter-runtime
transport-redis-runtime
```

Do not split if it only moves files while preserving the same dependencies.

Acceptance:

- A module split proposal names exact packages, artifacts, dependencies, and
  callers.
- It proves the split reduces dependency direction, not just file size.
- It includes Maven test-compile proof for SDK, server, and all adapters.
- If rejected, document why package/guard separation is sufficient.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Current transport docs include the external-adapter pressure test as an owner
  constraint.
- Every production class in `transport_runtime` is classified as core,
  embedded-support, diagnostics/raw side-channel, storage implementation, or
  residue.
- Core delivery/evidence/result code has guards preventing imports of embedded
  Java adapter assembly.
- Embedded Java adapter support is allowed to depend on core contracts, but
  core contracts do not depend on embedded Java adapter support.
- No core delivery command, batch, queue value, failure event, result envelope,
  or endpoint evidence model duplicates adapter/session/raw route facts as a
  second owner.
- Existing embedded Java WebSocket, Socket, and Polling adapters continue to
  pass focused tests.
- SDK/server assembly still compiles and embedded runtime startup tests pass.
- A residue scan confirms stale docs do not describe external/cross-language
  adapter implementation as current behavior.

## Suggested Implementation Order

1. TEAI-0 inventory.
2. TEAI-2 pressure-test guard and doc constraint.
3. TEAI-1 package/import separation where low-risk.
4. TEAI-3 embedded Java adapter assembly narrowing.
5. TEAI-4 diagnostics/raw containment.
6. TEAI-5 module split decision only if import direction proves it is worth
   the churn.

## Verification Set

Focused proof:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportDeliveryCommandListenerTest,TransportRuntimeRegistryTest,TransportAdapterContributionTest,TransportRegistrationResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Adapter proof:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,WebSocketInputProcessorTest,WebSocketOutputProcessorTest,DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest,WebSocketTransportFrameCodecTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Assembly proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,TransportConfigTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Longer compile guard:

```bash
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

## Open Decisions

- Whether package-level separation is enough, or whether a later Maven module
  split is worth the dependency churn.
- Whether `TransportPacketFactory` should remain in `transport_runtime` for
  result/system-event wire shapes or move to concrete adapter/wire owners.
- Whether raw/manual worker messaging remains a supported embedded diagnostic
  side-channel or is removed in a later route-key-removal slice.
- Whether `WorkerTransportRuntimeFactory` is embedded-support only or remains
  a general runtime assembly seam after polling is fully separated.
