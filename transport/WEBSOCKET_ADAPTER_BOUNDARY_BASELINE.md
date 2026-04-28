# WebSocket Adapter Boundary Baseline

Last updated: 2026-04-27

Status: current WebSocket adapter boundary baseline.

Use this file only for current adapter ownership and drift prevention.
Use the canonical trust order in [../AGENTS.md](../AGENTS.md).

## 1. Current Position

- module path: `transport/websocket-adapter`
- artifact: `xa-mass-transport-websocket`
- package root: `com.xa.mass.transport.websocket.*`
- sibling adapters: `polling-adapter`, `socket-adapter`
- transport-neutral contracts live in `transport/transport_api`
- shared transport runtime assembly lives in `transport/transport_runtime`

The WebSocket adapter is one concrete realtime transport adapter. It is not the
platform boundary and it is not the task engine.

## 2. Adapter Owns

Keep these responsibilities in the WebSocket adapter:

- server lifecycle and bind/stop
- connection/session registry
- outbound endpoint send and reachability
- raw JSON frame parse/encode
- handshake/session-led worker identity
- transport-level online/offline/heartbeat facts
- canonical task dispatch/task-result frame adaptation
- transport-level reject/error responses
- adapter-local logs, traces, and bounded diagnostics
- adapter-owned embedded bootstrap/support for current WebSocket runtime defaults

Current code shape is centered on:

- `WebSocketServerImpl`
- `DispatcherInboundHandler`
- `ServerSessionManager`
- `WebSocketTransportFrameCodec`
- `WebSocketInputProcessor`
- `WebSocketOutputProcessor`
- `WebSocketEmbeddedRuntimeSupport`

## 3. Adapter Does Not Own

These belong outside `xa-mass-transport-websocket`:

- task lifecycle transitions
- assignment and worker matching
- retry, timeout, terminal policy, and audit truth
- event authorization and submitter permission
- project or event catalog truth
- worker capability truth
- business event execution
- platform observability projections that require scan-heavy reconciliation

Hard rules:

- `eventCode` is the capability identity
- `supportedEventCodes` is worker capability truth
- `supportedProjects` is only a coarse filter hint
- transport reachability is not execution eligibility
- `connected == eligible` is forbidden

## 4. Transport-Neutral Discipline

`transport_api` and `transport_runtime` may carry dispatch, result-ingest,
system-event, endpoint-registry, and transport-server seams.

They must not become renamed WebSocket compatibility layers.

Keep these rules:

- do not push WebSocket/Netty/frame/session types into `transport_api`
- do not promote adapter-only frame fields into kernel or runtime truth
- do not add a second routing model keyed by frame subtype or protocol labels
- runtime routing must resolve from canonical worker transport identity, not from adapter implementation detail
- concrete `adapterId` values must stay globally unique within one embedded runtime
- runtime dispatch must not silently fall back to a default adapter when worker transport identity is missing or unsupported

## 5. Wiring Rule

`WebSocketDispatcherContext` is an adapter-local wiring snapshot, not a mutable
registration surface.

Keep these rules:

- construct codec, channels, endpoint registry, and adapter processors before start
- resolve one adapter endpoint-registry instance during runtime assembly and pass that same instance into server and dispatcher wiring
- route inbound task-result transport shells into the canonical `TaskResultReport -> TaskResultIngestChannel` seam
- resolve `WorkerSystemEventChannel` from runtime assembly, not from task-dispatch ownership
- keep WebSocket bootstrap defaults inside adapter-owned support code
- SDK mainline must not grow session-manager, frame-codec, or WebSocket-specific routing branches
- stable SDK entry is `transport(...)`; do not reintroduce `server(...)`, `transportServer(...)`, or `websocket(...)` compatibility names
- embedded runtime should consume adapter-owned bootstrap/contribution outputs instead of retaining live WebSocket config objects as runtime backbone
- do not add late `setHandler(...)` or `registerRoute(...)` style seams on adapter runtime wiring

## 6. Worker Runtime Split

Worker runtime owns:

- `eventCode -> handler` resolution
- business execution
- result materialization

The WebSocket client/server path owns only transport mechanics:

- connect / disconnect / reconnect
- frame parse / encode
- send / receive

WebSocket transport code is not the business handler framework.

## 7. Active Adapter Seams

Current adapter-local seams are:

- raw inbound JSON plus connection facts
- raw outbound JSON plus explicit transport addressability
- canonical task-dispatch/task-result frame detection and encoding
- explicit adapter input/output processors
- adapter metadata extraction for diagnostics only

Preserve only what the current adapter path still needs. Converge callers onto
canonical runtime contracts by removing obsolete seams, not by layering new
bridges over old ones.

## 8. Regression Floor

WebSocket-adapter changes must preserve:

- realtime worker connect/disconnect status perception
- task dispatch through the WebSocket adapter
- result write-back and callback idempotency behavior
- delayed worker availability behavior
- coexistence with polling and socket adapters without cross-routing

Before changing this module, answer:

1. Is this transport ownership or platform ownership?
2. If it is platform ownership, why is it still in the adapter?
3. Which module owns the truth after the change?
4. Is the touched seam canonical runtime contract or adapter-local compatibility?
5. Which integration tests or trace events prove behavior is still correct?
