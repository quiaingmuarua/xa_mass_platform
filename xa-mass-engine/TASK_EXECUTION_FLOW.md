# Task Execution Flow

Last updated: 2026-04-27

This file describes the verified engine mainline only.

Use [POLICY_INTERACTION_BASELINE.md](./POLICY_INTERACTION_BASELINE.md) for policy ownership and precedence.

## 1. Lifecycle

Verified mainline:

```text
NEW -> READY -> PAUSED -> READY
NEW -> BLOCKED -> READY
READY -> RUNNING -> TERMINAL
READY/RUNNING -> PAUSED
any non-TERMINAL -> TERMINAL
NEW/TERMINAL -> delete
```

Important closure rules:

- task completion is driven by persisted `TaskMsg` finality, not only by the current task label
- a paused task still closes to `TERMINAL` when all persisted callbacks become final
- late non-final callbacks after manual terminal closure are ignored

## 2. READY To RUNNING

Verified path:

1. task enters `READY` through `approveTask()` or `resumeTask()`
2. `TaskManager` notifies READY listeners
3. `MassEngine` hands a task-ready assignment signal to `TaskAssignWorker`
4. `TaskWorkerAssignListener` performs worker matching through `TaskWorkerMatchingStrategy`
5. no match causes delayed retry, not orphaning
6. if matching succeeds and task is still `READY`, task enters `RUNNING`
7. `SimpleTaskMsgAssignListener` claims ready work through `TaskWorkRuntime.claimReady(...)`
8. each claimed item gets an active lease and updates the `TaskMsg` compatibility projection
9. dispatch channel sends the claimed work downstream

Key facts:

- `batchSize` is a per-worker cap for each dispatch round
- `minRequiredWorkerCount` is the start gate
- surplus matched workers used only for the start gate are unlocked immediately
- active lease truth is in `TaskWorkRuntime`
- `TaskMsgAttempt` remains the compatibility audit/projection layer

## 3. Matching And Context

- worker candidate lookup is bounded before rule evaluation
- storage candidate lookup does not decide online, lock, context availability, or routing acceptance
- public task APIs do not define a dedicated routing-code field
- routing truth comes from explicit rules and worker-context signals, not `workerGroupId`
- `WorkerMatchContext` exposes worker and worker-context attributes for rule evaluation
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels only

Worker-context status vocabulary:

- `IDLE`
- `RESERVED`
- `OCCUPIED`
- `BLOCKED`
- `INVALID`

## 4. Open Intake

- `openEnded=true` initializes `Task.intakeStatus=OPEN`
- `POST /status/api/tasks/{taskId}/items` appends new work items
- `PUT /status/api/tasks/{taskId}/seal` closes intake
- once sealed, normal terminal convergence resumes

## 5. Result Write-Back

Verified path:

1. worker receives canonical task dispatch
2. worker returns canonical task result
3. `TaskResultIngestChannel` calls `TaskManager.handleTaskMessageResult(...)`
4. `TaskResultService` applies the result using the active runtime lease
5. accepted results update `TaskMsgAttempt` and `TaskMsg` projection
6. final runtime work items drive task convergence to `TERMINAL`

Important guards:

- only canonical task-dispatch frames trigger worker execution
- duplicate final callbacks are idempotent replays only
- `TaskMsgStatus` stays the logical lifecycle
- per-dispatch lease and retry history lives in `TaskMsgAttempt`
- worker-visible `leaseToken` is not yet part of the current result contract

## 6. Resource Release

- release happens only when runtime has no active lease for that worker on the task
- pending `INIT` work can trigger another dispatch round while task stays `RUNNING`
- normal or manual terminal closure releases runtime occupancy

## 7. Key Code Locations

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskWorkerAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/transport/RuntimeTaskResultIngestChannel.java`

## 8. Known Non-Converged Points

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- Redis and Database storage remain fail-fast placeholders
- broader cancel follow-up API coverage is still incomplete
