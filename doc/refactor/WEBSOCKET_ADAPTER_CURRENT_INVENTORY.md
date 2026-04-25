# WebSocket Adapter Current Inventory

This inventory records the responsibilities that still exist inside the current WebSocket adapter in the current checkout after substantial convergence toward a task-only WebSocket adapter. The module now lives physically under `transport/websocket-adapter`; its current artifact identity is `xa-mass-transport-websocket`, and its Java package identity is `com.xa.mass.transport.websocket.*`.

It is a migration aid, not a compatibility promise.

## Inventory Format

- `Class`
- `Method`
- `Current responsibility`
- `Should stay in WebSocket adapter?`
- `Target owner`
- `Migration phase`
- `Related tests`

## 1. Inbound Raw Frame Validation

- `Class`: `com.xa.mass.transport.websocket.server.DispatcherInboundHandler`
- `Method`: `channelRead0(...)`
- `Current responsibility`: validates inbound text as JSON, extracts `workerId + messageId`, refreshes session reachability, and forwards raw JSON into the adapter inbound sink; current worker identity is handshake/session-led with frame-level fallback only to already-registered session identity
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`, `TaskApiIntegrationTest`

## 2. Transport Error Emission

- `Class`: `com.xa.mass.transport.websocket.server.DispatcherInboundHandler`
- `Method`: `sendError(...)`, `exceptionCaught(...)`
- `Current responsibility`: emits transport-level error JSON for malformed payloads and channel failures
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`

## 3. WebSocket Server Lifecycle

- `Class`: `com.xa.mass.transport.websocket.server.WebSocketServerImpl`
- `Method`: `start(...)`, `stop()`
- `Current responsibility`: boots the Netty WebSocket adapter and owns adapter lifecycle
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `TransportChannelWiringIntegrationTest`, `TaskApiIntegrationTest`

## 4. Session Reachability Registry

- `Class`: `com.xa.mass.transport.websocket.session.ServerSessionManager`
- `Method`: `addSession(...)`, `removeSession(...)`, `sendMessage(...)`, `listWorkerEndpoints()`
- `Current responsibility`: tracks active transport endpoints by `workerId`, sends outbound frames, emits online/offline transport signals
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep with reachability-only semantics
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `PollingWorkerTaskFlowIntegrationTest`

## 5. Worker System Event Translation

- `Class`: `com.xa.mass.transport.websocket.session.EventBusWorkerSystemEventChannel`
- `Method`: `publishWorkerOnline(...)`, `publishWorkerOffline(...)`, `publishWorkerHeartbeat(...)`
- `Current responsibility`: exposes transport-fact translation into the transport-neutral worker system-event seam; current WebSocket ingress mainline actively uses connect/disconnect session facts, while heartbeat publication remains available but is not the primary ingress truth
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `TaskApiWorkerWithoutContextIntegrationTest`

## 6. WebSocket Compatibility Codec

- `Class`: `com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec`
- `Method`: `parseObject(...)`, `encodeCanonicalTaskDispatch(...)`, `decodeCanonicalTaskResult(...)`
- `Current responsibility`: converts current WebSocket raw JSON into canonical task objects and back
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep without expanding platform semantics
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketTaskDispatchChannelTest`, `RuntimeTaskResultIngestChannelTest`

## 7. Canonical Task Frame Detection

- `Class`: `com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec`
- `Method`: `isCanonicalTaskDispatch(...)`, `isCanonicalTaskResult(...)`
- `Current responsibility`: recognizes canonical task frames directly inside the codec without reintroducing an adapter-side route registry; `WebSocketInputProcessor` consumes canonical task-result detection rather than owning it
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep narrow; do not add new platform capability identities here
- `Related tests`: `WebSocketTransportFrameCodecTest`, `WebSocketInputProcessorTest`

## 8. Inbound / Outbound Adapter Orchestration

- `Class`: `com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor`, `com.xa.mass.transport.websocket.dispatcher.WebSocketOutputProcessor`
- `Method`: `process(...)`
- `Current responsibility`: `WebSocketInputProcessor` turns raw inbound JSON into canonical task-result ingest calls; `WebSocketOutputProcessor` performs endpoint send and transport-delivery failure reporting; task-dispatch frame encoding remains in codec/publisher paths rather than inside these processors
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as explicit adapter processors, not as a generic middleware framework; runtime result-ingest ownership stays above transport binding
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketOutputProcessorTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 9. Queue-Based Adapter Dispatch Loop

- `Class`: `com.xa.mass.transport.websocket.dispatcher.WebSocketMessageDispatcher`
- `Method`: `processInputQueueLoop()`, `processOutputQueueLoop()`, `submitOutputDelivery(...)`
- `Current responsibility`: consumes raw inbound JSON and transport-neutral `WorkerTransportMessage`, calls explicit adapter processors, preserves outbound ordering per `workerId`
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep
- `Related tests`: `WebSocketInputProcessorTest`, `WebSocketOutputProcessorTest`, `ServerMessageDispatcherShutdownTest`

## 10. Fixed WebSocket Adapter Wiring Snapshot

- `Class`: `com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext`
- `Method`: constructor + getters
- `Current responsibility`: exposes the fixed adapter-local wiring snapshot used by the adapter runtime; runtime assembly resolves the concrete endpoint registry once and injects the same instance into dispatcher wiring and transport-server creation; dispatcher-context assembly now lives in adapter runtime support instead of SDK-side `new WebSocketDispatcherContext(...)`, and `MassApplication` no longer retains this snapshot as a general runtime field after assembly
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as immutable adapter runtime wiring
- `Related tests`: `MassSdkTest`, `DispatcherInboundHandlerTest`

## 11. WebSocket Adapter Embedded Runtime Support

- `Class`: `com.xa.mass.transport.websocket.runtime.WebSocketEmbeddedRuntimeSupport`
- `Method`: `createEndpointRegistry(...)`, `createDispatcherContext(...)`, `resolveSystemEventChannel(...)`, `createRealtimeWorkerAdapter(...)`, `createTransportServer(...)`
- `Current responsibility`: keeps adapter-owned embedded-runtime defaults inside the WebSocket adapter, including endpoint-registry creation, dispatcher-context assembly, WebSocket-backed realtime adapter creation, and transport-server assembly; current realtime/default server implementations remain WebSocket-backed, and the embedded-runtime mainline now consumes these defaults through adapter-owned bootstrap/contribution assembly instead of routing WebSocket details through `MassApplication`
- `Should stay in WebSocket adapter?`: yes
- `Target owner`: `xa-mass-transport-websocket`
- `Migration phase`: keep as adapter-local bootstrap helper
- `Related tests`: `TransportChannelWiringIntegrationTest`

## 12. Embedded Runtime Outbound Carrier

- `Class`: `com.xa.mass.transport.model.WorkerTransportMessage`
- `Method`: DTO only
- `Current responsibility`: carries transport-neutral worker addressability plus raw outbound payload for embedded runtime composition; concrete adapters may consume it differently, but SDK/runtime code no longer depends on a WebSocket-only delivery DTO
- `Should stay in WebSocket adapter?`: no
- `Target owner`: `xa-mass-transport-api`
- `Migration phase`: converged current embedded mainline
- `Related tests`: `WebSocketTaskDispatchChannelTest`, `ServerMessageDispatcherShutdownTest`, `MassSdkTest`

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

That is the current baseline in this checkout: the WebSocket adapter is primarily an adapter over raw JSON, single-endpoint session reachability, handshake-based worker identity, canonical task frame handling, and transport/system-event reporting. Global capability identity remains `eventCode`, and the adapter no longer carries a separate control-event protocol. SDK mainline no longer owns WebSocket-specific runtime helper or worker-adapter classes; adapter-owned embedded runtime support now supplies the current WebSocket-backed realtime defaults.
