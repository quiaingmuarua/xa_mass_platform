# xa-mass-engine

Status: current engine owner README.

## Role

- mainline business logic
- task lifecycle and progress tracking
- worker assignment and rule management
- core library surface for state-machine correctness and pluggable matching behavior

## Current Status

- this is the active production path
- active production code lives under `src/main/java/com/xa/mass/engine`
- historical `v2` / archive engine generations are not part of the current repository snapshot
- mainline regression work should target current engine tests, not historical notes or removed archive tests

## Start Here

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/WorkerManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

## Boundaries

- do not reconstruct removed `v2` / archive code as current regression
- do not assume scheduler stubs represent the current runtime path for `READY -> RUNNING`
- shared runtime queue/lease/counter contracts now live outside engine in `../platform_infra/mass-runtime-api`; the current in-memory implementation lives in `../platform_infra/mass-runtime-memory`
- `TaskManager` now requires an injected `TaskWorkRuntime`; default in-memory runtime assembly belongs to sdk/server bootstrap, not engine constructors
- prefer extending assignment through engine strategy interfaces instead of hard-coding API or demo-layer behavior
- runtime listeners, watchdogs, startup recovery wiring, and transport-ingest glue should depend on narrow engine ports such as
  `TaskResultIngestFacade`, `TaskAssignmentRuntimePort`, `TaskRuntimeMaintenancePort`,
  `TaskRuntimeRecoveryPort`, and `TaskEventListenerRegistrar`
  instead of taking the full `TaskManager` facade by default
- transport/runtime result ingress should be wired through a dedicated `TaskResultIngestFacade`
  adapter, not by treating `TaskManager` itself as the transport-facing contract
- assignment-side runtime ports should expose claim/write operations directly; do not leak the full
  `TaskWorkRuntime` object into listeners just to claim ready work
- core runtime services such as lifecycle transitions and delayed redispatch request handling should also prefer package-local
  narrow ports/adapters instead of reaching through `TaskManager` for storage, scheduler, runtime, and event operations
- treat `validateTaskState(...)` as a bounded runtime validation tool; deep `TaskMsg` projection audits belong on the explicit engine-side audit path, not on hot-path runtime decisions
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../platform_infra/README.md`](../platform_infra/README.md)
  - [`TASK_EXECUTION_FLOW.md`](./TASK_EXECUTION_FLOW.md)
  - [`TASK_RUNTIME_PROFILE_DESIGN.md`](./TASK_RUNTIME_PROFILE_DESIGN.md) for engine-owned design/refactor notes only
  - [`POLICY_INTERACTION_BASELINE.md`](./POLICY_INTERACTION_BASELINE.md)
  - [`STORAGE_BASELINE.md`](STORAGE_BASELINE.md)

## Concurrency And Refill Risks

Use this module README, not `doc/`, when the risk is engine-local race or hot-path
behavior.

| Risk | Main code paths | Start tests |
| --- | --- | --- |
| callback result race | `TaskManager.handleTaskMessageResult(...)`, `TaskResultService` | `TaskConcurrencyAcceptanceTest`, `TaskApiCallbackReplayIntegrationTest` |
| watchdog expiry vs late result | `LeaseExpireWatchdog`, `TaskResultService`, `TaskStateResolver` | `TaskConcurrencyAcceptanceTest`, `TaskManagerLifecycleTest` |
| worker release and redispatch | `TaskResourceReleaseListener`, `TaskResultService` | `TaskResourceReleaseListenerTest`, `TaskApiSingleWorkerReuseIntegrationTest`, `TaskApiMultiRoundDispatchIntegrationTest` |
| assignment refill and batching | `TaskAssignWorker`, `TaskWorkerAssignListener`, `SimpleTaskMsgAssignListener` | `TaskApiMultiRoundDispatchIntegrationTest`, `TaskApiMinimumWorkerGateIntegrationTest`, `TaskApiDelayedWorkerAvailabilityIntegrationTest` |

Commands:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskConcurrencyAcceptanceTest,TaskManagerLifecycleTest,TaskResourceReleaseListenerTest test
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiCallbackReplayIntegrationTest,TaskApiSingleWorkerReuseIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiMinimumWorkerGateIntegrationTest,TaskApiDelayedWorkerAvailabilityIntegrationTest test
```

Engine-side invariants to keep:

- one active attempt resolves one callback winner
- expiry and late result must land in one allowed stable state
- release must not unlock a still-busy worker or sibling context
- refill must not duplicate logical `TaskMsg` rows
