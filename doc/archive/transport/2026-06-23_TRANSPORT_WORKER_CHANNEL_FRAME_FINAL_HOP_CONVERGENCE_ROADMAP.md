# Transport Worker Channel Frame And Final-Hop Convergence Roadmap

Status: completed and archived on 2026-06-23 after public worker-channel frame
and JSON codec ownership, push final-hop outcome normalization,
result-ingress entry helper, WebSocket dependency-surface cleanup, active
owner-doc updates, focused proof, and residue scan landed.

## Summary

This roadmap tightens adjacent transport/adapter residues while keeping owner
boundaries explicit. It allows the narrow public worker-channel wire contract to
move to `sdk/xa-mass-public-contract`, but it does not create a new Maven module
for worker-channel runtime logic yet.

1. `WorkerChannelFrame` is a worker-channel wire carrier, not a WebSocket-only
   concept. Define the canonical frame and kind vocabulary in
   `sdk/xa-mass-public-contract` so Java SDK worker runtime and transport
   WebSocket adapter code share one wire contract.
2. Java SDK worker-channel processing logic is larger than the frame DTO. Keep
   it inside `sdk/xa-mass-java-sdk` for now, but isolate it by package so a
   future module extraction is mechanical rather than semantic.
3. WebSocket and Socket assigned-delivery executors share the same batch/outcome
   template. Keep protocol-specific final-hop send as an adapter function, and
   move repeated batch/outcome normalization into transport runtime embedded
   support.
4. WebSocket and Socket result ingress share the same
   `ResultIngressEntry/Message/Diagnostics` construction mechanics. Keep
   protocol-specific frame parsing in adapters, and move result-ingress entry
   construction into a small transport runtime helper.
5. WebSocket adapter dependency surface is converged with the same boundary:
   it may depend on transport contracts/support, public worker-channel wire
   DTOs, Gson, Netty, and lifecycle annotations it actually uses, but it must
   not reverse-depend on embedded SDK API, base domain exception models, stale
   Java-WebSocket implementation libraries, or Redis clients.

Do not build a broad `adapter-common` module for this roadmap. The shared facts
split into different owners:

- `sdk/xa-mass-public-contract`: worker-channel wire DTO and kind constants.
- `sdk/xa-mass-java-sdk`: worker runtime action decode/handler/reply logic,
  initially isolated by package rather than by Maven module.
- `transport_runtime` embedded support: adapter final-hop outcome templates and
  result-ingress carrier construction after protocol extraction.
- `transport/websocket-adapter`: WebSocket protocol/session/frame I/O and
  adapter-local parsing only; no SDK/base/bootstrap/domain dependency surface.

## Current Code Observations

- `transport/websocket-adapter/.../WebSocketWorkerChannelFrameCodec` owns:
  - frame fields: `frameId`, `kind`, `body`
  - worker channel kinds: `ACTION`, `ACTION_REPLY`, `EVIDENCE_REPORT`,
    `HEARTBEAT`
  - JSON frame construction and field extraction
- `sdk/xa-mass-java-sdk/.../WorkerChannelFrame` already defines the same
  `frameId/kind/body` shape and the same kind constants for Java worker
  runtime, currently at
  `com.xa.mass.client.worker.WorkerChannelFrame`.
- `sdk/xa-mass-public-contract` is the existing lightweight public DTO module.
  It already depends only on Jackson annotations at main scope and is explicitly
  guarded from depending on transport/runtime modules.
- `WebSocketTaskDispatchChannel` and `SocketTaskDispatchChannel` both implement
  `AdapterCommandExecutor` and duplicate:
  - null item validation
  - per-item loop
  - `DispatchOutcomeFactory.delivered/noEndpoint/failed`
  - catch/log/retryable failure behavior
- The only adapter-specific part in push dispatch is the single final-hop send
  attempt:
  - WebSocket: encode `ACTION` frame and call
    `WebSocketSessionRegistry.sendTextToWorker(selectedWorkerId, frame)`
  - Socket: encode socket dispatch frame and call
    `SocketSessionManager.sendToWorker(selectedWorkerId, frame)`
- `PollingDeliveryExecutor` is different. It owns batch enqueue into the polling
  pending pull buffer. It should not be forced into a per-message send template.
- WebSocket and Socket both construct result ingress entries after
  protocol-local frame parsing:
  - WebSocket: `WebSocketResultIngressFrameReader.toEntry(...)` builds
    `ResultIngressEntry`, `ResultIngressMessage`, and
    `ResultIngressDiagnostics`.
  - Socket: `SocketTransportServer.handleClient(...)` inlines the same
    result-ingress carrier construction for canonical socket task result
    frames.
  - The adapter-specific part is detecting/extracting
    `resultCorrelationRef`, payload, route/correlation diagnostics, and trace
    id from the protocol frame.
- `transport/websocket-adapter/pom.xml` still declares stale or wrong-owner
  dependencies:
  - `xa-mass-embedded-sdk-api`: no current source import and wrong direction;
    adapters should not depend on embedded SDK API.
  - `xa-mass-base`: only used by `WebSocketInputProcessor` for
    `CommandException` / `ValidationException` logging classification.
  - `org.java-websocket:Java-WebSocket`: no current source import; WebSocket
    adapter is Netty-based.
  - `io.lettuce:lettuce-core`: no current source import; Redis client behavior
    does not belong in the concrete WebSocket adapter.
- `jakarta.annotation-api` remains currently used by `WebSocketServerImpl`
  `@PreDestroy`.
- Netty is current WebSocket implementation infrastructure. Dependency
  analyzer may report `netty-all` as unused while reporting concrete Netty
  modules as used undeclared; exact Netty module normalization is allowed only
  if it stays inside this dependency-surface slice and preserves behavior.

## Owner Review

`WorkerChannelFrame` belongs to the public worker-channel wire contract surface
for multiplexed worker protocols. The canonical class belongs in
`sdk/xa-mass-public-contract`, not in `websocket-adapter` and not as a
transport-only mirror in `transport_api`. This is schema and kind vocabulary
ownership only; it is not a generic transport payload carrier for every adapter.

`websocket-adapter` may own WebSocket `TextWebSocketFrame` I/O and JSON parsing
helpers, but it must not be the owner of shared worker channel kind names.
Socket and polling are not forced to use `WorkerChannelFrame`; Socket keeps its
line-delimited protocol frame and polling keeps its pull-buffer exchange.

Java SDK worker-channel business/runtime logic belongs to `sdk/xa-mass-java-sdk`
for now. It should be isolated under an internal worker-channel package before
any future Maven module extraction. Do not move handler dispatch, evidence
reporting, protocol driver lifecycle, or worker runtime state into transport.

`transport_runtime` embedded adapter support owns reusable assigned-delivery
batch/outcome normalization. Concrete adapters own only the final-hop attempt:
session lookup, protocol frame encoding, and protocol send.

`transport_runtime` owns reusable result-ingress carrier construction after an
adapter has parsed a protocol frame into opaque payload plus correlation and
diagnostics facts. Concrete adapters own only protocol frame detection and field
extraction.

`WorkerDispatchProcessor` remains Java SDK worker runtime. Transport and
adapters must not execute worker handlers or import worker runtime handler
types.

Concrete adapter dependencies are part of the same owner boundary. A concrete
adapter may consume `transport_api`, runtime embedded-support capabilities while
it remains embedded Java, the shared public worker-channel DTO, and protocol
libraries it directly uses. It must not depend on `xa-mass-embedded-sdk-api`,
`xa-mass-java-sdk`, `xa-mass-base`, Redis clients, or old protocol libraries
only to reuse bootstrap, exception, storage, or historical implementation
types.

## Boundary Decision

Use one public worker-channel frame carrier:

```java
package com.xa.mass.contract.worker;

public record WorkerChannelFrame(
        String frameId,
        String kind,
        String body
) { }
```

Use shared kind constants in the same public-contract package, either on the
record or in a separate `WorkerChannelFrameKind` class:

```java
ACTION
ACTION_REPLY
EVIDENCE_REPORT
HEARTBEAT
```

`WorkerChannelFrame` is a wire carrier only. It must not grow
`eventCode`, `replyRef`, task ids, handler result fields, endpoint/session
facts, adapter mailbox keys, or worker lifecycle fields.

The old Java SDK local `com.xa.mass.client.worker.WorkerChannelFrame` and the
WebSocket adapter-local kind constants should be replaced by the public-contract
class in the same slice. Do not leave compatibility aliases or two same-shaped
production frame classes inside the repo.

Use a narrow push final-hop function:

```java
@FunctionalInterface
public interface FinalHopDispatchAttempt {
    boolean send(DispatchMessage message);
}
```

Transport runtime may provide:

```java
AdapterCommandExecutor perMessage(String name, FinalHopDispatchAttempt attempt)
```

The template maps:

- `true` -> final-hop accepted / delivered outcome
- `false` -> no endpoint
- exception -> failed retryable
- null item -> invalid

Concrete adapters provide only the function body.

`delivered` here is transport best-effort language: the adapter found a local
session/endpoint and accepted the protocol send attempt. It does not mean the
worker processed the action or that a result will arrive. Missing results remain
engine task-attempt timeout/retry input.

Use a narrow result-ingress entry helper:

```java
public final class AdapterResultIngressEntries {
    public static ResultIngressEntry from(String resultCorrelationRef,
                                          String payload,
                                          Map<String, String> diagnostics);
}
```

The helper owns:

- generated `resultMessageId`
- default created timestamp
- `partitionKey == resultCorrelationRef`
- `ResultIngressMessage` construction
- `ResultIngressDiagnostics` construction
- required correlation/payload validation

Adapters provide only already parsed opaque payload, result correlation, and
diagnostic facts such as adapter id, route key, and trace id.

## Target Shape

```text
engine/starter
  -> DispatchMessage(payload, selectedWorkerId, correlationRef, ...)
  -> adapter mailbox
  -> AdapterCommandExecutor.dispatch(batch)
  -> per-message final-hop template
  -> adapter function
       websocket: public-contract WorkerChannelFrame(ACTION, payload) -> session send
       socket: socket dispatch frame -> session send
       polling: batch enqueue to pull buffer, not per-message template
```

Inbound WebSocket result:

```text
TextWebSocketFrame JSON
  -> public-contract WorkerChannelFrame(kind=ACTION_REPLY, body=<reply json>)
  -> ResultIngressEntry
```

Inbound Socket result:

```text
line-delimited socket JSON
  -> protocol-local result detection and field extraction
  -> AdapterResultIngressEntries.from(resultCorrelationRef, payload, diagnostics)
  -> ResultIngressEntry
```

Worker runtime:

```text
public-contract WorkerChannelFrame(kind=ACTION)
  -> WorkerAction
  -> WorkerDispatchProcessor
  -> WorkerActionResult
  -> public-contract WorkerChannelFrame(kind=ACTION_REPLY)
```

Java SDK worker-channel processing remains in `sdk/xa-mass-java-sdk` for now,
but it should be package-isolated from protocol driver lifecycle and public
runtime facade code before any future module extraction.

## Non-Goals

- Do not create a new Maven module in this roadmap.
- Do not introduce a broad `adapter-common` module.
- Do not move Java SDK worker-channel business/runtime logic into
  `sdk/xa-mass-public-contract`; only the narrow wire DTO and kind constants
  belong there.
- Do not make `websocket-adapter` depend on `xa-mass-java-sdk`.
- Do not make `websocket-adapter` depend on `xa-mass-embedded-sdk-api`.
- Do not keep `websocket-adapter` coupled to `xa-mass-base` only for exception
  taxonomy or logging classification.
- Do not make `transport_api` depend on Java SDK worker runtime classes.
- Do not add a transport-side duplicate `WorkerChannelFrame` in
  `transport_api`.
- Do not introduce `WorkerAction`, `WorkerActionHandler`,
  `WorkerDispatchProcessor`, or handler result types into transport.
- Do not force polling delivery through `WorkerChannelFrame`.
- Do not convert polling batch enqueue into per-message send attempts.
- Do not add adapter lifecycle, health supervision, retry policy, restart, or
  failover behavior.
- Do not change routeKey, adapter mailbox, endpoint lease, or result-ingress
  routing semantics.
- Do not move result payload parsing, task result correctness, attempt/lease
  validation, or handler result semantics into adapters or transport runtime.
- Do not make `AdapterResultIngressEntries` understand WebSocket or Socket
  frame shapes.
- Do not preserve stale Java-WebSocket or Lettuce dependencies in
  `websocket-adapter` when no production source uses them.

## WCF-0 Inventory And Proof Baseline

Scope:

- Inventory current frame and final-hop symbols:
  - `WebSocketWorkerChannelFrameCodec`
  - `WebSocketResultIngressFrameReader`
  - `WebSocketInputProcessor`
  - `WebSocketTaskDispatchChannel`
  - `SocketTaskDispatchChannel`
  - `SocketTransportFrameCodec`
  - `SocketTransportServer`
  - `PollingDeliveryExecutor`
  - Java SDK `com.xa.mass.client.worker.WorkerChannelFrame`
  - Java SDK `WebSocketWorkerProtocolDriver`
  - `sdk/xa-mass-public-contract`
- Separate shared worker-channel schema from protocol-specific JSON parsing and
  session send.
- Separate public worker-channel wire contract from Java SDK worker-channel
  business processing.
- Separate result-ingress carrier construction from protocol-specific result
  frame detection and field extraction.
- Inventory direct and transitive dependency impact before adding
  `sdk/xa-mass-public-contract` to transport adapters.
- Inventory `transport/websocket-adapter/pom.xml` dependencies separately. This
  roadmap adds the direct public-contract dependency needed for
  `WorkerChannelFrame` and removes stale or wrong-owner WebSocket adapter
  dependencies in the same convergence track.
  - Keep: `xa-mass-transport-api`, current embedded-support dependency on
    `xa-mass-transport-runtime`, Gson, Netty implementation dependencies,
    `jakarta.annotation-api` while `@PreDestroy` is used, test dependencies.
  - Remove candidates: `xa-mass-embedded-sdk-api`, `xa-mass-base`,
    `org.java-websocket:Java-WebSocket`, `io.lettuce:lettuce-core`.
  - Classify Netty separately: keep Netty behavior; exact `netty-all` versus
    concrete Netty modules is dependency hygiene, not a license to remove
    Netty.

Acceptance:

- The roadmap names all production call sites that will be changed.
- Polling is explicitly classified as batch enqueue and out of scope for the
  per-message push template.
- Java SDK WebSocket frame decode/encode tests are identified as compatibility
  proof for public-contract frame changes.
- Existing focused test names are classified before execution. Tests that do
  not exist today must be created in the slice that first depends on them, or
  replaced with current focused tests. Do not rely on
  `-Dsurefire.failIfNoSpecifiedTests=false` for mandatory proof.
- Dependency impact is explicit: transport adapters may consume
  `xa-mass-public-contract`, but public-contract must not depend on transport,
  runtime, or Java SDK modules.
- WebSocket adapter dependency cleanup is explicit roadmap scope: SDK API,
  base exception taxonomy, Java-WebSocket, and Lettuce residue are removed or
  classified before the roadmap is complete.
- The first implementation slice can compile after each phase; no break-now,
  fix-later state is required.

Suggested scan:

```bash
rg -n "WorkerChannelFrame|WebSocketWorkerChannelFrameCodec|ACTION_REPLY|EVIDENCE_REPORT|HEARTBEAT|WebSocketTaskDispatchChannel|SocketTaskDispatchChannel|PollingDeliveryExecutor|WebSocketResultIngressFrameReader|SocketTransportServer|ResultIngressEntry" transport sdk/xa-mass-java-sdk sdk/xa-mass-public-contract -g "*.java" --glob "!**/target/**"
```

## WCF-1 Public Worker Channel Frame Contract

Goal:

Move shared worker channel frame shape and kind vocabulary out of
`websocket-adapter` and Java SDK local runtime code into the existing public
contract module.

Scope:

- Add `com.xa.mass.contract.worker.WorkerChannelFrame` under
  `sdk/xa-mass-public-contract`.
- Add shared kind constants on that record or in
  `com.xa.mass.contract.worker.WorkerChannelFrameKind`.
- Add focused tests in `sdk/xa-mass-public-contract` for:
  - required `frameId`
  - required `kind`
  - non-null `body`
  - stable kind names
- Update Java SDK worker WebSocket runtime to use the public-contract frame
  class instead of `com.xa.mass.client.worker.WorkerChannelFrame`.
- Delete the Java SDK local `com.xa.mass.client.worker.WorkerChannelFrame`.
- Add a direct `xa-mass-public-contract` dependency only to modules that consume
  the worker-channel wire DTO. Do not inherit it accidentally through Java SDK
  or transport runtime dependencies.
- Update WebSocket adapter code to reference public-contract frame/kind
  definitions instead of owning constants locally.
- Add or update explicit compatibility proof that:
  - a public-contract `WorkerChannelFrame` JSON action frame is accepted by the
    Java SDK `WebSocketWorkerProtocolDriver`
  - a public-contract `WorkerChannelFrame` action-reply JSON frame is accepted
    by the WebSocket adapter result frame reader
- Update active transport owner docs/proof wording in the same slice to state
  that public-contract owns only shared frame field/kind vocabulary; protocol
  parsing and worker handler semantics remain outside the frame carrier.

Acceptance:

- `WebSocketWorkerChannelFrameCodec` no longer defines shared worker channel
  kind constants.
- WebSocket outbound `ACTION` frame and inbound `ACTION_REPLY` detection use
  public-contract frame/kind definitions.
- The Java SDK local `WorkerChannelFrame` class is removed; callers import the
  public-contract class.
- No production module adds a dependency on `xa-mass-java-sdk`.
- Any new WebSocket adapter dependency on `xa-mass-public-contract` is explicit
  and limited to the worker-channel wire DTO.
- No production module adds `WorkerChannelFrame` duplicate classes outside
  `sdk/xa-mass-public-contract`.
- Java SDK and transport WebSocket frame JSON remain byte-compatible by field
  names and kind constants because they share the same public-contract class.
- Active owner docs do not imply polling or socket must use
  `WorkerChannelFrame`.

Verification:

```bash
./mvnw -q -pl sdk/xa-mass-public-contract -am test "-Dtest=WorkerChannelFrameTest,WorkerChannelFrameJsonCodecTest"
./mvnw -q -pl transport/websocket-adapter,sdk/xa-mass-java-sdk -am test "-Dtest=WebSocketFrameReadersTest,WebSocketInputProcessorTest,WebSocketTaskDispatchChannelTest,WebSocketWorkerProtocolDriverTest,WebSocketWorkerRuntimeTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`WorkerChannelFrameTest` and `WorkerChannelFrameJsonCodecTest` are mandatory
new proof and must not be hidden behind
`-Dsurefire.failIfNoSpecifiedTests=false`.

## WCF-1.5 WebSocket Adapter Dependency Surface Cleanup

Goal:

Converge the WebSocket adapter Maven dependency surface so it matches concrete
adapter ownership after the public worker-channel frame dependency is added.

Scope:

- Remove `xa-mass-embedded-sdk-api` from `transport/websocket-adapter/pom.xml`.
  The WebSocket adapter must not consume embedded SDK API contracts or
  bootstrap surfaces.
- Remove `xa-mass-base` after replacing
  `WebSocketInputProcessor` base exception classification with adapter-local or
  standard exception handling.
  - `IllegalArgumentException` / validation-style failures may be logged as
    rejected adapter input.
  - Unexpected exceptions remain error logs.
  - Do not introduce adapter-local copies of `CommandException`, `ErrorCode`,
    or `ValidationException` just to preserve the old taxonomy.
- Remove `org.java-websocket:Java-WebSocket`; the current WebSocket adapter is
  Netty-based and has no source import for that library.
- Remove `io.lettuce:lettuce-core`; Redis clients are transport/runtime or
  infra concerns, not concrete WebSocket adapter dependencies.
- Keep the current direct dependencies that production code actually needs:
  `xa-mass-transport-api`, `xa-mass-transport-runtime` while embedded support is
  still consumed, Gson, Netty, and `jakarta.annotation-api` while `@PreDestroy`
  remains in `WebSocketServerImpl`.
- If dependency analysis reports `netty-all` as unused while reporting Netty
  modules as used undeclared, either keep the current Netty dependency for this
  slice or replace it with the exact Netty modules in the same slice. Do not
  remove Netty behavior.
- Add or update a guard that fails when `transport/websocket-adapter` main
  sources or POM reintroduce:
  - `com.xa.mass.sdk.*`
  - `com.xa.mass.base.*`
  - `xa-mass-embedded-sdk-api`
  - `xa-mass-base`
  - `Java-WebSocket`
  - `lettuce-core`

Acceptance:

- `transport/websocket-adapter` main sources do not import
  `com.xa.mass.sdk.*` or `com.xa.mass.base.*`.
- `transport/websocket-adapter/pom.xml` does not declare
  `xa-mass-embedded-sdk-api`, `xa-mass-base`, `Java-WebSocket`, or
  `lettuce-core`.
- WebSocket result input behavior remains equivalent except that base-specific
  exception taxonomy no longer drives logging.
- Dependency cleanup does not create a new wrapper, compatibility alias, or
  adapter-local copy of base exception classes.
- Guard coverage is stable owner coverage, not a temporary class-name lock.

Verification:

```bash
./mvnw -q -pl transport/websocket-adapter -am test "-Dtest=WebSocketInputProcessorTest,WebSocketFrameReadersTest,WebSocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest"
./mvnw -q -pl transport/websocket-adapter -am -DskipTests compile
./mvnw -pl transport/websocket-adapter dependency:analyze -DskipTests
```

The `dependency:analyze` output is supporting evidence. It may still report
Netty module precision work separately; it must not report the removed
SDK/base/Java-WebSocket/Lettuce dependencies as unused declared dependencies.

## WCF-2 Public Frame JSON Codec Extraction

Goal:

Move shared `WorkerChannelFrame` string JSON encoding/decoding into
`sdk/xa-mass-public-contract`. WebSocket owns only WebSocket/`JsonObject` glue,
not the shared frame JSON codec.

Scope:

- Replace the WebSocket-local frame JSON codec with
  `com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec`.
- The public codec consumes/produces the public-contract
  `WorkerChannelFrame`:
  - `encode(WorkerChannelFrame)`
  - `decode(String)`
  - `encodeAction(payload)` / generated frame helpers
- WebSocket may convert its protocol-local `JsonObject` to a string before
  decoding; that is adapter glue, not shared schema ownership.
- `WebSocketResultIngressFrameReader` should read the decoded frame shape
  before parsing `ACTION_REPLY` body.
- Keep Java SDK compatibility proof in this slice because Java SDK and
  WebSocket adapter both consume the shared public-contract frame JSON codec.

Acceptance:

- Frame field names, kind constants, and string JSON encoding/decoding have one
  public-contract owner.
- WebSocket-specific code owns only WebSocket inbound filtering, protocol-local
  `JsonObject` handling, and diagnostics/trace fallback.
- `WebSocketWorkerChannelFrameJsonCodec` does not exist as a production owner.
- `WebSocketResultIngressFrameReader` does not read task/handler fields from
  the outer channel frame.
- Java SDK WebSocket runtime still decodes transport WebSocket `ACTION` frames
  and encodes `ACTION_REPLY` frames that the adapter accepts.

Verification:

```bash
./mvnw -q -pl transport/websocket-adapter,sdk/xa-mass-java-sdk -am test "-Dtest=WebSocketFrameReadersTest,WebSocketInputProcessorTest,WebSocketTaskDispatchChannelTest,DispatcherInboundHandlerTest,WebSocketWorkerProtocolDriverTest,WebSocketWorkerRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## WCF-3 Push Final-Hop Executor Template

Goal:

Delete duplicated push adapter batch/outcome logic while keeping final-hop send
adapter-owned.

Scope:

- Add `FinalHopDispatchAttempt` under transport runtime embedded support.
- Add a small factory/helper such as `AdapterCommandExecutors.perMessage(...)`
  that returns `AdapterCommandExecutor`.
- The helper owns:
  - empty batch -> empty outcome list
  - null item -> invalid outcome
  - successful local send attempt -> final-hop accepted / delivered outcome
  - false attempt -> no-endpoint outcome
  - runtime exception -> failed retryable outcome
  - immutable outcome list
- Replace WebSocket and Socket duplicated `dispatch(List<DispatchMessage>)`
  loops with adapter-local final-hop lambdas or thin factory methods.
- Delete `WebSocketTaskDispatchChannel` and `SocketTaskDispatchChannel` if they
  become pass-through wrappers. If keeping either class materially improves
  adapter assembly readability, it must contain only protocol send construction
  and no duplicated outcome template.
- Update `TransportConvergenceArchitectureGuardTest` in this same slice before
  deleting or thinning those classes. Current guards read the concrete channel
  files directly, so WCF-3 must retarget them to the replacement owner or keep
  the classes as final-hop wiring owners.
- If either class is deleted or reduced to a wiring shell, update
  `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` and
  `doc/PROOF_REGISTRY.md` in the same slice. Do not leave active docs or proof
  rows saying the old class owns command execution.

Acceptance:

- WebSocket and Socket do not duplicate the batch/outcome loop.
- The only push-adapter-specific code is final-hop frame encoding and
  selected-worker session send.
- A `delivered` outcome from the shared template means local final-hop send was
  accepted by the adapter session/endpoint, not that worker processing or result
  delivery is guaranteed.
- Polling remains batch enqueue and does not use the per-message template.
- `AdapterCommandExecutor` remains the batch SPI consumed by embedded adapter
  mailbox consumers.
- Active docs and proof registry name the replacement proof if
  `WebSocketTaskDispatchChannelTest` or `SocketTaskDispatchChannelTest` are
  deleted.
- `TransportConvergenceArchitectureGuardTest` does not require deleted
  pass-through classes to exist. It guards the stable invariant instead:
  WebSocket/Socket final-hop protocol send remains adapter-owned, while
  repeated batch/outcome normalization is owned by transport runtime embedded
  support.

Verification:

```bash
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=AdapterCommandExecutorsTest"
./mvnw -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test "-Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingDeliveryExecutorTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

If `WebSocketTaskDispatchChannelTest` or `SocketTaskDispatchChannelTest` are
deleted with their classes, remove them from the second command in the same
slice and replace them with tests that prove the concrete adapter final-hop
lambda wiring and the shared template outcomes.
`AdapterCommandExecutorsTest` is mandatory new proof and must not be hidden
behind `-Dsurefire.failIfNoSpecifiedTests=false`.

## WCF-3.5 Java SDK Worker-Channel Package Isolation

Goal:

Make future worker-channel runtime extraction possible without creating a new
Maven module now.

Scope:

- Keep worker runtime implementation inside `sdk/xa-mass-java-sdk`.
- Create or converge to an internal package boundary for worker-channel logic,
  for example:

```text
com.xa.mass.client.worker.channel
```

- Move only package-local worker-channel mechanics into that package:
  - action body decode from `WorkerChannelFrame`
  - action reply encode to `WorkerChannelFrame`
  - frame kind handling
  - small codec helpers that have no runtime lifecycle dependency
- Keep these outside the package for now:
  - `WebSocketWorkerRuntime`
  - `PollingWorkerRuntime`
  - protocol driver lifecycle/connect loops
  - handler registry / handler execution owner
  - evidence reporter and command/evidence publisher lifecycle
- Do not create `xa-mass-worker-channel-runtime` in this roadmap.

Acceptance:

- Java SDK worker-channel frame/action codec logic is package-isolated from
  worker runtime lifecycle classes.
- The isolated package depends only on public-contract DTOs and Java/Jackson
  codec utilities, not on transport modules or runtime lifecycle classes.
- Public Java SDK behavior remains unchanged.

Verification:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test "-Dtest=WebSocketWorkerProtocolDriverTest,WebSocketWorkerRuntimeTest,PollingWorkerRuntimeTest,WorkerDispatchProcessorTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

If package isolation introduces a new worker-channel codec class, add a focused
test for that class in this slice and include it without
`-Dsurefire.failIfNoSpecifiedTests=false`.

## WCF-4 Result Ingress Entry Helper

Goal:

Delete duplicated result-ingress carrier construction while keeping protocol
frame parsing adapter-owned.

Scope:

- Add a small runtime helper such as `AdapterResultIngressEntries`.
- Replace inline WebSocket and Socket result ingress entry construction after
  protocol extraction.
- WebSocket keeps `WebSocketResultIngressFrameReader` as the
  WebSocket-frame-to-result-facts reader.
- Socket keeps `SocketTransportFrameCodec` and `SocketTransportServer`
  protocol loops. The server should call the shared helper after extracting
  `resultCorrelationRef`, payload, and diagnostics.
- Diagnostics remain opaque string facts. The helper may copy a diagnostics map,
  but it must not infer route, adapter, trace, task, or worker semantics.
- The helper owns `deadlineEpochMillis=0L` and generated created timestamp for
  the transport result-ingress message. Adapters remain responsible for
  protocol-specific trace fallback before passing diagnostics to the helper:
  WebSocket keeps `traceId -> frameId -> replyRef`; Socket keeps
  `traceId -> resultCorrelationRef`.
- If `doc/PROOF_REGISTRY.md` or active transport docs currently name
  WebSocket/Socket inline `ResultIngressEntry` construction as proof, update
  them in this slice when the helper becomes the owner.

Acceptance:

- WebSocket and Socket no longer duplicate `new ResultIngressEntry(...)` plus
  nested `ResultIngressMessage` / `ResultIngressDiagnostics` construction.
- WebSocket and Socket still own their protocol frame detection and extraction.
- The helper validates required correlation and payload but does not parse the
  result payload.
- `AdapterResultIngressEntriesTest` proves result message id generation,
  `deadlineEpochMillis=0L`, created timestamp defaulting, partition key
  equality, required correlation/payload validation, and diagnostics copying.
- WebSocket and Socket tests prove their existing trace diagnostic fallback
  behavior is preserved after helper adoption.
- Result correctness still belongs to starter/engine result ingress, not
  adapters or transport runtime helper code.
- Active docs/proof registry do not describe WebSocket or Socket inline
  `ResultIngressEntry` construction as the current owner after the helper lands.

Verification:

```bash
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=AdapterResultIngressEntriesTest"
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter -am test "-Dtest=WebSocketFrameReadersTest,WebSocketInputProcessorTest,SocketTransportServerTest,SocketTransportFrameCodecTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`AdapterResultIngressEntriesTest` is mandatory new proof and must not be hidden
behind `-Dsurefire.failIfNoSpecifiedTests=false`.

## WCF-5 Guards And Docs

Goal:

Prevent the old owner split from returning.

Scope:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`.
- Update `doc/PROOF_REGISTRY.md` if proof rows mention
  `WebSocketTaskDispatchChannel` or WebSocket-owned worker frame schema.
- Add or update architecture guards:
  - `websocket-adapter` must not define `ACTION`, `ACTION_REPLY`,
    `EVIDENCE_REPORT`, or `HEARTBEAT` as worker-channel owner constants.
  - Production sources must not define another `WorkerChannelFrame` outside
    `sdk/xa-mass-public-contract`.
  - Transport modules must not import `com.xa.mass.client.worker.WorkerChannelFrame`.
  - `transport` main sources must not import Java SDK worker runtime handler
    classes.
  - push adapters must not duplicate direct `DispatchOutcomeFactory` outcome
    loops when the shared template is available.
  - concrete adapters must not duplicate result-ingress carrier construction
    when the shared helper is available.
  - `transport/websocket-adapter` must not import embedded SDK or base packages
    and must not declare SDK/base/Java-WebSocket/Lettuce dependencies.

Most owner-doc changes should land in the slice that changes the owner:

- WCF-1 updates docs/proof for public-contract frame schema and kind ownership.
- WCF-1.5 updates dependency-surface guards for WebSocket adapter owner
  boundaries.
- WCF-3.5 updates Java SDK docs/guards if package isolation changes public
  implementation layout.
- WCF-3 updates docs/proof if push dispatch channel classes are deleted or
  reduced to wiring.
- WCF-4 updates docs/proof for result-ingress entry construction helper
  ownership.

WCF-5 is the residue/guard hardening slice, not a place to defer current-truth
doc updates that are required for earlier slices.

WCF-3 must update guards in the same slice if dispatch channel classes are
deleted or reduced to wiring shells; WCF-5 may harden those guards further, but
it must not be the first place where broken concrete-file guard references are
fixed.

Acceptance:

- Active docs describe `WorkerChannelFrame` as public-contract worker-channel
  carrier and WebSocket codec as JSON/protocol-local I/O.
- Active docs do not claim `WebSocketTaskDispatchChannel` is required if the
  class was removed.
- Active docs describe result-ingress entry construction as transport runtime
  carrier mechanics after adapter protocol extraction.
- Guards fail if WebSocket owns shared channel kind vocabulary again.
- Guards fail if Java SDK or transport reintroduces a second production
  `WorkerChannelFrame`.

Verification:

```bash
rg -n "WebSocketWorkerChannelFrameCodec|ACTION_REPLY|EVIDENCE_REPORT|HEARTBEAT|WebSocketTaskDispatchChannel|SocketTaskDispatchChannel|new ResultIngressEntry" transport doc/PROOF_REGISTRY.md -g "*.java" -g "*.md" --glob "!**/target/**"
rg -n "class WorkerChannelFrame|record WorkerChannelFrame|com\\.xa\\.mass\\.client\\.worker\\.WorkerChannelFrame|com\\.xa\\.mass\\.transport\\.channel\\.WorkerChannelFrame" sdk transport --glob "*.java" --glob "!**/target/**"
rg -n "com\\.xa\\.mass\\.sdk|com\\.xa\\.mass\\.base|xa-mass-embedded-sdk-api|xa-mass-base|Java-WebSocket|lettuce-core" transport/websocket-adapter/src/main/java transport/websocket-adapter/pom.xml --glob "*.java" --glob "pom.xml" --glob "!**/target/**"
./mvnw -q -pl sdk/xa-mass-public-contract -am test "-Dtest=WorkerChannelFrameTest,WorkerChannelFrameJsonCodecTest"
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=AdapterCommandExecutorsTest,AdapterResultIngressEntriesTest"
./mvnw -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-java-sdk -am test "-Dtest=TransportConvergenceArchitectureGuardTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest,SocketTransportServerTest,WebSocketWorkerProtocolDriverTest,WebSocketWorkerRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

The first two commands are mandatory proof commands for newly introduced test
classes and must not be run with `-Dsurefire.failIfNoSpecifiedTests=false`.
For the final representative command, WCF-0 must confirm every named existing
test is present or replace stale names before implementation.

Treat the `rg` command as a classification scan. Hits in the new
public-contract frame owner or in replacement tests are expected; hits showing
WebSocket-owned kind constants, stale active docs, duplicate production
`WorkerChannelFrame` classes, or duplicated construction loops are residue.

## Do Not Start With

Do not start by creating `xa-mass-worker-channel-runtime`,
`transport-adapter-common`, or another broad shared module. The current
problem is owner and package isolation, not Maven module count.

The first useful convergence is smaller:

1. move only the worker-channel frame DTO/kinds into public-contract;
2. make WebSocket stop owning shared kind/schema vocabulary;
3. isolate Java SDK worker-channel codec logic by package, not by new module;
4. reuse one push final-hop outcome template for WebSocket and Socket;
5. reuse one result-ingress entry construction helper after adapter protocol
   extraction.

## Roadmap Completion Criteria

This roadmap is complete when:

- `sdk/xa-mass-public-contract` owns the canonical `WorkerChannelFrame` shape
  and shared kind names.
- WebSocket adapter no longer owns shared worker-channel schema constants.
- WebSocket result ingress and outbound dispatch use the same public-contract
  frame shape as Java SDK WebSocket runtime.
- Java SDK local `com.xa.mass.client.worker.WorkerChannelFrame` is removed, with
  no in-repo compatibility alias.
- Java SDK worker-channel codec logic is isolated by package enough that a
  future module extraction can be evaluated without dragging runtime lifecycle
  dependencies.
- WebSocket and Socket push adapter dispatch no longer duplicate
  batch/outcome normalization.
- Push final-hop outcomes document and prove best-effort semantics:
  `delivered` means local adapter send accepted, not worker processing or result
  delivery.
- WebSocket and Socket result ingress no longer duplicate
  `ResultIngressEntry/Message/Diagnostics` construction after protocol parsing.
- WebSocket adapter POM no longer declares `xa-mass-embedded-sdk-api`,
  `xa-mass-base`, `Java-WebSocket`, or `lettuce-core`; main sources no longer
  import embedded SDK or base packages.
- Polling delivery remains batch enqueue and is not forced into a per-message
  send abstraction.
- Active transport docs and proof registry match the new owner boundary.
- Focused tests and guards listed above pass. Mandatory new proof tests
  `WorkerChannelFrameTest`, `WorkerChannelFrameJsonCodecTest`,
  `AdapterCommandExecutorsTest`, and `AdapterResultIngressEntriesTest` exist
  and are run without
  `-Dsurefire.failIfNoSpecifiedTests=false`.
- Residue scan finds no production references to old WebSocket-owned frame
  constants, duplicate `WorkerChannelFrame` classes, or deleted pass-through
  task dispatch channel classes.

## Later Decision

After this roadmap lands, decide whether the isolated Java SDK
worker-channel package is small enough to extract into a dedicated
worker-channel runtime/support Maven module.

That future decision should be based on actual dependency shape after package
isolation, not on the current mixed runtime implementation.
