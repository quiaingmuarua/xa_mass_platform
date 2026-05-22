# xa-mass-worker-pack

Status: current worker-pack owner README.

## Role

- official builtin, sample, and dev worker capabilities
- sample worker clients, worker launchers, and worker-side command runtime
- sample-only bootstrap surfaces that support dev-shell acceptance flows

## Boundaries

- keep runtime composition SDK-first; worker-pack registers through normal platform APIs
- keep external process references under `samples/`
- do not let worker-pack redefine `xa-mass-server` as the product shell

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
  and disconnect phase.
- `fault.state.get`, `fault.execution.profile`, `fault.execution.delay`,
  `fault.execution.stall`, `fault.result.drop`, `fault.result.duplicate`, and
  `fault.reset` are registered as sample command routes through
  `CommandRegistry`.
- the first behavior-bearing slice applies fault profile delay, stall, and
  result-drop/duplicate settings when the sample worker builds a normal task
  result.
- `fault.execution.stall(until=forever|lease-expiry)` suppresses result submit
  so the platform must recover through lease expiry; `until=ms` adds bounded
  delay and still submits normally.
- Default state is disabled and preserves stable worker behavior.

Not yet implemented:

- malformed result, transport disconnect, state flap, and capacity flap behavior
- applying fault profiles to worker state/capability report behavior
- matrix runner selection by scenario id

Those belong to the worker-fault roadmap and should be added through normal
sample-worker command paths, not by mutating engine or transport internals.
