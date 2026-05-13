# XA Mass Platform Verified Runbook

Last updated: 2026-05-12

Status: current verified runtime runbook.

This runbook records verified runtime facts only. It is not an architecture
essay, API reference, or changelog.

Use this file when you need to boot the app, run a smoke flow, or choose a
focused regression command.

Use [TESTING_INDEX.md](./TESTING_INDEX.md) for current test-layer truth,
minimum verification rules, and CI gate truth. Use
[TESTING_BASELINE.md](./TESTING_BASELINE.md) for lane placement,
[../xa-mass-engine/README.md](../xa-mass-engine/README.md) and
[../xa-mass-testing/README.md](../xa-mass-testing/README.md) for module-owned
test detail, [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) for
endpoint shapes, [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) for
lifecycle rules, and [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) for trace
semantics.

This file does not define lane ownership, suite layering, or CI gate policy.

## 1. Verified Entry

Current runtime entry:

- Spring Boot app: `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java`
- SDK entry: `xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java`
- Embedded runtime composition: `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
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
java -cp "xa-mass-server/target/classes:xa-mass-worker-pack/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/socket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

Windows guidance:

- Prefer module `target/classes` plus `logs/runtime-libs/*`.
- Avoid very long expanded classpaths; Windows command-line limits can create false missing-class errors.

Default runtime facts:

- `server.port=8088` serves the backend-hosted control console and JSON APIs.
- `mass.websocket.port=18088` serves the current WebSocket transport adapter endpoint when the transport server is enabled.
- `mass.socket.port=18089` serves the current socket transport adapter endpoint when `mass.socket.enabled=true`.
- default `dev` startup seeds a demo catalog in-process when `mass.demo.bootstrap.enabled=true`:
  - projects: `demoApp`, `demoOps`
  - events: `demo.dispatch`, `demo.dispatch.gb`
  - submitter credentials: `demo-app-key`, `demo-ops-key`, `demo-admin-key`
  - workers: `36` demo workers across `us,gb,de,fr,sg,jp`
  - tasks: `12` demo tasks with per-project mixed states (`RUNNING`, `NEW`, `PAUSED`, `BLOCKED`) and `1500` items each by default
- default `dev` startup also auto-starts embedded sample WebSocket clients against `ws://localhost:${mass.websocket.port}/ws`, so the SDK-registered demo workers can go `ONLINE` and consume the seeded demo tasks
- external sample worker launcher is not part of the default dev demo path; enable `sample.worker.auto-start=true` explicitly only when validating the separate cross-process sample shell
- Sample adapter clients connect through their adapter-local addresses, including `ws://localhost:18088/ws` for WebSocket and `tcp://localhost:18089` for socket when enabled.
- Pull-style workers can also run without the WebSocket transport server through `MassSdkApplication.pullWorker(...)`.
- `sample.client.task-result-status=FAILED` forces failed task result write-back for regression tests.

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
- message projection: `INIT -> ASSIGNED -> RUNNING -> SUCCESS` for success-mode sample clients
- message projection: `INIT -> ASSIGNED -> RUNNING -> FAILED` when `sample.client.task-result-status=FAILED`
- terminal tasks must be read as `status=TERMINAL` plus `terminalReason`
- task detail response returns shell and aggregate state only
- public API no longer exposes task-item snapshot, task-item detail, or attempt-detail query routes

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
- `RuleBasedTaskWorkerMatchingStrategy` is the verified default.
- `batchSize` is a per-worker cap for each dispatch round.
- `minRequiredWorkerCount` is a real `READY -> RUNNING` gate.
- unmatched `READY` tasks and refill `RUNNING` tasks are delayed-retried instead of being orphaned.
- persisted compatibility message rows are reused; dispatch creates attempt history and updates compatibility latest-attempt projections.

Result write-back and closure:

- Pull-style workers can fetch `TaskDispatchItem` work from the polling channel and submit the same logical result semantics without server push.
- `RuntimeTaskResultIngestChannel` writes results through the engine result-ingest facade (`TaskResultIngestFacade` / `TaskManagerResultIngestFacade`).
- callbacks must resolve a unique active attempt projection.
- retryable failure closes the attempt, resets the logical message to `INIT`, and does not publish logical-final semantics.
- success, retry exhaustion, expiry, and manual terminal drain close the logical message.
- once all engine runtime work items are final, the engine's internal task-progress convergence path closes any non-final task to `TERMINAL`.

Worker and worker-context truth:

- `Worker.status` is the worker model status on the control-plane side. Dispatch
  online/reachability truth comes from transport presence consumed through
  `WorkerReachabilityView`.
- worker lock truth lives in `WorkerStorage` and is read through `WorkerManager.isLocked(...)`; the
  server JDBC adapter intentionally keeps lock churn process-local instead of
  persisting it in the control-plane DB.
- `WorkerContext` is optional; stateless workers are verified for tasks without context-specific routing.
- `WorkerContext.workerId` is the owner truth for context attachment.

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

Explicit compatibility residue/audit supporting lanes:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EngineProjectionResidueSuite,EngineProjectionAuditSuite test
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerProjectionResidueSuite,ServerProjectionAuditSuite test
```

Cross-language external worker samples:

```bash
./scripts/run-external-worker-samples.sh
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

Scheduled/manual CI workflow:

- `.github/workflows/perf-smokes.yml`

SDK transport load harness:

Polling worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling
```

WebSocket worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket
```

Socket worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=socket
```

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

Artifacts:

- `perf`: `xa-mass-testing/target/perf-reports/`
- SDK transport harness: `xa-mass-testing/target/concurrency-reports/`
- `chaos`: `xa-mass-testing/target/chaos-reports/`

## 6. Focused Regression Gate

Focused command used for current high-signal scheduling-side runtime coverage:

```bash
mvn -pl xa-mass-server -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,TaskApiTargetedWorkerDebugIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Coverage: worker/context routing, stateless workers, delayed availability, targeted worker debug, control-console routing, and matching-rule support checks around the representative scheduling path.
