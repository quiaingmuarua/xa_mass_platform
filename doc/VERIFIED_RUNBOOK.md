# XA Mass Platform Verified Runbook

Last updated: 2026-04-14

This runbook records only facts that were verified by code, tests, or real runtime behavior. If older docs disagree, trust code and runtime.

Document scope:

- startup commands
- runtime verification checks
- verified execution path
- focused regression coverage

Testing stance:

- the project now treats end-to-end integration coverage as the primary acceptance gate
- unit and slice tests still matter, but they are support coverage
- the `xa-mass-mock` integration suites are organized by domain under `com.xa.mass.mock.e2e`

For endpoint inventory, response shapes, and implementation status, use [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

## 1. Current Conclusions

- The real Spring Boot entrypoint is `xa-mass-mock`.
- `xa-mass-runtime` is not the runnable Boot entry.
- The current root reactor is `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-runtime`, and `xa-mass-mock`.
- `xa-mass-base` and `xa-mass-starter` remain in the repository as directories, but they are not part of the current root reactor.
- The repository direction is library/SDK-first; HTTP/API surfaces are used primarily for validation and demonstration.
- Default `dev` startup now auto-starts mock WebSocket clients through `mock.client.auto-start=true`.
- The currently verified API happy path is:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> SUCCESS`
- The pause/resume lifecycle regression is also verified:
  - `NEW -> READY -> PAUSED -> READY`
- Create-time validation is now verified:
  - supported create fields are limited to `userId`, `project`, `taskName`, `textContent`, `targetList`, `countryCode`, and `batchSize`
  - `targetList` must contain at least one materialized target
  - unsupported `project` codes are rejected instead of silently falling back to `demoApp`
  - unknown JSON fields such as retired `targetJsonList`, `targetType`, and `extraParams` are rejected at the API boundary
  - request `batchSize` is preserved on the persisted task
  - current mainline uses `batchSize` as the per-device hard cap for each dispatch round
- Update-time validation is now verified:
  - supported update fields are limited to `userId`, `project`, `taskName`, `textContent`, `countryCode`, and `batchSize`
  - `targetList` and other unknown JSON fields are rejected at the API boundary
  - task edits are only allowed while status is `NEW` or `BLOCKED`
- Engine regression also verifies a paused-completion closure rule:
  - if all persisted `TaskMsg` callbacks finish while the task is `PAUSED`, the task is closed to `TERMINAL`
- Engine regression also verifies two state-machine safety rules:
  - assignment does not dispatch if a task leaves `READY` during the device-matching window
  - late callbacks after manual terminal closure are ignored instead of mutating task/message progress
- A failed downstream result path is also verified:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> FAILED`
- A duplicate callback replay path is also verified:
  - a repeated `TASK/step` result for the same `taskId + msgId` is accepted as a no-op
  - the first final message state and terminal task counts are preserved
- A running-task terminate path is also verified:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> EXPIRED` when the task is manually terminated before client callbacks
  - the terminal task can then be deleted through the real API
- A paused-after-assignment callback path is also verified:
  - `NEW -> READY -> RUNNING -> PAUSED -> TERMINAL`
  - persisted `TaskMsg` rows can move from `SENT` to final states while the task remains paused
  - no manual `resume` is required for terminal closure
- Device and token auxiliary rule labels are also verified:
  - `Device.attributes` and `Token.attributes` are defensive-copied and exposed as read-only maps
  - `DeviceMatchContext` exposes them to QLExpress as `deviceAttributes` and `tokenAttributes`
- token-attribute routing is verified end-to-end through `tokenAttributes['country'] == taskRoutingCountryCode`
- device availability truths are single-sourced:
  - online truth comes from `Device.status`
  - lock truth comes from `DeviceStorage` / `DeviceManager.isLocked(...)`, not from the `Device` model
- core device/token state is stricter:
  - `DeviceStatus` transitions are explicit instead of "any different value"
  - `Device` / `Token` reject null statuses
  - dispatch-time token binding moves `LOGIN_READY -> BIND_READY -> SENDING`
  - token release now only frees real dispatch ownership, not arbitrary blocked/invalid states
- terminal resource release is verified:
  - normal terminal completion releases the device lock and returns the token to `LOGIN_READY`
  - manual `RUNNING -> TERMINAL` closure also releases the same runtime occupancy
- multi-round dispatch refill is verified:
  - when `batchSize` is smaller than the remaining target count, only up to `batchSize` messages are dispatched per device in the current round
  - once a device finishes its in-flight messages for the task, that device/token slot is released and the still-`RUNNING` task can dispatch the next round of pending `INIT` messages
- minimum-device gating is verified:
  - `runTaskMinDeviceCnt` is a real start threshold, not just a matching hint
  - if matched devices are below the minimum, the task stays `READY` and provisional locks are released
- terminal policy is now an explicit engine seam:
  - `TaskManager` delegates terminal closure decisions through `TaskTerminalPolicy`
  - the current default policy is still "all persisted task messages are final"
  - future policy-driven terminal reasons are reserved for runtime limits, success-rate thresholds, and retry-budget exhaustion

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
- create: `TaskManager.createTask()` requires at least one `targetList` value
- create: only `userId`, `project`, `taskName`, `textContent`, `targetList`, `countryCode`, and `batchSize` are part of the supported request contract
- create: unsupported `project` codes are rejected
- create: unknown JSON fields such as retired `targetJsonList`, `targetType`, and `extraParams` are rejected at the API boundary
- create: request `batchSize` is persisted onto the task
- create: current mainline treats `batchSize` as the per-device hard cap for each dispatch round
- update: only `userId`, `project`, `taskName`, `textContent`, `countryCode`, and `batchSize` are part of the supported request contract
- update: `targetList` and other unknown JSON fields are rejected at the API boundary
- update: only `NEW` and `BLOCKED` tasks may be edited
- `approveTask`: `NEW`, `BLOCKED` -> `READY`
- `rejectTask`: `NEW` -> `BLOCKED`
- `pauseTask`: `READY`, `RUNNING` -> `PAUSED`
- `resumeTask`: `PAUSED` -> `READY`
- `deleteTask`: only `NEW`, `TERMINAL`

Additional implementation rule verified at engine regression level:

- if a paused task already has all persisted `TaskMsg` rows in final states, it is closed to `TERMINAL` instead of being put back into `READY`
- SDK callers can distinguish these two resume outcomes through `TaskManager.resumeTaskDetailed(...)`
- SDK callers can also use `TaskManager.resolveTaskStateFromMessages(...)` to ask whether current `TaskMsg` aggregation leaves the task pending, finalizes it, or observes an already-final task
- SDK callers can use `TaskManager.validateTaskState(...)` to audit whether task counters, `terminalReason`, and persisted `TaskMsg` aggregates are still self-consistent
- `Task` aggregate counters now use explicit names:
  - `taskTargetNumber`: initial persisted target count
  - `taskEligibleNumber`: target count currently included in progress/statistical aggregation
  - `taskSuccessNumber`: persisted `TaskMsg` rows already in `SUCCESS`
  - `taskNonSuccessNumber`: valid rows that are not yet `SUCCESS`, including failed and still-in-flight rows
- when a task reaches `TERMINAL`, inspect `task.terminalReason` to distinguish:
  - `MANUAL_CANCELLED`
  - `ALL_MESSAGES_SUCCEEDED`
  - `ALL_MESSAGES_FAILED`
  - `MIXED_MESSAGE_RESULTS`

The full endpoint matrix is maintained in [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

### 4.2 Assign and Run

Verified runtime path:

1. The task enters `READY` after approval or resume.
2. `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`.
3. `MassEngine` also resubmits pre-existing `READY` tasks at startup and subscribes to READY events from approve/resume.
4. `TaskDeviceAssignListener` performs device matching.
5. `TaskDeviceAssignListener` now delegates matching through `TaskDeviceMatchingStrategy`; the verified default implementation is `RuleBasedTaskDeviceMatchingStrategy`.
6. On successful matching it writes `scheduleDeviceCnt` for the current dispatch round and moves the task from `READY` to `RUNNING`, but only if the task is still `READY` when matching returns.
7. If no device matches at that moment, `TaskAssignWorker` delayed-retries the `READY` task instead of letting it fall out of the assignment loop.
8. The same delayed-retry behavior now also applies to explicit `RUNNING` re-dispatch attempts when a later dispatch round is requested before a slot is actually free.
9. `SimpleTaskMsgAssignListener` reuses the persisted `TaskMsg` records created during task creation.
10. It round-robins pending `INIT` messages across matched devices and enforces `batchSize` as a per-device cap for the current round.
11. Each dispatched `TaskMsg` is filled with `deviceId`, `tokenId`, and `batchId`, then moved to `SENT`.
12. Dispatch-time token ownership is now explicit: assignment binds `LOGIN_READY` tokens to the task, advances them to `SENDING`, and skips non-dispatchable token states.
13. `runTaskMinDeviceCnt` is now enforced before `READY -> RUNNING`; insufficient matches leave the task in `READY`.
14. Any devices matched only to satisfy the start gate, but not needed for current message dispatch, are unlocked immediately instead of being stranded as zero-message reservations.
15. `GatewayTaskMsgPublisher` pushes the downstream payload as `TASK/step`.
16. When a device has no more in-flight `TaskMsg` rows for the current task, `TaskResourceReleaseListener` releases that device/token slot and re-submits the still-`RUNNING` task if pending `INIT` messages remain.
17. Once the task reaches `TERMINAL`, `TaskResourceReleaseListener` releases any remaining token/device occupancy so later tasks can reuse the same runtime slot.
18. Once all persisted `TaskMsg` rows are final, `TaskManager.updateTaskProgress(...)` closes any non-final task to `TERMINAL`, including tasks paused while callbacks were still arriving.
18. The default terminal rule is now implemented through `AllMessagesFinalTaskTerminalPolicy`, so future stop conditions can be added without rewriting `TaskManager` state transitions again.

Current matching-context note:

- `Task.taskRoutingCountryCode` is the active routing-country input for matching and diagnostics
- `DeviceManager.getDevicesByGroupId(...)` / `DeviceStorage.getDevicesByGroupId(...)` are grouping helpers only, not country-routing APIs
- `RuleBasedTaskDeviceMatchingStrategy` no longer treats device `deviceGroupId` as a hard prefilter for routing country
- `DeviceMatchContext` now exposes nested `deviceAttributes` and `tokenAttributes` maps for QLExpress access such as `tokenAttributes['country'] == taskRoutingCountryCode`
- these maps are auxiliary rule labels only and are not the source of truth for lifecycle, lock, or online state
- `AssignmentRecordService` snapshots `deviceLocked` from live runtime lock state instead of from fields on `Device`

### 4.3 Result Write-Back and Completion

Verified runtime path:

1. Mock clients receive `TASK/step`.
2. Mock clients send back a `TASK/step` result frame.
3. `GatewayTaskResultHandler` calls `TaskManager.handleTaskMessageResult(...)`.
4. `TaskManager` updates the persisted `TaskMsg` by `taskId + msgId`.
5. Each `TaskMsg` reaches `SUCCESS` or `FAILED`.
6. When all persisted task messages are final, `TaskManager.updateTaskProgress(...)` closes any non-final task to `TERMINAL`.

Important guard added in the verified runtime:

- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames so mock clients do not echo server response frames back into the system
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so failure-path result handling can be exercised without changing the engine path
- `TaskManager.handleTaskMessageResult(...)` ignores late non-final callbacks for tasks already closed to `TERMINAL`, so manual cancel/terminate freezes later progress mutation
- `DeviceManager` now uses `Device.status` as the single online truth for runtime availability; gateway online/offline events update the device model directly
- runtime lock state is stored in `DeviceStorage` and exposed through `DeviceManager.isLocked(...)`; active diagnostics no longer infer lock from `Device`
- terminal resource release also runs on manual `cancel/terminate`, not only on message-driven completion, so device/token occupancy is not stranded after a forced stop

## 5. Verified Smoke and Regression Coverage on 2026-04-13

### 5.1 Mock Data Preconditions

`MassApplication.loadMockData(...)` now:

- normalizes `supportedProjects` into `Project` enums
- lowercases `deviceGroupId`
- loads explicit mock `tokens` when present
- auto-seeds only a minimal `LOGIN_READY` fallback token when a mock device still has no token data

This keeps the verified startup runnable without letting `deviceGroupId` silently become token-side routing truth in the mock path.

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

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiLifecycleGuardsIntegrationTest.java`

What it verifies:

- `POST /status/api/tasks`
- `GET /status/api/tasks/{taskId}` starts at `NEW`
- `POST /status/api/tasks/{taskId}/audit?approved=true`
- the task reaches `TERMINAL`
- `scheduleDeviceCnt == 2`
- `taskSuccessNumber == 2`
- two persisted messages exist
- each message finishes as `SUCCESS`
- each message has non-null `deviceId`, `tokenId`, and `batchId`
- separate lifecycle guard coverage now verifies reject/approve, pause/resume, and delete guard through real HTTP APIs with no assignable devices
- separate pause-completion coverage now verifies `RUNNING -> PAUSED -> TERMINAL` through real gateway callback write-back after assignment
- engine lifecycle coverage now verifies paused-task final callback closure into `TERMINAL`
- engine worker coverage now verifies retry of `READY` tasks that initially have no device match
- separate token-attribute routing coverage now verifies that a custom QLExpress rule can select the correct token via `tokenAttributes['country']`
- engine strategy and message-assignment coverage now verifies that assignment snapshots carry runtime lock state from `DeviceManager.isLocked(...)`
- separate single-device reuse coverage now verifies that a task completing to `TERMINAL` returns the token to `LOGIN_READY` and frees the same device for the next task
- separate minimum-device-gate coverage now verifies that one matching device is insufficient when `runTaskMinDeviceCnt=2`, and the task advances only after a second device becomes available

Implementation details that matter:

- It uses `@SpringBootTest` against the real `MockApplicationSpringBootApp`
- It dynamically allocates a free WebSocket port
- It wires both `mass.websocket.port` and `mock.client.uri` to that allocated port
- It uses minimal dedicated mock fixtures to keep the run deterministic

### 5.4 Failed Result Write-Back Path Is Covered

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/results/TaskApiFailureResultIntegrationTest.java`

What it verifies:

- `mock.client.task-result-status=FAILED` drives real mock clients to send failure result frames
- the task still reaches `TERMINAL`
- `scheduleDeviceCnt == 2`
- `taskSuccessNumber == 0`
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
- `taskSuccessNumber` and task final status remain consistent
- scheduler completion/failure callbacks are not triggered twice
- a manual `TERMINAL` closure also freezes later non-final callbacks so they do not alter `TaskMsg` state or task counters

### 5.7 Duplicate Callback Replay Is Covered End-to-End

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/results/TaskApiCallbackReplayIntegrationTest.java`

What it verifies:

- the task first completes through the normal `NEW -> READY -> RUNNING -> TERMINAL` runtime path
- a separate WebSocket client then replays a conflicting `TASK/step` result for an already-final `msgId`
- the gateway still acknowledges the replay frame
- the persisted `TaskMsg` keeps its first final state and is not overwritten
- `taskSuccessNumber` remains stable after the replay

### 5.8 Running Terminate Path Is Covered End-to-End

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiTerminateRunningIntegrationTest.java`

What it verifies:

- mock devices are available for real assignment, but mock clients are not auto-started
- approval still drives the task into `RUNNING`
- `scheduleDeviceCnt` is populated from real matching and assignment
- persisted `TaskMsg` rows advance to `SENT`
- `POST /status/api/tasks/{taskId}/terminate` transitions the task to `TERMINAL`
- `taskSuccessNumber` remains `0`
- no `TaskMsg` is incorrectly rewritten to `SUCCESS` or `FAILED` just because the task was terminated
- dispatched `TaskMsg` rows are explicitly drained to `EXPIRED`
- `DELETE /status/api/tasks/{taskId}` succeeds after the task reaches `TERMINAL`

### 5.9 Single-Device Reuse Is Covered End-to-End

Integration tests:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiSingleDeviceReuseIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiTerminateReuseIntegrationTest.java`

What they verify:

- a single manually registered `ONLINE` device with one `LOGIN_READY` token can run a task to terminal completion
- after normal terminal completion, the same token returns to `LOGIN_READY`
- the same device can then be assigned to the next task
- after a manual `RUNNING -> TERMINAL` stop, the same token also returns to `LOGIN_READY`
- the same device can then be assigned to the next task again without restarting the runtime

### 5.9 State Validation Is Covered End-to-End

Integration test:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/audit/TaskApiStateValidationIntegrationTest.java`

What it verifies:

- `GET /status/api/tasks/{taskId}` returns `stateValidation` over the real HTTP/runtime path
- a freshly created task reports `stateValidation.valid=true`, `needsResolution=false`, `status=NEW`
- a normal runtime-completed task reports `stateValidation.valid=true`, `needsResolution=false`, `status=TERMINAL`
- after a completed task is intentionally reopened to `RUNNING` without changing persisted `TaskMsg` finals, the same API reports `stateValidation.valid=true` and `needsResolution=true`
- if terminal metadata is intentionally corrupted, the same API reports `stateValidation.valid=false` and exposes concrete violations such as `TERMINAL_REASON_MISSING` or `TERMINAL_REASON_MISMATCH_ALL_FAILED`

### 5.10 Focused Verified Test Command

```bash
mvn --% -pl xa-mass-mock -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,TaskApiTerminateRunningIntegrationTest,TaskApiCallbackReplayIntegrationTest,TaskApiStateValidationIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Verified result:

- `BUILD SUCCESS`

### 5.11 Domain Grouping for E2E Suites

Current `xa-mass-mock` integration directory layout:

- `com.xa.mass.mock.e2e.lifecycle`: create/approve/pause/resume/terminate/complete flows
- `com.xa.mass.mock.e2e.results`: success/failure/mixed-result/callback-replay semantics
- `com.xa.mass.mock.e2e.assignment`: delayed device availability and multi-task scheduling fairness
- `com.xa.mass.mock.e2e.audit`: state-validation and consistency exposure through the real HTTP path
- `com.xa.mass.mock.e2e.support.AbstractMockE2eTest`: shared E2E base for task creation, HTTP exchange helpers, task snapshot polling, and dynamic WebSocket port registration

## 6. Remaining Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- runtime stop is now Spring-managed and `MassApplication.stop()` is idempotent, but single-interrupt exit has not yet been re-verified in a live process
- EventBus runtime now uses the current `channel.eventbus.core` and `channel.eventbus.event` namespace
- The verified implementation remains Guava-backed; Redis is still fail-fast
- Redis and Database storage remain fail-fast placeholders
- API integration coverage is still selective beyond the current happy, guard, failed-result, running-terminate-delete, and callback-replay paths
- Multiple matching policies are now possible at engine level, but only the rule-based strategy is covered in the current integrated runtime path

Recommended next test-driven additions:

1. `RUNNING -> PAUSED -> READY` end-to-end with real assigned messages and then resumed dispatch behavior validation
2. cancel path variants from `NEW`, `READY`, and `PAUSED` with message-state assertions
3. mixed-result aggregation coverage where one message succeeds and another fails
