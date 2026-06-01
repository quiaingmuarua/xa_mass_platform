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
- do not add `xa-mass-java-sdk` unless worker-pack has real public HTTP
  worker-control boilerplate to remove, or a later public realtime Java client
  contract to consume

## Start Here

- `src/main/java/com/xa/mass/workerpack/sample/starter/SampleWorkerProcessStarter.java`
- `src/main/java/com/xa/mass/workerpack/sample/client/SampleWorkerWebSocketClient.java`
- `src/main/java/com/xa/mass/workerpack/sample/command/runtime/SampleCommandRuntime.java`

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

Current direction is tracked by
`doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md`.

Current audit: worker-pack does not duplicate Java raw HTTP client calls for
`/worker-api/v1` topology or worker-control routes. It discovers dev-shell
sample workers through embedded `MassSdkApplication` and owns realtime
WebSocket/socket frame clients plus sample fault behavior. IJS-3 in the
archived `doc/archive/integrations/2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md`
is therefore conditional
rather than an immediate dependency migration.

Future convergence target:

- worker-pack may provide curated, real worker groups, but those groups are not
  privileged platform state; they must register, go online, receive dispatch,
  and report results through public worker APIs or SDK sessions.
- worker-pack should keep sample command/fault behavior local, but generic
  event dispatch handling should converge on the Java SDK's transport-neutral
  worker handler runtime once realtime sessions can consume it.
- WebSocket/socket clients should become transport/session adapters into that
  handler runtime rather than owning business dispatch in transport-specific
  frame handlers.
