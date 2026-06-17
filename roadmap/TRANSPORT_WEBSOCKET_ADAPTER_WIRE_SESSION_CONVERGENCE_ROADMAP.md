# Transport WebSocket Adapter Wire Session Convergence Roadmap

Status: proposed direction document.

## Summary

`websocket-adapter` still has a few old mixed-owner seams:

- `WebSocketTransportFrameCodec` is a combined JSON parser, session-field
  extractor, task-result recognizer, result payload encoder, and assigned
  dispatch passthrough.
- `DispatcherInboundHandler.channelRead0(...)` still lets ordinary worker
  frames resolve worker identity, resolve or mint route metadata, and register
  sessions.
- `MassWebSocketServer` exposes a Netty `Channel` escape hatch even though the
  transport-neutral `TransportServer` contract is enough for runtime assembly.

This roadmap converges the WebSocket adapter around protocol/session/final-hop
ownership without changing the wider transport delivery model.

## Current Code Observations

- `WebSocketTaskDispatchChannel.dispatch(...)` already consumes
  `DeliveryCommand`, but it still calls
  `WebSocketTransportFrameCodec.encodeCanonicalTaskDispatch(...)`. That method
  only returns `command.getPayload()`, so it is a pass-through layer on the
  assigned-delivery hot path.
- `WebSocketCommandDispatchContext` carries `WebSocketTransportFrameCodec`
  solely to support that pass-through dispatch call.
- `WebSocketTransportFrameCodec` imports `TransportPacket` constants and owns
  both dispatch and result-frame helpers.
- `DispatcherInboundHandler.channelRead0(...)` parses every text frame, resolves
  `workerId`, `workerGroupId`, and `routeKey` from the frame or session, then
  calls `registerSessionIfNeeded(...)` before forwarding the inbound message.
- `DispatcherInboundHandler` contains `routeKeyForWorkerGroup(...)`, which
  hard-codes `"bucket:" + workerGroupId` as a fallback route rule.
- `MassWebSocketServer` extends `TransportServer` only to expose
  `getClientChannel(String clientId)`.

## Owner Review

`DeliveryCommand` belongs to transport assigned delivery. It represents:

```text
transport -> websocket adapter -> selected worker
```

Inbound WebSocket frames represent:

```text
worker -> websocket adapter -> result/event/raw ingress
```

Therefore `DispatcherInboundHandler.channelRead0(...)` must not translate
`TextWebSocketFrame` into `DeliveryCommand`. Inbound frames may become result
ingress envelopes, worker/session events, or raw/manual messages, but not
assigned-delivery commands.

WebSocket adapter owns protocol I/O, session binding, frame parsing/writing,
endpoint lease projection, and final-hop send. It does not own task lifecycle,
worker selection, worker capability, route-key policy, or task payload schema.

## Boundary Decision

Converge the WebSocket adapter to these boundaries:

- outbound assigned delivery writes `DeliveryCommand.payload` directly to the
  selected worker's WebSocket session
- inbound worker frames read session identity from the already-bound channel
  session, not from per-frame delivery fields
- session identity is established only during handshake or an explicit
  session-open frame
- per-frame worker identity, bucket identity, and route metadata are diagnostics
  or legacy wire residue only; they must not rebind endpoint/session ownership
- route metadata fallback, if still required for legacy clients, must be a
  session-open concern and must not live as a generic frame-handler rule
- WebSocket-only Netty channel access stays inside adapter tests/session
  manager helpers, not the transport server interface

## Target Shape

Outbound assigned delivery:

```text
DeliveryCommand
  commandId
  deliveryBucketId
  selectedWorkerId
  payload
  correlationRef
  deadlineEpochMillis
  createdAtEpochMillis
      |
      v
WebSocketTaskDispatchChannel
  -> ServerSessionManager.sendToSelectedWorker(adapterId, selectedWorkerId, payload)
```

Inbound worker frame:

```text
TextWebSocketFrame.text()
  -> WebSocketJsonFrameParser
  -> channel-bound WebSocketSessionIdentity
  -> WebSocketResultIngressFrameReader or raw/event ingress
```

Final target classes should be small and single-owner:

- `WebSocketJsonFrameParser`: parse compact JSON object frames and write adapter
  error frames
- `WebSocketSessionOpenFrameReader`: parse only handshake/session-open identity
  fields
- `WebSocketResultIngressFrameReader`: recognize and build opaque
  `TransportResultIngressEnvelope` input
- `WebSocketTaskDispatchChannel`: direct `DeliveryCommand.payload` final-hop
  writer

The exact names can change during implementation, but the owner split should
not.

## Non-Goals

- Do not remove `routeKey` globally in this roadmap.
- Do not redesign result ingress across polling/socket/server.
- Do not change `DeliveryCommand` fields.
- Do not move task-result correctness validation into `websocket-adapter`.
- Do not introduce external adapter registration or adapter multi-instance
  discovery.
- Do not change socket adapter behavior in the same slice.
- Do not preserve compatibility aliases for removed WebSocket adapter internals.

## Do Not Start With

Do not make `DispatcherInboundHandler.channelRead0(...)` construct
`DeliveryCommand`. That reverses the direction of the model and would turn
worker result/event ingress into assigned task delivery.

Do not start by deleting every route-key mention from WebSocket code. First
move route metadata out of per-frame session rebinding, then leave any remaining
route-key cleanup to the route-key removal roadmap.

## WS-0 Inventory And Proof Baseline

Scope:

- Inventory production and test references to:
  `WebSocketTransportFrameCodec`, `encodeCanonicalTaskDispatch`,
  `registerSessionIfNeeded`, `routeKeyForWorkerGroup`,
  `MassWebSocketServer`, and `TransportPacket` imports inside
  `transport/websocket-adapter`.
- Classify each reference as outbound dispatch, inbound session binding, result
  ingress, raw/manual output, server lifecycle, or test fixture.

Acceptance:

- Inventory separates main-source and test-only references.
- The implementation sequence is confirmed before code changes.
- Existing dirty worktree changes outside transport are not touched.

Verification candidates:

```bash
rg -n "WebSocketTransportFrameCodec|encodeCanonicalTaskDispatch|registerSessionIfNeeded|routeKeyForWorkerGroup|MassWebSocketServer|TransportPacket" transport/websocket-adapter --glob "*.java" --glob "!**/target/**"
```

## WS-1 Outbound Dispatch Payload Passthrough

Goal:

Remove the fake codec layer from assigned WebSocket delivery.

Scope:

- Change `WebSocketTaskDispatchChannel` to pass `command.getPayload()` directly
  to `sendToSelectedWorker(...)`.
- Remove `WebSocketTransportFrameCodec` from `WebSocketCommandDispatchContext`.
- Delete `encodeCanonicalTaskDispatch(...)` and tests that assert dispatch
  through the codec.
- Keep `DeliveryCommand` unchanged.

Acceptance:

- Assigned dispatch path has no dependency on `WebSocketTransportFrameCodec`.
- WebSocket dispatch tests prove the payload is sent unchanged.
- No adapter dispatch code decodes or reshapes `DeliveryCommand.payload`.

Verification:

```bash
mvn -pl transport/websocket-adapter,transport/transport_api,transport/transport_runtime -am -DskipTests compile
mvn -pl transport/websocket-adapter -Dtest=WebSocketTaskDispatchChannelTest test
rg -n "encodeCanonicalTaskDispatch|WebSocketCommandDispatchContext.*FrameCodec|getFrameCodec\\(" transport/websocket-adapter/src/main/java --glob "*.java"
```

Expected residue:

- No `encodeCanonicalTaskDispatch`.
- No command-dispatch context dependency on frame codec.

## WS-2 Session Binding Only At Handshake Or Session Open

Goal:

Stop ordinary inbound worker frames from rebinding the WebSocket session.

Scope:

- Establish a channel-bound session identity during handshake or an explicit
  session-open frame.
- Change `channelRead0(...)` to read the current session identity from
  `ServerSessionManager` or channel attributes.
- Remove `registerSessionIfNeeded(...)` from the ordinary message path.
- Keep inbound frame fields such as `workerId`, `workerGroupId`, and `routeKey`
  as diagnostics only when present; they must not mutate session ownership.
- Remove `routeKeyForWorkerGroup(...)` from `DispatcherInboundHandler`.
- If a fallback endpoint address is still required for legacy clients, isolate
  it in a session-open resolver and name it as adapter-local endpoint address,
  not a worker-routing rule.

Acceptance:

- A normal result frame cannot change the worker id, delivery bucket, route
  metadata, endpoint lease, or session binding of the channel.
- Missing channel-bound identity causes an adapter-local error or close; it does
  not trigger implicit per-frame registration.
- No generic WebSocket frame handler contains a worker-group to route-key mint
  rule.

Verification:

```bash
mvn -pl transport/websocket-adapter -Dtest=DispatcherInboundHandlerTest test
rg -n "routeKeyForWorkerGroup|registerSessionIfNeeded\\(" transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/server --glob "*.java"
```

Expected residue:

- `registerSessionIfNeeded(...)` may remain only as handshake/session-open
  helper if the implementation keeps that name, but it must not be called from
  `channelRead0(...)`.
- `routeKeyForWorkerGroup(...)` has no matches.

## WS-3 Split Inbound Frame Parsing From Result Ingress

Goal:

Replace `WebSocketTransportFrameCodec` with narrow inbound helpers.

Scope:

- Introduce a compact JSON parser/writer for WebSocket adapter frames.
- Move result recognition and payload construction into a result-ingress
  reader used only by `WebSocketInputProcessor`.
- Move handshake/session-open field extraction into a session identity reader.
- Replace `WebSocketInputProcessor` calls to the old codec with the new
  result-ingress reader.
- Keep result payload opaque to transport. The reader may recognize the current
  worker wire result shell only to enqueue an opaque
  `TransportResultIngressEnvelope`; task-result correctness remains above
  transport.

Acceptance:

- `WebSocketTransportFrameCodec` is deleted.
- WebSocket adapter main code no longer imports `TransportPacket` for assigned
  dispatch or session binding.
- Any remaining result-wire field names are isolated to result ingress reader
  tests, not shared as a generic transport frame codec.
- JSON output is compact by default; pretty printing is not used in the
  hot-path frame writer.
- Malformed JSON logs a concise warning and does not emit a full stack trace on
  normal invalid input.

Verification:

```bash
mvn -pl transport/websocket-adapter -Dtest=WebSocketInputProcessorTest,DispatcherInboundHandlerTest test
rg -n "WebSocketTransportFrameCodec|TransportPacket|setPrettyPrinting" transport/websocket-adapter/src/main/java --glob "*.java"
```

Expected residue:

- No `WebSocketTransportFrameCodec` in main or tests.
- No `TransportPacket` import in websocket adapter main unless a later owner
  decision explicitly keeps it for result wire compatibility.

## WS-4 Remove MassWebSocketServer Escape Hatch

Goal:

Return WebSocket server lifecycle to the transport-neutral `TransportServer`
contract.

Scope:

- Delete `MassWebSocketServer`.
- Change `WebSocketServerImpl` to implement `TransportServer` directly.
- Delete `WebSocketServerImpl.getClientChannel(...)` unless a current
  production caller is found during WS-0.
- Update tests to use `ServerSessionManager` or explicit test fixtures when
  channel access is needed.

Acceptance:

- WebSocket server abstraction no longer exposes Netty `Channel`.
- Runtime assembly consumes `TransportServer`.
- No main-source references to `MassWebSocketServer`.

Verification:

```bash
mvn -pl transport/websocket-adapter,transport/transport_api -am -DskipTests compile
mvn -pl transport/websocket-adapter -Dtest=DispatcherInboundHandlerTest,ServerSessionManagerShutdownTest test
rg -n "MassWebSocketServer|getClientChannel\\(" transport/websocket-adapter/src/main/java transport/websocket-adapter/src/test/java --glob "*.java"
```

Expected residue:

- No `MassWebSocketServer`.
- No server-level `getClientChannel(...)` escape hatch.

## WS-5 Documentation And Guards

Goal:

Make the new WebSocket adapter boundary visible and guard against regression.

Scope:

- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` after code lands:
  remove "canonical task-dispatch frame adaptation" as a codec owner, clarify
  outbound payload passthrough, and state that normal inbound frames cannot
  rebind sessions.
- Update `transport/AGENTS.md` only if stable transport-wide rules change.
- Add or extend an architecture guard that fails if:
  - assigned WebSocket dispatch imports the removed codec
  - `DispatcherInboundHandler.channelRead0(...)` calls session registration
  - WebSocket adapter main reintroduces `WebSocketTransportFrameCodec`
  - WebSocket server main reintroduces `MassWebSocketServer`

Acceptance:

- Owner docs match implemented behavior.
- Guard failure messages point to the owner rule, not only to a symbol name.
- Focused WebSocket adapter tests and compile pass.

Verification:

```bash
mvn -pl transport/transport_runtime,transport/websocket-adapter -Dtest=TransportConvergenceArchitectureGuardTest,DispatcherInboundHandlerTest,WebSocketTaskDispatchChannelTest,WebSocketInputProcessorTest test
```

## Suggested Implementation Order

1. WS-0 inventory.
2. WS-1 outbound payload passthrough.
3. WS-2 session binding boundary.
4. WS-3 codec split/removal.
5. WS-4 server escape-hatch deletion.
6. WS-5 docs and guard cleanup.

WS-1 and WS-2 can land together if the patch remains small. WS-3 should not be
started until WS-1 removes the dispatch dependency from the codec.

## Completion Criteria

This roadmap can be marked complete only when all are true:

- assigned WebSocket dispatch sends `DeliveryCommand.payload` directly
- normal inbound text frames cannot rebind worker/session/route ownership
- no WebSocket handler hard-codes worker-group to route-key minting
- `WebSocketTransportFrameCodec` is gone or reduced to a name that no longer
  owns multiple directions; the preferred target is deletion
- `MassWebSocketServer` is gone
- WebSocket adapter owner docs reflect the new boundary
- residue scans and focused tests pass

## Open Decisions

- Whether legacy unmanaged WebSocket clients must still omit route metadata at
  handshake time. If yes, the fallback must be isolated to session-open
  identity resolution and documented as adapter-local endpoint address
  generation. It must not remain in the generic inbound frame handler.
- Whether current canonical result wire fields should keep using
  `TransportPacket` constants in tests only. Main-source adapter code should
  prefer adapter-local result-wire constants unless a stronger owner decision is
  made.
