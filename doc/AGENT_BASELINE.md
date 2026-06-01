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
- runtime entry is SDK-first; server HTTP/UI surfaces form a lightweight
  backend product shell and validation host, but they do not redefine kernel
  ownership
- observability belongs in logs, traces, counters, and bounded diagnostics, not scan-heavy hot-path projections
- canonical trace write-path ownership stays in `platform_infra/mass-trace-sink`;
  operator trace read/query ownership stays in `xa-mass-trace`
- process-local EventBus bridging is optional shell wiring, not default engine runtime truth

SDK-first boundary rules:

- the stable integration boundary for workers, embedding clients, and external
  automation is the SDK contract surface, not server DTOs and not engine/base
  aggregates
- `xa-mass-server` is the reference host and lightweight backend product
  skeleton; host auth, IAM, API-key, project, tenant, user, and console
  requirements may shape server APIs, but they must not redefine kernel owner
  semantics
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
- `xa-mass-trace` is the current operator-facing read path for canonical trace
  artifacts; it does not own a second event schema or lifecycle truth
- current bounded review/export materialization is server-local and lagging;
  legacy message-model naming is intentionally not part of the active
  public/kernel vocabulary
- `WorkerContext` is retired historical compatibility vocabulary, not active
  SDK/server/storage/trace truth. It is not an engine scheduling truth and must
  not be reintroduced as the worker capability or resource-lifecycle owner.

Stable kernel slots:

- worker: `Worker`
- worker scheduling view: worker registration, event bindings, scheduling
  attributes, reachability, and runtime load/capacity facts
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
  semantics, and runtime-first result convergence
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
  - `xa-mass-kernel-spi` owns kernel-facing task shell ports and matching rule
    value contracts
  - `platform_infra/mass-storage-api` owns persistence/control-plane task shell
    and rule storage contracts
  - `platform_infra/mass-trace-sink` owns canonical trace schema + sink write
    path; `xa-mass-trace` owns local operator read/query over that output
- current engine truth:
  - `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry,
    and backpressure truth
  - result convergence is runtime-first and currently crosses
    `TaskResultService`, `TaskWorkRuntime.applyResultWithContext(...)`, and the
    result-runtime/public-result boundary documented in
    `RESULT_BOUNDARY_BASELINE.md`
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
6. `doc/RESULT_BOUNDARY_BASELINE.md`
7. `xa-mass-trace/README.md`
8. `doc/TRACE_CONTRACT.md`

Read them to verify three things quickly:

- runtime admission happens through `TaskWorkRuntime`, not through a
  task-message CRUD mainline
- callback/expiry/result convergence is runtime-first; verify the split between
  runtime apply truth, stable-final result rows, and server-local review
  materialization from the active result baseline and code
- bounded message/attempt review reads are server/API materialization surfaces
  and are not the default engine query model
- canonical trace diagnosis should start from `xa-mass-trace` over sink output,
  not from MDC string logs or ad hoc projection reads

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
  flow; server-local review materialization may retain payload summary or
  `payloadRef` for operator views
- `Task.intakeStatus` is the append-window truth; the legacy boolean intake projection has been removed from the task model
- public contracts do not define a dedicated routing-code field
- message/attempt reads are server-local review/export helpers, not engine
  runtime truth

Lifecycle and trace detail live in:

- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [E2E_BASELINE.md](./E2E_BASELINE.md)

## 6. Hard Guardrails

- prefer transport-neutral names and contracts for new cross-adapter boundaries
- `Task.sharedConfig` plus runtime item payload / `payloadRef` are the main
  payload boundaries
- `Task.project` and `Task.user` are first-class task truth; do not push them back into bags or free-form attributes
- worker capability truth is `WorkerGroup.eventBindings`; worker registration
  declares execution identity and group/node membership. Scheduling decisions
  must consume explicit group selectors, group capability, worker scheduling
  facts, and runtime load/capacity facts, not worker-level capability overrides
  and not `WorkerContext`
- `WorkerMatchContext` plus rule evaluation is the current default matching
  input path, not the final policy model. Future matching may use worker
  intrinsic metrics, task-type affinity, fairness, and observed performance,
  but those inputs must stay explicit scheduling evidence and must not become
  replacement worker-resource ownership
- UI pages, mock runtime, and demo APIs must not redefine the kernel
- do not add full-table, full-task, or full-attempt scans to hot paths
- new or changed policy seams must keep ownership explicit across matching, assignment, attempt, release, refill, intake, control, and terminal decisions

## 7. Read Next

- repo handoff: [../AGENTS.md](../AGENTS.md)
- full doc map: [README.md](./README.md)
- engine owner entry: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- transport owner entry: [../transport/AGENTS.md](../transport/AGENTS.md)
