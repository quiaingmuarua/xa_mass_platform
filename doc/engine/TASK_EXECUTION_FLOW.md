# Task Execution Flow

Last updated: 2026-04-13

This document describes only the verified mainline runtime path. It does not describe historical `v2` design ideas.

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

## 2. READY to RUNNING

Verified runtime path:

1. A task enters `READY` after `approveTask()` or `resumeTask()`.
2. `TaskManager` notifies READY listeners.
3. `MassEngine` hands the task to `TaskAssignWorker`.
4. `TaskDeviceAssignListener` performs device assignment.
5. On success:
   - `scheduleDeviceCnt` is written
   - the task transitions from `READY` to `RUNNING`
6. `SimpleTaskMsgAssignListener` reuses the persisted `TaskMsg` rows created at task creation time.
7. Each `TaskMsg` is populated with:
   - `deviceId`
   - `tokenId`
   - `batchId`
   - status `SENT`
8. `GatewayTaskMsgPublisher` pushes the task downstream as `TASK/step`.

Key implementation facts:

- `TaskAssignWorker` no longer depends on `mockMode=true`
- `MassEngine` resubmits existing `READY` tasks on startup
- both `approveTask()` and `resumeTask()` produce READY events

## 3. Mock Preconditions for Assignment

`MassApplication.loadMockData(...)` now fills the minimum device prerequisites needed for assignment:

- normalizes `supportedProjects` into `Project` enums
- lowercases `groupId`
- auto-creates a `LOGIN_READY` token when the device does not already have one

This fixed the previous false symptom where approved tasks stayed in `READY` because the mock devices did not satisfy project/token matching requirements.

## 4. RUNNING to Result Write-Back

Verified runtime path:

1. Default `dev` startup automatically launches mock WebSocket clients after `ApplicationReadyEvent`.
2. A mock client receives `TASK/step`.
3. The mock client sends back a `TASK/step` result frame.
4. `GatewayTaskResultHandler` calls `TaskManager.handleTaskMessageResult(...)`.
5. `TaskManager` updates the persisted `TaskMsg` using `taskId + msgId`.
6. Each `TaskMsg` reaches:
   - `SUCCESS`, or
   - `FAILED`
7. When all `TaskMsg` rows are final and the task is still `RUNNING`:
   - the task is automatically closed to `TERMINAL`

Important guard:

- `MassWebSocketClientImpl` ignores `response=true` `TASK/step` frames so the mock side does not generate echo loops or duplicate result writes

## 5. Verified API Happy Path on 2026-04-13

The current verified API-first happy path is:

1. `POST /status/api/tasks`
2. task starts at `NEW`
3. `POST /status/api/tasks/{taskId}/audit?approved=true`
4. task is assigned and reaches `RUNNING`
5. task messages are dispatched and written back as `SUCCESS`
6. task automatically converges to `TERMINAL`

Observed verified outcomes:

- `scheduleDeviceCnt` is updated from real assignment results
- `taskExecutedNumber` is updated from real message completion results
- persisted `TaskMsg` rows contain `deviceId`, `tokenId`, and `batchId`

## 6. Key Code Locations

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskDeviceAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/GatewayTaskMsgPublisher.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/GatewayTaskResultHandler.java`
- `xa-mass-mock/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`
- `xa-mass-mock/src/main/java/com/xa/mass/mock/client/MassWebSocketClientImpl.java`

## 7. Still Not Converged

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- EventBus usage is still split and not fully migrated
- shutdown still needs more work
- broader API end-to-end coverage is still missing beyond the happy path

