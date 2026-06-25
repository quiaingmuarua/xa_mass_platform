# Transport Adapter Session Identity Convergence Roadmap

Status: active; session-identity mainline landed, residual ASI-2 cleanup remains.

## Summary

WebSocket session binding now normalizes protocol handshake fields into a
transport-runtime embedded-support identity before unwrapping that value into
the existing adapter-local registry/server-handle APIs:

```text
deliveryBucketId = upstream scheduling/index context for endpoint evidence
workerId         = selected execution identity
sessionHandle    = adapter-local connection/lease token
routeKey         = optional connection/correlation metadata
```

This roadmap introduced a small transport-runtime adapter session identity
value object at the protocol/evidence boundary. The first slice intentionally
did not rewrite the stable `WebSocketSessionRegistry` or
`WebSocketServerSessionHandle` mutation API; the inbound handler unwraps
identity into the existing registry methods while due-refresh consumes the typed
identity shape.

ASI-1 plus ASI-3 are complete and unblock the due-refresh roadmap. ASI-2 is an
adjacent inbound-frame cleanup recorded here because it touches the same
WebSocket inbound handler, but it is residual cleanup rather than a prerequisite
for due-refresh or session-identity mainline completion. This roadmap does not
implement endpoint lease due hints, bucket-scope refresh, route-key removal, or
assigned-delivery final-hop executor extraction.

## Current Mainline Facts

- `AdapterSessionIdentity(deliveryBucketId, workerId)` exists in
  transport-runtime embedded adapter support and is the WebSocket
  protocol/evidence-facing identity vocabulary.
- `WebSocketSessionIdentity`, `WebSocketSessionOpenFrameReader`, and the old
  `WebSocketFrameReadersTest` fixture have been removed.
- `DispatcherInboundHandler` parses WebSocket handshake query fields at the
  protocol edge, constructs `AdapterSessionIdentity`, and unwraps it into
  `WebSocketServerSessionHandle.addSession(deliveryBucketId, workerId,
  channel)`.
- `WebSocketServerSessionHandle` exposes loose string arguments:
  `addSession(String workerGroupId, String workerId, Channel)`.
- `WebSocketServerImpl` and `WebSocketServerFactoryContext` receive the same
  server session handle, so custom server factories are part of the WebSocket
  session identity caller surface.
- `WebSocketSessionRegistry` duplicates those fields in `bind(...)`,
  `SessionEntry`, and `SessionSnapshot`.
- `WebSocketSessionEvidenceRefresher` consumes list-all
  `SessionSnapshot(workerGroupId, workerId, sessionHandle)` values.
- `DispatcherInboundHandler.channelRead0(...)` is WebSocket-local as a Netty
  handler, but it currently also owns adapter-common inbound message mechanics:
  raw text JSON-object validation, JSON parsing, session-bound rejection, and
  parse error classification. WebSocket-specific behavior in that path is only
  `TextWebSocketFrame` extraction, `ChannelHandlerContext` error-frame writing,
  and Netty channel lifecycle.
- WebSocket assigned delivery now contributes a runtime-created
  `AdapterCommandExecutor` from `WebSocketTransportAdapterBootstrap` by passing
  a final-hop `send(DispatchMessage)` function to
  `AdapterCommandExecutors.perMessage(...)`. There is no
  `WebSocketTaskDispatchChannel` wrapper class in the target WebSocket shape.
  This remains separate from session identity and due-refresh work.
- `MassApplicationBuilder` and `MassSdk` expose
  `TransportServerFactory<WebSocketServerFactoryContext>`, and
  `WebSocketServerFactoryContext` exposes `WebSocketServerSessionHandle`.
  ASI-1 intentionally avoids retargeting this handle to
  `AdapterSessionIdentity`; changing it later would be an embedded SDK
  custom-server-factory breaking surface, not a private WebSocket-only change.
- `AdapterSessionEvidencePublisher.connected/heartbeat/disconnected(...)`
  still receives loose `workerId, deliveryBucketId, sessionHandle` arguments.
  WebSocket callers may unwrap `AdapterSessionIdentity` at that publisher
  boundary, but changing the publisher API itself is deferred because
  polling/socket also consume it.
- Socket has a related but wider identity shape
  `deliveryBucketId + routeKey + workerId + endpointId`; socket catch-up is a
  later phase and must not force routeKey into the common identity.

## Owner Review

`AdapterSessionIdentity` belongs to transport runtime embedded adapter support.
Concrete adapters may parse protocol-specific handshake fields into it and pass
it through evidence-facing seams, but they should not define their own
worker/bucket identity vocabulary.

The identity is evidence input, not scheduling truth:

```java
public record AdapterSessionIdentity(
        String deliveryBucketId,
        String workerId
) {}
```

Allowed consumers in ASI-1:

- protocol-edge WebSocket handshake parsing and session-bound checks
- future adapter session evidence refresher source lookups
- WebSocket adapter code that unwraps identity for current
  `WebSocketServerSessionHandle` / `WebSocketSessionRegistry` calls
- WebSocket adapter code that unwraps identity for current
  `AdapterSessionEvidencePublisher` endpoint lease and worker-presence calls

JSON inbound frame parsing belongs to transport runtime embedded adapter
support when it is independent of the concrete protocol carrier. Concrete
adapters own protocol frame extraction and error writing, but they should not
each duplicate raw JSON validation, parse failure classification, or
session-bound rejection mechanics.

`WebSocketServerSessionHandle` is part of the embedded WebSocket custom server
factory surface. ASI-1 keeps this surface stable and continues to expose the
existing loose-argument session methods. Retargeting that handle to
`AdapterSessionIdentity` remains a deferred breaking change because it also
changes SDK-exposed custom factory callers.

Forbidden consumers:

- engine scheduling, worker selection, or dispatch binding
- `DispatchMessage`
- common or WebSocket-local dispatch input
- route-key raw/manual side-channels
- endpoint lease store metadata values that require session handle or routeKey

`AdapterSessionEvidencePublisher` remains a loose-argument capability in this
roadmap. WebSocket callers may unwrap identity at the publisher boundary. A
publisher-wide identity overload or signature replacement must be handled by a
separate slice that covers WebSocket, socket, and polling together.

`sessionHandle` is not part of identity. It is the current connection/lease
token for one concrete session. `routeKey` is not part of identity. It remains
opaque connection/correlation metadata or socket raw-route residue.

## Boundary Decision

Introduce typed identity at the WebSocket protocol/evidence boundary while
keeping ASI-1 off the stable registry/server-handle mutation API:

```text
DispatcherInboundHandler parses handshake query
  -> AdapterSessionIdentity(deliveryBucketId=<query workerGroupId>, workerId)
  -> unwraps identity into existing WebSocketServerSessionHandle.addSession(...)
```

`WebSocketServerSessionHandle.addSession(String workerGroupId, String workerId,
Channel)` and `currentWorkerId(Channel)` may remain until a later registry/API
slice proves the custom-server-factory blast radius is worth taking.

The reusable inbound frame processor also belongs in transport runtime
embedded adapter support:

```java
final class JsonAdapterInboundFrameProcessor {
    JsonAdapterInboundFrameResult process(
            String rawFrame,
            boolean sessionBound);
}
```

`JsonAdapterInboundFrameProcessor` may use `TransportJsonFrameParser` and may
return common rejection codes such as `INVALID_FORMAT`, `PARSE_FAILED`, and
`SESSION_NOT_BOUND`. It must not import Netty/WebSocket classes, write protocol
frames, parse handshake URIs, dispatch results, or interpret task/action
payload semantics.

Selected-worker final-hop dispatch stays on the current WebSocket assigned
delivery path in this roadmap:

```text
DispatchMessage.selectedWorkerId
  -> WebSocketTransportAdapterBootstrap contributed AdapterCommandExecutor
  -> AdapterCommandExecutors.perMessage(...)
  -> WebSocketSessionRegistry.sendTextToWorker(workerId, frame)
```

Do not reintroduce a WebSocket protocol-specific executor wrapper class as part
of this session identity roadmap. WebSocket owns the final-hop send function;
runtime embedded support owns the batch/outcome executor wrapper.

Do not keep a standalone WebSocket session-open reader. WebSocket handshake
query parsing is protocol-edge work inside `DispatcherInboundHandler`; a
separate class that only creates `AdapterSessionIdentity` is not an owner
boundary.

Do not extract `DispatcherInboundHandler` itself into common runtime. The
common seam is raw text JSON inbound processing, not Netty/WebSocket channel
handling.

## Target Shape

### Session Open

```text
WebSocket handshake query
  -> DispatcherInboundHandler handshake query parser
  -> AdapterSessionIdentity(deliveryBucketId=<query workerGroupId>, workerId)
  -> WebSocketServerSessionHandle.addSession(identity.deliveryBucketId,
                                             identity.workerId,
                                             channel)
  -> WebSocketSessionRegistry stores current loose fields unchanged
  -> existing registry evidence publishing behavior remains unchanged
```

The WebSocket query parameter may remain named `workerGroupId` for now. That
is input compatibility at the protocol edge only. Inside newly introduced
identity/evidence-facing code, the value is `deliveryBucketId`. Existing
registry method names may stay loose until the deferred registry API slice.

### Current Evidence

```text
DispatcherInboundHandler.channelRead0
  -> raw text from TextWebSocketFrame
  -> currentWorkerId(channel) for the current first slice
  -> TransportJsonFrameParser parses JSON and DispatcherInboundHandler maps
     invalid/session-unbound frames to WebSocket error frames
  -> if accepted: inboundFrameSink.accept(frame)
  -> WebSocketTransportAdapterBootstrap result path uses
     AdapterInboundResultProcessor
```

Future due-refresh work should use the same identity shape:

```text
TransportEndpointLeaseDueHint(identity, leaseExpireAtEpochMillis)
  -> AdapterSessionEvidenceSource.currentEvidence(identity)
  -> AdapterSessionEvidenceSnapshot(identity, sessionHandle)
  -> AdapterSessionEvidencePublisher.heartbeat(...)
```

### Final-Hop Dispatch

```text
DispatchMessage.selectedWorkerId
  -> WebSocketTransportAdapterBootstrap contributed AdapterCommandExecutor
  -> AdapterCommandExecutors.perMessage(...)
  -> WorkerChannelFrameJsonCodec.encodeAction(payload)
  -> WebSocketSessionRegistry.sendTextToWorker(selectedWorkerId, frame)
  -> WebSocketSessionRegistry worker-id lookup
```

Do not pass `AdapterSessionIdentity` or `deliveryBucketId` into
`DispatchMessage` or the adapter command executor path. This roadmap does not
move the WebSocket assigned-delivery executor into session identity.

Concrete adapters still own protocol carriers. WebSocket still owns
`TextWebSocketFrame` construction and channel writes.

### Inbound Worker Frames

```text
WebSocket TextWebSocketFrame
  -> DispatcherInboundHandler extracts raw text and current session identity
  -> DispatcherInboundHandler validates, parses, and checks binding
  -> DispatcherInboundHandler maps rejection to WebSocket error frame
  -> accepted JsonObject goes to adapter inbound frame sink
```

The common processor is not the result-ingress processor and not a worker
action dispatcher. It only normalizes the carrier-independent part of receiving
an adapter inbound JSON frame.

## Non-Goals

- Do not implement endpoint lease due hints in this roadmap.
- Do not add adapter-scoped due indexes.
- Do not change worker selection, dispatch binding, or `DispatchMessage`.
- Do not add `deliveryBucketId` to push final-hop send input.
- Do not move session identity to `transport_api`.
- Do not move Netty `Channel`, WebSocket `TextWebSocketFrame`, socket
  channels, session handles, or local session registries into
  `transport_runtime`.
- Do not move WebSocket `ChannelHandlerContext` error writing, handshake event
  handling, or `TextWebSocketFrame` construction into the common inbound JSON
  processor.
- Do not make the common inbound JSON processor understand result ingress,
  worker action semantics, task/project fields, or frame-specific business
  handlers. It only validates/parses raw JSON text and checks that the carrier
  has a bound adapter session identity.
- Do not create a generic endpoint registry or selected-worker endpoint lookup
  abstraction.
- Do not reintroduce `WebSocketTaskDispatchChannel` or another WebSocket
  protocol-specific `AdapterCommandExecutor` wrapper class.
- Do not keep or introduce a reader/factory/helper class whose only job is to
  instantiate `AdapterSessionIdentity` from WebSocket handshake query fields.
- Do not include routeKey, adapterId, endpoint lease id, session handle,
  connection id, channel id, or transport hint in `AdapterSessionIdentity`.
- Do not change external WebSocket handshake query names in this slice.
- Do not force socket/polling to adopt `AdapterSessionIdentity` in the first
  slice.

## Do Not Start With

Do not start by changing `DispatchMessage`, socket executor wiring, or broader
assigned-delivery command semantics.

This convergence is about session evidence identity. Assigned task dispatch
already has the correct final-hop constraint: `selectedWorkerId`.

Do not start by moving the record to `transport_api`. This is not an external
transport-neutral protocol contract; it is embedded adapter/session evidence
support.

Do not treat session identity cleanup as permission to remove the WebSocket
final-hop dispatch capability. WebSocket final-hop dispatch remains present as
a bootstrap-contributed runtime executor built from a final-hop send function.

Do not preserve `WebSocketSessionOpenFrameReader` as a rename-only shim after
`WebSocketSessionIdentity` is removed. Move the tiny query parsing into the
WebSocket inbound handler and test the behavior there.

Do not extract a generic Netty/WebSocket handler to make `channelRead0` look
shared. Extract only the carrier-independent raw JSON processing and rejection
classification; WebSocket remains the owner of Netty frame IO and channel
lifecycle.

## Deferred Follow-Ups

- Socket assigned-delivery final-hop executor extraction has been handled by
  the separate socket adapter worker-id final-hop slice. Socket now follows the
  bootstrap-contributed runtime executor shape instead of a protocol-specific
  task dispatch wrapper.
- `AdapterSessionEvidencePublisher` identity overload/signature convergence:
  the publisher still receives loose `workerId, deliveryBucketId,
  sessionHandle` parameters. Replacing that API should cover WebSocket, socket,
  and polling together instead of quietly changing only WebSocket.
- WebSocket registry/server-handle typed API convergence:
  `WebSocketServerSessionHandle` and `WebSocketSessionRegistry` may later move
  from loose `workerGroupId, workerId` methods to `AdapterSessionIdentity`.
  That is a separate embedded SDK custom-server-factory breaking surface and
  should not be forced into the due-refresh prerequisite slice.

## ASI-0 - Inventory Current Identity Callers

Scope:

- inventory production and test callers of:
  - `WebSocketSessionIdentity`
  - `WebSocketSessionOpenFrameReader`
  - `WebSocketFrameReadersTest` handshake identity assertions
  - `WebSocketServerSessionHandle.addSession(...)`
  - `WebSocketServerSessionHandle.currentWorkerId(...)`
  - `WebSocketServerImpl`
  - `WebSocketServerFactoryContext`
  - `WebSocketTransportAdapterBootstrap` custom server factory wiring
  - `WebSocketSessionRegistry.SessionSnapshot`
  - `WebSocketSessionRegistry.activeSessionSnapshots()`
- classify socket session identity fields separately:
  `deliveryBucketId`, `routeKey`, `workerId`, `endpointId`
- confirm no engine/starter dispatch path consumes WebSocket session identity

Acceptance:

- inventory is recorded in this roadmap or a sibling inventory
- WebSocket-only first slice is confirmed
- due-refresh dependency points are named
- socket routeKey residue is recorded as deferred, not folded into common
  identity

## ASI-1 - Introduce AdapterSessionIdentity And Retarget WebSocket Bind

Scope:

- add `AdapterSessionIdentity` under transport runtime embedded/session
  support
- delete `WebSocketSessionOpenFrameReader`
- move WebSocket handshake query parsing into `DispatcherInboundHandler`
  as a private protocol-edge helper that constructs `AdapterSessionIdentity`
- delete `WebSocketSessionIdentity`
- keep `WebSocketServerSessionHandle` and `WebSocketSessionRegistry` mutation
  APIs unchanged in ASI-1; `DispatcherInboundHandler` unwraps identity into
  the existing `addSession(identity.deliveryBucketId(), identity.workerId(),
  channel)` call
- keep `currentWorkerId(Channel)` for inbound session-bound checks in ASI-1
- change `WebSocketServerImpl` constructor/wiring so it no longer receives a
  `WebSocketSessionOpenFrameReader`
- move handshake query compatibility proof out of `WebSocketFrameReadersTest`
  into `DispatcherInboundHandlerTest` or a focused WebSocket protocol-edge
  test; no test should preserve `WebSocketSessionOpenFrameReader` or
  `WebSocketSessionIdentity` as a fixture
- keep `WebSocketServerFactoryContext` on the existing narrow
  `WebSocketServerSessionHandle` surface; no SDK custom-server-factory API
  break is required in ASI-1
- keep `WebSocketSessionRegistry` `SessionEntry` / `SessionSnapshot` field
  shape unchanged in ASI-1
- keep connect/disconnect endpoint lease and worker-presence publishing
  behavior unchanged
- do not change `AdapterSessionEvidencePublisher` signatures in this slice;
  WebSocket registry/refresher may unwrap `AdapterSessionIdentity` when calling
  the current publisher methods
- prove WebSocket handshake parsing creates `AdapterSessionIdentity` and then
  unwraps it to the existing registry boundary; registry connect, disconnect,
  and refresher heartbeat evidence behavior remains unchanged

Acceptance:

- `WebSocketSessionIdentity` is removed
- `WebSocketSessionOpenFrameReader` is removed
- no production or test code preserves `WebSocketSessionOpenFrameReader` or
  `WebSocketSessionIdentity` as a second public fixture
- WebSocket registry still indexes only by worker id and channel
- `workerGroupId` remains only at the `DispatcherInboundHandler` WebSocket
  protocol-edge handshake parser or tests that assert query compatibility;
  newly introduced identity/evidence-facing code uses `deliveryBucketId`
- default Netty server and custom server factory wiring compile without
  changing the existing `WebSocketServerSessionHandle` method signatures
- handshake query compatibility proof moves off the deleted reader and proves
  identity construction at the WebSocket protocol edge
- existing WebSocket session evidence tests continue to prove connected,
  disconnected, and heartbeat publisher calls through the registry/refresher
- `AdapterSessionEvidencePublisher` loose-argument methods are documented as
  deferred residue rather than described as already converted
- existing stale replacement/disconnect semantics still pass

## ASI-2 - Residual: Extract Common JSON Inbound Frame Processor

This is residual cleanup, not part of the due-refresh prerequisite or
session-identity mainline completion. It may land with ASI-1 if implementation
touches the same handler anyway, but it must not delay ASI-1/ASI-3 or force a
generic WebSocket/Netty handler abstraction.

Scope:

- add a transport runtime embedded-support processor such as:

  ```java
  final class JsonAdapterInboundFrameProcessor {
      JsonAdapterInboundFrameResult process(
              String rawFrame,
              boolean sessionBound);
  }
  ```

- add a small result record or equivalent internal value with:
  - status: accepted or rejected
  - accepted `JsonObject` frame
  - rejected error code/message
- move raw text checks and JSON object parsing out of
  `DispatcherInboundHandler.channelRead0(...)`
- move `INVALID_FORMAT`, `PARSE_FAILED`, and `SESSION_NOT_BOUND` rejection
  classification into the common processor
- leave `DispatcherInboundHandler` responsible for:
  - extracting `msgFrame.text()`
  - reading `currentWorkerId(ctx.channel())` and converting it to
    `sessionBound`
  - writing WebSocket error frames
  - catching/logging unexpected Netty handler exceptions
  - forwarding accepted frames to the existing inbound frame sink
- keep WebSocket handshake query parsing in `DispatcherInboundHandler`; the
  common processor only handles text message frames after the session identity
  surface exists
- add focused processor tests for invalid format, malformed JSON object,
  missing session identity, and accepted frame
- retarget `DispatcherInboundHandlerTest` so it proves WebSocket mapping of
  processor rejections to WebSocket error frames, not JSON parsing internals

Acceptance:

- `DispatcherInboundHandler.channelRead0(...)` no longer directly calls
  `TransportJsonFrameParser.parseObject(...)` or owns JSON parse failure
  classification
- the common inbound processor lives in transport runtime embedded adapter
  support and does not import Netty, WebSocket, socket, session registry, or
  result-ingress classes
- WebSocket still owns `TextWebSocketFrame` extraction and error-frame writing
- common processor tests prove accepted/rejected shapes without starting a
  WebSocket server
- WebSocket tests still prove `INVALID_FORMAT`, `PARSE_FAILED`, and
  `SESSION_NOT_BOUND` are sent as WebSocket error frames
- result ingress still flows through `AdapterInboundResultProcessor`; this
  slice does not create a second result processor

## ASI-3 - Prepare Due-Refresh Consumption

Scope:

- update `TRANSPORT_SESSION_EVIDENCE_DUE_REFRESH_CONVERGENCE_ROADMAP.md` to
  consume `AdapterSessionIdentity`
- define the future due-refresh source shape in terms of identity:

  ```java
  public record AdapterSessionEvidenceSnapshot(
          AdapterSessionIdentity identity,
          String sessionHandle
  ) {}

  public interface AdapterSessionEvidenceSource {
      Optional<AdapterSessionEvidenceSnapshot> currentEvidence(
              AdapterSessionIdentity identity);
  }
  ```

- do not implement due hints or bucket-scope iteration in this roadmap

Acceptance:

- due-refresh roadmap no longer describes current evidence source as parallel
  `deliveryBucketId, workerId` string parameters
- due-refresh roadmap still owns due hints, bucket scope, and bounded refresh
  behavior
- no code behavior changes are required in ASI-3 beyond documentation unless
  ASI-1 has already landed

## ASI-4 - Guards And Docs

Scope:

- update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`
- update `transport/AGENTS.md` to describe typed WebSocket session identity and
  common embedded JSON inbound processing
- update `doc/PROOF_REGISTRY.md` rows covering WebSocket session identity,
  inbound frame parsing, and endpoint/session evidence where those rows drift
- update `TransportConvergenceArchitectureGuardTest`

Guard targets:

- WebSocket production code must not contain
  `WebSocketSessionIdentity`
- WebSocket production code must not contain
  `WebSocketSessionOpenFrameReader`
- `AdapterSessionIdentity` must not contain routeKey, adapterId,
  sessionHandle, endpointLeaseId, connectionId, or transportHint fields
- WebSocket protocol-edge code may unwrap `AdapterSessionIdentity` into the
  existing `WebSocketServerSessionHandle` methods, but assigned dispatch models
  must not import or consume identity
- assigned dispatch models must not import `AdapterSessionIdentity`
- common JSON inbound processing must not import Netty, WebSocket, socket,
  adapter session registry, or result-ingress classes
- `DispatcherInboundHandler` must not own raw JSON parse failure
  classification after the common processor lands

Acceptance:

- guard fails if `WebSocketSessionIdentity` or `WebSocketSessionOpenFrameReader`
  are reintroduced
- guard fails if `AdapterSessionIdentity` becomes a fat endpoint/session model
- docs describe identity as session evidence input, not scheduling truth

## Verification Candidates

Correct test names after ASI-0 inventory if they drift.

Session-identity mainline proof:

```powershell
.\mvnw.cmd -q -pl transport/websocket-adapter -am test "-Dtest=DispatcherInboundHandlerTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest,WebSocketTransportAdapterBootstrapTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime -am -Dtest=TransportConvergenceArchitectureGuardTest test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
rg -n "WebSocketSessionOpenFrameReader|WebSocketSessionIdentity" transport/websocket-adapter/src/main/java transport/websocket-adapter/src/test/java
```

The final `rg` command should return no production or test hits after ASI-1.
Strict proof should include target-module Surefire reports for the retargeted
WebSocket session identity tests. Do not rely on a deleted
`WebSocketFrameReadersTest` fixture to prove handshake query compatibility; the
proof must live in `DispatcherInboundHandlerTest` or a successor protocol-edge
test that still exists.

Residual ASI-2 processor proof:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime -am -Dtest=JsonAdapterInboundFrameProcessorTest test "-DtrimStackTrace=true"
```

Do not use `-Dsurefire.failIfNoSpecifiedTests=false` for the strict runtime
processor proof.

## Due-Refresh Unblock Criteria

The due-refresh roadmap is unblocked when ASI-1 and ASI-3 are complete:

- `AdapterSessionIdentity(deliveryBucketId, workerId)` is the WebSocket
  protocol/evidence-facing identity vocabulary.
- WebSocket handshake parsing constructs identity and unwraps it into the
  existing registry boundary without preserving `WebSocketSessionIdentity`.
- `WebSocketSessionOpenFrameReader` and `WebSocketSessionIdentity` are removed
  from production and tests.
- `TRANSPORT_SESSION_EVIDENCE_DUE_REFRESH_CONVERGENCE_ROADMAP.md` consumes
  `AdapterSessionIdentity` for current evidence lookup.

ASI-2 common inbound JSON processing may land before or after the due-refresh
roadmap starts. It is not a due-refresh prerequisite.

## Session-Identity Mainline Completion Criteria

- `AdapterSessionIdentity(deliveryBucketId, workerId)` is the WebSocket
  protocol/evidence-facing identity vocabulary
- `WebSocketSessionIdentity` is removed
- `WebSocketSessionOpenFrameReader` is removed; handshake query parsing lives
  at the WebSocket inbound protocol edge
- ASI-1 keeps `WebSocketSessionRegistry` and `WebSocketServerSessionHandle`
  mutation APIs stable; any future typed registry API is a separate breaking
  change with its own SDK custom-factory proof
- WebSocket assigned delivery remains a bootstrap-contributed runtime executor
  built from a WebSocket final-hop send function; do not recreate a
  `WebSocketTaskDispatchChannel` wrapper
- assigned push dispatch remains worker-id-only and does not consume session
  identity, bucket, route, endpoint lease, or session handle facts
- `AdapterSessionEvidencePublisher` loose-argument API is either unchanged and
  documented as deferred residue, or converted by a separate socket/polling/
  WebSocket-wide slice
- due-refresh roadmap references `AdapterSessionIdentity` for current evidence
  lookup
- owner docs and proof registry match implemented behavior
- architecture guard prevents identity widening and dispatch-path leakage

When this section is satisfied, the due-refresh roadmap is unblocked even if
ASI-2 has not landed.

## Residual ASI-2 Cleanup Criteria

- carrier-independent inbound JSON validation, parse failure classification,
  and session-bound rejection live in transport runtime embedded adapter
  support
- WebSocket owns only carrier extraction and WebSocket error writing
- residual proof runs `JsonAdapterInboundFrameProcessorTest` without
  `-Dsurefire.failIfNoSpecifiedTests=false`
