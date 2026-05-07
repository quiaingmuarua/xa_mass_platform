# Storage Baseline

Status: current engine storage/runtime boundary.

This file records the engine-facing split only. Keep it short and current:
storage owns durable control-plane truth, runtime owns hot-path
queue/lease/retry/backpressure truth, and bounded `TaskMsg` /
`TaskMsgAttempt` projections remain temporary compatibility residue rather
than a growth surface.

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

- `Task` shell truth, worker definitions, worker-context definitions, and rule
  definitions come from storage
- ready backlog, delay queues, lease ownership, retry visibility, expiry
  indexes, and backpressure come from runtime
- `TaskMsg` and `TaskMsgAttempt` remain bounded compatibility projections used
  by result repair, attempt identity validation, focused tests, and explicit
  projection audit
- ingest enqueue must not roll back or fail runtime admission just because the
  compatibility `TaskMsg` row could not be written in the same turn
- dispatch handoff no longer requires `TaskMsg.input` or a persisted
  `TaskMsgAttempt` object graph as the transport payload; runtime-native
  dispatch binding now carries message payload, retry summary, and
  attempt/lease ownership directly
- engine assembly wires `TaskStorage` and `TaskDetailStore` explicitly; there
  is no implicit "task storage also means detail store" fallback in the mainline

## TaskDetailStore

`TaskDetailStore` is not control-plane storage truth. It is the bounded
projection seam that still carries compatibility reads and result/repair
helpers.

Current runtime-essential helpers:

- `addTaskMessage(...)`
- `updateTaskMessage(...)`
- `getTaskMessage(...)`
- `addTaskMessageAttempt(...)`
- `updateTaskMessageAttempt(...)`
- `getLatestTaskMessageAttempt(...)`
- `getLatestActiveTaskMessageAttempt(...)`
- `getTaskMessageStats(...)`
- `getTaskMessageAttemptStats(...)`

Current shell/debug compatibility reads:

- `getTaskMessages(...)`
- `getTaskMessage(...)`
- `getTaskMessageAttempts(...)`
- `countTaskMessages(...)`

Rules:

- do not expand shell/debug reads into pagination, analytics, or a product
  detail-query model
- engine query seams that still return `TaskMsg`, `TaskMsgAttempt`, or
  `TaskMessageSnapshot` should be explicitly marked compatibility-only and must
  not be treated as the default external read API going forward
- runtime mainline must not depend on full-message scans
- full `TaskMsg` scans are allowed only in explicit projection-audit paths
- `getLatestActiveTaskMessageAttempt(...)` remains a transitional repair helper
  for runtime-to-projection convergence, but transport result ingest no longer
  requires an active compatibility attempt row for envelope identity validation
- runtime result convergence should prefer `TaskMsg.latestAttemptId`; when that
  field is missing, a bounded latest-attempt audit read may be used only to
  reuse the final audit row id, not to decide whether the runtime lease is
  valid
- `addTaskMessageAttempt(...)` and `updateTaskMessageAttempt(...)` are bounded
  compatibility writes only; dispatch, result convergence, and retry scheduling
  must continue from runtime truth even when these writes are missing or fail
- result/expiry/retry paths should upsert only the bounded final/latest attempt
  audit view rather than persisting intermediate recovered `DISPATCHED` or
  transient `RUNNING` rows just to keep the engine mainline moving
- callback/expiry/retry compensation may repair a bounded `TaskMsg` view in
  memory from runtime lease truth, but must not depend on persisting
  intermediate `ASSIGNED` or transient `FAILED` projection rows before the
  final summary write
- `getTaskMessage(...)` may still be used to repair or recreate a bounded
  compatibility `TaskMsg` view, but dispatch payload construction must not
  require reading projection input from this seam
- `getNonFinalTaskMessages(...)` is no longer allowed in engine task-terminal
  mainline cleanup; keep it only for explicit compatibility audit/testing until
  the remaining residue is removed

## Wiring Reality

Current mainline implementations:

- `platform_infra/mass-storage-memory`
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
  - `InMemoryRuleStorage`
- `platform_infra/mass-storage-jdbc`
  - JDBC control-plane storage adapter for task/worker/rule truth
  - process-local compatibility projection for `TaskMsg` / `TaskMsgAttempt`

Current implementation facts that matter architecturally:

- SDK/server embed the storage implementations explicitly
- engine depends on contracts only
- engine still reaches `TaskDetailStore` through `TaskManager`-owned internal
  helpers; there is no separate bridge layer that pretends bounded projection
  access has a distinct runtime lifecycle
- the JDBC path is a control-plane adapter, not a `TaskMsg` analytics backend
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
