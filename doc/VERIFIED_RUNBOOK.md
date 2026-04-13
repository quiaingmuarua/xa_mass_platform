# XA Mass Platform Verified Runbook

Last updated: 2026-04-13

This runbook records only facts that were verified by code, tests, or real runtime behavior. If older docs disagree, trust code and runtime.

Document scope:

- startup commands
- runtime verification checks
- verified execution path
- focused regression coverage

For endpoint inventory, response shapes, and implementation status, use [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

## 1. Current Conclusions

- The real Spring Boot entrypoint is `xa-mass-mock`.
- `xa-mass-runtime` is not the runnable Boot entry.
- The repository direction is library/SDK-first; HTTP/API surfaces are used primarily for validation and demonstration.
- Default `dev` startup now auto-starts mock WebSocket clients through `mock.client.auto-start=true`.
- The currently verified API happy path is:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> SUCCESS`
- The pause/resume lifecycle regression is also verified:
  - `NEW -> READY -> PAUSED -> READY`
- A failed downstream result path is also verified:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> FAILED`

## 2. Recommended Startup

Run from repo root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-runtime/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Windows guidance:

- Prefer a short classpath: module `target/classes` plus `logs/runtime-libs/*`
- Very long expanded `-cp` values can exceed Windows command-line limits and produce misleading missing-class errors
- As of 2026-04-13, removing `javafaker` from `xa-mass-core` avoids pulling `snakeyaml-android` into the Boot runtime path

## 3. Boot Checks

HTTP:

```bash
curl -i http://127.0.0.1:8088/status
curl -i http://127.0.0.1:8088/status/tasks
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
```

WebSocket:

```bash
nc -zv 127.0.0.1 18088
```

Default `dev` startup facts:

- `MockApplicationSpringBootApp` is the verified entry path
- `WebSocketClientStarter` now starts on `ApplicationReadyEvent`
- `xa-mass-mock/src/main/resources/application.yml` enables `mock.client.auto-start=true`
- `server.port` is the HTTP port, currently `8088`
- `mass.websocket.port` is the gateway WebSocket port, currently `18088`
- In the verified default path, mock devices connect automatically to `ws://localhost:18088/ws`
- `mock.client.task-result-status` can force mock result frames to `SUCCESS` or `FAILED`
- legacy client-only Spring Boot bootstrap has been removed

## 4. Current Runtime Mainline

### 4.1 Lifecycle Entry Points

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","countryCode":"us","textContent":"smoke","userId":"agent","targetList":["smoke-target-001","smoke-target-002"],"batchSize":1}'
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/pause
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/resume
```

Verified state transitions:

- create: initial task status is `NEW`
- create: `targetList` is persisted as real `TaskMsg` rows
- create: `TaskManager.createTask()` now guards `targetList = null` and no longer throws NPE on that path
- `approveTask`: `NEW`, `BLOCKED` -> `READY`
- `rejectTask`: `NEW` -> `BLOCKED`
- `pauseTask`: `READY`, `RUNNING` -> `PAUSED`
- `resumeTask`: `PAUSED` -> `READY`
- `deleteTask`: only `NEW`, `TERMINAL`

The full endpoint matrix is maintained in [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

### 4.2 Assign and Run

Verified runtime path:

1. The task enters `READY` after approval or resume.
2. `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`.
3. `MassEngine` also resubmits pre-existing `READY` tasks at startup and subscribes to READY events from approve/resume.
4. `TaskDeviceAssignListener` performs device matching.
5. `TaskDeviceAssignListener` now delegates matching through `TaskDeviceMatchingStrategy`; the verified default implementation is `RuleBasedTaskDeviceMatchingStrategy`.
6. On successful matching it writes `scheduleDeviceCnt` and moves the task from `READY` to `RUNNING`.
7. `SimpleTaskMsgAssignListener` reuses the persisted `TaskMsg` records created during task creation.
8. Each `TaskMsg` is filled with `deviceId`, `tokenId`, and `batchId`, then moved to `SENT`.
9. `GatewayTaskMsgPublisher` pushes the downstream payload as `TASK/step`.

### 4.3 Result Write-Back and Completion

Verified runtime path:

1. Mock clients receive `TASK/step`.
2. Mock clients send back a `TASK/step` result frame.
3. `GatewayTaskResultHandler` calls `TaskManager.handleTaskMessageResult(...)`.
4. `TaskManager` updates the persisted `TaskMsg` by `taskId + msgId`.
5. Each `TaskMsg` reaches `SUCCESS` or `FAILED`.
6. When all task messages are final and the task is in `RUNNING`, the task is closed to `TERMINAL`.

Important guard added in the verified runtime:

- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames so mock clients do not echo server response frames back into the system
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so failure-path result handling can be exercised without changing the engine path

## 5. Verified Smoke and Regression Coverage on 2026-04-13

### 5.1 Mock Data Preconditions

`MassApplication.loadMockData(...)` now:

- normalizes `supportedProjects` into `Project` enums
- lowercases `groupId`
- auto-seeds a `LOGIN_READY` token when a mock device has no token data

This fixes the earlier false-stuck case where approved tasks remained in `READY` because mock devices did not satisfy assignment prerequisites.

### 5.2 Default `dev` Startup Launches Mock Clients

`WebSocketClientStarter` now:

- no longer depends on a separate `client` profile
- starts on `ApplicationReadyEvent`
- is enabled by `mock.client.auto-start=true`
- prevents duplicate startup through an internal `AtomicBoolean`

Regression test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/starter/WebSocketClientStarterTest.java`

### 5.3 Real API Happy and Guard Paths Are Covered by Integration Tests

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiLifecycleGuardsIntegrationTest.java`

What it verifies:

- `POST /status/api/tasks`
- `GET /status/api/tasks/{taskId}` starts at `NEW`
- `POST /status/api/tasks/{taskId}/audit?approved=true`
- the task reaches `TERMINAL`
- `scheduleDeviceCnt == 2`
- `taskExecutedNumber == 2`
- two persisted messages exist
- each message finishes as `SUCCESS`
- each message has non-null `deviceId`, `tokenId`, and `batchId`
- separate lifecycle guard coverage now verifies reject/approve, pause/resume, and delete guard through real HTTP APIs with no assignable devices

Implementation details that matter:

- It uses `@SpringBootTest` against the real `MockApplicationSpringBootApp`
- It dynamically allocates a free WebSocket port
- It wires both `mass.websocket.port` and `mock.client.uri` to that allocated port
- It uses minimal dedicated mock fixtures to keep the run deterministic

### 5.4 Failed Result Write-Back Path Is Covered

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiFailureResultIntegrationTest.java`

What it verifies:

- `mock.client.task-result-status=FAILED` drives real mock clients to send failure result frames
- the task still reaches `TERMINAL`
- `scheduleDeviceCnt == 2`
- `taskExecutedNumber == 0`
- both persisted messages finish as `FAILED`
- each failed message keeps non-null `deviceId`, `tokenId`, and `batchId`
- each failed message writes `errorMessage = "Executed by mock client " + deviceId`

### 5.5 Mock Echo Loop Regression Is Covered

Regression test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/client/MassWebSocketClientImplTest.java`

What it verifies:

- a task request frame produces exactly one mock response
- a task response frame does not trigger another response

### 5.6 Duplicate Result Idempotency Is Covered at Engine/Starter Level

Regression tests:

- `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java`
- `xa-mass-runtime/src/test/java/com/xa/mass/starter/GatewayTaskResultHandlerTest.java`

What they verify:

- a second callback for the same `taskId + msgId` is accepted as a no-op rather than reprocessed
- the first final state is preserved and not overwritten by a later conflicting callback
- `taskExecutedNumber` and task final status remain consistent
- scheduler completion/failure callbacks are not triggered twice

### 5.7 Focused Verified Test Command

```bash
mvn --% -pl xa-mass-mock -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Verified result:

- `BUILD SUCCESS`

## 6. Remaining Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- the running app may still need two interrupts to exit
- EventBus runtime is not yet converged and still uses `old.eventbus` in places
- Redis and Database storage remain fail-fast placeholders
- API integration coverage is still selective beyond the current happy, guard, and failed-result paths; duplicate callback behavior is covered at unit level, not end-to-end
- Multiple matching policies are now possible at engine level, but only the rule-based strategy is covered in the current integrated runtime path

Recommended next test-driven additions:

1. `RUNNING -> PAUSED -> READY` end-to-end with real assigned messages
2. terminate path end-to-end from `READY`, `RUNNING`, and `PAUSED`
3. repeated callback replay end-to-end through the gateway and mock runtime
4. mixed-result aggregation coverage where one message succeeds and another fails

