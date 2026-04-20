# XA Mass Platform Verified Runbook

Last updated: 2026-04-20

This runbook records only facts that were verified by code, tests, or real runtime behavior. If older docs disagree, trust code and runtime.

Document scope:

- startup commands
- runtime verification checks
- verified execution path
- focused regression coverage

Testing stance:

- the project treats end-to-end integration coverage as the primary acceptance gate
- unit and slice tests still matter, but they are support coverage
- the `xa-mass-dev-app` integration suites are organized by domain under `com.xa.mass.mock.e2e`

For endpoint inventory, request contracts, and response shapes, use [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

## 1. Current Positioning

- XA Mass Platform is a general distributed task scheduling platform.
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The platform is scenario-agnostic. It does not define the business payload; it defines worker availability, dispatch, result write-back, audit, and terminal convergence.
- The stable kernel is `Task / TaskMsg / assignment / result / audit / terminal policy`.
- The currently verified reference scenario is a long-connection worker path built with `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Worker`, `WorkerContext`, WebSocket, mock runtime, and HTTP pages are current verification adapters, not the permanent platform boundary.
- The repository direction remains library/SDK-first; HTTP/API surfaces are used primarily for validation and demonstration.

## 2. Current Conclusions

- The real Spring Boot entrypoint is `xa-mass-dev-app`.
- `xa-mass-sdk` is the consumer-facing dependency entry for third-party embedding.
- Embedded runtime composition now lives inside `xa-mass-sdk`; it is not a standalone Boot entry.
- `xa-mass-dev-app` starts runtime through `xa-mass-sdk`, exposes the current status/demo pages through `xa-mass-api`, and still wires engine-side manager/rule beans directly for dev and E2E validation.
- Keep API/UI out of `xa-mass-sdk` so third-party SDK consumers do not inherit demo HTTP surfaces by default.
- The current root reactor is `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-sdk`, and `xa-mass-dev-app`.
- historical module experiments such as `xa-mass-base` and `xa-mass-starter` are not part of the current repository snapshot.
- Default `dev` startup auto-starts mock WebSocket clients through `mock.client.auto-start=true`.
- The currently verified API happy path is:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> ASSIGNED -> SUCCESS`
- The pause/resume lifecycle regression is also verified:
  - `NEW -> READY -> PAUSED -> READY`
- The worker debug-chat path is also verified:
  - outbound `CONTROL/manual-chat`
  - inbound `EVENT/manual-chat`
  - history visibility `QUEUED -> DELIVERED -> RECEIVED`

Create-time validation is verified:

- supported create fields are limited to `userId`, `project`, `taskName`, `sharedConfig`, `targetList`, `routingCode`, `batchSize`, `defaultMsgMaxRetryCount`, `openEnded`, and `maxRuntimeSeconds`
- `targetList` must contain at least one materialized target
- unsupported `project` codes are rejected instead of silently falling back to `demoApp`
- unknown JSON fields such as retired `targetJsonList`, `targetType`, and `extraParams` are rejected at the API boundary
- request `batchSize` is preserved on the persisted task
- current mainline uses `batchSize` as the per-worker hard cap for each dispatch round
- `defaultMsgMaxRetryCount` defaults to `3`
- `openEnded=true` allows runtime append through `/items` until `/seal`
- `maxRuntimeSeconds=0` disables runtime-limit termination; positive values are enforced by `LeaseExpireWatchdog`

Update-time validation is verified:

- supported update fields are limited to `userId`, `project`, `taskName`, `sharedConfig`, `routingCode`, and `batchSize`
- `targetList` and other unknown JSON fields are rejected at the API boundary
- task edits are only allowed while status is `NEW` or `BLOCKED`

Additional verified rules:

- if all persisted `TaskMsg` callbacks finish while the task is `PAUSED`, the task is closed to `TERMINAL`
- assignment does not dispatch if a task leaves `READY` during the worker-matching window
- late callbacks after manual terminal closure are ignored instead of mutating task/message progress
- duplicate final callbacks are handled idempotently
- worker-context-attribute routing is verified end-to-end through `workerContextAttributes['country'] == taskRoutingCode`
- stateless-worker execution is verified end-to-end for tasks that do not declare worker-context-based routing requirements

## 3. Recommended Startup

Run from repo root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Windows guidance:

- prefer a short classpath: module `target/classes` plus `logs/runtime-libs/*`
- very long expanded `-cp` values can exceed Windows command-line limits and produce misleading missing-class errors

## 4. Boot Checks

HTTP:

```bash
curl -i http://127.0.0.1:8088/status
curl -i http://127.0.0.1:8088/status/tasks
curl -i http://127.0.0.1:8088/status/workers
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
```

WebSocket:

```bash
nc -zv 127.0.0.1 18088
```

Default `dev` startup facts:

- `MockApplicationSpringBootApp` is the verified entry path
- `WebSocketClientStarter` starts on `ApplicationReadyEvent`
- `xa-mass-dev-app/src/main/resources/application.yml` enables `mock.client.auto-start=true`
- `server.port` is the HTTP port, currently `8088`
- `mass.websocket.port` is the gateway WebSocket port, currently `18088`
- in the verified default path, mock workers connect automatically to `ws://localhost:18088/ws`
- `mock.client.task-result-status` can force mock result frames to `SUCCESS` or `FAILED`

## 5. Current Runtime Mainline

### 5.1 Lifecycle Entry Points

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","routingCode":"us","sharedConfig":{"textContent":"smoke"},"userId":"agent","targetList":["smoke-target-001","smoke-target-002"],"batchSize":1,"defaultMsgMaxRetryCount":3,"openEnded":false,"maxRuntimeSeconds":0}'
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/pause
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/resume
```

Verified state transitions:

- create: initial task status is `NEW`
- create: `targetList` is materialized into persisted `TaskMsg` rows
- create: only `userId`, `project`, `taskName`, `sharedConfig`, `targetList`, `routingCode`, `batchSize`, `defaultMsgMaxRetryCount`, `openEnded`, and `maxRuntimeSeconds` are part of the supported request contract
- create: unsupported `project` codes are rejected
- create: unknown JSON fields such as retired `targetJsonList`, `targetType`, and `extraParams` are rejected at the API boundary
- create: request `batchSize` is persisted onto the task
- create: `defaultMsgMaxRetryCount` is copied into each created `TaskMsg.maxRetryCount`
- create: `openEnded=true` prevents automatic terminal closure until the append window is sealed
- create: `maxRuntimeSeconds` is persisted onto the task and enforced only when greater than `0`
- update: only `userId`, `project`, `taskName`, `sharedConfig`, `routingCode`, and `batchSize` are part of the supported request contract
- update: `targetList` and other unknown JSON fields are rejected at the API boundary
- update: only `NEW` and `BLOCKED` tasks may be edited
- `approveTask`: `NEW`, `BLOCKED` -> `READY`
- `rejectTask`: `NEW` -> `BLOCKED`
- `blockTask`: `READY`, `RUNNING` -> `BLOCKED`
- `pauseTask`: `READY`, `RUNNING` -> `PAUSED`
- `resumeTask`: `PAUSED` -> `READY` or `TERMINAL` if the task already completed while paused
- `deleteTask`: only `NEW`, `TERMINAL`

Additional SDK-facing verified APIs:

- `TaskManager.resumeTaskDetailed(...)` distinguishes `RESUMED_TO_READY` from `COMPLETED_TO_TERMINAL`
- `TaskManager.resolveTaskStateFromMessages(...)` reports whether message aggregation is pending, finalizing, or already final
- `TaskManager.validateTaskState(...)` audits counters, terminal metadata, and persisted message aggregates

Task aggregate naming:

- `taskTargetNumber`: initial persisted target count
- `taskEligibleNumber`: target count currently included in progress/statistical aggregation
- `taskSuccessNumber`: persisted `TaskMsg` rows already in `SUCCESS`
- `taskNonSuccessNumber`: eligible rows that are not yet `SUCCESS`

Terminal interpretation:

- read terminal tasks as `TaskStatus + terminalReason`
- currently verified reasons include `MANUAL_CANCELLED`, `ALL_MESSAGES_SUCCEEDED`, `ALL_MESSAGES_FAILED`, and `MIXED_MESSAGE_RESULTS`

Open-ended verified contract:

- `POST /status/api/tasks/{taskId}/items` appends new work items via `{"inputs":[{...}, ...]}`
- `PUT /status/api/tasks/{taskId}/seal` closes the append window
- once sealed, the task resumes normal terminal convergence based on persisted `TaskMsg` finality

### 5.2 Assign and Run

Verified runtime path:

1. The task enters `READY` after approval or resume.
2. `MassEngine` starts `TaskAssignWorker` regardless of `mockMode`.
3. `MassEngine` resubmits pre-existing `READY` tasks at startup and subscribes to READY events from approve/resume.
4. `TaskWorkerAssignListener` performs worker matching.
5. `TaskWorkerAssignListener` delegates matching through `TaskWorkerMatchingStrategy`; the verified default is `RuleBasedTaskWorkerMatchingStrategy`.
6. On successful matching it updates `peakAssignedWorkerCount` as the task's worker-usage high-water mark and moves the task from `READY` to `RUNNING`, but only if the task is still `READY` when matching returns.
7. If no worker matches at that moment, `TaskAssignWorker` delayed-retries the task instead of letting it fall out of the assignment loop.
8. `SimpleTaskMsgAssignListener` reuses the persisted `TaskMsg` records created during task creation.
9. It round-robins pending `INIT` messages across matched workers and enforces `batchSize` as a per-worker cap for the current round.
10. Each dispatched `TaskMsg` is filled with `workerId`, `workerContextId`, and `batchId`, then moved to `ASSIGNED`.
11. Dispatch-time worker-context ownership is explicit: assignment binds allocatable worker contexts, advances them into `OCCUPIED`, and skips non-dispatchable worker-context states.
12. `minRequiredWorkerCount` is enforced before `READY -> RUNNING`; insufficient matched workers leave the task in `READY`.
13. Any workers matched only to satisfy the start gate, but not needed for current message dispatch, are unlocked immediately.
14. `GatewayTaskMsgPublisher` pushes the downstream payload as `TASK/step`.
15. When a worker has no more in-flight `TaskMsg` rows for the current task, `TaskResourceReleaseListener` releases that worker/worker-context slot and re-submits the still-`RUNNING` task if pending `INIT` messages remain.
16. Once the task reaches `TERMINAL`, `TaskResourceReleaseListener` releases any remaining worker-context/worker occupancy so later tasks can reuse the same runtime slot.

Matching-context facts:

- `Task.taskRoutingCode` is the active task-owned routing input for matching and diagnostics
- `WorkerManager.getWorkersByGroupId(...)` and `WorkerStorage.getWorkersByGroupId(...)` are grouping helpers only, not country-routing APIs
- routing-code satisfaction should come from worker-context-facing signals and explicit rules, not from `workerGroupId`
- `WorkerMatchContext` exposes nested `workerAttributes` and `workerContextAttributes` maps for QLExpress access such as `workerContextAttributes['country'] == taskRoutingCode`
- `WorkerMatchContext` also exposes `hasWorkerContext` and `taskHasRoutingRequirement` so rules can distinguish stateless workers from worker-context-routed tasks
- `MassApplication.loadMockData(...)` does not auto-seed fallback worker contexts; workers remain stateless unless mock data provides explicit `workerContexts`
- these maps are auxiliary rule labels only and are not the source of truth for lifecycle, lock, or online state
- `WorkerContext.workerId` is the single ownership source; active runtime code no longer passes a duplicate owner `workerId` into add-context APIs

### 5.3 Result Write-Back and Completion

Verified runtime path:

1. Mock clients receive `TASK/step`.
2. Mock clients send back a `TASK/step` result frame.
3. `GatewayTaskResultHandler` calls `TaskManager.handleTaskMessageResult(...)`.
4. `TaskManager` updates the persisted `TaskMsg` by `taskId + msgId`.
5. Each `TaskMsg` reaches `SUCCESS` or `FAILED`.
6. When all persisted task messages are final, `TaskManager.updateTaskProgress(...)` closes any non-final task to `TERMINAL`.

Important guards:

- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames so mock clients do not echo server response frames back into the system
- `TaskManager.handleTaskMessageResult(...)` ignores late non-final callbacks for tasks already closed to `TERMINAL`
- duplicate final callbacks are treated as idempotent no-ops after the first final result is stored
- `TaskMsgStatus` stays as the logical lifecycle (`INIT -> ASSIGNED -> RUNNING -> final`)
- assignment/lease/retry transport history is tracked in `TaskMsgAttempt`

### 5.4 Worker And Worker-Context Truth Sources

- `Worker.status` is the single online truth for runtime availability
- gateway online/offline events update the `Worker` model directly
- worker lock truth lives in `WorkerStorage` and is exposed through `WorkerManager.isLocked(...)`
- `Worker.attributes` and `WorkerContext.attributes` are defensive-copied, read-only auxiliary rule labels
- `WorkerContextStatus` vocabulary is `IDLE`, `RESERVED`, `OCCUPIED`, `BLOCKED`, `INVALID`
- `isWorkerContextAllocatable` is the matching gate for new reservations (`IDLE` and not expired)
- `isWorkerContextAvailable` now has the same strict "free now" meaning; `isWorkerContextUsable` is the broader diagnostic signal for non-blocked, non-invalid runtime contexts
- a worker without any `WorkerContext` can still be matched for tasks that do not require worker-context-specific routing
- normal terminal completion and manual `RUNNING -> TERMINAL` closure both release runtime occupancy so the same worker/worker-context can be reused
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` is the compatibility request/response projection
- `TaskMsg.workerId` / `workerContextId` / `batchId` are compatibility projections of the latest `TaskMsgAttempt`

### 5.5 Manual Worker Debug Chat

Verified debug path:

1. `POST /status/workers/send-message` accepts a manual worker message request.
2. The verified default debug protocol is `CONTROL/manual-chat`.
3. `StatusPageController` normalizes the outbound payload with debug-chat metadata such as `messageKind`, `workerId`, `sentAt`, and `expectReply`.
4. The server records the outbound message into `WorkerDebugMessageStore` as `QUEUED`.
5. Mock workers receive the message over the real WebSocket gateway path and send back `EVENT/manual-chat`.
6. `ManualDebugMessageHandler` records the inbound acknowledgement and promotes the matched outbound record to `DELIVERED`.
7. `GET /status/workers/message-history?workerId=...` exposes the worker conversation history for page polling and troubleshooting.

Verified boundary:

- this path is a worker debug/control side-channel
- it does not create or mutate `TaskMsg`
- it must not affect task lifecycle state

## 6. Verified Coverage Snapshot

Focused verified regression command on 2026-04-17:

```bash
mvn -pl xa-mass-dev-app -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,WorkerManualDebugChatIntegrationTest,MassApplicationLoadMockDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Representative coverage proves:

- default `dev` startup launches mock client connections automatically
- API create + approve flows through assignment, dispatch, result write-back, and terminal completion
- failure-path result write-back also converges to terminal closure
- reject/approve, pause/resume, delete guard, callback replay, and running-task terminate are covered through real HTTP/runtime flows
- paused tasks still close to `TERMINAL` when final callbacks arrive
- `GET /status/api/tasks/{taskId}` exposes `items` derived from persisted `TaskMsg.input`, plus `stateValidation`
- worker-context-attribute-based routing is covered end-to-end
- manual worker debug chat is covered end-to-end through `POST /status/workers/send-message` and `GET /status/workers/message-history`
- a single worker/worker-context can be reused after both normal completion and manual termination
- `minRequiredWorkerCount` acts as a real start gate
- multi-round refill works when `batchSize` is smaller than the total target count

## 7. Known Mainline Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- Redis and Database storage remain fail-fast placeholders
- the active EventBus implementation is Guava-backed; Redis-backed behavior is not part of the verified runtime path
- API integration coverage is improved but still not exhaustive for all cancel follow-up variants


