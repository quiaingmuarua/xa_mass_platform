# External Worker Capability Evidence API Convergence Roadmap

Status: superseded. Do not execute this roadmap as an active implementation
plan. Current Java SDK code has already moved from `:report-capability` /
`WorkerCapabilityReport` wording to `reportHandlerEvidence(...)` and
`reportRuntimeEvidence(...)`. Remaining worker event/evidence reporter
decisions are owned by
`JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md` JWR-6.

## Summary

Historical context:

The old external worker route
`POST /worker-api/v1/workers/{workerId}:report-capability` mixed three ideas:

- worker-local handler availability through `availableEventCodes`
- scheduling attributes supplied by a worker process
- wording that can be misread as WorkerGroup capability truth

That mix should not block Java SDK worker runtime convergence. It is not a
`WorkerRuntime` lifecycle method and must not become shared runtime startup
policy. This file now remains only to explain the superseded decision context.
Current implementation and future work should use JWR-6 for evidence/reporter
shape.

## Owner Review

WorkerGroup capability truth belongs to worker-runtime/server declaration
paths. External worker sessions may only report worker-local evidence such as
handler availability, scheduling attributes, readiness, or bounded state.

`xa-mass-java-sdk` may expose typed worker evidence APIs, but it does not own
the platform meaning of reported capability. Worker runtimes must stay free of
hidden report-capability/report-state startup methods.

## Historical Code Sites

These names were the original inventory surface and may no longer exist:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerCapabilityReport.java`
- `POST /worker-api/v1/workers/{workerId}:report-capability`

## Superseding Boundary Decision

Do not preserve both old and new report-capability routes/models as parallel
production paths. Current Java SDK evidence names are
`reportHandlerEvidence(...)` and `reportRuntimeEvidence(...)`; future reporter
shape is owned by JWR-6.

## Non-Goals

- Do not change `WorkerRuntime` to call a shared capability policy.
- Do not let worker-reported `availableEventCodes` expand WorkerGroup event
  bindings.
- Do not change transport delivery, endpoint evidence, or selected-worker
  dispatch.

## Historical EWC-0 Inventory

Scope:

- Java SDK worker report DTOs and clients
- external worker Controller route and request DTOs
- worker-runtime report authority/projection
- scenario launcher and worker-pack adopters
- public docs mentioning report-capability or `availableEventCodes`

Acceptance:

- Inventory separates WorkerGroup capability declaration, worker-local handler
  availability, scheduling attributes, state/readiness evidence, and polling
  startup behavior.
- Inventory separates current production callers from tests/docs.

## Historical EWC-1 API Decision And Migration

Scope:

- Apply the chosen route/model decision across Java SDK, server, worker-runtime,
  integrations, and docs.
- Update or remove tests that protect old wording as stable capability truth.
- Keep `WorkerRuntime` free of report-capability/report-state methods.

Acceptance:

- There is exactly one production route/model for the selected worker-local
  evidence concept, or the concept is removed.
- WorkerGroup declaration remains the event capability truth.
- Java SDK docs do not describe worker-local evidence as WorkerGroup
  capability declaration.

## Verification Candidates

```bash
rg -n "reportCapability|report-capability|WorkerCapabilityReport|availableEventCodes" sdk xa-mass-server xa-mass-worker-runtime integrations -g "*.java" -g "*.md"
./mvnw -q -pl sdk/xa-mass-java-sdk,xa-mass-server,xa-mass-worker-runtime,integrations/xa-mass-scenario-launcher -am test -DskipTests
```

These commands are historical residue scans, not active completion proof for
JWR-6.
