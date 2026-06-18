# Transport Adapter Internal Capability Convergence Roadmap

Status: proposed direction document.

## Summary

Transport outer contracts are now much cleaner than the concrete adapter
implementations. `DeliveryCommand` is the assigned delivery intent,
`AdapterCommandExecutor` is the embedded Java final-hop execution seam, and
`TransportBinding` / `TransportAdapterContribution` hold adapter metadata and
bootstrap outputs.

The remaining problem is inside concrete adapter modules and the embedded SDK
pull-session path. Polling, WebSocket, and Socket adapters still have large
objects that mix command execution, pull buffer access, endpoint session
indexing, endpoint lease projection, worker session-presence publication,
raw/manual side-channels, diagnostics, and bootstrap composition.

This roadmap converges adapter internals around explicit capabilities:

```text
DeliveryCommand -> adapter-local final-hop attempt -> DispatchOutcome
worker pull request -> pull-buffer demux -> PulledDeliveryMessage
pull session observation -> evidence driver -> endpoint/presence evidence
adapter bootstrap -> explicit contributed capabilities
```

The first implementation target is the polling path. WebSocket and Socket are
analyzed together so the polling split does not create a shape that cannot
later fit push adapters, but they are not part of the first implementation
slice.

Polling-only does not mean adapter-module-only. The embedded SDK
`PullWorkerSession` is a production polling path and currently writes transport
session evidence directly, so the first executable slice must include that
session path.

## Why Separate From Existing Roadmaps

`TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` established the
outer executor boundary and moved adapter metadata out of executor facts.
`TRANSPORT_EMBEDDED_ADAPTER_INDEPENDENCE_CONVERGENCE_ROADMAP.md` established
that embedded Java adapter wiring must not become transport-core truth.

This roadmap starts after those decisions:

- it does not redefine `AdapterCommandExecutor`
- it does not move contracts into `transport_api`
- it does not implement external or cross-language adapters
- it does not change assigned-delivery queue semantics

It only splits concrete adapter and pull-session internals so each adapter role
has one owner.

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
- `PullWorkerSession` is the embedded SDK production pull session. It directly
  owns worker session-presence publication, endpoint lease claim/heartbeat/
  release, and selected-worker consumer claim/release, while also polling work
  and submitting results.
- `InternalPullWorkerSessions` passes `TransportEndpointLeaseStore`,
  `DeliveryCommandConsumerRegistry`, and `WorkerPresenceIngress` into
  `PullWorkerSession`.
- `MassApplication.openPullWorkerSession(...)` resolves
  `ResolvedPullWorkerTransport` and opens `PullWorkerSession`, so this is not a
  test-only or legacy path.
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

Embedded SDK worker sessions own the SDK-facing session actions: connect,
heartbeat, disconnect, poll, and submit result. They may consume a
runtime-resolved evidence driver, but they must not define endpoint lease
records, selected-worker consumer claims, or worker-presence event shape
directly.

## SDK Pattern Translation

The Java SDK worker-runtime cleanup is a useful shape check, but adapter work
must not copy worker-runtime semantics.

| SDK Runtime Shape | Adapter Translation | Constraint |
| --- | --- | --- |
| `WorkerRuntimeDefinition` | `TransportAdapterDescriptor` / adapter config / binding metadata | Describes adapter runtime facts, not worker capability truth. |
| package-private `WorkerRuntimeContext` | role-specific constructor wiring | Do not create a service locator that exposes delivery, lease, presence, raw, and diagnostics to every role. |
| protocol driver | selected-worker sender, pull channel, or pull-session evidence driver | Each driver owns one action shape. |
| `WorkerDispatchProcessor` | delivery outcome normalization or payload handoff | Adapter must not execute worker handlers or own worker invocation semantics. |
| public `WorkerRuntime` lifecycle | no public adapter runtime lifecycle | Adapter lifecycle remains embedded assembly/managed-adapter behavior unless a later roadmap proves a real external surface. |

Allowed borrowing:

- narrow public or runtime-facing surface
- private/common wiring only where it removes duplicated fact derivation
- protocol-specific drivers with one action owner
- focused guards that fail on role mixing

Forbidden borrowing:

- public `AdapterRuntime`
- worker capability, lifecycle, or handler semantics inside adapters
- a generic `AbstractAdapterRuntime`
- a context object that hides old mixed responsibilities

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

### Pull Session Evidence Driver

Owner: runtime-resolved embedded pull-session capability, implemented by the
polling adapter or embedded runtime assembly using runtime publishers.

Target contract lives in runtime embedded-support, not `transport_api`, so SDK
pull sessions can depend on one narrow evidence driver without depending on raw
stores or registries.

Shape:

```text
workerId + deliveryBucketId + session token + reason
  -> connect / heartbeat / disconnect
  -> worker session-presence observation
  -> endpoint lease + selected-worker consumer evidence
```

The SDK `PullWorkerSession` may call this driver for session actions. It must
not directly import or call `TransportEndpointLeaseStore`,
`DeliveryCommandConsumerRegistry`, `WorkerPresenceIngress`,
`WorkerSessionPresenceEvent`, `TransportEndpointLeaseClaim`,
`TransportEndpointLeaseHeartbeat`, `TransportEndpointLeaseRelease`, or
`DeliveryCommandConsumerClaim`.

The driver may call `TransportEndpointLeasePublisher` and
`WorkerPresenceSessionPublisher`; it must not duplicate endpoint lease record
construction or selected-worker consumer-claim projection.

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
- Do not start by adding a fat `PollingAdapterContext`. Role-specific wiring is
  safer than hiding delivery, lease, presence, and diagnostics behind one
  shared object.
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

Goal: freeze the current adapter-internal and SDK pull-session role map before
code changes.

Scope:

- Keep
  `TRANSPORT_ADAPTER_INTERNAL_CAPABILITY_CONVERGENCE_INVENTORY.md` current.
- Classify polling, websocket, and socket adapter classes by role:
  command executor, pull channel, session store, session evidence driver,
  presence publisher, raw/manual side-channel, diagnostics, bootstrap.
- Classify the embedded SDK production pull path:
  `MassApplication.openPullWorkerSession(...)`,
  `InternalPullWorkerSessions`, `ResolvedPullWorkerTransport`, and
  `PullWorkerSession`.
- Identify which tests prove behavior versus only support construction.
- Update guard targets before implementation if a guard would otherwise protect
  the old mixed object shape or bind to a class that PAC-1/PAC-2 intends to
  delete or narrow.

Acceptance:

- Inventory separates production usage from test-only construction.
- Inventory records that polling is the first implementation target.
- Inventory records that `PullWorkerSession` is in scope for the first polling
  production slice.
- Inventory records WebSocket/socket as later alignment targets.
- No roadmap or guard claims `PollingWorkerAdapter` is the desired final shape.
- `TransportConvergenceArchitectureGuardTest` is retargeted from
  `PollingWorkerAdapter.java` path checks to role-based checks before PAC-1
  deletes or narrows that class.

Suggested proof:

```bash
rg -n "PollingWorkerAdapter|WebSocketTaskDispatchChannel|SocketTaskDispatchChannel|ServerSessionManager|SocketSessionManager|DeliveryPullChannel|TransportEndpointLeasePublisher" transport -g "*.java"
rg -n "PullWorkerSession|InternalPullWorkerSessions|ResolvedPullWorkerTransport|openPullWorkerSession|TransportEndpointLeaseStore|DeliveryCommandConsumerRegistry|WorkerPresenceIngress" sdk/xa-mass-embedded-sdk/src/main/java transport/transport_runtime/src/main/java -g "*.java"
rg -n "PollingWorkerAdapter|ServerSessionManager|SocketSessionManager" transport/*-adapter/src/test/java -g "*.java"
```

## PAC-1 Pull Session Evidence Driver

Goal: move production polling session evidence writes behind a runtime-resolved
capability before splitting the adapter object.

Target shape:

```text
PullWorkerSession
  -> PullSessionEvidenceDriver
  -> TransportEndpointLeasePublisher
  -> WorkerPresenceSessionPublisher
```

Scope:

- Introduce a narrow `PullSessionEvidenceDriver` contract in runtime embedded
  support.
  - It owns connect, heartbeat, and disconnect evidence projection.
  - It returns the same success/failure shape that `PullWorkerSession` needs
    today.
  - It does not expose raw stores, registries, or presence ingress to SDK
    session code.
- Provide a polling implementation that delegates to:
  - `TransportEndpointLeasePublisher`
  - `WorkerPresenceSessionPublisher`
- Extend `TransportBinding` / `ResolvedPullWorkerTransport` only as needed to
  carry the driver to embedded SDK pull sessions.
- Update `InternalPullWorkerSessions` and `PullWorkerSession` so the session
  takes the driver instead of:
  - `TransportEndpointLeaseStore`
  - `DeliveryCommandConsumerRegistry`
  - `WorkerPresenceIngress`
- Keep `PullWorkerSession.poll(...)` and result submission behavior unchanged.
- Keep route-key/session-token behavior unchanged in this slice; only move the
  owner of evidence construction and publication.

Acceptance:

- `PullWorkerSession` no longer imports or stores
  `TransportEndpointLeaseStore`, `DeliveryCommandConsumerRegistry`, or
  `WorkerPresenceIngress`.
- `PullWorkerSession` no longer constructs
  `WorkerSessionPresenceEvent`, `TransportEndpointLeaseClaim`,
  `TransportEndpointLeaseHeartbeat`, `TransportEndpointLeaseRelease`, or
  `DeliveryCommandConsumerClaim`.
- `InternalPullWorkerSessions` no longer passes raw endpoint lease stores,
  consumer registries, or worker presence ingress into `PullWorkerSession`.
- `MassApplication.openPullWorkerSession(...)` still opens polling sessions
  through resolved runtime transport.
- Connect, heartbeat, and disconnect still publish equivalent presence,
  endpoint lease, and selected-worker consumer evidence.
- No public SDK worker API changes.

Suggested focused tests:

```bash
./mvnw -q -pl transport/transport_runtime,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=PullWorkerSessionTest,MassApplicationDistributedTransportTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest,TransportConvergenceArchitectureGuardTest
```

If the new tests do not exist yet, create them in this slice instead of using
`-Dsurefire.failIfNoSpecifiedTests=false`.

## PAC-2 Polling Adapter Capability Split

Goal: split `PollingWorkerAdapter` into explicit adapter-local command and pull
capabilities without changing protocol behavior.

Target classes are intentionally local to `transport/polling-adapter` unless a
contract is explicitly runtime embedded-support:

```text
PollingAdapterMetadata
PollingDeliveryExecutor
PollingDeliveryPullChannel
PollingSessionEvidenceDriver
```

Scope:

- Introduce role-specific wiring only.
  - `PollingAdapterMetadata` may carry adapter id, protocol label, and local
    limits.
  - `PollingDeliveryExecutor` receives only the facts it needs:
    `adapterId` and `TransportDeliveryService`.
  - `PollingDeliveryPullChannel` receives only `TransportDeliveryService`.
  - `PollingSessionEvidenceDriver` receives only the publishers or dependencies
    needed for connect/heartbeat/disconnect evidence projection.
- Do not introduce a `PollingAdapterContext` that exposes delivery, lease,
  presence, raw, and diagnostics capabilities together.
- Introduce `PollingDeliveryExecutor implements AdapterCommandExecutor`.
  - It calls `TransportDeliveryService.enqueue(...)`.
  - It owns retryable rejection logging for polling command enqueue.
  - It does not implement `DeliveryPullChannel`.
  - It cannot call endpoint lease claim/release/refresh.
- Introduce `PollingDeliveryPullChannel implements DeliveryPullChannel`.
  - It calls `TransportDeliveryService.pollItemResult(...)`.
  - It maps transport poll status to `DeliveryPullResult`.
  - It does not know endpoint lease store, consumer registry, or presence
    ingress.
- Update `PollingTransportAdapterBootstrap` to create:
  - one `PollingDeliveryExecutor`
  - one `PollingDeliveryPullChannel`
  - one pull-session evidence driver
- Build `TransportBinding` with:
  - adapter id
  - transport hint
  - protocol label
  - `PollingDeliveryExecutor`
  - `PollingDeliveryPullChannel`
  - pull-session evidence driver
- Keep `DefaultWorkerTransportRuntimeFactory` as registry aggregation only.
  It must not recreate polling special-case wiring.
- Narrow or delete `PollingWorkerAdapter`.
  - Preferred: delete it and update tests/callers to the explicit classes.
  - Acceptable only if needed for construction: keep it package-private as a
    composition holder with no implemented transport interfaces and no evidence
    write methods.

Acceptance:

- No production class named `PollingWorkerAdapter` implements both
  `AdapterCommandExecutor` and `DeliveryPullChannel`.
- `PollingTransportAdapterBootstrap` no longer contributes one object as both
  command executor and pull channel.
- `DefaultWorkerTransportRuntimeFactory` still does not instantiate polling
  adapter classes.
- Embedded SDK pull sessions still resolve the polling pull channel through
  `TransportBinding`.
- Polling command execution cannot call endpoint lease claim/release/refresh
  directly or indirectly through a shared context.
- Polling pull channel cannot call endpoint lease claim/release/refresh.
- Polling session evidence driver cannot import `DeliveryCommand` or
  `DeliveryPullChannel`.
- Existing polling behavior is unchanged:
  - selected-worker demux still prevents cross-worker consumption
  - empty/invalid/unavailable/shutdown pull statuses remain distinct
  - backpressure behavior remains bucket-buffer behavior
  - endpoint lease claim/release/refresh behavior remains equivalent
- No public SDK API or worker protocol behavior changes.

Suggested focused tests:

```bash
./mvnw -q -pl transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,PollingWorkerAdapterTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest,TransportConfigTest
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,TransportAdapterContributionTest,TransportConvergenceArchitectureGuardTest
```

## PAC-3 Push Adapter Alignment Inventory

Goal: use the polling split to define the later WebSocket/socket
session-manager split without changing push adapter behavior yet.

Scope:

- Compare `PollingDeliveryExecutor` with `WebSocketTaskDispatchChannel` and
  `SocketTaskDispatchChannel`.
- Compare `PollingSessionEvidenceDriver` with the lease/presence orchestration
  in `ServerSessionManager` and `SocketSessionManager`.
- Decide whether push adapters need explicit:
  - selected-worker sender
  - session store
  - session evidence driver
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

Goal: prevent concrete adapters and SDK pull sessions from drifting back into
multi-role objects.

Scope:

- Update `TransportConvergenceArchitectureGuardTest` with focused guards:
  - polling command executor does not import endpoint lease, presence, or
    consumer-registry types
  - polling pull channel does not import endpoint lease or command executor
    types
  - polling session evidence driver does not import `DeliveryCommand`,
    `DeliveryPullChannel`, or polling pull buffer classes
  - no production polling class implements both `AdapterCommandExecutor` and
    `DeliveryPullChannel`
  - `PullWorkerSession` does not import endpoint lease store, consumer
    registry, worker presence ingress, lease command models, presence events,
    or consumer claim models
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
./mvnw -q -pl transport/transport_runtime,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=TransportConvergenceArchitectureGuardTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,PullWorkerSessionTest
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Polling adapter no longer has a production object that owns command
  execution, pull polling, and endpoint lease projection at the same time.
- Polling bootstrap contributes explicit command executor, pull channel, and
  pull-session evidence driver capability objects.
- `PullWorkerSession` consumes a runtime-resolved pull-session evidence driver
  and no longer owns endpoint lease, selected-worker consumer, or worker
  presence event construction.
- Polling selected-worker delivery tests still prove that shared bucket queues
  cannot cross-consume selected-worker commands.
- Endpoint lease projection remains runtime-publisher based and is not
  duplicated in concrete adapter or SDK session code.
- WebSocket/socket have either been aligned to the same capability vocabulary or
  explicitly tracked in a follow-up roadmap with current evidence.
- Transport owner docs and proof registry match current code.
- Residue scan finds no tests or docs preserving the old polling multi-role
  object as the desired shape.

## Suggested Implementation Order

1. PAC-0: update inventory and retarget guard intent.
2. PAC-1: move production pull-session evidence writes behind a
   runtime-resolved driver.
3. PAC-2: split polling adapter command and pull capabilities and update
   bootstrap contribution.
4. PAC-4: land guards and owner-doc updates for polling.
5. PAC-3: classify WebSocket/socket alignment and decide whether to continue in
   this roadmap or split a push-adapter session-manager roadmap.

PAC-3 is intentionally after the polling split. WebSocket/socket should be
analyzed early, but they should not be the first edit surface.
