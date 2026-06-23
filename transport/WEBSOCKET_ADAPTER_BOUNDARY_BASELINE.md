# WebSocket Adapter Boundary Baseline

Last updated: 2026-06-23

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
- `WebSocketSessionRegistry`
- `WebSocketServerSessionHandle`
- `WebSocketSessionEvidenceRefresher`
- `WebSocketJsonFrameParser`
- `WebSocketSessionOpenFrameReader`
- `WebSocketResultIngressFrameReader`
- `WebSocketInputProcessor`
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

- do not push WebSocket/Netty/session types into `transport_api`; shared
  worker-channel carriers may live in `transport_api` only when they are not
  WebSocket-specific
- do not promote adapter-only frame fields into kernel or runtime truth
- do not add a second routing model keyed by frame subtype or protocol labels
- runtime routing must resolve from canonical worker transport identity, not
  from adapter implementation detail
- SDK/runtime mainline must not grow WebSocket-only branches for session or
  frame handling

## Wiring Rules

- construct codec, command executor, session registry, refresher, and processors
  before start
- `WebSocketSessionRegistry` owns the adapter-local session indexes and final
  channel write. Its only durable indexes are `workerId -> channel/session` and
  `channel -> workerId + workerGroupId`; `workerGroupId` is evidence context,
  not a lookup dimension. It must not expose public session records that carry
  Netty send behavior.
- WebSocket assigned-delivery local lookup is worker-id-only:
  `DispatchMessage.selectedWorkerId` -> `WebSocketTaskDispatchChannel` ->
  `WebSocketSessionRegistry.sendTextToWorker(...)` -> Netty frame write.
  `deliveryBucketId`, routeKey, endpoint address, and adapter mailbox key are
  not WebSocket session lookup dimensions.
- `WebSocketTaskDispatchChannel` owns assigned-delivery command execution and
  `DispatchOutcome` production. It must not import Netty, session records, or
  session record types.
- pass `WebSocketServerSessionHandle` to server/inbound wiring, not the broader
  runtime registry surface; do not add assigned-delivery lookup methods to that
  handle
- `WebSocketSessionRegistry` publishes connect/disconnect evidence for local
  session mutations; `WebSocketSessionEvidenceRefresher` is a managed adapter
  resource that periodically refreshes active local session evidence through
  the host-provided `AdapterSessionEvidencePublisher` capability. It is evidence
  hygiene, not adapter health, reconnect, failover, or scheduling ownership.
- route inbound result shells into opaque
  `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)`
  values through `TransportResultIngressChannel`
- bind WebSocket worker session identity only during handshake/session-open;
  normal text frames must read the channel-bound session identity and must not
  register or rebind sessions
- write assigned delivery frames directly from `DispatchMessage.payload`; do
  not route assigned dispatch through a generic task-frame codec
- worker-id raw sending may remain through `RawWorkerMessageChannel`; routeKey
  raw/manual output queues and route-only WebSocket registries are not current
  WebSocket owner surfaces and must not be assigned-delivery fallbacks
- endpoint address and route-style fields are not WebSocket session identity,
  assigned-delivery lookup keys, or endpoint lease truth
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
