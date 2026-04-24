# XA Mass Platform Verified Runbook

Last updated: 2026-04-22

This runbook records verified runtime facts only. It is not an architecture essay, API reference, or changelog.

Use this file when you need to boot the app, run a smoke flow, or choose a focused regression command. Use [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) for endpoint shapes, [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md) for lifecycle rules, and [TRACE_CONTRACT.md](./TRACE_CONTRACT.md) for trace semantics.

## 1. Verified Entry

Current runtime entry:

- Spring Boot app: `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- SDK entry: `xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java`
- Embedded runtime composition: `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`

Current module set from the root reactor:

- `xa-mass-core`
- `xa-mass-transport-api`
- `xa-mass-engine`
- `xa-mass-gateway`
- `xa-mass-sdk-api`
- `xa-mass-web`
- `xa-mass-sdk`
- `xa-mass-dev-app`

Do not treat removed historical modules or archive/v2 references as missing current code.

## 2. Startup

Run from repo root:

```bash
./mvnw -DskipTests compile
cd frontend && corepack pnpm build && cd ..
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-transport-api/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Windows guidance:

- Prefer module `target/classes` plus `logs/runtime-libs/*`.
- Avoid very long expanded classpaths; Windows command-line limits can create false missing-class errors.

Default runtime facts:

- `server.port=8088` serves the backend-hosted control console and JSON APIs.
- `mass.websocket.port=18088` serves the current WebSocket transport adapter endpoint when the transport server is enabled.
- Default local/dev startup auto-starts mock worker clients when `mock.client.auto-start=true`.
- Mock WebSocket clients connect to `ws://localhost:18088/ws`.
- Pull-style workers can also run without the WebSocket transport server through `MassSdkApplication.pullWorker(...)`.
- Worker routing may use neutral strategy hints like `realtime` and `polling`; the current WebSocket adapter still accepts `websocket/ws` as compatibility aliases.
- `mock.client.task-result-status=FAILED` forces failed task result write-back for regression tests.

## 3. Boot Checks

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

Expected result:

- Backend-hosted control console routes return successfully.
- Legacy `/status*` and `/config` console aliases redirect locally to the primary SPA routes.
- If the transport server is enabled, the current WebSocket adapter port is open.
- Mock workers appear online when auto-start is enabled.

## 4. Minimal Task Smoke

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

Expected mainline convergence:

- `Task`: `NEW -> READY -> RUNNING -> TERMINAL`
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> SUCCESS` for success-mode mock clients
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> FAILED` when `mock.client.task-result-status=FAILED`
- terminal tasks must be read as `status=TERMINAL` plus `terminalReason`
- task detail response includes `items` from persisted `TaskMsg.input` and `stateValidation`
- message read model exposes `input`, `output`, `latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId`

## 5. Runtime Facts To Trust

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

- Current WebSocket workers receive `TASK/step` and return `TASK/step` result frames.
- Pull-style workers can fetch `TaskDispatchItem` work from the polling channel and submit the same logical result semantics without server push.
- `RuntimeTaskResultIngestChannel` writes results through `TaskManager.handleTaskMessageResult(...)`.
- callbacks must resolve a unique active `TaskMsgAttempt`; legacy attempt synthesis is not part of the current path.
- retryable failure closes the attempt, resets the logical message to `INIT`, and does not publish logical-final semantics.
- success, retry exhaustion, expiry, and manual terminal drain close the logical message.
- once all persisted messages are final, `TaskManager.updateTaskProgress(...)` closes any non-final task to `TERMINAL`.

Worker and worker-context truth:

- `Worker.status` is the runtime online truth.
- worker lock truth lives in `WorkerStorage` and is read through `WorkerManager.isLocked(...)`.
- `WorkerContext` is optional; stateless workers are verified for tasks without context-specific routing.
- `WorkerContext.workerId` is the owner truth for context attachment.
- `Worker.attributes` and `WorkerContext.attributes` are defensive-copied auxiliary rule labels only.
- routing should come from explicit rules and worker-context signals, not `workerGroupId`.

Open-ended and debug side-channel:

- `Task.intakeStatus` is the append-window truth; `openEnded` is the create/response projection.
- `POST /status/api/tasks/{taskId}/items` appends inputs only while intake is open.
- `PUT /status/api/tasks/{taskId}/seal` closes intake and resumes normal terminal convergence.
- manual worker control uses the event-first path `POST /status/workers/send-event`, which currently bridges to `CONTROL/event -> CONTROL/event` on the WebSocket adapter.
- worker control messaging is a control side-channel and must not create or mutate `TaskMsg`.

## 6. Focused Regression Gate

Focused command used for current high-signal runtime coverage:

```bash
mvn -pl xa-mass-dev-app -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,WorkerControlEventCommandIntegrationTest,WorkerControlEventDisconnectIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Representative coverage:

- create/approve/assign/dispatch/result/terminal happy path
- failure-result convergence
- reject/approve, pause/resume, running terminate, delete guard, and callback replay
- paused task closure when final callbacks arrive
- worker-context-attribute routing
- stateless-worker execution
- worker/worker-context reuse after normal completion and manual termination
- minimum-worker gate
- multi-round refill
- worker debug event dispatch through the real gateway path
- backend-hosted control console routing through the real Boot entry

For the broader test map, use [INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md).

## 7. Known Mainline Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub.
- Redis and database storage remain fail-fast placeholders.
- Redis-backed EventBus behavior is not part of the verified runtime path.
- API integration coverage is improved but still not exhaustive for every cancel/terminate variant.
