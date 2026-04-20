# XA Mass Platform Agent Handoff

This file is the fastest entry point for coding agents such as Claude Code, Codex, and similar tools.

## 0. Navigation Index

Use this index before reading the full document end to end.

If you need fast orientation:

- Platform definition and hard constraints: [0. TL;DR](#0-tldr), [0.1 Platform Definition](#01-platform-definition), [0.2 Architectural Guardrails](#02-architectural-guardrails), [0.3 Encoding And Comment Rules](#03-encoding-and-comment-rules)
- Active repository truth: [1. What This Repo Is](#1-what-this-repo-is), [2. Read This First](#2-read-this-first), [5. Current Reality, Not Marketing](#5-current-reality-not-marketing), [5.1 Module Map](#51-module-map)
- Startup and runtime entry: [3. Real Entry Point](#3-real-entry-point), [4. Verified Startup](#4-verified-startup)
- State machine and payload model: [6. Task Lifecycle Status](#6-task-lifecycle-status), [6.1 Platform Model](#61-platform-model)
- Regression surface: [7. Known Good Test Surface](#7-known-good-test-surface)
- Current gaps and next work: [9. Known Problems](#9-known-problems), [10. Good Next Tasks](#10-good-next-tasks)
- Fast file-entry shortcuts: [11. Files Worth Opening Early](#11-files-worth-opening-early), [12. If You Need Fast Orientation By Task](#12-if-you-need-fast-orientation-by-task), [13. Working Rule](#13-working-rule)

## 0. TL;DR

- XA Mass Platform is a distributed task scheduling platform for large-scale work distribution, execution, result write-back, and lifecycle audit
- Real boot entry: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Trust code and verified runtime over repository docs
- Project direction is library/SDK-first; HTTP pages and backend endpoints are validation/demo surfaces
- Mainline change discipline is now end-to-end integration-test-driven first; unit tests remain important, but they are support coverage rather than the primary acceptance gate
- Default `dev` startup auto-connects mock WebSocket clients when `mock.client.auto-start=true`
- Port split is explicit: `server.port` for HTTP and `mass.websocket.port` for gateway WebSocket
- The most important baseline docs are `doc/STATE_MACHINE_BASELINE.md`, `doc/TRACE_CONTRACT.md`, and `doc/E2E_BASELINE.md`
- Verified mainline task lifecycle is `NEW -> READY -> RUNNING -> TERMINAL`, with pause/resume `NEW -> READY -> PAUSED -> READY`
- Create contract is strict: `userId`, `project`, `taskName`, `sharedConfig`, `targetList`, `routingCode`, `batchSize`, `defaultMsgMaxRetryCount`, and `openEnded`; unknown retired fields fail fast
- Update contract is narrower than create and only allowed while the task is `NEW` or `BLOCKED`
- `batchSize` is a per-worker hard cap for each dispatch round
- Runtime blocking and review rejection are intentionally distinct: `rejectTask` is `NEW -> BLOCKED`, while `blockTask` is `READY/RUNNING -> BLOCKED`
- Task aggregate counters now use their real meanings: `taskTargetNumber`, `taskEligibleNumber`, `taskSuccessNumber`, `taskNonSuccessNumber`
- Read terminal tasks as `TaskStatus + terminalReason`; `TaskTerminalPolicy` is the seam for future stop policies
- `TaskManager.validateTaskState()` is the SDK-facing audit for `Task + TaskMsg` consistency and pending resolution
- `Task.sharedConfig` is the task-level payload boundary; `TaskMsg.input/output` is the work-item payload boundary; `getTarget()` is only a backwards-compat accessor
- Keep task closure modeled as single final `TERMINAL` plus `terminalReason`; do not split task status into multiple terminal enums without an intentional kernel redesign
- Treat `TaskMsgStatus` as the platform lifecycle contract, not as a full transport-event history
- `Worker.status` is the single online truth; worker lock truth lives in `WorkerStorage` / `WorkerManager.isLocked(...)`
- `Worker.attributes` and `WorkerContext.attributes` are read-only auxiliary rule labels only
- Worker manual debug chat is a side-channel control flow, not part of `TaskMsg` lifecycle; current verified protocol is `CONTROL/manual-chat -> EVENT/manual-chat`
- `WorkerContext` is optional; stateless workers are part of the verified mainline
- The current runtime concurrency model is conservative: one worker is one active execution lane even if it owns multiple worker contexts
- Regression focus is healthy: mock/runtime E2E covers lifecycle, pause completion, callback replay, worker-context routing, stateless workers, single-worker reuse, minimum-worker gate, and multi-round dispatch
- Historical `v2` / archive engine generations have been removed from the current repository snapshot; if older notes mention them, treat those notes as stale history, not missing code

## 0.1 Platform Definition

The project definition is broader than the current mock/demo shell:

- The top-level product is a general distributed task scheduling platform. Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, then converge task-level completion state.
- The platform is intentionally scenario-agnostic. It does not define the business payload itself; it defines who is online, who can accept work, how work is dispatched, how results are written back, and how task state converges.
- The stable kernel is `Task / TaskMsg / assignment / result / audit / terminal policy`.
- The current mainline validates that kernel through a long-connection worker scenario built with `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be many things: phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Worker`, `WorkerContext`, WebSocket sessions, HTML pages, and demo REST APIs are current reference adapters and verification shells. They are not the permanent product boundary.
- Project direction remains library/SDK-first. Runtime shells exist to prove the kernel, not to redefine it.

## 0.2 Architectural Guardrails

Keep these constraints fixed unless the kernel itself is intentionally redesigned:

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy. UI/demo layers must not become the source of truth for those concepts.
- Task-level closure stays modeled as one final `TERMINAL` plus `terminalReason`. Do not split task status into multiple terminal enums such as `SUCCEEDED` / `FAILED` / `CANCELLED` unless API, validation, audit, and E2E baselines are being redesigned together.
- `TaskMsgStatus` is the platform lifecycle contract, not a complete transport-event log. If transport-level phases such as queueing, downstream ack, broker retry, or delivery confirmation become first-class, model them in trace/event data or a separate transport layer instead of overloading `TaskMsgStatus`.
- `Worker` is the current worker adapter name. It should be read as the current concrete worker implementation, not as the universal final name for all worker/resource forms.
- `WorkerContext` is optional worker context such as credentials, account scope, or capability context. Not every worker model must require one.
- Manual worker debug chat is a control/debug side-channel. Do not model it as `TaskMsg`, and do not let it mutate task lifecycle state.
- The current runtime concurrency model is conservative: one `Worker` is treated as one active execution lane even if it owns multiple `WorkerContext` rows. Do not assume same-worker parallel execution by multiple contexts unless worker-level locking, assignment, release, and E2E coverage are redesigned together.
- Active mainline no longer exposes compatibility single-context lookup by `workerId`; use `getWorkerContexts(workerId)` for ownership and `getWorkerContextById(workerContextId)` for precise mutation/read paths.
- `WorkerContext.workerId` is now the single owner truth for context attachment. Do not reintroduce `addWorkerContext(workerId, workerContext)`-style APIs that duplicate owner identity across parameters.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress the platform back into single-purpose fields such as top-level `textContent`.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals. Do not re-couple routing truth to `workerGroupId`.
- `attributes` on `Worker` and `WorkerContext` are auxiliary rule labels only. They are not a second source of lifecycle, lock, or online truth.
- New features should extend the abstract platform model first. Do not let the current `worker/worker-context` reference scenario collapse the platform definition back into a single vertical system.
- If lifecycle semantics change, update code, `STATE_MACHINE_BASELINE`, `TRACE_CONTRACT`, and E2E coverage together.

## 0.3 Encoding And Comment Rules

Keep these text-formatting rules fixed to reduce agent and Windows-tooling drift:

- Source files, Markdown docs, JSON, YAML, and HTML should be saved as UTF-8.
- New code comments should be written in English.
- When touching a file that already contains mojibake or encoding-drifted comments, prefer rewriting the touched comments into clear English instead of adding more localized comments beside them.
- Avoid adding new Chinese comments in Java source files. User-facing API payloads and business strings may still use the language required by the product, but source comments should stay English.
- If a file's displayed text looks garbled in Windows tooling, verify it with explicit UTF-8 decoding before assuming the file contents are corrupt.

## 1. What This Repo Is

- Maven multi-module Java project
- Distributed task scheduling platform with a library/SDK-first direction
- Current root reactor modules come from `pom.xml`, not from every top-level directory
- Modules:
  - `xa-mass-core`
  - `xa-mass-engine`
  - `xa-mass-gateway`
  - `xa-mass-api`
  - `xa-mass-runtime`
  - `xa-mass-mock`

Important boundary:

- for active shared models/enums/eventbus code, prefer `xa-mass-core`
- for active lifecycle/composition code, prefer `xa-mass-runtime`
- older docs may still mention removed modules such as `xa-mass-base` or `xa-mass-starter`; treat those names as historical only

## 2. Read This First

Treat repository docs with mixed trust.

Trust order:

1. Code
2. Verified runtime behavior
3. `AGENTS.md`
4. `doc/AGENT_BASELINE.md`
5. `doc/STATE_MACHINE_BASELINE.md`
6. `doc/TRACE_CONTRACT.md`
7. `doc/E2E_BASELINE.md`
8. `doc/VERIFIED_RUNBOOK.md`
9. module READMEs / internal API doc under `doc/` / task flow doc under `doc/engine/`
10. removed archive or `v2` material referenced by older notes - historical only, not expected local files

Deleted historical material that should not be treated as missing:

- `doc/daily/`
- former planning doc under `doc/`
- `doc/archive/`
- `xa-mass-engine/.../v2/new_engine_refactory.md`
- former v2 matching-strategy draft under `xa-mass-engine/.../v2/`
- removed top-level legacy modules such as `xa-mass-base` and `xa-mass-starter`

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
- `http://localhost:8088/status/workers`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088/ws`

Default startup facts:

- `xa-mass-mock/src/main/resources/application.yml` activates the `local` Spring profile by default (`spring.profiles.active: local`).
- `application-local.yml` is the active developer override (low connection limits, DEBUG logging). `application-dev.yml` targets CI/integration environments.
- `server.port` is the Spring Boot HTTP port, currently `8088`
- `mass.websocket.port` is the gateway WebSocket port, currently `18088`
- `WebSocketClientStarter` listens on `ApplicationReadyEvent`
- Mock worker clients now connect automatically to the gateway in the default verified startup path

Profile selection guide:
| Profile | File | When to edit |
|---------|------|--------------|
| `local` (default) | `application-local.yml` | Local developer tweaks (port, log level) |
| `dev` | `application-dev.yml` | CI / integration test overrides |
| `prod` | `application-prod.yml` | Production settings |

## 5. Current Reality, Not Marketing

- The app can compile and run.
- The current mainline is `core + engine + gateway + api + runtime + mock`, as defined by the root `pom.xml`.
- The active runtime path now uses the current `channel/eventbus/core` and `channel/eventbus/event` packages.
- New EventBus docs describe target architecture, not fully verified runtime reality.
- historical `v2` / archive code is no longer present in the current repository snapshot.
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
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/worker/WebSocketWorkerAdapter.java` is the concrete `WorkerAdapter` for WebSocket-connected workers; wires `GatewayTaskMsgPublisher` as the dispatch side.

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
- `StatusPageController` also exposes manual worker debug-chat endpoints: `POST /status/workers/send-message` and `GET /status/workers/message-history`.
- API happy-path integration coverage now exists, but API edge coverage is still incomplete.

### `xa-mass-engine`

Role:

- Main business logic
- task lifecycle
- worker assignment
- rule management

Current status:

- Mainline implementation lives here
- Active production code lives under `xa-mass-engine/src/main/java/com/xa/mass/engine`
- Historical `v2` / archive engine code has been removed from the current repository snapshot to reduce agent confusion

Open first:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/WorkerManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Notes:

- Mainline engine tests are the active regression surface.
- `TaskWorkerMatchingStrategy` is now the engine extension seam for pluggable task-to-worker matching policies.
- `WorkerAdapter` interface (`xa-mass-engine/worker/WorkerAdapter.java`) is the transport adapter seam; concrete implementations live outside the engine (currently `xa-mass-runtime`).
- If an older note references `xa-mass-engine/archive/v2/**`, treat it as historical drift rather than an active path.

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
- Many Java packages still use `com.xa.mass.base.*`; package names do not imply that a reactor module named `xa-mass-base` still exists in the current repo.

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
| `blockTask` | `READY`, `RUNNING` | `BLOCKED` |
| `pauseTask` | `READY`, `RUNNING` | `PAUSED` |
| `resumeTask` | `PAUSED` | `READY` |
| `cancelTask` | any non-`TERMINAL` | `TERMINAL` |
| `deleteTask` | `NEW`, `TERMINAL` only | physical delete |

Important current implementation facts:

- `TaskApiController` uses `TaskManager` lifecycle methods for all state changes.
- `deleteTask()` enforces the state guard. `READY`, `RUNNING`, and `PAUSED` tasks cannot be deleted.
- `TaskManager.createTask()` requires at least one materialized `targetList` entry, accepts only the supported create fields, and persists request `batchSize` onto the task.
- current mainline uses `batchSize` as the per-worker hard cap for each dispatch round, and remaining `INIT` messages are refilled in later rounds as worker/worker-context slots are released.
- `TaskApiController.updateTask(...)` is metadata-only: it rejects unsupported fields such as `targetList` and refuses to mutate `READY`, `RUNNING`, `PAUSED`, or `TERMINAL` tasks.
- `TaskManager.createTask()` also rejects unsupported `project` codes instead of silently falling back to `demoApp`.
- `TaskManager` persists one `TaskMsg` per target with the correct `taskId`, distinct `msgId`, and actual target value.
- `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`, submits existing `READY` tasks on startup, and subscribes to READY events from approve/resume.
- `TaskWorkerAssignListener` re-checks that the task is still `READY` after matching; if the task left `READY` during the matching window, dispatch is skipped.
- `TaskWorkerAssignListener` now treats `minRequiredWorkerCount` as a real start gate: if matched workers are below the minimum, the task stays `READY` and provisional locks are released.
- `TaskWorkerAssignListener` tracks `peakAssignedWorkerCount` as the high-water mark of workers actually used by the task and transitions matched tasks from `READY` to `RUNNING`.
- `TaskWorkerAssignListener` now delegates matching to `TaskWorkerMatchingStrategy`; `RuleBasedTaskWorkerMatchingStrategy` is the current default.
- `TaskWorkerAssignListener` also unlocks any surplus matched workers that were only needed to satisfy the start gate, so zero-message reservations do not leak.
- `Task.taskRoutingCode` is the active task-owned routing input; older task-country naming is retired from the mainline.
- `WorkerManager.getWorkersByGroupId(...)` / `WorkerStorage.getWorkersByGroupId(...)` are grouping helpers only; do not treat them as country-routing APIs.
- `RuleBasedTaskWorkerMatchingStrategy` no longer prefilters candidates by worker `workerGroupId`; routing-code satisfaction should come from worker-context-facing signals and explicit rules.
- `WorkerMatchContext` now exposes nested `workerAttributes` and `workerContextAttributes` maps to QLExpress rules.
- `WorkerMatchContext` also exposes `hasWorkerContext` and `taskHasRoutingRequirement`, so rules can distinguish stateless workers from worker-context-routed tasks.
- `SimpleTaskMsgAssignListener` reuses persisted `TaskMsg` records, fills `workerId` / `workerContextId` / `batchId`, moves them to `ASSIGNED`, and now round-robins messages across workers up to `batchSize` per worker per round.
- `SimpleTaskMsgAssignListener` now also binds dispatchable worker contexts to the current task and advances them into `OCCUPIED`; non-dispatchable worker-context states are skipped instead of being silently reused.
- `TaskResourceReleaseListener` now releases a worker/worker-context slot as soon as that worker has no more in-flight `TaskMsg` rows for the current task, then re-submits the still-`RUNNING` task when pending `INIT` messages remain.
- `GatewayTaskMsgPublisher` pushes task messages downstream as `TASK/step`.
- `GatewayTaskResultHandler` handles inbound `TASK/step` results and writes them back through `TaskManager.handleTaskMessageResult(...)`.
- `StatusPageController.sendWorkerMessage(...)` can send a manual worker debug message over the live gateway path; `CONTROL/manual-chat` is the verified debug-chat protocol default.
- `ManualDebugMessageHandler` records inbound `EVENT/manual-chat` acknowledgements into `WorkerDebugMessageStore`, and matched replies promote the outbound record from `QUEUED` to `DELIVERED`.
- `TaskManager.handleTaskMessageResult(...)` updates persisted `TaskMsg` state by `taskId + msgId`, recalculates progress, closes any non-final task to `TERMINAL` once all messages finish, and ignores late non-final callbacks after manual terminal closure.
- `TaskManager.updateTaskProgress(...)` now closes any non-final task to `TERMINAL` once all persisted `TaskMsg` rows are final, including tasks that were paused while callbacks were still arriving.
- `TaskManager` now delegates terminal-closure decisions through `TaskTerminalPolicy`; the current default remains `AllMessagesFinalTaskTerminalPolicy`.
- `TaskManager` now emits terminal-task notifications, and `TaskResourceReleaseListener` releases worker-context/worker occupancy on terminal closure so runtime locks do not leak across tasks.
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
  - future-ready reserved reasons already exist for policy-driven stop conditions:
    - `MAX_RUNTIME_REACHED`
    - `SUCCESS_RATE_REACHED`
    - `RETRY_BUDGET_EXHAUSTED`
- `TaskManager.handleTaskMessageResult(...)` treats duplicate final callbacks as idempotent: the first final result is kept, progress is recalculated, and scheduler callbacks are not triggered twice.
- `TaskManager.cancelTask(...)` now drains in-flight `TaskMsg` rows during manual terminal closure: `INIT -> FAILED(MANUAL_CANCELLED)`, `ASSIGNED/RUNNING -> EXPIRED(MANUAL_CANCELLED)`.
- `GET /status/api/tasks/{taskId}` now includes `stateValidation` so API/demo surfaces can expose the same state-audit result used by SDK callers.
- `MassApplication.loadMockData(...)` normalizes mock `supportedProjects`, lowercases `workerGroupId`, and loads explicit mock `workerContexts` only when they are provided; workers without mock `workerContexts` remain stateless.
- `WorkerManager` now treats `Worker.status` as the single online truth for matching/runtime availability; gateway online/offline events update the worker model directly instead of maintaining a separate online-state registry.
- `WorkerManager` / `WorkerStorage` now also own the single worker-lock truth; active mainline code should read lock state through `WorkerManager.isLocked(...)` instead of from `Worker`.
- `Worker` and `WorkerContext` now expose `attributes: Map<String, String>` with defensive-copy and read-only semantics; callers may replace the whole map on update, but there is no per-entry mutation API.
- `AssignmentRecordService` now snapshots `workerLocked` from live runtime lock state instead of from stale `Worker` fields.
- `WebSocketClientStarter` now starts on `ApplicationReadyEvent` behind `mock.client.auto-start=true`, so default `dev` startup includes mock client result write-back.
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so result write-back can be forced to `SUCCESS` or `FAILED`.
- `MassWebSocketClientImpl` now ignores `response=true` task frames to prevent mock client echo loops and duplicate result writes.
- Verified on `2026-04-13`: API-created tasks move `NEW -> READY -> RUNNING -> TERMINAL`, and persisted `TaskMsg` rows move `INIT -> ASSIGNED -> SUCCESS` with `workerId` / `workerContextId` / `batchId`.
- Verified on `2026-04-13`: with `mock.client.task-result-status=FAILED`, API-created tasks still move `NEW -> READY -> RUNNING -> TERMINAL`, `taskSuccessNumber` stays `0`, and persisted `TaskMsg` rows move `INIT -> ASSIGNED -> FAILED`.
- Verified on `2026-04-13`: after `RUNNING -> PAUSED`, real `TASK/step` callbacks can still finish the paused task to `TERMINAL` without requiring a manual resume.
- Verified on `2026-04-14`: a single worker/worker-context can be reused after both normal terminal completion and manual running-task termination.
- Verified on `2026-04-14`: a task with `minRequiredWorkerCount=2` stays `READY` with one matching worker and only advances once a second matching worker becomes available.
- `TaskAssignWorker` uses `CopyOnWriteArrayList` for listeners.
- `TaskAssignWorker` now delayed-retries `READY` tasks that receive no worker match, so they do not become orphaned after a single dequeue attempt.
- `TaskAssignWorker` now also accepts explicit `RUNNING` task re-dispatch requests for multi-round refill.
- `TaskAssignWorker` now also delayed-retries those `RUNNING` re-dispatch attempts when no slot is available yet, so refill is not dependent on a single callback timing window.
- `TaskAssignWorker.stop()` calls `shutdownNow()` plus `awaitTermination(10s)`.
- `ServerSessionManager.removeSession()` evicts `ChannelHandlerContext` on disconnect.
- `DispatcherInboundHandler` sends structured JSON error frames instead of silently closing connections.
- `MassApplication.stop()` is now idempotent, and the mock Spring Boot entry no longer adds an extra manual shutdown hook around the runtime.
- `WebSocketServerImpl.stop()` now calls `shutdownGracefully().syncUninterruptibly()` on both EventLoopGroups so a single Ctrl-C is sufficient for clean exit.
- `TaskMsgStatus` is now the logical item lifecycle (`INIT -> ASSIGNED -> RUNNING -> final`); transport-side assignment and retry details live in `TaskMsgAttempt`.
- `TaskMsg.finalReason` is now the item-level terminal explanation (`BUSINESS_SUCCESS`, `RETRY_EXHAUSTED`, `MANUAL_CANCELLED`, `LEASE_EXPIRED`, etc.).
- `TaskMsgAttempt` is now the assignment/lease/retry model; each dispatch round creates a new attempt and retry does not mutate a final attempt back to active.

## 6.1 Platform Model

The scheduling backbone is scenario-agnostic. The following mapping describes the current mainline carrier types, not a permanent product boundary:

| Abstract concept | Concrete type | Notes |
|---|---|---|
| Worker | `Worker` | Any long-connection client: phone, crawler, LLM agent, IM bot |
| Worker context | `WorkerContext` | Optional capability / credential context. Stateless workers do not need one. |
| Work item | `TaskMsg` | `input: Map<String,Object>` + `output: Map<String,Object>`. `target` is stored as `input["target"]` for backwards compat. |
| Shared config | `Task.sharedConfig` | `Map<String,Object>` injected by the platform into every dispatch params via `putAll`. Workers interpret the keys. |

Interpretation rules:

- The abstract concepts are the stable architecture boundary.
- The concrete types are the current reference scenario and default adapters.
- Future worker forms should preferentially reuse these abstract slots instead of redefining the platform around `worker/worker-context` vocabulary.

**Worker-context occupancy states** (domain-neutral since Phase A rename):

| Status | Meaning |
|---|---|
| `IDLE` | Free, can be reserved |
| `RESERVED` | Pre-allocated for a task, waiting for dispatch |
| `OCCUPIED` | Actively executing a task message |
| `BLOCKED` | Manually locked out of scheduling |
| `INVALID` | Permanently unusable |

Matching/runtime signal semantics:

- `isWorkerContextAllocatable`: the context can be newly reserved now (`IDLE` and not expired)
- `isWorkerContextAvailable`: same "truly free now" meaning, kept for readability in diagnostics
- `isWorkerContextUsable`: broader health signal for diagnostics and audits (`IDLE`, `RESERVED`, `OCCUPIED`, excluding expired, blocked, invalid)
- `hasWorkerContext`: whether the current worker candidate actually carries a `WorkerContext`
- `taskHasRoutingRequirement`: whether the current task requires worker-context-based routing signals
- A stateless worker can match only when the task does not require worker-context-specific routing; routing-required tasks must still be satisfied by worker-context signals

**Open-ended tasks** (`openEnded=true`):

- `openEnded` is the compatibility create flag; runtime lifecycle truth is `Task.intakeStatus`.
- `openEnded=true` initializes `intakeStatus=OPEN`; `sealTask()` transitions it to `SEALED`.
- The terminal policy never auto-closes an open-intake task, even when all current messages are final.
- Append new work items at runtime: `POST /status/api/tasks/{taskId}/items` with body `{"inputs": [{...}, ...]}`.
- Close the append window with `PUT /status/api/tasks/{taskId}/seal`; once the append window is closed, the task terminates normally after all remaining messages finish.
- Typical use case: crawler or agent pipeline where the full work list is not known at task creation time.

## 7. Known Good Test Surface

Focused verified regression command:

```bash
mvn -pl xa-mass-mock -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,WorkerManualDebugChatIntegrationTest,MassApplicationLoadMockDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Current regression shape:

- `xa-mass-mock` is the primary acceptance gate, with E2E suites grouped by domain under `com.xa.mass.mock.e2e`
- Domain groups are:
  - lifecycle
  - assignment
  - results
  - audit
  - support
- Representative E2E coverage includes:
  - create, approve, assign, run, and terminal completion
  - reject/approve, pause/resume, running terminate, and delete guard
  - callback replay, failed results, mixed results, and state validation
  - worker-context attribute routing and worker-without-context execution
  - manual worker debug chat with explicit `QUEUED -> DELIVERED -> RECEIVED` visibility
  - single-worker reuse, minimum-worker gate, delayed worker availability, and multi-round dispatch
- Shared E2E base: `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest.java`
- Important engine/runtime support coverage includes:
  - `TaskManagerLifecycleTest`
  - `TaskAssignWorkerTest`
  - `SimpleTaskMsgAssignListenerTest`
  - `WorkerMatchContextTest`
  - `QLExpressRuleEvaluatorTest`
  - `RuleBasedTaskWorkerMatchingStrategyTest`
  - `GatewayTaskResultHandlerTest`
  - `MassWebSocketClientImplTest`
  - `WebSocketClientStarterTest`

What this regression surface currently proves:

- default `dev` startup can auto-create mock client connections
- API lifecycle changes flow through real assignment, dispatch, callback write-back, and terminal convergence
- pause/resume, running terminate, callback replay, and state validation are covered through the real HTTP and gateway path
- worker-context routing and stateless-worker execution are both verified end to end
- single-worker reuse, minimum-worker gate, delayed worker availability, and multi-round refill are verified
- duplicate final callbacks stay idempotent, late callbacks after manual terminal closure are ignored, and paused tasks still close to `TERMINAL`

## 8. Historical Test Debt

Do not reconstruct old `v2` / archive test surfaces as current regression.

Reason:

- older references depended on removed `com.xa.mass.base.channel.messaging.*` packages
- they represented historical experimental code, not the current mainline
- the current repository intentionally keeps that code out of the active tree so agents start from live engine paths only

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
- `doc/STATE_MACHINE_BASELINE.md`
- `doc/TRACE_CONTRACT.md`
- `doc/E2E_BASELINE.md`
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
- keep `STATE_MACHINE_BASELINE`, `TRACE_CONTRACT`, and `E2E_BASELINE` aligned with the verified mainline
- do not assume historical architecture docs describe the live path
- check the root `pom.xml` before treating a top-level directory as an active module
