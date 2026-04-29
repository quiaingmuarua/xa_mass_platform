# Trace Contract

Last updated: 2026-04-27

Status: current global trace contract.

This file defines the minimum structured trace required to debug lifecycle issues.
Summary logs are useful, but they do not satisfy this contract by themselves.

This file defines trace semantics, not placement policy or sink design. If a
new field or history surface is being proposed, use
[INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) and
[DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md) instead of extending this
contract into a storage design document.

## 1. Scope

The contract covers task/task-message/attempt lifecycle, assignment and
dispatch decisions, worker/context resource transitions, callback outcomes,
resource release, and policy decision points that would otherwise be hidden.

## 2. Stable Event Names

Required event names:

- `TASK_STATUS_TRANSITION`
- `TASK_TERMINAL_CLOSED`
- `TASK_PROGRESS_SNAPSHOT`
- `TASK_MSG_STATUS_TRANSITION`
- `TASK_MSG_ATTEMPT_STATUS_TRANSITION`
- `TASK_MSG_ATTEMPT_CLOSED`
- `TASK_MSG_LOGICALLY_FINAL`
- `TASK_MSG_RETRY_RESET`
- `WORKER_CONTEXT_STATUS_TRANSITION`
- `WORKER_LOCK_ACQUIRED`
- `WORKER_LOCK_RELEASED`
- `WORKER_MATCH_ACCEPTED`
- `WORKER_MATCH_REJECTED`
- `DISPATCH_REQUESTED`
- `DISPATCH_SKIPPED`
- `ASSIGNMENT_SUMMARY`
- `TASK_STATE_VALIDATION_SUMMARY`
- `DISPATCH_BINDING_SUMMARY`
- `ASSIGNMENT_QUEUE_SNAPSHOT`
- `ASSIGNMENT_RETRY_SCHEDULED`
- `CALLBACK_ACCEPTED`
- `CALLBACK_IGNORED_DUPLICATE`
- `CALLBACK_IGNORED_LATE`
- `CALLBACK_REJECTED_NO_ACTIVE_LEASE`
- `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`
- `RESOURCE_RELEASED`
- `RESOURCE_RELEASE_FAILED`

Do not introduce synonym drift for the same concept.

Policy interaction rule:

- use existing stable events when a policy decision maps directly to a lifecycle event
- add a new stable event only when the decision would otherwise be hidden
- any new policy event must include `policyName`, `decision`, and `reason`
- update [../xa-mass-engine/POLICY_INTERACTION_BASELINE.md](../xa-mass-engine/POLICY_INTERACTION_BASELINE.md) and tests in the same change

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
- `messageId`
- `workerId`
- `workerContextId`
- `batchId`
- `latestAttemptWorkerId`
- `latestAttemptWorkerContextId`
- `latestAttemptBatchId`
- `attemptId`
- `attemptNo`

Transition fields:

- `fromStatus`
- `toStatus`

Specialized fields when relevant:

- `terminalReason`
- `retryCount`
- `workRetryDelayMillis`
- `finalReason`
- `requiredMinWorkerCount`
- `currentStatus`
- `retryDelayMillis`
- `taskStatus`
- `workloadClass`
- `dispatchLane`
- `dispatchPriority`
- `batchPolicy`
- `leaseProfile`
- `backpressureClass`
- `taskTargetNumber`
- `taskEligibleNumber`
- `taskSuccessNumber`
- `taskNonSuccessNumber`
- `totalMessages`
- `successMessages`
- `failedMessages`
- `expiredMessages`
- `processingMessages`
- `pendingMessages`
- `progressPercent`
- `resolutionOutcome`
- `needsTerminalClosure`
- `valid`
- `needsResolution`
- `violationCount`
- `violations`
- `pendingDispatchCount`
- `desiredDispatchWorkerCount`
- `requestedMatchCount`
- `matchedWorkerCount`
- `dispatchCandidateCount`
- `dispatchedMessageCount`
- `usedWorkerCount`
- `dispatchSlotCount`
- `uniqueWorkerCount`
- `uniqueWorkerContextCount`
- `perWorkerBatchLimit`
- `queueDepth`
- `trackedBatchPendingCount`
- `scheduledRetryCount`
- `queueAction`
- `leaseExpireTime`
- `policyName`
- `decision`

## 4. Minimum Required Paths

Must be traceable:

- `Task`: `NEW -> READY`, `READY -> RUNNING`, `RUNNING/PAUSED/BLOCKED -> TERMINAL`
- task-level funnel snapshot after progress reconciliation
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> SUCCESS/FAILED/EXPIRED`
- `TaskMsgAttempt`: `CREATED -> LEASED -> DISPATCHED -> ... -> final`
- attempt closure: `TASK_MSG_ATTEMPT_CLOSED` when a concrete attempt ends
- logical item finality: `TASK_MSG_LOGICALLY_FINAL` only when the logical message will not be reset for retry
- retry reset: final attempt closure plus logical `TaskMsg -> INIT`, without `TASK_MSG_LOGICALLY_FINAL`
- `WorkerContext`: `IDLE -> RESERVED -> OCCUPIED -> IDLE`
- worker lock acquire/release
- worker match reject reason
- assignment attempt summary with requested/matched/dispatched counts
- task-state validation summary when a task needs explicit reconciliation or violates invariants
- dispatch binding summary with per-worker batch usage
- assignment queue snapshot with backlog and scheduled retry pressure
- assignment retry scheduling after a skipped dispatch when a `READY` or `RUNNING` task remains eligible
- callback ignored because duplicate
- callback ignored because task already terminal
- callback rejected because no active runtime lease exists
- callback rejected because no recoverable active attempt exists after runtime-lease validation

Rules:

1. task transitions must always include `taskId`
2. task-message transitions must always include `taskId + messageId`
3. worker-context transitions must always include `workerId + workerContextId`
4. terminal closure must include `terminalReason`
5. retry reset must include `retryCount`; when runtime retry visibility is delayed it must also include `workRetryDelayMillis`
6. match rejection must include explicit `reason`
7. task progress snapshot must include task aggregate counters and engine work-runtime aggregate counters
8. task-level dispatch traces must include the resolved workload/runtime profile fields when a task is present
9. assignment summary must include requested/matched/dispatched worker or message counts
10. dispatch binding summary must include per-worker batch limit and unique worker/context counts
11. task-state validation summary must include `valid`, `needsResolution`, and violation details when emitted
12. assignment queue snapshot must include queue depth, dispatch lane, and scheduled retry count
13. task-message-attempt transitions must include `attemptId`, `attemptNo`, and `finalReason` when the attempt closes
14. task-message projection traces must use `latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId`
15. attempt-history traces may still use `workerId`, `workerContextId`, and `batchId` because those fields belong to `TaskMsgAttempt`

## 5. Replayability Requirement

Given a `taskId`, an operator or agent must be able to reconstruct:

1. when the task entered `READY`
2. why it entered `RUNNING`
3. which worker/context each message used
4. which attempt delivered each message and how that attempt finished
5. whether retry happened
6. why the task closed to `TERMINAL`
7. which resources were released
8. what the aggregate task funnel looked like at each reconciliation point
9. how many workers were requested, matched, and actually used during each assignment attempt
10. whether the task state audit detected invariant drift or pending terminal reconciliation
11. whether assignment pressure came from queue backlog or delayed retry accumulation

## 6. Test Requirement

The contract is only valid if tests pin it.

Minimum trace assertions:

- `READY -> RUNNING`
- `RUNNING -> TERMINAL` with `terminalReason`
- `TASK_PROGRESS_SNAPSHOT` after message reconciliation
- `INIT -> ASSIGNED`
- `TASK_MSG_ATTEMPT_STATUS_TRANSITION` for `CREATED -> LEASED -> DISPATCHED`
- `ASSIGNED -> RUNNING -> SUCCESS/FAILED`
- `IDLE -> RESERVED -> OCCUPIED -> IDLE`
- `ASSIGNMENT_SUMMARY` for at least one successful assignment attempt
- `TASK_STATE_VALIDATION_SUMMARY` when validation reports `needsResolution=true` or invariant violations; use `validationScope=RUNTIME` for bounded runtime validation and `validationScope=PROJECTION_AUDIT` for explicit deep projection audit
- `DISPATCH_BINDING_SUMMARY` for at least one successful binding round
- `ASSIGNMENT_QUEUE_SNAPSHOT` for at least one submission and one retry path
- `TASK_MSG_RETRY_RESET` when retry is exercised, including retryable failure and retryable lease expiry
- `TASK_MSG_ATTEMPT_CLOSED` for success, retryable failure, retry-exhausted failure, expiry, and manual terminal drain
- `TASK_MSG_LOGICALLY_FINAL` for success, retry-exhausted failure, non-retryable expiry, and manual terminal drain
- no `TASK_MSG_LOGICALLY_FINAL` for retryable failure reset or retryable lease-expiry reset
- `ASSIGNMENT_RETRY_SCHEDULED` when delayed assignment retry is exercised
- `CALLBACK_IGNORED_DUPLICATE` when replay is exercised
- `CALLBACK_IGNORED_LATE` when late callback is exercised
- `CALLBACK_REJECTED_NO_ACTIVE_LEASE` when a callback arrives without an active runtime lease
- `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT` only when a callback has a runtime lease but the engine still cannot recover an active attempt projection
