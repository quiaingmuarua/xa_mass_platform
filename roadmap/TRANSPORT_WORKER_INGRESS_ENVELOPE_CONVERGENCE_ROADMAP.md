# Transport Worker Ingress Envelope Convergence Roadmap

Status: proposed direction document.

## Summary

Create one adapter-neutral worker ingress envelope for worker-to-platform
messages:

```java
public record WorkerIngressSource(
        String workerId,
        String ingressSessionRef
) {}

public record WorkerIngress(
        WorkerIngressSource source,
        String ingressCode,
        String correlationRef,
        String payload,
        Map<String, String> diagnostics
) {}
```

The goal is to stop each adapter from owning a special inbound message model
and stop transport ingress from understanding task-result business fields.
WebSocket, socket, polling, and future adapter implementations should all
translate their wire/request shape into the same worker ingress envelope, then
route by `ingressCode`. `ingressCode` stays coarse. For task-result ingress,
the code is `task.result`; success, failure, result code, and result body stay
inside the task-result payload owned by starter/engine result handling.

This roadmap converges the worker ingress boundary only. It does not change
task result runtime finality, worker selection, delivery queue ownership, or
worker lifecycle truth.

## Current Code Observations

- WebSocket inbound currently uses an adapter-specific
  `WebSocketInboundMessage` carrying `rawJson`, `workerId`, `endpointId`, and
  an optional parsed `JsonObject`.
- `DispatcherInboundHandler` creates `WebSocketInboundMessage` from the Netty
  frame plus channel-bound worker identity.
- `WebSocketInputProcessor` consumes `WebSocketInboundMessage`, recognizes
  canonical task-result frames, and submits `TransportResultIngressEnvelope`.
- `WebSocketResultIngressFrameReader` recognizes result frames by
  `resultCorrelationRef` plus `success`, and directly creates
  `TransportResultIngressEnvelope`.
- Socket inbound handles canonical task-result frames directly in
  `SocketTransportServer`, using `SocketTransportFrameCodec` to recognize and
  encode the result payload before submitting `TransportResultIngressEnvelope`.
- Embedded pull workers and external worker HTTP submit result through
  `WorkerResultSubmission` / `WorkerResultSubmissionRequest`, then map directly
  to `TransportResultIngressEnvelope`.
- `TransportResultIngressEnvelope` is already opaque at the transport queue
  layer: it stores payload, correlation, partition key, diagnostics, and
  receive time without parsing task-result correctness.

## Owner Review

`WorkerIngress` belongs to the transport adapter-neutral ingress boundary.
Concrete adapters may create it from their wire/session context, but must not
define adapter-specific successor shapes for the same semantic role.

Task-result payload semantics belong to the result-ingress consumer, not to
WebSocket, socket, polling, or the generic worker ingress envelope.

Task-result success/failure is not a transport routing classification.
`ingressCode` should route only to the task-result ingress owner with the
coarse code `task.result`. Detailed success, failure, error code, retry
classification, and compensation decisions belong to the starter/engine
result handler after it decodes the task-result payload. They must not become
adapter parsing policy, generic `WorkerIngress` fields, or `ingressCode`
subtypes.

The Java in-process model should live in `transport_api` unless dependency
inventory proves a better owner. Public HTTP or cross-language clients should
follow the same wire shape without forcing a dependency on transport runtime
modules.

## Boundary Decision

Unify worker-to-platform messages as a single envelope:

```text
adapter wire/request
  -> adapter-local parser
  -> WorkerIngress
  -> ingress-code handler
  -> existing domain-specific sink
```

For the current task-result path:

```text
WorkerIngress(ingressCode="task.result",
              correlationRef,
              payload)
  -> starter-owned task-result ingress bridge
  -> TransportResultIngressEnvelope
  -> existing result inbox / starter / engine convergence
```

The unified model is the envelope, not the payload schema. `payload` remains an
opaque string. A handler that owns a specific `ingressCode` may parse it after
the envelope has crossed the adapter-neutral boundary.

For `task.result`, `payload` remains the starter-owned result callback payload
shape. In the current code, that means the opaque JSON payload still contains
`resultCorrelationRef`, `success`, and optional `resultCode` / `result` so
`TaskResultCallbackCodec` can decode it later. The generic transport ingress
path may copy this payload; it must not rebuild or reinterpret the task-result
JSON.

Current result inbox compatibility rule:

```text
starter-owned task-result bridge decodes payload.resultCorrelationRef
decoded payload.resultCorrelationRef -> TransportResultIngressEnvelope.partitionKey
if WorkerIngress.correlationRef is present, it must equal decoded payload.resultCorrelationRef
TransportResultIngressEnvelope.correlation -> keep existing JSON correlation semantics / null
```

Do not write plain `correlationRef` into
`TransportResultIngressEnvelope.correlation` unless the same slice changes
`TaskResultCallbackCodec` decode semantics. Today that field is decoded as an
optional JSON correlation record, while `resultCorrelationRef` is decoded from
the result payload. Do not let `WorkerIngress.correlationRef` and payload
`resultCorrelationRef` become two independent truths.

## Target Field Rules

`WorkerIngressSource.workerId`

- Required.
- Filled from adapter session/path/principal context.
- Must not be trusted from worker payload if a stronger session/path owner
  exists.
- Not a worker selection fact.

`WorkerIngressSource.ingressSessionRef`

- Optional opaque diagnostic/session reference.
- WebSocket/socket may use channel/session id.
- Polling/HTTP may use request/session token or leave it blank.
- Must not become a delivery key, lifecycle truth, or result correlation key.

`WorkerIngress.ingressCode`

- Required string.
- Not an enum in the core model.
- Known initial task-result code: `task.result`.
- Future codes such as `worker.signal` or `worker.command.ack` must define
  their own payload consumer.
- Must not reuse task item `eventCode`.
- Must not encode task-result success, failure, retry category, result code, or
  user-defined business detail. Those facts belong inside the owner-specific
  payload or downstream domain command.

`WorkerIngress.correlationRef`

- Optional opaque correlation.
- Required by the task-result handler.
- Must not be expanded into `taskId`, `messageId`, `attemptId`, or
  `attemptNo`.

`WorkerIngress.payload`

- Opaque string.
- Transport and adapters must not parse it for business correctness.
- Empty payload may be allowed by a specific `ingressCode`; `task.result`
  should require nonblank payload.
- For `task.result`, payload is the encoded result callback payload, not a
  generic success-output or failure-detail field.

`WorkerIngress.diagnostics`

- Optional string map.
- May carry adapter/source diagnostics such as protocol label, trace id,
  adapter-local binding label, legacy route address, or ingress session
  details.
- Must not participate in correctness, routing, lifecycle, retry policy, or
  worker selection.
- Must not be the only source of any fact required to handle an ingress code.

`success` / `resultCode` handling

- Do not add `success`, `resultCode`, or `result` to `WorkerIngress`.
- Existing result-shaped wire/API requests are boundary conveniences and must
  be converted to `ingressCode="task.result" + correlationRef + payload`.
- `success`, `resultCode`, and `result` stay inside the task-result payload or
  downstream task-result command.
- Retry and compensation decisions are engine-owned and happen after the
  task-result owner decodes payload. Transport must not infer retry behavior
  from `ingressCode`.

Forbidden fields on `WorkerIngress` and `WorkerIngressSource`:

- `taskId`
- `messageId`
- `attemptId`
- `attemptNo`
- `eventCode`
- `routeKey`
- `adapterId`
- `connectionId`
- `deliveryBucketId`
- `deliveryQueueKey`

## Target Components

Suggested Java types:

- `com.xa.mass.transport.ingress.WorkerIngressSource`
- `com.xa.mass.transport.ingress.WorkerIngress`
- `com.xa.mass.transport.ingress.WorkerIngressChannel`
- `com.xa.mass.transport.ingress.WorkerIngressHandler`
- `com.xa.mass.transport.ingress.WorkerIngressRouter`
- starter/assembly-owned `TaskResultWorkerIngressBridge` near
  `TaskResultCallbackCodec` / `RuntimeTaskResultIngestChannel`

The exact package can be adjusted during inventory, but the types must not
live under `websocket`, `socket`, `polling`, or SDK worker-session packages.
The task-result bridge is the exception to the transport package rule because
it is a starter/assembly owner, not an adapter-local model.

`WorkerIngressChannel` should be a narrow adapter-facing sink:

```java
@FunctionalInterface
public interface WorkerIngressChannel {
    boolean ingest(WorkerIngress ingress);
}
```

`WorkerIngressHandler` should route by code, not by adapter type:

```java
public interface WorkerIngressHandler {
    Set<String> ingressCodes();

    boolean handle(WorkerIngress ingress);
}
```

The first production handler owns `task.result`, lives in starter/assembly,
and maps it to the existing `TransportResultIngressChannel` without rebuilding
the task-result payload schema in transport runtime.

Adapter bootstrap target:

- `TransportAdapterBootstrapContext` exposes `WorkerIngressChannel`.
- Push adapters consume `WorkerIngressChannel`, not
  `TransportResultIngressChannel`, after their slice lands.
- `TransportResultIngressChannel` becomes a dependency of the task-result
  worker ingress handler / bridge.
- Any remaining `getResultIngressChannel()` exposure is a temporary migration
  point and must be removed or explicitly allowlisted by WIE-6.

## Non-Goals

- Do not replace `TransportResultIngressEnvelope` in the first slice. It is
  still the durable result inbox carrier.
- Do not move task-result finality, retry, attempt validation, or stable result
  truth into transport.
- Do not introduce adapter-specific neutral wrappers such as
  `WebSocketWorkerIngress` or `SocketWorkerIngress`.
- Do not make `ingressCode` an enum in the core model.
- Do not add `success`, `resultCode`, or `result` to the generic worker
  ingress envelope. Coarse task-result outcome is expressed by bounded
  `ingressCode` values.
- Do not use `WorkerIngress` for assigned delivery. It is worker-to-platform
  ingress only.
- Do not keep old and new WebSocket inbound paths alive after all in-repo
  callers move.

## Do Not Start With

Do not start by deleting `TransportResultIngressEnvelope` or changing engine
result convergence. The first safe slice is to add the neutral worker ingress
contract and bridge `task.result` into the existing result ingress channel
from the starter/assembly owner.

Do not start by only renaming `WebSocketInboundMessage`. The problem is not the
class name; it is that adapter-specific inbound envelopes and result-specific
fields leak into the generic ingress path.

## WIE-0 Inventory And Dependency Classification

Scope:

- Inventory all worker-to-platform ingress producers and consumers:
  - WebSocket inbound frame path
  - Socket inbound frame path
  - Embedded pull worker result submit
  - External worker HTTP result submit
  - Java SDK WebSocket result frame encoding
  - Java SDK polling result submit
- Classify each current model:
  - adapter-local wire frame
  - public worker API request
  - worker runtime convenience model
  - transport-neutral ingress envelope
  - result-inbox carrier
  - engine result callback command

Acceptance:

- A sibling inventory document exists or this roadmap gains a current-code
  inventory table.
- The inventory separates production callers from tests.
- The inventory records whether `transport_api`, `xa-mass-public-contract`, or
  SDK/server API DTOs need separate Java classes because of dependency rules.
- No implementation change is required in WIE-0.

## WIE-1 Neutral Contract Slice

Goal:

Add the worker ingress envelope contract and route `task.result` through a
starter-owned bridge into the existing result ingress sink.

Scope:

- Add `WorkerIngressSource`.
- Add `WorkerIngress`.
- Add `diagnostics` to `WorkerIngress` as a diagnostic-only string map.
- Add a mandatory narrow `WorkerIngressChannel` for adapter bootstrap.
- Add a handler/router only if the inventory shows more than one immediate
  consumer.
- Add a starter/assembly-owned task-result bridge from
  `WorkerIngress(ingressCode="task.result")` to
  `TransportResultIngressEnvelope`.
- Keep `TransportResultIngressEnvelope` as the existing queue/durable inbox
  payload.
- Change `TransportAdapterBootstrapContext` target shape so adapters can obtain
  `WorkerIngressChannel`.

Acceptance:

- `WorkerIngress` has only `source`, `ingressCode`, `correlationRef`,
  `payload`, and `diagnostics`.
- `WorkerIngressSource` has only `workerId` and `ingressSessionRef`.
- `task.result` bridge requires nonblank `payload`.
- `task.result` bridge decodes the canonical result correlation from the
  starter-owned payload before writing `TransportResultIngressEnvelope`.
- If `WorkerIngress.correlationRef` is present, it must equal the decoded
  payload `resultCorrelationRef`; mismatch is rejected before enqueueing to the
  result inbox.
- Existing result-shaped wire/API inputs map to `ingressCode="task.result"`;
  `success`, `resultCode`, and `result` remain inside payload.
- Result envelope `partitionKey` uses the decoded payload `resultCorrelationRef`.
- Result envelope `correlation` remains `null` or preserves the existing JSON
  correlation record semantics; plain `correlationRef` must not be written into
  `correlation`.
- The task-result bridge copies the opaque task-result payload into
  `TransportResultIngressEnvelope`; it does not reconstruct callback JSON from
  generic worker-ingress fields.
- Source facts may be copied only into diagnostics, not business payload.
- Adapter bootstrap exposes `WorkerIngressChannel` so WIE-2/WIE-3 do not
  create a second direct result-sink path.
- WIE-1 does not add `success`, `resultCode`, or `result` fields to
  `WorkerIngress`.

## WIE-2 WebSocket Ingress Slice

Goal:

Remove the WebSocket-specific inbound envelope from the result mainline.

Scope:

- Replace `WebSocketInboundMessage` / `WebSocketInboundMessageSink` with a
  WebSocket frame reader that produces `WorkerIngress`.
- `DispatcherInboundHandler` should attach `WorkerIngressSource` from
  channel-bound worker identity and a channel/session diagnostic ref.
- `WebSocketInputProcessor` should accept `WorkerIngress` or a
  `WorkerIngressChannel`, not a WebSocket-specific message model.
- Update Java SDK WebSocket worker result frame shape in the same slice:
  - preferred target is
    `ingressCode/correlationRef/payload/diagnostics`, using
    `ingressCode="task.result"`;
  - if old `resultCorrelationRef/success/resultCode/result` frames are kept,
    the adapter reader must convert the whole result-shaped content into the
    starter-owned task-result payload and mark that path as a temporary legacy
    bridge with a WIE-6 removal guard.

Acceptance:

- WebSocket adapter main sources no longer contain `WebSocketInboundMessage`
  or `WebSocketInboundMessageSink`.
- WebSocket adapter does not create `TransportResultIngressEnvelope` directly
  from adapter-specific message objects; it goes through `WorkerIngress` and
  the task-result handler.
- WebSocket parser may recognize frame shape and extract `ingressCode`,
  `correlationRef`, `payload`, and diagnostics. It may translate legacy
  `success/resultCode/result` frames into `ingressCode="task.result"` plus the
  encoded task-result payload, but must not preserve `success` or `resultCode`
  as generic ingress fields.
- WIE-2 explicitly chooses whether to break the old WebSocket result frame or
  keep a temporary bridge. It must not leave this as "if required".
- Existing WebSocket result tests are rewritten around `WorkerIngress`.

## WIE-3 Socket Ingress Slice

Goal:

Move socket result ingress from socket-specific direct envelope construction to
the neutral worker ingress path.

Scope:

- Refactor `SocketTransportServer` and `SocketTransportFrameCodec` so
  canonical worker-to-platform frames map to `WorkerIngress`.
- Feed `WorkerIngress` into the same `WorkerIngressChannel` used by WebSocket.
- Keep socket protocol framing local to socket adapter.

Acceptance:

- Socket adapter no longer directly constructs `TransportResultIngressEnvelope`
  from socket frame code.
- Socket adapter does not preserve `success`, `resultCode`, or `result` as
  generic ingress fields. It may translate legacy/current result-shaped frames
  into `ingressCode="task.result"` plus encoded task-result payload.
- Socket tests prove `task.result` reaches the existing result ingress channel
  through `WorkerIngress`.

## WIE-4 Polling And HTTP Worker API Slice

Goal:

Bring polling/HTTP result submit into the same worker ingress envelope without
forcing adapters to understand result schema.

Scope:

- Embedded pull worker result submit maps to `WorkerIngress`.
- External worker HTTP submit maps to `WorkerIngress`.
- Decide whether public HTTP request shape changes to generic
  `ingressCode/correlationRef/payload` in this slice or remains a public
  worker-result API that is immediately converted at the boundary.
- If public API keeps a result-shaped request for ergonomics, document it as
  public API convenience, not transport ingress truth; the boundary conversion
  must map it to `ingressCode="task.result"` and keep result fields inside
  payload.

Acceptance:

- `EmbeddedPullWorkerSession` no longer directly creates
  `TransportResultIngressEnvelope`.
- `ExternalWorkerApiController` no longer treats result-shaped request data as
  transport ingress truth; it converts to `WorkerIngress` before transport.
- No polling/HTTP code adds `taskId`, `messageId`, `attemptId`, or transport
  route facts to `WorkerIngress`.

## WIE-5 Worker-Initiated Reports Slice

Goal:

Use the same envelope for non-result worker-originated reports without
inventing another model.

Scope:

- Add at least one non-result test ingress code such as `worker.signal` or
  `worker.command.ack`.
- Prove unknown or unhandled `ingressCode` behavior is explicit:
  accepted-noop, rejected, or routed to diagnostics.
- Do not use `worker.heartbeat` as the proof code in this roadmap; heartbeat
  and session presence remain owned by `WorkerPresenceIngress`.
- Do not make this a worker lifecycle truth rewrite.

Acceptance:

- Non-result worker ingress uses `WorkerIngress`.
- `payload` remains opaque to generic transport.
- Unknown code handling is deterministic and tested.

## WIE-6 Residue And Guard Slice

Goal:

Remove old result-specific and adapter-specific ingress residue once all
production callers are moved.

Scope:

- Delete `WebSocketInboundMessage` and `WebSocketInboundMessageSink` if not
  already removed.
- Remove direct adapter construction of `TransportResultIngressEnvelope`.
- Update transport and WebSocket baselines.
- Add architecture guards.
- Update SDK/server docs where worker result submit shape or wire examples
  change.

Acceptance:

- Guard fails if `WorkerIngress` contains forbidden fields:
  `taskId`, `messageId`, `attemptId`, `eventCode`, `success`, `resultCode`,
  `result`, `routeKey`, `adapterId`, `connectionId`, `deliveryBucketId`, or
  `deliveryQueueKey`.
- Guard fails if `diagnostics` is used as a routing/correctness/lifecycle
  input in transport runtime.
- Guard fails if WebSocket/socket adapter mainline creates
  `TransportResultIngressEnvelope` outside the task-result worker ingress
  handler or an explicitly allowed temporary bridge.
- Guard fails if production code outside adapter-local wire packages imports
  `WebSocketInboundMessage`.
- Transport baselines state that `WorkerIngress` is the worker-to-platform
  ingress envelope and `TransportResultIngressEnvelope` is the result-inbox
  carrier.

## Suggested Implementation Order

1. WIE-0 inventory.
2. WIE-1 neutral contract and starter-owned task-result bridge.
3. WIE-2 WebSocket path.
4. WIE-3 Socket path.
5. WIE-4 Polling/HTTP path.
6. WIE-5 non-result worker reports.
7. WIE-6 residue/guards/docs.

Each slice must leave the repository compiling. Do not create a break-now,
fix-later state where adapters emit `WorkerIngress` but no starter-owned
`task.result` bridge exists.

## Verification Candidates

These are pre-implementation smoke candidates. They may use
`-Dsurefire.failIfNoSpecifiedTests=false` while slice tests are still being
created or renamed.

WIE-1:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,sdk/xa-mass-embedded-sdk -am test "-Dtest=WorkerIngressTest,TaskResultWorkerIngressBridgeTest,RuntimeTaskResultIngestChannelTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

WIE-2:

```powershell
./mvnw -q -pl transport/websocket-adapter,sdk/xa-mass-java-sdk -am test "-Dtest=WebSocketInputProcessorTest,WebSocketFrameReadersTest,DispatcherInboundHandlerTest,WebSocketWorkerRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

WIE-3:

```powershell
./mvnw -q -pl transport/socket-adapter -am test "-Dtest=SocketTransportServerTest,SocketTransportFrameCodecTest,SocketTaskDispatchChannelTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

WIE-4:

```powershell
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=EmbeddedPullWorkerSessionTest,RuntimeTaskResultIngestChannelTest,ExternalWorkerPollingApiIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Cross-module compile:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-server -am -DskipTests test-compile
```

## Completion Proof

Before any slice is marked complete, use proof commands that fail if named
tests are missing. Do not keep `-Dsurefire.failIfNoSpecifiedTests=false` in
completion proof.

WIE-1 completion proof:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,sdk/xa-mass-embedded-sdk -am test "-Dtest=WorkerIngressTest,TaskResultWorkerIngressBridgeTest,RuntimeTaskResultIngestChannelTest,TransportConvergenceArchitectureGuardTest"
```

WIE-2 completion proof:

```powershell
./mvnw -q -pl transport/websocket-adapter,sdk/xa-mass-java-sdk -am test "-Dtest=WebSocketInputProcessorTest,WebSocketFrameReadersTest,DispatcherInboundHandlerTest,WebSocketWorkerRuntimeTest"
```

WIE-3 completion proof:

```powershell
./mvnw -q -pl transport/socket-adapter -am test "-Dtest=SocketTransportServerTest,SocketTransportFrameCodecTest,SocketTaskDispatchChannelTest"
```

WIE-4 completion proof:

```powershell
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=EmbeddedPullWorkerSessionTest,RuntimeTaskResultIngestChannelTest,ExternalWorkerPollingApiIntegrationTest"
```

## Completion Criteria

The roadmap is complete only when:

- All production worker-to-platform result ingress paths pass through
  `WorkerIngress`.
- At least one non-result worker-originated report path uses `WorkerIngress` or
  is explicitly deferred with an owner decision.
- Adapters do not own result schema parsing beyond wire-to-envelope extraction.
- Transport result inbox still uses `TransportResultIngressEnvelope` as an
  opaque durable carrier.
- No active mainline code imports `WebSocketInboundMessage`.
- Guards prevent forbidden fields from entering `WorkerIngress`.
- Task-result success/failure stays inside task-result payload or downstream
  task-result command, and `WorkerIngress` does not expose `success`,
  `resultCode`, or `result` fields.
- Transport and SDK/server docs describe the current worker ingress shape
  without preserving a parallel old model.

## Open Decisions

- Should public HTTP worker submit switch directly to
  `ingressCode/correlationRef/payload`, or keep a result-shaped convenience
  request that is immediately converted to `WorkerIngress` at the server
  boundary?
- Should `WorkerIngress` live under `transport_api` only, or should a matching
  wire DTO be added to `xa-mass-public-contract` for external SDK generation?
  The dependency rule must be checked before adding a public-contract type.
- Should unknown `ingressCode` be accepted-noop, rejected, or routed to a
  diagnostics channel?
