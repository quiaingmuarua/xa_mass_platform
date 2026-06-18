# Transport Push Adapter Session Capability Convergence Roadmap

Status: proposed follow-up after polling adapter capability split.

## Summary

Polling adapter internals now use explicit capability objects:

```text
PollingDeliveryExecutor
PollingDeliveryPullChannel
PollingSessionEvidenceDriver
```

WebSocket and Socket assigned-delivery executors are already narrow enough for
the current command path, but their session managers still combine selected
worker session indexing, final-hop send, endpoint lease projection, worker
session-presence projection, refresh-loop ownership, raw/manual route
side-channels, and diagnostics.

This roadmap tracks the push-adapter follow-up so the polling roadmap can close
without pretending WebSocket/socket session managers have been rewritten.

## Current Facts

- `WebSocketTaskDispatchChannel` implements `AdapterCommandExecutor` and sends
  by `selectedWorkerId` through `WorkerEndpointRegistry.sendToSelectedWorker`.
- `SocketTaskDispatchChannel` implements `AdapterCommandExecutor` and sends by
  `selectedWorkerId` after socket frame encoding.
- `ServerSessionManager` implements `WorkerEndpointRegistry`, owns session
  indexes, selected-worker send, endpoint lease publisher wiring,
  worker-presence publisher wiring, endpoint lease refresh-loop scheduling, and
  shutdown/replacement behavior.
- `SocketSessionManager` mirrors the same broad session-owner shape for socket
  workers.
- Raw/manual route side-channels and diagnostics are already in dedicated
  wrapper classes, but those wrappers still delegate into the broad session
  managers.
- Push adapter bootstrap creates a command executor plus server/raw/diagnostic
  contributions, while the session manager remains the shared object behind
  several roles.

## Owner Review

Push adapters own protocol session mechanics and selected-worker final-hop
send attempts.

Transport runtime owns endpoint lease, selected-worker consumer evidence,
dispatch outcomes, and worker session-presence ingress shape.

Worker runtime owns worker lifecycle and reachability projection.

Therefore the push session manager can observe and orchestrate connection
events, but it should not remain the object that directly represents every
adapter capability.

## Target Shape

The target is role separation, not a public adapter runtime:

```text
WebSocketSelectedWorkerSender / SocketSelectedWorkerSender
  -> selectedWorkerId + payload/frame -> local send attempt

WebSocketSessionStore / SocketSessionStore
  -> session handle / worker id / endpoint address index

WebSocketSessionEvidenceDriver / SocketSessionEvidenceDriver
  -> connect / heartbeat / disconnect / refresh-loop evidence projection

WebSocketRawRouteChannel / SocketRawRouteChannel
  -> optional operator side-channel, outside assigned delivery

WebSocketEndpointInspector / SocketEndpointInspector
  -> bounded diagnostics only
```

The command executor may keep using `TransportDeliveryService.sendDirect(...)`,
but it should depend on a selected-worker sender, not on a broad session
manager.

## Non-Goals

- No polling changes; polling is the reference shape.
- No route-key removal.
- No raw/manual side-channel removal.
- No external or cross-language adapter protocol.
- No public SDK worker API change.
- No worker lifecycle, scheduling, retry, compensation, or result correctness
  change.

## PSA-0 Inventory And Proof Boundaries

Goal: classify WebSocket/socket session-manager roles before any rewrite.

Scope:

- Inventory `ServerSessionManager` and `SocketSessionManager` methods by role:
  selected-worker send, session store, endpoint evidence, presence evidence,
  refresh loop, shutdown/replacement, raw/manual send, diagnostics.
- Separate production usage from tests.
- Identify which behavior tests must stay green before and after the split.

Acceptance:

- Inventory names every production caller of the session managers.
- Raw/manual side-channel and diagnostics callers are classified separately
  from assigned delivery.
- No implementation starts by renaming routeKey, adapterId, or manager class.

## PSA-1 Selected-Worker Sender Extraction

Goal: make command executors depend on a selected-worker sender role instead of
the broad session manager.

Scope:

- Introduce adapter-local selected-worker sender classes or interfaces in each
  concrete adapter module.
- Move `sendToSelectedWorker(...)` implementation behind the selected-worker
  sender role.
- Keep `WebSocketTaskDispatchChannel` and `SocketTaskDispatchChannel` as
  command executors.
- Keep raw/manual route send outside the selected-worker sender.

Acceptance:

- Command executors cannot call endpoint lease, worker-presence, raw-route, or
  diagnostics APIs.
- Command executors still send by `selectedWorkerId`.
- Existing selected-worker delivery tests pass.

## PSA-2 Session Evidence Driver Extraction

Goal: move endpoint lease and worker-presence projection orchestration out of
the broad session manager.

Scope:

- Introduce WebSocket/socket session evidence drivers that delegate to
  `TransportEndpointLeasePublisher` and `WorkerPresenceSessionPublisher`.
- Session managers may observe protocol events and call the driver.
- Refresh-loop ownership is classified explicitly: either evidence driver owns
  it, or a dedicated refresh loop does.

Acceptance:

- Session managers no longer construct or directly depend on endpoint lease
  record classes, selected-worker consumer claim classes, or worker-presence
  event classes.
- Stale session behavior and replacement/shutdown behavior remain unchanged.
- Existing session manager tests pass with added evidence-driver tests.

## PSA-3 Raw And Diagnostics Boundary Tightening

Goal: keep raw/manual channels and diagnostics as explicit side roles, not
hidden capabilities of the selected-worker sender or command executor.

Scope:

- Keep or narrow `WebSocketRawWorkerRouteEndpointRegistry` and
  `SocketRawWorkerRouteEndpointRegistry`.
- Keep or narrow endpoint inspectors.
- Add guards that command executors do not import raw/manual or inspector
  contracts.

Acceptance:

- Raw/manual route send cannot be used as assigned-delivery fallback.
- Diagnostics stay bounded and do not drive endpoint lease or worker lifecycle
  truth.

## Verification Candidates

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest,SocketTransportServerTest,WebSocketFrameReadersTest,SocketTransportFrameCodecTest
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,CompositeWorkerEndpointRegistryTest,RouteEndpointIndexTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassApplicationDistributedTransportTest,ExternalWorkerRealtimeRegistrationIntegrationTest
```

## Completion Criteria

- WebSocket/socket command executors depend only on selected-worker send roles
  plus delivery outcome normalization.
- WebSocket/socket endpoint/session evidence projection is behind explicit
  evidence drivers or dedicated refresh-loop owners.
- Raw/manual and diagnostics remain side-channel contributions outside assigned
  delivery.
- Guards prevent command executors from importing lease, presence, raw-route,
  or diagnostics roles.
- Transport owner docs and proof registry are updated after implementation.
