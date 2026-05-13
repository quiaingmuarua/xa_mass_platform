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
- kernel truth is explicitly split across:
  - `Task.contract`
  - `Task.intakeStatus`
  - `TaskWorkRuntime` for ready/delayed/lease/counter truth
- result convergence is runtime-first, but the active owner split must be
  verified from `RESULT_BOUNDARY_BASELINE.md` plus current engine/runtime code
- runtime seams are transport-neutral: task dispatch, result ingest, and system events
- runtime entry is SDK-first; demo HTTP/UI surfaces validate the kernel but do not redefine it
- observability belongs in logs, traces, counters, and bounded diagnostics, not scan-heavy hot-path projections
- process-local EventBus bridging is optional shell wiring, not default engine runtime truth

SDK-first boundary rules:

- the stable integration boundary for workers, embedding clients, and external
  automation is the SDK contract surface, not server DTOs and not engine/base
  aggregates
- `xa-mass-server` is the reference host and validation shell; host auth,
  project, tenant, user, and console requirements may shape server APIs, but
  they must not redefine kernel owner semantics
- `xa-mass-base` and `xa-mass-engine` models may evolve quickly; public
  compatibility is preserved through SDK request models and SDK snapshot
  read-models instead of freezing internal `Task` / `Worker` / runtime types
- SDK snapshots are contract read-models only; engine/runtime logic must not
  consume SDK snapshots as decision input

Current owner vocabulary:

- `Task` is the task/control aggregate truth
- `Task.contract` is the runtime contract truth: `SESSION | BATCH`
- `Task.intakeStatus` is the intake-window truth: `OPEN | SEALED`
- `TaskWorkRuntime` is the hot-path owner for ready work, lease, retry, expiry,
  and backpressure truth
- result apply and visible final-result ownership are runtime-first concerns;
  verify `TaskResultService`, `TaskWorkRuntime.applyResultWithContext(...)`,
  `TaskResultRuntime`, and `RESULT_BOUNDARY_BASELINE.md` together before
  documenting the split more narrowly
- current bounded compatibility residue lives behind engine-internal owners plus
  neutral storage-edge projection records; legacy message-model naming is
  intentionally not part of the active public/kernel vocabulary
- `TaskMessageProjection` / `TaskMessageAttemptProjection` are the current
  storage-edge compatibility residue shapes

Stable kernel slots:

- worker: `Worker`
- optional worker context: `WorkerContext`
- task contract boundary: `Task.contract`
- task intake boundary: `Task.intakeStatus`
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
- do not let server view DTOs or SDK snapshots become kernel runtime truth
- task contract owns lifecycle, terminal, and default dispatch expectation
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
  - `platform_infra/mass-runtime-api` owns queue/lease/counter contracts plus
    the active result-runtime boundary
  - `platform_infra/mass-runtime-memory` is the current verified runtime implementation
  - `platform_infra/mass-storage-api` owns task/worker/rule storage contracts
- current engine truth:
  - `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry,
    and backpressure truth
  - result convergence is runtime-first and currently crosses
    `TaskResultService`, `TaskWorkRuntime.applyResultWithContext(...)`, and the
    result-runtime/public-result boundary documented in
    `RESULT_BOUNDARY_BASELINE.md`
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
5. `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java`
6. `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskDetailStore.java`
7. `doc/RESULT_BOUNDARY_BASELINE.md`

Read them to verify three things quickly:

- runtime admission happens through `TaskWorkRuntime`, not through a
  task-message CRUD mainline
- callback/expiry/result convergence is runtime-first; verify the split between
  runtime apply truth, stable-final result rows, and compatibility residue from
  the active result baseline and code
- bounded message/attempt reads live behind explicit compatibility surfaces and
  are not the default engine query model

## 5. Current Contract Summary

- task shell creation route: `POST /api/v1/tasks`
- `project` and `userId` are required business bindings on create
- shell create runs in single-tenant mode with tenant-aware semantics; current
  default tenant is `default`
- `Task.project` is the task-owned business container; capability/event auth is
  expected to converge on project grant plus explicit ingest declaration rather
  than task-level event truth
- `taskName` is a server-derived display field, not a client-provided shell
  truth field
- work-item materialization is explicit through `POST /api/v1/tasks/{taskId}/items`
- `executionSpec` is the task-level execution policy envelope; current defaults
  remain `contract=BATCH`, `profile=STANDARD`, `workloadClass=BULK`,
  `batchSize=1`, `maxRuntimeSeconds=0`
- ingress form such as inline create, repeated append, file import, or
  `sourceRef` metadata does not define the runtime contract; engine lifecycle,
  dispatch, and terminal semantics come from `Task.contract`
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item runtime truth stays on the runtime ingress item and dispatch/result
  flow; bounded compatibility projection may retain payload summary or
  `payloadRef`
- `Task.intakeStatus` is the append-window truth; the legacy boolean intake projection has been removed from the task model
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
