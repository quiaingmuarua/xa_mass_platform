# Gateway Current Inventory

This inventory records the current responsibilities that still exist inside `xa-mass-gateway` and whether they belong there.

It is a migration aid, not a design wishlist.

## Inventory Format

- `Class`
- `Method`
- `Current responsibility`
- `Should stay in gateway?`
- `Target owner`
- `Migration phase`
- `Related tests`

## 1. Inbound WebSocket Frame Validation

- `Class`: `com.xa.mass.gateway.server.DispatcherInboundHandler`
- `Method`: `channelRead0(...)`
- `Current responsibility`: validates raw inbound text as JSON object, decodes `MassMessage`, requires context/worker identity, translates to `Envelope`, pushes to input queue, refreshes session binding
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep in Phase 4
- `Related tests`: `DispatcherInboundHandlerTest`, `TaskApiIntegrationTest`

## 2. Transport-Level Error Frame Emission

- `Class`: `com.xa.mass.gateway.server.DispatcherInboundHandler`
- `Method`: `sendError(...)`, `exceptionCaught(...)`
- `Current responsibility`: emits transport error JSON for malformed frame, missing context, missing worker identity, and channel exception paths
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep in Phase 4
- `Related tests`: `DispatcherInboundHandlerTest`

## 3. WebSocket Server Lifecycle

- `Class`: `com.xa.mass.gateway.server.WebSocketServerImpl`
- `Method`: `start(...)`, `stop()`
- `Current responsibility`: boots Netty WebSocket server, wires pipeline, owns adapter lifecycle and connection statistics
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `TransportChannelWiringIntegrationTest`, `TaskApiIntegrationTest`

## 4. Session Reachability Registry

- `Class`: `com.xa.mass.gateway.session.ServerSessionManager`
- `Method`: `addSession(...)`, `removeSession(...)`, `sendMessage(...)`, `isWorkerOnline(...)`, `listWorkerEndpoints()`
- `Current responsibility`: tracks worker WebSocket channels by `workerId + connRole`, sends outbound frames, exposes transport endpoint snapshots, emits online/offline system events
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway` implementing transport-api endpoint registry
- `Migration phase`: keep, but preserve reachability-only semantics in Phase 6
- `Related tests`: `TransportChannelWiringIntegrationTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 5. Worker System Event Translation

- `Class`: `com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel`
- `Method`: `publishWorkerOnline(...)`, `publishWorkerOffline(...)`, `publishWorkerHeartbeat(...)`
- `Current responsibility`: translates transport-level worker online/offline/heartbeat facts into the current `WorkerSystemEventChannel`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway` as adapter implementation of `WorkerSystemEventChannel`
- `Migration phase`: keep, audit split tightened in Phase 7
- `Related tests`: `TaskApiDelayedWorkerAvailabilityIntegrationTest`, `TaskApiWorkerWithoutContextIntegrationTest`

## 6. Wire Shape Decode / Encode

- `Class`: `com.xa.mass.gateway.queue.MessageParser`
- `Method`: `tryDecode(...)`, `toStoredMessage(...)`, `extractEventCode(...)`
- `Current responsibility`: decodes WebSocket compatibility frame, extracts minimal routing metadata, maps to stored `Envelope`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep, continue reducing payload-semantic inference in Phase 4
- `Related tests`: `MessageParserTest`, `ProcessEnvelopeMiddlewareTest`

## 7. WebSocket Compatibility Codec

- `Class`: `com.xa.mass.gateway.queue.GsonMessageCodec`
- `Method`: `encode(...)`, `decode(...)`, `isValid(...)`
- `Current responsibility`: JSON codec for WebSocket compatibility frame DTO
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep
- `Related tests`: `MessageParserTest`, `ProcessEnvelopeMiddlewareTest`

## 8. Queue-Based Adapter Dispatch Loop

- `Class`: `com.xa.mass.gateway.dispatcher.ServerMessageDispatcher`
- `Method`: `processInputQueueLoop()`, `processOutputQueueLoop()`, `submitOutputEnvelope(...)`
- `Current responsibility`: consumes adapter input/output queues, runs middleware chain, preserves per-endpoint outbound ordering
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep, but transport-only
- `Related tests`: `ProcessEnvelopeMiddlewareTest`, `TransportChannelWiringIntegrationTest`

## 9. Frame Route Resolution

- `Class`: `com.xa.mass.gateway.dispatcher.GatewayFrameRouter`
- `Method`: route registration and `route(...)`
- `Current responsibility`: resolves current adapter frame kinds such as `TASK/step`, `PING/heartbeat`, `PONG/heartbeat`, `CONTROL/event`
- `Should stay in gateway?`: yes
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: keep, but no new platform semantics should be added here
- `Related tests`: `GatewayFrameRouterTest`, `ProcessEnvelopeMiddlewareTest`

## 10. Worker Control Event Bridge

- `Class`: `com.xa.mass.gateway.dispatcher.handler.WorkerControlEventBridgeHandler`
- `Method`: `handle(...)`
- `Current responsibility`: converts `CONTROL/event` WebSocket compatibility frame into `EventEnvelope`, forwards to SDK event runtime, converts `EventResponse` back into WebSocket response frame
- `Should stay in gateway?`: yes, as a bridge only
- `Target owner`: bridge stays in `xa-mass-gateway`; event authorization and execution stay in `xa-mass-sdk`
- `Migration phase`: keep in Phase 4, re-check in later removal of compatibility tuple shell
- `Related tests`: `GatewayFrameRouterTest`, `WorkerControlEventCommandIntegrationTest`, `WorkerControlEventDisconnectIntegrationTest`

## 11. SDK Runtime Bridge Invocation

- `Class`: `com.xa.mass.gateway.dispatcher.event.EventGatewayBridge`
- `Method`: `handle(...)`
- `Current responsibility`: converts gateway event envelope into `EventRequest` and delegates to SDK runtime dispatcher
- `Should stay in gateway?`: borderline, but acceptable as adapter bridge
- `Target owner`: bridging logic may stay local; runtime semantics belong to `xa-mass-sdk`
- `Migration phase`: Phase 4
- `Related tests`: `GatewayFrameRouterTest`

## 12. Transport Delivery Failure Handling

- `Class`: `com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry`
- `Method`: `sendEnvelopeMiddleware()`
- `Current responsibility`: attempts outbound delivery through session registry, marks debug message failed when endpoint unavailable, logs delivery failure
- `Should stay in gateway?`: yes, with a transport-only scope
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: Phase 5 and Phase 7
- `Related tests`: `ProcessEnvelopeMiddlewareTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest`

## 13. Inbound Frame -> Handler -> Response Orchestration

- `Class`: `com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry`
- `Method`: `processEnvelopeMiddleware()`
- `Current responsibility`: decodes stored envelope back to frame DTO, resolves current adapter route, invokes bridge/handler, pushes response frames to output queue
- `Should stay in gateway?`: yes, but only as adapter orchestration
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: Phase 4
- `Related tests`: `ProcessEnvelopeMiddlewareTest`, `GatewayFrameRouterTest`

## 14. WebSocket Compatibility Frame Model

- `Class`: `com.xa.mass.gateway.model.massMessage.MassMessage`
- `Method`: DTO only
- `Current responsibility`: current WebSocket compatibility frame shell carrying `msgType/subMsgType/context/project/payload`
- `Should stay in gateway?`: yes, but only as adapter DTO
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: later protocol-shell cleanup after boundary hardening
- `Related tests`: `MessageParserTest`, `GatewayTaskMsgPublisherTest`, `GatewayTaskResultHandlerTest`

## 15. WebSocket Compatibility Context Model

- `Class`: `com.xa.mass.gateway.model.massMessage.MessageContext`
- `Method`: DTO only
- `Current responsibility`: current WebSocket compatibility frame context carrying worker/session/task/step transport metadata
- `Should stay in gateway?`: yes, as adapter DTO only
- `Target owner`: `xa-mass-gateway`
- `Migration phase`: later DTO shell cleanup
- `Related tests`: `MockWorkerWebSocketClientTest`, `GatewayTaskResultHandlerTest`

## Explicit Non-Findings

These platform concerns were not found as direct responsibilities in current `xa-mass-gateway` mainline code:

- direct `TaskManager` lifecycle mutation
- direct task assignment or worker matching logic
- retry policy ownership
- terminal policy ownership
- timeout policy ownership
- project/event catalog validation ownership
- submitter/client permission decision ownership

That is good news: the current mainline problem is now mostly boundary hardening and remaining bridge-shape cleanup, not a full semantic extraction from gateway.
