# Gateway Current Inventory

This inventory records the responsibilities that still exist inside `xa-mass-gateway` after the raw-json mainline refactor.

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
- `Current responsibility`: validates inbound text as JSON, extracts `workerId + messageId`, refreshes session reachability, forwards raw JSON into the adapter inbound sink
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`, `TaskApiIntegrationTest`

## 2. Transport Error Emission

- `Class`: `com.xa.mass.gateway.server.DispatcherInboundHandler`
- `Method`: `sendError(...)`, `exceptionCaught(...)`
- `Current responsibility`: emits transport-level error JSON for malformed payloads and channel failures
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `DispatcherInboundHandlerTest`

## 3. WebSocket Server Lifecycle

- `Class`: `com.xa.mass.gateway.server.WebSocketServerImpl`
- `Method`: `start(...)`, `stop()`
- `Current responsibility`: boots the Netty WebSocket adapter and owns adapter lifecycle
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `TransportChannelWiringIntegrationTest`, `TaskApiIntegrationTest`

## 4. Session Reachability Registry

- `Class`: `com.xa.mass.gateway.session.ServerSessionManager`
- `Method`: `addSession(...)`, `removeSession(...)`, `sendMessage(...)`, `listWorkerEndpoints()`
- `Current responsibility`: tracks active transport endpoints by `workerId`, sends outbound frames, emits online/offline transport signals
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep with reachability-only semantics
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `PollingWorkerTaskFlowIntegrationTest`

## 5. Worker System Event Translation

- `Class`: `com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel`
- `Method`: `publishWorkerOnline(...)`, `publishWorkerOffline(...)`, `publishWorkerHeartbeat(...)`
- `Current responsibility`: translates WebSocket transport facts into the transport-neutral worker system-event seam
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `TaskApiWorkerWithoutContextIntegrationTest`

## 6. WebSocket Compatibility Codec

- `Class`: `com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec`
- `Method`: `parseObject(...)`, `encodeCanonicalTaskDispatch(...)`, `decodeCanonicalTaskResult(...)`, `decodeControlEventRequest(...)`
- `Current responsibility`: converts current WebSocket raw JSON into canonical task/control objects and back
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep without expanding platform semantics
- `Related tests`: `GatewayInputProcessorTest`, `GatewayTaskMsgPublisherTest`, `RuntimeTaskResultIngestChannelTest`

## 7. Canonical Task Frame Detection

- `Class`: `com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec`, `com.xa.mass.gateway.dispatcher.GatewayInputProcessor`
- `Method`: `isCanonicalTaskDispatch(...)`, `isCanonicalTaskResult(...)`
- `Current responsibility`: recognizes canonical task frames directly inside the codec and input processor without reintroducing a tuple-router model
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep narrow; do not add new platform capability identities here
- `Related tests`: `WebSocketGatewayFrameCodecTest`, `GatewayInputProcessorTest`

## 8. Inbound / Outbound Adapter Orchestration

- `Class`: `com.xa.mass.gateway.dispatcher.GatewayInputProcessor`, `com.xa.mass.gateway.dispatcher.GatewayOutputProcessor`
- `Method`: `process(...)`
- `Current responsibility`: turns raw JSON into canonical seam calls, routes task results through `TaskResultReport -> TaskResultIngestChannel`, invokes control-event handlers, encodes replies, and reports transport delivery failure
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as explicit adapter processors, not as a generic middleware framework; runtime result-ingest ownership stays above transport binding
- `Related tests`: `GatewayInputProcessorTest`, `GatewayOutputProcessorTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 9. Queue-Based Adapter Dispatch Loop

- `Class`: `com.xa.mass.gateway.dispatcher.ServerMessageDispatcher`
- `Method`: `processInputQueueLoop()`, `processOutputQueueLoop()`, `submitOutputDelivery(...)`
- `Current responsibility`: consumes raw inbound JSON and `OutboundDelivery`, calls explicit adapter processors, preserves outbound ordering per `workerId`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `GatewayInputProcessorTest`, `GatewayOutputProcessorTest`, `ServerMessageDispatcherShutdownTest`

## 10. Fixed Gateway Wiring Snapshot

- `Class`: `com.xa.mass.gateway.dispatcher.DispatcherContext`
- `Method`: constructor + getters
- `Current responsibility`: exposes the fixed gateway-local wiring snapshot used by the adapter runtime; runtime assembly resolves the concrete endpoint registry once and injects the same instance into dispatcher wiring and transport-server creation
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as immutable adapter runtime wiring
- `Related tests`: `MassSdkTest`, `DispatcherInboundHandlerTest`

## 11. WebSocket Adapter Runtime Support

- `Class`: `com.xa.mass.gateway.runtime.WebSocketGatewayRuntimeSupport`
- `Method`: `createEndpointRegistry(...)`, `resolveSystemEventChannel(...)`, `requireSessionManager(...)`, `createTransportServer(...)`
- `Current responsibility`: keeps WebSocket-specific runtime assembly defaults inside gateway, including endpoint-registry creation and transport-server assembly
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as adapter-local bootstrap helper
- `Related tests`: `TransportChannelWiringIntegrationTest`

## 12. Control Event Request Port

- `Type`: `com.xa.mass.gateway.dispatcher.port.ControlEventRequestHandler`
- `Method`: `handleControlEventRequest(...)`
- `Current responsibility`: names the single gateway adapter port that hands root-level event-first control requests into the global event runtime
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as a narrow event-first port, but avoid extra pass-through bridge classes or late-bound setter wiring
- `Related tests`: `GatewayInputProcessorTest`

## 13. Minimal Outbound Delivery Record

- `Class`: `com.xa.mass.gateway.queue.OutboundDelivery`
- `Method`: DTO only
- `Current responsibility`: carries only transport addressability and raw outbound payload for ordered delivery
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep unless queueing strategy changes
- `Related tests`: `GatewayTaskMsgPublisherTest`, `ServerMessageDispatcherShutdownTest`

## Explicit Non-Findings

These platform concerns are not owned by current `xa-mass-gateway` mainline code:

- task lifecycle transitions
- task assignment and worker matching
- retry / timeout / terminal policy
- project or event catalog truth
- global capability identity beyond transport diagnostics
- submitter/client permission
- business event execution
- generic handler-routing runtime models beyond the current raw JSON frame path and narrow control-event handler types

That is the current baseline: gateway is now primarily an adapter over raw JSON, single-endpoint session reachability, handshake-based worker identity, canonical task/control frame handling, and narrow event-first control handlers. Global capability identity remains `eventCode`, not transport tuple fields.
