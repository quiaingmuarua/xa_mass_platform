# High-Volume Migration Map

Last updated: 2026-04-27

This document maps the current runtime hot path to the approved high-volume
target model.

Use it when the task is about:

- deciding where to cut the current object-heavy path first
- preserving public contracts while compressing the runtime
- identifying which current invariants must survive a queue-first redesign
- planning implementation slices without drifting into a big-bang rewrite

Use with:

- [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)

Important status note:

- this file is a migration planning aid
- it describes current code paths and approved cut lines
- it does not itself deprecate symbols; only code changes plus
  `DEPRECATION_LEDGER.md` entries do that

## 1. Current Hot Path Map

The current mainline hot path after the first WorkRuntime slice looks like this:

1. create task through `POST /status/api/tasks`
2. materialize one `Task` plus one persisted `TaskMsg` per input item as the compatibility projection
3. enqueue one `TaskWorkEnvelope` per `INIT` message into `TaskWorkRuntime`
4. scheduler submits a whole `Task` into the assignment queue
5. assignment listener matches workers and claims ready envelopes through `claimReady(...)`
6. claim creates an active runtime lease, then updates `TaskMsgAttempt` and `TaskMsg` projection
7. worker adapter receives the unchanged `TaskDispatchItem`
8. result callback applies a runtime `TaskWorkResult` outcome, then updates `TaskMsgAttempt`, `TaskMsg`, and runtime-backed task progress
9. watchdog polls expired runtime leases instead of scanning every task message

That shape removes the worst assignment/expiry scans from the hot path. It
still keeps `TaskMsg` and `TaskMsgAttempt` as synchronous compatibility models,
but result aggregation and terminal convergence now read runtime counters rather
than re-scanning the message projection.

## 2. Current Hot Spots

### 2.1 Task Create Materializes Message Rows Up Front

Current path:

- `TaskManager.createTask(...)`
- one input list becomes one persisted `TaskMsg` list immediately

Pressure:

- fine for bounded lists
- wrong default shape for file-backed or million-item task sources

Migration target:

- task shell first
- explicit ingest pipeline for `batch`, `stream`, and `file`
- no requirement to materialize all messages before the task can exist

### 2.2 Assignment Queue Still Carries Whole Task Objects

Current path:

- `TaskAssignWorker`
- `BlockingQueue<Task>`

Pressure:

- task-level queueing is workable in a local runtime
- it is not the right long-term runnable unit for a distributed high-volume queue

Migration target:

- task queue for control signals only when needed
- message-envelope queue for runnable work

### 2.3 Dispatch Binds by Scanning All Messages of a Task

Current path:

- `SimpleTaskMsgAssignListener.onMsgAssign(...)`
- worker matching still happens at task level
- ready work is claimed through `TaskWorkRuntime.claimReady(...)`
- claimed work updates the existing `TaskMsg` / `TaskMsgAttempt` projection

Pressure:

- the main dispatch source no longer scans all task messages
- assignment still carries a whole task control signal and still writes a thick compatibility projection

Migration target:

- dispatch pulls ready envelopes from queue-native hot structures
- no steady-state dispatch path should require whole-task message scans

### 2.4 Result Path Still Writes Through Full TaskMsg + Attempt Projection

Current path:

- `TaskResultService.handleTaskMessageResult(...)`
- requires active `TaskMsgAttempt`
- applies runtime result counters, then updates attempt and `TaskMsg` projection

Pressure:

- semantically strong
- projection writes are still expensive as the default path for high-volume message traffic

Migration target:

- preserve correctness for lease, retry, and late-callback rejection
- move heavy attempt history out of the default hot path
- keep result aggregation on runtime/output counters

### 2.5 Watchdog Scans All Tasks and Their Messages

Current path:

- `LeaseExpireWatchdog`
- polls expired active leases from `TaskWorkRuntime.pollExpiredLeases(...)`
- still iterates tasks only for max-runtime policy enforcement

Pressure:

- lease expiry recovery is now indexed
- max-runtime policy still needs task-level scanning or a future task deadline index

Migration target:

- lease expiry should be driven by inflight indexes or timeout buckets
- do not find expired work by scanning every task and message in steady state

### 2.6 API Read Models Still Assume Whole-Task Message Browsing

Current path:

- `GET /status/api/tasks/{taskId}` includes `items` derived from all task messages
- `GET /status/api/tasks/{taskId}/messages` paginates in memory over `getTaskMessages(taskId)`

Pressure:

- useful for validation and UI inspection
- not a safe default read model for high-volume tasks

Migration target:

- task summary APIs stay
- message browsing becomes an indexed, query-oriented read model
- task lookup should not require loading all message inputs for large tasks

## 3. Current Public Contract Constraints

These are the current external or semi-external contracts that migration must
either preserve or change explicitly.

### 3.1 Task Create Route

Current constraint:

- `POST /status/api/tasks` is the only task-create HTTP route

Keep:

- single task-create route remains the mainline

May change later only with explicit contract work:

- request fields may grow for file-backed ingest
- create may return ingest metadata instead of implying all messages already exist

### 3.2 Task Business Bindings

Current constraint:

- `project` and `userId` are required business bindings

Keep:

- task ownership stays on `Task`, not hidden in payload

### 3.3 Open-Ended Append Semantics

Current constraint:

- `openEnded` maps to `TaskIntakeStatus.OPEN`
- `appendTaskItems` and `sealTask` are live APIs

Keep:

- streaming and append-window semantics remain supported

May compress:

- internal implementation should move away from full `TaskMsg` aggregate ownership

### 3.4 Worker Polling Surface

Current constraint:

- external polling workers receive `TaskDispatchItem`
- workers submit `TaskResultReport`

Keep:

- transport-neutral dispatch and result models stay the public worker-facing seam

### 3.5 Late Callback And Active Attempt Rules

Current constraint:

- late callbacks must not mutate terminal tasks
- callbacks currently require a unique active attempt

Keep:

- terminal tasks remain immutable to late results
- result acceptance must remain idempotent and lease-aware

May compress:

- active-attempt truth can be represented by smaller hot state than a full permanent attempt history

## 4. Invariants That Must Survive Compression

These invariants stay mandatory even in the lighter high-volume model.

### 4.1 Task Terminal Safety

- task terminal closure remains explicit
- terminal reason remains required
- late callbacks do not reopen or mutate terminal tasks

### 4.2 Message Retry Correctness

- retry budget remains explicit
- retryable failures or lease expiry must not publish stable-final semantics
- stable-final messages must not re-enter ready flow

### 4.3 Lease Ownership

- one active lease per runnable unit
- lease timeout recovery must not double-finalize the same work item

### 4.4 Counter Correctness

- task aggregate counters must converge to correct terminal evaluation
- high-volume completion must be driven by counters, not probabilistic scans

### 4.5 Transport-Neutral Worker Path

- dispatch, result, and worker system events remain explicit transport-neutral seams

## 5. Semantics That Can Be Downgraded

These semantics are useful, but they do not all need to stay on the default hot
path.

### 5.1 Full Attempt History For Every Message

Can downgrade to:

- failure-only audit
- sampled audit
- short-retention audit
- optional audit mode

### 5.2 Rich Persistent Mid-Flight States

Can downgrade from default persistent truth to:

- trace-only phases
- adapter-local observability
- transient runtime state

Examples:

- acked
- executor-started subphases that are not required for correctness

### 5.3 Whole-Task Message Inspection As Default Read Model

Can downgrade to:

- indexed read model
- sampled or capped views
- explicit export or debug endpoints

## 6. First Implementation Cut Lines

The first implementation slices should cut here.

### 6.1 Task Shell Compression

Touch first:

- `Task`
- task create mapping
- task list/detail summary views

Goal:

- turn `Task` into a smaller control-plane shell without breaking current create and lifecycle APIs

### 6.2 Ingest Pipeline Introduction

Touch first:

- task create path
- append path
- new file-ingest path or staging shape

Goal:

- allow task existence before all runnable messages are materialized

### 6.3 Queue-Native Dispatch

Touch first:

- `TaskAssignWorker`
- `SimpleTaskMsgAssignListener`
- task pull and dispatch flow

Goal:

- stop using whole-task message scans as the mainline dispatch source

Current status:

- first slice landed in `TaskWorkRuntime` and `InMemoryTaskWorkRuntime`
- `SimpleTaskMsgAssignListener` now claims ready work from runtime
- persisted `TaskMsg` rows remain as the compatibility projection
- Redis/JDBC replacement is intentionally deferred to a runtime-store implementation
- `FILE` task creation is constrained to a `sourceRef` shell; work items must enter through bounded ingest batches instead of initial input materialization
- inline `BATCH` create and `appendTaskItems` have explicit batch limits so high-volume producers must chunk work into the runtime queue

### 6.4 Output Queue And Counter Aggregation

Touch first:

- `TaskResultService`
- result ingest and aggregation path
- task progress resolution

Goal:

- converge tasks through incremental counters and output processing, not thick object mutation chains

### 6.5 Audit Downgrade

Touch first:

- `TaskMsgAttempt`
- watchdog lease recovery path
- trace and audit persistence policy

Goal:

- keep correctness-critical active lease truth
- move full execution audit out of the default hot path

## 7. Read Model Strategy

During migration, separate three read-model tiers.

### 7.1 Control Plane Read Model

For:

- task list
- task summary
- task lifecycle controls

Backed by:

- `Task` shell
- aggregate counters

### 7.2 Operational Message Read Model

For:

- recent failures
- inflight messages
- retry backlog
- worker-specific troubleshooting

Backed by:

- indexed hot state
- bounded windows

### 7.3 Deep Audit Read Model

For:

- failure investigations
- sampled historical replay
- long-lived diagnostics

Backed by:

- optional audit store

Working rule:

- do not force control-plane APIs to act as deep-audit APIs for million-message tasks

## 8. Verification Gates For Each Slice

### 8.1 Task Shell Compression

- task create/update/list/detail contract tests
- task terminal reason correctness
- one Boot-shell E2E smoke

### 8.2 Ingest Pipeline

- bounded-memory batch ingest
- file ingest chunking
- open-ended append and seal semantics

### 8.3 Queue-Native Dispatch

- no full-task message scan in the hot dispatch path
- worker polling path still returns `TaskDispatchItem`
- dispatch retry and refill correctness

### 8.4 Output Aggregation

- duplicate result handling
- late callback rejection
- counter-driven task convergence

### 8.5 Audit Downgrade

- lease timeout recovery
- active lease uniqueness
- failure visibility does not disappear when full audit is reduced

## 9. Rollback Boundaries

Each slice needs a narrow rollback boundary.

Safe rollback points:

- keep task-create route stable while changing ingest internals
- keep `TaskDispatchItem` stable while changing dispatch source
- keep `TaskResultReport` stable while changing result aggregation internals
- keep task terminal rules stable while changing counters and audit storage

Do not combine all of these in one irreversible step.
