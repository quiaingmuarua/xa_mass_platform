# xa-mass-engine

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
- prefer extending assignment through engine strategy interfaces instead of hard-coding API or demo-layer behavior
- treat `validateTaskState(...)` as an audit/acceptance tool; keep it bounded and do not route hot-path runtime decisions through full task-message scans
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`TASK_EXECUTION_FLOW.md`](./TASK_EXECUTION_FLOW.md)
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
./mvnw -pl xa-mass-dev-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiCallbackReplayIntegrationTest,TaskApiSingleWorkerReuseIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiMinimumWorkerGateIntegrationTest,TaskApiDelayedWorkerAvailabilityIntegrationTest test
```

Engine-side invariants to keep:

- one active attempt resolves one callback winner
- expiry and late result must land in one allowed stable state
- release must not unlock a still-busy worker or sibling context
- refill must not duplicate logical `TaskMsg` rows
