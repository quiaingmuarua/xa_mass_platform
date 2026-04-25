# Callback Result Race

## 1. Scope

This topic covers concurrent or repeated result callbacks for the same logical `TaskMsg`.

The core question is:

- does the runtime converge to one allowed stable outcome without double-closing the attempt, double-publishing logical finality, or double-closing the task

## 2. Core Code Paths

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/storage/InMemoryTaskStorage.java`

Important behaviors:

- callback handling is serialized by `TaskManager.withTaskLock(...)`
- `TaskResultService` accepts exactly one active attempt as callback truth
- duplicate callbacks after finality should be ignored, not re-applied

## 3. Allowed Stable Outcomes

Allowed outcomes:

- one callback wins and persists `SUCCESS`
- one callback wins and persists a retry path or failure path when that is the valid business outcome
- later duplicate callbacks are ignored or rejected according to state

Forbidden outcomes:

- two attempt closures for one attempt
- two logical-final publications for one logical message finalization
- two terminal closures for one task closure
- final message projection and attempt history disagreeing about the winner

## 4. Primary Acceptance Coverage

Deterministic concurrency acceptance:

- [TaskConcurrencyAcceptanceTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-engine/src/test/java/com/xa/mass/engine/TaskConcurrencyAcceptanceTest.java:43)

Boot-shell replay coverage:

- [TaskApiCallbackReplayIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/results/TaskApiCallbackReplayIntegrationTest.java)

Command:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskConcurrencyAcceptanceTest test
./mvnw -pl xa-mass-dev-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiCallbackReplayIntegrationTest test
```

## 5. Trace Expectations

Relevant trace contract:

- `CALLBACK_ACCEPTED`
- `CALLBACK_IGNORED_DUPLICATE`
- `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`
- `TASK_MSG_ATTEMPT_CLOSED`
- `TASK_MSG_LOGICALLY_FINAL`
- `TASK_TERMINAL_CLOSED`

Use [../../TRACE_CONTRACT.md](../../TRACE_CONTRACT.md) when changing semantics here.
