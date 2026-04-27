# XA Mass Platform Agent Baseline

This document keeps only the stable baseline facts that coding agents need first:

- platform definition and architectural guardrails
- current module and boundary truth
- current lifecycle and payload summary
- what to trust when docs and runtime disagree

It intentionally does not duplicate run commands, detailed endpoint inventories, or full protocol examples.

For those, use:

- [../AGENTS.md](../AGENTS.md)
- [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
- [../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)

Design-only reference:

- [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md)

## 1. Using This File

Use the canonical trust order in [../AGENTS.md](../AGENTS.md).
This file is the stable project baseline, not a higher-priority source than code, verified runtime behavior, or narrower owner docs such as state machine, trace, E2E, and WebSocket adapter boundary baselines.

Working rule:

- verify old READMEs and architecture notes against current code before using them
- update active docs after confirming runtime truth
- keep mainline docs current; delete stale history unless it explains a live operational constraint

## 2. Platform Definition

- The project is a general distributed task scheduling platform.
- The core product problem is not "send work over one transport"; it is "match structured work items to heterogeneous, stateful executors, track each item result, and converge task-level state".
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The kernel value is the combination of `stateful worker + capability/routing match + per-item result tracking + task-level convergence`.
- For queue-first high-volume compression work, use [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md). Do not treat that design doc as current runtime truth.
- Adapter vocabulary note: current code still uses `Worker`, `WorkerContext`, and some WebSocket-named types for today's adapter surfaces. Read those names literally inside their current scope, but keep new cross-adapter boundaries transport-neutral.
- The platform is scenario-agnostic. It owns dispatch, result write-back, and task convergence rather than business payload meaning.
- The long-term stable kernel is `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- The current runtime model is transport-agnostic: task dispatch, result ingest, and worker system events are explicit seams rather than one transport shape.
- Observability belongs in logs, traces, counters, and bounded diagnostics. Do not push scan-heavy operational introspection back into hot-path domain models.
- Favor idempotent dispatch, result, and retry-side operations so duplicate delivery or reconnect churn stays manageable.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- The runtime entry is library/SDK-first. Demo runtime surfaces validate the kernel; they do not redefine it.

## 3. Platform Model

| Abstract concept | Concrete type | Notes |
| --- | --- | --- |
| Worker | `Worker` | Current worker adapter. Examples include phone, crawler, LLM agent, and IM bot. |
| Worker context | `WorkerContext` | Optional capability or credential context. Stateless workers do not require one. |
| Work item | `TaskMsg` | Mainline message unit with `input: Map<String,Object>` and `output: Map<String,Object>`. |
| Shared config | `Task.sharedConfig` | Platform-level dispatch config merged into each downstream dispatch payload. |

Interpretation rules:

- the abstract concepts are the stable architecture boundary
- new worker forms should extend these abstract slots instead of shrinking the platform back into `worker/workerContext` vocabulary
- mock/runtime loading does not auto-create fallback worker contexts; a worker with no explicit `workerContexts` stays stateless
- SDK-first worker resource creation is the preferred path: use `WorkerRegistration` / `WorkerContextRegistration` through `MassSdkApplication.registerWorker(...)` and `registerWorkerContext(...)`; registration does not imply online state
- SDK project/event metadata is registered through `MassSdkApplication.registerProject(...)` and `registerEventDefinition(...)`; enabled project registration also extends the core runtime project registry used by task and worker-context validation
- `ResourceOperations` is the SDK project/event control-plane interface
- `EventDefinition.code` is the globally unique capability identity for SDK/runtime dispatch, worker capability declarations, and permission checks
- `EventDefinition.projectCodes` is scope metadata only; it constrains where an event may be invoked but is not part of the event identity
- SDK submitter registration is currently a minimal in-memory credential binding for task submission identity; do not treat it as a complete user/security subsystem
- SDK submitter list/get operations expose submitter metadata only; raw credentials are accepted on registration and consumed by authentication, not returned as resource read models

## 4. Model Boundaries

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
- manual worker debug stays task-backed through `POST /status/api/tasks` plus `Task.sharedConfig.targetWorkerId`

## 5. Architectural Guardrails

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy.
- Prefer transport-neutral names and contracts for new cross-adapter boundaries.
- `WorkerContext` is optional worker context. Not every worker model requires one.
- The active API is explicitly `0..n`: do not reintroduce single-context helper APIs keyed only by `workerId`; use `getWorkerContexts(...)` or `getWorkerContextById(...)`.
- `WorkerContext.workerId` is the single owner truth; attachment APIs should accept the `WorkerContext` object itself rather than duplicating the owner `workerId` as a second parameter.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress back to single-purpose top-level fields such as `textContent`.
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate. Do not push project/user identity back into `sharedConfig`, `TaskMsg.input`, or attribute bags.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals, not from `workerGroupId`.
- Worker matching truth is `RuleDefinition.content` evaluated by QLExpress over `WorkerMatchContext`.
- The typed JSON DSL path goes through `JsonDslParser -> JsonDslDefinition -> JsonDslProcessorEngine`.
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels for matching and diagnostics only. They are not lifecycle, lock, or online truth.
- Prefer SDK registration models for new resource scenarios; low-level core-model mutation APIs are not the default path.
- UI pages, mock runtime, and demo APIs must not redefine the platform kernel.
- Manual worker debug now enters through normal task creation with explicit worker targeting in `Task.sharedConfig`; do not reintroduce a direct worker-control side-channel.
- Do not add full-table, full-task, or full-attempt scans to hot paths for observability convenience; use indexed lookups, traces, and counters.
- new or changed policy seams must keep ownership explicit across matching, attempt, release, refill, intake, control, and terminal decisions; use [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md) before extending those paths

## 6. Mainline Reality

- The real Spring Boot entry is `xa-mass-dev-app`.
- Java baseline is JDK 21. The root reactor compiles with `maven.compiler.release=21`, Java worker samples use release 21, and CI is expected to run on Temurin 21.
- Java 21 virtual threads are the runtime baseline for blocking concurrency boundaries when routed through explicit runtime abstractions. They reduce concurrency complexity, but they must not redefine engine lifecycle correctness, worker lock ownership, or `TaskMsgAttempt` state semantics.
- Runtime executor boundary: `com.xa.mass.base.runtime.RuntimeTaskExecutor`
- SDK control-plane event dispatch is synchronous by default; bounded virtual-thread isolation is optional in SDK embedding
- Embedded runtime composition lives in `xa-mass-sdk`; `xa-mass-dev-app` consumes it and adds the current HTTP/control-console shell
- Transport module split:
  - `transport/transport_api`: transport-neutral SPI
  - `transport/transport_runtime`: shared transport runtime assembly
  - `transport/polling-adapter`: pull/polling adapter
  - `transport/websocket-adapter`: current WebSocket adapter
  - `transport/socket-adapter`: current socket adapter
- Reactor truth comes from the root `pom.xml`; current active modules are `xa-mass-web`, `xa-mass-core`, `xa-mass-transport-api`, `xa-mass-transport-polling`, `xa-mass-transport-runtime`, `xa-mass-engine`, `xa-mass-transport-websocket`, `xa-mass-sdk-api`, `xa-mass-sdk`, `xa-mass-testing`, and `xa-mass-dev-app`
- WebSocket adapter frame classification is a protocol compatibility seam only; it is not business or control capability identity
- Core acceptance modules: `xa-mass-testing` for `perf` and SDK transport probes, `xa-mass-engine` for `concurrency`, `xa-mass-dev-app` for Boot-shell E2E

Use [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) for startup/runtime commands and diagnostics, [./TESTING_BASELINE.md](./TESTING_BASELINE.md) for lane ownership, and [../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) before changing `xa-mass-transport-websocket` or `xa-mass-transport-api`.

## 7. Current Contract Summary

Task and payload summary:

- task creation has one HTTP route: `POST /status/api/tasks`
- `project` and `userId` are required business bindings on create
- `inputs` is the only supported create shape for work-item materialization
- `PUT /status/api/tasks/{taskId}` is metadata-only and only valid while `NEW` or `BLOCKED`
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item truth stays on `TaskMsg.input/output`; `TaskMsgAttempt` is the attempt-level audit snapshot
- `Task.intakeStatus` is the append-window truth; `openEnded` is only the create/read projection
- public create/update/read contracts do not define a dedicated routing-code field
- worker runtime capability truth is `supportedEventCodes`; `supportedProjects` is only a coarse filter hint
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

## 8. WorkerContext And Matching Baseline

- `WorkerContextStatus` is domain-neutral: `IDLE`, `RESERVED`, `OCCUPIED`, `BLOCKED`, `INVALID`
- `WorkerContext.project` is the first-class project/resource binding for account-like contexts; do not hide project ownership only inside attributes
- `WorkerMatchContext` is the canonical rule-evaluation shape; match logic should prefer explicit signals such as `workerAttributes`, `workerContextAttributes`, `workerContextProject`, `workerContextProjectMatchesTaskProject`, `hasWorkerContext`, and `taskHasRoutingRequirement`
- `RuleDefinition.content` is the canonical rule expression; `expression` and `desc` remain compatibility aliases, not separate rule truths
- `isWorkerContextAvailable` means truly free for new assignment; `isWorkerContextUsable` is only the broader diagnostic signal
- new matching rules should prefer explicit worker-context signals such as `workerContextProject`, `workerContextRoutingTags`, and `workerContextAttributes`; do not reintroduce a frontend or API-level routing-code model field
- `WorkerContext` is optional in the active platform model: workers without one can still run tasks that do not require worker-context-specific routing
- `Worker.status` is the single online truth
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`

## 9. Entry Files

- startup/runtime:
  - `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- lifecycle/API:
  - `xa-mass-web/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
  - `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- payload/matching:
  - `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
  - `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

## 10. Guardrails

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
