# Trace Contract

Last updated: 2026-04-16

This file defines the minimum structured trace required to debug lifecycle issues.
Summary logs are useful, but they do not satisfy this contract by themselves.

## 1. Scope

The contract covers:

- task transitions
- task-message transitions
- worker-context transitions
- worker lock acquire/release
- worker match accept/reject
- dispatch accept/skip
- callback accept/ignore
- resource release

## 2. Stable Event Names

Required event names:

- `TASK_STATUS_TRANSITION`
- `TASK_TERMINAL_CLOSED`
- `TASK_MSG_STATUS_TRANSITION`
- `TASK_MSG_RETRY_RESET`
- `WORKER_CONTEXT_STATUS_TRANSITION`
- `WORKER_LOCK_ACQUIRED`
- `WORKER_LOCK_RELEASED`
- `WORKER_MATCH_ACCEPTED`
- `WORKER_MATCH_REJECTED`
- `DISPATCH_REQUESTED`
- `DISPATCH_SKIPPED`
- `CALLBACK_ACCEPTED`
- `CALLBACK_IGNORED_DUPLICATE`
- `CALLBACK_IGNORED_LATE`
- `RESOURCE_RELEASED`
- `RESOURCE_RELEASE_FAILED`

Do not introduce synonym drift for the same concept.

## 3. Required Fields

Common fields:

- `event`
- `entityType`
- `entityId`
- `result`
- `trigger`
- `source`
- `reason`
- `traceId`
- `taskId`
- `msgId`
- `workerId`
- `workerContextId`
- `batchId`

Transition fields:

- `fromStatus`
- `toStatus`

Specialized fields when relevant:

- `terminalReason`
- `retryCount`
- `requiredMinWorkerCount`

## 4. Minimum Required Paths

Must be traceable:

- `Task`: `NEW -> READY`, `READY -> RUNNING`, `RUNNING/PAUSED/BLOCKED -> TERMINAL`
- `TaskMsg`: `INIT -> BINDING -> ASSIGNED -> RUNNING -> SUCCESS/FAILED/EXPIRED`
- retry reset: `FAILED/EXPIRED -> INIT`
- `WorkerContext`: `IDLE -> RESERVED -> OCCUPIED -> IDLE`
- worker lock acquire/release
- worker match reject reason
- callback ignored because duplicate
- callback ignored because task already terminal

Rules:

1. task transitions must always include `taskId`
2. task-message transitions must always include `taskId + msgId`
3. worker-context transitions must always include `workerId + workerContextId`
4. terminal closure must include `terminalReason`
5. retry reset must include `retryCount`
6. match rejection must include explicit `reason`

## 5. Replayability Requirement

Given a `taskId`, an operator or agent must be able to reconstruct:

1. when the task entered `READY`
2. why it entered `RUNNING`
3. which worker/context each message used
4. which messages succeeded, failed, or expired
5. whether retry happened
6. why the task closed to `TERMINAL`
7. which resources were released

## 6. Test Requirement

The contract is only valid if tests pin it.

Minimum trace assertions:

- `READY -> RUNNING`
- `RUNNING -> TERMINAL` with `terminalReason`
- `INIT -> BINDING -> ASSIGNED`
- `ASSIGNED -> RUNNING -> SUCCESS/FAILED`
- `IDLE -> RESERVED -> OCCUPIED -> IDLE`
- `TASK_MSG_RETRY_RESET` when retry is exercised
- `CALLBACK_IGNORED_DUPLICATE` when replay is exercised
- `CALLBACK_IGNORED_LATE` when late callback is exercised
