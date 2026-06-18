# Transport WebSocket Adapter Session Capability Convergence Roadmap

Status: WebSocket mainline implemented; guards/docs and Socket follow-up remain
tracked through `TRANSPORT_PUSH_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`.

## Summary

The outer assigned-delivery path is now mostly clean:

```text
DeliveryCommand -> AdapterCommandExecutor -> selected-worker final-hop attempt -> DispatchOutcome
```

The WebSocket adapter still has an internal owner problem below that boundary.
`WebSocketTaskDispatchChannel` is narrow and the former `ServerSessionManager`
shape has been split. The active implementation keeps session indexing,
selected-worker send, endpoint lease projection, worker session-presence
projection, refresh-loop scheduling, raw/manual route send, diagnostics,
shutdown, and replacement behavior in separate adapter-local roles.

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

## Before Convergence

These observations describe the old shape that motivated this roadmap.

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
- `WebSocketAdapterConfig` exposes
  `TransportServerFactory<TransportServerFactoryContext>` through SDK/starter
  embedded assembly options. This is not purely adapter-internal; narrowing the
  server factory context must be part of the same executable slice that removes
  `ServerSessionManager` from assigned-delivery registry ownership.
- `TransportAdapterContribution` is already an explicit adapter output for
  bindings, servers, raw side-channels, and diagnostics, but selected-worker
  endpoint registries are still registered by mutating the runtime-owned
  `CompositeWorkerEndpointRegistry` passed through
  `TransportAdapterBootstrapContext`. The roadmap must keep that registration
  sink explicit instead of hiding it behind the session manager.
- `WebSocketSessionOpenFrameReader` still accepts or derives a route-key-like
  endpoint address from worker-group input. That is route-key removal residue,
  not the first concern of this roadmap.

## Implemented Current Shape

- `ServerSessionManager` has been removed from production code.
- `WebSocketSessionStore` owns WebSocket session maps, `RouteEndpointIndex`,
  retired-channel tracking, active connection count, and session snapshots.
- `WebSocketSessionController` owns server-facing bind/remove/shutdown
  orchestration only and implements `WebSocketServerSessionHandle`.
- `WebSocketSelectedWorkerSender` and `WebSocketSelectedWorkerRegistry` own
  selected-worker final-hop send for assigned delivery.
- `WebSocketSessionEvidenceDriver` owns endpoint lease and worker-presence
  projection; `WebSocketSessionRefreshLoop` owns periodic evidence refresh.
- `WebSocketRawWorkerRouteEndpointRegistry` and `WebSocketEndpointInspector`
  read `WebSocketSessionStore` directly as side roles.
- `WebSocketServerFactoryContext` is WebSocket-specific and exposes a
  server-facing session handle plus inbound raw-message sink, port, and path.
  The old runtime-wide `TransportServerFactoryContext` is removed.
- `WebSocketTransportAdapterBootstrap` registers only
  `WebSocketSelectedWorkerRegistry` with the runtime-owned selected-worker
  endpoint registry sink.

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
- runtime-owned selected-worker endpoint registry aggregation

SDK/starter owns embedded Java assembly surfaces such as custom
`TransportServerFactory` wiring. That surface may expose a server-facing
WebSocket session handle, but it must not expose assigned-delivery
`WorkerEndpointRegistry` as a generic server dependency.

Worker runtime owns worker lifecycle and reachability projection. Engine owns
worker selection, retry, reassign, compensation, and task attempt policy.

Therefore WebSocket session events are orchestrated by a narrow
`WebSocketSessionController`, while selected-worker send, session evidence,
refresh, raw/manual route send, and diagnostics live in separate roles. No
single WebSocket object should be registered as every adapter capability.

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

Server creation must use a server-facing session handle:

```text
WebSocketServerImpl / WebSocketServerFactoryContext
  -> WebSocketSessionController or WebSocketServerSessionHandle
```

It must not receive the selected-worker `WorkerEndpointRegistry` just because
the old manager happened to implement that interface.

## Target Shape

Target internal roles:

```text
WebSocketSessionRecord
  -> deliveryBucketId, endpointAddress, workerId, sessionHandle, channel,
     optional ChannelHandlerContext

WebSocketSessionStore
  -> bind / remove / lookup / snapshot active WebSocket sessions
  -> owns RouteEndpointIndex usage and channel/session maps
  -> returns explicit BindResult / RemoveResult records so replacement,
     retired-channel, active-count, and evidence publication decisions are not
     recomputed by callers

WebSocketSelectedWorkerSender
  -> selectedWorkerId + payload -> local channel write
  -> backs WorkerEndpointRegistry for assigned delivery

WebSocketSelectedWorkerRegistry
  -> selected-worker-only WorkerEndpointRegistry adapter backed by sender/store
  -> registered with the runtime-owned CompositeWorkerEndpointRegistry

WebSocketSessionEvidenceDriver
  -> connected / heartbeat / disconnected / replaced
  -> delegates to endpoint lease and worker-presence publishers

WebSocketSessionRefreshLoop
  -> scheduled heartbeat projection for active session records

WebSocketSessionController
  -> orchestrates add/remove/shutdown/replacement by calling store, evidence
     driver, and refresh loop

WebSocketServerSessionHandle
  -> server-facing session operations needed by DispatcherInboundHandler and
     custom TransportServerFactory implementations
  -> owns no assigned-delivery send capability

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
  -> registers selected-worker registry with the runtime-owned endpoint
     registry sink, or contributes it through an explicit contribution slot if
     that sink is moved to contribution output
  -> creates server with session controller/server-session handle, not a broad
     endpoint registry
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
- Do not remove the old broad-manager endpoint-registry role before the
  server-facing session handle / custom server-factory context has a
  replacement input. Otherwise WSA-2 will break server wiring or preserve the
  old manager under another name.
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
- Classify old `TransportServerFactoryContext.getEndpointRegistry()` as either a
  retained embedded WebSocket server-factory seam or a narrowing target. If it
  remains, the retained shape must be server-facing session control, not
  assigned-delivery endpoint registry access.
- Inventory the SDK/starter callers that expose
  `TransportServerFactory<TransportServerFactoryContext>` so the server factory
  context change is not treated as an adapter-internal rename.
- Classify selected-worker endpoint registry registration as either:
  - runtime-owned registry sink passed in through `TransportAdapterBootstrapContext`; or
  - explicit `TransportAdapterContribution` output.
  The roadmap must choose one before WSA-2 implementation.

Acceptance:

- The inventory separates production usage from tests.
- Every `ServerSessionManager` public method has one target owner.
- Server-facing session operations required by `DispatcherInboundHandler`,
  `WebSocketServerImpl`, and custom server factories are explicitly named.
- Selected-worker registry registration owner is explicit and has a guardable
  target shape.
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
- Define store result records before moving behavior:
  - `BindResult`: current record, previous worker record if replaced, unchanged
    flag, active-count delta, and whether the channel was retired/ignored.
  - `RemoveResult`: removed record, removed-current flag, retired-channel flag,
    active-count delta, and any records that require evidence release.
  Exact field names may differ, but the result must carry enough information
  that the controller does not re-read store internals to decide replacement,
  release, or active-count behavior.
- Keep current behavior for duplicate worker replacement, shared route/address
  workers, and retired channel protection.
- Keep session orchestration narrow; if a temporary manager name exists during
  implementation, it must not survive completion.

Acceptance:

- The WebSocket session controller no longer directly owns `RouteEndpointIndex`
  or the low-level session maps.
- Store bind/remove operations are the only place that mutates session maps,
  retired-channel tracking, and active-connection count.
- Store result records are covered by focused tests for unchanged bind,
  replacement, removed-current, stale/retired channel, and shared endpoint
  address cases.
- Existing behavior tests still prove:
  - shared endpoint address does not cross-deliver selected workers
  - replacing a worker channel retires the old channel
  - shutdown closes active channels and clears session state
  - diagnostic snapshots preserve current fields
- `WebSocketSessionStoreTest` or equivalent focused tests cover bind, remove,
  lookup by worker, lookup by endpoint address, snapshot, and shutdown clear.

## WSA-2 Server Handle And Selected-Worker Sender Boundary

Goal: split server-facing session control from assigned-delivery selected-worker
send before the broad manager stops implementing `WorkerEndpointRegistry`.

Scope:

- Add a server-facing WebSocket session handle, for example
  `WebSocketServerSessionHandle` or a narrowed `WebSocketSessionController`
  interface. It must contain only operations needed by `DispatcherInboundHandler`
  and server creation: bind/open session, read bound worker/session facts,
  remove/close session, and shutdown if needed.
- Update WebSocket server factory wiring so
  custom server factories receive the server-facing session handle instead of
  assigned-delivery `WorkerEndpointRegistry`.
- Update SDK/starter tests that capture the WebSocket server factory context so they
  prove the server-facing handle exists and no longer rely on
  `getEndpointRegistry()` as a server dependency.
- Add `WebSocketSelectedWorkerSender` or equivalent adapter-local role.
- The sender uses `WebSocketSessionStore` to locate the active record for
  `selectedWorkerId` and writes the payload to that channel.
- If a `WorkerEndpointRegistry` implementation remains needed by
  `TransportBinding` assembly, it must be a thin selected-worker registry backed
  by the sender/store, not the session controller.
- Update `WebSocketCommandDispatchContext` so it receives the selected-worker
  send role or selected-worker-only registry.
- Update `WebSocketTransportAdapterBootstrap` to register only the
  selected-worker registry in the runtime-owned registry sink; do not register
  `ServerSessionManager` or the server-facing session handle.
- If WSA-0 chose to move endpoint registry registration to
  `TransportAdapterContribution`, add that contribution slot in this slice and
  update `MassApplication` assembly in the same slice.

Acceptance:

- `ServerSessionManager` is removed from production code, or at minimum cannot
  implement `WorkerEndpointRegistry` during an intermediate slice.
- `WebSocketTaskDispatchChannel` cannot import or receive
  `ServerSessionManager`.
- `WebSocketServerFactoryContext` does not expose assigned-delivery
  `WorkerEndpointRegistry` as the server factory input.
- `DispatcherInboundHandler` and `WebSocketServerImpl` depend on the
  server-facing session handle/controller, not on `WorkerEndpointRegistry`.
- Assigned delivery still calls `TransportDeliveryService.sendDirect(...)` and
  returns `DELIVERED`, `NO_ENDPOINT`, or `UNAVAILABLE` as before.
- A guard or focused test fails if the WebSocket command executor can reach raw
  route, endpoint lease, worker presence, or diagnostics roles.
- `TransportConvergenceArchitectureGuardTest` or an equivalent guard fails if
  the old broad `ServerSessionManager` shape returns after this slice.

## WSA-3 Session Evidence Driver Extraction

Goal: isolate endpoint lease and worker presence projection from session
orchestration.

Scope:

- Add `WebSocketSessionEvidenceDriver`.
- Move use of `TransportEndpointLeasePublisher` and
  `WorkerPresenceSessionPublisher` behind the driver.
- Replace broad manager setter wiring with evidence-driver wiring through
  bootstrap/runtime context.
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
- The session controller is not required to construct raw route or inspector
  contributions.

## WSA-6 Bootstrap And Server Factory Boundary

Goal: make WebSocket bootstrap outputs explicit and stop passing the broad
manager across extension seams.

Scope:

- Update `WebSocketTransportAdapterBootstrap` to create and wire the explicit
  WebSocket roles.
- Finish any server-factory-context cleanup left by WSA-2. The final context
  must expose only server-facing session control plus inbound raw-message sink,
  port, and endpoint path. It must not expose the assigned-delivery
  `WorkerEndpointRegistry`.
- Finish the selected-worker registry registration decision made in WSA-0/WSA-2:
  either runtime-owned registry sink usage is documented and guarded, or
  endpoint registry contribution output exists and `MassApplication` assembly
  consumes it.
- Update `WebSocketServerImpl` and `DispatcherInboundHandler` to depend on the
  session controller/session lookup role rather than the old manager shape.

Acceptance:

- Bootstrap contribution lists are explicit:
  - command executor
  - selected-worker endpoint registry registration owner
  - server
  - raw worker message channel
  - endpoint inspector
- Custom server factory context no longer leaks assigned-delivery
  `WorkerEndpointRegistry`.
- SDK/starter public embedded assembly tests prove custom server factory wiring
  still works with the narrowed context.
- Existing server/inbound tests pass.

## WSA-7 Guards, Owner Docs, And Socket Handoff

Goal: lock in the WebSocket split and leave Socket as a deliberate follow-up,
not hidden residue.

Scope:

- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` with the current
  WebSocket role split after implementation.
  - Replace the old rule that one endpoint-registry instance is passed to both
    server and dispatcher wiring.
  - Document server/session-controller wiring separately from selected-worker
    sender/registry wiring.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` only if a transport-wide
  contract changes.
- Update `doc/PROOF_REGISTRY.md` if proof ownership changes.
- Add or update architecture guards as each slice lands, then consolidate them
  here:
  - WebSocket command executor must not import `ServerSessionManager`, raw
    route registry, endpoint lease publisher, worker presence publisher, or
    endpoint inspector.
  - the old broad `ServerSessionManager` production class must not return, and
    session control must not implement assigned-delivery `WorkerEndpointRegistry`.
  - `WebSocketServerFactoryContext` must not expose assigned-delivery
    `WorkerEndpointRegistry` after WSA-2/WSA-6.
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
./mvnw -q -pl transport/websocket-adapter -am -DskipTests compile
./mvnw -q -pl transport/websocket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,WebSocketSessionControllerTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest,WebSocketOutputProcessorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Transport runtime guard proof:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,CompositeWorkerEndpointRegistryTest,RouteEndpointIndexTest -Dsurefire.failIfNoSpecifiedTests=false
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

- `ServerSessionManager` is removed from production code and cannot implement
  `WorkerEndpointRegistry`.
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
- `WebSocketServerFactoryContext` does not expose assigned-delivery
  `WorkerEndpointRegistry`; custom embedded server factories receive a
  server-facing session handle instead.
- Selected-worker endpoint registry registration has exactly one documented
  owner: runtime registry sink or explicit contribution output.
- Owner docs and guards match the implemented role split.
- Socket follow-up is either updated with the proven WebSocket pattern or
  deliberately kept as active residue in the push-adapter umbrella roadmap.
