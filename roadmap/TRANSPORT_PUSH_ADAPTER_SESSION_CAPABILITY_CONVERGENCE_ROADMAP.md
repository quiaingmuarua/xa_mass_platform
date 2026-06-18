# Transport Push Adapter Session Capability Convergence Roadmap

Status: superseded by
`TRANSPORT_PUSH_ADAPTER_FINAL_HOP_BOUNDARY_CONVERGENCE_ROADMAP.md`.
Retained only as historical push-adapter session notes until archive.
Do not treat lower current-code observations or verification commands in this
file as current contracts.

WebSocket implementation detail is now tracked by
`TRANSPORT_WEBSOCKET_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`.
This file remains the push-adapter umbrella and Socket follow-up holder.

## Summary

Polling adapter internals now use explicit capability objects:

```text
PollingDeliveryExecutor
PollingDeliveryPullChannel
PollingSessionEvidenceDriver
```

WebSocket and Socket assigned-delivery executors are already narrow enough for
the current command path. WebSocket now uses explicit session store,
session controller, evidence driver, refresh loop, server handle, raw
side-channel, and diagnostics roles. Socket still carries the older broad
session-manager shape and should follow the WebSocket-proven split.

This roadmap tracks the push-adapter follow-up so the polling roadmap can close
without pretending WebSocket/socket session managers have been rewritten. The
first concrete implementation track is the WebSocket-specific roadmap; Socket
should follow after the WebSocket shape is proven.

## Current Facts

- `WebSocketTaskDispatchChannel` implements `AdapterCommandExecutor`, looks up
  the selected worker in `WebSocketSessionStore`, writes the WebSocket frame,
  and returns `DispatchOutcome` directly.
- `SocketTaskDispatchChannel` implements `AdapterCommandExecutor` and sends by
  `selectedWorkerId` after socket frame encoding.
- WebSocket no longer has `ServerSessionManager`; assigned delivery is owned by
  `WebSocketTaskDispatchChannel`, server wiring uses
  `WebSocketServerSessionHandle`, session state is indexed by
  `WebSocketSessionStore`, and session evidence uses
  `WebSocketSessionEvidenceDriver`.
- `SocketSessionManager` mirrors the same broad session-owner shape for socket
  workers.
- Raw/manual route side-channels and diagnostics are already in dedicated
  classes. In WebSocket they are backed by `WebSocketSessionStore`; in Socket
  they still delegate into the broad session manager.
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
WebSocketTaskDispatchChannel / future Socket command executor
  -> selectedWorkerId + payload/frame local send
  -> reads session store and returns DispatchOutcome directly

WebSocketSessionStore / SocketSessionStore
  -> session handle / worker id / endpoint address index

WebSocketSessionController / SocketSessionController
  -> server/session orchestration only

WebSocketSessionEvidenceDriver / SocketSessionEvidenceDriver
  -> connect / heartbeat / disconnect / refresh-loop evidence projection

WebSocketRawRouteChannel / SocketRawRouteChannel
  -> optional operator side-channel, outside assigned delivery

WebSocketEndpointInspector / SocketEndpointInspector
  -> bounded diagnostics only
```

The command executor should not use `TransportDeliveryService.sendDirect(...)`
as the push mainline. WebSocket has already moved to direct outcome
production; Socket should stop depending on a broad session manager before this
umbrella closes.

## Non-Goals

- No polling changes; polling is the reference shape.
- No route-key removal.
- No raw/manual side-channel removal.
- No external or cross-language adapter protocol.
- No public SDK worker API change.
- No worker lifecycle, scheduling, retry, compensation, or result correctness
  change.

## PSA-0 Inventory And Proof Boundaries

WebSocket note: the concrete WebSocket implementation is owned in more detail
by `TRANSPORT_WEBSOCKET_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`.
Keep this umbrella section as the cross-adapter checklist and Socket reminder.

Goal: classify WebSocket/socket session-manager roles before any rewrite.

Scope:

- Inventory `SocketSessionManager` methods by role: selected-worker send,
  session store, endpoint evidence, presence evidence, refresh loop,
  shutdown/replacement, raw/manual send, diagnostics. Use the WebSocket split
  as the reference shape.
- Separate production usage from tests.
- Identify which behavior tests must stay green before and after the split.

Acceptance:

- Inventory names every production caller of the session managers.
- Raw/manual side-channel and diagnostics callers are classified separately
  from assigned delivery.
- No implementation starts by renaming routeKey, adapterId, or manager class.

## PSA-1 Selected-Worker Endpoint Role Extraction

Goal: make push command executors depend on a narrow selected-worker endpoint
role instead of a broad session manager.

Scope:

- Treat WebSocket as the proven shape: `WebSocketTaskDispatchChannel` owns
  final-hop frame write and outcome production, while `WebSocketSessionStore`
  owns lookup/state and `WebSocketSessionController` owns server/session
  orchestration. Do not reintroduce WebSocket `SelectedWorkerSender` or
  `SelectedWorkerRegistry` wrappers.
- Move Socket `sendToSelectedWorker(...)` behind an equivalent narrow endpoint
  role or split its broad manager so the command executor cannot reach raw,
  evidence, or diagnostics behavior through the same object.
- Keep `WebSocketTaskDispatchChannel` and `SocketTaskDispatchChannel` as
  command executors.
- Keep raw/manual route send outside the selected-worker endpoint role.

Acceptance:

- Command executors cannot call endpoint lease, worker-presence, raw-route, or
  diagnostics APIs.
- Command executors still send by `selectedWorkerId`.
- WebSocket does not regain `WebSocketSelectedWorkerSender` or
  `WebSocketSelectedWorkerRegistry`.
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
hidden capabilities of the selected-worker endpoint role or command executor.

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
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,WebSocketSessionControllerTest,SocketSessionManagerTest,SocketTransportServerTest,WebSocketFrameReadersTest,SocketTransportFrameCodecTest
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
