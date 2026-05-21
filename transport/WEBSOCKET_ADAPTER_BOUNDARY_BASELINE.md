# WebSocket Adapter Boundary Baseline

Last updated: 2026-04-29

Status: current WebSocket adapter boundary baseline.

Use this file only for current WebSocket adapter ownership. For transport-wide
truth, use [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md).

## Position

- module: `transport/websocket-adapter`
- artifact: `xa-mass-transport-websocket`
- role: one concrete realtime adapter, not the platform boundary and not the task engine

## Adapter Owns

- Netty server lifecycle and bind/stop
- session registry and reachability
- raw JSON frame parse/encode
- handshake/session-led worker identity
- canonical task-dispatch/task-result frame adaptation
- transport-level online/offline/heartbeat facts
- adapter-local reject/error responses
- adapter-local logs, traces, and bounded diagnostics
- adapter-owned embedded bootstrap/support for current runtime defaults

Current code centers on:

- `WebSocketServerImpl`
- `DispatcherInboundHandler`
- `ServerSessionManager`
- `WebSocketTransportFrameCodec`
- `WebSocketInputProcessor`
- `WebSocketOutputProcessor`
- `WebSocketEmbeddedRuntimeSupport`

## Adapter Does Not Own

- task lifecycle transitions
- assignment and worker matching
- retry, timeout, terminal policy, and audit truth
- project or event catalog truth
- worker capability truth
- submitter permission
- business event execution
- scan-heavy platform observability

Hard rules:

- `eventCode` is capability identity
- `WorkerGroup.eventBindings` is capability truth
- worker-level `supportedEventCodes` and `supportedProjects` are
  compatibility/read residue only
- transport reachability is not execution eligibility

## Boundary Rules

- do not push WebSocket/Netty/frame/session types into `transport_api`
- do not promote adapter-only frame fields into kernel or runtime truth
- do not add a second routing model keyed by frame subtype or protocol labels
- runtime routing must resolve from canonical worker transport identity, not
  from adapter implementation detail
- SDK/runtime mainline must not grow WebSocket-only branches for session or
  frame handling

## Wiring Rules

- construct codec, channels, endpoint registry, and processors before start
- pass one endpoint-registry instance into both server and dispatcher wiring
- route inbound result shells into `TaskResultReport -> TaskResultIngestChannel`
- keep bootstrap defaults inside adapter-owned support code
- do not add mutable late-binding seams like `setHandler(...)` or `registerRoute(...)`

## Regression Floor

WebSocket changes must preserve:

- realtime connect/disconnect status perception
- task dispatch through WebSocket adapter
- result write-back and callback idempotency behavior
- coexistence with polling and socket adapters without cross-routing

Before changing this module, answer:

1. Is this adapter ownership or platform ownership?
2. If it is platform ownership, why is it still here?
3. Which module owns the truth after the change?
4. Is the touched seam adapter-local or canonical runtime contract?
