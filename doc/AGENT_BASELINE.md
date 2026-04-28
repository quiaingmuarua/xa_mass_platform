# XA Mass Platform Agent Baseline

This file keeps the stable project baseline only: product definition, mainline
truth, and hard guardrails.

Use owner docs for commands, full API inventories, and transport-local detail:

- [../AGENTS.md](../AGENTS.md)
- [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
- [../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

## 1. Using This File

Use the canonical trust order in [../AGENTS.md](../AGENTS.md).

Working rule:

- verify old READMEs and architecture notes against current code before using them
- update owning contract docs in the same change when code changes documented behavior, ownership, or workflow expectations
- update active docs after confirming runtime truth
- keep mainline docs current; delete stale history unless it explains a live operational constraint
- keep target-state or staged-refactor writing out of mainline baseline docs; if it is not implemented yet, label it as design/refactor-only and keep current docs describing current truth

## 2. Platform Definition

- The project is a general distributed task scheduling platform.
- The core product problem is not "send work over one transport"; it is "match structured work items to heterogeneous, stateful executors, track each item result, and converge task-level state".
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The kernel value is the combination of `stateful worker + capability/routing match + per-item result tracking + task-level convergence`.
- The platform is scenario-agnostic. It owns dispatch, result write-back, and task convergence rather than business payload meaning.
- Stable kernel: `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- The current runtime model is transport-agnostic: task dispatch, result ingest, and worker system events are explicit seams rather than one transport shape.
- Observability belongs in logs, traces, counters, and bounded diagnostics. Do not push scan-heavy operational introspection back into hot-path domain models.
- Favor idempotent dispatch, result, and retry-side operations so duplicate delivery or reconnect churn stays manageable.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- The runtime entry is library/SDK-first. Demo runtime surfaces validate the kernel; they do not redefine it.

Canonical slots:

- worker: `Worker`
- worker context: `WorkerContext` and it is optional
- task workload class: `Task.workloadClass`
- work item: `TaskMsg`
- per-item payload: `TaskMsg.input/output`
- task-level dispatch config: `Task.sharedConfig`

## 3. Model Boundaries

Keep one canonical truth per boundary:

- HTTP API: typed controller-edge request models plus `ApiResponse<T>`
- SDK API: `MassTaskCreateRequest`, `MassTaskRequest`, `EventDefinition`
- engine/core: `Task`, `TaskMsg`, `TaskMsgAttempt`, matching and lifecycle state
- transport runtime: transport-neutral dispatch/result/system-event seams
- WebSocket adapter: raw frame I/O, adapter-local codec, and addressability only

Rules:

- do not let protocol fields become business or lifecycle truth
- do not let the same class name mean different things across layers
- `EventDefinition.code` is globally unique capability identity
- SDK-first resource creation is the preferred path: `registerWorker(...)`, `registerWorkerContext(...)`, `registerProject(...)`, `registerEventDefinition(...)`
- `Task.workloadClass` is the explicit task-level runtime optimization boundary; current mainline values are `INTERACTIVE` and `BULK`
- task runtime scheduling semantics resolve from `Task.workloadClass`, not from free-form `sharedConfig` keys
- task orchestration and worker matching belong at the task or task-slice level; do not reintroduce per-`TaskMsg` rule matching on the hot path
- `TaskMsg` read surfaces are bounded compatibility or audit helpers, not the future business-detail query model
- large-scale task detail analysis belongs in structured trace, audit sinks, or downstream storage, not engine-owned full-message query projections

## 4. Architectural Guardrails

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy.
- Prefer transport-neutral names and contracts for new cross-adapter boundaries.
- `WorkerContext` is optional worker context. Not every worker model requires one.
- The active API is explicitly `0..n`: do not reintroduce single-context helper APIs keyed only by `workerId`; use `getWorkerContexts(...)` or `getWorkerContextById(...)`.
- `WorkerContext.workerId` is the single owner truth; attachment APIs should accept the `WorkerContext` object itself rather than duplicating the owner `workerId` as a second parameter.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress back to single-purpose top-level fields such as `textContent`.
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate. Do not push project/user identity back into `sharedConfig`, `TaskMsg.input`, or attribute bags.
- Worker matching truth is `RuleDefinition.content` evaluated by QLExpress over `WorkerMatchContext`.
- `Task` is the orchestration unit. If a workload needs materially different routing or capability semantics, split it into separate tasks or explicit task-owned slices instead of falling back to per-message matching.
- The typed JSON DSL path goes through `JsonDslParser -> JsonDslDefinition -> JsonDslProcessorEngine`.
- Prefer SDK registration models for new resource scenarios; low-level core-model mutation APIs are not the default path.
- UI pages, mock runtime, and demo APIs must not redefine the platform kernel.
- Do not add full-table, full-task, or full-attempt scans to hot paths for observability convenience; use indexed lookups, traces, and counters.
- new or changed policy seams must keep ownership explicit across matching, attempt, release, refill, intake, control, and terminal decisions; use [../xa-mass-engine/POLICY_INTERACTION_BASELINE.md](../xa-mass-engine/POLICY_INTERACTION_BASELINE.md) before extending those paths

## 5. Mainline Reality

- The real Spring Boot entry is `xa-mass-server`.
- Java baseline is JDK 21. The root reactor compiles with `maven.compiler.release=21`, Java worker samples use release 21, and CI is expected to run on Temurin 21.
- Java 21 virtual threads are the runtime baseline for blocking concurrency boundaries when routed through explicit runtime abstractions. They reduce concurrency complexity, but they must not redefine engine lifecycle correctness, worker lock ownership, or `TaskMsgAttempt` state semantics.
- Runtime executor boundary: `com.xa.mass.base.runtime.RuntimeTaskExecutor`
- SDK control-plane event dispatch is synchronous by default; bounded virtual-thread isolation is optional in SDK embedding
- Embedded runtime composition lives in `xa-mass-sdk`; `xa-mass-server` consumes it and owns the current HTTP/control-console/frontend shell
- Transport modules: `transport/transport_api`, `transport/transport_runtime`, `transport/polling-adapter`, `transport/websocket-adapter`, `transport/socket-adapter`
- Reactor truth comes from the root `pom.xml`; current active modules are `xa-mass-base`, `xa-mass-transport-api`, `xa-mass-transport-polling`, `xa-mass-transport-runtime`, `xa-mass-engine`, `xa-mass-transport-websocket`, `xa-mass-sdk-api`, `xa-mass-sdk`, `xa-mass-testing`, and `xa-mass-server`
- Core acceptance modules: `xa-mass-testing` for `perf` and SDK transport probes, `xa-mass-engine` for `concurrency`, `xa-mass-server` for Boot-shell E2E

## 6. Current Contract Summary

Task and payload summary:

- task creation has one HTTP route: `POST /status/api/tasks`
- `project` and `userId` are required business bindings on create
- `inputs` is the only supported create shape for work-item materialization
- `workloadClass` is an explicit create-time field; it defaults to `BULK` when omitted
- `PUT /status/api/tasks/{taskId}` is metadata-only and only valid while `NEW` or `BLOCKED`
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item truth stays on `TaskMsg.input/output`; `TaskMsgAttempt` is the attempt-level audit snapshot
- `Task.intakeStatus` is the append-window truth; `openEnded` is only the create/read projection
- engine runtime policy normalization goes through `TaskRuntimeProfile`; enqueue backpressure, assignment, ready-work claim, and trace consume the resolved profile, while assignment retry delay plus runtime retry visibility delay are unified under engine-internal `TaskRuntimeRetryPolicy` instead of reinterpreting `sharedConfig`
- create-time `defaultMsgMaxRetryCount` seeds runtime retry budget inside `TaskWorkRuntime`; retry-scheduled vs retry-exhausted branching now follows runtime result application instead of re-reading persisted `TaskMsg.maxRetryCount`
- delayed runtime retry wakeups are task-level dispatch signals; the engine coalesces them per task instead of spawning one sleeping redispatch wakeup per retrying `TaskMsg`
- public create/update/read contracts do not define a dedicated routing-code field
- engine-provided `TaskMsg` reads remain bounded compatibility helpers; production-scale detail should flow through structured trace or downstream audit storage
- exact HTTP fields and examples live in [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

Current lifecycle summary:

Verified mainline lifecycle:

```text
NEW --approve--> READY --pause--> PAUSED --resume--> READY
 |                  |                                     |
 +--reject-------> BLOCKED --approve--------------------> +
 |                                                        |
 +--cancel/terminate-----------------------------------> TERMINAL

READY --assign--> RUNNING --all task messages final--> TERMINAL
```

Important current rules:

- task completion is driven by persisted `TaskMsg` finality, not only by the visible task status; paused tasks may still close to `TERMINAL`
- no-match assignment is retryable backlog, not terminal dequeue; assignment must not dispatch if the task leaves `READY` during the matching window
- late callbacks must not mutate a task already closed to `TERMINAL`
- `Task.terminalReason` is required to interpret why a task ended
- `BLOCKED` has two distinct intents that must stay separate at the API layer:
  - review rejection uses `rejectTask` for `NEW -> BLOCKED`
  - runtime/manual blocking uses `blockTask` for `READY/RUNNING -> BLOCKED`

## 7. WorkerContext And Matching Baseline

- `WorkerContextStatus` is domain-neutral: `IDLE`, `RESERVED`, `OCCUPIED`, `BLOCKED`, `INVALID`
- `WorkerContext.project` is the first-class project/resource binding for account-like contexts; do not hide project ownership only inside attributes
- `WorkerMatchContext` is the canonical rule-evaluation shape for matching
- `WorkerContext` is optional in the active platform model: workers without one can still run tasks that do not require worker-context-specific routing
- `Worker.status` is the single online truth
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`

## 8. Entry Files

- startup/runtime:
  - `xa-mass-server/src/main/java/com/xa/mass/mock/XaMassServerApplication.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- lifecycle/API:
  - `xa-mass-server/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
  - `xa-mass-base/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- payload/matching:
  - `xa-mass-base/src/main/java/com/xa/mass/base/model/Task.java`
  - `xa-mass-base/src/main/java/com/xa/mass/base/model/TaskMsg.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

## 9. Guardrails

Use these positive defaults:

- start from the real entrypoint and current call sites
- check the root `pom.xml` before treating a top-level directory as active mainline code
- verify API docs against controller DTOs and integration tests before changing request or response contracts
- prefer transport-neutral contracts for new cross-adapter boundaries
- treat documented capabilities as unverified until code, tests, or runtime behavior prove they are live
- consult [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md) before extending compatibility or legacy seams
- add or update regression coverage before changing behavior
- update the short state-machine, trace, and E2E baselines when lifecycle semantics change
- sync active docs after verified behavior changes
