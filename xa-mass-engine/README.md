# xa-mass-engine

Status: current engine owner README.

This module owns the kernel-side lifecycle, assignment, result handling,
matching, and terminal convergence logic. It is the engine truth for
`Task / TaskMsg / TaskMsgAttempt` orchestration semantics, but it is not the
owner of queue/runtime implementation details or storage implementations.

## Role

- task lifecycle transitions and terminal convergence
- task-level worker matching and assignment orchestration
- result ingest application and retry/finality decisions
- engine-local policy ownership across matching, assignment, attempt, release,
  refill, intake, and terminal decisions

## Mainline Entry Points

Start with these classes before changing behavior:

- `src/main/java/com/xa/mass/engine/TaskManager.java`
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

## Runtime Mainline

Verified engine mainline in one pass:

1. task becomes `READY` through command/lifecycle flow
2. engine emits a task-level dispatch request
3. assignment resolves `TaskRuntimeProfile` from `Task.workloadClass`
4. assignment signal enters the `INTERACTIVE` or `BULK` lane skeleton
5. worker matching runs once at the task level through `TaskWorkerMatchingStrategy`
6. `TaskWorkRuntime` claims ready logical work and owns active lease truth
7. transport dispatch delivers claimed work
8. result ingest applies against the active runtime lease first, then repairs or
   updates bounded `TaskMsg` / `TaskMsgAttempt` compatibility projection state
9. task terminal policy closes the task once work is stably final

Keep these facts fixed unless the owning baselines change:

- matching is task-level orchestration; do not fall back to per-`TaskMsg`
  matching on the hot path
- `Task.workloadClass` is the explicit workload input; scheduling semantics must
  not drift back into free-form `sharedConfig`
- `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry, and
  queue/backpressure truth
- `TaskMsg` and `TaskMsgAttempt` remain bounded compatibility/audit projections,
  not the hot-path runtime owner
- engine-provided message reads are compatibility helpers, not the future
  business-detail query model

## Storage And Boundary Rules

- storage contracts live in `../platform_infra/mass-storage-api`
- queue/runtime contracts live in `../platform_infra/mass-runtime-api`
- current default in-memory runtime implementation lives in
  `../platform_infra/mass-runtime-memory`
- engine constructors should depend on contracts only, never on storage/runtime
  implementations
- SDK/server bootstrap owns default wiring

Current repo-level mainline:

- shell/admin mutation flows use `TaskCommandService`
- bounded inspection flows use `TaskQueryService`
- transport/runtime result ingress uses `TaskResultIngestFacade`
- listeners, watchdogs, and startup recovery should depend on narrow ports, not
  on `TaskManager` plus reach-through getters

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

Important boundaries:

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

## Engine Doc Map

Keep the engine doc set small and purpose-specific:

- [`POLICY_INTERACTION_BASELINE.md`](./POLICY_INTERACTION_BASELINE.md):
  current policy ownership and precedence
- [`STORAGE_BASELINE.md`](./STORAGE_BASELINE.md):
  current engine-facing storage/runtime boundary
- [`TASK_RUNTIME_PROFILE_DESIGN.md`](./TASK_RUNTIME_PROFILE_DESIGN.md):
  design/refactor note for the remaining workload-profile evolution only

Global truth still lives in:

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../doc/STATE_MACHINE_BASELINE.md`](../doc/STATE_MACHINE_BASELINE.md)
- [`../doc/TRACE_CONTRACT.md`](../doc/TRACE_CONTRACT.md)
- [`../doc/TESTING_BASELINE.md`](../doc/TESTING_BASELINE.md)
