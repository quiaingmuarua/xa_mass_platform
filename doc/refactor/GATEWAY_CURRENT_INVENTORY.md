# Gateway Current Inventory

This inventory records the responsibilities that still exist inside the current WebSocket adapter in the current checkout after substantial convergence toward a task-only WebSocket adapter. The module now lives physically under `transport/websocket-adapter`; its current artifact identity is `xa-mass-transport-websocket`, while its Java package identity remains `com.xa.mass.gateway.*`.

It is a migration aid, not a compatibility promise.

## Inventory Format

- `Class`
- `Method`
- `Current responsibility`
- `Should stay in gateway?`
- `Target owner`
- `Migration phase`
- `Related tests`

## 1. Inbound Raw Frame Validation

- `Class`: `com.xa.mass.gateway.server.DispatcherInboundHandler`
- `Method`: `channelRead0(...)`
- `Current responsibility`: validates inbound text as JSON, extracts `workerId + messageId`, refreshes session reachability, and forwards raw JSON into the adapter inbound sink; current worker identity is handshake/session-led with frame-level fallback only to already-registered session identity
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`, `TaskApiIntegrationTest`

## 2. Transport Error Emission

- `Class`: `com.xa.mass.gateway.server.DispatcherInboundHandler`
- `Method`: `sendError(...)`, `exceptionCaught(...)`
- `Current responsibility`: emits transport-level error JSON for malformed payloads and channel failures
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`

## 3. WebSocket Server Lifecycle

- `Class`: `com.xa.mass.gateway.server.WebSocketServerImpl`
- `Method`: `start(...)`, `stop()`
- `Current responsibility`: boots the Netty WebSocket adapter and owns adapter lifecycle
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `TransportChannelWiringIntegrationTest`, `TaskApiIntegrationTest`

## 4. Session Reachability Registry

- `Class`: `com.xa.mass.gateway.session.ServerSessionManager`
- `Method`: `addSession(...)`, `removeSession(...)`, `sendMessage(...)`, `listWorkerEndpoints()`
- `Current responsibility`: tracks active transport endpoints by `workerId`, sends outbound frames, emits online/offline transport signals
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep with reachability-only semantics
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `PollingWorkerTaskFlowIntegrationTest`

## 5. Worker System Event Translation

- `Class`: `com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel`
- `Method`: `publishWorkerOnline(...)`, `publishWorkerOffline(...)`, `publishWorkerHeartbeat(...)`
- `Current responsibility`: exposes transport-fact translation into the transport-neutral worker system-event seam; current WebSocket ingress mainline actively uses connect/disconnect session facts, while heartbeat publication remains available but is not the primary ingress truth
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `TaskApiWorkerWithoutContextIntegrationTest`

## 6. WebSocket Compatibility Codec

- `Class`: `com.xa.mass.gateway.queue.WebSocketTransportFrameCodec`
- `Method`: `parseObject(...)`, `encodeCanonicalTaskDispatch(...)`, `decodeCanonicalTaskResult(...)`
- `Current responsibility`: converts current WebSocket raw JSON into canonical task objects and back
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep without expanding platform semantics
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketTaskDispatchChannelTest`, `RuntimeTaskResultIngestChannelTest`

## 7. Canonical Task Frame Detection

- `Class`: `com.xa.mass.gateway.queue.WebSocketTransportFrameCodec`
- `Method`: `isCanonicalTaskDispatch(...)`, `isCanonicalTaskResult(...)`
- `Current responsibility`: recognizes canonical task frames directly inside the codec without reintroducing an adapter-side route registry; `WebSocketInputProcessor` consumes canonical task-result detection rather than owning it
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep narrow; do not add new platform capability identities here
- `Related tests`: `WebSocketTransportFrameCodecTest`, `WebSocketInputProcessorTest`

## 8. Inbound / Outbound Adapter Orchestration

- `Class`: `com.xa.mass.gateway.dispatcher.WebSocketInputProcessor`, `com.xa.mass.gateway.dispatcher.WebSocketOutputProcessor`
- `Method`: `process(...)`
- `Current responsibility`: `WebSocketInputProcessor` turns raw inbound JSON into canonical task-result ingest calls; `WebSocketOutputProcessor` performs endpoint send and transport-delivery failure reporting; task-dispatch frame encoding remains in codec/publisher paths rather than inside these processors
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as explicit adapter processors, not as a generic middleware framework; runtime result-ingest ownership stays above transport binding
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketOutputProcessorTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 9. Queue-Based Adapter Dispatch Loop

- `Class`: `com.xa.mass.gateway.dispatcher.WebSocketMessageDispatcher`
- `Method`: `processInputQueueLoop()`, `processOutputQueueLoop()`, `submitOutputDelivery(...)`
- `Current responsibility`: consumes raw inbound JSON and `OutboundDelivery`, calls explicit adapter processors, preserves outbound ordering per `workerId`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketOutputProcessorTest`, `ServerMessageDispatcherShutdownTest`

## 10. Fixed Gateway Wiring Snapshot

- `Class`: `com.xa.mass.gateway.dispatcher.WebSocketDispatcherContext`
- `Method`: constructor + getters
- `Current responsibility`: exposes the fixed gateway-local wiring snapshot used by the adapter runtime; runtime assembly resolves the concrete endpoint registry once and injects the same instance into dispatcher wiring and transport-server creation; dispatcher-context assembly now lives in gateway runtime support instead of SDK-side `new WebSocketDispatcherContext(...)`, and `MassApplication` no longer retains this snapshot as a general runtime field after assembly
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as immutable adapter runtime wiring
- `Related tests`: `MassSdkTest`, `DispatcherInboundHandlerTest`

## 11. Gateway Embedded Runtime Support

- `Class`: `com.xa.mass.gateway.runtime.WebSocketEmbeddedRuntimeSupport`
- `Method`: `createEndpointRegistry(...)`, `createDispatcherContext(...)`, `resolveSystemEventChannel(...)`, `createRealtimeWorkerAdapter(...)`, `createTransportServer(...)`
- `Current responsibility`: keeps gateway-owned embedded-runtime defaults inside gateway, including endpoint-registry creation, dispatcher-context assembly, gateway-backed realtime adapter creation, and transport-server assembly; current realtime/default server implementations remain WebSocket-backed, and `MassApplication` mainline now calls this helper directly instead of routing default assembly through `WebSocketConfig`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as adapter-local bootstrap helper
- `Related tests`: `TransportChannelWiringIntegrationTest`

## 12. Minimal Outbound Delivery Record

- `Class`: `com.xa.mass.gateway.queue.OutboundDelivery`
- `Method`: DTO only
- `Current responsibility`: carries only transport addressability and raw outbound payload for ordered delivery
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep unless queueing strategy changes
- `Related tests`: `WebSocketTaskDispatchChannelTest`, `ServerMessageDispatcherShutdownTest`

## Explicit Non-Findings

These platform concerns are not owned by current `xa-mass-transport-websocket` mainline code:

- task lifecycle transitions
- task assignment and worker matching
- retry / timeout / terminal policy
- project or event catalog truth
- global capability identity beyond transport diagnostics
- submitter/client permission
- business event execution
- generic handler-routing runtime models beyond the current raw JSON task-frame path

That is the current baseline in this checkout: gateway is primarily an adapter over raw JSON, single-endpoint session reachability, handshake-based worker identity, canonical task frame handling, and transport/system-event reporting. Global capability identity remains `eventCode`, and gateway no longer carries a separate control-event protocol. SDK mainline no longer owns WebSocket-specific runtime helper or worker-adapter classes; gateway-owned embedded runtime support now supplies the current gateway-backed realtime defaults.
