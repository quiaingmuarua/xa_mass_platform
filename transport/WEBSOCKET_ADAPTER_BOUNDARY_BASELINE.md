# WebSocket Adapter Boundary Baseline

Last updated: 2026-06-18

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
- adapter-local JSON frame parse/error writing
- handshake/session-led worker identity; ordinary inbound frames do not rebind
  session ownership
- assigned task-dispatch payload passthrough from `DeliveryCommand.payload`
- task-result wire recognition into opaque transport result ingress envelopes
- transport-level online/offline/heartbeat facts
- adapter-local reject/error responses
- adapter-local logs, traces, and bounded diagnostics
- adapter-owned embedded bootstrap/support for current runtime defaults

Current code centers on:

- `WebSocketServerImpl`
- `DispatcherInboundHandler`
- `WebSocketSessionStore`
- `WebSocketSessionController`
- `WebSocketServerSessionHandle`
- `WebSocketSessionEvidenceDriver`
- `WebSocketSessionRefreshLoop`
- `WebSocketRawWorkerRouteEndpointRegistry`
- `WebSocketEndpointInspector`
- `WebSocketJsonFrameParser`
- `WebSocketSessionOpenFrameReader`
- `WebSocketResultIngressFrameReader`
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

- construct codec, command executor, session store/controller, and processors
  before start
- `WebSocketSessionStore` owns low-level session indexes and private session
  entries only. It must not expose a public session record that carries Netty
  send behavior.
- WebSocket assigned-delivery local lookup is worker-id-only:
  `DeliveryCommand.selectedWorkerId` -> `WebSocketSessionController`
  `sendTextToWorker(...)` -> store channel lookup -> Netty frame write.
  `deliveryBucketId` is upstream scheduling/index and endpoint-evidence
  context, not a WebSocket session lookup dimension.
- `WebSocketTaskDispatchChannel` owns assigned-delivery command execution and
  `DispatchOutcome` production. It must not import Netty, session store, or
  session record types.
- `WebSocketSessionController` is the adapter-local session mutation and send
  coordinator. It may own selected-worker text send, but must not implement
  `WorkerEndpointRegistry` or expose broad session records.
- pass `WebSocketServerSessionHandle` to server/inbound wiring, not the broader
  runtime registry surface; do not add assigned-delivery lookup methods to that
  handle
- keep `WebSocketSessionStore`, `WebSocketSessionEvidenceDriver`, and
  `WebSocketSessionRefreshLoop` as separate adapter-local roles
- route inbound result shells into opaque
  `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)`
  values through `TransportResultIngressChannel`
- bind WebSocket worker session identity only during handshake/session-open;
  normal text frames must read the channel-bound session identity and must not
  register or rebind sessions
- write assigned delivery frames directly from `DispatchMessage.payload`; do
  not route assigned dispatch through a generic task-frame codec
- raw/manual route sending and endpoint diagnostics are explicit side roles
  backed by session store snapshots; they are not assigned-delivery fallbacks
- endpoint address and route-style fields are raw/manual or diagnostic
  metadata only; they are not the WebSocket assigned-delivery lookup key
- keep bootstrap defaults inside adapter-owned support code
- do not add mutable late-binding seams like `setHandler(...)` or `registerRoute(...)`

## Regression Floor

WebSocket changes must preserve:

- realtime endpoint-lease perception
- task dispatch through WebSocket adapter
- result write-back and callback idempotency behavior
- coexistence with polling and socket adapters without cross-routing

Before changing this module, answer:

1. Is this adapter ownership or platform ownership?
2. If it is platform ownership, why is it still here?
3. Which module owns the truth after the change?
4. Is the touched seam adapter-local or canonical runtime contract?
