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
- transport-shared JSON text-frame parse plus WebSocket-local error writing
- handshake/session-led worker identity; ordinary inbound frames do not rebind
  session ownership
- assigned task-dispatch payload passthrough from `DispatchMessage.payload`
- task-result wire recognition into opaque `ResultIngressEntry` values
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
- runtime-support `TransportJsonFrameParser`
- `WebSocketSessionOpenFrameReader`
- runtime embedded-support `WorkerChannelActionReplyResultFrameReader`
- runtime embedded-support `AdapterInboundResultProcessor`
- `WebSocketResultDiagnosticsProvider`

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
  worker-channel carriers live in `sdk/xa-mass-public-contract` when they are
  public worker wire DTOs/JSON codecs and not WebSocket-specific runtime types
- do not promote adapter-only frame fields into kernel or runtime truth
- do not add a second routing model keyed by frame subtype or protocol labels
- runtime routing must resolve from canonical worker transport identity, not
  from adapter implementation detail
- SDK/runtime mainline must not grow WebSocket-only branches for session or
  frame handling

## Wiring Rules

- construct command executor, session registry, refresher, and processors
  before start; use the transport runtime support `TransportJsonFrameParser` for
  JSON text frame parse/read/write and the public-contract worker-channel frame
  JSON codec rather than WebSocket-owned JSON/frame codecs. Public contract owns
  the frame carrier/codec only; adapter/runtime support owns any ACTION_REPLY
  body interpretation such as `replyRef`.
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
- `WebSocketTaskDispatchChannel` owns only WebSocket final-hop frame writing
  and selected-worker session send wiring. Reusable `DispatchOutcome`
  production for per-message push final-hop attempts belongs to transport
  runtime embedded support. The channel must not import Netty, session records,
  session record types, or duplicate outcome-loop logic.
- pass `WebSocketServerSessionHandle` to server/inbound wiring, not the broader
  runtime registry surface; do not add assigned-delivery lookup methods to that
  handle
- default Netty inbound handling parses a `TextWebSocketFrame` into a
  `JsonObject` and passes only that parsed frame through
  `Consumer<JsonObject>`; do not recreate a `WebSocketInboundMessage`
  carrier that mixes frame payload with worker/session/endpoint metadata
- `WebSocketSessionRegistry` publishes connect/disconnect evidence for local
  session mutations; `WebSocketSessionEvidenceRefresher` is a managed adapter
  resource that periodically refreshes active local session evidence through
  the host-provided `AdapterSessionEvidencePublisher` capability. It is evidence
  hygiene, not adapter health, reconnect, failover, or scheduling ownership.
- route inbound result shells into opaque
  `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)`
  values through the adapter-facing result ingress sink; result entry
  construction after protocol extraction is shared transport runtime embedded
  support logic
- `WorkerChannelFrame(ACTION_REPLY)` carrier decoding uses the public frame
  codec, but `replyRef` extraction belongs to transport runtime embedded
  support; `WorkerChannelActionReplyResultFrameReader` owns the shared
  worker-channel result facts, `WebSocketResultDiagnosticsProvider` owns only
  WebSocket-local diagnostics such as route/trace fallback, and
  `AdapterInboundResultProcessor` owns result-entry construction plus sink
  ingestion behavior
- classify WebSocket protocol control frames locally before invoking shared
  worker-channel result support; shared embedded readers must not become the
  owner of WebSocket `type=hello/handshake/heartbeat` rules
- bind WebSocket worker session identity only during handshake/session-open;
  normal text frames must read the channel-bound session identity and must not
  register or rebind sessions
- write assigned delivery frames directly from `DispatchMessage.payload`; do
  not route assigned dispatch through a generic task-frame codec. WebSocket may
  wrap that payload in the public `WorkerChannelFrame(kind=ACTION, body=...)`
  wire carrier using the public-contract JSON codec because WebSocket is a
  multiplexed protocol; the frame carrier is not task lifecycle truth.
- `transport/websocket-adapter` must not depend on embedded SDK API, Java SDK,
  base exception/model taxonomy, Redis clients, or old Java-WebSocket client
  libraries. It may depend on transport API/runtime embedded support,
  `xa-mass-public-contract` worker-channel DTOs/codecs, Gson for
  WebSocket-local frame glue, Netty, and currently used lifecycle annotations.
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
