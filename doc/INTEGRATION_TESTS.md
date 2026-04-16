# Integration Test Guide

Last updated: 2026-04-15

This document is the single authoritative reference for integration tests in `xa-mass-mock`.

Contents:
1. [Why integration tests matter for this project](#1-why-integration-tests-matter-for-this-project)
2. [How tests are structured](#2-how-tests-are-structured)
3. [Running the tests](#3-running-the-tests)
4. [Current test inventory](#4-current-test-inventory)
5. [State machine coverage map](#5-state-machine-coverage-map)
6. [Test technique patterns](#6-test-technique-patterns)
7. [Important missing tests](#7-important-missing-tests)
8. [Adding a new integration test](#8-adding-a-new-integration-test)

> **Package note**: all integration tests live under `com.xa.mass.mock.e2e` organized by domain:
> `lifecycle/`, `assignment/`, `results/`, `audit/`. The shared base class is
> `com.xa.mass.mock.e2e.support.AbstractMockE2eTest`.
>
> **Task creation note**: since `9aa791c`, `textContent` is no longer a top-level task field.
> Pass it inside `sharedConfig: {"textContent": "..."}`. Unknown top-level fields are rejected
> with HTTP 400 (`@JsonIgnoreProperties(ignoreUnknown = false)` on `TaskCreateRequestDto`).
> Use `AbstractMockE2eTest.createTaskId()` which handles this correctly.

---

## 1. Why integration tests matter for this project

The platform has **two independent state machines** that must stay consistent:

```
Task:    NEW → READY → RUNNING → TERMINAL
                ↘ BLOCKED          ↑
                ↘ PAUSED ──────────┘

TaskMsg: INIT → BINDING → ASSIGNED → RUNNING → SUCCESS
                                         ↘ FAILED
                                         ↘ EXPIRED
```

Unit tests verify each state machine in isolation. But only integration tests can catch:

- Task reaches `TERMINAL` while some `TaskMsg` rows are still `ASSIGNED` (counter mismatch)
- `terminalReason` is `ALL_MESSAGES_SUCCEEDED` but a `FAILED` message exists (metadata corruption)
- `peakAssignedWorkerCount` is non-zero but no `workerId` was ever written to any `TaskMsg` (assignment gap)
- A `PAUSED` task that received callbacks while paused never closes to `TERMINAL` (state convergence failure)
- A `READY` task with no matching worker is silently dropped from the assignment queue (orphan task)
- Two concurrent tasks are assigned to the same worker instead of separate workers (concurrency bug)

These bugs are invisible at unit-test level because the two state machines talk through `TaskManager`, which is mocked out in unit tests.

---

## 2. How tests are structured

All integration tests are in:

```
xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/
```

Each test:

- Uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` against the real `MockApplicationSpringBootApp`
- Allocates a free WebSocket port at class load time and injects it via `@DynamicPropertySource`
- Uses `@DirtiesContext` so each test class gets a clean Spring context (important: state is in-memory)
- Uses `TestRestTemplate` for HTTP calls to the real running API
- Uses `MassWebSocketClientImpl` for real WebSocket connections to the gateway

Default fixture files used by most tests:

| Property | File |
|----------|------|
| `mock.client.workers-config` | `mock/test_mock_workers.json` — 2 workers, workerGroupId=us, project=demoApp |
| `mass.mock.data.workers` | `mock/test_mock_workers.json` — seeded workers for assignment tests |
| `mass.mock.data.workers` | `mock/test_mock_workers_empty.json` — 0 workers (for delayed-worker tests) |
| `mass.mock.data.worker-contexts` | `mock/test_mock_worker_contexts.json` — matching worker contexts |
| `mass.mock.data.worker-contexts` | `mock/test_mock_worker_contexts_empty.json` — 0 worker contexts (for delayed-worker tests) |
| `mass.mock.data.tasks` | `mock/test_mock_tasks.json` — no pre-seeded tasks |
| `mass.mock.data.rules` | `mock/test_mock_rules.json` — empty array; keeps the current default worker-match rules unless the test overrides `RuleManager` explicitly |

Key `@SpringBootTest` properties:

| Property | Meaning |
|----------|---------|
| `mock.client.auto-start=true` | `WebSocketClientStarter` connects mock clients on `ApplicationReadyEvent` |
| `mock.client.auto-start=false` | no auto-connect; tests connect clients manually |
| `mock.client.task-result-status=FAILED` | all mock clients respond with `FAILED` instead of `SUCCESS` |
| `mock.client.retry-attempts=1` | reduces connection retry noise in test logs |

---

## 3. Running the tests

Run all integration tests (plus supporting unit tests):

```bash
./mvnw -pl xa-mass-mock -am clean test
```

Run only the integration tests by name:

```bash
./mvnw -pl xa-mass-mock -am \
  -Dtest="TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiMixedResultsIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,TaskApiPauseCompletionIntegrationTest,TaskApiResumeAndCompleteIntegrationTest,TaskApiTerminateRunningIntegrationTest,TaskApiCallbackReplayIntegrationTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiMultiTaskAssignmentIntegrationTest,TaskApiStateValidationIntegrationTest,TaskApiMinimumWorkerGateIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiSingleWorkerReuseIntegrationTest,TaskApiTerminateReuseIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Run the full regression suite including engine and runtime unit tests:

```bash
./mvnw clean test
```

Expected result: `BUILD SUCCESS`, 0 failures.

> **Note**: integration tests start a real Spring Boot context and open real TCP ports. They take 1–6 s each. The full suite runs in ~30 s on a modern laptop.

---

## 4. Current test inventory

### 4.1 `TaskApiIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (happy path)

**Setup**: auto-start=true, 2 workers, 2 targets

**Scenario**:
1. `POST /status/api/tasks` → task is `NEW`
2. `POST /status/api/tasks/{id}/audit?approved=true` → triggers assignment
3. Poll until `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `peakAssignedWorkerCount = 2`, `taskSuccessNumber = 2`
- Both `TaskMsg` rows: `status=SUCCESS`, `workerId/workerContextId/batchId` non-null

---

### 4.2 `TaskApiFailureResultIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (all-fail path)

**Setup**: auto-start=true, 2 workers, `mock.client.task-result-status=FAILED`

**Scenario**: same as 4.1, but mock clients respond with `FAILED`

**Assertions**:
- `terminalReason = ALL_MESSAGES_FAILED`
- `peakAssignedWorkerCount = 2`, `taskSuccessNumber = 0`
- Both `TaskMsg` rows: `status=FAILED`, `errorMessage` contains `workerId`

---

### 4.3 `TaskApiMixedResultsIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (mixed-result path)

**Setup**: auto-start=false, 2 workers, 2 targets

**Scenario**:
1. Approve task → wait for `RUNNING` with 2 `ASSIGNED` messages
2. Send `SUCCESS` for `messages[0]` via `ReplayClient` directly to the gateway WebSocket
3. Send `FAILED` for `messages[1]` via `ReplayClient` directly to the gateway WebSocket
4. Poll until `TERMINAL`

**Assertions**:
- `terminalReason = MIXED_MESSAGE_RESULTS`
- `taskSuccessNumber = 1`
- `messages[0].status = SUCCESS`, `messages[1].status = FAILED`

> This is the only test that exercises the `MIXED_MESSAGE_RESULTS` branch of `determineTerminalReason()`.

---

### 4.4 `TaskApiLifecycleGuardsIntegrationTest` (4 tests)

**Paths covered**: reject/approve, pause/resume, delete guard

**Setup**: auto-start=false, no assignable workers (for pause/resume tests)

**Scenarios**:
1. `rejectThenApprove`: `NEW → BLOCKED → READY`
2. `pauseAndResume`: `READY → PAUSED → READY` (no worker available, so stays READY after resume)
3. `deleteRejected`: `BLOCKED` task can be deleted
4. `cannotDeleteReady`: `READY` task delete returns 400

**Assertions**: HTTP response codes, `task.status` at each step

---

### 4.5 `TaskApiPauseCompletionIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → PAUSED → TERMINAL` (callbacks arrive while paused)

**Setup**: auto-start=false, 2 workers, 2 targets

**Scenario**:
1. Approve → wait for `RUNNING` with 2 `ASSIGNED` messages
2. `POST /status/api/tasks/{id}/pause` → `PAUSED`
3. Send `SUCCESS` for both messages via `ReplayClient` directly to the gateway — **no resume called**
4. Poll until `TERMINAL`

**Assertions**:
- Task closes to `TERMINAL` without a manual resume call
- `taskSuccessNumber = 2`, both messages `SUCCESS`

> Proves that `TaskManager.updateTaskProgress()` closes non-final tasks (including `PAUSED`) to `TERMINAL` once all `TaskMsg` rows are final.

---

### 4.6 `TaskApiResumeAndCompleteIntegrationTest`

**Path covered**: `NEW → READY → PAUSED → READY → RUNNING → TERMINAL` (normal resume path)

**Setup**: auto-start=false, **empty worker list** initially, 1 target

**Scenario**:
1. Approve → task reaches `READY` but stays there (`peakAssignedWorkerCount=0`, message stays `INIT`)
2. Pause the `READY` task → `PAUSED`
3. Register a matching worker programmatically via `WorkerManager`
4. Connect a `MassWebSocketClientImpl` (auto-responds SUCCESS)
5. `POST /status/api/tasks/{id}/resume` → `notifyTaskReady()` fires → assign worker picks it up → `RUNNING`
6. Mock client auto-sends callback → `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `peakAssignedWorkerCount = 1`, `taskSuccessNumber = 1`
- `message.workerId` is non-null and `workerContextId/batchId` are non-null

> Distinct from PauseCompletion: in that test, callbacks arrive *while paused*. Here, the task resumes first, then the worker completes it.

---

### 4.7 `TaskApiTerminateRunningIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (manual terminate, no worker callbacks)

**Setup**: auto-start=false, 2 workers seeded (for assignment), 2 targets

**Scenario**:
1. Approve → wait for `RUNNING`, 2 messages at `ASSIGNED`
2. `POST /status/api/tasks/{id}/terminate` before any client callback
3. `DELETE /status/api/tasks/{id}` after terminal
4. `GET /status/api/tasks/{id}` returns 404

**Assertions**:
- `terminalReason` reflects manual cancel (`MANUAL_CANCELLED`)
- `taskSuccessNumber = 0` (no callbacks processed)
- Pending `ASSIGNED` messages are drained to `EXPIRED` by `cancelPendingMessages()` on terminal transition
- Delete succeeds; subsequent GET returns 404

---

### 4.8 `TaskApiCallbackReplayIntegrationTest`

**Path covered**: duplicate callback idempotency

**Setup**: auto-start=true, 2 workers, 2 targets

**Scenario**:
1. Happy path to `TERMINAL`
2. A `ReplayClient` re-sends an already-processed `TASK/step` result for the same `taskId + msgId`

**Assertions**:
- Gateway still returns 200 ACK
- `TaskMsg.status` remains what it was after the first result (not overwritten)
- `taskSuccessNumber` unchanged

---

### 4.9 `TaskApiDelayedWorkerAvailabilityIntegrationTest`

**Path covered**: `READY` task waits for a late worker

**Setup**: auto-start=false, **empty worker list** initially, 1 target

**Scenario**:
1. Approve → task reaches `READY`, `peakAssignedWorkerCount=0`, message stays `INIT`
2. Register a matching worker + connect `MassWebSocketClientImpl`
3. TaskAssignWorker retry loop picks it up → `RUNNING → TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `message.workerId` is non-null

> Proves `TaskAssignWorker` delayed-retry keeps orphaned `READY` tasks in the queue instead of dropping them.

---

### 4.10 `TaskApiMultiTaskAssignmentIntegrationTest`

**Path covered**: two concurrent tasks distributed across separate workers

**Setup**: auto-start=true, 2 workers, 2 separate tasks with 1 target each

**Scenario**:
1. Create and approve two tasks simultaneously
2. Wait for both to reach `TERMINAL`

**Assertions**:
- Both tasks: `terminalReason = ALL_MESSAGES_SUCCEEDED`, `peakAssignedWorkerCount = 1`
- `messages[0].workerId ≠ messages[1].workerId` (tasks went to different workers)

---

### 4.11 `TaskApiStateValidationIntegrationTest` (4 tests)

**Path covered**: `GET /status/api/tasks/{id}` `stateValidation` field

**Setup**: auto-start=true, 2 workers, 2 targets

**Scenarios**:
1. `getTaskExposesValidTerminalState`: normal completed task → `valid=true`, `needsResolution=false`
2. `getTaskExposesNeedsResolution`: manually reopen a completed task to `RUNNING` → `needsResolution=true`
3. `getTaskExposesInvalidWhenTerminalReasonMissing`: remove `terminalReason` from a `TERMINAL` task → `violations=[TERMINAL_REASON_MISSING]`
4. `getTaskExposesInvalidWhenTerminalReasonMismatch`: set wrong `terminalReason` → `violations=[TERMINAL_REASON_MISMATCH_ALL_FAILED]`

---

### 4.12 `TaskApiMinimumWorkerGateIntegrationTest`

**Path covered**: `READY` blocked by `minRequiredWorkerCount`, unblocked when enough workers arrive

**Setup**: auto-start=false, 1 worker registered, `minRequiredWorkerCount=2`

**Scenario**:
1. Approve task → stays `READY` (`peakAssignedWorkerCount=0`), first worker context stays `IDLE`
2. Register second worker + connect both clients
3. Task advances to `RUNNING` → `TERMINAL`

**Assertions**:
- Task stays `READY` while worker count stays below minimum (provisional locks released)
- Both messages complete to `SUCCESS` once gate is satisfied

---

### 4.13 `TaskApiMultiRoundDispatchIntegrationTest`

**Path covered**: single worker, `batchSize=1`, 3 targets → 3 sequential dispatch rounds

**Setup**: auto-start=false, 1 worker, 3 targets, `batchSize=1`

**Scenario**:
1. Approve → worker gets 1 message (round 1)
2. Worker completes → worker lock released → round 2 dispatched
3. Worker completes → round 3 dispatched → final `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`, `taskSuccessNumber = 3`
- Proves `batchSize` is a per-worker per-round cap, not a total task cap

---

### 4.14 `TaskApiSingleWorkerReuseIntegrationTest`

**Path covered**: single worker completes task 1, is reused for task 2

**Setup**: auto-start=false, 1 worker, 2 sequential tasks

**Scenario**:
1. Approve task 1 → `TERMINAL`
2. Approve task 2 → same worker assigned → `TERMINAL`

**Assertions**:
- Both tasks: `terminalReason = ALL_MESSAGES_SUCCEEDED`
- Same `workerId` appears in both task message records
- Proves worker/worker-context locks are properly released on terminal completion

---

### 4.15 `TaskApiTerminateReuseIntegrationTest`

**Path covered**: manual terminate releases worker lock for next task

**Setup**: auto-start=false, 1 worker, 2 tasks

**Scenario**:
1. Approve task 1 → `RUNNING`
2. Terminate task 1 before callbacks → `TERMINAL (MANUAL_CANCELLED)`
3. Approve task 2 → same worker available → `TERMINAL (ALL_MESSAGES_SUCCEEDED)`

**Assertions**:
- Proves `cancelPendingMessages()` + worker release on manual terminal
- Second task can reuse the same worker after the first task's forced closure

---

### 4.16 `TaskApiWorkerContextAttributeRoutingIntegrationTest`

**Path covered**: `workerContextAttributes['country']` is used as the routing signal instead of a hard `workerGroupId` filter

**Setup**: auto-start=false, multiple workers with different `workerGroupId` values and `workerContextAttributes['country']`

**Scenario**:
- Task with `taskRoutingCode=us`
- Worker A: `workerGroupId=pool-east`, `workerContextAttributes['country']=us` → should match
- Worker B: `workerGroupId=pool-west`, `workerContextAttributes['country']=gb` → should not match

**Assertions**:
- The matched message has a non-null `workerId`
- The matched `workerContextId` belongs to the worker context whose `country` attribute equals `taskRoutingCode`
- Proves attribute-based routing works end-to-end through QLExpress rules
- The test clears the default rules in code and injects its own explicit worker-context-attribute rule set, so it does not depend on the shared mock rule fixture

---

### 4.17 `TaskApiWorkerWithoutContextIntegrationTest`

**Path covered**: stateless worker execution without any `WorkerContext`

**Setup**: auto-start=false, one worker registered programmatically, **no worker contexts**

**Scenario**:
- Create a task without `routingCode`
- Approve task
- Connect a real WebSocket worker client with no associated `WorkerContext`
- Wait for `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `message.workerId` is non-null
- `message.workerContextId = null`
- Proves the mainline platform can dispatch to a stateless worker when the task does not require worker-context-based routing

---

## 5. State machine coverage map

### Task state paths

| Path | Tested by |
|------|-----------|
| `NEW → READY → RUNNING → TERMINAL` (all succeed) | 4.1, 4.8, 4.9, 4.10, 4.11, 4.13, 4.14, 4.15 |
| `NEW → READY → RUNNING → TERMINAL` (all fail) | 4.2 |
| `NEW → READY → RUNNING → TERMINAL` (mixed) | 4.3 |
| `NEW → READY → RUNNING → TERMINAL` (manual cancel) | 4.7 |
| `NEW → BLOCKED → READY` (reject then approve) | 4.4 |
| `NEW → READY → PAUSED → READY` (pause then resume, no worker) | 4.4 |
| `NEW → READY → RUNNING → PAUSED → TERMINAL` (callbacks while paused) | 4.5 |
| `NEW → READY → PAUSED → READY → RUNNING → TERMINAL` (resume, late worker) | 4.6 |
| `READY` orphan retry (delayed worker) | 4.9 |
| `READY` blocked by minimum worker gate | 4.12 |
| Multi-round dispatch (batchSize cap per worker per round) | 4.13 |
| Worker reuse across sequential tasks | 4.14, 4.15 |
| Stateless worker with no WorkerContext | 4.17 |

### TaskMsg state paths

| Path | Tested by |
|------|-----------|
| `INIT → ASSIGNED → SUCCESS` | 4.1, 4.5, 4.6, 4.8, 4.9, 4.10, 4.12–4.16 |
| `INIT → ASSIGNED → FAILED` | 4.2, 4.3 (one of two) |
| `INIT → ASSIGNED → EXPIRED` (task terminated before callback) | 4.7, 4.15 |
| Duplicate result idempotency | 4.8 |
| Mixed SUCCESS + FAILED in same task | 4.3 |
| Multi-round: worker reused after each batch completes | 4.13 |

### `TaskTerminalReason` values

| Value | Tested by |
|-------|-----------|
| `ALL_MESSAGES_SUCCEEDED` | 4.1, 4.5, 4.6, 4.8–4.10, 4.12–4.16 |
| `ALL_MESSAGES_FAILED` | 4.2 |
| `MIXED_MESSAGE_RESULTS` | 4.3 |
| `MANUAL_CANCELLED` | 4.7, 4.15 |

All four `TaskTerminalReason` values are covered end-to-end.

> `EXPIRED` messages count as "failed" in `AllMessagesFinalTaskTerminalPolicy`:
> `ALL_MESSAGES_FAILED` if all are `FAILED + EXPIRED`; `MIXED_MESSAGE_RESULTS` if some are `SUCCESS`.

---

## 6. Test technique patterns

### Pattern A: Auto-started mock clients

Used by: 4.1, 4.2, 4.10, 4.11

```java
properties = {
    "mock.client.auto-start=true",
    "mock.client.workers-config=mock/test_mock_workers.json",
    ...
}
```

`WebSocketClientStarter` connects all workers from the config file on `ApplicationReadyEvent`. The test only needs to approve the task and poll for `TERMINAL`.

Limitation: all clients use the same `mock.client.task-result-status` — you cannot mix `SUCCESS` and `FAILED` results across workers this way.

---

### Pattern B: Manual ReplayClient (direct WebSocket gateway call)

Used by: 4.3, 4.5, 4.7, 4.8

```java
ReplayClient client = new ReplayClient(wsUri, workerId, msgId);
client.connectBlocking();
client.sendMessage(buildResultPayload(taskId, msgId, workerId, "SUCCESS", "detail"));
assertTrue(client.awaitAck(3, TimeUnit.SECONDS));
assertEquals(200, client.ackSnapshot().code());
```

`ReplayClient` extends `MassWebSocketClientImpl`, overrides `onMessage` to capture the gateway ACK frame. Use this pattern when you need:

- Per-message control of `SUCCESS` vs `FAILED`
- To assert the gateway ACK code and message directly
- To replay an already-processed result

`buildResultPayload` constructs a real `MassMessage` of type `TASK/step` with `response=true`.

---

### Pattern C: Late worker registration

Used by: 4.6, 4.9

```java
@Autowired
private WorkerManager workerManager;

Worker worker = new Worker();
worker.setWorkerId(workerId);
worker.setWorkerGroupId("us");
worker.setStatus(WorkerStatus.ONLINE);
worker.setSupportedProjects(List.of("demoApp"));
workerManager.addWorker(worker);

WorkerContext workerContext = new WorkerContext();
workerContext.setWorkerContextId("worker-context-" + workerId);
workerContext.setWorkerId(workerId);
workerContext.setChannel("us");
workerContext.setStatus(WorkerContextStatus.IDLE);
workerManager.addWorkerContext(workerContext);

MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, workerId);
client.connectBlocking();
```

Both a `Worker` and a matching `WorkerContext` must be registered before the assignment rule can match. The `WorkerContext` status must be `IDLE`.

---

### Pattern D: `waitForTaskSnapshot` polling helper

All tests use a polling helper that retries up to N times with 250–500 ms sleep:

```java
private TaskSnapshot waitForTaskStatus(String taskId, String expected, int maxAttempts)
        throws InterruptedException {
    for (int i = 0; i < maxAttempts; i++) {
        Map<String, Object> detail = exchange("/status/api/tasks/" + taskId, GET, null);
        Map<String, Object> msgs = exchange("/status/api/tasks/" + taskId + "/messages?...", GET, null);
        if (expected.equals(task(detail).get("status"))) {
            return new TaskSnapshot(task(detail), messages(msgs));
        }
        Thread.sleep(500L);
    }
    throw new AssertionError("Task did not reach " + expected + " within timeout");
}
```

`maxAttempts=20` with `sleep=250ms` gives a 5-second window, which is enough for in-memory assignment. Use `maxAttempts=8` with `sleep=500ms` for delayed-worker scenarios that include assignment retry.

---

## 7. Important missing tests

The following paths are **not yet covered** by any integration test. They are listed in priority order.

### 7.1 🔴 Cancel from RUNNING via HTTP API

**Missing path**: `RUNNING → TERMINAL (MANUAL_CANCELLED)` triggered by `POST /terminate` with subsequent late callbacks being ignored

**Why it matters**: `TaskApiTerminateRunningIntegrationTest` (4.7) covers terminate-before-callbacks. What is not covered is sending callbacks **after** terminate and verifying they are silently dropped (not re-opening the task to `RUNNING` or corrupting `taskSuccessNumber`).

**Suggested test name**: `TaskApiLateCallbackAfterTerminateIntegrationTest`

**Outline**:
```
1. Approve → wait RUNNING, 2 messages ASSIGNED
2. POST /terminate → TERMINAL
3. Submit SUCCESS callback for both messages via ReplayClient
4. Assert task stays TERMINAL, taskSuccessNumber stays 0
5. Assert messages stay ASSIGNED (not flipped to SUCCESS)
```

---

### 7.2 🟡 Worker disconnect mid-task

**Missing path**: a worker connects, receives `TASK/step`, then the WebSocket disconnects before sending a result

**Why it matters**: the task stays `RUNNING` forever since no callback arrives. In production this causes stuck tasks. There is currently no timeout, expiry, or retry mechanism for this — the test would expose the gap explicitly.

**Suggested test name**: `TaskApiWorkerDisconnectMidTaskIntegrationTest`

**Outline**:
```
1. auto-start=false, 2 workers, 2 targets
2. Approve → wait RUNNING, messages at ASSIGNED
3. Disconnect all MassWebSocketClientImpl instances
4. Assert task stays RUNNING (messages stay ASSIGNED)
5. Assert peakAssignedWorkerCount unchanged
```

This test intentionally documents the known limitation: the task does not self-heal today.

---

### 7.3 🟡 Cancel from READY via HTTP API

**Missing path**: `READY → TERMINAL (MANUAL_CANCELLED)` before any worker assignment

**Why it matters**: the `POST /terminate` endpoint calls `cancelTask()` which accepts any non-TERMINAL task. A task that was never assigned should reach `TERMINAL` cleanly with `taskSuccessNumber=0` and all messages still `INIT`.

**Suggested test name**: `TaskApiCancelReadyTaskIntegrationTest`

**Outline**:
```
1. auto-start=false, no workers, 1 target
2. Approve → READY (stays there, no worker)
3. POST /terminate → TERMINAL
4. Assert terminalReason = MANUAL_CANCELLED
5. Assert message.status = INIT (was never sent)
6. Assert DELETE succeeds
```

---

### 7.4 🟡 PAUSED → TERMINAL via `resumeTask` short-circuit

**Missing path**: `PAUSED → TERMINAL` triggered by a `POST /resume` call when all messages finished while paused

**Why it matters**: `TaskManager.resumeTaskDetailed()` has a special branch that closes the task to `TERMINAL` directly if `allTaskMessagesCompleted()` returns true at resume time. This is distinct from `TaskApiPauseCompletionIntegrationTest` (4.5), which tests closure without a resume call.

**Suggested test name**: `TaskApiResumeShortCircuitToTerminalIntegrationTest`

**Outline**:
```
1. auto-start=false, 2 workers, 2 targets
2. Approve → RUNNING → PAUSED
3. Submit SUCCESS for both messages via ReplayClient (task closes to TERMINAL automatically)
4. Reopen the task to PAUSED by directly calling taskManager.setStatus (or check if there's an API)
   — OR: construct the scenario so messages complete exactly when paused, then call resume
5. POST /resume
6. Assert response body: outcome = COMPLETED_TO_TERMINAL (not RESUMED_TO_READY)
7. Assert task status = TERMINAL
```

> Note: this scenario is hard to set up reliably in an integration test because the PauseCompletion path already closes the task. The unit test `TaskManagerLifecycleTest.resumeClosesToTerminalWhenAllMessagesDoneWhilePaused` covers the core logic. Only add an integration test if the API response shape (`outcome=COMPLETED_TO_TERMINAL`) matters for callers.

---

### 7.5 🟢 batchSize > 1

**Missing path**: a task with `batchSize=2` and multiple targets

**Why it matters**: `TaskMsg` creation logic uses `batchSize` to determine how many messages per target. No integration test has ever set `batchSize > 1`.

**Suggested test name**: `TaskApiBatchSizeIntegrationTest`

---

### 7.6 🟢 `EXPIRED` TaskMsg status

**Missing path**: a `TaskMsg` that times out and moves to `EXPIRED` instead of `SUCCESS`/`FAILED`

**Why it matters**: `TaskMsgStatus.canTransitionTo(EXPIRED)` allows transitions from `ASSIGNED` and `RUNNING`. No code path currently triggers this in production. A test would first require implementing the timeout mechanism.

---

## 8. Adding a new integration test

### Checklist

1. Place the test in the right sub-package under `xa-mass-mock/src/test/java/com/xa/mass/mock/e2e/`:
   `lifecycle/`, `assignment/`, `results/`, or `audit/`. Create a new sub-package if none fits.
2. Extend `AbstractMockE2eTest` — it provides `exchange()`, `task()`, `messages()`, `stateValidation()`,
   `createTaskId()`, `waitForTaskSnapshot()`, and `findFreePort()`.
3. Annotate with `@SpringBootTest(classes = MockApplicationSpringBootApp.class, webEnvironment = RANDOM_PORT)`
4. Add `@ActiveProfiles("dev")` and `@DirtiesContext`
5. Allocate a free WebSocket port and register it via `@DynamicPropertySource`
6. Use `createTaskId()` from `AbstractMockE2eTest` — it builds the `sharedConfig` wrapper correctly
7. Use `ReplayClient` (copy from `e2e/results/TaskApiMixedResultsIntegrationTest`) for per-message SUCCESS/FAILED
8. Use `WorkerManager.addWorker` + `addWorkerContext` (Pattern C) for late worker registration
9. Assert both the `task` and `messages` layers

### Template

```java
package com.xa.mass.mock.e2e.lifecycle; // or assignment/results/audit

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = MockApplicationSpringBootApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "mock.client.auto-start=false",
        "mock.client.workers-config=mock/test_mock_workers.json",
        "mass.mock.data.workers=mock/test_mock_workers.json",
        "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
        "mass.mock.data.tasks=mock/test_mock_tasks.json",
        "mass.mock.data.rules=mock/test_mock_rules.json"
    }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiYourScenarioIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        // also call registerWebSocketPropertiesWithClientUri if auto-start=true
    }

    @Test
    void yourScenario() throws Exception {
        String taskId = createTaskId("scenario-name", "scenario text", "target-a");
        // arrange, act, assert using inherited helpers
    }
}
```

### Before submitting

Run the focused integration suite to verify no regressions:

```bash
./mvnw -pl xa-mass-mock -am clean test -Dsurefire.failIfNoSpecifiedTests=false
```

Then update this document:

- Add a new row to the **State machine coverage map** (§5)
- Add a `###` entry under **Current test inventory** (§4)
- Remove the entry from **Important missing tests** (§7) if applicable
- Update the regression command in `doc/VERIFIED_RUNBOOK.md` §5.10 to include the new test class
