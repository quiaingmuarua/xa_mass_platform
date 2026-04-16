# State Machine Baseline

Last updated: 2026-04-16

This is the short normative baseline for the active mainline.
If lifecycle semantics change, update this file, trace expectations, and E2E coverage together.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)

## 1. Global Rules

1. `TaskStatus`, `TaskMsgStatus`, `WorkerContextStatus`, and `TaskTerminalReason` are stable vocabulary.
2. No lifecycle change is complete without:
   - code change
   - this file update
   - trace update
   - E2E coverage update
3. `TERMINAL` task semantics are interpreted by `status + terminalReason`, not status alone.

## 2. TaskStatus

States:

- `NEW`: created, not yet approved
- `BLOCKED`: intentionally prevented from scheduling
- `READY`: eligible for matching and dispatch
- `RUNNING`: at least one persisted `TaskMsg` entered execution
- `PAUSED`: temporarily not dispatchable; old callbacks may still finish
- `TERMINAL`: lifecycle closed

Allowed transitions:

- `NEW -> READY/BLOCKED/TERMINAL`
- `BLOCKED -> READY/TERMINAL`
- `READY -> RUNNING/PAUSED/BLOCKED/TERMINAL`
- `RUNNING -> PAUSED/BLOCKED/TERMINAL`
- `PAUSED -> READY/TERMINAL`

Current entry points:

- `approveTask`: `NEW/BLOCKED -> READY`
- `rejectTask`: `NEW -> BLOCKED`
- `blockTask`: `READY/RUNNING -> BLOCKED`
- `pauseTask`: `READY/RUNNING -> PAUSED`
- `resumeTask`: `PAUSED -> READY` or `PAUSED -> TERMINAL`
- `cancelTask`: non-terminal -> `TERMINAL`
- assignment success: `READY -> RUNNING`
- message convergence: non-terminal -> `TERMINAL`

Must hold:

- `READY -> RUNNING` only if the task is still `READY` after matching
- paused tasks may still close to `TERMINAL`
- every `TERMINAL` task must carry a valid `terminalReason`

## 3. TaskMsgStatus

States:

- `INIT`: created, not yet bound
- `BINDING`: binding runtime ownership
- `ASSIGNED`: assigned and ready to execute
- `RUNNING`: executing
- `SUCCESS`: final success
- `FAILED`: final non-success
- `EXPIRED`: final non-success caused by timeout/cancel after assignment

Allowed transitions:

- `INIT -> BINDING`
- `BINDING -> ASSIGNED/FAILED`
- `ASSIGNED -> RUNNING/FAILED/EXPIRED`
- `RUNNING -> SUCCESS/FAILED/EXPIRED`

Current entry points:

- dispatch bind: `INIT -> BINDING -> ASSIGNED`
- completion normalization: may force `INIT/BINDING/ASSIGNED -> RUNNING` before final mark
- callback write-back: `RUNNING -> SUCCESS/FAILED`
- expiry: `ASSIGNED/RUNNING -> EXPIRED`
- manual task terminate cleanup:
  - `INIT/BINDING -> FAILED`
  - `ASSIGNED/RUNNING -> EXPIRED`
- retry reset: `FAILED/EXPIRED -> INIT`

Must hold:

- `workerId`, `workerContextId`, and `batchId` are written before downstream dispatch
- duplicate final callbacks do not mutate final state
- `RUNNING` must be observable before final success/failure in the normalized completion path

## 4. WorkerContextStatus

States:

- `IDLE`: free
- `RESERVED`: pre-allocated for a task
- `OCCUPIED`: executing for a task
- `BLOCKED`: manually excluded
- `INVALID`: unusable

Allowed transitions:

- `IDLE -> RESERVED/BLOCKED/INVALID`
- `RESERVED -> OCCUPIED/IDLE/BLOCKED/INVALID`
- `OCCUPIED -> IDLE/BLOCKED/INVALID`
- `BLOCKED -> IDLE/INVALID`

Current entry points:

- `bindToTask`: `IDLE -> RESERVED`
- `startOccupying`: `RESERVED -> OCCUPIED`
- `release`: `RESERVED/OCCUPIED -> IDLE`
- `block`, `unblock`, `invalidate`

Must hold:

- `lastBindTaskId` is the ownership truth for task-bound contexts
- releasing one context must not release sibling contexts
- current model is conservative `1:N`: one worker may own many contexts, but only one active context per worker executes at a time

## 5. TaskTerminalReason

Active reasons:

- `MANUAL_CANCELLED`
- `ALL_MESSAGES_SUCCEEDED`
- `ALL_MESSAGES_FAILED`
- `MIXED_MESSAGE_RESULTS`
- `MAX_RUNTIME_REACHED`
- `SUCCESS_RATE_REACHED`
- `RETRY_BUDGET_EXHAUSTED`

Must hold:

- terminal task -> non-null `terminalReason`
- non-terminal task -> null `terminalReason`
- message-driven closure must match persisted message aggregates

## 6. Core Invariants

1. `taskNonSuccessNumber == taskEligibleNumber - taskSuccessNumber`
2. late callbacks after manual terminal closure do not mutate task/message state
3. routing-required tasks do not run on stateless workers
4. worker release does not happen while that worker still has in-flight messages for the same task
5. worker-context release must target the exact bound `workerContextId`
