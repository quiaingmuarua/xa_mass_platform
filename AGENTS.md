# XA Mass Platform Agent Handoff

This file is the fastest entry point for coding agents such as Claude Code, Codex, and similar tools.

## 0. TL;DR

- XA Mass Platform is a distributed task scheduling platform for large-scale work distribution, execution, result write-back, and lifecycle audit
- Real boot entry: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Trust code and verified runtime over repository docs
- Default `dev` startup now auto-starts mock WebSocket clients through `mock.client.auto-start=true`
- Port split is explicit: `server.port` for HTTP and `mass.websocket.port` for the gateway WebSocket server
- Project direction is library/SDK-first; HTTP pages and backend endpoints are validation/demo surfaces
- Mainline change discipline is now end-to-end integration-test-driven first; unit tests remain important, but they are support coverage rather than the primary acceptance gate
- Current verified API task path: `NEW -> READY -> RUNNING -> TERMINAL`
- Pause/resume regression is also verified: `NEW -> READY -> PAUSED -> READY`
- `TaskManager.createTask()` now accepts only the supported create contract fields: `userId`, `project`, `taskName`, `sharedConfig`, `targetList`, `countryCode`, `batchSize`, `defaultMsgMaxRetryCount`, and `openEnded`
- current mainline preserves request `batchSize` and now enforces it as a per-worker hard cap for each dispatch round
- task-create requests fail fast when `targetList` is empty/null, when `project` is unsupported, or when clients send unknown JSON fields such as retired `targetJsonList` / `targetType` / `extraParams`
- `PUT /status/api/tasks/{taskId}` is now intentionally narrower than create: it accepts only metadata fields (`userId`, `project`, `taskName`, `sharedConfig`, `countryCode`, `batchSize`), rejects `targetList` and other unknown fields, and only allows edits while the task is still `NEW` or `BLOCKED`
- `Task` aggregate counters are now named by real meaning:
  - `taskTargetNumber`
  - `taskEligibleNumber`
  - `taskSuccessNumber`
  - `taskNonSuccessNumber`
- `Task.terminalReason` now distinguishes manual cancel from message-driven terminal closure
- `TaskTerminalPolicy` is now the engine seam for future task-level stop rules such as max runtime, success-rate thresholds, or retry-budget exhaustion
- `TaskManager.validateTaskState()` now provides an explicit SDK-facing audit for `Task + TaskMsg` consistency and pending terminal resolution
- Engine regression now verifies that paused tasks close to `TERMINAL` once all `TaskMsg` callbacks finish
- Engine regression now verifies that `READY` tasks without a worker match are retried instead of falling out of the assignment loop
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
- `TaskApiWorkerContextAttributeRoutingIntegrationTest` now covers worker-context-attribute-based routing through the real assignment and gateway path
- `TaskApiSingleWorkerReuseIntegrationTest` now covers normal `TERMINAL` completion releasing a single worker/worker-context for the next task
- `TaskApiTerminateReuseIntegrationTest` now covers manual `RUNNING -> TERMINAL` release so the same single worker/worker-context can be assigned again
- `TaskApiMinimumWorkerGateIntegrationTest` now covers `minRequiredWorkerCount` as a real start gate: one worker is not enough to leave `READY` when the task requires two
- `TaskApiMultiRoundDispatchIntegrationTest` now covers a single worker completing a multi-target task across multiple dispatch rounds when `batchSize=1`
- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames to avoid mock echo loops
- `mock.client.task-result-status` can force mock result frames to `SUCCESS` or `FAILED`
- `Worker.attributes` and `WorkerContext.attributes` are now read-only auxiliary rule labels for matching and diagnostics only
- `Worker.status` is the single online truth, and runtime worker lock truth now lives only in `WorkerStorage` / `WorkerManager.isLocked(...)`
- `WorkerStatus`, `WorkerContext`, and dispatch-time worker-context binding are now stricter: null statuses are rejected, worker-context release only frees real dispatch ownership, and assignment moves worker contexts into `OCCUPIED`
- `WorkerContextStatus` vocabulary is domain-neutral: `IDLE` (free), `RESERVED` (pre-allocated), `OCCUPIED` (executing), `BLOCKED` (manually locked), `INVALID` (unusable); dispatch-time worker-context ownership now uses the `RESERVED -> OCCUPIED` progression.
- `Task.sharedConfig: Map<String,Object>` replaces the former `textContent: String`; all keys are spread into WebSocket dispatch params alongside `TaskMsg.input` keys, so existing workers receive `textContent` transparently when it is stored in `sharedConfig`
- `TaskMsg.input: Map<String,Object>` replaces the former `target: String`; `getTarget()` is a backwards-compat accessor reading `input["target"]`
- `TaskCreateRequestDto.defaultMsgMaxRetryCount` (default `3`) configures per-task retry budget; callers may set to `0` to disable retries
- `Task.openEnded=true` suppresses automatic terminal closure; append work items at runtime via `POST /status/api/tasks/{taskId}/items`, then close the append window with `PUT /status/api/tasks/{taskId}/seal`
- Treat `engine/v2` as historical archive material, not mainline

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
- `Worker` is the current worker adapter name. It should be read as the current concrete worker implementation, not as the universal final name for all worker/resource forms.
- `WorkerContext` is optional worker context such as credentials, account scope, or capability context. Not every worker model must require one.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress the platform back into single-purpose fields such as top-level `textContent`.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals. Do not re-couple routing truth to `workerGroupId`.
- `attributes` on `Worker` and `WorkerContext` are auxiliary rule labels only. They are not a second source of lifecycle, lock, or online truth.
- New features should extend the abstract platform model first. Do not let the current `worker/worker-context` reference scenario collapse the platform definition back into a single vertical system.

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
- `ws://localhost:18088/ws`

Default `dev` startup facts:

- `xa-mass-mock/src/main/resources/application.yml` sets `mock.client.auto-start=true`
- `server.port` is the Spring Boot HTTP port, currently `8088`
- `mass.websocket.port` is the gateway WebSocket port, currently `18088`
- `WebSocketClientStarter` listens on `ApplicationReadyEvent`
- Mock worker clients now connect automatically to the gateway in the default verified startup path

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
- worker assignment
- rule management

Current status:

- Mainline implementation lives here
- Active production code lives under `xa-mass-engine/src/main/java/com/xa/mass/engine`
- Historical `v2` code has been moved under `xa-mass-engine/archive/v2/` to keep it out of the active source tree

Open first:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/WorkerManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Notes:

- Mainline engine tests are the active regression surface.
- `TaskWorkerMatchingStrategy` is now the engine extension seam for pluggable task-to-worker matching policies.
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
- `Task.taskRoutingCountryCode` is the active routing-country input; older `taskCountry` naming is retired from the mainline.
- `WorkerManager.getWorkersByGroupId(...)` / `WorkerStorage.getWorkersByGroupId(...)` are grouping helpers only; do not treat them as country-routing APIs.
- `RuleBasedTaskWorkerMatchingStrategy` no longer prefilters candidates by worker `workerGroupId`; routing-country satisfaction should come from worker-context-facing signals and explicit rules.
- `WorkerMatchContext` now exposes nested `workerAttributes` and `workerContextAttributes` maps to QLExpress rules.
- `SimpleTaskMsgAssignListener` reuses persisted `TaskMsg` records, fills `workerId` / `workerContextId` / `batchId`, moves them to `SENT`, and now round-robins messages across workers up to `batchSize` per worker per round.
- `SimpleTaskMsgAssignListener` now also binds dispatchable worker contexts to the current task and advances them into `OCCUPIED`; non-dispatchable worker-context states are skipped instead of being silently reused.
- `TaskResourceReleaseListener` now releases a worker/worker-context slot as soon as that worker has no more in-flight `TaskMsg` rows for the current task, then re-submits the still-`RUNNING` task when pending `INIT` messages remain.
- `GatewayTaskMsgPublisher` pushes task messages downstream as `TASK/step`.
- `GatewayTaskResultHandler` handles inbound `TASK/step` results and writes them back through `TaskManager.handleTaskMessageResult(...)`.
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
- `TaskManager.cancelTask(...)` now drains in-flight `TaskMsg` rows during manual terminal closure: `INIT/BINDING -> FAILED`, `SENT/RUNNING -> EXPIRED`.
- `GET /status/api/tasks/{taskId}` now includes `stateValidation` so API/demo surfaces can expose the same state-audit result used by SDK callers.
- `MassApplication.loadMockData(...)` normalizes mock `supportedProjects`, lowercases `workerGroupId`, loads explicit mock `workerContexts` when present, and only auto-seeds minimal `IDLE` fallback worker contexts for workers that still have none.
- `WorkerManager` now treats `Worker.status` as the single online truth for matching/runtime availability; gateway online/offline events update the worker model directly instead of maintaining a separate online-state registry.
- `WorkerManager` / `WorkerStorage` now also own the single worker-lock truth; active mainline code should read lock state through `WorkerManager.isLocked(...)` instead of from `Worker`.
- `Worker` and `WorkerContext` now expose `attributes: Map<String, String>` with defensive-copy and read-only semantics; callers may replace the whole map on update, but there is no per-entry mutation API.
- `AssignmentRecordService` now snapshots `workerLocked` from live runtime lock state instead of from stale `Worker` fields.
- `WebSocketClientStarter` now starts on `ApplicationReadyEvent` behind `mock.client.auto-start=true`, so default `dev` startup includes mock client result write-back.
- `WebSocketClientStarter` passes `mock.client.task-result-status` into each mock client so result write-back can be forced to `SUCCESS` or `FAILED`.
- `MassWebSocketClientImpl` now ignores `response=true` task frames to prevent mock client echo loops and duplicate result writes.
- Verified on `2026-04-13`: API-created tasks move `NEW -> READY -> RUNNING -> TERMINAL`, and persisted `TaskMsg` rows move `INIT -> SENT -> SUCCESS` with `workerId` / `workerContextId` / `batchId`.
- Verified on `2026-04-13`: with `mock.client.task-result-status=FAILED`, API-created tasks still move `NEW -> READY -> RUNNING -> TERMINAL`, `taskSuccessNumber` stays `0`, and persisted `TaskMsg` rows move `INIT -> SENT -> FAILED`.
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
- `TaskManager.advanceTaskMsgForCompletion()` always advances through `INIT -> BINDING -> SENT -> RUNNING` before the final `markAsSuccess`/`markAsFailed` call, ensuring `RUNNING` appears in the state history for both success and failure paths.

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

**Open-ended tasks** (`openEnded=true`):

- The terminal policy never auto-closes an open-ended task, even when all current messages are final.
- Append new work items at runtime: `POST /status/api/tasks/{taskId}/items` with body `{"inputs": [{...}, ...]}`.
- Close the append window with `PUT /status/api/tasks/{taskId}/seal`; once the append window is closed, the task terminates normally after all remaining messages finish.
- Typical use case: crawler or agent pipeline where the full work list is not known at task creation time.

## 7. Known Good Test Surface

Focused verified regression command on `2026-04-14`:

```bash
mvn -pl xa-mass-mock -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,MassApplicationLoadMockDataTest -Dsurefire.failIfNoSpecifiedTests=false test
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
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiDelayedWorkerAvailabilityIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiWorkerContextAttributeRoutingIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMultiTaskAssignmentIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMultiRoundDispatchIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiSingleWorkerReuseIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiTerminateReuseIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMinimumWorkerGateIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/audit/TaskApiStateValidationIntegrationTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest.java` is the shared E2E base for HTTP helpers, task creation, snapshot polling, and dynamic WebSocket port wiring
- `xa-mass-mock/src/test/java/com/xa/mass/mock/client/MassWebSocketClientImplTest.java`
- `xa-mass-mock/src/test/java/com/xa/mass/mock/starter/WebSocketClientStarterTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/policy/TaskTerminalPolicy.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/policy/AllMessagesFinalTaskTerminalPolicy.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/listener/TaskAssignWorkerTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListenerTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/model/WorkerMatchContextTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/rules/QLExpressRuleEvaluatorTest.java`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategyTest.java`
- `xa-mass-core/src/test/java/com/xa/mass/base/model/WorkerAttributesTest.java`
- `xa-mass-core/src/test/java/com/xa/mass/base/model/WorkerContextAttributesTest.java`
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
- worker-context-attribute-based routing is covered end-to-end through a custom QLExpress rule using `workerContextAttributes['country'] == taskRoutingCountryCode`
- assignment diagnostics now snapshot runtime worker lock state instead of stale `Worker` model fields
- normal terminal completion releases worker-context/worker occupancy so a later task can reuse the same single-worker slot
- manual `RUNNING -> TERMINAL` closure also releases worker-context/worker occupancy so the next task can reuse the same single-worker slot
- `minRequiredWorkerCount` is enforced as a start threshold: a task remains `READY` until enough workers are simultaneously matchable
- terminal closure is now explicitly policy-driven in code, even though the default policy still means "all task messages are final"
- mock clients no longer respond to server response frames
- mock result status can be forced to `FAILED` without changing business logic code paths
- duplicate `TASK/step` result callbacks are covered at engine/runtime regression level and keep the first final state
- paused tasks are closed to `TERMINAL` when their final callbacks arrive instead of getting stranded in `PAUSED` or resurrected back into `READY`
- `READY` tasks without an immediate worker match stay in the assignment loop through delayed worker retry instead of silently orphaning

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
