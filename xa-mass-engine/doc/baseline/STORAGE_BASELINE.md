# Storage Baseline

Status: current engine storage/runtime boundary.

This file records the engine-facing split only. Storage owns durable
control-plane truth, runtime owns hot-path queue/lease/retry/backpressure
truth, and server review/export owns lagging operator read models.

## Boundary

The active engine boundary has three explicit contract groups:

- control-plane storage contracts in `platform_infra/mass-storage-api`
  - `TaskShellStore`
  - `RuleStorage`
- worker declaration port in `xa-mass-worker-runtime`
  - `WorkerDeclarationStore`
- hot-path runtime contracts in `platform_infra/mass-runtime-api`
  - `TaskWorkRuntime`
  - `TaskResultRuntime`

Do not collapse them back into one "storage owns everything" model.

## Engine Assumptions

What the engine assumes today:

- `Task` shell truth and rule definitions come from storage; worker
  declarations come through the worker-runtime declaration port.
- Ready backlog, delay queues, lease ownership, retry visibility, expiry
  indexes, and backpressure come from runtime.
- Result/expiry recovery should prefer runtime work-envelope metadata,
  runtime leases, runtime final receipts, and `TaskResultRuntime` stable-final
  rows.
- Ingest enqueue must not roll back or fail runtime admission because a server
  review row cannot be materialized in the same turn.
- Dispatch handoff does not require message-projection input or a persisted
  attempt object graph as the transport payload; runtime-native dispatch
  binding carries message payload, retry summary, and attempt/lease ownership
  directly.
- Engine assembly wires `TaskShellStore` without a detail/projection store
  fallback.

## Review Rows

Server review/export rows are not engine storage truth. They are lagging
materialized views owned by `xa-mass-server` through its review report queue,
materializer, and `TaskReviewStore`.

Rules:

- engine runtime code must not read review rows to decide callback acceptance,
  retry scheduling, finality, or terminal convergence
- engine runtime code must not require review row persistence before publishing
  result-side events or applying task progress
- server review rows may summarize item status, final reason, worker/batch
  evidence, payload refs, input, and output for operator views
- full item history and attempt timelines belong to trace/audit/export
  ownership, not engine storage contracts

## Wiring Reality

Current mainline implementations:

- `platform_infra/mass-storage-memory`
  - `InMemoryTaskShellStore`
  - `InMemoryWorkerDeclarationStore` as a worker-runtime declaration adapter
  - `InMemoryRuleStorage`
- `platform_infra/mass-storage-jdbc`
  - JDBC control-plane storage adapter for task/rule truth
- `xa-mass-server`
  - server-local in-memory/JDBC review stores for review/export materialization

Current implementation facts that matter architecturally:

- SDK/server embed the storage implementations explicitly
- engine depends on contracts only
- the JDBC path is a control-plane adapter, not a message analytics backend
- in-memory helper indexes are allowed when they protect hot paths from full
  scans, but they remain helper indexes rather than second lifecycle truth

## Convergence Direction

The shortest convergence path remains:

1. keep runtime-critical storage contracts narrow
2. keep cross-module message/attempt reads outside engine runtime
3. move future detail, analytics, and timelines into trace or async audit sinks

The next step is not "make engine storage query richer". It is "keep engine
runtime independent from review/export materialization".
