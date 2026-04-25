# State Machine Baseline

Last updated: 2026-04-25 (retryable lease-expiry reset clarified; policy interaction guardrail linked; lease watchdog, max runtime, errorCode, expireTaskMessage release fix; TaskMsgAttempt baseline clarified)

This is the short normative baseline for the active mainline.
If lifecycle semantics change, update this file, trace expectations, and E2E coverage together.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)

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
7. Policy changes must preserve ownership boundaries across matching, assignment, attempt, release, refill, intake, control, and terminal decisions.

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

- automatic message-driven terminal closure only happens when `intakeStatus=SEALED`
- `intakeStatus=OPEN` may still close only for explicit stop reasons that allow open-intake closure: `MANUAL_CANCELLED`, `MAX_RUNTIME_REACHED`, `SUCCESS_RATE_REACHED`, or `RETRY_BUDGET_EXHAUSTED`
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
- `FAILED -> INIT` (retry reset only; `SUCCESS` is never reset)
- `EXPIRED -> INIT` (retry reset only; `SUCCESS` is never reset)

Current entry points:

- dispatch bind: `INIT -> ASSIGNED`
- callback write-back: `RUNNING -> SUCCESS/FAILED`
- expiry: `ASSIGNED/RUNNING -> EXPIRED`
- manual task terminate cleanup:
  - `INIT -> FAILED`
  - `ASSIGNED/RUNNING -> EXPIRED`
- retry reset: `FAILED/EXPIRED -> INIT` via `TaskMsg.resetForRetry()` when retry budget remains; stale latest-attempt projection fields (`latestAttemptWorkerId`, `latestAttemptWorkerContextId`, `latestAttemptBatchId`, `assignedTime`) are cleared on reset

Must hold:

- `latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are projections of the latest attempt used for compatibility and UI; they are null between retry reset and next assignment
- duplicate final callbacks do not mutate final state
- final `TaskMsg` must carry a compatible `finalReason`
- `taskMessageAttemptClosed` must fire whenever an execution attempt ends, including retryable failure
- `taskMessageLogicallyFinal` must only fire when `TaskMsg` is stably final and will not be reset for retry
- retryable failure must close the current attempt and reset the logical `TaskMsg` to `INIT`; it must not publish logically-final semantics
- worker/gateway callbacks must resolve a unique active `TaskMsgAttempt`; missing active attempt is rejected and traced as `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`
- `errorCode` is an optional short symbolic code set by the worker alongside `errorMessage`; it is cleared on `resetForRetry()` and must not carry over between attempts
- richer transport phases must not be silently backfilled into `TaskMsgStatus` without a baseline redesign

## 5. TaskMsgAttempt

`TaskMsgAttempt` is one concrete, auditable execution opportunity for a logical `TaskMsg`. It is not a raw transport-event log, and push/pull/polling must map into this same attempt truth rather than define separate attempt models.

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

- `CREATED -> LEASED/EXPIRED/REVOKED`
- `LEASED -> DISPATCHED/EXPIRED/REVOKED`
- `DISPATCHED -> ACKED/RUNNING/SUCCEEDED/FAILED/EXPIRED/REVOKED`
- `ACKED -> RUNNING/SUCCEEDED/FAILED/EXPIRED/REVOKED`
- `RUNNING -> SUCCEEDED/FAILED/EXPIRED/REVOKED`

State semantics:

- `EXPIRED`: attempt ended due to lease timeout, worker loss, or task cancellation; the parent `TaskMsg` is finalized as `EXPIRED` or `FAILED`
- `REVOKED`: attempt cancelled by the orchestrator so the parent `TaskMsg` can be retried; `finalReason` must be `REVOKED_FOR_RETRY`; lease/cancel expiry must use `EXPIRED`, not `REVOKED`

Must hold:

- each dispatch round creates a new attempt with monotonically increasing `attemptNo`
- retry never rewrites a final attempt back to active
- active attempt truth outranks projected `latestAttemptWorkerId/latestAttemptWorkerContextId/latestAttemptBatchId` on `TaskMsg`
- at most one active attempt may exist for a single `taskId + messageId`
- a stable-final `TaskMsg` must not have any active attempt
- `REVOKED` must not be used as an expiry shortcut; only `EXPIRED` carries expiry and cancellation final reasons

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
- open-intake task closure is only valid for `MANUAL_CANCELLED` or policy-driven stop reasons; normal message-convergence reasons must wait until `intakeStatus=SEALED`

## 8. Time-Based Policy Enforcement

Both policies are enforced by `LeaseExpireWatchdog` (runs every `leaseWatchdogIntervalSeconds`, default 30 s):

- **Lease expiry**: any active `TaskMsgAttempt` whose `leaseExpireTime` has passed is expired via
  `TaskManager.expireTaskMessage()`. This always marks the concrete attempt `EXPIRED` and publishes
  `taskMessageAttemptClosed` for resource release. If retry budget remains, the logical message is reset
  `EXPIRED -> INIT`, `TASK_MSG_RETRY_RESET` is emitted, and redispatch is requested without
  `taskMessageLogicallyFinal`. If retry budget is exhausted, the logical message stays `EXPIRED` and
  `taskMessageLogicallyFinal` is published.
- **Max task runtime**: any non-terminal `Task` with `maxRuntimeSeconds > 0` that has been running
  longer than that limit is terminated with `MAX_RUNTIME_REACHED` via `TaskManager.terminateTask()`.
  Set `maxRuntimeSeconds = 0` (default) to disable the limit.

Must hold:
- `expireTaskMessage` must fire `taskMessageAttemptClosed` after expiry so `TaskResourceReleaseListener`
  can release the worker context; skipping this call leaves the context permanently `OCCUPIED`
- retryable lease expiry must follow the same logical-reset rule as retryable failure: close the attempt,
  clear latest-attempt projections, increment `retryCount`, and avoid logical-final publication until the
  retried logical message becomes stably final
- `terminateTask(reason)` follows the same drain-and-notify path as `cancelTask`; the only
  difference is the `TaskTerminalReason` recorded

## 9. Core Invariants

1. `taskNonSuccessNumber == taskEligibleNumber - taskSuccessNumber` (derived; `setTaskNonSuccessNumber` ignores its argument and recomputes to prevent silent invariant breaks)
2. late callbacks after manual terminal closure do not mutate task/message state
3. routing-required tasks do not run on stateless workers
4. worker release does not happen while that worker still has a non-final latest attempt for the same task
5. worker-context release must target the exact bound `workerContextId`
6. `TaskMsg` final reason must match `TaskMsgStatus`
7. active attempt and final logical `TaskMsg` must not coexist
