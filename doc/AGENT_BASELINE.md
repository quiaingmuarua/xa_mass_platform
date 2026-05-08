# XA Mass Platform Agent Baseline

Status: current global baseline.

This file keeps the stable platform baseline only: product definition, model
boundaries, mainline reality, and hard guardrails. Use owner docs for detailed
flows, commands, and module-local inventories.

## 1. Working Rule

- verify old READMEs and architecture notes against current code before using them
- update owning contract docs in the same change when code changes documented behavior, ownership, or workflow expectations
- keep target-state or staged-refactor writing out of mainline baseline docs
- keep current docs on current truth; delete stale history unless it explains a live operational constraint

## 2. Platform Definition

- XA Mass Platform is a general distributed task scheduling platform
- the kernel problem is: match structured work items to heterogeneous,
  stateful executors, track per-item result, and converge task-level state
- stable kernel: `Task / assignment / result / audit / terminal policy`
- runtime seams are transport-neutral: task dispatch, result ingest, and system events
- runtime entry is SDK-first; demo HTTP/UI surfaces validate the kernel but do not redefine it
- observability belongs in logs, traces, counters, and bounded diagnostics, not scan-heavy hot-path projections
- process-local EventBus bridging is optional shell wiring, not default engine runtime truth

Current owner vocabulary:

- `Task` is the task/control aggregate truth
- `TaskWorkRuntime` is the hot-path owner for ready work, lease, retry, expiry,
  and result application
- current bounded compatibility residue lives behind engine-internal owners plus
  neutral storage-edge projection records; legacy message-model naming is
  intentionally not part of the active public/kernel vocabulary
- `TaskMessageProjection` / `TaskMessageAttemptProjection` are the current
  storage-edge compatibility residue shapes

Stable kernel slots:

- worker: `Worker`
- optional worker context: `WorkerContext`
- task-level workload boundary: `Task.workloadClass`
- runtime work item identity: `taskId + messageId`
- per-item runtime payload boundary: runtime ingress payload or `payloadRef`
- task-level dispatch config: `Task.sharedConfig`

## 3. Model Boundaries

Keep one canonical truth per layer:

- HTTP API: typed controller-edge DTOs plus `ApiResponse<T>`
- SDK API: `MassTaskShellCreateRequest`, `MassTaskItemBatchAppendRequest`, `EventDefinition`
- engine/core: `Task` aggregate truth plus matching, lifecycle, terminal
  semantics, and engine-internal bounded compatibility projection handling
- transport runtime: transport-neutral dispatch/result/system-event seams
- adapter layer: protocol-specific frame I/O and adapter-local codec only

Boundary rules:

- do not let protocol fields become business or lifecycle truth
- `EventDefinition.code` is globally unique capability identity
- task runtime scheduling semantics resolve from `Task.workloadClass`, not from free-form `sharedConfig`
- task orchestration and worker matching belong at task or task-slice level; do not reintroduce per-message rule matching on the hot path
- message/attempt read surfaces are bounded compatibility or audit helpers, not
  the production business-detail query model

## 4. Mainline Reality

- current mainline execution path:
  - `Task shell -> item append -> runtime enqueue -> dispatch binder -> transport delivery view -> result convergence -> task state`
- real Boot entry: `xa-mass-server`
- embedded runtime composition: `xa-mass-sdk`
- Java baseline: JDK 21 with virtual threads routed through explicit runtime abstractions
- current runtime/storage split:
  - `platform_infra/mass-runtime-api` owns queue/lease/counter contracts
  - `platform_infra/mass-runtime-memory` is the current verified runtime implementation
  - `platform_infra/mass-storage-api` owns task/worker/rule storage contracts
- current engine truth:
  - `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry, and backpressure truth
  - bounded message/attempt compatibility state is carried through
    `TaskMessageProjection` / `TaskMessageAttemptProjection` plus
    engine-internal projection access
  - `TaskManager` remains the engine-internal orchestration facade; cross-module
    callers should prefer `TaskCommandService`, `TaskQueryService`,
    `TaskResultIngestFacade`, `TaskEventService`, and runtime ports
- core acceptance modules:
  - `xa-mass-testing` for `perf`
  - `xa-mass-engine` for `concurrency`
  - `xa-mass-server` for Boot-shell `E2E`

Fast code verification path for new agents:

Read these before inferring architecture from historical vocabulary:

1. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
2. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java`
3. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java`
4. `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskWorkRuntime.java`
5. `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskDetailStore.java`

Read them to verify three things quickly:

- runtime admission happens through `TaskWorkRuntime`, not through a
  task-message CRUD mainline
- callback/expiry/result convergence is runtime-first, with compatibility
  projection written afterward as bounded residue
- bounded message/attempt reads live behind explicit compatibility surfaces and
  are not the default engine query model

## 5. Current Contract Summary

- task shell creation route: `POST /api/v1/tasks`
- `project` and `userId` are required business bindings on create
- work-item materialization is explicit through `POST /api/v1/tasks/{taskId}/items`
- `workloadClass` is explicit at create time and defaults to `BULK`
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item runtime truth stays on the runtime ingress item and dispatch/result
  flow; bounded compatibility projection may retain payload summary or
  `payloadRef`
- `Task.intakeStatus` is the append-window truth; `openEnded` is compatibility projection only
- public contracts do not define a dedicated routing-code field
- engine-provided message/attempt reads remain bounded compatibility helpers

Lifecycle and trace detail live in:

- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [E2E_BASELINE.md](./E2E_BASELINE.md)

## 6. Hard Guardrails

- prefer transport-neutral names and contracts for new cross-adapter boundaries
- `Task.sharedConfig` plus runtime item payload / `payloadRef` are the main
  payload boundaries
- `Task.project` and `Task.user` are first-class task truth; do not push them back into bags or free-form attributes
- `WorkerContext.workerId` is the single owner truth
- `WorkerMatchContext` plus rule evaluation is the matching truth
- UI pages, mock runtime, and demo APIs must not redefine the kernel
- do not add full-table, full-task, or full-attempt scans to hot paths
- new or changed policy seams must keep ownership explicit across matching, assignment, attempt, release, refill, intake, control, and terminal decisions

## 7. Read Next

- repo handoff: [../AGENTS.md](../AGENTS.md)
- full doc map: [README.md](./README.md)
- engine owner entry: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- transport owner entry: [../transport/AGENTS.md](../transport/AGENTS.md)
