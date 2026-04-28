# WebSocket Adapter Current Inventory

This inventory records the responsibilities that still exist inside the current WebSocket adapter. The module lives under `transport/websocket-adapter`; its artifact identity is `xa-mass-transport-websocket`, and its Java package identity is `com.xa.mass.transport.websocket.*`.

Status: refactor inventory, not current platform architecture truth or compatibility promise.

Trust: code, [../AGENTS.md](../AGENTS.md), [../TRANSPORT_BOUNDARY_BASELINE.md](../TRANSPORT_BOUNDARY_BASELINE.md), and [../../doc/AGENT_BASELINE.md](../../doc/AGENT_BASELINE.md) override this inventory.

## Current Inventory

| Scope | Code | Current responsibility | Keep here? | Notes |
| --- | --- | --- | --- | --- |
| inbound validation | `DispatcherInboundHandler.channelRead0(...)` | parse inbound JSON, extract `workerId + messageId`, refresh session reachability, forward raw JSON into adapter inbound sink | yes | worker identity is handshake/session-led with frame fallback only to registered session identity |
| transport errors | `DispatcherInboundHandler.sendError(...)`, `exceptionCaught(...)` | emit transport-level error JSON for malformed payloads and channel failures | yes | transport concern only |
| server lifecycle | `WebSocketServerImpl.start(...)`, `stop()` | boot and stop the Netty WebSocket adapter | yes | adapter lifecycle |
| session reachability | `ServerSessionManager.addSession(...)`, `removeSession(...)`, `sendMessage(...)`, `listWorkerEndpoints()` | track active endpoints by `workerId`, send outbound frames, emit online/offline transport signals | yes | reachability only, not eligibility truth |
| system-event translation | `EventBusWorkerSystemEventChannel.publishWorkerOnline(...)`, `publishWorkerOffline(...)`, `publishWorkerHeartbeat(...)` | translate transport facts into worker system events | yes | connect/disconnect is primary ingress truth |
| frame codec | `WebSocketTransportFrameCodec.parseObject(...)`, `encodeCanonicalTaskDispatch(...)`, `decodeCanonicalTaskResult(...)` | convert raw JSON to canonical task objects and back | yes | do not expand platform semantics here |
| frame detection | `WebSocketTransportFrameCodec.isCanonicalTaskDispatch(...)`, `isCanonicalTaskResult(...)` | detect canonical task frames inside the codec | yes | no adapter-side route registry |
| adapter processors | `WebSocketInputProcessor.process(...)`, `WebSocketOutputProcessor.process(...)` | inbound result ingest handoff and outbound endpoint send | yes | explicit adapter processors, not generic middleware |
| runtime wiring snapshot | `WebSocketDispatcherContext` | expose fixed adapter-local wiring resolved before start | yes | immutable adapter runtime wiring |
| embedded runtime support | `WebSocketEmbeddedRuntimeSupport.*` | create endpoint registry, dispatcher context, realtime adapter, system-event channel, and transport server defaults | yes | adapter-owned embedded bootstrap helper |
| outbound carrier | `WorkerTransportMessage` | carry transport-neutral worker addressability plus raw outbound payload | no | converged to `xa-mass-transport-api` mainline |

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

Current baseline:

- WebSocket adapter = raw JSON transport, single-endpoint session reachability, handshake-based worker identity, canonical task frame handling, and transport/system-event reporting
- global capability identity remains `eventCode`
- SDK mainline no longer owns WebSocket-specific runtime helper or worker-adapter classes
- adapter-owned embedded runtime support now supplies current WebSocket-backed realtime defaults
