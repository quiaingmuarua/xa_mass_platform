# XA Mass Platform Agent Handoff

This file is the fastest entry point for coding agents such as Claude Code, Codex, and similar tools.

## 0. TL;DR

- Real boot entry: `xa-mass-mock`
- Do not start from `xa-mass-starter`
- Trust code and verified runtime over repository docs
- Current verified task path: `NEW -> READY -> PAUSED -> READY`
- Current verified engine regression is green
- Treat `engine/v2` as historical, not mainline

## 1. What This Repo Is

- Maven multi-module Java project
- Modules:
  - `xa-mass-base`
  - `xa-mass-engine`
  - `xa-mass-gateway`
  - `xa-mass-api`
  - `xa-mass-starter`
  - `xa-mass-mock`

## 2. Read This First

Treat repository docs with mixed trust.

Trust order:

1. Code
2. Verified runtime behavior
3. `AGENTS.md`
4. `doc/AGENT_BASELINE.md`
5. `doc/VERIFIED_RUNBOOK.md`
6. module READMEs / top-level README / quick references
7. `old` / `v2` / refactor / todo docs

## 3. Real Entry Point

Current verified Spring Boot entrypoint:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`

Do not assume:

- `xa-mass-starter` is the runnable Spring Boot app
- `MassApplication.java` is a Spring Boot entry

`xa-mass-starter` is currently a lifecycle/composition layer, not the verified Boot entry.

## 4. Verified Startup

Run from repo root:

```bash
./mvnw -DskipTests compile
./mvnw -pl xa-mass-mock -am dependency:build-classpath \
  -Dmdep.outputFile=/tmp/xa-mass-mock.cp \
  -DincludeScope=runtime
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:$(cat /tmp/xa-mass-mock.cp)" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Verified endpoints:

- `http://localhost:8088/status`
- `http://localhost:8088/status/tasks`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088`

## 5. Current Reality, Not Marketing

- The app can compile and run.
- The current runtime path still uses parts of `old.eventbus`.
- New EventBus docs describe target architecture, not fully verified runtime reality.
- `v2` is not the mainline implementation.
- Some historical docs overstate coverage and completion.

## 5.1 Module Map

### `xa-mass-mock`

Role:

- Real Spring Boot entrypoint
- Wires `api + starter + gateway + engine`
- Loads mock data and publishes initial task events

Current status:

- Verified runnable
- Best module for end-to-end validation

Open first:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-mock/src/main/resources/application.yml`
- `xa-mass-mock/src/main/resources/mock/mock_tasks.json`

Notes:

- This is the real operational entry, not just a demo shell.
- Use this module when verifying runtime behavior.

### `xa-mass-starter`

Role:

- Lifecycle/composition layer
- Builds and starts `MassApplication`, `MassEngine`, `MassGateway`

Current status:

- Important internally
- Not the verified Spring Boot entrypoint

Open first:

- `xa-mass-starter/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-starter/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `xa-mass-starter/src/main/java/com/xa/mass/starter/MassEngine.java`

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
- Not an independent verified app

Open first:

- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-api/src/main/java/com/xa/mass/api/internal/StatusPageController.java`
- `xa-mass-api/src/main/resources/templates/tasks.html`

Notes:

- Task lifecycle endpoints have been aligned to `TaskManager`.
- API tests are still the highest-value missing test layer.

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

- Mainline engine tests are now small but green.
- `src/test/java/com/xa/mass/engine/v2/**` is historical test debt, not active regression.

### `xa-mass-base`

Role:

- shared models
- task/task-msg enums and entities
- messaging abstractions
- JSON DSL
- event bus implementations

Current status:

- Stable enough for current mainline
- Contains both current and historical infra paths

Open first:

- `xa-mass-base/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- `xa-mass-base/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-base/src/main/java/com/xa/mass/base/model/TaskMsg.java`

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
- Not independently validated as standalone app

Open first:

- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/server/WebSocketServerImpl.java`
- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/dispatcher/ServerMessageDispatcher.java`

Notes:

- WebSocket port `18088` is part of the verified startup path.
- Session/queue APIs exist, but some observability surfaces still look thin.

## 6. Task Lifecycle Status

Verified current lifecycle behavior:

```
NEW ──approve──► READY ──pause──► PAUSED ──resume──► READY
 │                 │                                    │
 └──reject──► BLOCKED ──approve──► READY               │
 │                                                       │
 └──cancel/terminate──────────────────────────────► TERMINAL
```

State machine constraints enforced in code (`TaskStatus.canTransitionTo`):

| Action | Allowed from | Target |
|--------|-------------|--------|
| `approveTask` | NEW, BLOCKED | READY |
| `rejectTask` | NEW | BLOCKED |
| `pauseTask` | READY | PAUSED |
| `resumeTask` | PAUSED | READY |
| `cancelTask` | any non-TERMINAL | TERMINAL |
| `deleteTask` | **NEW, TERMINAL only** | (physical delete) |

Important current implementation facts (verified through Phase 1–4 fixes):

- `TaskApiController` uses `TaskManager` lifecycle methods for all state changes
- `deleteTask()` now enforces state guard — READY/PAUSED/RUNNING tasks cannot be deleted; returns `success=false` with reason
- `TaskManager.createTask()` guards null `targetList` (no NPE)
- `TaskManager` task-message creation preserves:
  - correct `taskId`
  - distinct `msgId` per message
  - real `targetList` values as `target` field on each `TaskMsg`
- `SimpleTaskMsgAssignListener` now correctly instantiates `TaskMsg` with `deviceId`, `tokenId`, `batchId` (was previously commented out, so `pushQueue` was always empty)
- `TaskAssignWorker` uses `CopyOnWriteArrayList` for listeners (was `ArrayList`, ConcurrentModificationException risk)
- `TaskAssignWorker.stop()` calls `shutdownNow()` + `awaitTermination(10s)` (was leaking threads)
- `ServerSessionManager.removeSession()` correctly evicts `ChannelHandlerContext` on disconnect (was memory leak)
- `DispatcherInboundHandler` sends structured JSON error frames to clients instead of silently closing the channel

## 7. Known Good Test Surface

Full suite command (verified 2026-04-13):

```bash
./mvnw clean test
```

Result: **618 tests, 0 failures, 0 errors** across all modules.

Key test classes by module:

| Module | Test Class | Tests |
|--------|-----------|-------|
| xa-mass-engine | `TaskManagerLifecycleTest` | 7 |
| xa-mass-engine | `TaskAssignWorkerTest` | 5 |
| xa-mass-engine | `SimpleTaskMsgAssignListenerTest` | 5 |
| xa-mass-engine | `TaskDeviceAssignListenerTest` | 4 |
| xa-mass-engine | `DeviceManagerTest` | 12 |
| xa-mass-engine | `RuleManagerTest` | 14 |
| xa-mass-engine | `TaskStorageFactoryTest` | 10 |
| xa-mass-gateway | `DispatcherInboundHandlerTest` | 5 |
| xa-mass-gateway | `MessageHandlerRegistryTest` | 8 |
| xa-mass-gateway | `ProcessEnvelopeMiddlewareTest` | 4 |
| xa-mass-gateway | `ServerSessionManagerShutdownTest` | 3 |
| xa-mass-api | `TaskApiControllerTest` | 15 |
| xa-mass-starter | `MassEngineStopTest` | 4 |
| xa-mass-starter | `MassApplicationStopOrderTest` | 2 |

Single-module commands:

```bash
./mvnw -pl xa-mass-engine -am clean test
./mvnw -pl xa-mass-gateway -am clean test
./mvnw -pl xa-mass-api -am clean test
```

## 8. Historical Test Debt

Do not treat `xa-mass-engine/src/test/java/com/xa/mass/engine/v2/**` as current regression.

Reason:

- those tests/examples depend on removed `com.xa.mass.base.channel.messaging.*` packages
- they represent historical experimental code, not the current mainline

The engine POM currently excludes those tests from `testCompile` and `surefire`.

## 9. Known Problems

- **MassEngine mock-only**: `TaskAssignWorker` only starts when `config.isMockMode()` is true. In non-mock production config, tasks reach READY but are never auto-assigned to devices.
- **pushQueue not wired to transport**: `SimpleTaskMsgAssignListener` builds the queue of `TaskMsg` correctly but does not push them to the WebSocket downstream. The gateway transport is a separate layer not yet connected.
- **scheduleTasks() is a stub**: `SimpleTaskScheduler.scheduleTasks()` always returns an empty list. Tasks do not auto-transition to RUNNING.
- **Shutdown still needs two interrupts**: The running app may need Ctrl-C twice to fully exit.
- **EventBus not converged**: Runtime uses old Guava-based `EventBusFactory.get("guava")` in places. New `StreamEventBusFacade` is the target but not fully adopted.
- **Redis/Database storage unimplemented**: Both throw `UnsupportedOperationException` at creation time (fail-fast). Only MEMORY storage works.

## 10. Good Next Tasks

Best next tasks for an agent:

1. Wire `pushQueue` → WebSocket downstream (connect `SimpleTaskMsgAssignListener` output to the gateway transporter)
2. Implement auto-transition: READY → RUNNING when devices are assigned
3. Start `TaskAssignWorker` unconditionally (decouple from mockMode)
4. Converge EventBus: migrate `EventBusFactory.get("guava")` call sites to `StreamEventBusFacade`
5. Add `POST /api/message/send` endpoint for manual device push
6. Improve shutdown: single Ctrl-C should cleanly exit

## 11. Files Worth Opening Early

- `doc/AGENT_BASELINE.md`
- `doc/VERIFIED_RUNBOOK.md`
- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-base/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

## 12. If You Need Fast Orientation By Task

For startup/runtime issues:

- start in `xa-mass-mock`
- then inspect `xa-mass-starter`

For task lifecycle/API issues:

- start in `xa-mass-api/internal/TaskApiController`
- then inspect `xa-mass-engine/TaskManager`
- then inspect `xa-mass-base/TaskStatus` and `Task`

For message/target data issues:

- inspect `TaskManager.createTask`
- inspect `TaskMsg`
- inspect `mock_tasks.json`

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
