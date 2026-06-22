# Task Lifecycle Baseline

Last updated: 2026-06-16

Status: current global task lifecycle baseline.

This is the short normative baseline for the active task mainline. It covers
the three top-level owners that a new agent must understand first:

```text
Task + Worker + Scheduling Plane
  -> assignment / dispatch
  -> result apply
  -> terminal convergence
```

If lifecycle semantics change, update this file, trace expectations, and
testing/E2E ownership together.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./TESTING_INDEX.md](./TESTING_INDEX.md)
- [../xa-mass-engine/doc/baseline/SCHEDULING_KERNEL_BASELINE.md](../xa-mass-engine/doc/baseline/SCHEDULING_KERNEL_BASELINE.md)

## 1. Primitive Map

| Primitive | Current meaning | Primary owner |
| --- | --- | --- |
| `Task` | task shell, contract, intake window, aggregate status, terminal reason, and task-level execution policy | engine lifecycle plus kernel-facing task-shell ports |
| `Worker` | execution identity plus WorkerGroup membership, bounded topology evidence, and declared worker facts | `xa-mass-worker-runtime` declaration/resource owners |
| `Scheduling Plane` | deciding when task work may enter competition, dispatch, retry, pause, resume, or close; which worker universe it may compete in; and which concrete worker receives it. It has three first-class owners: task scheduling policy, worker scheduling policy, and runtime worker selection. Current policy remains distributed across task runtime profile, selectors, assignment policy, matching rules, backpressure, and admission | engine lifecycle and runtime queues today; future scheduling-policy catalog plus project/workload binding when implemented |

Mechanism note:

- `Matching` is current worker-selection mechanism vocabulary inside Scheduling
  Plane, not a top-level primitive or owner. Current matching code combines
  candidate source, rule-backed eligibility, ranking, reserve/lock, and runtime
  admission. Future work should classify those pieces under worker scheduling
  policy, runtime worker selection, or diagnostics instead of preserving
  `Matching` as a fourth primitive.

Architecture boundary:

```text
Scheduling Plane
  TaskSchedulingPolicyExecution
  WorkerSchedulingPolicyResolution
  RuntimeWorkerSelection

External inputs and constraints
  SchedulingPolicyCatalog / ProjectSchedulingBinding  -> allowed/default policy selection
  TaskDispatchIntent                                  -> selected policies, route, target constraints
  WorkerGroupCapability                               -> project/event capability truth
  Item                                                -> eventCode plus payload only
```

The first two nodes are target owner boundaries. Current engine-facing value
contracts exist for `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
`ResolvedWorkerSchedulingPolicy`, but a complete scheduling policy catalog or
project/workload binding module does not exist yet. Current policy is still
spread across runtime profile, explicit selectors, matching rules, assignment
policy, backpressure, and admission behavior. The intended target is that
reusable platform policies define task scheduling and worker scheduling strategy
modes, project/workload binding chooses allowed/default policies and
configuration, task intent selects or inherits policies and narrows groups.
Inside the Scheduling Plane, task scheduling execution handles competition
admission/cadence/priority/fairness/budget, worker scheduling resolution handles
resource-universe and pool constraints, and RuntimeWorkerSelection chooses a
worker from live evidence/admission. WorkerGroup capability remains an external
capability truth that constrains worker scheduling resolution, and the item only
tells the worker which handler to execute.

The current lifecycle mainline is:

```text
task shell create
  -> item append
  -> runtime enqueue
  -> scheduling eligibility
  -> worker selection and assignment
  -> transport dispatch
  -> worker result
  -> runtime result apply
  -> visible result commit
  -> task progress / terminal convergence
```

Task item dispatch boundary:

```text
task item
  -> TaskWorkRuntime claim / lease
  -> Scheduling Plane selects concrete worker
  -> engine binds TaskDispatchBinding(workerId, workerGroupId, ...)
  -> transport handoff carries delivery metadata
  -> adapter delivers only to selectedWorkerId
```

At transport entry, the concrete worker decision is already made.
`TaskDispatchBinding.workerId` is the selected execution identity. Transport
may use that value only as a delivery constraint, named `selectedWorkerId` in
transport delivery models. It must not reinterpret the value as scheduling
authority, worker lifecycle truth, route-key minting input, capacity truth, or
fallback-worker permission.

Dispatch delivery identifiers have separate meanings:

- `selectedWorkerId`: correctness identity for the assigned worker that may
  consume the item.
- `deliveryQueueKey`: transport storage/batching/shard partition; it may be
  shared by many workers and must not encode worker selection.
- `routeKey`: opaque connection address, coarse delivery-domain metadata, or
  protocol correlation value; transport must not decode it or rely on its
  cardinality for correctness.
- `connectionId` / session token: transport lease evidence for one concrete
  connection or polling session; it is not a worker scheduling identity.
- adapter identifiers, endpoint lease ids, session handles, route keys, and
  queue keys are transport implementation facts. Engine selection must not use
  them directly; any delivery reachability needed for scheduling must be
  projected into worker-runtime evidence first.

Polling, WebSocket, and socket delivery all preserve the same rule: transport
delivers an already selected item to an available connection/session for that
`selectedWorkerId`; if no such connection exists, delivery is infeasible and
retry/compensation remains engine-owned.

## 2. Global Rules

1. `TaskStatus`, `TaskHoldReason`, `TaskIntakeStatus`, and `TaskTerminalReason` are the current task lifecycle vocabulary. Per-item runtime state lives in `TaskWorkRuntime`; server review item/attempt statuses are materialized read-model strings, not engine lifecycle truth.
2. `Task.contract` is the current public/runtime preset input (`SESSION | BATCH`). Engine behavior must consume resolved task scheduling policy values derived from it; ingress form must not redefine lifecycle, terminal, or retry semantics.
3. Worker capability truth is declared through WorkerGroup/event bindings. Worker scheduling evidence participates in runtime selection inside the selected group, but worker rows and adapters must not become a second project/event capability source.
4. Scheduling and worker selection are task-level orchestration decisions, but item payload is not a worker-selection policy source. Do not reintroduce per-message rule matching, worker-context lifecycle ownership, or item-level worker capability scans on the hot path.
5. No lifecycle change is complete without:
   - code change
   - this file update
   - trace update
   - E2E coverage update
6. `TERMINAL` task semantics are interpreted by `status + terminalReason`, not status alone.
7. Task closure stays modeled as one final status plus terminal reason. Do not split `TaskStatus` into multiple terminal enums unless API, validation, trace, and E2E baselines are redesigned together.
8. The logical work-projection status model is a bounded compatibility contract, not a complete transport-event history. Transport-specific delivery phases belong in trace/event data or a dedicated transport model.
9. The current runtime concurrency model is owned by worker scheduling facts, runtime capacity/reservation state, active worker locks where applicable, and work-runtime leases. `WorkerContext` must not be used as the engine scheduling or resource-lifecycle truth.
10. Policy changes must preserve ownership boundaries across matching, assignment, attempt, release, refill, intake, control, and terminal decisions.
11. `TaskWorkRuntime` in `platform_infra/mass-runtime-api` is the current hot-path owner for ready work, active leases, retry scheduling, retry budget, and lease expiry indexes.
12. `TaskResultRuntime` is the runtime-owned public result read truth for stable-final result rows, repair staging, and result-side attempt-closed/event/progress barriers. Server review/export rows are lagging materialized views and must not drive lifecycle decisions.
13. `Task.workloadClass` is the explicit task-level runtime optimization input; current engine truth is `INTERACTIVE` or `BULK`, and assignment signal routing consumes resolved task scheduling policy rather than free-form `sharedConfig` semantics.
14. Result callbacks follow the result-side lifecycle mainline: runtime apply truth comes from `TaskWorkRuntime.applyResultWithContext(...)`; stable-final public result rows are committed into `TaskResultRuntime`; server review reports are emitted best-effort after runtime acceptance and are not the result commit point or a public result read source.
15. `eventCode` is handler/capability identity. It validates that the selected WorkerGroup supports the item's handler and tells the worker which local handler to invoke. It is not a worker selector.
16. The architecture boundary is: task scheduling policy decides competition admission/cadence/priority/fairness/budget; worker scheduling policy decides resource-universe and pool constraints; RuntimeWorkerSelection chooses a concrete worker from live evidence/admission. Project/workload binding selects and configures allowed/default policies, task dispatch intent narrows selected policies/route/target constraints, WorkerGroup capability constrains project/event eligibility, adapter owns only final-hop connectivity, transport delivers only to `selectedWorkerId`, and item decides only the event handler plus payload.

## 3. TaskStatus

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

## 4. Task Intake

States:

- `OPEN`: task may accept new work-item ingress through append
- `SEALED`: append window is closed

Current entry points:

- create: `SESSION` and `BATCH` shells initialize `intakeStatus=OPEN`
- `sealTask`: contract-neutral intake close action; `OPEN -> SEALED` for both `SESSION` and `BATCH`

Must hold:

- automatic message-driven terminal closure is controlled by resolved idle-close policy; the current `BATCH` preset enables sealed/all-final closure
- `SESSION` tasks may close intake explicitly without becoming terminal; draining current work is not sufficient for automatic terminal closure
- every terminal task must have `intakeStatus=SEALED`
- `intakeStatus` is the active append-window lifecycle truth for both `SESSION` and `BATCH`

## 5. Result-Side Lifecycle Ownership

Result handling is the result-side counterpart to assignment:

```text
callback / result inbox
  -> transport ingress normalization
  -> optional envelope identity gate
  -> engine result ingest port
  -> TaskResultService
  -> TaskResultRuntime.stageCallback(...)
  -> TaskWorkRuntime.applyResultWithContext(...)
  -> runtime outcome interpretation
  -> trace
  -> TaskResultRuntime.commitVisibleFinal(...)
  -> result-side event/progress barriers
  -> server review report emitted for async materialization
  -> task progress / terminal convergence trigger
```

Owner split:

| Layer | Owner | Meaning |
| --- | --- | --- |
| worker result submission | `WorkerResultSubmission` / adapter worker frame | worker-submitted result body at SDK/server or adapter boundary; does not own retry, finality, or terminal policy |
| transport ingress carrier | `ResultIngressEntry(partitionKey=<resultCorrelationRef>, message)` | opaque payload, partition key, diagnostics, and creation time; transport queues and relays it without task-shaped validation |
| starter result callback projection | `TaskResultCallbackCodec`, `TaskResultCallbackCommand` | decodes opaque ingress payload/correlation and carries task result callback facts into engine-owned validation |
| engine ingest port | `TaskResultIngestFacade`, `TaskResultIngestPort` | narrow transport-to-engine callable surface |
| runtime apply truth | `TaskWorkRuntime.applyResultWithContext(...)` | lease-valid apply, retry budget consumption, runtime apply status, counters, and recent receipts |
| runtime result read truth | `TaskResultRuntime` | staged callback repair anchors, stable-final visible rows, task-local result sequence, and result-side idempotency barriers |
| engine result orchestration | `TaskResultService` | terminal/duplicate/late classification, runtime outcome interpretation, trace, review report emission, result-side events, and convergence trigger |
| server review materialization | `TaskReviewReportQueue`, `TaskReviewMaterializer`, `TaskReviewStore` | bounded UI/debug/export read view; not callback acceptance, retry/finality, public result read, or runtime truth |

Must hold:

- callbacks without an active runtime lease are not accepted by the runtime
  apply path unless recent-final receipt or task terminal state proves the
  logical result already converged
- public result reads and archive generation read stable-final rows from
  `TaskResultRuntime`, not server review rows
- visible final commit is atomic at the runtime boundary; duplicate visible
  commit resolves by `taskId + messageId`
- repair uses staged callback anchors plus runtime final truth; it is bounded
  runtime recovery, not a durable result ledger
- result-side attempt-closed, logical-final, and progress barriers are
  idempotent on runtime-owned keys and must not be inferred from server review
  side effects

Out of scope for the current lifecycle baseline:

- durable result ledger
- `outputRef` or blob-backed result storage
- server-owned transport result endpoints
- million-scale archive materialization beyond the current streaming contract

## 6. Server Review Item Status

States:

- `INIT`: created, not yet dispatched
- `ASSIGNED`: assigned and ready to execute
- `RUNNING`: executing
- `SUCCESS`: final success
- `FAILED`: final non-success
- `EXPIRED`: final non-success caused by timeout/cancel after assignment; this remains the stable logical outcome mainly for session-style result-finality policy and terminal-control overlays, not for batch-style lease-retry exhaustion

Allowed transitions:

- `INIT -> ASSIGNED`
- `ASSIGNED -> RUNNING/FAILED/EXPIRED`
- `RUNNING -> SUCCESS/FAILED/EXPIRED`
- `FAILED -> INIT` (retry reset only; `SUCCESS` is never reset)
- `EXPIRED -> INIT` (session-style result-finality retry reset only; batch-style lease expiry does not use `EXPIRED` as its logical retry base)

Current materialization entry points:

- review items accepted: materializes `INIT`
- result-side review report: materializes `SUCCESS` / `FAILED` / `EXPIRED`
- server review materialization is task opt-in by `sharedConfig.reviewMaterializationMode`;
  the server default is `OFF`, so review DB rows are not written unless a task
  explicitly selects `TERMINAL` or `DIAGNOSTIC`
- terminal task review overlay for stop reasons (`MANUAL_CANCELLED`, `MAX_RUNTIME_REACHED`, `SUCCESS_RATE_REACHED`, `RETRY_BUDGET_EXHAUSTED`):
  - bounded reads project `INIT -> FAILED`
  - bounded reads project `ASSIGNED/RUNNING -> EXPIRED`
- retry reset: logical work returns to `INIT` when retry budget remains; this is usually `FAILED -> INIT` for retryable failures and session-style expiry, while batch-style lease expiry resets directly from live runtime truth without treating `EXPIRED` as the logical mainline state

Must hold:

- review rows are server-local materialization only; they do not decide runtime
  callback acceptance, retry scheduling, finality, or task terminal convergence
- `workerId` and `batchId` in review rows are display/evidence fields, not active lease truth
- duplicate final callbacks do not mutate runtime final state
- final review item should carry a compatible `finalReason`
- `taskWorkAttemptClosed` must fire whenever an execution attempt ends, including retryable failure
- `taskWorkLogicallyFinal` must only fire when the logical message view is stably final and will not be reset for retry
- retryable failure must close the current attempt and reset the logical message view to `INIT`; it must not publish logically-final semantics
- batch-style lease expiry has no stable logical timeout meaning while the task is still live: it either resets `INIT` when runtime retry budget remains or finalizes as `FAILED + RETRY_EXHAUSTED` when the budget is exhausted
- worker/adapter callbacks must resolve an active runtime lease before result application; server review row absence must not block engine result handling
- callbacks without an active runtime lease are rejected and traced as `CALLBACK_REJECTED_NO_ACTIVE_LEASE`
- result handling applies against the runtime active lease and runtime retry budget before submitting server review reports
- result-side review materialization is best-effort after runtime acceptance; synchronous result-side events and task progress evaluation must not depend on review row write completion
- task cancellation and policy-driven task stop must not synchronously rewrite every queued review row
- `errorCode` is an optional short symbolic code set by the worker alongside `errorMessage`; it is cleared on `resetForRetry()` and must not carry over between attempts
- richer transport phases must not be silently backfilled into the server review item status model without a baseline redesign

## 7. Server Review Attempt Status

Server review attempts are materialized summaries of concrete execution
opportunities. They are not raw transport-event logs and do not own runtime
lease truth.

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
- active attempt truth outranks materialized `workerId` / `batchId` on server review rows
- at most one active attempt may exist for a single `taskId + messageId`
- a stable-final logical message must not have any active attempt
- if a runtime lease exists but the review attempt row is missing, engine
  callback/expiry handling still proceeds from runtime truth and server
  materialization may lag
- `REVOKED` must not be used as an expiry shortcut; only `EXPIRED` carries expiry and cancellation final reasons

## 8. Legacy WorkerContext Compatibility

`WorkerContextStatus`, WorkerContext CRUD/API/storage surfaces, and context-id
runtime/transport/projection/trace payloads have been removed. WorkerContext is
not active resource ownership truth.

Current engine scheduling and resource lifecycle truth comes from:

- worker registration identity plus WorkerGroup membership and bounded topology
  evidence
- `WorkerGroup.eventBindings` capability facts
- worker scheduling attributes / routing tags
- process-local reachability and runtime load/capacity views
- active worker locks when the selected workload mode requires them
- runtime work leases and attempt close events

Must hold:

- new scheduling behavior must not depend on WorkerContext status transitions
- compatibility diagnostics must not recreate WorkerContext state
- deleting or retiring WorkerContext compatibility must not change task status,
  intake, retry, final-result, or terminal semantics

## 9. TaskTerminalReason

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
- batch message-driven closure must match engine work-runtime aggregate counters; server review rows remain lagging operator/audit views
- persisted terminal task state must always carry `intakeStatus=SEALED`; batch message-convergence reasons still require intake to be sealed before automatic closure
- `SESSION` tasks do not auto-close to `ALL_MESSAGES_SUCCEEDED` / `ALL_MESSAGES_FAILED` / `MIXED_MESSAGE_RESULTS` just because the current runtime work set drained

## 10. Time-Based Policy Enforcement

Both policies are enforced by `LeaseExpireWatchdog` (runs every `leaseWatchdogIntervalSeconds`, default 30 s):

- **Lease expiry**: expired active leases are pulled from `TaskWorkRuntime.pollExpiredLeases(...)` and expired via
  the engine lease-maintenance path (`TaskLeaseMaintenancePort.expireLeasedWork(...)`). This always publishes
  `taskWorkAttemptClosed` for resource release and may later materialize an
  `EXPIRED` review attempt. If retry budget remains, the logical work item is reset to `INIT`,
  `TASK_WORK_RETRY_RESET` is emitted, and redispatch is requested without `taskWorkLogicallyFinal`. When retry
  budget is exhausted, `SESSION` keeps logical `EXPIRED`, while `BATCH` finalizes as `FAILED + RETRY_EXHAUSTED`
  because lease loss is treated as an attempt failure mode rather than a stable per-item timeout contract.
- **Max task runtime**: non-terminal tasks with `maxRuntimeSeconds > 0` are indexed by their
  runtime deadline and polled through `TaskShellLifecycleQuery.pollTasksPastMaxRuntimeDeadline(...)`; expired
  tasks are terminated with `MAX_RUNTIME_REACHED` via `TaskManager.terminateTask()`. Set
  `maxRuntimeSeconds = 0` (default) to disable the limit.

Must hold:
- `expireLeasedWork` must fire `taskWorkAttemptClosed` after expiry so resource
  release listeners can release worker locks/reservations and compatibility
  residue; skipping this call leaves runtime resource state stuck
- retryable lease expiry must follow the same logical-reset rule as retryable failure: close the attempt,
  update runtime retry counters, and avoid logical-final publication until the
  retried logical message becomes stably final
- retryable callback failure and lease expiry must branch from runtime result application outcome; review materialization follows that decision and does not override it
- `terminateTask(reason)` follows the same drain-and-notify path as `cancelTask`; the only
  difference is the `TaskTerminalReason` recorded

## 11. Core Invariants

1. `taskNonSuccessNumber == taskEligibleNumber - taskSuccessNumber` (derived; `setTaskNonSuccessNumber` ignores its argument and recomputes to prevent silent invariant breaks)
2. late callbacks after manual terminal closure do not mutate task/message state
3. routing-required tasks do not run on workers whose scheduling attributes,
   routing tags, capability bindings, or reachability facts do not satisfy the
   task policy
4. worker release does not happen while that worker still has a non-final latest attempt for the same task
5. resource release must target the exact active worker/attempt/resource binding
6. review item final reason must match review item status when materialized
7. active attempt and final logical message must not coexist
