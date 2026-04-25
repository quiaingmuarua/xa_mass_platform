# Watchdog Expiry And Late Results

## 1. Scope

This topic covers time-based expiry and callbacks arriving around the same time.

The core question is:

- if the watchdog expires an active attempt while a result callback is arriving, does the runtime still land in one stable persisted state

## 2. Core Code Paths

- `xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/LeaseExpireWatchdog.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskStateResolver.java`

Important behaviors:

- watchdog walks non-final tasks and expires stale active attempts
- expiry must close the concrete attempt and finalize or requeue the logical message according to retry budget
- late callbacks after finality must be ignored, not resurrect the message

## 3. Allowed Stable Outcomes

Allowed outcomes for one race:

- callback wins and the message is `SUCCESS`
- expiry wins and the message is `EXPIRED`

Forbidden outcomes:

- both success and expiry semantics applied to the same attempt
- duplicate final publications
- task left non-terminal when all persisted messages are already final

## 4. Primary Acceptance Coverage

Deterministic concurrency acceptance:

- [TaskConcurrencyAcceptanceTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-engine/src/test/java/com/xa/mass/engine/TaskConcurrencyAcceptanceTest.java:83)

Lifecycle/invariant expiry coverage:

- [TaskManagerLifecycleTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java)

Command:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskConcurrencyAcceptanceTest,TaskManagerLifecycleTest test
```

## 5. Follow-On Risk

The current deterministic lane covers one logical message well.

Future expansion should add:

- multi-message expiry/result races
- worker release behavior after expiry wins
- Boot-shell black-box timing coverage once a stable harness exists
