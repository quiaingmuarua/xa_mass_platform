# xa-mass-worker-pack

Status: current worker-pack owner README.

## Role

- official builtin, sample, and dev worker capabilities
- sample worker clients, worker launchers, and worker-side command runtime
- sample-only bootstrap surfaces that support dev-shell acceptance flows

## Boundaries

- keep runtime composition SDK-first; worker-pack registers through normal platform APIs
- keep external process references under `integrations/samples/`
- do not let worker-pack redefine `xa-mass-server` as the product shell
- use `xa-mass-java-sdk` only for real public HTTP worker-control/session
  paths; do not use it to move worker-pack command/fault behavior into the SDK

## Package And Dependency Ownership

Current first step is package-level separation inside this artifact. A Maven
split is deferred until package boundaries stop being enough evidence.

Package ownership:

- `com.xa.mass.workerpack.tool.*`: production capability runtime and SDK-backed
  bootstrap. It must not import `workerpack.sample.*`, `com.xa.mass.sdk.*`, or
  transport internals.
- `com.xa.mass.workerpack.sample.*`: dev-shell bootstrap, command runtime, and
  fault harness. It may use embedded runtime APIs because it is active server
  E2E harness support, not the public external SDK capability path.
- `src/test/java`: verification fixtures for both package families.

Dependency ownership:

- `xa-mass-java-sdk`: production external worker/task entry point for
  SDK-backed capabilities such as `tool.geo.lookup`.
- `xa-mass-embedded-sdk`: dev-shell/sample bootstrap and legacy worker-control
  harness only; its transitive transport dependencies do not define public
  worker-pack capability shape.
- `spring-boot-starter-web`: sample bootstrap controller.
- `gson`: sample WebSocket command/fault frame parsing.
- `lettuce-core`: sample/dev Redis wiring inherited from worker-pack harness.
- `spring-boot-starter-test`: module tests.

## Start Here

- `src/main/java/com/xa/mass/workerpack/sample/starter/SampleWorkerProcessStarter.java`
- `src/main/java/com/xa/mass/workerpack/sample/client/SampleWorkerWebSocketClient.java`
- `src/main/java/com/xa/mass/workerpack/sample/command/runtime/SampleCommandRuntime.java`

## Transport Stance

Worker-pack no longer owns a Java socket demo client. Socket proof belongs to
Node black-box fixtures, socket adapter tests, and scheduled/manual transport
diagnostics. The remaining raw WebSocket client is worker-pack fault/command
harness code, not the public Java SDK worker-session recommendation.

## Worker Capabilities

Worker-pack capabilities are production-oriented handlers plus SDK-backed
bootstrap helpers. They register through the same public worker/task paths used
by external actors; server startup does not seed privileged workers.

`tool.geo.lookup` is a provider-configurable SDK-backed capability.

- event code: `tool.geo.lookup`
- worker group: `worker-pack.tools.geo`
- input: `query` or `city`
- output: `city`, `countryCode`, `timeZone`, `currency`, `latitude`,
  `longitude`, `provider`, `simulated`

`GeoLookupProvider` owns provider lookup. `DeterministicGeoLookupProvider`
keeps CI deterministic, while `GeoLookupWorkerPack` owns the external Java SDK
bootstrap: it declares the worker group, adapter node, node binding, worker
identity, online state, polling session, and result reporting through
`MassPlatform` / `PollingWorkerSession`.

`ProbeWorkerPack` carries scenario-derived local probe capabilities:

- `probe.phone.metadata` on WorkerGroup `phone-device-probe`
- `probe.url.dns` on WorkerGroup `public-probe`
- `probe.csv.validate` on WorkerGroup `data-quality-probe`
- `probe.json.schema` on WorkerGroup `data-quality-probe`

The handler is a Java SDK `WorkerEventHandler`, so business event handling is
independent of the polling transport. The phone-device Stage-2 proof uses
`ProbeWorkerPack.phoneDevicePolling(...)` to run real external polling workers
through the Java SDK and proves WorkerGroup/worker-attribute selection without
server-side worker seeding.

The dev command route `ToolCommandRoutes.tool.geo.lookup` delegates to the same
tool implementation to avoid a second result shape.

## Sample Fault State

Sample worker fault injection is worker-pack local state. It must not become an
engine model, transport protocol, or runtime owner.

Current implemented surface:

- `SampleClientState` stores legacy `mock.*` controls plus a reusable
  `faultProfile` snapshot.
- `SampleWorkerFaultProfile` defines deterministic profile names and primitive
  settings for delay, result drop, duplicate result, stall, malformed result,
  late result, invalid result identity, and disconnect phase.
- `fault.state.get`, `fault.execution.profile`, `fault.execution.delay`,
  `fault.execution.stall`, `fault.result.drop`, `fault.result.duplicate`,
  `fault.result.late`, `fault.result.malformed`, `fault.result.identity`,
  `fault.transport.disconnect`, `fault.worker.state.flap`, and `fault.reset`
  are registered as sample command routes through `CommandRegistry`.
- the first behavior-bearing slice applies fault profile delay, stall, and
  result-drop/duplicate/late/malformed/identity/transport-disconnect settings
  when the sample worker builds a normal task result.
- `fault.execution.stall(until=forever|lease-expiry)` suppresses result submit
  so the platform must recover through lease expiry; `until=ms` adds bounded
  delay and still submits normally.
- `fault.result.malformed(kind=missing_message_id|invalid_status|invalid_payload)`
  mutates the next normal task result frame after command ACK, so the platform
  sees bad result evidence without changing engine or transport owners.
- `fault.result.late(delayPastLeaseMs=N)` adds worker-side delay before normal
  result submit; the harness is responsible for choosing a delay that lands
  after the scenario lease boundary.
- `fault.result.identity(kind=wrongTask|wrongMessage|wrongWorker|wrongLease)`
  mutates result correlation fields before normal result submit.
- `fault.transport.disconnect(phase=before_receive|after_receive|before_result|after_result)`
  closes the sample worker connection around normal task result submission.
- `fault.worker.state.flap(state=AVAILABLE|DEGRADED|DRAINING|OFFLINE)`
  is a stateless one-shot command that reports through
  `WorkerControlOperations.reportWorkerState`; repeated flap loops belong to the
  harness, not the worker.
- Default state is disabled and preserves stable worker behavior.

Not yet implemented:

- capacity flap behavior; current public capability report does not own
  `maxConcurrentWork`, so this should wait for an explicit capacity owner
  surface instead of faking it through attributes
- applying fault profiles to capability report behavior
- matrix runner selection by scenario id

Those belong to the worker-fault roadmap and should be added through normal
sample-worker command paths, not by mutating engine or transport internals.

## Java External SDK Convergence

The external SDK/worker-pack hardening work is recorded in
`doc/archive/integrations/2026-06-02_INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`.
The prior SDK convergence work is recorded in
`doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md`.

Current audit: worker-pack does not duplicate Java raw HTTP client calls for
`/worker-api/v1` topology or worker-control routes. Real worker capability
paths use `xa-mass-java-sdk`; dev-shell sample worker discovery still uses
`xa-mass-embedded-sdk` `MassSdkApplication`, and raw WebSocket frame handling
remains only as active command/fault E2E harness substrate. The retired Java
socket demo path is documented in
`doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_INVENTORY.md`.

Current hardening baseline:

- worker-pack may provide curated WorkerGroup capabilities, but those groups
  are not privileged platform state; they must register, go online, receive
  dispatch, and report results through public worker APIs or SDK sessions.
- scenario-derived probe capabilities such as `probe.phone.metadata`,
  `probe.url.dns`, `probe.csv.validate`, and `probe.json.schema` live as
  worker-pack capability implementations, not server-owned hidden workers.
- worker-pack keeps sample command/fault behavior local, while generic event
  dispatch handling uses the Java SDK's transport-neutral worker handler
  runtime for SDK-backed capabilities.
- normal WebSocket capability workers may use Java SDK WebSocket sessions after
  SDK lifecycle hardening covers close, reconnect, queue-full, and queued-result
  terminal outcomes.
- WebSocket fault-harness code should become a transport/session adapter into
  the SDK handler runtime only when the SDK has an explicit hook for that
  behavior; raw harness code is not public Java SDK usage.
