# Task Execution Flow

Last updated: 2026-04-20

This document describes only the verified mainline runtime path. It does not describe historical `v2` design ideas.

For policy ownership and interaction precedence, use [POLICY_INTERACTION_BASELINE.md](./POLICY_INTERACTION_BASELINE.md).

Current reference scenario:

- the platform kernel is generic, but the verified mainline currently runs through a long-connection worker path
- current adapters are `Worker` as worker, `WorkerContext` as optional worker context, and WebSocket as the dispatch/result transport
- this is a reference scenario, not the permanent platform boundary

## 1. Lifecycle

Current verified state machine:

```text
NEW -> READY -> PAUSED -> READY
NEW -> BLOCKED -> READY
READY -> RUNNING -> TERMINAL
READY/RUNNING -> PAUSED
any non-TERMINAL -> TERMINAL
NEW/TERMINAL -> delete
```

State constraints from `TaskStatus.canTransitionTo(...)`:

| Action | Allowed From | Target |
| --- | --- | --- |
| `approveTask` | `NEW`, `BLOCKED` | `READY` |
| `rejectTask` | `NEW` | `BLOCKED` |
| `pauseTask` | `READY`, `RUNNING` | `PAUSED` |
| `resumeTask` | `PAUSED` | `READY` |
| `cancelTask` | any non-`TERMINAL` | `TERMINAL` |
| `deleteTask` | `NEW`, `TERMINAL` | physical delete |

Important closure rules:

- task completion is driven by persisted `TaskMsg` finality, not only by the current task label
- a paused task still closes to `TERMINAL` when all persisted callbacks become final
- late non-final callbacks after manual terminal closure are ignored

## 2. READY To RUNNING

Verified runtime path:

1. A task enters `READY` after `approveTask()` or `resumeTask()`.
2. `TaskManager` notifies READY listeners.
3. `MassEngine` hands the task to `TaskAssignWorker`.
4. `TaskWorkerAssignListener` performs worker matching through `TaskWorkerMatchingStrategy`.
5. If no worker matches at that moment, `TaskAssignWorker` delayed-retries the task instead of orphaning it.
6. If matching succeeds and the task is still `READY`:
   - `peakAssignedWorkerCount` is updated to the highest number of workers actually used by the task
   - the task transitions from `READY` to `RUNNING`
7. `SimpleTaskMsgAssignListener` reuses the persisted `TaskMsg` rows created at task creation time.
8. Each selected `TaskMsg` is populated with:
   - `workerId`
   - `workerContextId`
   - `batchId`
   - status `ASSIGNED`
9. `GatewayTaskMsgPublisher` pushes the task downstream as `TASK/step`.

Key implementation facts:

- `TaskAssignWorker` no longer depends on `mockMode=true`
- `MassEngine` resubmits existing `READY` tasks on startup
- both `approveTask()` and `resumeTask()` produce READY events
- `batchSize` is enforced as a per-worker cap for each dispatch round
- `minRequiredWorkerCount` is the minimum matched-worker start gate
- surplus matched workers that were only needed for start-gate satisfaction are unlocked immediately

## 3. Worker Context And Matching

Mainline matching facts:

- optional routing hints are read from `Task.sharedConfig["routingCode"]`; `Task` has no first-class routing field
- `workerGroupId` is not the routing-country source of truth
- routing-country satisfaction should come from explicit rules and worker-context-facing signals
- `WorkerMatchContext` exposes `workerAttributes` and `workerContextAttributes` for rule evaluation
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels only

Current worker-context lifecycle vocabulary:

| Status | Meaning |
| --- | --- |
| `IDLE` | Free and allocatable |
| `RESERVED` | Pre-allocated |
| `OCCUPIED` | Executing work |
| `BLOCKED` | Manually locked out |
| `INVALID` | Unusable |

Dispatch-time rule:

- assignment only uses dispatchable worker contexts and advances bound runtime ownership into `OCCUPIED`

## 4. Open-Ended Tasks

Verified open-ended behavior:

- `openEnded=true` initializes `Task.intakeStatus=OPEN`, which disables automatic terminal closure while the append window remains open
- `POST /status/api/tasks/{taskId}/items` appends new work items as `TaskMsg.input`
- `PUT /status/api/tasks/{taskId}/seal` closes the append window
- once sealed, normal terminal convergence resumes when all persisted `TaskMsg` rows are final

## 5. RUNNING To Result Write-Back

Verified runtime path:

1. Default `dev` startup automatically launches mock WebSocket clients after `ApplicationReadyEvent`.
2. A mock client receives `TASK/step`.
3. The mock client sends back a `TASK/step` result frame.
4. `GatewayTaskResultHandler` calls `TaskManager.handleTaskMessageResult(...)`.
5. `TaskManager` updates the persisted `TaskMsg` using `taskId + msgId`.
6. Each `TaskMsg` reaches `SUCCESS` or `FAILED`.
7. When all persisted `TaskMsg` rows are final, the task automatically converges to `TERMINAL`.

Important guards:

- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames so the mock side does not generate echo loops
- duplicate final callbacks are accepted only as idempotent replays
- `TaskMsgStatus` stays as the logical lifecycle (`INIT -> ASSIGNED -> RUNNING -> final`)
- per-dispatch lease and retry history now lives in `TaskMsgAttempt`

## 6. Resource Release

Verified runtime release behavior:

- when a worker has no more in-flight `TaskMsg` rows for the current task, `TaskResourceReleaseListener` releases that worker/worker-context slot
- if pending `INIT` rows remain, the still-`RUNNING` task is re-submitted for another dispatch round
- normal terminal completion releases runtime occupancy
- manual `RUNNING -> TERMINAL` closure also releases runtime occupancy

## 7. Key Code Locations

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskWorkerAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/GatewayTaskMsgPublisher.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/GatewayTaskResultHandler.java`
- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`
- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/client/MassWebSocketClientImpl.java`

## 8. Still Not Converged

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- Redis and Database storage remain fail-fast placeholders
- broader API end-to-end coverage is still incomplete for some cancel follow-up variants


