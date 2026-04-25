# Worker Release And Redispatch

## 1. Scope

This topic covers resource release after attempt closure and the follow-on redispatch decision.

The core questions are:

- is the worker unlocked only when it is truly no longer busy for that task
- is the worker-context released only when it is still owned by that task
- is redispatch requested only when pending work actually remains

## 2. Core Code Paths

- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskResourceReleaseListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/storage/TaskStorage.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/storage/InMemoryTaskStorage.java`

Important behaviors:

- `hasProcessingMessagesForWorker(...)` is the release-side truth for "worker still busy on this task"
- terminal closure releases remaining task-owned runtime occupancy
- non-terminal attempt closure can request redispatch when pending dispatchable work remains

## 3. Acceptance Coverage

Engine listener/invariant coverage:

- [TaskResourceReleaseListenerTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-engine/src/test/java/com/xa/mass/engine/listener/TaskResourceReleaseListenerTest.java)

Boot-shell E2E coverage:

- [TaskApiSingleWorkerReuseIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiSingleWorkerReuseIntegrationTest.java)
- [TaskApiMultiRoundDispatchIntegrationTest.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/assignment/TaskApiMultiRoundDispatchIntegrationTest.java)

Command:

```bash
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskResourceReleaseListenerTest test
./mvnw -pl xa-mass-dev-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiSingleWorkerReuseIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest test
```

## 4. Invariants

Must stay true:

- releasing one context must not release sibling contexts
- no unlock while another message for the same task is still processing on that worker
- redispatch should only happen after the prior round is truly free to refill
- terminal release must not steal a context already rebound to another task
