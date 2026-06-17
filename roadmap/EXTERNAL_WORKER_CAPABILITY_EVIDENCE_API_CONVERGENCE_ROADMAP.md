# External Worker Capability Evidence API Convergence Roadmap

Status: linked owner-decision roadmap created from archived
`doc/archive/sdk/2026-06-17_JAVA_SDK_WORKER_SESSION_MODEL_CONVERGENCE_ROADMAP.md`
JWS-4. It is also a dependency of
`JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md` JWR-6.

## Summary

The current external worker route
`POST /worker-api/v1/workers/{workerId}:report-capability` mixes three ideas:

- worker-local handler availability through `availableEventCodes`
- scheduling attributes supplied by a worker process
- wording that can be misread as WorkerGroup capability truth

That mix should not block the Java SDK `WorkerSession` convergence. It is not a
`WorkerSession` lifecycle method and must not become shared session startup
policy. This roadmap owns the API decision for whether the route is removed,
renamed, narrowed to worker-local evidence, or replaced by a clearer external
worker evidence contract.

## Owner Review

WorkerGroup capability truth belongs to worker-runtime/server declaration
paths. External worker sessions may only report worker-local evidence such as
handler availability, scheduling attributes, readiness, or bounded state.

`xa-mass-java-sdk` may call the current route as a typed worker API while the
route exists, but it does not own the platform meaning of reported capability.
`WorkerSession` must stay free of report-capability/report-state methods.

## Current Code Sites

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerClient.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerCapabilityReport.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/PollingWorkerSession.java`
- `xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java`
- `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCapabilityAuthority.java`
- `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerReportOwner.java`
- `integrations/xa-mass-scenario-launcher/src/main/java/com/xa/mass/scenario/WorkerScenarioRegistrar.java`

## Boundary Decision To Make

Choose one target:

1. Remove `:report-capability` if worker-local handler availability is not a
   supported external evidence surface.
2. Rename/narrow it to a worker-local evidence route, for example handler
   availability plus scheduling attributes, without WorkerGroup capability
   wording.
3. Replace it with a clearer evidence API and migrate Java SDK/server/runtime
   callers in one no-compatibility slice.

Do not preserve both old and new routes as parallel production paths after the
decision lands.

## Non-Goals

- Do not change `WorkerSession` to call a shared capability policy.
- Do not let worker-reported `availableEventCodes` expand WorkerGroup event
  bindings.
- Do not change transport delivery, endpoint evidence, or selected-worker
  dispatch.

## EWC-0 Inventory

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

## EWC-1 API Decision And Migration

Scope:

- Apply the chosen route/model decision across Java SDK, server, worker-runtime,
  integrations, and docs.
- Update or remove tests that protect old wording as stable capability truth.
- Keep `WorkerSession` free of report-capability/report-state methods.

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
