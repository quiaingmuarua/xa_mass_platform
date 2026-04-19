# State Machine Baseline

Last updated: 2026-04-19

This is the short normative baseline for the active mainline.
If lifecycle semantics change, update this file, trace expectations, and E2E coverage together.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)

## 1. Global Rules

1. `TaskStatus`, `TaskHoldReason`, `TaskIntakeStatus`, `TaskMsgStatus`, `TaskMsgFinalReason`, `TaskMsgAttemptStatus`, `WorkerContextStatus`, and `TaskTerminalReason` are stable vocabulary.
2. No lifecycle change is complete without:
   - code change
   - this file update
   - trace update
   - E2E coverage update
3. `TERMINAL` task semantics are interpreted by `status + terminalReason`, not status alone.
4. Task closure stays modeled as one final status plus terminal reason. Do not split `TaskStatus` into multiple terminal enums unless API, validation, trace, and E2E baselines are redesigned together.
5. `TaskMsgStatus` is the platform lifecycle contract, not a complete transport-event history. Transport-specific delivery phases belong in trace/event data or a dedicated transport model.
6. The current runtime concurrency model is conservative: one worker is one active execution lane, even when that worker owns multiple worker contexts.

## 2. TaskStatus

States:

- `NEW`: created, not yet approved
- `BLOCKED`: intentionally prevented from scheduling; `holdReason` is required
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

- `approveTask`: `NEW/BLOCKED -> READY`, clears `holdReason`
- `rejectTask`: `NEW -> BLOCKED`, writes `holdReason=REVIEW_REJECTED`
- `blockTask`: `READY/RUNNING -> BLOCKED`, writes `holdReason=MANUAL_BLOCKED`
- `pauseTask`: `READY/RUNNING -> PAUSED`
- `resumeTask`: `PAUSED -> READY` or `PAUSED -> TERMINAL`
- `cancelTask`: non-terminal -> `TERMINAL`
- assignment success: `READY -> RUNNING`
- message convergence: non-terminal -> `TERMINAL`

Must hold:

- `READY -> RUNNING` only if the task is still `READY` after matching
- paused tasks may still close to `TERMINAL`
- every `TERMINAL` task must carry a valid `terminalReason`
- `BLOCKED` task must carry `holdReason`
- non-`BLOCKED` task must not carry `holdReason`

## 3. Task Intake

States:

- `OPEN`: task may accept new `TaskMsg` rows through append
- `SEALED`: append window is closed

Current entry points:

- create: `openEnded=true` initializes `intakeStatus=OPEN`
- create: `openEnded=false` initializes `intakeStatus=SEALED`
- `sealTask`: `OPEN -> SEALED`

Must hold:

- automatic terminal closure only happens when `intakeStatus=SEALED`, unless a non-message policy forces terminal closure
- `openEnded` is a compatibility field; `intakeStatus` is the active lifecycle truth

## 4. TaskMsgStatus

States:

- `INIT`: created, not yet dispatched
- `ASSIGNED`: assigned and ready to execute
- `RUNNING`: executing
- `SUCCESS`: final success
- `FAILED`: final non-success
- `EXPIRED`: final non-success caused by timeout/cancel after assignment

Allowed transitions:

- `INIT -> ASSIGNED`
- `ASSIGNED -> RUNNING/FAILED/EXPIRED`
- `RUNNING -> SUCCESS/FAILED/EXPIRED`

Current entry points:

- dispatch bind: `INIT -> ASSIGNED`
- callback write-back: `RUNNING -> SUCCESS/FAILED`
- expiry: `ASSIGNED/RUNNING -> EXPIRED`
- manual task terminate cleanup:
  - `INIT -> FAILED`
  - `ASSIGNED/RUNNING -> EXPIRED`
- retry reset: attempt finalizes, logical `TaskMsg` returns to `INIT`

Must hold:

- `workerId`, `workerContextId`, and `batchId` are projections of the latest attempt used for compatibility and UI
- duplicate final callbacks do not mutate final state
- final `TaskMsg` must carry a compatible `finalReason`
- richer transport phases must not be silently backfilled into `TaskMsgStatus` without a baseline redesign

## 5. TaskMsgAttempt

States:

- `CREATED`: attempt row created
- `LEASED`: dispatch slot and worker lease reserved
- `DISPATCHED`: payload handed to transport adapter
- `ACKED`: downstream acknowledged receipt
- `RUNNING`: executor started processing
- `SUCCEEDED`: final success
- `FAILED`: final failure
- `EXPIRED`: final timeout/lease-loss
- `REVOKED`: final orchestration-side revocation

Allowed transitions:

- `CREATED -> LEASED`
- `LEASED -> DISPATCHED/EXPIRED/REVOKED`
- `DISPATCHED -> ACKED/RUNNING/SUCCEEDED/FAILED/EXPIRED/REVOKED`
- `ACKED -> RUNNING/SUCCEEDED/FAILED/EXPIRED/REVOKED`
- `RUNNING -> SUCCEEDED/FAILED/EXPIRED/REVOKED`

Must hold:

- each dispatch round creates a new attempt with monotonically increasing `attemptNo`
- retry never rewrites a final attempt back to active
- active attempt truth outranks projected `workerId/workerContextId/batchId` on `TaskMsg`

## 6. WorkerContextStatus

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
- true same-worker multi-context parallelism would require redesign of worker locking, assignment, release, and E2E baselines

## 7. TaskTerminalReason

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

## 8. Core Invariants

1. `taskNonSuccessNumber == taskEligibleNumber - taskSuccessNumber`
2. late callbacks after manual terminal closure do not mutate task/message state
3. routing-required tasks do not run on stateless workers
4. worker release does not happen while that worker still has a non-final latest attempt for the same task
5. worker-context release must target the exact bound `workerContextId`
6. `TaskMsg` final reason must match `TaskMsgStatus`
7. active attempt and final logical `TaskMsg` must not coexist
