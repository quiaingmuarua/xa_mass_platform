# Java SDK Worker Public Model Convergence Roadmap

Status: proposed direction document.

## Summary

`sdk/xa-mass-java-sdk` currently exposes several generations of worker-facing
models through one `com.xa.mass.client.worker` package and one `WorkerClient`.
That package mixes worker execution, WorkerGroup capability declaration,
adapter-node topology setup, worker-local evidence reports, result submission,
and worker command control.

This roadmap pins the public-model cleanup goal: old worker SDK models must keep
converging to the current owner model. They should not be preserved as
compatibility aliases, downgraded helper DTOs, or parallel live tracks.

Target rule:

```text
Every public worker SDK model must be one of:
  current worker execution/session contract,
  current WorkerGroup/worker declaration contract,
  current read-only dispatch trace / result-submit contract,
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

Current Java SDK public models include these mixed groups:

- worker execution/session:
  `WorkerSession`, `PollingWorkerSession`, `WebSocketWorkerSession`,
  `WorkerInvocation`, `WorkerResult`, `WorkerEventHandler`,
  `WorkerEventHandlers`
- worker dispatch/result wire:
  `WorkerPollRequest`, `WorkerPollResult`, `WorkerDispatchItem`,
  `ResultCorrelationRef`, `WorkerResultSubmitRequest`,
  `WorkerResultSubmitOutcome`. `ResultCorrelationRef` is currently only a
  one-field wrapper around a string correlation value, and
  `WorkerResultSubmitOutcome` mostly echoes request-side identity with a
  submit flag.
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
  generic wire/runtime envelope. `WorkerDispatchItem` is a worker API wire item,
  not the handler-facing invocation model.

Related active direction:

- `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md` owns the
  larger worker runtime ability model.
- `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md` owns the
  `report-capability` route decision.
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` already states that worker-facing
  invocation/result APIs must not expose task lifecycle authority.

This roadmap is narrower: it owns public Java SDK worker model cleanup and
prevents old models from remaining as compatibility residue.

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
read-only item trace handle
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
   intake. Polling and WebSocket are only different submit transports.
   `messageId` may be exposed to the worker as a read-only trace/item handle
   because it has no business routing semantics. The public handler/session
   model must not expose task lifecycle authority such as attempt ids, lease
   tokens, retry decisions, or runtime barriers.
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
  WorkerInvocation(messageId as read-only trace handle, eventCode, input, sharedConfig)
  WorkerResult
  WorkerEventHandler

protocol drivers
  PollingWorkerSession / WebSocketWorkerSession as current concrete drivers,
  or successor protocol drivers that implement the same runtime abilities

worker wire edge, direct API only
  WorkerPollRequest / WorkerPollResult / WorkerDispatchItem
  WorkerResultSubmitRequest

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
  `WorkerInvocation.messageId` is allowed as a read-only trace/item handle. It
  must not become business input, scheduling input, retry authority, or a way
  for worker code to drive task lifecycle.
- **Managed session budget**:
  `WorkerSession` plus one current session definition/configuration model if
  it owns validation and startup semantics.
- **Protocol driver budget**:
  concrete polling/WebSocket drivers may exist, but they must implement the
  same declaration, intake, execution, result-submit, evidence, and command
  abilities. They must not create protocol-specific domain models.
- **Direct worker API wire budget**:
  `WorkerPollRequest`, `WorkerPollResult`, `WorkerDispatchItem`,
  `WorkerResultSubmitRequest`. These are HTTP/wire-edge models, not
  handler-facing runtime models.
- **Runtime-internal budget**:
  at most one internal dispatch/correlation record may bind a wire item,
  read-only item trace identity, `WorkerInvocation`, and `WorkerResult`. It
  should be package-private unless a public caller can act on it.
- **Diagnostics budget**:
  bounded failure/diagnostic records may exist only when they carry actionable
  session diagnostics. Do not keep one public failure DTO per callback,
  protocol, or request/response step.

`ResultCorrelationRef` should not stay public if it only wraps a string. Do not
hide a readable `messageId` behind a new public correlation concept merely to
avoid showing task-item trace identity. Use `messageId` as the read-only trace
handle when that is sufficient, or keep any richer correlation as a
package-private runtime value object inside session/result processing.

`WorkerResultSubmitOutcome` should not stay public if its only new fact is
`submitted` and the rest of the fields echo `workerId` or correlation already
known to the caller. Managed sessions should submit results and treat failure
as a bounded session diagnostic. Direct `WorkerClient.submitResult(...)` should
return `boolean` or `void`, depending on the final error-handling contract.

`WorkerEventHandlers` should not remain public merely to wrap
`Map<String, WorkerEventHandler>`. If handler registry needs a type, it must own
real validation, duplicate policy, definition binding, or runtime lifecycle
semantics. Otherwise, keep the map internal to session/runtime builders.

`WorkerEventInvocation` should not remain public merely to wrap
`WorkerInvocation + WorkerResult + Throwable`. Handler execution outcome is a
runtime-internal concern unless it becomes a deliberate bounded diagnostic
contract.

`WorkerDispatchItem` must stay at the wire edge. It may carry `messageId`,
`eventCode`, `input`, and `sharedConfig` for direct poll responses. If a
temporary `resultCorrelationRef` remains during convergence, it is a wire-edge
or internal result-submit detail, not a handler-facing model. Handler code
should only see `WorkerInvocation`.

`MassPayload` is acceptable only as a handler payload ergonomics helper. It must
not become a transport envelope, worker wire DTO, task lifecycle model, or
runtime correlation carrier.

## Non-Goals

- Do not redesign transport delivery.
- Do not reintroduce task ids, attempts, lease tokens, transport commands,
  endpoint ids, or raw wire DTOs into handler-facing APIs. `messageId` is the
  only allowed task-item identity in the handler API, and only as read-only
  trace metadata.
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

## JSM-2 Remove Or Rename Worker Capability/State Report Models

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

Acceptance:

- `PollingWorkerSession.start()` does not call `reportCapability` or
  `reportState`.
- WorkerGroup declaration remains the only SDK worker capability declaration
  path.
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

## JSM-4 Collapse Result/Handler/Wire Helper Residue

Goal: remove duplicate result-submit, handler, session, and wire helper models
that do not express a real owner boundary.

Scope:

- `ResultCorrelationRef` public exposure
- `WorkerResultSubmitOutcome`
- `WorkerResultSubmitRequest` boundary
- `WorkerDispatchHandler`
- `WorkerEventHandlers`
- `WorkerEventInvocation`
- `WorkerResultSink`
- `WorkerDispatchItem` boundary and naming
- `MassPayload` boundary
- broad `WorkerSession*Failure` DTOs
- direct `WorkerClient.submitResult(...)` return shape
- Java SDK docs and tests that preserve old names

Acceptance:

- There is one public handler callback shape for `WorkerInvocation ->
  WorkerResult`.
- Handler registration does not require a public wrapper around
  `Map<String, WorkerEventHandler>` unless that wrapper owns real validation or
  runtime definition semantics.
- Handler execution outcome does not leak as public `WorkerEventInvocation`
  unless it is intentionally retained as a bounded diagnostic contract.
- `WorkerDispatchItem` is contained to direct worker API wire edges and does not
  enter handler-facing contracts or integration business logic.
- `MassPayload` is documented and guarded as handler payload ergonomics only.
- Result submission hooks, if retained, live in session/runtime ownership and
  use the read-only item trace handle without exposing task lifecycle authority
  or raw wire items to handler-facing contracts.
- `ResultCorrelationRef` is not a public handler/session contract if it only
  wraps a string. It is either deleted, replaced by `messageId` where a
  readable item trace handle is sufficient, or moved to a package-private
  runtime-internal value object for richer result-submit correlation.
- `WorkerResultSubmitOutcome` is deleted unless it gains actionable
  user-visible state beyond `submitted` and request echo fields. The preferred
  direct submit return is `boolean` or `void`.
- `WorkerResultSubmitRequest` is contained to direct worker API calls. Managed
  polling/WebSocket sessions should build result submission internally from the
  handler `WorkerResult` and runtime correlation.
- No public model survives only to wrap one primitive, mirror one HTTP JSON
  response, or preserve old tests.
- Diagnostic records are bounded and do not recreate old dispatch-context
  shapes.
- No alias remains only to preserve old builder examples or old tests.

Verification candidates:

```bash
rg -n "WorkerDispatchHandler|WorkerEventHandlers|WorkerEventInvocation|WorkerResultSink|ResultCorrelationRef|WorkerResultSubmitOutcome|DispatchContext|taskId|attemptId|leaseToken|rawItem" sdk/xa-mass-java-sdk integrations -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- every public worker SDK model is classified and either kept with a correct
  owner, moved/renamed to the correct owner, or deleted
- `WorkerClient` no longer mixes worker execution with adapter topology setup,
  capability-report residue, and command-control residue as one undifferentiated
  mainline
- no old model remains as a compatibility alias after in-repo callers migrate
- no public worker SDK model remains only to wrap one primitive, echo request
  fields, or mirror an internal runtime step
- `WorkerResultSubmitOutcome` is gone or replaced by an explicitly justified
  current contract; `ResultCorrelationRef` is not exposed as public session or
  handler API unless it owns a real invariant beyond a non-blank string
- polling and WebSocket worker code no longer define separate public domain
  models for the same declaration, runtime init, intake, execution, result
  submit, evidence report, or command ack abilities
- Java SDK docs show the recommended worker path through WorkerGroup
  declaration, worker/session definition, handler registration, managed result
  submission, and session startup
- architecture guards prevent reintroducing task lifecycle authority,
  transport-owned models, adapter-node topology, or worker capability reports
  into the worker execution surface

## Guard Candidates

```bash
rg -n "AdapterNodeRegistrationResult|NodeGroupBindingResult|WorkerCapabilityReport|WorkerDispatchHandler|WorkerEventInvocation|WorkerResultSubmitOutcome|ResultCorrelationRef|DispatchContext" sdk/xa-mass-java-sdk/src/main/java -g "*.java"
rg -n "taskId|attemptId|leaseToken|DeliveryCommand|AdapterNodeRegistrationResult|NodeGroupBindingResult" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

Expected final state: no matches for removed symbols, and only explicitly
allowlisted matches for successor owner surfaces.
