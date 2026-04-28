# XA Mass Platform Verified Runbook

Last updated: 2026-04-27

This runbook records verified runtime facts only. It is not an architecture essay, API reference, or changelog.

Use this file when you need to boot the app, run a smoke flow, or choose a focused regression command. Use [TESTING_BASELINE.md](./TESTING_BASELINE.md) for test-lane placement, [../xa-mass-engine/README.md](../xa-mass-engine/README.md) and [../xa-mass-testing/README.md](../xa-mass-testing/README.md) for module-owned test detail, [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) for endpoint shapes, [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) for lifecycle rules, and [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) for trace semantics.

## 1. Verified Entry

Current runtime entry:

- Spring Boot app: `xa-mass-server/src/main/java/com/xa/mass/mock/XaMassServerApplication.java`
- SDK entry: `xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java`
- Embedded runtime composition: `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
- Java baseline: JDK 21 / `maven.compiler.release=21`

Current module set from the root reactor:

- `xa-mass-base`
- `xa-mass-transport-api`
- `xa-mass-transport-polling`
- `xa-mass-transport-runtime`
- `xa-mass-engine`
- `xa-mass-transport-websocket`
- `xa-mass-sdk-api`
- `xa-mass-sdk`
- `xa-mass-testing`
- `xa-mass-server`

The WebSocket adapter artifact is `xa-mass-transport-websocket`; its sources live under `transport/websocket-adapter`, and its Java package namespace is `com.xa.mass.transport.websocket.*`.

## 2. Startup

Run from repo root:

```bash
java -version
./mvnw -DskipTests compile
cd frontend && corepack pnpm build && cd ..
java -cp "xa-mass-server/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

Windows guidance:

- Prefer module `target/classes` plus `logs/runtime-libs/*`.
- Avoid very long expanded classpaths; Windows command-line limits can create false missing-class errors.

Default runtime facts:

- `server.port=8088` serves the backend-hosted control console and JSON APIs.
- `mass.websocket.port=18088` serves the current WebSocket transport adapter endpoint when the transport server is enabled.
- `mass.socket.port=18089` serves the current socket transport adapter endpoint when `mass.socket.enabled=true`.
- Default local/dev startup auto-starts mock worker clients when `sample.client.auto-start=true`.
- Mock adapter clients connect through their adapter-local addresses, including `ws://localhost:18088/ws` for WebSocket and `tcp://localhost:18089` for socket when enabled.
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
- Mock workers appear online when auto-start is enabled.

Create a sealed task:

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","sharedConfig":{"textContent":"smoke"},"userId":"agent","inputs":[{"target":"smoke-target-001"},{"target":"smoke-target-002"}],"batchSize":1,"defaultMsgMaxRetryCount":3,"openEnded":false,"maxRuntimeSeconds":0}'
```

Approve it:

```bash
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
```

Inspect task and messages:

```bash
curl -s http://127.0.0.1:8088/status/api/tasks/{taskId}
curl -s http://127.0.0.1:8088/status/api/tasks/{taskId}/messages
```

- `Task`: `NEW -> READY -> RUNNING -> TERMINAL`
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> SUCCESS` for success-mode mock clients
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> FAILED` when `sample.client.task-result-status=FAILED`
- terminal tasks must be read as `status=TERMINAL` plus `terminalReason`
- task detail response includes `items` from persisted `TaskMsg.input` and `stateValidation`
- message read model exposes `input`, `output`, `latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId`

## 4. Runtime Facts To Trust

Task create/update:

- Create supports `userId`, `project`, `taskName`, `eventCode`, `mode`, `payloadType`, `sharedConfig`, `inputs`, `batchSize`, `defaultMsgMaxRetryCount`, `openEnded`, and `maxRuntimeSeconds`.
- `/status/api/tasks` is the single HTTP task-create route; when `eventCode` is present it uses the SDK mode/payload-aware create path.
- The public task API and control-console read models do not define a dedicated routing-code field.
- `inputs` must be non-empty and materializes persisted `TaskMsg.input` rows.
- Unknown or retired create fields fail fast.
- Update is metadata-only and supports `userId`, `project`, `taskName`, `sharedConfig`, and `batchSize`.
- Updates are allowed only while the task is `NEW` or `BLOCKED`.

Assignment and dispatch:

- `TaskWorkerAssignListener` performs worker matching and delegates policy to `TaskWorkerMatchingStrategy`.
- `RuleBasedTaskWorkerMatchingStrategy` is the verified default.
- `batchSize` is a per-worker cap for each dispatch round.
- `minRequiredWorkerCount` is a real `READY -> RUNNING` gate.
- unmatched `READY` tasks and refill `RUNNING` tasks are delayed-retried instead of being orphaned.
- persisted `TaskMsg` rows are reused; dispatch creates `TaskMsgAttempt` history and updates latest-attempt projections.

Result write-back and closure:

- Pull-style workers can fetch `TaskDispatchItem` work from the polling channel and submit the same logical result semantics without server push.
- `RuntimeTaskResultIngestChannel` writes results through `TaskManager.handleTaskMessageResult(...)`.
- callbacks must resolve a unique active `TaskMsgAttempt`.
- retryable failure closes the attempt, resets the logical message to `INIT`, and does not publish logical-final semantics.
- success, retry exhaustion, expiry, and manual terminal drain close the logical message.
- once all engine runtime work items are final, the engine's internal task-progress convergence path closes any non-final task to `TERMINAL`.

Worker and worker-context truth:

- `Worker.status` is the runtime online truth.
- worker lock truth lives in `WorkerStorage` and is read through `WorkerManager.isLocked(...)`.
- `WorkerContext` is optional; stateless workers are verified for tasks without context-specific routing.
- `WorkerContext.workerId` is the owner truth for context attachment.

Open-ended and targeted worker debug:

- `Task.intakeStatus` is the append-window truth; `openEnded` is the create/response projection.
- `POST /status/api/tasks/{taskId}/items` appends inputs only while intake is open.
- `PUT /status/api/tasks/{taskId}/seal` closes intake and resumes normal terminal convergence.
- worker debug from the control console creates a normal task through `POST /status/api/tasks`, with fixed-worker routing carried by `Task.sharedConfig.targetWorkerId`.

## 5. Core Acceptance Commands

Boot-shell E2E:

```bash
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiCallbackReplayIntegrationTest,TaskApiMixedResultsIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiSingleWorkerReuseIntegrationTest test
```

Cross-language external worker samples:

```bash
./scripts/run-external-worker-samples.sh
```

Engine concurrency acceptance:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskConcurrencyAcceptanceTest test
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

Focused command used for current high-signal runtime coverage:

```bash
mvn -pl xa-mass-server -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,TaskApiTargetedWorkerDebugIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Coverage: lifecycle happy path, failure convergence, callback replay, worker/context routing, stateless workers, worker reuse, minimum-worker gate, refill, targeted worker debug, and control-console routing.

## 7. Known Mainline Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub.
- Redis and database storage remain fail-fast placeholders.
- Redis-backed EventBus behavior is not part of the verified runtime path.
- API integration coverage is improved but still not exhaustive for every cancel/terminate variant.
