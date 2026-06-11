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
- the kernel problem is: schedule task work to heterogeneous, stateful
  executors through task/group dispatch intent, track per-item result, and
  converge task-level state
- core primitives: `Task + Worker + Scheduling Plane`
- kernel truth is explicitly split across:
  - `Task.contract`
  - `Task.intakeStatus`
  - `TaskWorkRuntime` for ready/delayed/lease/counter truth
- result-side read truth in `TaskResultRuntime` for stable-final result rows,
  repair staging, and result-side idempotency barriers
- result convergence is runtime-first, but the lifecycle owner split must be
  verified from `TASK_LIFECYCLE_BASELINE.md` plus current engine/runtime code
- runtime seams are transport-neutral: task dispatch, result ingest, and system events
- runtime entry is SDK-first; server HTTP/UI surfaces provide the backend
  product/API host, control-console support, and validation surface, but they do
  not redefine kernel ownership
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
- detailed SDK/integrations guardrails live in
  [SDK_INTEGRATIONS_BOUNDARY_GUARD.md](./SDK_INTEGRATIONS_BOUNDARY_GUARD.md);
  read it before changing `sdk/`, `integrations/`, public Controller DTOs,
  external worker contracts, or server startup registration behavior

Current owner vocabulary:

- `Task` is the task/control aggregate truth
- `Task.contract` is the current public/runtime preset input: `SESSION | BATCH`
- `Task.intakeStatus` is the intake-window truth: `OPEN | SEALED`
- Scheduling Plane ownership is split into three first-class categories:
  `TaskSchedulingPolicy` for competition admission/cadence/priority/fairness/
  budget, `WorkerSchedulingPolicy` for worker resource-universe selection and
  pool constraints, and `RuntimeWorkerSelection` for concrete worker choice
  from live evidence and admission state. This is an architectural boundary,
  not a fully implemented current module.
- Canonical shorthand:
  - `TaskSchedulingPolicy` = how work enters dispatch competition
  - `WorkerSchedulingPolicy` = which worker universe work competes in
  - `RuntimeWorkerSelection` = which currently eligible workers are selected
    inside that universe
- `Worker` is execution identity plus group/node membership and declared
  scheduling/resource facts; worker rows do not own project/event capability
  truth
- `Scheduling Plane` decides when task work may enter competition, dispatch,
  retry, pause, resume, or close; which worker universe it may compete in; and
  which concrete worker receives it. Current scheduling policy remains
  distributed across task runtime profile, group selectors, assignment policy,
  matching rule sets, runtime backpressure, and admission behavior.
- Current engine-facing scheduling value contracts already exist:
  `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy`. They are resolved views and execution
  inputs, not storage truth or public policy products.
- `Matching` is current worker-selection mechanism vocabulary, not a top-level
  policy owner. Worker scheduling policy resolves the resource universe, and
  RuntimeWorkerSelection chooses a concrete worker inside the selected group
  from reachability, runtime load/capacity, admission, draining, lease, and
  explicit scheduling evidence.
- `TaskDispatchIntent` is the task-level dispatch intent: project,
  `workerGroupId(s)` or selector, `routingCode`, route attributes, optional
  `targetWorkerId`, and optional constrained target worker attributes
- `Item` is the executable work unit: `eventCode` plus input or `payloadRef`;
  it does not own worker-selection policy
- `eventCode` is handler/capability identity. It validates against the selected
  WorkerGroup event binding and tells the worker which local handler to run; it
  is not a worker selector and must not trigger all-worker capability scans
- Task item dispatch reaches transport only after runtime claim/lease and
  concrete worker selection. Engine binds the selected worker into
  `TaskDispatchBinding.workerId`; transport carries that value as
  `selectedWorkerId`, a delivery constraint, not a scheduling or lifecycle
  decision.
- Transport delivery identifiers are intentionally split:
  `selectedWorkerId` is assigned-worker correctness, `deliveryQueueKey` is a
  queue/storage partition, `routeKey` is opaque connection/domain metadata, and
  `connectionId` or session token is the transport lease handle. Do not collapse
  these into worker identity or routeKey minting rules.
- `TaskWorkRuntime` is the hot-path owner for ready work, lease, retry, expiry,
  and backpressure truth
- result apply and visible final-result ownership are runtime-first concerns;
  verify `TaskResultService`, `TaskWorkRuntime.applyResultWithContext(...)`,
  `TaskResultRuntime`, and `TASK_LIFECYCLE_BASELINE.md` together before
  documenting the split more narrowly
- `xa-mass-trace` is the current operator-facing read path for canonical trace
  artifacts; it does not own a second event schema or lifecycle truth
- current bounded review/export materialization is server-local and lagging;
  legacy message-model naming is intentionally not part of the active
  public/kernel vocabulary
- `WorkerContext` is retired historical compatibility vocabulary, not active
  SDK/server/storage/trace truth. It is not an engine scheduling truth and must
  not be reintroduced as the worker capability or resource-lifecycle owner.

Platform scheduling abstraction boundary:

| Layer | Role | Current status |
| --- | --- | --- |
| `TaskSchedulingPolicyExecution` | task-side competition admission, cadence, priority, quota/fairness, and task-level budget execution | Scheduling Plane owner; current engine consumes `ResolvedTaskSchedulingPolicy` for selected task-side inputs; broader target contract not fully implemented |
| `WorkerSchedulingPolicyResolution` | worker-side resource-universe, group selector, route, target override, and worker-pool constraint resolution | Scheduling Plane owner; current engine consumes `ResolvedWorkerSchedulingPolicy` for selected worker-universe inputs; broader target contract not fully implemented |
| `RuntimeWorkerSelection` | concrete worker choice inside the selected group from online/presence/load/admission/draining/lease evidence | Scheduling Plane owner; current engine + worker-runtime matching/admission path |

Scheduling Plane inputs, resolved views, and constraints:

| Layer | Role | Current status |
| --- | --- | --- |
| `SchedulingPolicyCatalog` | reusable task scheduling policy definitions and worker scheduling policy definitions | target owner boundary, not fully implemented |
| `ProjectSchedulingBinding` | project/workload allowed/default task policies, allowed/default worker policies, per-policy config, and quota/fairness scope | target owner boundary, not fully implemented |
| `TaskDispatchIntent` | task-level dispatch target and constraints: project, WorkerGroup selector, route, optional target worker, optional target attributes, and future selected/inherited policy refs | current engine value contract derived from task fields and shared config; selected/inherited policy refs remain target-only |
| `ResolvedTaskSchedulingPolicy` | resolved task-side scheduling input view: workload class, dispatch cadence, resource mode, idle-close, result-finality, dispatch lane/priority, claim/retry/backpressure inputs | current engine value contract consumed by selected task-side execution owners; not storage truth |
| `ResolvedWorkerSchedulingPolicy` | resolved worker-side scheduling input view: WorkerGroup selector, adapter node, candidate buckets, target worker, and target attributes | current engine value contract consumed before runtime worker selection; not storage truth |
| `WorkerGroupCapability` | external group-level capability truth: project bindings, event bindings, group defaults, and capacity hints | current worker-runtime resource truth; constrains worker scheduling resolution |
| `Item` | executable work unit: `eventCode` plus input or `payloadRef` | current runtime work-item boundary; not worker-selection policy |

The intended boundary is:

```text
Scheduling Plane
  TaskSchedulingPolicyExecution
  WorkerSchedulingPolicyResolution
  RuntimeWorkerSelection

Target policy products
  SchedulingPolicyCatalog / ProjectSchedulingBinding

Resolved engine-facing values
  TaskDispatchIntent
  ResolvedTaskSchedulingPolicy
  ResolvedWorkerSchedulingPolicy

External constraints
  WorkerGroupCapability
  Item eventCode + payload
```

Do not invert that flow by letting item payload, `eventCode`, worker row
attributes, SDK snapshots, or rule DSL become the owner of scheduling policy.

Example boundary:

```text
TaskSchedulingPolicy:
  BULK_THROUGHPUT cadence, refill timing, task-side worker budget

WorkerSchedulingPolicy:
  crawler worker groups, region route, target constraints, rule-set reference

RuntimeWorkerSelection:
  online workers only, reject locked/draining/capacity-full workers, rank,
  reserve, and dispatch
```

Stable kernel slots:

- worker: `Worker`
- worker scheduling view: worker registration, WorkerGroup event-binding
  evidence, scheduling attributes, reachability, and runtime load/capacity facts
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
- task contract is a preset input; resolved task scheduling policy owns lifecycle-adjacent terminal, dispatch cadence, retry/finality, resource-mode, and backpressure inputs
- task runtime scheduling semantics resolve through `ResolvedTaskSchedulingPolicy`, not directly from free-form `sharedConfig`
- task orchestration and worker selection belong at task or task-slice level; do not reintroduce per-message rule matching on the hot path
- message/attempt read surfaces are bounded compatibility or audit helpers, not
  the production business-detail query model

## 4. Mainline Reality

- current mainline execution path:
  - `Task shell -> item append -> runtime enqueue -> scheduling eligibility -> worker selection and assignment -> transport dispatch -> result convergence -> task state`
- real Boot entry: `xa-mass-server`
- embedded runtime composition: `xa-mass-embedded-sdk`
- Java baseline: JDK 21 with virtual threads routed through explicit runtime abstractions
- current runtime/storage split:
  - `platform_infra/mass-runtime-api` owns queue/lease/counter contracts plus
    the active result-runtime boundary
  - `platform_infra/mass-runtime-memory` owns the embedded default runtime
    implementation and focused runtime contract tests
  - `platform_infra/mass-runtime-redis` owns the Redis-backed runtime used by
    the current local distributed verification path and Redis parity/restart
    proof
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
    `TASK_LIFECYCLE_BASELINE.md`
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
6. `doc/TASK_LIFECYCLE_BASELINE.md`
7. `xa-mass-trace/README.md`
8. `doc/TRACE_CONTRACT.md`

Read them to verify three things quickly:

- runtime admission happens through `TaskWorkRuntime`, not through a
  task-message CRUD mainline
- callback/expiry/result convergence is runtime-first; verify the split between
  runtime apply truth, stable-final result rows, and server-local review
  materialization from the task lifecycle baseline and code
- bounded message/attempt review reads are server/API materialization surfaces
  and are not the default engine query model
- canonical trace diagnosis should start from `xa-mass-trace` over sink output,
  not from MDC string logs or ad hoc projection reads

## 5. Current Contract Summary

- task shell creation route: `POST /api/v1/tasks`
- `project` and `userId` are required business bindings on create
- shell create runs in single-tenant mode with tenant-aware semantics; current
  default tenant is `default`
- `Task.project` is the task-owned business container; task-level event truth
  is not the scheduling candidate source
- `taskName` is a server-derived display field, not a client-provided shell
  truth field
- work-item materialization is explicit through `POST /api/v1/tasks/{taskId}/items`
- `executionSpec` is the task-level execution policy envelope; current defaults
  remain `contract=BATCH`, `profile=STANDARD`, `workloadClass=BULK`,
  `batchSize=1`, `maxRuntimeSeconds=0`
- ingress form such as inline create, repeated append, file import, or
  `sourceRef` metadata does not define task policy; engine lifecycle-adjacent
  dispatch, retry/finality, and terminal semantics consume resolved task
  scheduling policy
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item runtime truth stays on the runtime ingress item and dispatch/result
  flow; server-local review materialization may retain payload summary or
  `payloadRef` for operator views
- `Task.intakeStatus` is the append-window truth; the legacy boolean intake projection has been removed from the task model
- public contracts do not define a dedicated routing-code field
- message/attempt reads are server-local review/export helpers, not engine
  runtime truth

Lifecycle and trace detail live in:

- [TASK_LIFECYCLE_BASELINE.md](./TASK_LIFECYCLE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [TESTING_INDEX.md](./TESTING_INDEX.md)

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
- task scheduling policy decides competition admission/cadence/priority/
  fairness/budget, worker scheduling policy decides resource-universe and pool
  constraints, project/workload binding decides allowed/default policies and
  scoped config, task decides dispatch intent and selected/inherited policies,
  WorkerGroup decides capability boundary, RuntimeWorkerSelection chooses a
  concrete worker from live evidence/admission, and item only decides which
  event handler is invoked. Do not turn item payload, `eventCode`, or worker
  rows into policy owners.
- `WorkerMatchContext` plus rule evaluation is the current default rule-backed
  eligibility/scoring component inside worker selection. Matching inputs must
  stay explicit scheduling evidence and must not become replacement worker
  scheduling policy or runtime worker selection ownership.
- UI pages, mock runtime, and demo APIs must not redefine the kernel
- do not add full-table, full-task, or full-attempt scans to hot paths
- new or changed policy seams must keep ownership explicit across matching, assignment, attempt, release, refill, intake, control, and terminal decisions

## 7. Read Next

- repo handoff: [../AGENTS.md](../AGENTS.md)
- full doc map: [README.md](./README.md)
- engine owner entry: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- transport owner entry: [../transport/AGENTS.md](../transport/AGENTS.md)
