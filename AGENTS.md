# XA Mass Platform Agent Handoff

This file is the fastest entry point for coding agents such as Claude Code, Codex, and similar tools.

## 0. TL;DR

- Real boot entry: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Trust code and verified runtime over repository docs
- Default `dev` startup now auto-starts mock WebSocket clients through `mock.client.auto-start=true`
- Port split is explicit: `server.port` for HTTP and `mass.websocket.port` for the gateway WebSocket server
- Project direction is library/SDK-first; HTTP pages and backend endpoints are validation/demo surfaces
- Current verified API task path: `NEW -> READY -> RUNNING -> TERMINAL`
- Pause/resume regression is also verified: `NEW -> READY -> PAUSED -> READY`
- Current focused mock/runtime regression is green
- `TaskApiIntegrationTest` now covers `create -> approve -> assign -> run -> complete`
- `TaskApiFailureResultIntegrationTest` now covers `create -> approve -> assign -> fail -> terminal`
- `TaskApiLifecycleGuardsIntegrationTest` now covers `reject -> approve`, `pause -> resume`, and delete guard through real HTTP APIs
- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames to avoid mock echo loops
- `mock.client.task-result-status` can force mock result frames to `SUCCESS` or `FAILED`
- Treat `engine/v2` as historical, not mainline

## 1. What This Repo Is

- Maven multi-module Java project
- Modules:
  - `xa-mass-core`
  - `xa-mass-engine`
  - `xa-mass-gateway`
  - `xa-mass-api`
  - `xa-mass-runtime`
  - `xa-mass-mock`

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
- The current runtime path still uses parts of `old.eventbus`.
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
- Runtime still uses parts of `old.eventbus` from this layer.

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
- `v2` exists but is not the active production path

Open first:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/DeviceManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Notes:

- Mainline engine tests are the active regression surface.
- `TaskDeviceMatchingStrategy` is now the engine extension seam for pluggable task-to-device matching policies.
- `src/test/java/com/xa/mass/engine/v2/**` is historical test debt, not active regression.

### `xa-mass-core`

Role:

- shared models
- task/task-msg enums and entities
- messaging abstractions
- JSON DSL
- event bus implementations

Current status:

- Stable enough for current mainline
- Contains both current and historical infra paths
- Maven module name is `xa-mass-core`; Java packages remain under `com.xa.mass.base`

Open first:

- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`

Notes:

- Event bus is split between `channel/eventbus` and `old/eventbus`.
- Current runtime still depends on old path in places.

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
- `TaskManager.createTask()` guards null `targetList`.
- `TaskManager` persists one `TaskMsg` per target with the correct `taskId`, distinct `msgId`, and actual target value.
- `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`, submits existing `READY` tasks on startup, and subscribes to READY events from approve/resume.
- `TaskDeviceAssignListener` sets `scheduleDeviceCnt` and transitions matched tasks from `READY` to `RUNNING`.
- `TaskDeviceAssignListener` now delegates matching to `TaskDeviceMatchingStrategy`; `RuleBasedTaskDeviceMatchingStrategy` is the current default.
- `SimpleTaskMsgAssignListener` reuses persisted `TaskMsg` records, fills `deviceId` / `tokenId` / `batchId`, and moves them to `SENT`.
- `GatewayTaskMsgPublisher` pushes task messages downstream as `TASK/step`.
- `GatewayTaskResultHandler` handles inbound `TASK/step` results and writes them back through `TaskManager.handleTaskMessageResult(...)`.
- `TaskManager.handleTaskMessageResult(...)` updates persisted `TaskMsg` state by `taskId + msgId`, recalculates progress, and closes `RUNNING` tasks to `TERMINAL` when all messages finish.
- `TaskManager.handleTaskMessageResult(...)` treats duplicate final callbacks as idempotent: the first final result is kept, progress is recalculated, and scheduler callbacks are not triggered twice.
- `MassApplication.loadMockData(...)` normalizes mock `supportedProjects`, lowercases `groupId`, and auto-seeds `LOGIN_READY` tokens when devices do not already have token data.
- `WebSocketClientStarter` now starts on `ApplicationReadyEvent` behind `mock.client.auto-start=true`, so default `dev` startup includes mock client result write-back.
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so result write-back can be forced to `SUCCESS` or `FAILED`.
- `MassWebSocketClientImpl` now ignores `response=true` task frames to prevent mock client echo loops and duplicate result writes.
- Verified on `2026-04-13`: API-created tasks move `NEW -> READY -> RUNNING -> TERMINAL`, and persisted `TaskMsg` rows move `INIT -> SENT -> SUCCESS` with `deviceId` / `tokenId` / `batchId`.
- Verified on `2026-04-13`: with `mock.client.task-result-status=FAILED`, API-created tasks still move `NEW -> READY -> RUNNING -> TERMINAL`, `taskExecutedNumber` stays `0`, and persisted `TaskMsg` rows move `INIT -> SENT -> FAILED`.
- `TaskAssignWorker` uses `CopyOnWriteArrayList` for listeners.
- `TaskAssignWorker.stop()` calls `shutdownNow()` plus `awaitTermination(10s)`.
- `ServerSessionManager.removeSession()` evicts `ChannelHandlerContext` on disconnect.
- `DispatcherInboundHandler` sends structured JSON error frames instead of silently closing connections.

## 7. Known Good Test Surface

Focused verified regression command on `2026-04-13`:

```bash
mvn --% -pl xa-mass-mock -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Verified focused classes:

- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiFailureResultIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/api/TaskApiLifecycleGuardsIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/client/MassWebSocketClientImplTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/starter/WebSocketClientStarterTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java`
- `xa-mass-runtime/src/test/java/com/xa/mass/starter/GatewayTaskResultHandlerTest.java`
- Existing engine/api/runtime regressions remain the primary mainline unit-test surface

What the new focused coverage proves:

- default `dev` startup can auto-create mock client connections
- API create + approve flows through assignment, dispatch, result write-back, and terminal completion
- API create + approve also covers failed downstream result write-back through terminal completion
- API lifecycle guards for reject/approve, pause/resume, and delete protection are verified through real HTTP calls
- mock clients no longer respond to server response frames
- mock result status can be forced to `FAILED` without changing business logic code paths
- duplicate `TASK/step` result callbacks are covered at engine/runtime regression level and keep the first final state

## 8. Historical Test Debt

Do not treat `xa-mass-engine/src/test/java/com/xa/mass/engine/v2/**` as current regression.

Reason:

- those tests/examples depend on removed `com.xa.mass.base.channel.messaging.*` packages
- they represent historical experimental code, not the current mainline

The engine POM excludes those tests from active test compilation/execution.

## 9. Known Problems

- `SimpleTaskScheduler.scheduleTasks()` is still a stub. Scheduler APIs are not the current source of `READY -> RUNNING`.
- Shutdown may still require two interrupts in the running app.
- EventBus is not yet converged. Runtime still uses Guava-based `old.eventbus` in places.
- Redis and Database storage remain fail-fast only. `MEMORY` is the only implemented storage path.
- API integration coverage is still selective. Duplicate result/idempotency is covered at unit level, but some end-to-end callback replay and cancel-path behavior still need integration tests.

## 10. Good Next Tasks

1. Add API-level integration coverage for callback replay/idempotency and remaining cancel-path variants.
2. Improve shutdown so a single Ctrl-C exits cleanly.
3. Converge EventBus call sites onto the current intended runtime abstraction.
4. Expand diagnostics around task dispatch and result write-back so stuck tasks are easier to localize.
5. Keep UI work secondary until API/runtime convergence is stable.

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
- verify whether the code path uses `old.eventbus` or `channel.eventbus`

## 13. Working Rule

If code, runtime behavior, and docs disagree:

- trust code and verified runtime
- update docs after confirmation
- do not assume historical architecture docs describe the live path



