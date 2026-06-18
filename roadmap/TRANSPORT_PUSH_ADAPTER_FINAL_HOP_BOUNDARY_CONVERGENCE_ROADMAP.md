# Transport Push Adapter Final-Hop Boundary Convergence Roadmap

Status: implementation landed; final verification and archive-readiness scan
remain.

## Summary

Push adapter assigned delivery used to be too indirect:

```text
AdapterCommandExecutor.dispatch(...)
  -> TransportDeliveryService.sendDirect(...)
  -> WorkerEndpointRegistry.sendToSelectedWorker(...)
  -> concrete adapter session object
```

This creates wrapper layers, multi-role interfaces, and runtime-to-adapter-to-
runtime callback loops. The goal is to make push adapters straightforward
embedded Java final-hop executors:

```text
DeliveryCommand
  -> concrete AdapterCommandExecutor
  -> adapter-local selected-worker session lookup/write
  -> DispatchOutcome
```

Inside a concrete push adapter, assigned-delivery lookup must use one local
unique id only:

```text
selectedWorkerId / workerId
```

`deliveryBucketId` remains an upstream queue/evidence/binding fact. It is not a
WebSocket or Socket session lookup dimension. Endpoint address, routeKey, and
connection/session ids are raw/manual, diagnostic, or evidence metadata only;
they must not become a second adapter-local delivery identity.

The roadmap converges the whole owner boundary instead of fixing one class at a
time. WebSocket and Socket should share the same principles, even if Socket
lands after WebSocket.

## Current Code Observations

- `TransportDeliveryCommandListener` is already the runtime-owned bridge from
  handoff batches to local `AdapterCommandExecutor` instances. It resolves
  endpoint lease evidence, resolves `TransportBinding`, groups by adapter
  binding id, and calls `executor.dispatch(...)`.
- `WebSocketTaskDispatchChannel` and `SocketTaskDispatchChannel` now produce
  outcomes directly from concrete adapter-local final-hop attempts. Neither
  executor keeps adapter id as an executor input.
- `TransportDeliveryService` is queue/pull only. `sendDirect(...)`,
  `TransportDeliverySender`, and runtime direct-send counters have been
  removed.
- `WorkerEndpointRegistry` and `CompositeWorkerEndpointRegistry` have been
  removed. Push assigned delivery has no transport-neutral selected-worker
  endpoint registry.
- `TransportConfig`, `TransportRuntimeComposition`, and `MassApplication` no
  longer expose endpoint-registry assembly hooks.
- WebSocket removed `WebSocketCommandDispatchContext`,
  `WebSocketSelectedWorkerSender`, and `WebSocketSelectedWorkerRegistry`.
  `WebSocketSessionController` now implements only
  `WebSocketServerSessionHandle`.
- Socket removed `SocketCommandDispatchContext`; `SocketTaskDispatchChannel`
  calls `SocketSessionManager.sendToWorker(...)` directly. A later Socket
  internal-session cleanup may split the manager, but assigned delivery no
  longer routes through a wrapper interface.
- Current architecture guards protect against endpoint-registry,
  command-context, and `TransportDeliveryService.sendDirect(...)`
  reintroduction.

## Owner Review

Push adapter final-hop send belongs to the concrete adapter executor.

Transport runtime may:

- resolve which local adapter binding should execute a command
- own adapter binding metadata such as adapter id, transport hint, and protocol
  label
- invoke `AdapterCommandExecutor.dispatch(...)`
- normalize executor-level rejection or failure
- emit delivery-failure evidence from returned outcomes
- keep delivery queue/store/polling mechanics

Concrete push adapters may:

- encode protocol frames
- look up the selected worker by one adapter-local worker id
- write to a local channel/session
- return `DispatchOutcome`

Concrete push adapters must not:

- call back into runtime delivery service for direct-send outcome wrapping
- expose their local session store as a transport-neutral registry
- require adapter id as a command-executor constructor fact just for logging or
  diagnostics
- implement several unrelated role interfaces on one class unless that class
  is an explicitly documented top-level adapter manager/assembly point
- use route, adapter id, connection id, endpoint address, or session handle as
  delivery correctness

Diagnostics and lifecycle are separate roles. Active connection count does not
belong on the assigned-delivery send interface. Shutdown belongs to managed
adapter/server lifecycle, not to selected-worker send.

## Boundary Decision

The assigned-delivery push adapter contract is:

```java
List<DispatchOutcome> dispatch(List<DeliveryCommand> commands)
```

`AdapterCommandExecutor` returns delivery outcomes directly.

`WorkerEndpointRegistry` is not the final target assigned-delivery interface.
It should exit push assigned delivery, then exit `transport_api` if no
production non-test caller still needs it. If a temporary local sender method is
needed inside one adapter, it must be adapter-local and concrete, not a new
transport-neutral registry.

`TransportDeliveryService` remains the owner of queue/pull delivery service
operations. It should not own push final-hop direct send.

Adapter id is binding/evidence/bootstrap metadata. It may be used by
`TransportDeliveryCommandListener`, `TransportBinding`, endpoint evidence,
raw/manual side-channels, diagnostics, and adapter bootstrap. It must not be a
required `AdapterCommandExecutor` input when the executor can perform final-hop
send from `DeliveryCommand` plus adapter-local session state.

`deliveryBucketId` is also not a push-adapter final-hop lookup key. The engine
and runtime can use it to derive queue addresses and publish endpoint evidence,
but the concrete WebSocket/Socket send attempt must resolve the local session
from `DeliveryCommand.selectedWorkerId()` alone. A `bucket + worker` local
lookup is unnecessary for the current embedded push adapters and would recreate
two identities for the same final-hop decision.

## Target Shape

WebSocket target:

```text
WebSocketTaskDispatchChannel
  -> DeliveryCommand.selectedWorkerId
  -> WebSocketSessionStore.activeRecordForWorker(selectedWorkerId)
  -> channel.writeAndFlush(...)
  -> DispatchOutcome

WebSocketSessionController
  -> implements WebSocketServerSessionHandle only
  -> orchestrates bind/remove/shutdown with store/evidence/refresh loop

WebSocketSessionStore
  -> owns selected-worker session lookup and session state only
```

Socket target:

```text
SocketTaskDispatchChannel
  -> DeliveryCommand.selectedWorkerId
  -> SocketTransportFrameCodec
  -> socket selected-worker session write owner
  -> DispatchOutcome

Socket session owner
  -> split into store/controller/evidence/diagnostics, or kept as one
     explicitly documented top-level manager only until the split lands
```

Runtime assembly target:

```text
TransportAdapterBootstrapContext
  -> result ingress
  -> presence ingress
  -> endpoint lease store
  -> delivery service/store for polling
  -> delivery consumer registry
  -> runtime executor
  -> no assigned-delivery endpoint registry

TransportAdapterContribution
  -> bindings
  -> servers
  -> raw/manual channels
  -> diagnostics
  -> managed adapters
```

Diagnostics target:

```text
WorkerEndpointInspector / transport stats
  -> active endpoint/connection diagnostics

No active connection count on assigned-delivery send contract.
```

## Non-Goals

- No worker selection, retry, reassignment, compensation, or task timeout
  changes.
- No delivery bucket, queue key, endpoint lease, or selected-worker consumer
  evidence rewrite.
- No route-key removal in this roadmap.
- No raw/manual route side-channel deletion.
- No external/cross-language adapter protocol.
- No public Java worker SDK model change.
- No new abstract adapter runtime or broad base class.
- No compatibility aliases for removed in-repo interfaces.

## Do Not Start With

- Do not start by deleting `WorkerEndpointRegistry` from `transport_api`.
  First move WebSocket/Socket executors and SDK diagnostics off it.
- Do not replace `WorkerEndpointRegistry` with another same-shape interface
  such as `SelectedWorkerSender`. That just hides the same owner problem.
- Do not add a `bucket + worker` session lookup or extend
  `WebSocketServerSessionHandle` with assigned-delivery lookup methods. Push
  adapter local lookup is worker-id-only.
- Do not keep `TransportDeliveryService.sendDirect(...)` as the push mainline
  just because it already records counters. Move or drop the counters.
- Do not make `WebSocketSessionController` or `SocketSessionManager` implement
  more interfaces to avoid changing assembly.
- Do not use raw/manual route send as a fallback for assigned delivery.

## PAFH-0 Inventory And Guard Preparation

Goal: make the actual caller and guard surface explicit before moving code.

Scope:

- Keep
  `TRANSPORT_PUSH_ADAPTER_FINAL_HOP_BOUNDARY_CONVERGENCE_INVENTORY.md`
  current while implementing this roadmap.
- Classify all production `WorkerEndpointRegistry` callers:
  - assigned-delivery final-hop
  - diagnostics
  - lifecycle/shutdown
  - assembly/override
  - tests only
- Classify all `TransportDeliveryService.sendDirect(...)` callers and tests.
- Update stale active roadmap/proof wording that says selected-worker sender or
  endpoint registry is the target.
- Change guards so they no longer require obsolete WebSocket wrapper shapes.
- Stop guards from treating `SocketCommandDispatchContext` as the target shape,
  but do not make PAFH-0 fail the current Socket production path. Socket still
  uses `SocketCommandDispatchContext`, `WorkerEndpointRegistry`, and
  `TransportDeliveryService.sendDirect(...)` until PAFH-2 lands.
- Prepare the PAFH-2 guard checks that will forbid Socket command executors from
  importing or mentioning `WorkerEndpointRegistry`, `SocketCommandDispatchContext`,
  `TransportDeliveryService`, `sendDirect(...)`, or executor-owned `adapterId`.

Acceptance:

- The inventory separates production and test-only use.
- Guards no longer require WebSocket wrapper classes or WebSocket selected-worker
  registry/sender concepts.
- Guards no longer assert that `SocketCommandDispatchContext` is the desired
  target. Current Socket use is recorded as residue, not protected as
  architecture.
- Guards fail if a converged adapter command executor calls raw route send,
  endpoint lease, worker presence, diagnostics, or a runtime direct-send helper.
  During PAFH-0, this applies to WebSocket and to newly introduced converged
  executors; it flips to Socket in PAFH-2.
- Guards fail if WebSocket reintroduces `WebSocketCommandDispatchContext`,
  `WebSocketSelectedWorkerSender`, or `WebSocketSelectedWorkerRegistry`.
- No behavior changes are required in this slice.

## PAFH-1 WebSocket Final-Hop Direct Executor

Goal: remove WebSocket assigned-delivery wrapper layers and runtime direct-send
callback loops.

Scope:

- Change `WebSocketTaskDispatchChannel` to return `DispatchOutcome` directly.
- Remove `TransportDeliveryService` from `WebSocketTaskDispatchChannel`.
- Remove `adapterId` from `WebSocketTaskDispatchChannel` constructor and
  fields. Binding-level diagnostics must be recorded by runtime listener /
  binding context, not by forcing adapter identity into the executor.
- Change `WebSocketTaskDispatchChannel` to look up the selected worker in
  `WebSocketSessionStore` and perform the local channel write itself, without
  depending on `WorkerEndpointRegistry` or moving send behavior into the store.
  The lookup key is `DeliveryCommand.selectedWorkerId()` only; do not add
  `deliveryBucketId` to the WebSocket local session lookup.
- Change `WebSocketSessionController` back to a single-role
  `WebSocketServerSessionHandle` / orchestration object.
- Decide whether `WebSocketServerSession` should be renamed or contained as a
  bounded session projection. It must not grow endpoint/bucket/route/channel
  fields.
- Update WebSocket bootstrap assembly accordingly.

Acceptance:

- WebSocket assigned delivery no longer imports or mentions
  `WorkerEndpointRegistry`.
- `WebSocketSessionController` does not implement multiple role interfaces.
- `WebSocketSessionStore` does not expose `sendToSelectedWorker(...)`, import
  WebSocket frame classes, or call `writeAndFlush(...)`.
- `WebSocketTaskDispatchChannel` does not call
  `TransportDeliveryService.sendDirect(...)`.
- `WebSocketTaskDispatchChannel` does not own an `adapterId` field,
  `requireAdapterId(...)`, or constructor parameter.
- `WebSocketTaskDispatchChannel` performs local session lookup by
  `selectedWorkerId` only. It does not call or expose
  `activeRecordForDelivery(deliveryBucketId, workerId)` or an equivalent
  bucket-worker lookup.
- `WebSocketServerSessionHandle` remains server/inbound session control only;
  it must not grow assigned-delivery lookup methods.
- `WebSocketTaskDispatchChannel` returns:
  - `DELIVERED` on successful local channel write
  - `NO_ENDPOINT` when the selected worker has no active local session
  - retryable `FAILED` for local send exceptions
  - `INVALID` for invalid/null command input
- Existing WebSocket session replacement, shared route/address, inbound result,
  and server factory behavior remains unchanged.

## PAFH-2 Socket Final-Hop Direct Executor

Goal: apply the same final-hop owner decision to Socket without copying the old
WebSocket wrapper pattern.

Scope:

- Delete `SocketCommandDispatchContext`.
- Change `SocketTaskDispatchChannel` to receive explicit constructor facts:
  `SocketTransportFrameCodec` and the socket-local selected-worker session
  lookup/write owner. Do not pass adapter id into the executor as logging or
  diagnostics context.
  The socket-local lookup key is `DeliveryCommand.selectedWorkerId()` only; do
  not add `deliveryBucketId` to socket local session lookup unless a later
  adapter-specific proof shows one worker id can legitimately map to multiple
  simultaneous push sessions in the same adapter process.
- Remove `TransportDeliveryService` from `SocketTaskDispatchChannel`.
- If `SocketSessionManager` remains temporarily, document it as a top-level
  adapter manager and do not add more interfaces to it.
- Prefer splitting socket session state into store/controller/evidence driver
  if that is needed to remove the broad manager dependency cleanly.

Acceptance:

- Socket assigned delivery no longer imports or mentions
  `WorkerEndpointRegistry`.
- `SocketTaskDispatchChannel` does not own an `adapterId` field or constructor
  parameter.
- `SocketTaskDispatchChannel` does not depend on `SocketCommandDispatchContext`
  or any same-shape replacement command context.
- Socket final-hop lookup is worker-id-only, not bucket-plus-worker.
- `SocketTaskDispatchChannel` does not call
  `TransportDeliveryService.sendDirect(...)`.
- `SocketCommandDispatchContext` no longer exists.
- Architecture guards fail if Socket command execution reintroduces
  `WorkerEndpointRegistry`, `SocketCommandDispatchContext`,
  `TransportDeliveryService`, `sendDirect(...)`, or executor-owned `adapterId`.
- Socket final-hop outcomes match WebSocket outcome semantics.
- Socket raw route, diagnostics, endpoint lease, and worker presence remain
  outside command execution.

## PAFH-3 Runtime Assembly And Diagnostics Cleanup

Goal: remove global endpoint-registry wiring from runtime composition and SDK
diagnostics after push executors no longer need it.

Scope:

- Remove `TransportAdapterBootstrapContext.getEndpointRegistry()`.
- Remove `CompositeWorkerEndpointRegistry` if no production assigned-delivery
  caller remains.
- Remove `TransportConfig.workerEndpointRegistry`,
  `endpointRegistryFactory`, and `workerPresenceIngressResolver` coupling if
  no production use remains.
- Remove `TransportRuntimeComposition.resolveWorkerEndpointRegistry()`.
- Remove `MassApplication.endpointRegistry` and `getEndpointRegistry()` unless
  a current external caller proves a non-dispatch diagnostic need.
- Retarget `DefaultRuntimeDiagnosticsOperations` active-connection diagnostics
  to `WorkerEndpointInspector`, transport stats, or adapter diagnostics
  contribution.
- Remove `WorkerEndpointRegistry` from `transport_api` once production callers
  are gone.

Acceptance:

- Main production sources no longer import `com.xa.mass.transport.WorkerEndpointRegistry`.
- `CompositeWorkerEndpointRegistry` is removed or test-only residue is deleted.
- SDK/starter no longer exposes endpoint-registry override hooks.
- Runtime diagnostics still report bounded endpoint/connection information
  through diagnostics owner surfaces.
- Worker presence ingress no longer depends on endpoint registry assembly.

## PAFH-4 TransportDeliveryService Direct-Send Removal

Goal: keep `TransportDeliveryService` focused on queue/pull delivery.

Scope:

- Remove `TransportDeliveryService.sendDirect(...)`.
- Remove `TransportDeliverySender`.
- Move or delete direct-send counters:
  - if kept, record them from returned `DispatchOutcome` after
    `AdapterCommandExecutor.dispatch(...)`;
  - if deleted, update diagnostics and docs to stop promising direct counters.
- Update `TransportDeliveryServiceTest` to cover queue/pull behavior only.
- Add adapter executor tests that cover local send outcome normalization.

Acceptance:

- No production source calls `sendDirect(...)`.
- `TransportDeliveryService` no longer knows about push final-hop sender
  callbacks.
- Queue/pull store tests still pass.
- Push adapter outcome tests cover delivered, no endpoint, unavailable, failed,
  invalid, and empty batch behavior.
  `UNAVAILABLE` is runtime/binding-level evidence; executor tests should only
  cover it when the executor has a real missing local dependency after
  construction.

## PAFH-5 Docs, Proof, And Residue Scan

Goal: lock in the simplified final-hop boundary and remove stale roadmap
narratives.

Scope:

- Update `transport/AGENTS.md`.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Update or supersede:
  - `TRANSPORT_PUSH_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`
  - `TRANSPORT_WEBSOCKET_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`
  - `TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md`
- Run a residue scan for:
  - `WorkerEndpointRegistry`
  - `CompositeWorkerEndpointRegistry`
  - `sendDirect(`
  - `TransportDeliverySender`
  - `SocketCommandDispatchContext`
  - `WebSocketCommandDispatchContext`
  - `SelectedWorkerSender`
  - `SelectedWorkerRegistry`
  - executor-owned `adapterId` fields or constructor parameters
  - push-executor local `deliveryBucketId + selectedWorkerId` lookup
  - server-session-handle assigned-delivery lookup methods

Acceptance:

- Active docs no longer describe endpoint registry or sender wrappers as the
  target final-hop shape.
- Guards protect against wrapper/interface reintroduction.
- Completed predecessor roadmaps are either updated as historical context or
  archived after residue scan.

## Verification Candidates

Focused runtime and guard proof:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportDeliveryCommandListenerTest,TransportDeliveryServiceTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Push adapter proof:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,WebSocketSessionControllerTest,DispatcherInboundHandlerTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,SocketTransportServerTest,SocketTransportFrameCodecTest -Dsurefire.failIfNoSpecifiedTests=false
```

SDK/starter assembly and diagnostics proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,MassApplicationDistributedTransportTest,TransportQueueDiagnosticsMapperTest,MassApplicationStopOrderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Compile safety:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests test-compile
```

The exact test names must be corrected in PAFH-0 if current source has renamed
or deleted listed tests.

## Completion Criteria

This roadmap is complete only when all of these are true:

- Push adapter assigned delivery does not use `WorkerEndpointRegistry`.
- Push adapter assigned delivery does not call
  `TransportDeliveryService.sendDirect(...)`.
- `TransportDeliveryService` owns queue/pull delivery only.
- `AdapterCommandExecutor` implementations return `DispatchOutcome` directly.
- `AdapterCommandExecutor` implementations do not require adapter id as a
  constructor/input fact; adapter id stays in binding/evidence/bootstrap
  metadata.
- Push adapter final-hop local lookup uses one unique worker id
  (`selectedWorkerId` / `workerId`) and not `deliveryBucketId + workerId`.
- WebSocket and Socket command executors have no command-context wrappers.
- Concrete adapter classes do not implement multiple role interfaces unless
  explicitly documented as the top-level adapter manager/assembly point.
- Diagnostics and lifecycle are not methods on the assigned-delivery send
  interface.
- `transport_api` contains no embedded Java final-hop registry interface.
- Owner docs, proof registry, and architecture guards match the implemented
  final-hop shape.
