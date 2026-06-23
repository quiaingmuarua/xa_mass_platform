# Transport Adapter Worker-Channel Dispatcher Extraction Convergence Roadmap

Status: mainline implemented; WebSocket/runtime dispatcher extraction landed,
with Socket adoption left as residue/follow-up.

## Current Code Observations

The WebSocket/runtime mainline now matches this roadmap:

- `WebSocketInputProcessor`, `WebSocketDispatcherContext`,
  `WebSocketInboundFrameSink`, and `WebSocketResultIngressFrameReader` are
  removed from production main.
- `transport_runtime.embedded` owns `AdapterInboundResultProcessor`,
  `AdapterResultFrameReader`, `AdapterResultFrame`,
  `AdapterResultDiagnosticsProvider`, `AdapterResultProcessOutcome`, and
  `WorkerChannelActionReplyResultFrameReader`.
- `WebSocketResultDiagnosticsProvider` is the WebSocket-local diagnostic
  provider; it does not construct `ResultIngressEntry`.
- `WebSocketSessionOpenFrameReader` is URI/query handshake-only; the parsed
  frame session identity path was not promoted because production WebSocket
  session open does not use it.
- `DispatcherInboundHandler` parses Netty text frames and passes parsed JSON
  into a narrow `Consumer<JsonObject>`.
- `WebSocketTransportAdapterBootstrap` wires the fixed runtime result
  processor with the first-party WebSocket reader, diagnostics provider, and
  adapter-facing result ingress sink.
- Socket remains a lagging adapter residue line and must not define or
  constrain the WebSocket/runtime boundary unless a socket-unique protocol
  requirement is explicitly identified.

## Owner Review

Worker-channel carrier parsing and task-result ingress normalization belong to
transport embedded adapter support, not to a concrete WebSocket adapter.

Concrete adapters own only protocol-specific I/O and session state:

- WebSocket owns Netty channel/session registry, URI/query handshake parsing,
  text-frame read/write, and WebSocket-local diagnostics.
- Socket owns line-delimited socket I/O, socket session manager, and socket
  frame read/write. In this roadmap it is residue/comparison evidence, not the
  owner that defines the runtime support shape.
- Transport runtime embedded support may own common worker-channel carrier
  readers, result-entry construction, inbound result processing, and
  final-hop outcome normalization for already selected workers.

Public worker contract owns only stable worker wire carrier DTOs/codecs such as
`WorkerChannelFrame` and `WorkerChannelFrameJsonCodec`. It must not parse
adapter result body semantics such as `replyRef`.

## Boundary Decision

Extract the WebSocket worker-channel dispatcher/frame mainline into transport
runtime embedded support, but do not create a generic `DispatcherContext` or a
broad adapter framework. Socket may adopt the result processor later only when
that removes real duplication without changing the WebSocket/runtime boundary.

Allowed shared support:

- JSON text-frame helper already in `TransportJsonFrameParser`.
- Worker-channel `ACTION_REPLY` carrier reader and `replyRef` extraction.
- Result frame reader abstraction for parsed protocol frames that emits only
  minimal result facts, not `ResultIngressEntry`.
- Default inbound result processor that combines an adapter-provided result
  reader, diagnostics provider, and `AdapterResultIngressSink`.
- First-party constructor dependencies for the initial WebSocket convergence:
  result reader, diagnostics provider, and existing result ingress sink. These
  are not extension hooks or public plugin points.
- Worker session identity field reader only if inventory proves a real
  production caller outside WebSocket URI/query handshake.
- Per-message final-hop outcome normalization already in
  `AdapterCommandExecutors`.

Must stay adapter-local:

- Netty, `Channel`, `TextWebSocketFrame`, request URI/query parsing, and
  `QueryStringDecoder`.
- WebSocket session registry and selected-worker channel write.
- Socket socket streams, endpoint ids, and socket session manager.
- Adapter-specific handshake/control-frame decisions when they depend on a
  protocol-level connection lifecycle.
- Adapter-specific unknown/unsupported frame handling. Shared result processing
  must not turn an unknown frame into a successful no-op.

## Target Shape

Transport runtime embedded support should expose narrow role contracts/classes:

```text
AdapterResultFrameReader<T>
  boolean isResultFrame(T frame)
  AdapterResultFrame read(T resultFrame)

AdapterResultFrame
  String correlationRef
  String payload
  String traceSeed
  String frameId

AdapterResultDiagnosticsProvider<T>
  Map<String, String> diagnostics(T resultFrame, AdapterResultFrame result)

AdapterInboundResultProcessor<T>
  static with(reader, resultIngressSink, diagnosticsProvider)
  AdapterResultProcessOutcome processResult(T resultFrame)

AdapterResultProcessOutcome
  INGESTED | REJECTED | FAILED

WorkerChannelActionReplyResultFrameReader
  input: JsonObject frame
  deps: TransportJsonFrameParser + WorkerChannelActionReplyReader
  output: AdapterResultFrame

Optional WorkerSessionIdentityFrameReader
  input: JsonObject frame
  output: AdapterWorkerSessionIdentity(workerGroupId, workerId)
  condition: only if ADC-0 finds a real production caller
```

Names may be adjusted during implementation, but the owner split must stay:
shared support owns the default result processor, result facts contract, and
worker-channel reader; concrete adapters own protocol/session resources,
frame classification, and protocol diagnostics. ADC-1 should wire the built-in
processor with first-party WebSocket reader, diagnostics, and sink dependencies
only. Do not design an extension hook set yet. The processor flow is not a
plugin point. Do not replace this with an `AdapterDispatcherContext` or
service-locator object.

## Initial Convergence Boundary

This roadmap is about making the current owner boundary explicit before opening
extension points. Treat the following as first-party constructor dependencies
for the convergence, not as a supported adapter plugin API.

Initial dependencies:

| Dependency | Owner | Purpose | Not Allowed |
| --- | --- | --- | --- |
| `WorkerChannelActionReplyResultFrameReader` | transport embedded support | read the shared worker-channel `ACTION_REPLY` frame into `AdapterResultFrame` facts | construct `ResultIngressEntry`, parse task-result success/failure semantics, select workers, or write ingress |
| WebSocket diagnostics provider/function | WebSocket adapter | add WebSocket-local trace, route, session, or protocol diagnostics | affect result correctness, routing, retry, worker selection, or endpoint feasibility |
| `AdapterResultIngressSink` | transport runtime assembly | write normalized result entries into the existing transport result ingress path; tests may inject fakes | redefine result-ingress carrier shape or bypass `AdapterResultIngressEntries` |

Not replaceable as plugin points in this roadmap:

- session registry, local worker-session lookup, and session mutation owner;
- endpoint/session evidence publisher and endpoint lease semantics;
- mailbox consumer, dispatch handoff, and mailbox polling/offer mechanics;
- adapter lifecycle, host mounting, and managed-resource start/stop policy;
- inbound result processor flow and result-entry construction order;
- result ingress carrier shape and `ResultIngressEntry` construction contract;
- `DispatchOutcome` construction contract and known-failure evidence shape;
- worker selection, selected-worker correctness, and delivery target evidence.

If future adapter variants need supported override hooks, define them after the
default WebSocket path has converged and after the owner, caller, failure
semantics, and proof are clear. Do not introduce them as a side effect of this
dispatcher extraction.

Default processor flow:

```text
adapter local classification says "this is a result frame"
  -> AdapterInboundResultProcessor.processResult(resultFrame)
  -> resultReader.read(resultFrame) returns AdapterResultFrame
  -> diagnosticsProvider.diagnostics(resultFrame, result)
  -> AdapterResultIngressEntries.from(result.correlationRef, result.payload, diagnostics)
  -> AdapterResultIngressSink.ingest(entry)
```

WebSocket target:

```text
TextWebSocketFrame
  -> TransportJsonFrameParser.parseObject(...)
  -> WebSocket-local control/result/unknown classification
  -> built-in WorkerChannelActionReplyResultFrameReader
  -> WebSocket diagnostics provider for adapterId/routeKey/trace fallback
  -> AdapterInboundResultProcessor<JsonObject>.processResult(resultFrame)

Handshake URI
  -> WebSocket adapter-local handshake identity reader
  -> WebSocket session identity

DispatchMessage
  -> WebSocket adapter local frame write
  -> WebSocketSessionRegistry.sendTextToWorker(...)
```

Socket residue target if a later slice adopts the shared processor:

```text
socket line
  -> TransportJsonFrameParser.parseObject(...)
  -> socket control/handshake/heartbeat handling
  -> socket-local result/unsupported classification
  -> SocketResultFrameReader.read(...) returns AdapterResultFrame
  -> Socket diagnostics provider for adapterId/routeKey/trace fallback
  -> AdapterInboundResultProcessor<JsonObject>.processResult(resultFrame)
```

If ADC-4 does not adopt the shared processor, socket may remain adapter-local
for result-frame handling. That decision must not block WebSocket convergence
or force WebSocket/runtime support to preserve socket's current legacy shape.
Socket should continue using the transport-owned `AdapterResultIngressEntries`
normalization path where it already does so.

## Non-Goals

- Do not change worker-channel wire shape.
- Do not change result ingress payload shape or `replyRef` semantics.
- Do not change mailbox handoff, dispatch queue, adapter lifecycle, or session
  evidence TTL behavior.
- Do not create a new public SDK/contract DTO.
- Do not introduce a generic adapter lifecycle framework or service-locator
  context.
- Do not move WebSocket Netty/session registry concerns into transport runtime.
- Do not force socket canonical JSON frames into the WebSocket
  `WorkerChannelFrame(ACTION_REPLY)` reader.

## Do Not Start With

Do not begin with a package rename of `websocket/dispatcher` or
`websocket/frame`. First move the common reader/processor behavior into
runtime embedded support and update callers. Package deletion is residue work
after the owner boundary is real.

Do not introduce a replacement `AdapterDispatcherContext`. Direct constructor
injection of narrow role objects is preferred.

## ADC-0 - Inventory And Classification

Scope:

- Classify all current `transport/websocket-adapter/.../dispatcher` and
  `transport/websocket-adapter/.../frame` classes by owner:
  shared worker-channel support, WebSocket protocol/session glue, or residue.
- Include socket result and dispatch paths only as residue/comparison rows:
  `SocketTransportFrameCodec`, `SocketTransportServer`, and
  `SocketTaskDispatchChannel`.
- Record any socket-unique requirement that would make shared processing
  unsafe, but do not let socket residue block ADC-1.
- Identify tests that currently preserve WebSocket-specific class names.

Acceptance:

- Inventory in this roadmap names each class and target owner.
- No implementation change is required in ADC-0.
- Verification candidates are corrected to real test classes before execution.

Current classification:

| Class | Current Owner | Target Owner | Classification |
| --- | --- | --- | --- |
| `WebSocketInputProcessor` | WebSocket adapter | split | result ingest processing moves to runtime embedded support; unknown/control handling stays adapter-local |
| `WebSocketDispatcherContext` | WebSocket adapter | deleted | pass-through context residue |
| `WebSocketInboundFrameSink` | WebSocket adapter | deleted or `Consumer<JsonObject>` | single-method wrapper residue |
| `WebSocketTaskDispatchChannel` | WebSocket adapter | WebSocket adapter, renamed/moved if useful | protocol final-hop sender |
| `WebSocketResultIngressFrameReader` | WebSocket adapter | split | worker-channel result facts reader moves to runtime embedded support; WebSocket diagnostics provider stays adapter-local |
| `WebSocketSessionOpenFrameReader` | WebSocket adapter | WebSocket adapter | URI/query handshake reader; parsed-frame identity path is suspect residue until proven |
| `WebSocketSessionIdentity` | WebSocket adapter | WebSocket adapter unless a second production caller appears | worker session identity value |
| `SocketTransportFrameCodec` result methods | Socket adapter | socket residue/follow-up | socket canonical result reader, not worker-channel reader; does not define WebSocket/runtime boundary |
| `SocketTransportServer` result ingest block | Socket adapter | socket residue/follow-up | duplicated result ingest flow; may adopt shared processor later if beneficial |

## ADC-1 - Extract Worker-Channel Result Reader And Inbound Processor

Scope:

- Add `AdapterResultFrameReader<T>` or equivalent narrow runtime embedded
  contract.
- Move WebSocket `ACTION_REPLY` result frame interpretation into a runtime
  embedded `JsonObject` reader that returns `AdapterResultFrame` facts only:
  correlation ref, opaque payload, trace seed, and frame id.
- Add `AdapterInboundResultProcessor<T>` or equivalent runtime embedded class
  that is called only after the adapter has identified a result frame. It owns:
  - invalid canonical result handling,
  - ingest sink unavailable behavior,
  - ingest rejection behavior,
  - entry construction through `AdapterResultIngressEntries.from(...)`,
  - logging taxonomy for expected invalid input vs unexpected failures.
- Add a small adapter-supplied diagnostics provider function. WebSocket keeps
  current adapter id, route fallback, and trace fallback there; the reader must
  not own diagnostics or entry construction.
- Use only the first-party dependencies from `Initial Convergence Boundary`:
  the runtime-owned action-reply result reader, WebSocket diagnostics provider,
  and existing result ingress sink. Do not introduce extension points for the
  inbound processor flow, session registry, evidence publisher, mailbox
  handoff, adapter lifecycle, carrier shape, outcome construction, or worker
  selection.
- Keep unknown/control/unsupported frame handling in the concrete adapter.
- Replace `WebSocketInputProcessor` and `WebSocketResultIngressFrameReader`
  production usage with the shared reader/processor.
- Delete `WebSocketDispatcherContext` if no longer needed by the WebSocket
  assembly path.
- Update `TransportConvergenceArchitectureGuardTest`,
  `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`, and `doc/PROOF_REGISTRY.md`
  in this same slice so deleting old WebSocket dispatcher/frame classes does
  not break the slice's own proof.

Acceptance:

- WebSocket production main no longer contains `WebSocketInputProcessor`,
  `WebSocketDispatcherContext`, or `WebSocketResultIngressFrameReader`.
- Runtime embedded support contains the only `ACTION_REPLY` result frame reader
  used by WebSocket.
- The runtime embedded action-reply reader does not construct
  `ResultIngressEntry`, `ResultIngressMessage`, or `ResultIngressDiagnostics`.
- WebSocket diagnostics are supplied through a narrow adapter function, not
  hidden inside the result reader.
- `AdapterInboundResultProcessor` is constructed from explicit narrow
  dependencies: result reader, result ingress sink, and diagnostics provider.
  The processor flow is not replaceable by concrete adapters. No replacement
  dispatcher context or service locator exists.
- The implementation has no broad adapter plugin interface or context object
  that can override session registry, endpoint evidence, mailbox handoff,
  adapter lifecycle, carrier construction, dispatch outcome construction, or
  worker selection semantics.
- Concrete adapter production main sources do not call
  `AdapterResultIngressEntries.from(...)` or `new ResultIngressEntry(...)` on
  the shared result path; result-entry construction is centralized in the
  runtime embedded processor.
- Public contract still contains only `WorkerChannelFrame` and
  `WorkerChannelFrameJsonCodec`, not action-reply body readers.
- Result entry construction still goes through `AdapterResultIngressEntries`.
- WebSocket result diagnostics keep current adapter id, route fallback, and
  trace fallback behavior.
- Unknown WebSocket frames still produce the current WebSocket-local warning or
  no-op behavior; the shared processor has no `IGNORED` branch for unknown
  frames.
- Guard, baseline, and proof registry changes land with ADC-1, not in ADC-5.

## ADC-2 - Session Identity Path Pruning

Scope:

- Inventory current production callers for
  `WebSocketSessionOpenFrameReader.readFrame(JsonObject)`.
- If no production caller exists, delete the parsed-frame identity path and
  keep only WebSocket URI/query handshake identity reading.
- Introduce shared parsed-frame identity support only if a real socket,
  polling, or WebSocket production caller needs it.
- Keep WebSocket request URI/query parsing in WebSocket adapter, under a name
  that makes it clear it is handshake/protocol glue.

Acceptance:

- Netty `QueryStringDecoder` remains only in WebSocket adapter.
- Shared runtime support does not import Netty/WebSocket/session classes.
- Session identity value does not grow routeKey, endpoint address,
  deliveryBucketId, adapter id, or mailbox key.
- No shared session-identity abstraction is introduced without a production
  caller outside WebSocket handshake.

## ADC-3 - Remove WebSocket Dispatcher Package Residue

Scope:

- Move or rename `WebSocketTaskDispatchChannel` only if the package name still
  misleads after ADC-1. It may remain WebSocket-owned because it calls
  `WebSocketSessionRegistry`.
- Replace `WebSocketInboundFrameSink` with `Consumer<JsonObject>` or a shared
  processor reference if no owner boundary remains.
- Delete the empty `dispatcher` package if no production class remains.
- Update bootstrap/server wiring to use narrow constructor dependencies rather
  than a dispatcher context.

Acceptance:

- `transport/websocket-adapter/.../dispatcher` is either gone or contains only
  a clearly WebSocket-owned final-hop sender.
- No class in WebSocket adapter reintroduces a broad dispatcher context.
- `WebSocketTaskDispatchChannel`, if retained, does not parse result frames,
  own result ingress, import Netty, or own adapter metadata.

## ADC-4 - Socket Residue Disposition

Scope:

- Keep socket result frame shape independent from WebSocket
  `WorkerChannelFrame(ACTION_REPLY)`. Socket canonical JSON currently carries
  `resultCorrelationRef` directly.
- Decide whether socket remains adapter-local for now or adopts shared result
  processing later. This is residue work, not a prerequisite for ADC-1 through
  ADC-3.
- If socket later adopts shared processing, introduce a socket-specific
  `AdapterResultFrameReader<JsonObject>` or equivalent that converts socket
  canonical result frames to `AdapterResultFrame` facts.
- Any shared processing must run only after socket-local
  hello/heartbeat/result/unsupported classification.
- Keep socket handshake and heartbeat handling in `SocketTransportServer`.
- Keep socket line parsing and socket session manager in socket adapter.

Acceptance:

- Socket does not force WebSocket/runtime support to preserve socket's legacy
  frame shape.
- If socket adopts shared processing in this roadmap, it uses the same
  result-entry normalization sink path as WebSocket where applicable, without
  using the worker-channel ACTION_REPLY reader.
- If socket stays adapter-local, the roadmap records the deferral and why no
  socket-unique requirement changes the WebSocket/runtime mainline.
- Socket control/hello/heartbeat behavior remains unchanged.
- Socket tests are required only when socket production code changes. Otherwise
  optional socket smoke proof may support residue classification but does not
  gate WebSocket completion.

## ADC-5 - Residue Guards And Documentation Hardening

Scope:

- After ADC-1 through ADC-3 land, harden `TransportConvergenceArchitectureGuardTest`
  to prevent:
  - `WebSocketInputProcessor` and `WebSocketDispatcherContext` returning,
  - worker-channel result reader living in WebSocket adapter,
  - public contract owning `replyRef` body parsing,
  - shared runtime embedded support importing WebSocket/Netty classes.
- Update any remaining owner docs that were not already updated by the slice
  that changed production behavior.
- Run residue scans for old dispatcher/frame names and stale test fixtures.

Acceptance:

- Owner docs describe WebSocket as protocol/session glue plus final-hop sender,
  not generic result parsing owner.
- Guards target stable owner invariants, not temporary class names that may be
  legitimately renamed in the same convergence.
- Residue scans find no `WebSocketResultIngressFrameReader`,
  `WebSocketInputProcessor`, or `WebSocketDispatcherContext` in production main
  unless explicitly retained by a documented owner decision.
- Guards do not force socket to use `WorkerChannelFrame(ACTION_REPLY)`; they
  only prevent duplicated result-entry construction and public-contract
  `replyRef` parsing.

## Roadmap Completion Criteria

- Common worker-channel result parsing and inbound result processing are owned
  by transport runtime embedded support where the worker-channel frame shape is
  actually used.
- Built-in processor composition is explicit: adapters provide result reader
  and diagnostics functions; runtime embedded support owns common ingest,
  rejection, and entry construction behavior.
- This roadmap does not establish a supported adapter hook/plugin API. It
  converges the first-party WebSocket path with narrow constructor
  dependencies only. Inbound processor flow, session registry,
  endpoint/session evidence, mailbox handoff, adapter lifecycle, carrier shape,
  `DispatchOutcome`, `ResultIngressEntry`, worker selection, and endpoint lease
  semantics remain owned by their existing mainline owners.
- WebSocket adapter no longer owns shared action-reply result parsing.
- WebSocket adapter no longer hides diagnostics or entry construction inside a
  WebSocket-specific result reader.
- Socket is classified as residue/follow-up. It either adopts shared result
  processing in a later slice, or remains adapter-local with an explicit note
  that no socket-unique requirement changes the WebSocket/runtime mainline.
- WebSocket protocol/session code remains adapter-local and does not leak
  Netty/session types into transport runtime support.
- Public worker contract remains carrier/codec-only and does not parse
  `replyRef` or task-result body semantics.
- Focused tests, architecture guard, and owner docs all agree with the new
  boundary.

## Verification Candidates

Correct test names after ADC-0 inventory if they drift. Mandatory new tests
must be created in the same slice that names them. The focused reactor test
commands use `-Dsurefire.failIfNoSpecifiedTests=false` only because `-am`
builds upstream modules that do not own the named tests; target-module Surefire
reports remain the completion proof.

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime -am -DskipTests test-compile
.\mvnw.cmd -q -pl transport/transport_runtime -am test "-Dtest=AdapterInboundResultProcessorTest,WorkerChannelActionReplyReaderTest,WorkerChannelActionReplyResultFrameReaderTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/websocket-adapter -am -DskipTests test-compile
.\mvnw.cmd -q -pl transport/websocket-adapter -am test "-Dtest=WebSocketFrameReadersTest,WebSocketResultDiagnosticsProviderTest,WebSocketTransportAdapterBootstrapTest,WebSocketTaskDispatchChannelTest,DispatcherInboundHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,sdk/xa-mass-public-contract -am -DskipTests compile
```

Optional socket residue smoke only if socket code changes or ADC-4 is executed:

```powershell
.\mvnw.cmd -q -pl transport/socket-adapter -am -DskipTests test-compile
.\mvnw.cmd -q -pl transport/socket-adapter test "-Dtest=SocketTransportServerTest,SocketTransportFrameCodecTest,SocketTaskDispatchChannelTest" "-DtrimStackTrace=true"
```

Test replacement map:

| Current/Old Test | Target Proof |
| --- | --- |
| `WebSocketInputProcessorTest` | `AdapterInboundResultProcessorTest` for default ingest/reject/failure behavior |
| `WebSocketFrameReadersTest` result-reader cases | runtime `WorkerChannelActionReplyResultFrameReaderTest` plus WebSocket diagnostics provider proof |
| Socket result block assertions | `SocketResultFrameReaderTest` if socket adopts shared processor; otherwise current socket server/frame codec tests remain the proof |

## Open Decisions

- Whether `WebSocketTaskDispatchChannel` should remain under WebSocket adapter
  with a clearer package/name, or become a tiny adapter-local sender injected
  into a shared push-dispatch executor. This must not reintroduce a broad
  dispatcher context.
- Whether socket result ingest should adopt the shared result processor later
  or stay adapter-local after sharing only `AdapterResultIngressEntries`. This
  is a residue decision and must not constrain ADC-1 through ADC-3.
