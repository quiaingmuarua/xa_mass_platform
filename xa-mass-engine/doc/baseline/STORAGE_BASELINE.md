# Storage Baseline

Status: current engine storage/runtime boundary.

This file records the engine-facing split only. Keep it short and current:
storage owns durable control-plane truth, runtime owns hot-path
queue/lease/retry/backpressure truth, and bounded message/attempt projections
remain temporary compatibility residue rather than a growth surface.

## Boundary

The active engine boundary has three explicit contracts:

- control-plane storage contracts in `platform_infra/mass-storage-api`
  - `TaskStorage`
  - `WorkerStorage`
  - `RuleStorage`
- bounded compatibility projection seam in `platform_infra/mass-storage-api`
  - `TaskDetailStore`
- hot-path runtime contract in `platform_infra/mass-runtime-api`
  - `TaskWorkRuntime`

Do not collapse them back into one "storage owns everything" model.

## Engine Assumptions

What the engine assumes today:

- `Task` shell truth, worker definitions, and rule definitions come from
  storage
- ready backlog, delay queues, lease ownership, retry visibility, expiry
  indexes, and backpressure come from runtime
- result/expiry recovery should prefer runtime work-envelope metadata
  (`payloadRef`, retry counters, create time); projection residue does not
  repair public result rows
- bounded message/attempt projections are still used by attempt identity
  display/audit, focused tests, and explicit projection audit
- ingest enqueue must not roll back or fail runtime admission just because the
  compatibility message row could not be written in the same turn
- dispatch handoff no longer requires message-projection input or a persisted
  attempt object graph as the transport payload; runtime-native
  dispatch binding now carries message payload, retry summary, and
  attempt/lease ownership directly
- engine assembly wires `TaskStorage` and `TaskDetailStore` explicitly; there
  is no implicit "task storage also means detail store" fallback in the mainline

## TaskDetailStore

`TaskDetailStore` is not control-plane storage truth. It is the bounded
projection seam that still carries projection reads and audit/debug residue.

Current bounded projection helpers:

- `upsertTaskMessageProjection(TaskMessageProjection)`
- `getTaskMessageProjection(...)`
- `getTaskMessageProjections(..., limit)`
- `upsertTaskMessageAttemptProjection(TaskMessageAttemptProjection)`
- `getTaskMessageAttemptProjections(...)`
- `getLatestTaskMessageAttemptProjection(...)`
- `getLatestActiveTaskMessageAttemptProjection(...)`
- `getTaskMessageStats(...)`
- `getTaskMessageAttemptStats(...)`

Rules:

- do not expand shell/debug reads into pagination, analytics, or a product
  detail-query model
- engine hot-path code should prefer `TaskDetailStore.TaskMessageProjection`
  and `TaskDetailStore.TaskMessageAttemptProjection` over any legacy
  compatibility materialization path
- those projection records are storage-edge materialization only; production
  engine services should translate them inside the compatibility owner path
  instead of returning them as engine-facing results
- `TaskDetailStore` is now a neutral projection seam only; do not add message
  CRUD owners back into this boundary
- runtime mainline must not depend on full-message scans
- bounded projection reads must be explicit about their `limit`
- full message scans are allowed only in explicit projection-audit paths, and
  those callers should derive a bounded limit from stats instead of depending on
  an unbounded storage helper
- engine mainline should treat `TaskDetailStore` as a bounded projection sink;
  it should call projection upsert helpers rather than open-coding add/update
  CRUD flow in engine services
- in-memory and JDBC compatibility stores should keep neutral
  `TaskMessageProjection` / `TaskMessageAttemptProjection` as their internal
  residue unit where possible; compatibility materialization belongs at
  engine-internal boundaries, not as the store's internal owner shape
- runtime result convergence and active-attempt visibility should prefer the
  runtime-derived attempt id while a lease is active; bounded
  `latestAttemptId` residue or latest-attempt audit reads may help reuse the
  same compatibility row id, but they must not decide whether the runtime
  lease or current attempt identity is valid
- result/expiry/retry paths should upsert only the bounded final/latest attempt
  audit view rather than persisting intermediate recovered `DISPATCHED` or
  transient `RUNNING` rows just to keep the engine mainline moving
- engine-facing attempt projection writes should stay at latest-attempt summary
  level: attempt identity, worker binding, logical status/final reason, and
  bounded error/output summary; dispatch/ack/start/finish timelines belong to
  trace or explicit audit paths, not runtime result convergence
- callback/expiry/retry compensation may repair a bounded message view in
  memory from runtime lease truth, but must not depend on persisting
  intermediate `ASSIGNED` or transient `FAILED` projection rows before the
  final summary write
- single-message compatibility reads for non-final tasks should prefer runtime
  work-envelope / active-lease metadata first and use stored message
  projection only as fallback residue
- compatibility reads for the current active attempt should also prefer
  runtime active-lease reconstruction first; stored attempt projections remain
  the bounded history/audit residue for prior or finalized attempts
- result/expiry/retry compatibility rewrites should preserve only bounded
  residue such as `payloadRef`, logical status, retry summary, output/error
  summary, and latest-attempt linkage; they should not keep full input payload
  materialized as a hot-path persistence requirement
- result/expiry trace emission must consume runtime-native message snapshots;
  message projection remains a bounded compatibility write/read shape, not the
  mandatory event input model for hot-path convergence
- `getTaskMessageProjection(...)` is the engine-facing bounded read seam;
  do not reintroduce wider message-read helpers as default engine API

## Wiring Reality

Current mainline implementations:

- `platform_infra/mass-storage-memory`
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
  - `InMemoryRuleStorage`
- `platform_infra/mass-storage-jdbc`
  - JDBC control-plane storage adapter for task/worker/rule truth
  - process-local compatibility projection for neutral message/attempt records

Current implementation facts that matter architecturally:

- SDK/server embed the storage implementations explicitly
- engine depends on contracts only
- engine mainline now reaches `TaskDetailStore` directly from the owning
  engine services that still need bounded projection residue, instead of
  routing those reads and writes back through `TaskManager`
- the JDBC path is a control-plane adapter, not a message analytics backend
- in-memory helper indexes are allowed when they protect hot paths from full
  scans, but they remain helper indexes rather than second lifecycle truth

## Convergence Direction

The shortest convergence path remains:

1. keep runtime-critical projection helpers narrow
2. keep cross-module message/attempt reads explicitly named as projection or
   audit surfaces, not general query APIs
3. move future detail, analytics, and timelines into trace or async audit sinks

The next step is not "make engine storage query richer". It is "reduce who
depends on compatibility projection reads at all".
