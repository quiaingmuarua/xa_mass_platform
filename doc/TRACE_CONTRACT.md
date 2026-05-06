# Trace Contract

Last updated: 2026-05-06

Status: current global trace contract.

This file documents the single canonical trace model for XA Mass Platform.
There is no second trace vocabulary for logs, sink payloads, or transport
adapters. The code model in `platform_infra/mass-trace-sink` is the source of
truth; this document explains how to use it and what it must contain.

Trace is a new platform feature. Older MDC lifecycle logs are useful temporary
diagnostics, but they are not the contract and they must not define the long-
term trace vocabulary.

Use with:

- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md)
- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [../platform_infra/mass-trace-sink/README.md](../platform_infra/mass-trace-sink/README.md)

## 1. Canonical Model

Canonical trace objects are:

- `com.xa.mass.trace.sink.ExecutionEvent`
- `com.xa.mass.trace.sink.ExecutionEventType`

Canonical event name:

- `ExecutionEvent.eventType`

Canonical schema:

- `xa.mass.execution-event.v1`

Rules:

1. `ExecutionEventType` is the only stable event-name vocabulary.
2. Do not maintain a second event-name registry in engine logs, transport docs,
   tests, or dashboards.
3. If an event name changes, update the enum, this document, and the emitting
   tests in the same change.
4. Trace placement still follows infra truth layering: trace is for lifecycle
   history, debugging, replay assistance, and analytics, not for control-plane
   or runtime correctness.

## 2. Stable Event Types

Current stable event types:

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
- `LEASE_EXPIRED`
- `WORKER_ONLINE`
- `WORKER_OFFLINE`

Do not introduce synonym drift such as `*_CHANGED` beside `*_TRANSITION`, or
parallel "summary" and "snapshot" names for the same semantic event.

## 3. Event Shape

Every trace record is one `ExecutionEvent` with these stable top-level groups:

- `schema`
- `eventId`
- `eventType`
- `category`
- `severity`
- `ts`
- `tsIso`
- `traceId`
- `spanId`
- `parentSpanId`
- `node`
- `identity`
- `transition`
- `outcome`
- `attrs`

Field ownership:

- top-level scalar fields: envelope identity and event routing metadata
- `node`: producer-node context
- `identity`: task / message / attempt / worker / lease identity bag
- `transition`: state transition semantics only
- `outcome`: success/error outcome semantics only
- `attrs`: event-type-specific supplemental fields

`attrs` is not a dumping ground for fields that should have a stable home. If a
field becomes required across multiple emitters or multiple event types, move it
into a fixed part of the schema or standardize it explicitly here.

## 4. Required Population Rules

### Common required groups

Every event must populate:

- `eventType`
- `category`
- `severity`
- `ts`
- `tsIso`
- `identity`

### Identity rules

Populate these when relevant:

- task events: `identity.taskId`
- task-message events: `identity.taskId + identity.messageId`
- attempt events: `identity.taskId + identity.messageId + identity.attemptId`
- worker-context events: `identity.workerId + identity.workerContextId`
- lease-specific events: `identity.leaseToken` when available

### Transition rules

Populate `transition` only when the event describes a lifecycle change or
policy transition outcome:

- `TASK_STATUS_TRANSITION`
- `TASK_TERMINAL_CLOSED`
- `TASK_MSG_STATUS_TRANSITION`
- `TASK_MSG_ATTEMPT_STATUS_TRANSITION`
- `TASK_MSG_RETRY_RESET`
- `WORKER_CONTEXT_STATUS_TRANSITION`
- `LEASE_EXPIRED`

Use:

- `transition.src`
- `transition.dst`
- `transition.reason`

Do not backfill transport delivery phases into `transition` unless they are
promoted to stable platform lifecycle semantics.

### Outcome rules

Populate `outcome` when the event represents a decision, acceptance, rejection,
or execution result:

- callback acceptance/rejection
- worker match acceptance/rejection
- resource release failure
- message final success/failure/expiry summaries

Recommended fields:

- `outcome.success`
- `outcome.errorCode`
- `outcome.detail`

### Attr rules

Standardized `attrs` fields currently include:

- `trigger`
- `source`
- `reason`
- `result`
- `terminalReason`
- `finalReason`
- `attemptNo`
- `retryCount`
- `retryDelayMillis`
- `workRetryDelayMillis`
- `currentStatus`
- `requiredMinWorkerCount`
- `workloadClass`
- `dispatchLane`
- `dispatchPriority`
- `batchPolicy`
- `leaseProfile`
- `backpressureClass`
- task progress counters and funnel fields
- assignment summary counts
- queue snapshot counts
- validation details such as `valid`, `needsResolution`, `violationCount`, `violations`
- policy fields such as `policyName` and `decision`

Any new `attrs` field used by more than one emitter must be named consistently
and documented here in the same change.

## 5. Minimum Required Paths

The canonical model must be able to represent these flows:

- `Task`: `NEW -> READY`, `READY -> RUNNING`, `RUNNING/PAUSED/BLOCKED -> TERMINAL`
- task progress reconciliation snapshots
- `TaskMsg`: `INIT -> ASSIGNED -> RUNNING -> SUCCESS/FAILED/EXPIRED`
- `TaskMsgAttempt`: `CREATED -> LEASED -> DISPATCHED -> ... -> final`
- retry reset without falsely claiming logical finality
- worker-context reservation / occupation / release transitions
- worker lock acquire / release
- worker match accept / reject
- dispatch request / skip
- assignment summary and queue pressure snapshots
- callback accepted / duplicate / late / rejected
- resource release success / failure
- lease expiry
- validation and reconciliation decisions

## 6. Replayability Requirement

Given a `taskId`, operators must be able to reconstruct:

1. when the task entered `READY`
2. why it entered `RUNNING`
3. which worker/context each message used
4. which attempt delivered each message and how that attempt finished
5. whether retry happened
6. why the task closed to `TERMINAL`
7. which resources were released
8. what the aggregate task funnel looked like at reconciliation points
9. how many workers were requested, matched, and actually used per assignment attempt
10. whether validation detected invariant drift or pending reconciliation
11. whether pressure came from queue backlog, delayed retry accumulation, or lease expiry

## 7. Non-Goals

The trace contract does not grant ownership of:

- control-plane task truth
- runtime queue or lease correctness
- durable hot-path counters
- transport protocol payload persistence

Trace may assist replay, debugging, and analytics. It must not become the only
source of correctness for task lifecycle decisions.

## 8. Test Requirement

The contract is only valid if tests pin it.

At minimum, tests must assert that:

- events serialize with the correct `eventType`
- category and severity defaults remain stable per event type
- identity and transition blocks are populated correctly
- `attrs` remains a JSON object for event-specific fields
- sink rotation, overflow, and shutdown draining preserve the trace contract

As engine and transport adopt the sink, lifecycle tests must assert the real
event stream against this canonical model rather than against MDC log strings.
