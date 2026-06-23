# Java SDK Worker Action Channel Model Convergence Roadmap

Status: archived after mainline implementation. WebSocket focused proof passed;
socket channel carrier migration remains deferred to a future protocol-carrier
decision.

Supersedes:

- `JAVA_SDK_WORKER_PUBLIC_MODEL_CONVERGENCE_ROADMAP.md` for the final
  `WorkerInvocation` / `WorkerResultSubmission` ownership decision. That
  roadmap's earlier "single public invocation" conclusion was correct for the
  previous residue cleanup slice, but this roadmap is the successor for the
  worker action/channel model.
- `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md` wherever it
  says future runtime work must preserve `WorkerInvocation` and
  `resultCorrelationRef` naming. Registration, evidence, and maintenance-loop
  decisions in that historical roadmap were superseded where explicitly changed
  here.

## Summary

The Java worker SDK already removed most historical public DTO residue, but the
current runtime model is still task-dispatch shaped:

```text
WorkerInvocation(resultCorrelationRef, eventCode, input, sharedConfig)
WorkerEventHandler.handle(WorkerInvocation) -> WorkerResult
WorkerResultSubmission(resultCorrelationRef, success, resultCode, result)
```

That model works for assigned task dispatch, but it is too narrow for the next
worker runtime shape. Platform-to-worker work should be expressed as one
worker action model, independent of whether the action came from a task item,
system command, operator command, or later platform-directed control message.

Target principle:

```text
Network protocol owns framing.
Worker runtime owns action intake and reply routing.
Worker business code owns one action handler shape.
Worker evidence/reporting stays outbound and does not enter the business
handler path.
```

This roadmap converges the Java SDK worker runtime toward:

```text
WorkerChannelFrame(kind, body)
  -> WorkerAction
  -> WorkerActionHandler.handle(action)
  -> WorkerActionResult
  -> WorkerActionReply
```

The main payoff is that polling, WebSocket, future socket, embedded pull
workers, server HTTP workers, and future remote-process Java workers can share
the same `WorkerActionHandler` functions without being coupled to their network
protocol or to task-only invocation names.

## Before-Convergence Code Observations

This section records the pre-convergence state that motivated the roadmap. It is
not current implementation truth after the mainline action/channel model landed.

Current Java SDK model facts:

- `WorkerInvocation` is the public assigned-dispatch item and carries
  `resultCorrelationRef`, `eventCode`, `input`, and `sharedConfig`.
- `WorkerEventHandler` is the handler callback:
  `WorkerResult handle(WorkerInvocation invocation)`.
- `WorkerResult` is the handler result:
  `success`, `resultCode`, and `result`.
- `WorkerResultSubmission` is the direct worker API result-submit DTO:
  `resultCorrelationRef`, `success`, `resultCode`, and `result`.
- `WorkerRuntimeDefinition` stores `Map<String, WorkerEventHandler>` keyed by
  event code.
- `WorkerDispatchProcessor` is the common runtime executor for polling and
  WebSocket runtimes. It resolves handlers by `WorkerInvocation.eventCode()`.
- `PollingWorkerProtocolDriver` polls `WorkerInvocation` items and submits a
  `WorkerResultSubmission`.
- `WebSocketWorkerProtocolDriver` directly decodes a text frame into
  `WorkerInvocation` and encodes a result frame from `resultCorrelationRef`
  plus `WorkerResult`.
- `transport/websocket-adapter` is the server-side counterpart of the Java SDK
  WebSocket protocol. `WebSocketTaskDispatchChannel` currently sends
  `DispatchMessage.payload()` as raw text, and result ingress reads the old
  result-shaped frame directly. If the SDK switches to `WorkerChannelFrame`,
  the WebSocket adapter send/read side must switch in the same slice.
- `WorkerRuntimeReporter` separately reports handler/runtime evidence through
  `WorkerClient.reportHandlerEvidence(...)` and
  `WorkerClient.reportRuntimeEvidence(...)`.
- Historical command poll/ack DTOs have already been removed from the Java SDK
  execution surface. If command intake returns later, it should not recreate a
  second handler domain beside task invocation.
- Embedded SDK still exposes a parallel worker edge:
  `com.xa.mass.sdk.worker.WorkerInvocation`,
  `WorkerPollResult`, `WorkerResultSubmission`,
  `EmbeddedPullWorkerSession.poll(...)`, and
  `EmbeddedPullWorkerSession.submitResult(...)`.
- Server worker HTTP routes currently return embedded SDK `WorkerInvocation`
  values from `ExternalWorkerApiController.pollTasks(...)` and accept
  `WorkerResultSubmissionRequest(resultCorrelationRef, success, resultCode,
  result)` at `:submit-result`.
- `TaskDispatchPayloadEncoder`, embedded pull decoding, Java SDK WebSocket
  dispatch decoding, and server worker poll responses all participate in the
  same worker action payload shape. They must converge together when the payload
  body model changes.
- Active roadmap residue still describes `WorkerInvocation` /
  `WorkerResultSubmission` as the final public model. This roadmap intentionally
  replaces that conclusion for the worker execution/action boundary.

Representative files:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerInvocation.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmission.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerRuntimeDefinition.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerEventHandler.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerResult.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerDispatchProcessor.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/PollingWorkerProtocolDriver.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WebSocketWorkerProtocolDriver.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimeReporter.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerInvocation.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerInvocationPayloadDecoder.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/EmbeddedPullWorkerSession.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/WorkerClientOperations.java`
- `xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java`
- `xa-mass-server/src/main/java/com/xa/mass/api/model/worker/WorkerResultSubmissionRequest.java`

## Disposition Table

This table records the JWA-0 disposition after the mainline implementation.

| Symbol | Domain | Java SDK runtime disposition | Embedded/server edge disposition | Target |
| --- | --- | --- | --- | --- |
| `WorkerInvocation` | handler-domain | deleted, replaced by `WorkerAction` | embedded `WorkerInvocation` deleted; server poll response emits action-shaped fields | `WorkerAction(actionId, replyRef, eventCode, body, sharedConfig)` |
| `WorkerEventHandler` | handler-domain | deleted, replaced by `WorkerActionHandler` | not exposed by embedded/server edge | `WorkerActionHandler.handle(WorkerAction)` |
| `WorkerResult` | handler-domain | deleted, replaced by `WorkerActionResult` | not exposed by embedded/server edge | `WorkerActionResult(success, code, body)` |
| `WorkerResultSubmission` | reply-domain | deleted, replaced by `WorkerActionReply` | embedded `WorkerResultSubmission` and server `WorkerResultSubmissionRequest` deleted | `WorkerActionReply(replyRef, success, code, body)` |
| `WorkerPollResult` | protocol/edge-domain | kept as polling protocol result wrapper carrying `WorkerAction` items | embedded poll result kept as HTTP edge wrapper carrying `WorkerAction` items | protocol wrapper, not handler model |
| `WorkerRuntimeDefinition` | evidence/config-domain | kept; handler map now owns `WorkerActionHandler` values keyed by `eventCode` | no server DTO owner | worker runtime definition and handler availability evidence |
| `WorkerDispatchProcessor` | handler-domain runtime | kept; executes `WorkerActionHandler` and emits `WorkerActionResult` | no server DTO owner | common handler executor for polling and WebSocket runtimes |
| `PollingWorkerProtocolDriver` | protocol-domain | kept; polls action items and submits `WorkerActionReply` | server API request/response field names are action/reply-shaped | polling network exchange only |
| `WebSocketWorkerProtocolDriver` | protocol-domain | kept; reads/writes `WorkerChannelFrame` and decodes action/reply body | WebSocket adapter sends `ACTION` and reads `ACTION_REPLY` | WebSocket network exchange only |
| `WorkerRuntimeFailureEvent` | diagnostics-domain | kept; failure correlation uses `replyRef` | not a server worker action DTO | runtime diagnostic event |
| `WorkerRuntimeReporter` | evidence-domain | kept separate from handler path | no server action DTO owner | explicit evidence/reporting owner |
| `TaskDispatchPayloadEncoder` / embedded action decoder | bridge-domain | not Java SDK public API | starter/embedded bridge emits and decodes worker action payload shape | task-backed action payload bridge |

## Owner Review

Worker runtime owns the local execution contract:

```text
action handler registry
action intake
handler dispatch
handler result handling
reply submission orchestration
bounded runtime diagnostics
```

Protocol drivers own network exchange only:

```text
poll request/response
websocket frame read/write
connection/session mechanics
wire JSON encoding/decoding
```

Worker business handlers own action execution:

```text
eventCode/action code interpretation
opaque action body parsing
business result body creation
```

Worker evidence reporting owns worker-originated reports:

```text
heartbeat
runtime state evidence
handler availability evidence
load/health attributes
```

Evidence/reporting must not be forced through the business action handler. A
worker may share the same network channel for replies and reports, but channel
multiplexing is not the same as handler-domain unification.

## Boundary Decision

Use one worker business execution model:

```java
public record WorkerAction(
        String actionId,
        String replyRef,
        String eventCode,
        String body,
        Map<String, Object> sharedConfig
) {}
```

Semantics:

- `actionId` is trace/readable action identity. It replaces the need to expose
  task-specific `messageId` as a handler concept. For task-backed actions it is
  sourced from the delivery/action id already carried at the worker edge, such
  as `DispatchMessage.deliveryId` / `PulledDeliveryMessage.deliveryId`. If an
  upstream source lacks an action id, the worker runtime or protocol driver may
  generate one for diagnostics. `actionId` is not reply correctness, retry
  authority, task lifecycle truth, or a reason to expose `messageId`.
- `replyRef` is an opaque reply handle. For a task-backed action it replaces
  `resultCorrelationRef`; for a system/operator action it can be an
  acknowledgement correlation. Handlers must round-trip it only through runtime
  reply submission and must not interpret lifecycle semantics from it.
- `eventCode` is the worker handler dispatch key. Keep the existing vocabulary:
  it is not task-only, it is the code for the worker action the handler knows
  how to execute.
- `body` is opaque action input. For current task-backed actions this is a JSON
  object string produced by the SDK/starter worker-payload encoder; later action
  sources may use a different string body, but only worker business code may
  parse it.
- `sharedConfig` is system-injected, read-only shared configuration for the
  action. It is a JSON-safe map, not a Java object graph: values must be
  strings, numbers, booleans, nulls, lists, or nested maps that the configured
  codec can serialize across protocol boundaries. Absent config is an empty
  map. It is not diagnostics and must not carry task lifecycle, transport
  endpoint, trace, session, or retry authority facts.

Use one handler return model:

```java
public record WorkerActionResult(
        boolean success,
        String code,
        String body
) {}
```

Use one action reply model for runtime/protocol submission:

```java
public record WorkerActionReply(
        String replyRef,
        boolean success,
        String code,
        String body
) {}
```

Use one channel carrier for protocols that need multiplexing:

```java
public record WorkerChannelFrame(
        String frameId,
        String kind,
        String body
) {}
```

Frame `kind` is channel-level, not business-domain-level:

```text
ACTION
ACTION_REPLY
EVIDENCE_REPORT
HEARTBEAT
```

`WorkerChannelFrame.kind` must be the only place where a shared network channel
distinguishes action, reply, evidence, and heartbeat frames. The `body` for
`ACTION` decodes to `WorkerAction`; it must not repeat `kind` inside the body.

Worker evidence remains separate:

```text
WorkerHandlerEvidence / WorkerRuntimeEvidence today
future WorkerEvidenceReport or reporter-owned successor
```

Evidence can be encoded as `WorkerChannelFrame(kind=EVIDENCE_REPORT, body=...)`
when a shared channel supports it, but it is not a `WorkerAction` and does not
go through `WorkerActionHandler`.

## Target Shape

Recommended Java SDK worker runtime model after convergence:

```text
protocol carrier
  WorkerChannelFrame(frameId, kind, body)

worker business execution
  WorkerAction(actionId, replyRef, eventCode, body, sharedConfig)
  WorkerActionHandler.handle(WorkerAction) -> WorkerActionResult

runtime reply
  WorkerActionReply(replyRef, success, code, body)

worker declaration/runtime definition
  WorkerRuntimeDefinition(workerId, workerGroupId, attributes, actionHandlers)
  WorkerSpec projection remains registration-only

worker evidence
  WorkerRuntimeReporter remains the reporter owner
  WorkerHandlerEvidence / WorkerRuntimeEvidence stay explicit until a later
  evidence-report shape is approved
```

The handler function is now portable:

```text
same WorkerActionHandler
  used by polling runtime
  used by WebSocket runtime
  usable by future socket runtime
  usable by future external adapter/remote-process worker runtime
```

Protocol choice affects only how `WorkerAction` arrives and how
`WorkerActionReply` leaves.

## Public Edge Decision

Use `replyRef` as the worker action/reply model name across Java SDK,
embedded SDK, and server worker DTOs. The existing HTTP route path
`:submit-result` may remain as a product verb during this convergence, but its
request/response model should not keep `resultCorrelationRef` after the edge
mapping slice lands.

`input` helper ergonomics may remain as helper methods on `WorkerAction` for
Java convenience, but the public action contract is `body` plus
`sharedConfig`. Implementations must not JSON serialize and deserialize between
runtime layers just to create fake separation; the shared codec/mapper owns the
single conversion at protocol or server edge.

## Non-Goals

- Do not redesign transport dispatch/result carriers.
- Do not reintroduce worker command poll/ack DTOs as a second handler domain.
- Do not force heartbeat, evidence report, or connection lifecycle events
  through `WorkerActionHandler`.
- Do not add task ids, attempt ids, lease tokens, transport endpoint ids, or
  adapter mailbox keys to `WorkerAction`.
- Do not preserve `WorkerInvocation` as a compatibility alias after all in-repo
  callers migrate.
- Do not preserve embedded SDK `WorkerInvocation` / `WorkerResultSubmission` or
  server `WorkerResultSubmissionRequest` as a parallel public worker model after
  action/reply callers migrate.
- Do not create separate polling/WebSocket action models.
- Do not require shared-channel framing for polling in the first slice if the
  polling HTTP API can directly return action items. `WorkerChannelFrame` is
  required at the protocol boundary that multiplexes multiple frame kinds, not
  as noise on every internal method.
- Do not rename `eventCode` to `actionCode` in this roadmap. The project
  already uses `eventCode` as handler identity; redefine it as worker action
  handler key instead of introducing a parallel vocabulary.
- Do not add task ids, message ids, attempt ids, result-ingress partition keys,
  or transport delivery internals to `WorkerAction` to make `actionId` easier to
  populate. `actionId` is an action diagnostic id, not task identity.

## Do Not Start With

Do not start by adding `WorkerChannelFrame` everywhere.

Start by converging the handler-domain model from `WorkerInvocation` to
`WorkerAction`. Frame multiplexing is useful, but forcing every current call
path through a frame before the action contract is stable would add a new
wrapper without removing the old task-shaped model.

## JWA-0 Inventory And Disposition

Goal: classify every current Java SDK worker model and route affected by the
action model.

Scope:

- `WorkerInvocation`
- `WorkerEventHandler`
- `WorkerResult`
- `WorkerResultSubmission`
- `WorkerPollResult`
- `WorkerRuntimeDefinition`
- `WorkerDispatchProcessor`
- `PollingWorkerProtocolDriver`
- `WebSocketWorkerProtocolDriver`
- `WorkerRuntimeFailureEvent`
- `WorkerRuntimeReporter`
- embedded SDK `WorkerInvocation`, `WorkerPollResult`,
  `WorkerResultSubmission`, `EmbeddedPullWorkerSession`,
  `WorkerClientOperations`
- server external worker API request/response DTOs and controller mapping
- server external worker poll/result routes that still emit/consume the SDK
  worker invocation/result shape
- `TaskDispatchPayloadEncoder` and embedded pull payload decoder because they
  define current action body shape
- active roadmap/docs that still protect `WorkerInvocation` /
  `WorkerResultSubmission` as final public worker model

Acceptance:

- A disposition table exists in this roadmap or a sibling inventory that marks
  each symbol as keep, rename, delete, private protocol artifact, or deferred.
- The table separates handler-domain, protocol-domain, reply-domain, and
  evidence-domain models.
- The table separates Java SDK managed-runtime usage, embedded SDK pull-worker
  usage, and server HTTP worker API edge usage.
- The old public-model and runtime-capability roadmaps are marked superseded or
  revised for the `WorkerInvocation` / `WorkerResultSubmission` final-model
  decision before implementation begins.
- No implementation slice starts while `WorkerInvocation`, `WorkerResult`, and
  `WorkerResultSubmission` ownership is still ambiguous.

Verification candidates:

```bash
rg -n "WorkerInvocation|WorkerEventHandler|WorkerResult\\b|WorkerResultSubmission|WorkerPollResult|WorkerResultSubmissionRequest|resultCorrelationRef" sdk/xa-mass-java-sdk sdk/xa-mass-embedded-sdk xa-mass-server roadmap doc -g "*.java" -g "*.md" --glob "!**/target/**"
```

## JWA-1 Converge Action Intake To WorkerAction

Goal: replace the task-shaped handler and worker-poll input vocabulary with
action vocabulary across Java SDK, embedded SDK, and server worker API edges.

Scope:

- add `WorkerAction`
- add `WorkerActionResult`
- add `WorkerActionHandler`
- rename or replace `WorkerInvocation`
- rename or replace `WorkerResult`
- update `WorkerRuntimeDefinition` handler map
- update `WorkerDispatchProcessor`
- update polling/WebSocket runtime handler calls
- update `PollingWorkerProtocolDriver` and `WebSocketWorkerProtocolDriver`
- update embedded SDK `WorkerInvocation`, `WorkerPollResult`,
  `EmbeddedPullWorkerSession`, and `WorkerInvocationPayloadDecoder`
- update `WorkerClientOperations.pollTasks*` or rename to action-oriented
  methods if the public operation name changes
- update server external worker poll response shape
- update `TaskDispatchPayloadEncoder` so task-backed worker actions emit a
  `body` string and JSON-safe `sharedConfig` map rather than preserving the old
  `WorkerInvocation(input, sharedConfig)` contract
- update Java SDK tests and examples

Acceptance:

- Business handlers implement `WorkerActionHandler`.
- Handler input is `WorkerAction`, not `WorkerInvocation`.
- Handler output is `WorkerActionResult`, not `WorkerResult`.
- `eventCode` remains the dispatch key and is documented as worker action
  handler identity, not task-only capability truth.
- `eventCode` as a handler key does not expand WorkerGroup capability truth.
  Platform scheduling still relies on WorkerGroup capability, event permission,
  and namespace constraints to decide which actions may be externally
  scheduled.
- `actionId` is populated from delivery/action id where available and generated
  as diagnostic-only fallback when unavailable; it never exposes task message
  id or attempt id.
- `replyRef` replaces `resultCorrelationRef` in handler/runtime action input.
- `body` is a non-null opaque string. Task-backed dispatch uses a JSON object
  string, defaulting absent input to `{}`.
- `sharedConfig` is a JSON-safe read-only map, defaulting absent config to an
  empty map. It is not diagnostics and does not carry transport/session/task
  lifecycle authority.
- `MassPayload` may remain only as optional handler ergonomics for parsing JSON
  bodies; it is not the action contract and does not drive wire shape.
- No public task-shaped invocation alias remains in `xa-mass-java-sdk`,
  `xa-mass-embedded-sdk`, or server worker API response DTOs.
- Polling and WebSocket runtimes invoke the same `WorkerActionHandler` model.
- Existing worker E2E tests prove task-backed actions still reach the selected
  worker and submit task results through reply mapping.

Verification candidates:

```bash
rg -n "WorkerInvocation|WorkerEventHandler|WorkerResult\\b|resultCorrelationRef|inputMap|sharedConfigMap" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=WorkerRuntimeDefinitionTest,WorkerDispatchProcessorTest,WorkerRuntimeContextTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,WorkerClientTest,TaskDispatchPayloadEncoderTest,WorkerInvocationPayloadDecoderTest,EmbeddedPullWorkerSessionTest,ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## JWA-2 Converge Reply Submission To WorkerActionReply

Goal: make result/ack submission a reply to an action instead of a task-result
specific DTO.

Scope:

- add `WorkerActionReply`
- replace `WorkerResultSubmission` in Java SDK direct submit paths
- replace embedded SDK `WorkerResultSubmission` and
  `EmbeddedPullWorkerSession.submitResult(...)` request shape
- replace server worker result-submit request model fields with action-reply
  names
- update `WorkerClient.submitResult(...)` to `submitActionReply(...)`; the HTTP
  route path may keep `:submit-result` as an edge verb only during this
  roadmap, but SDK/runtime model names must use reply vocabulary
- update polling runtime result submission
- update WebSocket result frame encoding
- update starter-owned task result callback bridge to map `WorkerActionReply`
  to task-result payload while keeping task-result decode owner in
  embedded/starter code

Acceptance:

- Runtime reply submission uses `replyRef + WorkerActionResult` facts.
- `resultCorrelationRef` no longer appears in handler-domain classes. If the
  server route path still says `submit-result`, request/response field names
  still use `replyRef`.
- `WorkerActionReply` can represent a task result reply and a future command
  acknowledgement without introducing `WorkerControlCommand`.
- No `WorkerResultSubmission` compatibility alias remains after in-repo callers
  migrate.
- Starter/embedded task-result bridge is the only place that maps `replyRef` to
  the existing task-result callback correlation payload.
- `resultCorrelationRef` may remain only inside starter-owned task-result
  callback bridge/codec code and tests that prove the task-result payload
  mapping. It must not remain in Java SDK handler/runtime action models,
  embedded worker action API models, or server worker action request/response
  DTOs.

Verification candidates:

```bash
rg -n "WorkerResultSubmission|resultCorrelationRef" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java -g "*.java" | rg -v "TaskResultCallbackCodec|TaskResultCallbackCommand|RuntimeTaskResultIngestChannel|ResultIngress"
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=WorkerClientTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,EmbeddedPullWorkerSessionTest,RuntimeTaskResultIngestChannelTest,TaskResultCallbackCodecTest,ExternalWorkerApiControllerTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## JWA-3 Introduce WorkerChannelFrame At Multiplexed Protocol Boundaries

Goal: use a single channel carrier where a protocol can carry action, reply,
evidence, heartbeat, or future system frames.

Scope:

- add `WorkerChannelFrame`
- WebSocket worker protocol frame decode/encode on the Java SDK worker side
- WebSocket adapter outbound dispatch wrapping: `WebSocketTaskDispatchChannel`
  sends `WorkerChannelFrame(kind=ACTION, body=<worker action payload>)` instead
  of raw dispatch payload text
- WebSocket adapter inbound reply reading: result/reply frame reader and
  `WebSocketInputProcessor` consume `WorkerChannelFrame(kind=ACTION_REPLY,
  body=<worker action reply payload>)` before handing the opaque reply payload
  to result ingress
- add focused `WebSocketWorkerProtocolDriverTest` or an equivalent
  protocol-driver test that does not depend on full runtime startup
- update `WebSocketTaskDispatchChannelTest`, `WebSocketFrameReadersTest`, and
  `WebSocketInputProcessorTest` with adapter-side send/read proof
- socket adapter `WorkerChannelFrame` migration is explicitly deferred; this
  roadmap does not claim socket already uses the shared channel carrier
- protocol tests

Acceptance:

- WebSocket protocol driver reads `WorkerChannelFrame` before decoding action
  or other frame body.
- WebSocket adapter sends the same `WorkerChannelFrame` shape that the Java SDK
  WebSocket worker reads; there is no intermediate state where SDK expects
  frames but the adapter still sends raw action payload.
- WebSocket adapter result ingress accepts `ACTION_REPLY` frames and passes the
  reply body to the starter-owned task-result bridge without parsing business
  success/result fields in adapter code.
- WebSocket adapter `ACTION_REPLY` requires `WorkerActionReply.replyRef`; legacy
  `resultCorrelationRef` is not a production fallback at the adapter frame
  boundary.
- `WorkerChannelFrame.kind` is the only channel-level discriminator.
- `ACTION` body decodes to `WorkerAction`.
- `ACTION_REPLY` body encodes `WorkerActionReply`.
- Evidence/heartbeat frames may be recognized or deferred, but they do not go
  through `WorkerActionHandler`.
- Polling is not forced through `WorkerChannelFrame` unless the HTTP API itself
  needs multiplexed worker frames.
- Socket is not forced through `WorkerChannelFrame` in this roadmap; socket
  remains a follow-up protocol-carrier decision.
- Focused protocol-driver tests prove frame kind dispatch without relying only
  on `WebSocketWorkerRuntimeTest`.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test "-Dtest=WebSocketWorkerProtocolDriverTest"
./mvnw -q -pl transport/websocket-adapter -am test "-Dtest=WebSocketTaskDispatchChannelTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
rg -n "resultCorrelationRef" transport/websocket-adapter/src/main/java -g "*.java"
rg -n "decodeDispatchFrame|encodeResultFrame|WorkerChannelFrame|ACTION_REPLY|EVIDENCE_REPORT|HEARTBEAT" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java transport/websocket-adapter/src/main/java transport/websocket-adapter/src/test/java -g "*.java"
```

## JWA-4 Keep Evidence Reporting Separate But Channel-Compatible

Goal: preserve the correct owner split for worker-originated reports while
allowing shared-channel transport later.

Scope:

- `WorkerRuntimeReporter`
- `WorkerHandlerEvidence`
- `WorkerRuntimeEvidence`
- future `WorkerEvidenceReport` decision, if needed
- WebSocket/channel evidence frame support only if implementation is ready

Acceptance:

- Evidence report models are not passed to `WorkerActionHandler`.
- Runtime/reporting code may emit evidence through an explicit reporter.
- If evidence uses `WorkerChannelFrame`, the frame is
  `kind=EVIDENCE_REPORT` with a reporter-owned body. It is not encoded as
  `WorkerAction`.
- WorkerGroup capability truth remains outside worker self-reporting.
- `WorkerRuntimeDefinition.eventCodes()` or equivalent handler registry
  inspection remains handler availability evidence only. It does not grant new
  WorkerGroup capability, event permission, namespace permission, or scheduling
  eligibility.
- No `reportCapability` style API is reintroduced.

Verification candidates:

```bash
rg -n "reportCapability|WorkerCapabilityReport|WorkerActionHandler.*Evidence|EVIDENCE_REPORT" sdk/xa-mass-java-sdk xa-mass-server -g "*.java" -g "*.md" --glob "!**/target/**"
mvn -pl sdk/xa-mass-java-sdk -Dtest='WorkerRuntimeReporterTest,JavaExternalSdkArchitectureGuardTest' test
```

## JWA-5 Guards And Documentation

Goal: freeze the new worker action boundary after the handler, reply, and
protocol carrier shape is stable.

Scope:

- `JavaExternalSdkArchitectureGuardTest`
- `sdk/xa-mass-java-sdk/README.md`
- embedded SDK worker docs/tests that expose pull worker action shape
- server worker API docs/tests if request/response field names change
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` if public SDK boundary wording
  changes
- roadmap archive/update flow for superseded Java SDK worker public-model and
  runtime-capability model decisions

Acceptance:

- Guards reject public `WorkerInvocation`, `WorkerResultSubmission`, and
  task-shaped handler models after migration in Java SDK, embedded SDK, and
  server worker API main sources.
- Guards reject task lifecycle facts in Java SDK worker handler packages:
  `taskId`, `attemptId`, `leaseToken`, transport endpoint ids, adapter mailbox
  keys.
- Guards allow `eventCode` as worker action handler key.
- Docs show one handler model usable by polling and WebSocket runtimes.
- Docs explain that shared channel frames multiplex message types, while
  business handlers only process `WorkerAction`.

Verification candidates:

```bash
rg -n "WorkerInvocation|WorkerResultSubmission|TaskDispatch|DeliveryCommand|adapterMailboxKey|leaseToken|attemptId" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker xa-mass-server/src/main/java/com/xa/mass/api/model/worker -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=JavaExternalSdkArchitectureGuardTest,ExternalWorkerApiControllerTest,EmbeddedPullWorkerSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## Suggested Implementation Order

1. Execute `JWA-0` first if caller inventory is stale.
2. Execute `JWA-1` next. The action handler contract is the highest-value
   change because it makes worker business handlers portable across protocols.
3. Execute `JWA-2` after action handler migration. Reply naming should follow
   from action naming, not lead it.
4. Execute `JWA-3` only after action/reply contracts are stable. Do not let the
   frame carrier become another pass-through wrapper.
5. Execute `JWA-4` when evidence over shared channel is actually needed.
   Until then, keep reporter APIs explicit and separate.
6. Execute `JWA-5` last, once names and owner boundaries are stable enough to
   guard.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Java SDK business handlers use `WorkerActionHandler`.
- The handler input model is `WorkerAction`.
- The handler result model is `WorkerActionResult`.
- Runtime reply submission uses `WorkerActionReply`.
- `WorkerInvocation`, `WorkerEventHandler`, `WorkerResult`, and
  `WorkerResultSubmission` no longer remain as public compatibility aliases in
  Java SDK main sources.
- Embedded SDK and server worker API no longer expose
  `WorkerInvocation` / `WorkerResultSubmission` as parallel public worker
  action/reply models.
- Active SDK worker model roadmaps/docs no longer protect `WorkerInvocation` /
  `WorkerResultSubmission` as the final worker execution shape.
- Polling and WebSocket runtimes use the same handler-domain model.
- WebSocket action/reply protocol code uses `WorkerChannelFrame` rather than
  ad hoc frame detection. Socket channel-frame migration is explicitly deferred
  and is not part of this completion claim.
- `WorkerChannelFrame.kind` does not duplicate type inside `WorkerAction`.
- Worker evidence/reporting remains distinct from business action handling.
- WorkerGroup capability truth is not reintroduced through worker evidence or
  action handler registration.
- SDK docs and architecture guards reflect the new action/channel boundary.

## Open Decisions

- Whether `WorkerActionReply` should be public API or package-private runtime
  wire model if managed runtimes hide reply submission from ordinary SDK users.
- Whether `WorkerChannelFrame` should become part of the public Java SDK worker
  API or remain package-private to managed WebSocket runtime until a user-owned
  raw channel API is needed.
- Whether the old HTTP route verb `:submit-result` should be renamed after
  action/reply models land. Field/model names should converge to `replyRef` in
  this roadmap; route naming can be a server API cleanup if it has separate
  product impact.
