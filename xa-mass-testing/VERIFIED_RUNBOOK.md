# XA Mass Platform Verified Runbook

Last updated: 2026-06-09

Status: current verified runtime runbook.

This runbook records verified runtime facts only. It is not an architecture
essay, API reference, or changelog.

Use this file when you need to boot the app, run a smoke flow, or choose a
focused regression command.

Use [TESTING_INDEX.md](../doc/TESTING_INDEX.md) for current test-layer truth,
minimum verification rules, and CI gate truth. Use
[../xa-mass-engine/README.md](../xa-mass-engine/README.md) and
[README.md](./README.md) for module-owned
test detail, [INTERNAL_API_REFERENCE.md](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md) for
endpoint shapes, [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md) for
lifecycle rules, and [TRACE_CONTRACT.md](../doc/TRACE_CONTRACT.md) for trace
semantics.

This file does not define lane ownership, suite layering, or CI gate policy.

## 0. Proof Class Reading

Use [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md) for the authoritative
proof class model. Runtime commands in this runbook should be read through
three classes:

| Question | Proof class | Runbook evidence |
| --- | --- | --- |
| Can it be used? | `Product / API Capability Proof` | packaged server startup, admin CLI login/env init, API-key setup, task producer calls, worker APIs, Java SDK launchers, and result reads through supported external surfaces |
| Can it be wrong? | `Policy & Safety Correctness Proof` | engine deterministic suites as primary scheduling/policy proof, plus representative negative auth probes, fail-closed route checks, server E2E, and trace analyzers that show unsafe bind/auth/mutation paths are rejected |
| Can it withstand this exact condition? | `Scoped Operational Resilience Proof` | chaos, perf, soak, restart, lease-expiry, stale replay, worker churn, Redis/runtime recovery, and high-volume runtime pressure evidence scoped to a named scenario, fault/load, duration, and pass/fail oracle |

End-to-end means the evidence crosses real process/API/runtime boundaries. It
does not by itself prove every policy, authorization, route-permission, or
scale invariant. A happy-path smoke can prove that a user path works, but it is
not proof that unsafe paths fail closed. Chaos/perf/soak wording must not imply
general resilience unless the command actually exercises and asserts that
specific condition.

Capability proof must name which family it exercised:
`operator-admin-session`, `task-producer-api-key`, or `worker-api-key`. Negative
permission, wrong credential-family, scope mismatch, CSRF, fixture-header, and
impersonation cases belong to `authorization-no-bypass-safety`, not to the
capability happy path.

Correct credential/session plus correct route family, scope, project/event, and
request shape is an authorized-positive Product/API capability proof. If that
call is rejected by auth, CSRF, an interceptor, route mapping, or
credential-family handling, treat it as a first-layer capability failure.

## 1. Verified Entry

Current runtime entry:

- Spring Boot app: `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java`
- SDK entry: `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java`
- Embedded runtime composition: `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
- Java baseline: JDK 21 / `maven.compiler.release=21`

Current runnable path includes the reactor modules declared in the root
`pom.xml`, including the `platform_infra/*`, `transport/*`, SDK, testing,
worker-pack, and server modules. Treat the root reactor as the module source of
truth when you need the full list.

Adapter artifacts map to source modules by reactor path: `xa-mass-transport-polling` lives under `transport/polling-adapter`, `xa-mass-transport-websocket` under `transport/websocket-adapter`, and `xa-mass-transport-socket` under `transport/socket-adapter`.

## 2. Startup

Run from repo root:

```bash
java -version
./mvnw -DskipTests compile
cd frontend && corepack pnpm build && cd ..
java -cp "xa-mass-server/target/classes:integrations/xa-mass-worker-pack/target/classes:sdk/xa-mass-embedded-sdk/target/classes:sdk/xa-mass-embedded-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/socket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

Compose verification entry:

```bash
./mvnw -pl xa-mass-server -am -DskipTests package
docker compose up redis server
```

The compose entry runs the already-built server jar with the `prod` profile:

- SQLite file storage keeps control-plane task/worker/rule truth in a compose
  volume.
- Redis stores engine work/result runtime plus transport delivery and presence
  runtime truth.
- Fixed namespaces use the `xa:mass:compose:*` prefix so compose keys are easy
  to inspect and remove.

Use it for local distributed/restart verification. Compose intentionally does
not compile the reactor; rebuild the jar on the host after code changes. It is
not a production image or deployment contract. Stop services without deleting
state:

```bash
docker compose down
```

Reset compose storage and runtime state:

```bash
docker compose down -v
```

Windows guidance:

- Prefer module `target/classes` plus `logs/runtime-libs/*`.
- Avoid very long expanded classpaths; Windows command-line limits can create false missing-class errors.

Default runtime facts:

- `server.port=8088` serves the backend-hosted control console and JSON APIs.
- `mass.websocket.port=18088` serves the current WebSocket transport adapter endpoint when the transport server is enabled.
- `mass.socket.port=18089` serves the current socket transport adapter endpoint when `mass.socket.enabled=true`.
- default `dev` startup may register control-console catalog events, projects, and submitters as metadata, but it does not create demo tasks, WorkerGroups, workers, or task items.
- optional local/demo data is created by external launchers or SDK clients through public task and worker APIs.
- external sample worker launcher is not part of clean server startup; enable `sample.worker.auto-start=true` explicitly only when validating the separate cross-process sample shell.
- Worker-pack embedded sample clients connect through
  `ws://localhost:18088/ws` for the WebSocket fault/command harness. Socket
  adapter proof uses Node fixtures, transport tests, or scheduled/manual
  transport diagnostics rather than a worker-pack Java socket client.
- Pull-style workers can also run without the WebSocket transport server through `MassSdkApplication.pullWorker(...)`.
- `sample.client.task-result-status=FAILED` forces failed task result write-back for regression tests.

Optional external dev scenario against a running server:

```bash
node integrations/samples/dev/scenario/launch-workers.mjs
```

The launcher uses public sample bootstrap, task, and worker APIs. It starts
managed realtime sample workers, registers a larger polling phone-device worker
group for matching review, and creates seed tasks externally instead of through
server startup.

Manual registration without starting managed realtime sample processes:

```bash
node integrations/samples/dev/scenario/launch-workers.mjs --register-only
```

This mode is intended for quick console population after `XaMassServerApplication`
is already running. It registers catalog metadata, rules, WorkerGroups, adapter
nodes, workers, online API-polling workers, and sample tasks through public HTTP
APIs, then exits. Realtime workers remain registered but offline until an
external worker process connects.

## 3. Smoke Checks

HTTP:

```bash
curl -i http://127.0.0.1:8088/
curl -i http://127.0.0.1:8088/tasks
curl -i http://127.0.0.1:8088/resources/workers
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
```

Current WebSocket adapter port:

```bash
nc -zv 127.0.0.1 18088
```

- Backend-hosted control console routes return successfully.
- Legacy `/status*` and `/config` console aliases redirect locally to the primary SPA routes.
- If the transport server is enabled, the current WebSocket adapter port is open.
- Sample workers appear online when auto-start is enabled.

Create a task shell, append items, then seal:

```bash
curl -s -X POST http://127.0.0.1:8088/api/v1/tasks \
  -H 'X-Mass-Api-Key: demo-app-key' \
  -H 'Content-Type: application/json' \
  -d '{"project":"demoApp","sharedConfig":{"textContent":"smoke"},"userId":"demo-app-user","executionSpec":{"batchSize":1,"maxRuntimeSeconds":0}}'

curl -s -X POST http://127.0.0.1:8088/api/v1/tasks/{taskId}/items \
  -H 'X-Mass-Api-Key: demo-app-key' \
  -H 'Content-Type: application/json' \
  -d '{"eventCode":"demo.dispatch","items":[{"target":"smoke-target-001"},{"target":"smoke-target-002"}]}'

curl -i -X POST http://127.0.0.1:8088/api/v1/tasks/{taskId}/commands \
  -H 'X-Mass-Api-Key: demo-app-key' \
  -H 'Content-Type: application/json' \
  -d '{"command":"SEAL"}'
```

Approve it:

```bash
curl -i -X POST "http://127.0.0.1:8088/api/v1/tasks/{taskId}/commands" \
  -H 'X-Mass-Api-Key: demo-app-key' \
  -H 'Content-Type: application/json' \
  -d '{"command":"APPROVE"}'
```

Inspect task:

```bash
curl -s http://127.0.0.1:8088/api/v1/tasks/{taskId} \
  -H 'X-Mass-Api-Key: demo-app-key'
```

- `Task`: `NEW -> READY -> RUNNING -> TERMINAL`
- review item materialization: `INIT -> ASSIGNED -> RUNNING -> SUCCESS` for success-mode sample clients
- review item materialization: `INIT -> ASSIGNED -> RUNNING -> FAILED` when `sample.client.task-result-status=FAILED`
- terminal tasks must be read as `status=TERMINAL` plus `terminalReason`
- task detail response returns shell and aggregate state only
- public API no longer exposes task-item snapshot, task-item detail, or attempt-detail query routes

External worker CLI contract proof:

```bash
scripts/proof/external-worker-http-contract.sh
```

This script intentionally uses only `curl` and `jq` against a running server. It
registers the dev-only `external-proof-polling-worker-001`, marks it online,
reports capability/state, creates and approves an `external.proof.echo` task,
polls work, submits a result, acknowledges a worker command, and marks the
worker offline. It is the preferred local smoke when validating the
repo-external polling worker API through real HTTP rather than browser or UI
automation.

Worker-pack SDK capability proof:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkerPackGeoLookupExternalSdkIntegrationTest,PhoneDeviceWorkerPackExternalSdkIntegrationTest test
```

This verifies that worker-pack's `tool.geo.lookup` capability can register a
worker group, worker, polling session, task, item, result, and result readback
through the Java SDK/public HTTP path. It also verifies the phone-device
Stage-2 worker-pack proof where two external polling workers join the same
WorkerGroup and scheduling selects the worker whose attributes satisfy the
task's fingerprint requirement. The server fixture starts without preseeded
workers; catalog metadata is test setup, not privileged worker startup.

Packaged platform confidence proof:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile durable-local
```

Windows local proof should use Git Bash explicitly from PowerShell:

```powershell
$env:MASS_OPERATOR_PASSWORD='ops-admin'
& 'C:\Program Files\Git\bin\bash.exe' xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
```

The WindowsApps `bash.exe` WSL launcher is not the supported shell for this
packaged proof because it can run Linux Java against Windows-built artifacts.

This is the current packaged-process confidence gate. It packages the server,
admin CLI, and Java scenario launchers; starts the real server jar; requires
session operator auth; disables fixture-header auth; asserts the actual
`/api/v1/auth/config` response reports `authMode=session`,
`sessionCookieSupported=true`, and `operatorHeaderSupported=false`; seeds only
the minimal operator credential; runs `xa-mass-admin env init` over HTTP;
checks unauthenticated operator, invalid task API-key, and invalid worker
API-key requests fail closed with the expected `ApiResponse.code/msg` envelope;
starts the Java SDK worker launcher as a background process; runs the Java SDK
task launcher to create and append work;
executes task approval through `xa-mass-admin task command`; and waits for a
visible success result through the Java SDK result verifier. It then runs
`xa-mass-admin api health` and writes `apiHealth.routeTimings` into the summary
so route reachability and local latency are captured without browser
inspection. Failure artifacts and `summary.json` are written under
`xa-mass-testing/target/platform-confidence/`.

Durable-local requires Redis on `localhost:6379`. The active-profile confidence
script uses explicit profile arguments and does not prove the no-arg startup
contract.

Packaged worker read health proof:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-worker-read-health-smoke.sh --profile memory-local
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-worker-read-health-smoke.sh --profile durable-local
```

Windows local proof:

```powershell
$env:MASS_OPERATOR_PASSWORD='ops-admin'
& 'C:\Program Files\Git\bin\bash.exe' xa-mass-testing/scripts/run-worker-read-health-smoke.sh --profile memory-local
```

This proof is separate from platform confidence. It starts a packaged server,
runs `xa-mass-admin env init`, creates workerId-bound credentials for 100
workers, registers those workers through the Java worker launcher with
`--register-api-online-only`, and then runs `xa-mass-admin api health`. The
summary is written under `xa-mass-testing/target/worker-read-health/` with
`workerFixture` scale metadata. Proof summary counts the run as worker-read
`scale-contention-evidence` only when the run passes and
`workerFixture.workerCount >= 100`.

Packaged default startup and durable restart proof:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-server-default-startup-smoke.sh
```

This smoke starts the packaged server jar with no application arguments from an
isolated working directory. It proves default `durable-local` startup reaches
`/actuator/health`, remains alive after health, avoids `Application run failed`,
uses the default relative SQLite path
`./data/xa-mass-sqlite/xa_mass.db`, logs in through the seeded operator
credential, and then succeeds again against the same SQLite file after restart.
The output summary classifies the Redis namespace as `default` or
`ci-isolated`; current CI uses default localhost Redis namespace proof.

Proof summary artifact:

```bash
node xa-mass-testing/scripts/write-proof-summary.mjs --job local
```

The writer reads existing surefire XML and lane-local JSON artifacts under
`target/` and writes `xa-mass-testing/target/proof-summary/summary.json`. It
emits project proof class definitions and marks recognized evidence with
`proofClass`, `proofLines`, `proofQuestion`, `evidenceShape`, `gateType`,
`credentialRouteFamilies`, `authorizedPositiveChecks`, and `claimScope`. It is
CI evidence only and does not replace the proof registry or the owning reports.
Perf/soak release
interpretation is sourced from
`xa-mass-testing/proof/perf-soak-release-evidence.json`, which keeps hard
threshold signals separate from trend-only latency/throughput values.
Use scoped inputs such as `--test-report-dir`, `--platform-confidence-dir`, and
`--perf-dir` for job-clean evidence; unscoped local runs can include stale
`target/` reports from earlier commands.

## 4. Runtime Facts To Trust

Task create/update:

- Shell create supports `userId`, `project`, `sharedConfig`, `executionSpec`, and optional opaque `sourceRef`.
- `POST /api/v1/tasks` is the public shell-create route.
- `taskName` is server-derived and persisted on the shell; callers do not provide it.
- `eventCode` is not task shell truth; append requests declare capability at the batch level or per item.
- The public task API and control-console read models do not define a dedicated routing-code field.
- Work items are appended explicitly through `POST /api/v1/tasks/{taskId}/items`.
- Append requires explicit `eventCode` either on the batch request or on each item payload.
- Unknown or retired create fields fail fast.
- Update is shell-only and supports `userId`, `project`, and `sharedConfig`.
- Updates are allowed only while the task is `NEW` or `BLOCKED`.

Assignment and dispatch:

- `TaskWorkerAssignListener` performs worker matching and delegates policy to `TaskWorkerMatchingStrategy`.
- `RuleBasedTaskWorkerMatchingStrategy` is the verified default implementation,
  not the platform's final matching model. Treat `TaskWorkerMatchingStrategy`
  and the engine matching policy surface as the strategic boundary.
- `batchSize` is a per-worker cap for each dispatch round.
- `minRequiredWorkerCount` is a real `READY -> RUNNING` gate.
- unmatched `READY` tasks and refill `RUNNING` tasks are delayed-retried instead of being orphaned.
- runtime work identity is reused; dispatch creates attempt/lease evidence and server review materialization may update latest-attempt fields.

Result write-back and closure:

- Pull-style workers can fetch `PulledTaskDispatch` work from the polling channel and submit the same logical result semantics without server push.
- `RuntimeTaskResultIngestChannel` writes results through the engine result-ingest facade (`TaskResultIngestFacade` / `TaskManagerResultIngestFacade`).
- callbacks must resolve a unique active runtime lease/attempt identity.
- retryable failure closes the attempt, resets the logical message to `INIT`, and does not publish logical-final semantics.
- success, retry exhaustion, expiry, and manual terminal drain close the logical message.
- once all engine runtime work items are final, the engine's internal task-progress convergence path closes any non-final task to `TERMINAL`.

Worker scheduling truth:

- `Worker.status` is the worker model status on the control-plane side. Dispatch
  online/reachability truth comes from transport presence consumed through
  `WorkerReachabilityView`.
- worker lock truth is runtime/resource state and must stay out of the
  control-plane DB. The server JDBC adapter intentionally keeps lock churn
  process-local instead of persisting it in durable storage.
- worker capability truth comes from `WorkerGroup.eventBindings`. Worker
  registration owns worker identity plus group/node membership; routing also
  uses worker scheduling attributes/tags and runtime reachability/load facts.
- `WorkerContext` model/storage/API surfaces are retired. It is not engine
  scheduling truth, and new verification should not depend on context-specific
  routing or context-id evidence.

Open-ended and targeted worker debug:

- `Task.intakeStatus` is the append-window truth.
- `POST /api/v1/tasks/{taskId}/items` appends inputs only while intake is open.
- `POST /api/v1/tasks/{taskId}/commands` with `{"command":"SEAL"}` closes intake and resumes normal terminal convergence.
- worker debug/test flows use `/internal/v1/debug/task-invocations:sync` for one-item sync execution, or the normal shell-create + append flow for standard task runs.

## 5. Core Acceptance Commands

Scheduling correctness core gate:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EngineSchedulingCoreSuite test
```

Representative server scheduling E2E gate:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerSchedulingE2eSuite test
```

Non-scheduling lifecycle and result convergence gate:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerLifecycleResultConvergenceSuite test
```

Explicit compatibility and review read-model supporting lanes:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerReviewReadModelResidueSuite,ServerReviewReadModelAuditSuite test
```

Retired engine support suites must not be used as runtime/result proof.
Engine runtime/result proof now belongs to `EngineSchedulingCoreSuite` and
`EngineKernelConvergenceSuite`; review/export materialization proof belongs to
server-local review tests.

Cross-language external worker samples:

```bash
./scripts/run-external-worker-samples.sh
```

Worker-pack SDK capability proof:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkerPackGeoLookupExternalSdkIntegrationTest,PhoneDeviceWorkerPackExternalSdkIntegrationTest test
```

Testing-module perf load model:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner
```

Testing-module perf smoke bundle:

```bash
xa-mass-testing/scripts/run-perf-smokes.sh
```

Current bundle contents:

- `TaskWorkloadMixSmokeRunner`
- `TaskInteractiveRetryWakeupSmokeRunner`

Use the bundle when the goal is to validate current workspace runtime behavior after engine perf/concurrency changes. It refreshes sibling artifacts for the active workspace and then runs with a direct runtime classpath, so the smoke results come from the current workspace state instead of whatever was last installed manually.

The bundle pins a more stable interactive retry-delay JVM property for the retry-wakeup smoke so cross-environment timing is less fragile.
It also defaults the workload-mix runner to the stable
`workload-mix-slow-bulk-interactive-isolation` release-evidence row; override
with `MASS_WORKLOAD_SMOKE_SCENARIO_ID` only when deliberately comparing another
scenario.

Scheduled/manual CI workflow:

- `.github/workflows/perf-smokes.yml`

SDK transport load harness:

Polling worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling
```

Polling mode-specific scenario row, verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.load.forceExit=false -Dmass.sdk.load.scenarioId=sdk-transport-load-polling -Dmass.sdk.load.tasks=1 -Dmass.sdk.load.messagesPerTask=1 -Dmass.sdk.load.workers=1 -Dmass.sdk.load.batchSize=1 -Dmass.sdk.load.pollBatchSize=1 -Dmass.sdk.load.timeoutSeconds=20 -Dmass.sdk.load.processingDelayMillis=0 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.concurrency.SdkTransportLoadRunner
```

Verified evidence:

- report: `xa-mass-testing/target/concurrency-reports/sdk-transport-load-polling-*.json`
- `scenarioId=sdk-transport-load-polling`
- top-level `transport=polling`
- `actualTransport=polling`
- `config.scenarioId=sdk-transport-load-polling`
- `runtimeBackend=memory`
- `workerProfile=NORMAL`
- `faultShape=delivery-diagnostics`
- one terminal task with `ALL_MESSAGES_SUCCEEDED`

WebSocket transport churn scenario row, verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.load.forceExit=false -Dmass.sdk.load.scenarioId=sdk-transport-load-websocket-churn -Dmass.sdk.load.tasks=1 -Dmass.sdk.load.messagesPerTask=1 -Dmass.sdk.load.workers=1 -Dmass.sdk.load.batchSize=1 -Dmass.sdk.load.workerProcessingThreads=1 -Dmass.sdk.load.processingDelayMillis=20 -Dmass.sdk.load.timeoutSeconds=25 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.concurrency.SdkTransportLoadRunner
```

Verified evidence:

- report: `xa-mass-testing/target/concurrency-reports/sdk-transport-load-websocket-*.json`
- `scenarioId=sdk-transport-load-websocket-churn`
- top-level `transport=websocket`
- `actualTransport=websocket`
- `config.scenarioId=sdk-transport-load-websocket-churn`
- `runtimeBackend=memory`
- `workerProfile=FLAKY_TRANSPORT`
- `faultShape=transport-connection-churn`
- `workerMetrics.transportChurnDisconnects=1`
- `workerMetrics.transportChurnReconnects=1`
- one terminal task with `ALL_MESSAGES_SUCCEEDED`
- `deliveryQueue.directFailedItems=0`

WebSocket worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket
```

Socket worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=socket
```

SDK polling scheduling soak noisy mixed-result row:

Verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.soak.forceExit=false -Dmass.soak.scenarioId=polling-soak-noisy-mixed-result -Dmass.soak.durationSeconds=1 -Dmass.soak.workerCount=1 -Dmass.soak.initialWorkerCount=1 -Dmass.soak.groupCount=1 -Dmass.soak.eventCodeCount=1 -Dmass.soak.submitRatePerSecond=1 -Dmass.soak.messagesPerTask=5 -Dmass.soak.pollBatchSize=1 -Dmass.soak.processingDelayMillis=0 -Dmass.soak.trace=false compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.soak.SdkPollingSchedulingSoakRunner
```

Verified evidence:

- report: `xa-mass-testing/target/soak-reports/polling-scheduling-soak-*.json`
- `config.scenarioId=polling-soak-noisy-mixed-result`
- `proof.matrixProfile.scenarioId=polling-soak-noisy-mixed-result`
- `workerProfile=NOISY`
- `faultShape=noisy-mixed-result`
- `failureProfile=every-5`
- `processingJitterSeed=20260602`
- one terminal mixed-result task with five visible results, four successes, and one synthetic failure

The scheduled/manual fast soak script now defaults to this same scenario id via
`MASS_SOAK_SCENARIO_ID=polling-soak-noisy-mixed-result`, while still allowing
explicit JVM properties to tune duration, late-worker, and rate parameters.

SDK WebSocket disconnect/reconnect chaos harness:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner
```

SDK WebSocket lease-expiry redispatch chaos harness:

Verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner
```

SDK polling Redis runtime owner restart/reconnect chaos harness:

Verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.forceExit=false -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingRedisRestartRecoveryChaosRunner
```

Verified evidence:

- report: `xa-mass-testing/target/chaos-reports/sdk-polling-redis-restart-recovery-chaos-*.json`
- `runtimeBackend=redis`
- task reaches `TERMINAL` with `ALL_MESSAGES_SUCCEEDED`
- final receipt shows `attempts=2` and `retryCount=1`
- runtime counters show one successful final result and zero active leases
- trace `droppedCount=0`
- analyzer `lease-expiry-redispatch` returns `ok=true`

Current PR chaos scenario-id gate:

The current `chaos-smokes` gate resolves these scenario ids through
`WorkerFaultScenarioCli` and source-guards their runner classes before
execution:

- `polling-lease-expiry-redispatch`
- `websocket-lease-expiry-redispatch`
- `websocket-late-stale-result-replay`

Local direct `WorkerFaultScenarioCli` verification passed for all three rows
with `mass.sdk.chaos.timeoutSeconds=30` and
`mass.sdk.chaos.processingDelayMillis=10`. GitHub Actions runs the same gate in
`.github/workflows/maven.yml` and uploads `target/chaos-reports`.

Dropped-result/retry scenario alias:

Verified from `xa-mass-testing/` module directory:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.forceExit=false -Dmass.sdk.chaos.timeoutSeconds=30 -Dmass.sdk.chaos.processingDelayMillis=10 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.workerfault.WorkerFaultScenarioCli -Dexec.args=fault.dropped-result-retry
```

Verified evidence:

- report: `xa-mass-testing/target/chaos-reports/sdk-polling-lease-expiry-redispatch-chaos-*.json`
- `scenarioId=fault.dropped-result-retry`
- `transport=polling`
- `runtimeBackend=memory`
- `workerProfile=STALL_LEASE_TAKEOVER`
- `faultShape=dropped-result-retry`
- task reaches `TERMINAL` with `ALL_MESSAGES_SUCCEEDED`
- trace `droppedCount=0`
- analyzer `lease-expiry-redispatch` returns `ok=true`

Artifacts:

- `perf`: `xa-mass-testing/target/perf-reports/`
- SDK transport harness: `xa-mass-testing/target/concurrency-reports/`
- polling soak: `xa-mass-testing/target/soak-reports/`
- `chaos`: `xa-mass-testing/target/chaos-reports/`

## 6. Focused Regression Gate

Prefer suite-owned focused gates over hand-maintained class lists. The current
high-signal scheduling-side runtime coverage is:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EngineSchedulingCoreSuite test
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerSchedulingE2eSuite test
```

Coverage: worker scheduling/routing, delayed availability, targeted worker
debug, control-console routing, matching-rule support checks, allocation,
resource admission, refill, and representative Boot-shell wiring.

When a change touches matching rules, trace evidence, or storage/evaluator
plumbing, add the owning focused tests named by
[TESTING_INDEX.md](../doc/TESTING_INDEX.md) or the module README. Do not restore
retired WorkerContext-focused tests as scheduling proof.
