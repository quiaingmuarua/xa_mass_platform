# Storage Baseline

Status: current engine storage baseline.

This file records the engine-facing storage/runtime boundary only. It should
stay short: storage owns durable control-plane truth, runtime owns hot-path
queue/lease/counter truth, and bounded `TaskMsg` / `TaskMsgAttempt`
compatibility projections remain temporary engine residue rather than a growth
surface.

## Boundary

The active engine boundary is split in two:

- control-plane storage contracts in `platform_infra/mass-storage-api`
  - `TaskStorage`
  - `WorkerStorage`
  - `RuleStorage`
- hot-path runtime contract in `platform_infra/mass-runtime-api`
  - `TaskWorkRuntime`

Do not collapse them back into one "storage owns everything" model.

What the engine assumes today:

- `Task` shell truth, worker definitions, worker-context definitions, and rule
  definitions come from storage
- ready backlog, delay queues, lease ownership, retry visibility, expiry
  indexes, and backpressure come from runtime
- `TaskMsg` and `TaskMsgAttempt` remain bounded compatibility/audit projections
  still used by result repair, convergence checks, focused tests, and shell/demo
  reads

## TaskStorage

`TaskStorage` owns:

- durable `Task` shell CRUD and indexed task lookups
- bounded `taskId + messageId` lookup support needed by result write-back
- narrow message/attempt projection helpers still used by convergence and
  repair
- task-level message/attempt summary stats used by terminal policy and audits

`TaskStorage` does not own:

- ready-work admission
- in-flight lease ownership
- retry scheduling
- expiry polling for logical messages
- queue/backpressure truth

Current helper classes and methods worth treating as deliberate residue:

- runtime-essential projection helpers
  - `getNonFinalTaskMessages(...)`
  - `getLatestActiveTaskMessageAttempt(...)`
  - `addTaskMessageAttempt(...)`
  - `updateTaskMessageAttempt(...)`
  - `getTaskMessageStats(...)`
  - `getTaskMessageAttemptStats(...)`
- bounded compatibility reads
  - `getTaskMessages(...)`
  - `getTaskMessage(...)`
  - `getTaskMessageAttempts(...)`
  - `getLatestTaskMessageAttempt(...)`
  - `countTaskMessages(...)`

Rules:

- do not expand bounded compatibility reads into pagination, cross-task
  analytics, or a product-detail query model
- if a runtime path only needs pending work, prefer `getNonFinalTaskMessages(...)`
  over full message snapshots
- `getLatestActiveTaskMessageAttempt(...)` exists so result/expiry paths can
  reconcile one logical message's active attempt without scanning its full
  attempt history

## WorkerStorage

`WorkerStorage` owns:

- durable `Worker` and `WorkerContext` definitions
- indexed worker candidate lookups
- runtime worker-lock truth

Rules:

- `Worker.status` remains the online truth
- `WorkerContext.workerId` is the single ownership truth
- do not collapse the active `0..n` worker-context model back to single-context
  helpers
- worker candidate composition stays in `WorkerManager`; storage only provides
  indexed lookup primitives

## RuleStorage

`RuleStorage` owns:

- `RuleDefinition` CRUD
- evaluator registration and resolution

Rules:

- rule contracts must stay aligned with the live `WorkerMatchContext` keys
- do not move matching semantics into transport payloads or storage-side hidden
  heuristics

## Wiring Reality

Current mainline implementations:

- `platform_infra/mass-storage-memory`
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
  - `InMemoryRuleStorage`
- `platform_infra/mass-storage-jdbc`
  - JDBC control-plane storage adapter for task/worker/rule truth

Current implementation facts that matter architecturally:

- SDK/server embed the storage implementations explicitly
- engine depends on the contracts only
- the JDBC path is a control-plane adapter, not a `TaskMsg` analytics backend
- `TaskMsg` and `TaskMsgAttempt` remain process-local compatibility projection
  state in the current JDBC path
- in-memory helper indexes are allowed when they protect hot paths from full
  scans, but they are helper indexes rather than second lifecycle truth

## Convergence Direction

The shortest storage-side convergence path is:

1. keep runtime-critical projection helpers narrow
2. stop growing shell/debug message reads
3. move future detail, analytics, and timelines into trace or async audit sinks

That means the next step is not "make engine storage query richer". It is
"reduce who depends on compatibility projection reads at all".

## Read Next

- [`README.md`](./README.md)
- [`../platform_infra/README.md`](../platform_infra/README.md)
- [`../doc/DB_STORAGE_PRINCIPLES.md`](../doc/DB_STORAGE_PRINCIPLES.md)
- [`../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskStorage.java`](../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskStorage.java)
