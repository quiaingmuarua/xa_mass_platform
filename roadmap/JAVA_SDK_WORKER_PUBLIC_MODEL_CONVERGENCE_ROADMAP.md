# Java SDK Worker Public Model Convergence Roadmap

Status: mainline complete for Java SDK public worker model; payload-shape and
lower-level worker-control/runtime naming residues remain separate follow-ups.

## Summary

`sdk/xa-mass-java-sdk` currently exposes several generations of worker-facing
models through one `com.xa.mass.client.worker` package and one `WorkerClient`.
That package mixes worker execution, WorkerGroup capability declaration,
adapter-node topology setup, worker-local evidence reports, result submission,
and worker command control.

This roadmap pins the public-model cleanup goal: old worker SDK models must
converge into the current owner model or be deleted. They must not be preserved
as compatibility aliases, downgraded helper DTOs, or parallel live tracks.

Target rule:

```text
Every public worker SDK model must be one of:
  current worker execution/session contract,
  current WorkerGroup/worker declaration contract,
  current opaque result-submit contract,
  explicitly owned worker-control contract,
  explicitly owned topology/admin setup contract,
or deleted.
```

If a model survives, its name, package, caller, and route must express the owner
that actually owns the truth. If no owner can be stated, remove the model and
retarget in-repo callers.

Protocol choice must not create a separate public model family. Polling and
WebSocket are protocol drivers for the same worker runtime abilities. The Java
SDK should not have one conceptual model for polling workers and another for
WebSocket workers when the only difference is how dispatch is received or how a
result is submitted.

## Current Code Observations

Before this roadmap, Java SDK public models included these mixed groups:

- worker execution/session:
  `WorkerSession`, `PollingWorkerSession`, `WebSocketWorkerSession`,
  `WorkerInvocation`, `WorkerResult`, `WorkerEventHandler`,
  `WorkerEventHandlers`
- worker dispatch/result wire:
  `WorkerPollRequest`, `WorkerPollResult`, `WorkerDispatchItem`,
  `ResultCorrelationRef`, `WorkerResultSubmitRequest`,
  `WorkerResultSubmitOutcome`. `WorkerDispatchItem` overlapped with
  `WorkerInvocation`: it carried `resultCorrelationRef`, `eventCode`, `input`,
  and `sharedConfig`, while `WorkerInvocation` carried a second handler-facing
  projection of the same dispatch. `ResultCorrelationRef` is the current
  direct-wire/session result-submit token; the embedded starter bridge currently
  encodes task/message/attempt facts behind that opaque value.
  `WorkerResultSubmitOutcome` mostly echoed request-side identity with a submit
  flag.
- result shape residue:
  `WorkerResult` and `WorkerResultSubmitRequest` used
  `detail/errorCode/output`, while WebSocket encoded the same facts into a
  result frame and queued them through private `OutboundResult`. These were the
  same result-submission facts expressed through protocol-shaped names and
  field groups. The same old result shape also appeared in the embedded SDK
  pull worker submit API and server external worker result-submit request.
- declaration/presence:
  `WorkerGroupSpec`, `WorkerEventBindingSpec`, `WorkerSpec`,
  `WorkerRegistrationResult`, `WorkerPresenceResult`
- adapter topology residue:
  `AdapterNodeSpec`, `AdapterNodeRegistrationResult`,
  `NodeGroupBindingSpec`, `NodeGroupBindingResult`,
  `WorkerClient.registerAdapterNode(...)`, `WorkerClient.bindNodeGroup(...)`
- worker-local evidence residue:
  `WorkerCapabilityReport`, `WorkerCapabilityReportResult`,
  `WorkerStateReport`, `WorkerStateReportResult`, `WorkerStateProjection`,
  `WorkerClient.reportCapability(...)`, `WorkerClient.reportState(...)`
- worker-control command residue:
  `WorkerCommand`, `WorkerCommandPollRequest`, `WorkerCommandPollResult`,
  `WorkerCommandAck`, `WorkerCommandAckResult`,
  `WorkerClient.pollCommands(...)`, `WorkerClient.ackCommand(...)`
- callback/helper residue:
  `WorkerDispatchHandler`, `WorkerEventHandlers`, `WorkerEventInvocation`,
  `WorkerResultSink`, and broad `WorkerSession*Failure` records
- payload/wire boundary residue:
  `MassPayload` is useful as a handler payload helper, but it must not become a
  generic wire/runtime envelope. `WorkerDispatchItem` and `WorkerInvocation`
  were two DTOs for one assigned worker invocation. The landed direction is one
  neutral invocation model, not a wire item plus a handler projection.
  `PulledTaskDispatch` in the embedded SDK/server polling path had the same
  assigned-invocation fields and was another duplicate model. The current
  landed slice keeps `input` as a JSON object with SDK payload helpers; opaque
  string input is a separate payload-shape slice, not proof that the old DTOs
  should stay alive.
- integration wire residue:
  worker-pack WebSocket helpers and samples constructed result frames with
  `detail/errorCode/output`. Those tests are not compatibility proof for the
  new public worker model; they must be migrated or explicitly isolated as raw
  frame fixtures when the result shape converges.
- session startup asymmetry before convergence:
  `PollingWorkerSession.start()` wrote capability/state evidence via
  `WorkerClient.reportCapability(...)` and `WorkerClient.reportState(...)`,
  while WebSocket session startup followed a different shape. The mainline now
  treats worker-local evidence as an explicit worker ability, not a hidden
  polling session startup side effect.

Related active direction:

- `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md` owns the
  larger worker runtime ability model.
- `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md` owns any
  deeper worker-control/runtime evidence naming beyond the external Java SDK
  surface.
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` already states that worker-facing
  invocation/result APIs must not expose task lifecycle authority.

This roadmap is narrower: it owns public Java SDK worker model cleanup and
prevents old models from remaining as compatibility residue.

Landed slices:

- `WorkerResultSubmitRequest`, `WorkerResultSubmitOutcome`, and the embedded
  SDK/server old result-submit request classes have been replaced by
  `WorkerResultSubmission(resultCorrelationRef, success, resultCode, result)`.
- `WorkerDispatchItem`, handler-package `WorkerInvocation`,
  embedded `PulledTaskDispatch`, `TaskPullResult`, and `TaskPullStatus` have
  converged into `WorkerInvocation` / `WorkerPollResult` at the SDK/server
  worker boundary.
- `WorkerDispatchHandler` and `WorkerResultSink` have been deleted; handler
  registration uses `WorkerEventHandler`, and managed polling result submission
  uses the worker API path.
- Java SDK worker topology methods and DTOs have been removed from
  `WorkerClient`; normal worker registration/session setup no longer exposes
  adapter node or node-group binding ids.
- Java SDK worker-local reports have converged to explicit
  `WorkerHandlerEvidence` and `WorkerRuntimeEvidence` routes. Managed polling
  and WebSocket session startup do not publish those reports implicitly.
- Java SDK worker command poll/ack DTOs and `WorkerClient` methods have been
  removed from the execution-oriented worker SDK surface.
- `ResultCorrelationRef`, `WorkerEventHandlers`, `WorkerEventInvocation`, and
  `WorkerEventHandlerRuntime` have been deleted. `WorkerInvocation` and
  `WorkerResultSubmission` carry the opaque string token directly, and session
  builders keep handler maps internally.

## Owner Review

WorkerGroup owns capability declaration:

```text
WorkerGroupSpec / WorkerEventBindingSpec
```

Worker owns execution identity and scheduling evidence:

```text
WorkerSpec / registration / presence / worker-local evidence only if explicitly
owned by worker-runtime as evidence, not capability truth
```

Java SDK worker sessions own the worker execution experience:

```text
handler registry
dispatch intake
opaque result correlation held by the result-submit path
managed result submission
session lifecycle
bounded diagnostics
```

Adapter topology belongs to adapter/admin setup, not worker execution:

```text
adapter node registration
adapter-to-WorkerGroup binding
endpoint topology
```

Worker control commands belong to an explicit worker-control owner, not task
dispatch and not worker session delivery:

```text
operator/server command request
worker command intake
worker command acknowledgement
bounded command diagnostics
```

## Boundary Decision

Do not keep old worker SDK models by making them "legacy but supported" or
"lower-priority helper" APIs. For every old model, make one of these decisions:

1. **Keep as current contract**: only if the owner and mainline caller are clear.
2. **Rename/move to correct owner**: only if the model is still needed and the
   current name/package misstates ownership.
3. **Delete**: if the model exists only because an older adapter-node,
   capability-report, command, or dispatch-context design used to exist.

No compatibility alias is allowed after in-repo callers are migrated.

Simplicity rule:

```text
A public Java SDK worker model must earn its place by owning user-visible
semantics, validation, lifecycle, or an external wire edge.

Do not create or keep a public model only because an HTTP response, internal
runtime step, or session callback currently has a field group.
```

One-field wrappers are not models unless they protect a real owner invariant.
Request-echo response DTOs are not models unless the response owns additional
domain state that the caller can act on. Otherwise prefer a primitive, `void`,
`boolean`, or a package-private/runtime-internal record.

## Worker Runtime Ability Model

Use this model to judge whether a Java SDK worker type is real or just protocol
residue:

1. **Declaration / Register**
   Worker declares execution identity, WorkerGroup membership, transport mode
   hint, and base attributes. This is registration, not the runtime loop.
2. **Runtime Init**
   The local worker runtime loads handlers, creates the protocol driver,
   prepares the result sink, and prepares command or evidence reporters. This
   step may be purely local and does not necessarily call the server.
3. **Dispatch Intake**
   The runtime receives assigned task dispatch. Polling uses a poll loop;
   WebSocket receives frames from the channel. Both are the same intake ability.
   Do not model this as "long connection" versus "polling worker" capability.
4. **Dispatch Execution**
   The runtime resolves a handler by `eventCode`, invokes it with
   `WorkerInvocation`, and produces `WorkerResult`.
5. **Result Submit**
   The runtime submits the handler result for the dispatch item received from
   intake. Polling and WebSocket are only different submit transports. The
   current Java SDK result path uses `resultCorrelationRef` as an opaque token
   because the starter-side bridge still needs task/message/attempt facts to
   correlate the result. That token belongs to session/direct result-submit
   ownership, not handler input. It is not task/message/attempt identity and
   must not be interpreted as business input, scheduling input, retry
   authority, or lifecycle authority.
   The common result-submit fact should converge to
   `WorkerResultSubmission(resultCorrelationRef, success, resultCode, result)`,
   where `result` is an opaque string body:
   `success=true` means successful output, and `success=false` means error
   detail or log summary.
6. **Worker Event / Evidence Report**
   The worker may publish heartbeat, ready/draining/offline, local handler
   availability, load, health, or other bounded evidence. This should converge
   behind a distinct owner such as `WorkerEventReporter` or
   `WorkerEvidencePublisher`; it must not be confused with WorkerGroup
   capability declaration.
7. **Worker Command Intake / Ack**
   The worker may receive platform control commands such as drain, stop, or
   reload config, then execute/reject and acknowledge them. This is
   worker-control, not task dispatch.

Current model cleanup should align names and packages to these abilities. It
does not require implementing the future unified runtime now, but it must not
preserve models that contradict this shape.

## Target Shape

Recommended Java SDK worker surface after convergence:

```text
worker declaration
  WorkerGroupSpec
  WorkerEventBindingSpec
  WorkerSpec
  WorkerRegistrationResult
  WorkerPresenceResult

worker execution/session
  WorkerSession
  WorkerSessionDefinition or equivalent current-definition model
  WorkerInvocation(resultCorrelationRef, eventCode, input, sharedConfig)
  WorkerResult(success, resultCode, result)
  WorkerEventHandler

protocol drivers
  PollingWorkerSession / WebSocketWorkerSession as current concrete drivers,
  or successor protocol drivers that implement the same runtime abilities

worker wire edge, direct API only
  WorkerPollRequest / WorkerPollResult carrying resultCorrelationRef + WorkerInvocation only when manual submit is retained
  WorkerResultSubmission(resultCorrelationRef, success, resultCode, result)

result submit acknowledgement
  boolean or void; no public WorkerResultSubmitOutcome-style DTO
```

Models not in this target shape must be explicitly justified in their own owner
group or removed.

## SDK Public Model Budget

The worker SDK should stay small enough that a caller can understand the runtime
surface without learning old transport, task lifecycle, and command-control
shapes. Use this budget when deciding whether a model survives:

- **Handler API budget**:
  `WorkerInvocation`, `WorkerResult`, `WorkerEventHandler`.
  `WorkerInvocation` is the single worker execution item:
  `resultCorrelationRef`, `eventCode`, `input`, and `sharedConfig`.
  `resultCorrelationRef` is an opaque submit token, not task/message/attempt
  identity, scheduling input, business input, or lifecycle authority. It may be
  visible to direct worker code because the SDK intentionally avoids a second
  wire DTO plus handler projection, but handlers must not interpret it beyond
  round-tripping result submission.
  `WorkerInvocation` does not carry task ids, message ids, attempt ids, worker
  wire DTOs, transport facts, endpoint ids, or raw command models.
- **Managed session budget**:
  `WorkerSession` plus one current session definition/configuration model if
  it owns validation and startup semantics.
- **Protocol driver budget**:
  concrete polling/WebSocket drivers may exist, but they must implement the
  same declaration, intake, execution, result-submit, and evidence abilities.
  They must not create protocol-specific domain models. Worker-control command
  intake is not part of the Java SDK execution/session surface after this
  convergence.
- **Direct worker API wire budget**:
  `WorkerPollRequest`, `WorkerPollResult`, and `WorkerResultSubmission`.
  Direct poll responses may carry `resultCorrelationRef + WorkerInvocation`
  when manual submit remains supported, but that response shape is a direct API
  edge and must not become the handler invocation model. Avoid `Request` names
  when the model is the protocol-neutral result-submission fact shared by
  polling/direct API and WebSocket.
- **Runtime-internal budget**:
  at most one internal processing record may bind `WorkerInvocation` and
  `WorkerResult` while a session is executing and submitting the result. It
  should be package-private unless a public caller can act on it.
- **Diagnostics budget**:
  bounded failure/diagnostic records may exist only when they carry actionable
  session diagnostics. Do not keep one public failure DTO per callback,
  protocol, or request/response step.

`resultCorrelationRef` stays in scope for now as the opaque result-submit token.
This is not just hiding `messageId`: the current bridge encodes
`taskId/messageId/attemptId/attemptNo`, and direct worker result submission
needs a token that the worker can round-trip without owning task lifecycle
facts. Prefer it as a string field on `WorkerInvocation` and
`WorkerResultSubmission`; the public `ResultCorrelationRef` wrapper has been
deleted because it did not own validation beyond non-blank normalization. Do
not replace it with public `messageId`.

`WorkerResultSubmitOutcome` should not stay public if its only new fact is
`submitted` and the rest of the fields echo `workerId` or correlation already
known to the caller. Managed sessions should submit results and treat failure
as a bounded session diagnostic. Direct `WorkerClient.submitResult(...)` should
return `boolean` or `void`, depending on the final error-handling contract.

`WorkerResult` should converge away from `detail/errorCode/output`. The target
handler output shape is `success`, optional `resultCode`, and opaque string
`result`. `result` is not only an error log: for successful execution it is the
successful output body; for failed execution it is the error detail or log
summary.

`WorkerResultSubmission` is the neutral model that combines the
`resultCorrelationRef` field with the result facts for submission. It should be
shared by direct HTTP polling submit and WebSocket result sending. If WebSocket
needs a retry queue entry after frame encoding, that entry must be private and
named as an encoded frame artifact, such as `QueuedWebSocketResultFrame`, not as
a second result domain model.

`WorkerEventHandlers` should not remain public merely to wrap
`Map<String, WorkerEventHandler>`. If handler registry needs a type, it must own
real validation, duplicate policy, definition binding, or runtime lifecycle
semantics. Otherwise, keep the map internal to session/runtime builders.

`WorkerEventInvocation` should not remain public merely to wrap
`WorkerInvocation + WorkerResult + Throwable`. Handler execution outcome is a
runtime-internal concern unless it becomes a deliberate bounded diagnostic
contract.

`WorkerDispatchItem` should not survive as a second public model for worker
execution input. Managed poll/WebSocket intake should use the same
`WorkerInvocation(resultCorrelationRef, eventCode, input, sharedConfig)` shape
as direct poll. `input` currently remains JSON-object shaped with SDK
`MassPayload` helpers; converting it to an opaque string body is a separate
payload-shape convergence because it touches starter payload encoding, server
poll responses, external worker SDK handlers, and worker-pack capabilities.
`sharedConfig` may remain a `Map<String, Object>` for now because it is a
read-only config view rather than item body payload.

`MassPayload` is acceptable only as a handler payload ergonomics helper. It must
not become a transport envelope, worker wire DTO, task lifecycle model, or
runtime correlation carrier.

## Non-Goals

- Do not redesign transport delivery.
- Do not reintroduce task ids, message ids, attempts, lease tokens, transport
  commands, endpoint ids, read-only trace handles, or raw wire DTOs into
  handler-facing APIs. `resultCorrelationRef` may remain on `WorkerInvocation`
  and result submission as an opaque submit token, not as business input or task
  lifecycle truth.
- Do not build compatibility wrappers around old worker SDK models.
- Do not introduce polling-specific and WebSocket-specific public domain models
  when the protocol difference is only intake or result-submit mechanics.
- Do not add one public DTO per HTTP response, callback, or internal runtime
  transition when a primitive, bounded diagnostic, or internal record is enough.
- Do not split clients just to add pass-through facades. Split only when it
  expresses a real owner boundary and removes mixed semantics from `WorkerClient`.
- Do not treat public SDK test fixtures as compatibility requirements.

## Do Not Start With

Do not start by adding new wrappers such as `WorkerTopologyClient`,
`WorkerCommandClient`, or `WorkerEvidenceClient` while old models still remain
alive in `WorkerClient`.

First classify each public worker model and route by owner. Then either retarget
the model to a correct owner or delete it with all in-repo callers migrated in
the same slice.

## JSM-0 Public Model Inventory

Goal: classify every public worker SDK model and method.

Scope:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler`
- `WorkerClient` methods and their server routes
- method-level disposition for every `WorkerClient` method, including
  declaration, worker registration/presence, poll/submit, adapter topology,
  capability/state reports, and command poll/ack
- in-repo adopters in `integrations/`
- Java SDK docs and quickstarts

Acceptance:

- Each model is classified as one of:
  worker declaration, worker execution/session, opaque result, worker wire edge,
  protocol driver, worker-control, topology/admin setup, worker-local evidence,
  diagnostic, test fixture, or delete.
- Each surviving model is also classified as one of:
  public semantic contract, direct wire-edge DTO, runtime-internal record,
  bounded diagnostic, or delete.
- Any model that wraps one primitive, echoes request fields, or exists only to
  mirror a server JSON shape is marked for deletion unless the inventory states
  the user-visible invariant it owns.
- Inventory maps every model to one of the seven worker runtime abilities above
  or marks it for deletion.
- Inventory separates production callers from tests/docs.
- Inventory marks old models as convergence targets, not compatibility surfaces.
- Inventory records whether each model remains in `WorkerClient`, moves to a
  different owner surface, or is deleted.
- Inventory records whether each `WorkerClient` method remains on the worker
  execution client, moves to topology/admin setup, moves to worker-control,
  moves to worker evidence reporting, or is deleted.
- `resultCorrelationRef` is classified as the current opaque result-submit
  token field, not as a task/message public identity. A public
  `ResultCorrelationRef` wrapper is justified only if it owns validation beyond
  non-blank normalization.

Verification candidates:

```bash
rg -n "AdapterNode|NodeGroupBinding|WorkerCapabilityReport|WorkerStateReport|WorkerCommand|WorkerDispatchHandler|WorkerResultSink|ResultCorrelationRef|WorkerResultSubmitOutcome" sdk/xa-mass-java-sdk integrations xa-mass-server -g "*.java" -g "*.md"
```

## JSM-1 Remove Adapter Topology From Worker Execution SDK

Goal: remove old adapter-node topology models from the Java worker execution
surface.

Scope:

- `AdapterNodeSpec`
- `AdapterNodeRegistrationResult`
- `NodeGroupBindingSpec`
- `NodeGroupBindingResult`
- `WorkerClient.registerAdapterNode(...)`
- `WorkerClient.bindNodeGroup(...)`
- SDK docs and in-repo callers that use adapter-node setup through
  `mass.workers()`

Acceptance:

- `mass.workers()` no longer exposes adapter-node registration or
  node-group-binding methods.
- Normal worker session examples require only WorkerGroup declaration, worker
  identity, worker group id, attributes, handler registration, and session
  startup.
- If adapter topology setup is still needed, it is represented by an explicitly
  owned topology/admin setup surface or a separate roadmap. It is not kept as a
  worker execution API.
- No `AdapterNodeRegistrationResult` or `NodeGroupBindingResult` compatibility
  alias remains in `xa-mass-java-sdk`.

Verification candidates:

```bash
rg -n "AdapterNodeSpec|AdapterNodeRegistrationResult|NodeGroupBindingSpec|NodeGroupBindingResult|registerAdapterNode|bindNodeGroup" sdk/xa-mass-java-sdk integrations -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk,integrations/xa-mass-scenario-launcher -am test -Dtest=WorkerClientTest,WorkerSessionContractTest,WorkerScenarioRegistrarTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JSM-2 Separate Worker Evidence Reporting From Session Startup

Goal: prevent worker-local reports from masquerading as WorkerGroup capability
truth or session startup requirements.

Scope:

- `WorkerCapabilityReport`
- `WorkerCapabilityReportResult`
- `WorkerStateReport`
- `WorkerStateReportResult`
- `WorkerStateProjection`
- `WorkerClient.reportCapability(...)`
- `WorkerClient.reportState(...)`
- `PollingWorkerSession.start()` startup sequence
- WebSocket and polling session startup semantics

Acceptance:

- `PollingWorkerSession.start()` does not call `reportCapability` or
  `reportState`.
- Polling and WebSocket session startup have the same owner semantics:
  registration/session connection starts the worker session; evidence reporting
  is a distinct worker runtime ability, not a hidden side effect of one
  protocol driver.
- WorkerGroup declaration remains the only SDK worker capability declaration
  path.
- If periodic heartbeat or active worker-originated reports remain, they are
  owned by an explicit reporter/maintenance component and are configured as
  such. They are not modeled as scheduled platform tasks and are not bundled
  into dispatch intake.
- If worker-local evidence remains supported, the route/model is renamed or
  replaced by the owner chosen in
  `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`.
- No old `WorkerCapabilityReport*` compatibility alias remains after callers
  migrate.

Verification candidates:

```bash
rg -n "reportCapability|report-capability|WorkerCapabilityReport|availableEventCodes|reportState|WorkerStateReport" sdk/xa-mass-java-sdk integrations xa-mass-server -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk,xa-mass-server,integrations/xa-mass-scenario-launcher -am test -Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JSM-3 Decide Worker Command Public Surface

Goal: prevent worker command DTOs from being mistaken for task dispatch,
transport delivery, or session lifecycle models.

Scope:

- `WorkerCommand`
- `WorkerCommandPollRequest`
- `WorkerCommandPollResult`
- `WorkerCommandAck`
- `WorkerCommandAckResult`
- `WorkerClient.pollCommands(...)`
- `WorkerClient.ackCommand(...)`
- server external worker command poll/ack routes

Acceptance:

- Worker command models are either deleted from `xa-mass-java-sdk` or moved to
  an explicitly owned worker-control surface.
- Command intake/ack is not modeled as task dispatch or result delivery.
- No command DTO is reused as session lifecycle, transport delivery, or task
  item model.
- If retained, command model names and package express worker-control
  ownership, not generic worker execution.

Verification candidates:

```bash
rg -n "WorkerCommand|pollCommands|ackCommand|commands:poll|commands/.+:ack" sdk/xa-mass-java-sdk integrations xa-mass-server -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk,xa-mass-server -am test -Dtest=WorkerClientTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JSM-4 Converge Result Submission Contract

Goal: replace protocol/request-shaped result models with one result-submission
fact.

Scope:

- `WorkerResult` field shape
- `WorkerResultSubmission` neutral result-submit model
- `WorkerResultSubmitRequest` in Java SDK and embedded SDK
- `WorkerResultSubmitOutcome`
- direct `WorkerClient.submitResult(...)` return shape
- embedded SDK `PullWorkerSession` submit overloads
- server external worker result-submit request/route shape
- WebSocket result frame encoding and private queued-result shape
- worker-pack WebSocket samples and test helpers that preserve old result frame
  fields

Acceptance:

- `WorkerResult` uses `success/resultCode/result` rather than
  `detail/errorCode/output`.
- `WorkerResultSubmission` is the shared result-submit fact:

  ```text
  resultCorrelationRef
  success
  resultCode
  result
  ```

- `result` is documented as an opaque string body. `success=true` means result
  is successful output; `success=false` means result is error detail or log
  summary.
- `WorkerResultSubmitRequest` is deleted or replaced by
  `WorkerResultSubmission`. It must not remain as a parallel request-shaped
  alias for the same facts.
- Embedded SDK pull-worker result submit and server external worker result
  submit use the same `WorkerResultSubmission` shape. They do not keep a
  separate `detail/errorCode/output` DTO just because the route is HTTP.
- Old controller request classes are deleted or replaced. If a Spring/Jackson
  request record is still needed at the HTTP edge, it uses the new neutral
  result-submission shape and name, such as `WorkerResultSubmissionRequest`, and
  maps immediately to `WorkerResultSubmission`. `ExternalWorkerResultSubmitApiRequest`
  must not remain as a renamed compatibility shell for the old
  `detail/errorCode/output` contract.
- WebSocket no longer owns a separate result fact model. It either queues
  `WorkerResultSubmission` and encodes on send, or uses a private
  `QueuedWebSocketResultFrame` only after encoding. The private queued-frame
  artifact must not become public SDK/domain API.
- worker-pack WebSocket samples and test helpers are migrated to
  `WorkerResultSubmission` frame fields, or clearly classified as raw frame
  protocol fixtures. They must not keep `detail/errorCode/output` assertions as
  the recommended worker API shape.
- `WorkerResultSubmitOutcome` is deleted unless it gains actionable
  user-visible state beyond `submitted` and request echo fields. The preferred
  direct submit return is `boolean` or `void`.

Verification candidates:

```bash
rg -n "WorkerResultSubmitRequest|WorkerResultSubmitOutcome|detail|errorCode|output|OutboundResult|ExternalWorkerResultSubmitApiRequest" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker xa-mass-server/src/main/java/com/xa/mass/api/model/worker integrations/xa-mass-worker-pack -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,PullWorkerSessionTest,ExternalWorkerPollingApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JSM-5 Converge Worker Invocation Contract

Goal: remove duplicate invocation/wire DTOs while keeping result-submit
correlation opaque and non-authoritative.

Scope:

- `WorkerInvocation` field shape
- `WorkerDispatchItem`
- embedded SDK `PulledTaskDispatch` and `PulledTaskDispatchPayloadDecoder`
- starter `TaskDispatchPayloadEncoder` input encoding
- server `ExternalWorkerApiController.pollTasks(...)` response shape
- WebSocket dispatch frame decoding
- `MassPayload` boundary
- session processing around `WorkerInvocation` and `WorkerResult`

Acceptance:

- `WorkerInvocation` is the single public assigned worker invocation item:

  ```text
  resultCorrelationRef
  eventCode
  input
  sharedConfig
  ```

- `resultCorrelationRef` is only an opaque result-submit token. It is not task
  id, message id, attempt id, business input, scheduling input, trace/read API,
  retry authority, or worker lifecycle truth.
- `WorkerInvocation` does not contain task ids, message ids, attempt ids,
  transport facts, endpoint ids, or raw wire DTOs.
- This slice may keep `WorkerInvocation.input` as a JSON object with
  `MassPayload` helpers while removing duplicate DTOs. Opaque string input is a
  separate payload-shape slice because it touches starter encoding, server poll
  responses, external SDK handlers, and worker-pack capabilities.
- `WorkerInvocation.sharedConfig` may remain `Map<String, Object>` for now as a
  read-only config view. If it later becomes opaque string, that is a separate
  payload-shape slice.
- Managed polling/WebSocket sessions must not introduce a second public wire DTO
  for the same item. Any package-private processing record must own runtime
  execution/submission state only.
- `WorkerDispatchItem` is deleted. If session runtime still needs a processing
  record, it uses a new package-private type named for session processing, not
  the old wire DTO moved inward as downgraded residue.
- `PulledTaskDispatch` is deleted or replaced by the invocation contract.
  Embedded SDK pull sessions and server worker poll responses must not keep a
  second assigned-invocation DTO with the same fields.
- Direct/manual poll response carries `WorkerInvocation` items. It does not
  need a second `resultCorrelationRef + WorkerInvocation` wrapper.
- A later payload-shape slice should decide whether
  `TaskDispatchPayloadEncoder`, WebSocket dispatch frames, embedded SDK pull
  decoder, and server poll responses preserve `input` as a string body.
- `MassPayload` is documented and guarded as handler payload ergonomics only,
  not as the invocation model.

Verification candidates:

```bash
rg -n "WorkerDispatchItem|PulledTaskDispatch|DispatchContext|taskId|attemptId|leaseToken|rawItem" sdk/xa-mass-java-sdk sdk/xa-mass-embedded-sdk integrations xa-mass-server -g "*.java" -g "*.md"
rg -n "WorkerDispatchItem|PulledTaskDispatch|TaskPullResult|TaskPullStatus|PulledTaskDispatchPayloadDecoder" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,PullWorkerSessionTest,ExternalWorkerPollingApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JSM-6 Collapse Handler And Session Helper Residue

Goal: remove helper models that remain after the result and invocation contracts
have converged.

Scope:

- any public `ResultCorrelationRef` wrapper
- `WorkerDispatchHandler`
- `WorkerEventHandlers`
- `WorkerEventInvocation`
- `WorkerResultSink`
- broad `WorkerSession*Failure` DTOs
- Java SDK docs and tests that preserve old names

Acceptance:

- There is one public handler callback shape for `WorkerInvocation ->
  WorkerResult`.
- `WorkerDispatchHandler` is deleted; session builders use
  `WorkerEventHandler` directly.
- Handler registration does not require a public wrapper around
  `Map<String, WorkerEventHandler>`.
- Handler execution outcome does not leak as public `WorkerEventInvocation`.
- `resultCorrelationRef` is allowed only in session/direct result-submit
  ownership. A public `ResultCorrelationRef` wrapper should be deleted or
  replaced by a package-private validation type with an explicit session/result
  owner; it must not survive as the old public one-field wrapper.
- `WorkerResultSink` is deleted or made private runtime test infrastructure. A
  handler package type must not become the public owner of result correlation.
- No public model survives only to wrap one primitive, mirror one HTTP JSON
  response, or preserve old tests.
- Diagnostic records are bounded and do not recreate old dispatch-context
  shapes.
- No alias remains only to preserve old builder examples or old tests.

Verification candidates:

```bash
rg -n "WorkerDispatchHandler|WorkerResultSink|DispatchContext|taskId|messageId|attemptId|leaseToken|rawItem" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
rg -n "WorkerEventHandlers|WorkerEventInvocation|ResultCorrelationRef" sdk/xa-mass-java-sdk/src/main/java -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Suggested Implementation Order

1. Execute `JSM-0` first and produce a method/model disposition table. Do not
   delete models before classifying every `WorkerClient` method and public
   worker DTO by owner.
2. Execute `JSM-2` next because it fixes the biggest semantic split between
   polling and WebSocket workers: session startup versus worker-originated
   evidence reporting. This also clarifies whether future heartbeat or active
   worker reports are a session-maintenance ability or a separate reporter.
3. Execute `JSM-1` after session/evidence ownership is clear, then move or
   delete adapter topology setup from the worker execution client. Scenario
   launcher and worker-pack examples must follow the new owner instead of
   keeping topology calls on `mass.workers()`.
4. Execute `JSM-3` only after `WorkerClient` method ownership is known. Worker
   command intake/ack should either become an explicit worker-control surface
   or leave the Java worker SDK.
5. Execute `JSM-4` next because result submission is a public contract rewrite,
   not helper cleanup.
6. Execute `JSM-5` after result submission is stable. It rewires dispatch intake
   and manual/direct poll edges into one `WorkerInvocation` shape while keeping
   `resultCorrelationRef` opaque and non-authoritative.
7. Execute `JSM-6` last. Helper DTO cleanup should be a residue pass after the
   direct wire edge, managed session path, invocation contract, and
   result-submit correlation owner are already settled.

Do not invert this order by deleting the `resultCorrelationRef` token or
replacing it with `messageId`. The current result-submit bridge still requires
an opaque correlation token. The public `ResultCorrelationRef` wrapper has been
removed, but the token field must remain until result ingress proves a
different correlation contract.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- every public worker SDK model is classified and either kept with a correct
  owner, moved/renamed to the correct owner, or deleted
- `WorkerClient` no longer mixes worker execution with adapter topology setup,
  old capability/state-report residue, and command-control residue as one undifferentiated
  mainline
- no old model remains as a compatibility alias after in-repo callers migrate
- no public worker SDK model remains only to wrap one primitive, echo request
  fields, or mirror an internal runtime step
- `WorkerResultSubmitOutcome` is gone or replaced by an explicitly justified
  current contract
- worker result facts converge to `success/resultCode/result`; no public worker
  result model keeps the old `detail/errorCode/output` triple as its primary
  shape
- direct polling/HTTP result submit and WebSocket result sending share
  `WorkerResultSubmission(resultCorrelationRef, success, resultCode, result)`
  as the neutral result-submit fact
- handler invocation uses
  `WorkerInvocation(resultCorrelationRef, eventCode, input, sharedConfig)` as
  the single worker execution item; the correlation field is only an opaque
  result-submit token and not lifecycle authority
- direct/manual poll edges, if retained, return `WorkerInvocation` items rather
  than a second wrapper around `resultCorrelationRef + WorkerInvocation`
- `WorkerDispatchItem` and `PulledTaskDispatch` are gone from the public and
  mainline internal worker execution path; any replacement processing record has
  a new session/runtime owner name instead of preserving the old DTO inwardly
- duplicate worker invocation DTOs are gone; `input` may remain JSON-object
  shaped until a separate payload-shape slice changes starter payload encoding,
  WebSocket dispatch frame decoding, embedded SDK pull decoding, server worker
  poll responses, and worker-pack capabilities together
- embedded SDK pull-worker result submit and server external worker result
  submit use the same result-submission shape
- worker-pack samples/tests no longer preserve `detail/errorCode/output` as the
  recommended WebSocket worker result shape
- `resultCorrelationRef` remains only as an opaque invocation/result-submit
  token; it must not become task/message identity, business payload,
  trace/read API, retry authority, or worker lifecycle semantics
- polling and WebSocket worker code no longer define separate public domain
  models for the same declaration, runtime init, intake, execution, result
  submit, or evidence report abilities
- polling and WebSocket worker session startup no longer disagree on whether
  worker evidence reporting is an implicit startup side effect
- Java SDK docs show the recommended worker path through WorkerGroup
  declaration, worker/session definition, handler registration, managed result
  submission, and session startup
- architecture guards prevent reintroducing task lifecycle authority,
  transport-owned models, adapter-node topology, or old worker capability reports
  into the worker execution surface

## Guard Candidates

```bash
rg -n "AdapterNodeRegistrationResult|NodeGroupBindingResult|WorkerCapabilityReport|WorkerStateReport|WorkerDispatchItem|PulledTaskDispatch|WorkerDispatchHandler|WorkerResultSink|WorkerResultSubmitOutcome|DispatchContext" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
rg -n "ResultCorrelationRef|WorkerEventInvocation|WorkerEventHandlers" sdk/xa-mass-java-sdk/src/main/java -g "*.java"
rg -n "WorkerResultSubmitRequest|detail|errorCode|output|OutboundResult|ExternalWorkerResultSubmitApiRequest" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker xa-mass-server/src/main/java/com/xa/mass/api/model/worker integrations/xa-mass-worker-pack -g "*.java" -g "*.md"
rg -n "taskId|messageId|attemptId|leaseToken|DeliveryCommand|AdapterNodeRegistrationResult|NodeGroupBindingResult" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

Expected final state: no matches for removed symbols; public
`ResultCorrelationRef` wrapper has no matches unless explicitly justified as an
internal validation type; handler invocation uses the single
`WorkerInvocation` item; result submission uses `WorkerResultSubmission`;
task/message/attempt facts do not appear in worker handler/session packages.
