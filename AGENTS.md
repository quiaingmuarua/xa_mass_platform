# XA Mass Platform Agent Handoff

This file is the fastest entry point for coding agents such as Claude Code, Codex, and similar tools.

## 0. TL;DR

- Real boot entry: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Trust code and verified runtime over repository docs
- Default `dev` startup now auto-starts mock WebSocket clients through `mock.client.auto-start=true`
- Port split is explicit: `server.port` for HTTP and `mass.websocket.port` for the gateway WebSocket server
- Project direction is library/SDK-first; HTTP pages and backend endpoints are validation/demo surfaces
- Mainline change discipline is now end-to-end integration-test-driven first; unit tests remain important, but they are support coverage rather than the primary acceptance gate
- Current verified API task path: `NEW -> READY -> RUNNING -> TERMINAL`
- Pause/resume regression is also verified: `NEW -> READY -> PAUSED -> READY`
- `TaskManager.createTask()` is now fail-fast for unsupported inputs: empty/null `targetList` is rejected, non-empty `targetJsonList` is rejected, unsupported `project` codes are rejected, and request `batchSize` is preserved on the task
- `Task.terminalReason` now distinguishes manual cancel from message-driven terminal closure
- `TaskManager.validateTaskState()` now provides an explicit SDK-facing audit for `Task + TaskMsg` consistency and pending terminal resolution
- Engine regression now verifies that paused tasks close to `TERMINAL` once all `TaskMsg` callbacks finish
- Engine regression now verifies that `READY` tasks without a device match are retried instead of falling out of the assignment loop
- Engine regression now verifies that assignment does not dispatch if a task leaves `READY` during the matching window
- Engine regression now verifies that late callbacks after manual terminal closure are ignored instead of mutating task/message progress
- Current focused mock/runtime regression is green
- `TaskApiIntegrationTest` now covers `create -> approve -> assign -> run -> complete`
- `TaskApiFailureResultIntegrationTest` now covers `create -> approve -> assign -> fail -> terminal`
- `TaskApiLifecycleGuardsIntegrationTest` now covers `reject -> approve`, `pause -> resume`, and delete guard through real HTTP APIs
- `TaskApiTerminateRunningIntegrationTest` now covers `approve -> assign -> running -> terminate -> delete` without mock client callbacks
- `TaskApiCallbackReplayIntegrationTest` now covers duplicate `TASK/step` callback replay through the real gateway path
- `TaskApiPauseCompletionIntegrationTest` now covers `approve -> assign -> running -> pause -> callback -> terminal` through the real gateway path
- `TaskApiStateValidationIntegrationTest` now covers `GET /status/api/tasks/{taskId}` state-audit output for valid terminal tasks, forced `needsResolution=true` tasks, and invalid terminal-reason variants
- `TaskApiTokenAttributeRoutingIntegrationTest` now covers token-attribute-based routing through the real assignment and gateway path
- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames to avoid mock echo loops
- `mock.client.task-result-status` can force mock result frames to `SUCCESS` or `FAILED`
- `Device.attributes` and `Token.attributes` are now read-only auxiliary rule labels for matching and diagnostics only
- Treat `engine/v2` as historical archive material, not mainline

## 1. What This Repo Is

- Maven multi-module Java project
- Current root reactor modules come from `pom.xml`, not from every top-level directory
- Modules:
  - `xa-mass-core`
  - `xa-mass-engine`
  - `xa-mass-gateway`
  - `xa-mass-api`
  - `xa-mass-runtime`
  - `xa-mass-mock`

Important boundary:

- `xa-mass-base` and `xa-mass-starter` directories still exist in the repo, but they are not in the current root reactor
- do not assume those directories represent the active mainline just because their package names or filenames look familiar
- for active shared models/enums/eventbus code, prefer `xa-mass-core`
- for active lifecycle/composition code, prefer `xa-mass-runtime`

## 2. Read This First

Treat repository docs with mixed trust.

Trust order:

1. Code
2. Verified runtime behavior
3. `AGENTS.md`
4. `doc/AGENT_BASELINE.md`
5. `doc/VERIFIED_RUNBOOK.md`
6. module READMEs / internal API doc under `doc/` / task flow doc under `doc/engine/`
7. `doc/archive/API_DOCUMENTATION.md` / `doc/archive/QUICK_REFERENCE.md` - archived reference docs, partially outdated
8. `old/` / `v2/` docs - historical archive only

Deleted historical docs that should not be treated as missing:

- `doc/daily/`
- former planning doc under `doc/`
- `xa-mass-engine/.../v2/new_engine_refactory.md`
- former v2 matching-strategy draft under `xa-mass-engine/.../v2/`

## 3. Real Entry Point

Current verified Spring Boot entrypoint:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`

Do not assume:

- `xa-mass-runtime` is the runnable Spring Boot app
- `MassApplication.java` is a Spring Boot entry

`xa-mass-runtime` is a lifecycle/composition layer, not the verified Boot entry.

## 4. Verified Startup

Run from repo root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-runtime/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Windows note:

- Prefer a short classpath: module `target/classes` plus `logs/runtime-libs/*`
- Fully expanded dependency classpaths can exceed Windows command-line limits and produce false missing-class errors

Verified endpoints:

- `http://localhost:8088/status`
- `http://localhost:8088/status/tasks`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088`

Default `dev` startup facts:

- `xa-mass-mock/src/main/resources/application.yml` sets `mock.client.auto-start=true`
- `server.port` is the Spring Boot HTTP port, currently `8088`
- `mass.websocket.port` is the gateway WebSocket port, currently `18088`
- `WebSocketClientStarter` listens on `ApplicationReadyEvent`
- Mock device clients now connect automatically to the gateway in the default verified startup path

## 5. Current Reality, Not Marketing

- The app can compile and run.
- The current mainline is `core + engine + gateway + api + runtime + mock`, as defined by the root `pom.xml`.
- The active runtime path now uses the current `channel/eventbus/core` and `channel/eventbus/event` packages.
- New EventBus docs describe target architecture, not fully verified runtime reality.
- `v2` is not the mainline implementation.
- API-first task flow is the current source of truth. UI pages are a secondary validation surface.
- Some historical docs still overstate completion and should not be trusted over code.

## 5.1 Module Map

### `xa-mass-mock`

Role:

- Real Spring Boot entrypoint
- Wires `api + runtime + gateway + engine`
- Loads mock data and starts mock clients for end-to-end validation

Current status:

- Verified runnable
- Best module for end-to-end verification

Open first:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-mock/src/main/resources/application.yml`
- `xa-mass-mock/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`

Notes:

- This is the real operational entry, not just a demo shell.
- Default `dev` startup now includes mock WebSocket clients when `mock.client.auto-start=true`.
- `mock.client.task-result-status` can be used to simulate success or failure result write-back in tests.
- Legacy client-only Spring Boot entry and client monitor endpoints have been removed.
- New focused runtime regression tests live here.

### `xa-mass-runtime`

Role:

- Lifecycle/composition layer
- Builds and starts `MassApplication`, `MassEngine`, `MassGateway`

Current status:

- Important internally
- Not the verified Spring Boot entrypoint

Open first:

- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`

Notes:

- Do not treat this as the current `spring-boot:run` target.
- Runtime publishes and consumes events through the current `channel/eventbus/core` and `channel/eventbus/event` packages from this module.

### `xa-mass-api`

Role:

- REST controllers
- status pages / HTML templates
- request/response DTO layer

Current status:

- Loaded via `xa-mass-mock` Spring Boot scanning
- Not an independently verified app

Open first:

- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-api/src/main/java/com/xa/mass/api/internal/StatusPageController.java`
- `xa-mass-api/src/main/resources/templates/tasks.html`

Notes:

- Task lifecycle endpoints are aligned to `TaskManager`.
- API happy-path integration coverage now exists, but API edge coverage is still incomplete.

### `xa-mass-engine`

Role:

- Main business logic
- task lifecycle
- device assignment
- rule management

Current status:

- Mainline implementation lives here
- Active production code lives under `xa-mass-engine/src/main/java/com/xa/mass/engine`
- Historical `v2` code has been moved under `xa-mass-engine/archive/v2/` to keep it out of the active source tree

Open first:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/DeviceManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Notes:

- Mainline engine tests are the active regression surface.
- `TaskDeviceMatchingStrategy` is now the engine extension seam for pluggable task-to-device matching policies.
- `xa-mass-engine/archive/v2/**` is historical experiment code, not active regression.

### `xa-mass-core`

Role:

- shared models
- task/task-msg enums and entities
- messaging abstractions
- JSON DSL
- event bus implementations

Current status:

- Stable enough for current mainline
- Contains the active EventBus implementation under `channel/eventbus/core` and `channel/eventbus/event`
- Maven module name is `xa-mass-core`; Java packages remain under `com.xa.mass.base`

Open first:

- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`

Notes:

- The mainline EventBus namespace is the current `channel/eventbus/core` and `channel/eventbus/event` path.
- The former legacy compatibility package has been removed from the active source tree to reduce agent confusion.
- Many Java packages still use `com.xa.mass.base.*`; package names do not imply that `xa-mass-base` is the active reactor module.

### `xa-mass-gateway`

Role:

- WebSocket connection handling
- message routing / dispatch
- session and connection context

Current status:

- Verified as part of full mock startup
- Not independently validated as a standalone app

Open first:

- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/server/WebSocketServerImpl.java`
- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/dispatcher/ServerMessageDispatcher.java`

Notes:

- WebSocket port `18088` is part of the verified startup path.
- Gateway now participates in real task message publish/result write-back for the verified happy path.

## 6. Task Lifecycle Status

Verified current lifecycle behavior:

```text
NEW --approve--> READY --pause--> PAUSED --resume--> READY
 |                  |                                     |
 +--reject-------> BLOCKED --approve--------------------> +
 |                                                        |
 +--cancel/terminate-----------------------------------> TERMINAL

READY --assign--> RUNNING --all task messages final--> TERMINAL
```

State machine constraints enforced in code (`TaskStatus.canTransitionTo`):

| Action | Allowed from | Target |
|--------|--------------|--------|
| `approveTask` | `NEW`, `BLOCKED` | `READY` |
| `rejectTask` | `NEW` | `BLOCKED` |
| `pauseTask` | `READY`, `RUNNING` | `PAUSED` |
| `resumeTask` | `PAUSED` | `READY` |
| `cancelTask` | any non-`TERMINAL` | `TERMINAL` |
| `deleteTask` | `NEW`, `TERMINAL` only | physical delete |

Important current implementation facts:

- `TaskApiController` uses `TaskManager` lifecycle methods for all state changes.
- `deleteTask()` enforces the state guard. `READY`, `RUNNING`, and `PAUSED` tasks cannot be deleted.
- `TaskManager.createTask()` requires at least one materialized `targetList` entry, rejects non-empty `targetJsonList`, and persists request `batchSize` onto the task.
- `TaskManager.createTask()` also rejects unsupported `project` codes instead of silently falling back to `demoApp`.
- `TaskManager` persists one `TaskMsg` per target with the correct `taskId`, distinct `msgId`, and actual target value.
- `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`, submits existing `READY` tasks on startup, and subscribes to READY events from approve/resume.
- `TaskDeviceAssignListener` re-checks that the task is still `READY` after matching; if the task left `READY` during the matching window, dispatch is skipped.
- `TaskDeviceAssignListener` sets `scheduleDeviceCnt` and transitions matched tasks from `READY` to `RUNNING`.
- `TaskDeviceAssignListener` now delegates matching to `TaskDeviceMatchingStrategy`; `RuleBasedTaskDeviceMatchingStrategy` is the current default.
- `DeviceMatchContext` now exposes nested `deviceAttributes` and `tokenAttributes` maps to QLExpress rules.
- `SimpleTaskMsgAssignListener` reuses persisted `TaskMsg` records, fills `deviceId` / `tokenId` / `batchId`, and moves them to `SENT`.
- `GatewayTaskMsgPublisher` pushes task messages downstream as `TASK/step`.
- `GatewayTaskResultHandler` handles inbound `TASK/step` results and writes them back through `TaskManager.handleTaskMessageResult(...)`.
- `TaskManager.handleTaskMessageResult(...)` updates persisted `TaskMsg` state by `taskId + msgId`, recalculates progress, closes any non-final task to `TERMINAL` once all messages finish, and ignores late non-final callbacks after manual terminal closure.
- `TaskManager.updateTaskProgress(...)` now closes any non-final task to `TERMINAL` once all persisted `TaskMsg` rows are final, including tasks that were paused while callbacks were still arriving.
- `TaskManager.resumeTask(...)` now short-circuits paused tasks that already fully completed underneath them and closes them to `TERMINAL` instead of re-queueing them as `READY`.
- `TaskManager.resumeTaskDetailed(...)` is now the explicit SDK-facing resume API:
  - `RESUMED_TO_READY`
  - `COMPLETED_TO_TERMINAL`
  - `REJECTED`
- `TaskManager.resolveTaskStateFromMessages(...)` is now the explicit SDK-facing aggregation API:
  - `TASK_NOT_FOUND`
  - `NOT_FINALIZED`
  - `FINALIZED_TO_TERMINAL`
  - `ALREADY_FINAL`
- `TaskManager.validateTaskState(...)` is now the explicit SDK-facing state-audit API:
  - validates task counters against persisted `TaskMsg` aggregates
  - validates whether `terminalReason` is present and semantically matched
  - reports `needsResolution=true` when all messages are final but the task itself is still non-final
- `Task.terminalReason` is part of the live task model. Read `status=TERMINAL` together with `terminalReason`:
  - `MANUAL_CANCELLED`
  - `ALL_MESSAGES_SUCCEEDED`
  - `ALL_MESSAGES_FAILED`
  - `MIXED_MESSAGE_RESULTS`
- `TaskManager.handleTaskMessageResult(...)` treats duplicate final callbacks as idempotent: the first final result is kept, progress is recalculated, and scheduler callbacks are not triggered twice.
- `GET /status/api/tasks/{taskId}` now includes `stateValidation` so API/demo surfaces can expose the same state-audit result used by SDK callers.
- `MassApplication.loadMockData(...)` normalizes mock `supportedProjects`, lowercases `groupId`, and auto-seeds `LOGIN_READY` tokens when devices do not already have token data.
- `Device` and `Token` now expose `attributes: Map<String, String>` with defensive-copy and read-only semantics; callers may replace the whole map on update, but there is no per-entry mutation API.
- `WebSocketClientStarter` now starts on `ApplicationReadyEvent` behind `mock.client.auto-start=true`, so default `dev` startup includes mock client result write-back.
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so result write-back can be forced to `SUCCESS` or `FAILED`.
- `MassWebSocketClientImpl` now ignores `response=true` task frames to prevent mock client echo loops and duplicate result writes.
- Verified on `2026-04-13`: API-created tasks move `NEW -> READY -> RUNNING -> TERMINAL`, and persisted `TaskMsg` rows move `INIT -> SENT -> SUCCESS` with `deviceId` / `tokenId` / `batchId`.
- Verified on `2026-04-13`: with `mock.client.task-result-status=FAILED`, API-created tasks still move `NEW -> READY -> RUNNING -> TERMINAL`, `taskExecutedNumber` stays `0`, and persisted `TaskMsg` rows move `INIT -> SENT -> FAILED`.
- Verified on `2026-04-13`: after `RUNNING -> PAUSED`, real `TASK/step` callbacks can still finish the paused task to `TERMINAL` without requiring a manual resume.
- `TaskAssignWorker` uses `CopyOnWriteArrayList` for listeners.
- `TaskAssignWorker` now delayed-retries `READY` tasks that receive no device match, so they do not become orphaned after a single dequeue attempt.
- `TaskAssignWorker.stop()` calls `shutdownNow()` plus `awaitTermination(10s)`.
- `ServerSessionManager.removeSession()` evicts `ChannelHandlerContext` on disconnect.
- `DispatcherInboundHandler` sends structured JSON error frames instead of silently closing connections.
- `MassApplication.stop()` is now idempotent, and the mock Spring Boot entry no longer adds an extra manual shutdown hook around the runtime.
- `WebSocketServerImpl.stop()` now calls `shutdownGracefully().syncUninterruptibly()` on both EventLoopGroups so a single Ctrl-C is sufficient for clean exit.
- `TaskManager.advanceTaskMsgForCompletion()` always advances through `INIT→BINDING→SENT→RUNNING` before the final `markAsSuccess`/`markAsFailed` call, ensuring `RUNNING` appears in the state history for both success and failure paths.

## 7. Known Good Test Surface

Focused verified regression command on `2026-04-14`:

```bash
mvn --% -pl xa-mass-mock -am -Dtest=DeviceAttributesTest,TokenAttributesTest,DeviceMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskDeviceMatchingStrategyTest,TaskApiDelayedDeviceAvailabilityIntegrationTest,TaskApiTokenAttributeRoutingIntegrationTest,MassApplicationLoadMockDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Verified focused classes:

- `xa-mass-mock` end-to-end integration suites are now organized by domain instead of a flat `api/` package:
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiLifecycleGuardsIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiPauseCompletionIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiResumeAndCompleteIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/lifecycle/TaskApiTerminateRunningIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/results/TaskApiFailureResultIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/results/TaskApiCallbackReplayIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/results/TaskApiMixedResultsIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiDelayedDeviceAvailabilityIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiTokenAttributeRoutingIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMultiTaskAssignmentIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/audit/TaskApiStateValidationIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest.java` is the shared E2E base for HTTP helpers, task creation, snapshot polling, and dynamic WebSocket port wiring
- `xa-mass-mock/src/test/java/com/xa/mass/mock/client/MassWebSocketClientImplTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/starter/WebSocketClientStarterTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/listener/TaskAssignWorkerTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/model/DeviceMatchContextTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/rules/QLExpressRuleEvaluatorTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/strategy/RuleBasedTaskDeviceMatchingStrategyTest.java`
- `xa-mass-core/src/test/java/com/xa/mass/base/model/DeviceAttributesTest.java`
- `xa-mass-core/src/test/java/com/xa/mass/base/model/TokenAttributesTest.java`
- `xa-mass-runtime/src/test/java/com/xa/mass/starter/GatewayTaskResultHandlerTest.java`
- Existing engine/api/runtime unit and slice tests remain support coverage, but the primary acceptance gate is the grouped `xa-mass-mock` end-to-end domain suites

What the new focused coverage proves:

- default `dev` startup can auto-create mock client connections
- API create + approve flows through assignment, dispatch, result write-back, and terminal completion
- API create + approve also covers failed downstream result write-back through terminal completion
- API lifecycle guards for reject/approve, pause/resume, and delete protection are verified through real HTTP calls
- API terminate-from-running is verified after real assignment and before any mock callback completion, and terminal cleanup delete is also verified
- duplicate `TASK/step` callback replay is verified end-to-end through the real gateway path and keeps the first final result
- a paused task can still complete to `TERMINAL` through real callback write-back after assignment, without requiring a manual resume
- `GET /status/api/tasks/{taskId}` exposes `stateValidation` over the real HTTP/runtime path, including `needsResolution=true` when a task is manually reopened after all persisted message callbacks are already final
- invalid terminal metadata is also covered end-to-end: missing `terminalReason` and message/result mismatch both surface through `stateValidation.violations`
- token-attribute-based routing is covered end-to-end through a custom QLExpress rule using `tokenAttributes['country'] == taskCountry`
- mock clients no longer respond to server response frames
- mock result status can be forced to `FAILED` without changing business logic code paths
- duplicate `TASK/step` result callbacks are covered at engine/runtime regression level and keep the first final state
- paused tasks are closed to `TERMINAL` when their final callbacks arrive instead of getting stranded in `PAUSED` or resurrected back into `READY`
- `READY` tasks without an immediate device match stay in the assignment loop through delayed worker retry instead of silently orphaning

## 8. Historical Test Debt

Do not treat `xa-mass-engine/archive/v2/**` as current regression.

Reason:

- those tests/examples depend on removed `com.xa.mass.base.channel.messaging.*` packages
- they represent historical experimental code, not the current mainline
- they were moved out of `src/main/java` and `src/test/java` into `xa-mass-engine/archive/v2/` to reduce agent confusion

## 9. Known Problems

- `SimpleTaskScheduler.scheduleTasks()` is still a stub. Scheduler APIs are not the current source of `READY -> RUNNING`.
- EventBus is converged onto `channel/eventbus/core` and `channel/eventbus/event` namespace. Active implementation is Guava-backed; Redis remains fail-fast only.
- Redis and Database storage remain fail-fast only. `MEMORY` is the only implemented storage path.
- API integration coverage is still selective. Callback replay and running terminate/delete are now covered end-to-end, but some cancel follow-up variants still need integration tests.

## 10. Good Next Tasks

1. Add API-level integration coverage for remaining cancel follow-up variants.
2. Expand diagnostics around task dispatch and result write-back so stuck tasks are easier to localize.
3. Expand EventBus observability around the `channel/eventbus/core` path.
4. Keep UI work secondary until API/runtime convergence is stable.

## 11. Files Worth Opening Early

- `doc/AGENT_BASELINE.md`
- `doc/VERIFIED_RUNBOOK.md`
- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

## 12. If You Need Fast Orientation By Task

For startup/runtime issues:

- start in `xa-mass-mock`
- then inspect `xa-mass-runtime`

For task lifecycle/API issues:

- start in `xa-mass-api/internal/TaskApiController`
- then inspect `xa-mass-engine/TaskManager`
- then inspect `xa-mass-core/TaskStatus` and `Task`

For message/target data issues:

- inspect `TaskManager.createTask`
- inspect `TaskMsg`
- inspect mock fixtures

For WebSocket/session issues:

- inspect `xa-mass-gateway`
- verify runtime through `xa-mass-mock`

For event bus questions:

- inspect current call sites first
- do not start from architecture docs
- verify whether the code path uses the current `channel.eventbus.core` / `channel.eventbus.event` path before trusting older architecture notes

## 13. Working Rule

If code, runtime behavior, and docs disagree:

- trust code and verified runtime
- update docs after confirmation
- do not assume historical architecture docs describe the live path
- check the root `pom.xml` before treating a top-level directory as an active module
