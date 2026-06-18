# Transport Adapter Internal Capability Convergence Roadmap

Status: proposed direction document.

## Summary

Transport outer contracts are now much cleaner than the concrete adapter
implementations. `DeliveryCommand` is the assigned delivery intent,
`AdapterCommandExecutor` is the embedded Java final-hop execution seam, and
`TransportBinding` / `TransportAdapterContribution` hold adapter metadata and
bootstrap outputs.

The remaining problem is inside concrete adapter modules. Polling, WebSocket,
and Socket adapters still have large objects that mix command execution, pull
buffer access, endpoint session indexing, endpoint lease projection, worker
session-presence publication, raw/manual side-channels, diagnostics, and
bootstrap composition.

This roadmap converges adapter internals around explicit capabilities:

```text
DeliveryCommand -> adapter-local final-hop attempt -> DispatchOutcome
worker pull request -> pull-buffer demux -> PulledDeliveryMessage
protocol session observation -> endpoint/presence evidence projection
adapter bootstrap -> explicit contributed capabilities
```

The first implementation target is `transport/polling-adapter`. WebSocket and
Socket are analyzed together so the polling split does not create a shape that
cannot later fit push adapters, but they are not part of the first slice.

## Why Separate From Existing Roadmaps

`TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` established the
outer executor boundary and moved adapter metadata out of executor facts.
`TRANSPORT_EMBEDDED_ADAPTER_INDEPENDENCE_CONVERGENCE_ROADMAP.md` established
that embedded Java adapter wiring must not become transport-core truth.

This roadmap starts after those decisions:

- it does not redefine `AdapterCommandExecutor`
- it does not move contracts between modules
- it does not implement external or cross-language adapters
- it does not change assigned-delivery queue semantics

It only splits concrete adapter internals so each adapter role has one owner.

## Current Code Observations

- `PollingWorkerAdapter` currently implements both `AdapterCommandExecutor` and
  `DeliveryPullChannel`.
- `PollingWorkerAdapter.dispatch(...)` calls
  `TransportDeliveryService.enqueue(adapterId, commands)` and returns
  `DispatchOutcome`.
- `PollingWorkerAdapter.pollDeliveryMessagesResult(...)` calls
  `TransportDeliveryService.pollItemResult(deliveryBucketId,
  selectedWorkerId, ...)`.
- `PollingWorkerAdapter` also constructs and owns a
  `TransportEndpointLeasePublisher`, then exposes `announceWorkerOnline`,
  `announceWorkerOffline`, and `refreshEndpointLeaseHeartbeat`.
- `PollingTransportAdapterBootstrap` creates one `PollingWorkerAdapter` and
  contributes it as both the command executor and the pull channel.
- `WebSocketTaskDispatchChannel` is already closer to the target executor
  shape: it only consumes `DeliveryCommand` and sends by selected worker through
  the selected-worker endpoint registry.
- `SocketTaskDispatchChannel` mirrors the WebSocket command path.
- `ServerSessionManager` and `SocketSessionManager` still own broad protocol
  session responsibilities, including selected-worker send and lease/presence
  projection orchestration. They are later alignment targets, not the first
  slice.

See
`TRANSPORT_ADAPTER_INTERNAL_CAPABILITY_CONVERGENCE_INVENTORY.md` for the
current symbol classification.

## Owner Review

Transport runtime owns:

- assigned delivery queueing and pull-buffer storage
- bucket-derived queue addressing
- selected-worker demux
- endpoint lease and selected-worker consumer evidence
- dispatch outcome and failure evidence
- embedded Java adapter binding/registration mechanics

Concrete adapters own:

- protocol server/client mechanics
- local session or pull-loop details
- final-hop selected-worker send attempt
- protocol frame/request parsing and writing
- adapter-local raw/manual side-channels and diagnostics

Concrete adapters may publish endpoint/session evidence through runtime
publishers, but they do not own worker lifecycle truth, worker scheduling,
retry, reassignment, or task-result correctness.

## Boundary Decision

Concrete adapter internals should be split by action shape, not by protocol
label.

### Assigned Delivery Executor

Owner: concrete adapter module.

Shape:

```text
List<DeliveryCommand>
  -> AdapterCommandExecutor
  -> final-hop attempt
  -> List<DispatchOutcome>
```

Polling final-hop attempt means enqueue into the polling pull buffer.
WebSocket/socket final-hop attempt means send to the selected worker endpoint.

The executor must not own:

- pull polling API
- endpoint lease publisher wiring
- worker session-presence publisher wiring
- raw/manual route side-channel
- diagnostics inspector
- adapter bootstrap contribution state

### Pull Channel

Owner: polling adapter module, backed by transport runtime delivery store.

Shape:

```text
deliveryBucketId + selectedWorkerId + max/timeout
  -> DeliveryPullChannel
  -> DeliveryPullResult
```

The pull channel must not own endpoint lease claim/release or command execution
logging. It is a delivery-buffer demux surface only.

### Endpoint Evidence Projector

Owner: concrete adapter module orchestration using runtime publishers.

Shape:

```text
protocol session or polling lease observation
  -> TransportEndpointLeasePublisher
  -> endpoint lease + selected-worker consumer evidence
```

It may call `TransportEndpointLeasePublisher`; it must not duplicate endpoint
lease record construction or selected-worker consumer-claim projection.

### Bootstrap Composition

Owner: concrete adapter bootstrap.

Shape:

```text
adapter config + runtime context
  -> explicit capability instances
  -> TransportAdapterContribution
```

Bootstrap may wire capability objects together. It should not hide multiple
runtime roles behind a single object when those roles have different owners.

## Do Not Start With

- Do not start by renaming `routeKey`, `adapterId`, or `ServerSessionManager`.
  Rename only after an ownership change makes the old name misleading.
- Do not start by refactoring WebSocket or Socket session managers. Their
  protocol lifecycle and side-channel responsibilities make them a larger proof
  surface.
- Do not create `AbstractAdapterRuntime`, `AdapterFacade`, or another
  same-module forwarding layer. The goal is capability ownership, not another
  wrapper.
- Do not move adapter-local classes into `transport_api`.
- Do not change `DeliveryCommand`, `DispatchOutcome`, or polling Redis queue
  semantics in this roadmap.

## Non-Goals

- No external or cross-language adapter protocol.
- No public SDK worker API change.
- No worker lifecycle, scheduling, retry, compensation, or result correctness
  change.
- No raw/manual side-channel removal in the first slice.
- No route-key-removal implementation in the first slice.
- No adapter id strategy change.
- No delivery queue sharding change.

## PAC-0 Inventory And Guard Preparation

Goal: freeze the current adapter-internal role map before code changes.

Scope:

- Keep
  `TRANSPORT_ADAPTER_INTERNAL_CAPABILITY_CONVERGENCE_INVENTORY.md` current.
- Classify polling, websocket, and socket adapter classes by role:
  command executor, pull channel, session store, endpoint evidence projector,
  presence publisher, raw/manual side-channel, diagnostics, bootstrap.
- Identify which tests prove behavior versus only support construction.
- Update guard targets before implementation if a guard would otherwise protect
  the old mixed object shape.

Acceptance:

- Inventory separates production usage from test-only construction.
- Inventory records that polling is the first implementation target.
- Inventory records WebSocket/socket as later alignment targets.
- No roadmap or guard claims `PollingWorkerAdapter` is the desired final shape.

Suggested proof:

```bash
rg -n "PollingWorkerAdapter|WebSocketTaskDispatchChannel|SocketTaskDispatchChannel|ServerSessionManager|SocketSessionManager|DeliveryPullChannel|TransportEndpointLeasePublisher" transport -g "*.java"
rg -n "PollingWorkerAdapter|ServerSessionManager|SocketSessionManager" transport/*-adapter/src/test/java -g "*.java"
```

## PAC-1 Polling Capability Split

Goal: split `PollingWorkerAdapter` into explicit package-private capability
owners without changing protocol behavior.

Target classes are intentionally local to `transport/polling-adapter`:

```text
PollingAdapterContext
PollingDeliveryExecutor
PollingDeliveryPullChannel
PollingEndpointLeaseProjector
```

Scope:

- Introduce package-private `PollingAdapterContext` to hold common adapter
  wiring:
  - adapter id
  - `TransportDeliveryService`
  - `TransportEndpointLeasePublisher`
  - any polling-local limits or logger context
- Introduce `PollingDeliveryExecutor implements AdapterCommandExecutor`.
  - It calls `TransportDeliveryService.enqueue(...)`.
  - It owns retryable rejection logging for polling command enqueue.
  - It does not implement `DeliveryPullChannel`.
  - It does not expose online/offline/refresh methods.
- Introduce `PollingDeliveryPullChannel implements DeliveryPullChannel`.
  - It calls `TransportDeliveryService.pollItemResult(...)`.
  - It maps transport poll status to `DeliveryPullResult`.
  - It does not know endpoint lease store or consumer registry.
- Introduce `PollingEndpointLeaseProjector`.
  - It exposes claim/release/refresh methods for polling worker lease evidence.
  - It delegates to `TransportEndpointLeasePublisher`.
  - It does not know `DeliveryCommand`.
- Narrow or delete `PollingWorkerAdapter`.
  - Preferred: delete it and update tests/callers to the explicit classes.
  - Acceptable only if needed for construction: keep it package-private as a
    composition holder with no implemented transport interfaces.

Acceptance:

- No production class named `PollingWorkerAdapter` implements both
  `AdapterCommandExecutor` and `DeliveryPullChannel`.
- Polling command execution cannot call endpoint lease claim/release/refresh.
- Polling pull channel cannot call endpoint lease claim/release/refresh.
- Polling endpoint lease projector cannot import `DeliveryCommand` or
  `DeliveryPullChannel`.
- Existing polling behavior is unchanged:
  - selected-worker demux still prevents cross-worker consumption
  - empty/invalid/unavailable/shutdown pull statuses remain distinct
  - backpressure behavior remains bucket-buffer behavior
  - endpoint lease claim/release/refresh behavior remains equivalent

Suggested focused tests:

```bash
./mvnw -q -pl transport/polling-adapter,transport/transport_runtime -am test -Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingEndpointLeaseProjectorTest,PollingWorkerAdapterTest,TransportConvergenceArchitectureGuardTest
```

If the new tests do not exist yet, create them in this slice instead of using
`-Dsurefire.failIfNoSpecifiedTests=false`.

## PAC-2 Polling Bootstrap Contribution Cleanup

Goal: make polling bootstrap contribute explicit capabilities rather than a
single multi-role adapter object.

Scope:

- Update `PollingTransportAdapterBootstrap` to create:
  - one `PollingAdapterContext`
  - one `PollingDeliveryExecutor`
  - one `PollingDeliveryPullChannel`
  - one `PollingEndpointLeaseProjector` if a polling-facing lease publisher is
    still required by embedded SDK pull sessions
- Build `TransportBinding` with:
  - adapter id
  - transport hint
  - protocol label
  - `PollingDeliveryExecutor`
  - `PollingDeliveryPullChannel`
- Keep `DefaultWorkerTransportRuntimeFactory` as registry aggregation only.
  It must not recreate polling special-case wiring.

Acceptance:

- `PollingTransportAdapterBootstrap` no longer contributes one object as both
  command executor and pull channel.
- `DefaultWorkerTransportRuntimeFactory` still does not instantiate polling
  adapter classes.
- Embedded SDK pull sessions still resolve the polling pull channel through
  `TransportBinding`.
- No public SDK API or worker protocol behavior changes.

Suggested focused tests:

```bash
./mvnw -q -pl transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=PollingWorkerAdapterTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest,TransportConfigTest
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,TransportAdapterContributionTest,TransportConvergenceArchitectureGuardTest
```

## PAC-3 Push Adapter Alignment Inventory

Goal: use the polling split to define the later WebSocket/socket session-manager
split without changing push adapter behavior yet.

Scope:

- Compare `PollingDeliveryExecutor` with `WebSocketTaskDispatchChannel` and
  `SocketTaskDispatchChannel`.
- Compare `PollingEndpointLeaseProjector` with the lease/presence orchestration
  in `ServerSessionManager` and `SocketSessionManager`.
- Decide whether push adapters need explicit:
  - selected-worker sender
  - session store
  - endpoint lease projector
  - presence projector
  - raw/manual side-channel
  - diagnostics inspector
- Record whether this belongs in this roadmap's next slice or a separate
  push-adapter session-manager roadmap.

Acceptance:

- WebSocket/socket alignment work is classified before any push session-manager
  rewrite starts.
- Assigned delivery remains selected-worker only.
- Raw/manual side-channels remain outside command executors.
- Any later split has its own focused proof commands and does not depend on
  polling test behavior.

Suggested proof:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest,SocketTransportServerTest,WebSocketFrameReadersTest,SocketTransportFrameCodecTest
```

## PAC-4 Guards And Owner Docs

Goal: prevent concrete adapters from drifting back into multi-role objects.

Scope:

- Update `TransportConvergenceArchitectureGuardTest` with focused guards:
  - polling command executor does not import endpoint lease store/publisher
    types directly except through the polling context if the context is the
    explicit owner
  - polling pull channel does not import endpoint lease or command executor
    types
  - polling endpoint lease projector does not import `DeliveryCommand`,
    `DeliveryPullChannel`, or polling pull buffer classes
  - no production polling class implements both `AdapterCommandExecutor` and
    `DeliveryPullChannel`
- Update `transport/AGENTS.md` and
  `transport/TRANSPORT_BOUNDARY_BASELINE.md` after PAC-1/PAC-2 land so current
  truth reflects explicit polling capabilities.
- Update `doc/PROOF_REGISTRY.md` only after tests/guards prove the new shape.

Acceptance:

- Guard failures point to specific role mixing, not broad vocabulary scans.
- Owner docs do not describe the target shape as implemented before code lands.
- Proof registry cites behavior tests and architecture guards that actually
  exist.

Suggested verification:

```bash
./mvnw -q -pl transport/transport_runtime,transport/polling-adapter -am test -Dtest=TransportConvergenceArchitectureGuardTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingEndpointLeaseProjectorTest
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Polling adapter no longer has a production object that owns command
  execution, pull polling, and endpoint lease projection at the same time.
- Polling bootstrap contributes explicit command executor and pull channel
  capability objects.
- Polling selected-worker delivery tests still prove that shared bucket queues
  cannot cross-consume selected-worker commands.
- Endpoint lease projection remains runtime-publisher based and is not
  duplicated in concrete adapter code.
- WebSocket/socket have either been aligned to the same capability vocabulary or
  explicitly tracked in a follow-up roadmap with current evidence.
- Transport owner docs and proof registry match current code.
- Residue scan finds no tests or docs preserving the old polling multi-role
  object as the desired shape.

## Suggested Implementation Order

1. PAC-0: update inventory and prepare guard intent.
2. PAC-1: split polling adapter capabilities with behavior tests.
3. PAC-2: update polling bootstrap contribution and embedded SDK proof.
4. PAC-4: land guards and owner-doc updates for polling.
5. PAC-3: classify WebSocket/socket alignment and decide whether to continue in
   this roadmap or split a push-adapter session-manager roadmap.

PAC-3 is intentionally after the polling split. WebSocket/socket should be
analyzed early, but they should not be the first edit surface.
