# xa-mass-engine

Status: current engine owner README.

This module owns kernel orchestration semantics: lifecycle, matching,
assignment, result handling, and terminal convergence. It does not own runtime
implementation modules or storage implementations.

## Role

- task lifecycle transitions and terminal convergence
- task-level worker matching and assignment orchestration
- result ingest application and retry/finality decisions
- engine-local policy ownership across matching, assignment, attempt, release,
  refill, intake, and terminal decisions

## Start Here

Start with these classes before changing behavior:

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/TaskConcurrencyCoordinator.java`
- `src/main/java/com/xa/mass/engine/TaskRuntimeBridge.java`
- `src/main/java/com/xa/mass/engine/TaskCommandService.java`
- `src/main/java/com/xa/mass/engine/TaskQueryService.java`
- `src/main/java/com/xa/mass/engine/WorkerManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Runtime-facing glue should prefer narrow engine ports and facades such as:

- `TaskResultIngestFacade`
- `TaskAssignmentRuntimePort`
- `TaskRuntimeMaintenancePort`
- `TaskRuntimeRecoveryPort`
- `TaskEventListenerRegistrar`
- `TaskEventService`

Do not default new cross-module callers to the full `TaskManager` facade.
When a seam is transport-neutral and cross-module by nature, prefer a small
shared runtime contract in a neutral module over making transport depend on
engine-internal listener packages.

## Mainline Truth

Keep these facts fixed unless the owning global baselines change:

- `Task.workloadClass` is the explicit workload input; scheduling semantics must
  not drift back into free-form `sharedConfig`
- worker matching is task-level orchestration; do not fall back to per-`TaskMsg`
  matching on the hot path
- `TaskManager` is the engine orchestration entry, not the place to keep raw
  lock bookkeeping or direct runtime-bridge mechanics
- `TaskManager` remains the engine-internal orchestration facade and
  composition root; cross-module callers should not treat it as the default
  engine API
- `TaskConcurrencyCoordinator` owns task/message locking plus coalesced progress
  reconciliation
- `TaskRuntimeBridge` owns engine-side bridging into `TaskWorkRuntime`,
  including enqueue, claim, lease, and discard/apply helpers
- `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry, and
  queue/backpressure truth
- `TaskMsg` and `TaskMsgAttempt` remain bounded compatibility/audit projections,
  not the hot-path runtime owner
- engine-provided message reads are compatibility helpers, not the future
  business-detail query model
- cross-module callers that only need worker registration lookup should depend
  on storage lookup contracts rather than carrying `WorkerManager`
- cross-module callers that only need rule definition/evaluator registration
  should depend on `RuleStorage`; keep `RuleManager` scoped to engine matching
  and rule-evaluation orchestration

Repo-level mainline surfaces:

- shell/admin mutation flows use `TaskCommandService`
- bounded inspection flows use `TaskQueryService`
- explicit projection audit stays on `TaskQueryService` as a diagnostic-only
  path
- transport/runtime result ingress uses `TaskResultIngestFacade`
- dispatch-ready bindings and result-ingest seams used across engine, SDK,
  transport runtime, and tests now live in shared base runtime contracts rather
  than engine-owned package paths
- engine emits dispatch-ready bindings into a neutral handoff/listener seam; it
  must not grow a direct dependency on transport routing/runtime classes
- task-create input consumed by `TaskCommandService` now lives in the neutral
  base model layer; cross-module create flows should not import engine-owned
  DTO packages just to submit tasks
- listeners, watchdogs, and startup recovery should depend on narrow ports, not
  on `TaskManager` plus reach-through getters

Infra ownership:

- storage contracts live in `../platform_infra/mass-storage-api`
- runtime queue/lease contracts live in `../platform_infra/mass-runtime-api`
- transport adapter contracts live outside engine; engine must not take a direct
  dependency on `../transport/transport_api`
- SDK/server bootstrap owns concrete wiring
- primary SDK/server builders should wire `TaskStorage`, `TaskDetailStore`,
  `TaskWorkRuntime`, `WorkerStorage`, and `RuleStorage` rather than
  constructing `TaskManager` / `WorkerManager` in outer modules
- starter assembly should treat `WorkerManager` and `RuleManager` as derived
  helpers over storage contracts, not as parallel config truth carried beside
  `WorkerStorage` / `RuleStorage`

## Rule-Matching Surface

Matching evaluates `WorkerMatchContext` through QLExpress rules.

Current owner types:

- `src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`
- `src/main/java/com/xa/mass/engine/rules/RuleConfig.java`

Current default rule set:

- `basic_worker_check`
- `worker_context_status_check`
- `routing_code_match`
- `worker_capability_check`
- `worker_load_check`

Matching boundaries:

- `Worker.status` and worker lock state are typed truth, not attributes
- `workerAttributes` and `workerContextAttributes` are auxiliary matching labels
  only
- routing is a task-owned hint currently resolved from
  `Task.sharedConfig["routingCode"]`
- once a task requires routing, a missing `WorkerContext` must not satisfy that
  rule by accident

If matching semantics change, update `RuleConfig`, `WorkerMatchContext`, and the
relevant routing/integration coverage together.

## Acceptance Focus

Core acceptance for this module stays:

- `concurrency`: engine-owned correctness under callback/expiry/retry/release
  races
- `perf`: mainly in `xa-mass-testing`, but engine changes must preserve the
  task-level, queue-first runtime shape
- `Boot-shell E2E`: mainly in `xa-mass-server`, used to verify lifecycle and
  workload-class plumbing end to end

Useful starting tests:

- `TaskConcurrencyAcceptanceTest`
- `TaskManagerLifecycleTest`
- `TaskResourceReleaseListenerTest`

## Read Map

Engine-local owner docs:

- [`POLICY_INTERACTION_BASELINE.md`](./POLICY_INTERACTION_BASELINE.md):
  current policy ownership and precedence
- [`STORAGE_BASELINE.md`](./STORAGE_BASELINE.md):
  current engine-facing storage/runtime boundary
- [`TASK_RUNTIME_PROFILE_DESIGN.md`](./TASK_RUNTIME_PROFILE_DESIGN.md):
  design/refactor note for the remaining workload-profile evolution only

Global baselines:

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../doc/STATE_MACHINE_BASELINE.md`](../doc/STATE_MACHINE_BASELINE.md)
- [`../doc/TRACE_CONTRACT.md`](../doc/TRACE_CONTRACT.md)
- [`../doc/TESTING_BASELINE.md`](../doc/TESTING_BASELINE.md)
