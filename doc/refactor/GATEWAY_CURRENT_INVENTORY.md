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
- `Current responsibility`: validates inbound text as JSON, extracts `workerId + connRole + msgId`, refreshes session reachability, forwards raw JSON into the adapter input queue
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
- `Current responsibility`: tracks active transport endpoints by `workerId + connRole`, sends outbound frames, emits online/offline transport signals
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

- `Class`: `com.xa.mass.gateway.queue.GsonMessageCodec`
- `Method`: `parseObject(...)`, `encodeTaskDispatch(...)`, `decodeTaskResult(...)`, `decodeControlEventRequest(...)`
- `Current responsibility`: converts current WebSocket compatibility JSON into canonical task/control objects and back
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep without expanding platform semantics
- `Related tests`: `MessageParserTest`, `GatewayTaskMsgPublisherTest`, `GatewayTaskResultHandlerTest`

## 7. Adapter Frame Classification

- `Class`: `com.xa.mass.gateway.dispatcher.GatewayFrameRouter`
- `Method`: `route(...)`, `handlePing(...)`, `handlePong(...)`
- `Current responsibility`: classifies adapter-local compatibility tuples such as `TASK/step`, `CONTROL/event`, `PING/heartbeat`, and `PONG/heartbeat`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep, but do not add new platform capability identities here
- `Related tests`: `GatewayFrameRouterTest`, `ProcessEnvelopeMiddlewareTest`

## 8. Inbound / Outbound Adapter Orchestration

- `Class`: `com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry`
- `Method`: `processEnvelopeMiddleware()`, `sendEnvelopeMiddleware()`
- `Current responsibility`: turns raw JSON into canonical seam calls, invokes fixed bridge ports, encodes replies, and reports transport delivery failure
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as adapter-only orchestration
- `Related tests`: `ProcessEnvelopeMiddlewareTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 9. Queue-Based Adapter Dispatch Loop

- `Class`: `com.xa.mass.gateway.dispatcher.ServerMessageDispatcher`
- `Method`: `processInputQueueLoop()`, `processOutputQueueLoop()`, `submitOutputDelivery(...)`
- `Current responsibility`: consumes raw inbound JSON and `OutboundDelivery`, runs middleware chains, preserves outbound ordering per `workerId + connRole`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `ProcessEnvelopeMiddlewareTest`, `ServerMessageDispatcherShutdownTest`

## 10. Fixed Gateway Wiring Snapshot

- `Class`: `com.xa.mass.gateway.dispatcher.DispatcherContext`
- `Method`: constructor + getters
- `Current responsibility`: exposes the fixed gateway-local wiring snapshot used by the adapter runtime
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep as immutable adapter runtime wiring
- `Related tests`: `MassSdkTest`, `DispatcherInboundHandlerTest`

## 11. Minimal Outbound Delivery Record

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
- submitter/client permission
- business event execution
- generic handler-routing runtime models beyond the current raw JSON frame path and narrow control-event bridge types

That is the current baseline: gateway is now primarily an adapter over raw JSON, session reachability, and fixed bridge ports.
