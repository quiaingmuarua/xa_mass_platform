# Assignment Refill And Batching

## 1. Scope

This topic covers refill semantics after the first dispatch round:

- `batchSize`
- delayed worker availability
- minimum worker gate
- multi-round completion with one or more workers

The core question is:

- does the runtime keep the logical task and message state consistent while refill pressure changes over time

## 2. Core Code Paths

- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskAssignWorker.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskWorkerAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskResourceReleaseListener.java`

## 3. Primary Acceptance Coverage

Boot-shell E2E:

- [TaskApiMultiRoundDispatchIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMultiRoundDispatchIntegrationTest.java)
- [TaskApiMinimumWorkerGateIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMinimumWorkerGateIntegrationTest.java)
- [TaskApiDelayedWorkerAvailabilityIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiDelayedWorkerAvailabilityIntegrationTest.java)

Perf relevance:

- refill-heavy paths are also hot paths for storage scans, release checks, and dispatch queue pressure
- pair this topic with [perf_load_model.md](./perf_load_model.md) when the change affects counters, scans, or dispatch pressure

Command:

```bash
./mvnw -pl xa-mass-dev-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiMultiRoundDispatchIntegrationTest,TaskApiMinimumWorkerGateIntegrationTest,TaskApiDelayedWorkerAvailabilityIntegrationTest test
```

## 4. Invariants

Must stay true:

- `READY` tasks without a current match are retried, not orphaned
- `minRequiredWorkerCount` must really gate `READY -> RUNNING`
- refill must not dispatch a new round while the worker is still occupied by the current round
- retry and refill must not duplicate logical `TaskMsg` rows
