# Transport Result Routing Envelope Convergence Roadmap

Status: complete; archived after verification.

## Summary

Converge worker result ingress onto the same minimal routing carrier shape used
by the adapter-mailbox dispatch direction:

```java
RoutingEnvelope(
        envelopeId,
        RoutingTarget target,
        String payload,
        Map<String, String> diagnostics,
        long createdAtEpochMillis
)
```

The goal is not a generic event bus. The goal is to make result ingress a
single queue/process-boundary carrier:

```text
adapter receives worker result
  -> RoutingEnvelope(target=result-ingress:<resultCorrelationRef>, payload=opaque)
  -> result ingress queue
  -> starter-owned result bridge decodes payload
  -> engine result apply / retry / compensation decision
```

This roadmap supersedes the older `WorkerIngress` / `ingressCode` direction in
`../doc/archive/transport/2026-06-22_TRANSPORT_WORKER_INGRESS_ENVELOPE_CONVERGENCE_ROADMAP.md`.
Do not restore `WorkerIngress` as a second transport ingress model.

## Current Code Observations

- `transport_api` has `RoutingEnvelope`, `RoutingTarget`, and
  `RoutingOwnerKinds.RESULT_INGRESS`.
- `TransportResultIngressChannel` and `TransportResultIngressHandler` accept
  `RoutingEnvelope`.
- `RedisTransportResultIngressChannel` stores and claims `RoutingEnvelope`
  values through `RoutingEnvelopeCodec`.
- `BufferedTransportResultIngressChannel` drains in-memory `RoutingEnvelope`
  values into a `TransportResultIngressHandler`.
- `TransportResultIngressInboxPump` polls Redis result inbox claims and passes
  `RoutingEnvelope` to the starter-owned result handler.
- `WebSocketResultIngressFrameReader` and `SocketTransportServer` create
  `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)` and keep the
  full result frame/request as opaque payload.
- WebSocket and socket adapters recognize result shells by
  `resultCorrelationRef` present, `eventCode` absent, and non-control frame
  context. They no longer require or validate `success` while recognizing the
  shell.
- `EmbeddedPullWorkerSession` maps `WorkerResultSubmission` to
  `RoutingEnvelope` through `TaskResultCallbackCodec`.
- The public Java SDK worker runtimes remain upstream result producers:
  WebSocket runtime encodes result frames and polling runtime posts worker API
  result submissions; adapter/server boundaries normalize those into
  `RoutingEnvelope`.
- `TaskResultCallbackCodec` is starter-owned. It encodes
  `WorkerResultSubmission -> RoutingEnvelope`, decodes opaque result callback
  payload into `TaskResultCallbackCommand`, and validates envelope target
  owner ref against payload `resultCorrelationRef`.
- `RuntimeTaskResultIngestChannel` owns the starter-to-engine result bridge and
  maps handled / noop / permanent reject / retryable failure into transport ack
  outcomes.
- `TransportResultIngressEnvelope` and its codec have been deleted from
  production code.

## Owner Review

`RoutingEnvelope` belongs to the transport API process-boundary vocabulary.
It is the carrier, not the domain schema.

Result payload schema belongs to the starter/result bridge near
`TaskResultCallbackCodec` and `RuntimeTaskResultIngestChannel`. Transport
runtime and concrete adapters may queue, retry, diagnose, and route the
envelope, but they must not parse result success, result code, task id, message
id, attempt id, lease token, retry policy, or finality.

Engine owns result application, retry, reassign, compensation, and final task
convergence. Transport result ingress must return delivery/handling evidence;
it must not decide task retry or failure strategy.

Adapter implementations own protocol parsing only far enough to recognize that
a worker has submitted a result frame/request and to obtain routing metadata
that the wire/session contract explicitly exposes. The current clean result
shell rule is `resultCorrelationRef` present, `eventCode` absent, and the frame
is not a handshake or heartbeat. `success` is not part of shell recognition;
its required-field validation belongs to `TaskResultCallbackCodec`. Adapters
must not decode result payload semantics.

Statistics, snapshots, counts, list views, and inspection APIs are side
channels only. They must not be added to the result-ingress mainline to prove
this roadmap. Temporary proof counters must be test-scoped or removed before a
slice is complete.

## Boundary Decision

Use `RoutingEnvelope` as the result ingress queue carrier.

Target shape:

```text
target.ownerKind = "result-ingress"
target.ownerRef  = resultCorrelationRef or another result-inbox partition ref
payload          = opaque task-result callback payload
diagnostics      = bounded debug facts only
```

`RoutingOwnerKinds.ENGINE` should not be used for the current result envelope.
The current payload is decoded by the starter-owned result bridge before the
engine can apply it. If a future engine-owned payload schema is introduced, it
must be a separate owner decision.

The active adapter-mailbox roadmap previously described result routing as
`RoutingEnvelope(target = engine:<resultCorrelationRef>)`. This roadmap
supersedes that wording for result ingress: the current target owner is
`result-ingress`, and the starter result bridge remains the task-result payload
decoder.

`TransportResultIngressEnvelope` has been deleted from production transport
APIs. Result inbox codecs, pumps, channels, adapters, and embedded pull
sessions use `RoutingEnvelope` directly. Do not restore both carriers as
parallel public transport APIs.

## Target Shape

### Result Routing Envelope

```java
RoutingEnvelope resultEnvelope =
        new RoutingEnvelope(
                envelopeId,
                RoutingTarget.resultIngress(resultCorrelationRef),
                opaqueResultPayload,
                diagnostics,
                System.currentTimeMillis()
        );
```

Rules:

- `target.ownerKind` is the owner allowed to decode or delegate payload decode.
- `target.ownerRef` is a routing / partition reference, not task lifecycle
  truth.
- `payload` is opaque to transport runtime and adapters.
- `diagnostics` is not routing, lifecycle, retry, or correctness input.
- `createdAtEpochMillis` is carrier creation evidence only; task attempt
  timeout remains engine-owned.

### Result Payload

For the current task-result path, payload remains the starter-owned callback
JSON currently decoded by `TaskResultCallbackCodec`:

```json
{
  "resultCorrelationRef": "...",
  "success": true,
  "resultCode": "...",
  "result": "..."
}
```

`success`, `resultCode`, and `result` stay inside payload. Do not promote them
to `RoutingEnvelope`, `RoutingTarget`, or transport diagnostics.

### Correlation Consistency

During the transition, result frames and submit requests may expose
`resultCorrelationRef` both as routing metadata and inside the opaque payload.
The bridge must enforce one of these rules:

- derive `target.ownerRef` from the starter-owned payload decoder before
  enqueueing, or
- validate that `target.ownerRef` equals payload `resultCorrelationRef` before
  applying the result.

Do not let target owner ref and payload result correlation become two
independent truths.

## Non-Goals

- Do not design external adapter process registration in this roadmap.
- Do not add a generic cross-domain event bus.
- Do not change task result finality, retry, reassign, compensation, or public
  result read truth.
- Do not move task-result payload parsing into transport runtime or concrete
  adapters.
- Do not add worker heartbeat, worker signal, command ack, or diagnostics
  message families to this roadmap.
- Do not add statistics, dashboards, list APIs, global scans, or inspection
  surfaces to the result-ingress mainline.
- Do not use this roadmap to revisit delivery mailbox evidence or dispatch
  queue ownership.

## Do Not Start With

Do not start by adding a generic `WorkerIngress`, `ingressCode`, or
`MessageBus` abstraction.

Do not start by moving result payload decode from
`TaskResultCallbackCodec` into transport runtime.

Do not start by adding result stats or queue inspection APIs to prove the
change.

Start with the carrier boundary: result inbox producers, queue codecs, pumps,
and handlers must agree on `RoutingEnvelope`.

## RRE-0 - Inventory And Status Repair

Status: implemented.

Goal: classify every current result-ingress caller and decide whether it is a
carrier producer, queue owner, starter bridge, or engine result consumer.

Scope:

- `TransportResultIngressEnvelope`
- `TransportResultIngressChannel`
- `TransportResultIngressHandler`
- `RedisTransportResultIngressChannel`
- `BufferedTransportResultIngressChannel`
- `TransportResultIngressInboxPump`
- `TransportResultIngressEnvelopeCodec`
- `WebSocketResultIngressFrameReader`
- `WebSocketInputProcessor`
- `SocketTransportServer`
- `SocketTransportFrameCodec`
- `EmbeddedPullWorkerSession`
- `WorkerClientOperations.submitResult(...)`
- `ExternalWorkerApiController.submitResult(...)`
- `TaskResultCallbackCodec`
- `RuntimeTaskResultIngestChannel`
- result ingress tests and architecture guards

Acceptance:

- A sibling inventory records each symbol as carrier producer, queue owner,
  starter bridge, engine-facing handler, public worker API, diagnostics, test
  fixture, or stale residue.
- The inventory separates production callers from tests.
- The old `WorkerIngress` roadmap remains marked superseded and is not used as
  implementation input.
- Current owner docs do not describe `TransportResultIngressEnvelope` as a
  task-result schema.

## RRE-1 - Routing Result Target Vocabulary

Status: implemented.

Goal: make `RoutingEnvelope` capable of representing result ingress without
mislabeling the payload as engine-owned.

Scope:

- Add a `result-ingress` owner kind to `RoutingOwnerKinds`.
- Add `RoutingTarget.resultIngress(String resultCorrelationRef)` or equivalent
  named factory.
- Keep `RoutingTarget.engine(...)` out of the current result-ingress mainline.
- Update `TRANSPORT_ROUTING_ENVELOPE_ADAPTER_MAILBOX_CONVERGENCE_ROADMAP.md`
  RTE-5 so it points to this roadmap and uses `result-ingress`, not `engine`,
  for current result routing.
- Add tests for owner-kind normalization, unknown owner rejection, and required
  owner ref.

Acceptance:

- Result ingress target construction uses `result-ingress`, not `engine`.
- `RoutingEnvelope` tests prove payload is required and diagnostics are copied.
- No task-result payload fields are added to `RoutingEnvelope` or
  `RoutingTarget`.
- Existing `RoutingTarget.engine(...)` tests do not describe the current
  task-result ingress owner.

## RRE-2 - Compile-Safe Result Carrier Pivot

Status: implemented.

Goal: move all production result ingress producers, queues, pumps, and handlers
from `TransportResultIngressEnvelope` to `RoutingEnvelope` in one compiling
slice. Do not split this into separate interface, producer, and starter-bridge
slices; that would either break compilation or preserve old/new carrier
overloads as a hidden compatibility track.

Scope:

- `TransportResultIngressChannel.ingest(RoutingEnvelope envelope)`
- `TransportResultIngressHandler.handle(RoutingEnvelope envelope)`
- `BufferedTransportResultIngressChannel`
- `RedisTransportResultIngressChannel`
- `TransportResultIngressInboxPump`
- `ClaimedTransportResultIngress`
- Result inbox codec, renamed or replaced with a routing-envelope codec
- WebSocket result frames
- Socket result frames
- Embedded polling/pull worker result submission
- External HTTP worker result submission through server and embedded SDK
  worker client
- Public Java SDK WebSocket and polling worker result producers
- `TaskResultCallbackCodec`
- `RuntimeTaskResultIngestChannel`
- `ResultIngressHandleOutcome`
- result identity validation tests

Acceptance:

- In-memory and Redis result inboxes store `RoutingEnvelope`.
- Redis result inbox JSON contains routing target + payload + diagnostics, not
  a result-specific transport envelope record.
- `WebSocketResultIngressFrameReader` returns `RoutingEnvelope`.
- `SocketTransportServer` enqueues `RoutingEnvelope`.
- `EmbeddedPullWorkerSession.submitResult(...)` enqueues `RoutingEnvelope`.
- External HTTP submit result still maps public request models through SDK /
  starter-owned result submission code; it must not import transport runtime or
  transport result DTOs.
- Public Java SDK WebSocket and polling worker tests prove result frame/request
  shape remains compatible with the adapter shell recognition rule.
- Adapters recognize result shells by `resultCorrelationRef` plus frame context:
  `eventCode` absent and not handshake/heartbeat. They must not require or
  validate `success` during shell recognition.
- `TaskResultCallbackCodec.decode(...)` accepts `RoutingEnvelope` or a narrow
  starter-owned adapter from `RoutingEnvelope` to the current callback command.
- The bridge validates `target.ownerRef` against decoded
  `payload.resultCorrelationRef`, or clearly derives one from the other before
  result apply.
- Invalid payloads return permanent reject / handled outcome as today; retryable
  failures remain reserved for transient handler/runtime failure.
- Result apply still flows through `TaskResultIngestFacade`.
- Engine-facing result apply continues to receive decoded task/message/result
  facts only inside starter/engine result code, not transport runtime.
- Inbox ack semantics remain narrow: ack only after handler returns an ackable
  outcome; retry/compensation remains engine/starter owned.
- No result inbox code parses `success`, `resultCode`, `result`, task id,
  message id, attempt id, or lease token.
- No stats/list/count/snapshot/inspect API is added to this mainline.

## RRE-3 - Remove Transitional Carrier Residue And Guards

Status: implemented.

Goal: delete the old result-specific carrier once all producers and consumers
use `RoutingEnvelope`.

Scope:

- Delete `TransportResultIngressEnvelope` if no production public boundary
  still needs it.
- Delete or rename `TransportResultIngressEnvelopeCodec`.
- Rewrite tests from transport-envelope expectations to routing-envelope
  expectations.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `transport/AGENTS.md`, and `doc/PROOF_REGISTRY.md`.
- Mark or archive superseded worker-ingress/result-ingress roadmap residue.

Acceptance:

- Production code no longer imports
  `com.xa.mass.transport.model.TransportResultIngressEnvelope`.
- Tests do not preserve `TransportResultIngressEnvelope` as a compatibility
  API.
- Transport docs say result inbox value is `RoutingEnvelope`.
- Guard fails if adapters or transport runtime parse task-result success,
  result code, task id, message id, attempt id, or lease token. The guard must
  allow payload pass-through and starter-owned decode, and it must not flag
  result shell recognition that only reads `resultCorrelationRef` / `eventCode`.
- Guard fails if result ingress mainline grows stats/list/count/snapshot/inspect
  APIs as part of this convergence.

## Roadmap Completion Criteria

- Result ingress producers across WebSocket, socket, polling/pull, and external
  worker HTTP paths emit `RoutingEnvelope`.
- Result inbox storage, Redis codec, in-memory buffer, and pump consume
  `RoutingEnvelope`.
- Starter result bridge remains the only task-result payload decoder.
- Engine remains the owner of result apply, retry, reassign, compensation, and
  final convergence.
- `TransportResultIngressEnvelope` is deleted or explicitly scoped to a
  non-production test fixture with no public transport API role.
- Current owner docs and proof registry describe result ingress as
  `RoutingEnvelope -> starter result bridge -> engine result apply`.
- No statistics/view/inspection APIs were added to the mainline; any temporary
  proof counters are removed or test-scoped.

## Verification Candidates

These commands are candidates and must be corrected after RRE-0 inventory. Do
not use `failIfNoSpecifiedTests=false` for newly mandatory tests.

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime -am -DskipTests test-compile
.\mvnw -q -pl transport/transport_api,transport/transport_runtime test "-Dtest=RoutingEnvelopeTest,RedisTransportResultIngressChannelTest,BufferedTransportResultIngressChannelTest,TransportConvergenceArchitectureGuardTest"

.\mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
.\mvnw -q -pl transport/websocket-adapter test "-Dtest=WebSocketFrameReadersTest,WebSocketInputProcessorTest"
.\mvnw -q -pl transport/socket-adapter test "-Dtest=SocketTransportServerTest,SocketTransportFrameCodecTest"
.\mvnw -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=TaskResultCallbackCodecTest,RuntimeTaskResultIngestChannelTest,EmbeddedPullWorkerSessionTest"
.\mvnw -q -pl xa-mass-server test "-Dtest=ExternalWorkerApiControllerTest"

.\mvnw -q -pl sdk/xa-mass-java-sdk -am -DskipTests test-compile
.\mvnw -q -pl sdk/xa-mass-java-sdk test "-Dtest=WorkerClientTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,JavaExternalSdkArchitectureGuardTest"
```

If reactor dependency state makes focused module test commands depend on stale
local artifacts, first run the matching `-am -DskipTests test-compile` command,
then run tests in the owning module without `-am`.
