# Transport WebSocket Adapter Session Capability Convergence Roadmap

Status: proposed implementation roadmap; WebSocket-specific child of
`TRANSPORT_PUSH_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`.

## Summary

The outer assigned-delivery path is now mostly clean:

```text
DeliveryCommand -> AdapterCommandExecutor -> selected-worker final-hop attempt -> DispatchOutcome
```

The WebSocket adapter still has an internal owner problem below that boundary.
`WebSocketTaskDispatchChannel` is already narrow, but `ServerSessionManager`
still combines session indexing, selected-worker send, endpoint lease
projection, worker session-presence projection, refresh-loop scheduling,
raw/manual route send, diagnostics, shutdown, and replacement behavior.

This roadmap makes WebSocket adapter internals follow explicit capabilities:

```text
session store
selected-worker sender
session evidence driver
refresh loop
raw/manual side-channel
diagnostics inspector
```

The goal is not a new public adapter runtime. The goal is to make the embedded
Java WebSocket adapter understandable, testable, and resistant to leaking raw
route/session concepts back into assigned delivery.

## Relationship To Other Roadmaps

- `TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` owns the completed
  command-executor boundary context. This roadmap owns the WebSocket internal
  session-capability split below that boundary.
- `TRANSPORT_PUSH_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md` remains the
  umbrella for push adapters. This WebSocket roadmap is the first concrete
  implementation track; Socket should follow after this shape is proven.
- `TRANSPORT_ROUTE_KEY_REMOVAL_CONVERGENCE_ROADMAP.md` owns route-key removal.
  This roadmap may isolate route-key usage behind adapter-local session/raw
  roles, but it must not remove or rename routeKey as its first move.

## Current Code Observations

These observations are code-grounded as of this roadmap creation and must be
rechecked before implementation because transport is actively changing.

- `WebSocketTaskDispatchChannel` implements `AdapterCommandExecutor` and sends
  each `DeliveryCommand` by `selectedWorkerId` through
  `WorkerEndpointRegistry.sendToSelectedWorker(...)`.
- `WebSocketCommandDispatchContext` carries only adapter id plus
  `WorkerEndpointRegistry`, so the command executor is already close to the
  desired assigned-delivery shape.
- `ServerSessionManager` implements `WorkerEndpointRegistry`, owns
  `RouteEndpointIndex`, channel/session maps, selected-worker send, raw route
  send helpers, endpoint snapshots, endpoint lease publisher wiring, worker
  presence publisher wiring, refresh-loop executor state, shutdown, and session
  replacement behavior.
- `WebSocketTransportAdapterBootstrap` resolves one `ServerSessionManager` and
  passes it into command dispatch, raw route registry, endpoint inspector, and
  server wiring.
- `DispatcherInboundHandler` directly depends on `ServerSessionManager` for
  session registration and bound-session lookup during inbound frame handling.
- `WebSocketRawWorkerRouteEndpointRegistry` and `WebSocketEndpointInspector`
  are separate classes, but both still delegate into `ServerSessionManager`.
- `TransportServerFactoryContext` currently exposes `WorkerEndpointRegistry`.
  The WebSocket bootstrap passes the session manager through that shape, so
  custom WebSocket server factories can accidentally depend on the broad
  manager role.
- `WebSocketSessionOpenFrameReader` still accepts or derives a route-key-like
  endpoint address from worker-group input. That is route-key removal residue,
  not the first concern of this roadmap.

## Owner Review

WebSocket adapter owns protocol session mechanics:

- Netty channel lifecycle
- handshake/session-open interpretation
- adapter-local session indexes
- selected-worker local send attempt
- raw/manual WebSocket route side-channel when explicitly retained
- adapter-local diagnostics

Transport runtime owns shared runtime shapes:

- endpoint lease and selected-worker consumer evidence contracts
- worker session-presence ingress shape
- delivery outcomes
- binding/contribution assembly

Worker runtime owns worker lifecycle and reachability projection. Engine owns
worker selection, retry, reassign, compensation, and task attempt policy.

Therefore `ServerSessionManager` may orchestrate WebSocket session events, but
it should not remain the object registered as every WebSocket adapter capability.

## Boundary Decision

Assigned delivery must enter the WebSocket adapter through one path:

```text
DeliveryCommand -> WebSocketTaskDispatchChannel -> WebSocketSelectedWorkerSender
```

The selected-worker sender may only use `adapterId`, `selectedWorkerId`, and
the already-opaque worker payload to attempt a local channel write. It must not
touch endpoint lease stores, worker-presence ingress, raw route channels, or
diagnostic inspectors.

Session evidence must enter the runtime through one path:

```text
WebSocket protocol session event -> WebSocketSessionEvidenceDriver
```

The evidence driver may project connect, heartbeat, disconnect, and replacement
events to `TransportEndpointLeasePublisher` and
`WorkerPresenceSessionPublisher`. It does not choose workers and does not own
task retry policy.

Raw/manual route send and endpoint diagnostics remain side roles:

```text
RawWorkerRouteEndpointRegistry -> WebSocketSessionStore
WorkerEndpointInspector -> WebSocketSessionStore
```

They must not be used as assigned-delivery fallback.

## Target Shape

Target internal roles:

```text
WebSocketSessionRecord
  -> deliveryBucketId, endpointAddress, workerId, sessionHandle, channel,
     optional ChannelHandlerContext

WebSocketSessionStore
  -> bind / remove / lookup / snapshot active WebSocket sessions
  -> owns RouteEndpointIndex usage and channel/session maps

WebSocketSelectedWorkerSender
  -> selectedWorkerId + payload -> local channel write
  -> backs WorkerEndpointRegistry for assigned delivery

WebSocketSessionEvidenceDriver
  -> connected / heartbeat / disconnected / replaced
  -> delegates to endpoint lease and worker-presence publishers

WebSocketSessionRefreshLoop
  -> scheduled heartbeat projection for active session records

WebSocketSessionController
  -> orchestrates add/remove/shutdown/replacement by calling store, evidence
     driver, and refresh loop
  -> may keep the current `ServerSessionManager` name during the first slice,
     but its role must narrow before completion

WebSocketRawWorkerRouteEndpointRegistry
  -> raw/manual side-channel backed by session store

WebSocketEndpointInspector
  -> bounded diagnostics backed by session store
```

Target bootstrap contribution:

```text
WebSocketTransportAdapterBootstrap
  -> creates session store
  -> creates selected-worker sender/registry
  -> creates evidence driver and refresh loop
  -> creates session controller
  -> contributes TransportBinding with WebSocketTaskDispatchChannel
  -> contributes raw channel and endpoint inspector as explicit side roles
  -> creates server with session controller, not a broad endpoint registry
```

## Non-Goals

- No Socket adapter rewrite in the first implementation pass.
- No routeKey removal or public wire-field rename.
- No raw/manual side-channel deletion.
- No public SDK worker API change.
- No external or cross-language adapter protocol.
- No worker lifecycle, scheduling, retry, compensation, or result convergence
  change.
- No new abstract adapter base class or same-module pass-through wrapper.

## Do Not Start With

- Do not start by deleting or renaming `routeKey`.
- Do not start by moving WebSocket frame classes into `transport_api`.
- Do not make `ServerSessionManager` thinner by adding pass-through wrappers
  while still registering the manager as every capability.
- Do not use raw route send as a fallback for assigned delivery.
- Do not rewrite Socket first. Socket can follow the WebSocket-proven shape.

## WSA-0 Inventory And Proof Boundary

Goal: make the current broad manager roles explicit before moving code.

Scope:

- Inventory `ServerSessionManager` methods by role:
  - session store/index
  - selected-worker send
  - raw route send
  - endpoint lease projection
  - worker presence projection
  - refresh loop
  - shutdown/replacement
  - diagnostics
- Inventory production callers of `ServerSessionManager`, especially
  `WebSocketTransportAdapterBootstrap`, `DispatcherInboundHandler`,
  `WebSocketServerImpl`, `WebSocketRawWorkerRouteEndpointRegistry`, and
  `WebSocketEndpointInspector`.
- Classify `TransportServerFactoryContext.getEndpointRegistry()` as either a
  retained embedded WebSocket server-factory seam or a follow-up narrowing
  target. It must not stay ambiguous.

Acceptance:

- The inventory separates production usage from tests.
- Every `ServerSessionManager` public method has one target owner.
- The first implementation slice has an executable verification set using
  existing WebSocket tests.
- No implementation starts by route-key removal.

## WSA-1 Session Store Extraction

Goal: move WebSocket session indexes out of the broad manager.

Scope:

- Add adapter-local `WebSocketSessionRecord`.
- Add adapter-local `WebSocketSessionStore`.
- Move `RouteEndpointIndex`, channel/session maps, retired-channel tracking,
  active-connection count, and snapshot lookup into the store.
- Keep current behavior for duplicate worker replacement, shared route/address
  workers, and retired channel protection.
- Keep `ServerSessionManager` as the orchestrator for this slice only.

Acceptance:

- `ServerSessionManager` no longer directly owns `RouteEndpointIndex` or the
  low-level session maps.
- Existing behavior tests still prove:
  - shared endpoint address does not cross-deliver selected workers
  - replacing a worker channel retires the old channel
  - shutdown closes active channels and clears session state
  - diagnostic snapshots preserve current fields
- `WebSocketSessionStoreTest` or equivalent focused tests cover bind, remove,
  lookup by worker, lookup by endpoint address, snapshot, and shutdown clear.

## WSA-2 Selected-Worker Sender Extraction

Goal: make assigned delivery depend on a selected-worker sender, not the session
manager.

Scope:

- Add `WebSocketSelectedWorkerSender` or equivalent adapter-local role.
- The sender uses `WebSocketSessionStore` to locate the active record for
  `selectedWorkerId` and writes the payload to that channel.
- If a `WorkerEndpointRegistry` implementation remains needed by
  `TransportBinding` assembly, it must be a thin selected-worker registry backed
  by the sender/store, not the session controller.
- Update `WebSocketCommandDispatchContext` so it receives the selected-worker
  send role or selected-worker-only registry.
- Update `WebSocketTransportAdapterBootstrap` to register the selected-worker
  registry in `CompositeWorkerEndpointRegistry`; do not register
  `ServerSessionManager`.

Acceptance:

- `ServerSessionManager` no longer implements `WorkerEndpointRegistry`.
- `WebSocketTaskDispatchChannel` cannot import or receive
  `ServerSessionManager`.
- Assigned delivery still calls `TransportDeliveryService.sendDirect(...)` and
  returns `DELIVERED`, `NO_ENDPOINT`, or `UNAVAILABLE` as before.
- A guard or focused test fails if the WebSocket command executor can reach raw
  route, endpoint lease, worker presence, or diagnostics roles.

## WSA-3 Session Evidence Driver Extraction

Goal: isolate endpoint lease and worker presence projection from session
orchestration.

Scope:

- Add `WebSocketSessionEvidenceDriver`.
- Move use of `TransportEndpointLeasePublisher` and
  `WorkerPresenceSessionPublisher` behind the driver.
- Replace `ServerSessionManager.setEndpointLeaseStore(...)`,
  `setDeliveryCommandConsumerRegistry(...)`, and
  `setWorkerPresenceIngress(...)` with construction-time evidence-driver
  wiring through bootstrap/runtime context.
- The session controller calls driver methods for connected, heartbeat,
  disconnected, replaced, and shutdown cases.

Acceptance:

- The session controller no longer directly constructs or mutates endpoint
  lease publishers or worker presence publishers.
- Reconnect/replacement remains stale-session safe: old channel release cannot
  remove the new channel evidence.
- Shared endpoint address still preserves peer worker endpoint evidence when
  one worker disconnects.
- Focused evidence-driver tests cover connect, heartbeat, disconnect,
  replacement, and shutdown release.

## WSA-4 Refresh Loop Extraction

Goal: keep scheduled evidence refresh separate from session storage and command
send.

Scope:

- Add `WebSocketSessionRefreshLoop`.
- The refresh loop gets active session records from `WebSocketSessionStore` and
  calls `WebSocketSessionEvidenceDriver.heartbeat(...)`.
- The session controller starts/stops the loop based on active session state and
  shutdown.
- Keep current lease refresh cadence unless a focused test proves a safer
  change is needed.

Acceptance:

- Refresh scheduling code is not in selected-worker sender or raw route
  registry.
- Shutdown cancels refresh and releases active session evidence.
- Refresh failure is logged and does not corrupt the session store.
- Existing shutdown/replacement tests still pass.

## WSA-5 Raw And Diagnostics Retarget

Goal: keep raw/manual side-channel and diagnostics explicit without using the
session controller as their backing object.

Scope:

- Retarget `WebSocketRawWorkerRouteEndpointRegistry` to
  `WebSocketSessionStore` or a raw-route sender backed by the store.
- Retarget `WebSocketEndpointInspector` to `WebSocketSessionStore`.
- Keep raw/manual behavior unchanged unless route-key-removal roadmap later
  decides to delete or rename it.

Acceptance:

- Raw/manual route send cannot be called from assigned-delivery command
  execution.
- Endpoint inspector reads bounded snapshots only and does not publish endpoint
  lease, presence, or lifecycle facts.
- `ServerSessionManager` is not required to construct raw route or inspector
  contributions.

## WSA-6 Bootstrap And Server Factory Boundary

Goal: make WebSocket bootstrap outputs explicit and stop passing the broad
manager across extension seams.

Scope:

- Update `WebSocketTransportAdapterBootstrap` to create and wire the explicit
  WebSocket roles.
- Review `TransportServerFactoryContext`. If it remains public embedded support,
  it should expose only the server-facing session controller or a clearly named
  WebSocket-specific server-session handle, not a generic
  `WorkerEndpointRegistry` that suggests assigned-delivery ownership.
- Update `WebSocketServerImpl` and `DispatcherInboundHandler` to depend on the
  session controller/session lookup role rather than the old manager shape.

Acceptance:

- Bootstrap contribution lists are explicit:
  - command executor
  - server
  - raw worker message channel
  - endpoint inspector
- Custom server factory context no longer leaks assigned-delivery
  `WorkerEndpointRegistry` unless it is explicitly classified as an
  embedded-only transitional seam with a follow-up removal item.
- Existing server/inbound tests pass.

## WSA-7 Guards, Owner Docs, And Socket Handoff

Goal: lock in the WebSocket split and leave Socket as a deliberate follow-up,
not hidden residue.

Scope:

- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` with the current
  WebSocket role split after implementation.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` only if a transport-wide
  contract changes.
- Update `doc/PROOF_REGISTRY.md` if proof ownership changes.
- Add or update architecture guards:
  - WebSocket command executor must not import `ServerSessionManager`, raw
    route registry, endpoint lease publisher, worker presence publisher, or
    endpoint inspector.
  - `ServerSessionManager` must not implement `WorkerEndpointRegistry` after
    WSA-2.
  - raw route and diagnostics must not be reachable from assigned-delivery
    command execution.
- Update the umbrella push-adapter roadmap to reflect that WebSocket has a
  concrete implementation roadmap and Socket remains a later track.

Acceptance:

- Guard tests fail on the old broad-manager shape.
- WebSocket owner docs describe current implementation, not only target state.
- Socket follow-up remains visible without blocking WebSocket archive.

## Verification Candidates

Focused WebSocket adapter proof:

```bash
./mvnw -q -pl transport/websocket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest,WebSocketOutputProcessorTest
```

Transport runtime guard proof:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,CompositeWorkerEndpointRegistryTest,RouteEndpointIndexTest
```

SDK/server realtime assembly proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassApplicationDistributedTransportTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Compile safety:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
```

The exact test names should be corrected during WSA-0 if current source has
renamed or deleted any listed tests.

## Completion Criteria

This roadmap is complete only when all of these are true:

- `ServerSessionManager` no longer implements `WorkerEndpointRegistry`.
- Assigned WebSocket delivery depends only on a selected-worker send role and
  delivery outcome normalization.
- WebSocket session storage/indexing is isolated from evidence projection,
  refresh scheduling, raw/manual route send, and diagnostics.
- Endpoint lease and worker-presence publication are owned by a dedicated
  WebSocket session evidence driver.
- Raw/manual and diagnostics are explicit side-channel contributions and cannot
  be used as assigned-delivery fallback.
- Bootstrap/server factory surfaces no longer leak the broad session manager as
  the adapter capability object.
- Owner docs and guards match the implemented role split.
- Socket follow-up is either updated with the proven WebSocket pattern or
  deliberately kept as active residue in the push-adapter umbrella roadmap.
