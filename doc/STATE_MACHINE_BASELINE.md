# State Machine Baseline

Last updated: 2026-05-08 (runtime owns hot-path ready/claim/lease/expiry state; bounded message/attempt projection remains compatibility residue only)

Status: current global lifecycle baseline.

This is the short normative baseline for the active mainline.
If lifecycle semantics change, update this file, trace expectations, and E2E coverage together.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md)
- [../xa-mass-engine/POLICY_INTERACTION_BASELINE.md](../xa-mass-engine/POLICY_INTERACTION_BASELINE.md)

## 1. Global Rules

1. `TaskStatus`, `TaskHoldReason`, `TaskIntakeStatus`, `WorkerContextStatus`, `TaskTerminalReason`, and the neutral projection enums under `mass-storage-api` are the current lifecycle vocabulary.
2. `Task.contract` is the runtime contract truth (`SESSION | BATCH`), and ingress form must not redefine lifecycle, terminal, or retry semantics.
3. No lifecycle change is complete without:
   - code change
   - this file update
   - trace update
   - E2E coverage update
4. `TERMINAL` task semantics are interpreted by `status + terminalReason`, not status alone.
5. Task closure stays modeled as one final status plus terminal reason. Do not split `TaskStatus` into multiple terminal enums unless API, validation, trace, and E2E baselines are redesigned together.
6. The logical work-projection status model is a bounded compatibility contract, not a complete transport-event history. Transport-specific delivery phases belong in trace/event data or a dedicated transport model.
7. The current runtime concurrency model is conservative: one worker is one active execution lane, even when that worker owns multiple worker contexts.
8. Policy changes must preserve ownership boundaries across matching, assignment, attempt, release, refill, intake, control, and terminal decisions.
9. `TaskWorkRuntime` in `platform_infra/mass-runtime-api` is the current hot-path owner for ready work, active leases, retry scheduling, and lease expiry indexes. `TaskResultRuntime` is the runtime-owned public result read truth for stable-final result rows, repair staging, and result-side attempt-closed/event/progress barriers. `TaskMessageProjection` remains bounded compatibility/debug residue for logical work-item status and payload summary. `TaskMessageAttemptProjection` remains auditable execution-history residue for concrete dispatch attempts.
10. `Task.workloadClass` is the explicit task-level runtime optimization field; current engine truth is `INTERACTIVE` or `BULK`, and assignment signal routing resolves from that field rather than free-form `sharedConfig` semantics.
11. runtime retry budget is seeded at create/append time and consumed from `TaskWorkRuntime`; post-ingest mutation of persisted message-projection retry settings must not redefine retry scheduling or finalization.
12. result callbacks follow the result-kernel mainline in `RESULT_BOUNDARY_BASELINE.md`: runtime apply truth comes from `TaskWorkRuntime.applyResultWithContext(...)`; stable-final public result rows are committed into `TaskResultRuntime`; projection writes are submitted best-effort after runtime acceptance and are not the result commit point or a public result read source.

## 2. TaskStatus

States:

- `NEW`: created, not yet approved
- `BLOCKED`: intentionally prevented from scheduling; `holdReason` is required
- `READY`: eligible for matching and dispatch
- `RUNNING`: at least one runtime-owned work item is actively leased or executing
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
- batch runtime convergence: non-terminal -> `TERMINAL`

Must hold:

- `READY -> RUNNING` only if the task is still `READY` after matching
- paused tasks may still close to `TERMINAL`
- every `TERMINAL` task must carry a valid `terminalReason`
- `BLOCKED` task must carry `holdReason`
- non-`BLOCKED` task must not carry `holdReason`

## 3. Task Intake

States:

- `OPEN`: task may accept new work-item ingress through append
- `SEALED`: append window is closed

Current entry points:

- create: `SESSION` and `BATCH` shells initialize `intakeStatus=OPEN`
- `sealTask`: contract-neutral intake close action; `OPEN -> SEALED` for both `SESSION` and `BATCH`

Must hold:

- automatic message-driven terminal closure only happens for `BATCH` tasks after intake has been sealed
- `SESSION` tasks may close intake explicitly without becoming terminal; draining current work is not sufficient for automatic terminal closure
- every terminal task must have `intakeStatus=SEALED`
- `intakeStatus` is the active append-window lifecycle truth for both `SESSION` and `BATCH`

## 4. Message Projection Status

States:

- `INIT`: created, not yet dispatched
- `ASSIGNED`: assigned and ready to execute
- `RUNNING`: executing
- `SUCCESS`: final success
- `FAILED`: final non-success
- `EXPIRED`: final non-success caused by timeout/cancel after assignment; this remains the stable logical outcome mainly for `SESSION` work and terminal-control overlays, not for `BATCH` lease-retry exhaustion

Allowed transitions:

- `INIT -> ASSIGNED`
- `ASSIGNED -> RUNNING/FAILED/EXPIRED`
- `RUNNING -> SUCCESS/FAILED/EXPIRED`
- `FAILED -> INIT` (retry reset only; `SUCCESS` is never reset)
- `EXPIRED -> INIT` (`SESSION`-style retry reset only; `BATCH` lease expiry does not use `EXPIRED` as its logical retry base)

Current entry points:

- runtime claim + dispatch bind: `INIT -> ASSIGNED`
- callback write-back: `RUNNING -> SUCCESS/FAILED`
- expiry: `SESSION` compatibility view may converge `ASSIGNED/RUNNING -> EXPIRED`
- terminal task compatibility overlay for stop reasons (`MANUAL_CANCELLED`, `MAX_RUNTIME_REACHED`, `SUCCESS_RATE_REACHED`, `RETRY_BUDGET_EXHAUSTED`):
  - bounded reads project `INIT -> FAILED`
  - bounded reads project `ASSIGNED/RUNNING -> EXPIRED`
- retry reset: logical work returns to `INIT` when retry budget remains; this is usually `FAILED -> INIT` for retryable failures and `SESSION` expiry, while `BATCH` lease expiry resets directly from live runtime truth without treating `EXPIRED` as the logical mainline state

Must hold:

- `latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are projections of the latest attempt used for compatibility and UI; they are null between retry reset and next assignment
- duplicate final callbacks do not mutate final state
- final message projection must carry a compatible `finalReason`
- `taskWorkAttemptClosed` must fire whenever an execution attempt ends, including retryable failure
- `taskWorkLogicallyFinal` must only fire when the logical message view is stably final and will not be reset for retry
- retryable failure must close the current attempt and reset the logical message view to `INIT`; it must not publish logically-final semantics
- `BATCH` lease expiry has no stable logical timeout meaning while the task is still live: it either resets `INIT` when runtime retry budget remains or finalizes as `FAILED + RETRY_EXHAUSTED` when the budget is exhausted
- worker/adapter callbacks must resolve an active runtime lease before result application; when the lease exists but the latest attempt/message projection is missing, engine repairs that compatibility state from runtime before continuing
- callbacks without an active runtime lease are rejected and traced as `CALLBACK_REJECTED_NO_ACTIVE_LEASE`
- during the current WorkRuntime slice, result handling applies against the runtime active lease and runtime retry budget before mutating the compatibility projection
- result-side projection residue is submitted best-effort after runtime acceptance; synchronous result-side events and task progress evaluation must not depend on projection write completion
- task cancellation and policy-driven task stop must not synchronously rewrite every queued message projection row; bounded compatibility reads may project the final message view from task shell truth instead
- `errorCode` is an optional short symbolic code set by the worker alongside `errorMessage`; it is cleared on `resetForRetry()` and must not carry over between attempts
- richer transport phases must not be silently backfilled into the message-projection status model without a baseline redesign

## 5. Attempt Projection Status

`TaskMessageAttemptProjection` is one concrete, auditable execution opportunity for a logical message. It is not a raw transport-event log, and push/pull/polling must map into this same attempt truth rather than define separate attempt models.

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

- `EXPIRED`: attempt ended due to lease timeout, worker loss, or task cancellation; the parent logical message is finalized as `EXPIRED` or `FAILED`
- `REVOKED`: attempt cancelled by the orchestrator so the parent logical message can be retried; `finalReason` must be `REVOKED_FOR_RETRY`; lease/cancel expiry must use `EXPIRED`, not `REVOKED`

Must hold:

- each dispatch round creates a new attempt with monotonically increasing `attemptNo`
- retry never rewrites a final attempt back to active
- active attempt truth outranks projected `latestAttemptWorkerId/latestAttemptWorkerContextId/latestAttemptBatchId` on the message projection
- at most one active attempt may exist for a single `taskId + messageId`
- a stable-final logical message must not have any active attempt
- if a runtime lease exists but the compatibility attempt row is missing, the engine may recover an audit attempt projection so callback/expiry handling does not fall back to stale projection truth
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
- batch message-driven closure must match engine work-runtime aggregate counters; message projection remains the compatibility projection/audit view
- persisted terminal task state must always carry `intakeStatus=SEALED`; batch message-convergence reasons still require intake to be sealed before automatic closure
- `SESSION` tasks do not auto-close to `ALL_MESSAGES_SUCCEEDED` / `ALL_MESSAGES_FAILED` / `MIXED_MESSAGE_RESULTS` just because the current runtime work set drained

## 8. Time-Based Policy Enforcement

Both policies are enforced by `LeaseExpireWatchdog` (runs every `leaseWatchdogIntervalSeconds`, default 30 s):

- **Lease expiry**: expired active leases are pulled from `TaskWorkRuntime.pollExpiredLeases(...)` and expired via
  the engine runtime-maintenance path (`TaskRuntimeMaintenancePort.expireLeasedWork(...)`). This always marks the
  concrete compatibility attempt `EXPIRED` and publishes
  `taskWorkAttemptClosed` for resource release. If retry budget remains, the logical message is reset to `INIT`,
  `TASK_WORK_RETRY_RESET` is emitted, and redispatch is requested without `taskWorkLogicallyFinal`. When retry
  budget is exhausted, `SESSION` keeps logical `EXPIRED`, while `BATCH` finalizes as `FAILED + RETRY_EXHAUSTED`
  because lease loss is treated as an attempt failure mode rather than a stable per-item timeout contract.
- **Max task runtime**: non-terminal tasks with `maxRuntimeSeconds > 0` are indexed by their
  runtime deadline and polled through `TaskStorage.pollExpiredMaxRuntimeTasks(...)`; expired
  tasks are terminated with `MAX_RUNTIME_REACHED` via `TaskManager.terminateTask()`. Set
  `maxRuntimeSeconds = 0` (default) to disable the limit.

Must hold:
- `expireLeasedWork` must fire `taskWorkAttemptClosed` after expiry so `TaskResourceReleaseListener`
  can release the worker context; skipping this call leaves the context permanently `OCCUPIED`
- retryable lease expiry must follow the same logical-reset rule as retryable failure: close the attempt,
  clear latest-attempt projections, increment `retryCount`, and avoid logical-final publication until the
  retried logical message becomes stably final
- retryable callback failure and lease expiry must branch from runtime result application outcome; compatibility message fields follow that decision and do not override it
- `terminateTask(reason)` follows the same drain-and-notify path as `cancelTask`; the only
  difference is the `TaskTerminalReason` recorded

## 9. Core Invariants

1. `taskNonSuccessNumber == taskEligibleNumber - taskSuccessNumber` (derived; `setTaskNonSuccessNumber` ignores its argument and recomputes to prevent silent invariant breaks)
2. late callbacks after manual terminal closure do not mutate task/message state
3. routing-required tasks do not run on stateless workers
4. worker release does not happen while that worker still has a non-final latest attempt for the same task
5. worker-context release must target the exact bound `workerContextId`
6. message final reason must match message status
7. active attempt and final logical message must not coexist

