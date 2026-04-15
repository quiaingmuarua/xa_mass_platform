# Integration Test Guide

Last updated: 2026-04-13

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

---

## 1. Why integration tests matter for this project

The platform has **two independent state machines** that must stay consistent:

```
Task:    NEW → READY → RUNNING → TERMINAL
                ↘ BLOCKED          ↑
                ↘ PAUSED ──────────┘

TaskMsg: INIT → BINDING → SENT → RUNNING → SUCCESS
                                         ↘ FAILED
                                         ↘ EXPIRED
```

Unit tests verify each state machine in isolation. But only integration tests can catch:

- Task reaches `TERMINAL` while some `TaskMsg` rows are still `SENT` (counter mismatch)
- `terminalReason` is `ALL_MESSAGES_SUCCEEDED` but a `FAILED` message exists (metadata corruption)
- `scheduleDeviceCnt` is non-zero but no `deviceId` was ever written to any `TaskMsg` (assignment gap)
- A `PAUSED` task that received callbacks while paused never closes to `TERMINAL` (state convergence failure)
- A `READY` task with no matching device is silently dropped from the assignment queue (orphan task)
- Two concurrent tasks are assigned to the same device instead of separate devices (concurrency bug)

These bugs are invisible at unit-test level because the two state machines talk through `TaskManager`, which is mocked out in unit tests.

---

## 2. How tests are structured

All integration tests are in:

```
xa-mass-mock/src/test/java/com/xa/mass/mock/api/
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
| `mass.mock.data.devices` | `mock/test_mock_devices.json` — 2 devices, groupId=us, project=demoApp |
| `mass.mock.data.devices` | `mock/test_mock_devices_empty.json` — 0 devices (for delayed-device tests) |
| `mass.mock.data.tasks` | `mock/test_mock_tasks.json` — no pre-seeded tasks |
| `mass.mock.data.rules` | `mock/test_mock_rules.json` — 1 rule: country=us → demoApp |

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
  -Dtest="TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiMixedResultsIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,TaskApiPauseCompletionIntegrationTest,TaskApiResumeAndCompleteIntegrationTest,TaskApiTerminateRunningIntegrationTest,TaskApiCallbackReplayIntegrationTest,TaskApiDelayedDeviceAvailabilityIntegrationTest,TaskApiMultiTaskAssignmentIntegrationTest,TaskApiStateValidationIntegrationTest" \
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

**Setup**: auto-start=true, 2 devices, 2 targets

**Scenario**:
1. `POST /status/api/tasks` → task is `NEW`
2. `POST /status/api/tasks/{id}/audit?approved=true` → triggers assignment
3. Poll until `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `scheduleDeviceCnt = 2`, `taskExecutedNumber = 2`
- Both `TaskMsg` rows: `status=SUCCESS`, `deviceId/tokenId/batchId` non-null

---

### 4.2 `TaskApiFailureResultIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (all-fail path)

**Setup**: auto-start=true, 2 devices, `mock.client.task-result-status=FAILED`

**Scenario**: same as 4.1, but mock clients respond with `FAILED`

**Assertions**:
- `terminalReason = ALL_MESSAGES_FAILED`
- `scheduleDeviceCnt = 2`, `taskExecutedNumber = 0`
- Both `TaskMsg` rows: `status=FAILED`, `errorMessage` contains `deviceId`

---

### 4.3 `TaskApiMixedResultsIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (mixed-result path)

**Setup**: auto-start=false, 2 devices, 2 targets

**Scenario**:
1. Approve task → wait for `RUNNING` with 2 `SENT` messages
2. Send `SUCCESS` for `messages[0]` via `ReplayClient` directly to the gateway WebSocket
3. Send `FAILED` for `messages[1]` via `ReplayClient` directly to the gateway WebSocket
4. Poll until `TERMINAL`

**Assertions**:
- `terminalReason = MIXED_MESSAGE_RESULTS`
- `taskExecutedNumber = 1` (only successes count)
- `messages[0].status = SUCCESS`, `messages[1].status = FAILED`

> This is the only test that exercises the `MIXED_MESSAGE_RESULTS` branch of `determineTerminalReason()`.

---

### 4.4 `TaskApiLifecycleGuardsIntegrationTest` (4 tests)

**Paths covered**: reject/approve, pause/resume, delete guard

**Setup**: auto-start=false, no assignable devices (for pause/resume tests)

**Scenarios**:
1. `rejectThenApprove`: `NEW → BLOCKED → READY`
2. `pauseAndResume`: `READY → PAUSED → READY` (no device available, so stays READY after resume)
3. `deleteRejected`: `BLOCKED` task can be deleted
4. `cannotDeleteReady`: `READY` task delete returns 400

**Assertions**: HTTP response codes, `task.status` at each step

---

### 4.5 `TaskApiPauseCompletionIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → PAUSED → TERMINAL` (callbacks arrive while paused)

**Setup**: auto-start=false, 2 devices, 2 targets

**Scenario**:
1. Approve → wait for `RUNNING` with 2 `SENT` messages
2. `POST /status/api/tasks/{id}/pause` → `PAUSED`
3. Send `SUCCESS` for both messages via `ReplayClient` directly to the gateway — **no resume called**
4. Poll until `TERMINAL`

**Assertions**:
- Task closes to `TERMINAL` without a manual resume call
- `taskExecutedNumber = 2`, both messages `SUCCESS`

> Proves that `TaskManager.updateTaskProgress()` closes non-final tasks (including `PAUSED`) to `TERMINAL` once all `TaskMsg` rows are final.

---

### 4.6 `TaskApiResumeAndCompleteIntegrationTest`

**Path covered**: `NEW → READY → PAUSED → READY → RUNNING → TERMINAL` (normal resume path)

**Setup**: auto-start=false, **empty device list** initially, 1 target

**Scenario**:
1. Approve → task reaches `READY` but stays there (`scheduleDeviceCnt=0`, message stays `INIT`)
2. Pause the `READY` task → `PAUSED`
3. Register a matching device programmatically via `DeviceManager`
4. Connect a `MassWebSocketClientImpl` (auto-responds SUCCESS)
5. `POST /status/api/tasks/{id}/resume` → `notifyTaskReady()` fires → assign worker picks it up → `RUNNING`
6. Mock client auto-sends callback → `TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `scheduleDeviceCnt = 1`, `taskExecutedNumber = 1`
- `message.deviceId = late-device-0`, `tokenId/batchId` non-null

> Distinct from PauseCompletion: in that test, callbacks arrive *while paused*. Here, the task resumes first, then the device completes it.

---

### 4.7 `TaskApiTerminateRunningIntegrationTest`

**Path covered**: `NEW → READY → RUNNING → TERMINAL` (manual terminate, no device callbacks)

**Setup**: auto-start=false, 2 devices seeded (for assignment), 2 targets

**Scenario**:
1. Approve → wait for `RUNNING`, 2 messages at `SENT`
2. `POST /status/api/tasks/{id}/terminate` before any client callback
3. `DELETE /status/api/tasks/{id}` after terminal
4. `GET /status/api/tasks/{id}` returns 404

**Assertions**:
- `terminalReason` reflects manual cancel (`MANUAL_CANCELLED`)
- `taskExecutedNumber = 0` (no callbacks processed)
- Messages stay `SENT` (not flipped to SUCCESS/FAILED)
- Delete succeeds; subsequent GET returns 404

---

### 4.8 `TaskApiCallbackReplayIntegrationTest`

**Path covered**: duplicate callback idempotency

**Setup**: auto-start=true, 2 devices, 2 targets

**Scenario**:
1. Happy path to `TERMINAL`
2. A `ReplayClient` re-sends an already-processed `TASK/step` result for the same `taskId + msgId`

**Assertions**:
- Gateway still returns 200 ACK
- `TaskMsg.status` remains what it was after the first result (not overwritten)
- `taskExecutedNumber` unchanged

---

### 4.9 `TaskApiDelayedDeviceAvailabilityIntegrationTest`

**Path covered**: `READY` task waits for a late device

**Setup**: auto-start=false, **empty device list** initially, 1 target

**Scenario**:
1. Approve → task reaches `READY`, `scheduleDeviceCnt=0`, message stays `INIT`
2. Register a matching device + connect `MassWebSocketClientImpl`
3. TaskAssignWorker retry loop picks it up → `RUNNING → TERMINAL`

**Assertions**:
- `terminalReason = ALL_MESSAGES_SUCCEEDED`
- `message.deviceId = late-device-0`

> Proves `TaskAssignWorker` delayed-retry keeps orphaned `READY` tasks in the queue instead of dropping them.

---

### 4.10 `TaskApiMultiTaskAssignmentIntegrationTest`

**Path covered**: two concurrent tasks distributed across separate devices

**Setup**: auto-start=true, 2 devices, 2 separate tasks with 1 target each

**Scenario**:
1. Create and approve two tasks simultaneously
2. Wait for both to reach `TERMINAL`

**Assertions**:
- Both tasks: `terminalReason = ALL_MESSAGES_SUCCEEDED`, `scheduleDeviceCnt = 1`
- `messages[0].deviceId ≠ messages[1].deviceId` (tasks went to different devices)

---

### 4.11 `TaskApiStateValidationIntegrationTest` (4 tests)

**Path covered**: `GET /status/api/tasks/{id}` `stateValidation` field

**Setup**: auto-start=true, 2 devices, 2 targets

**Scenarios**:
1. `getTaskExposesValidTerminalState`: normal completed task → `valid=true`, `needsResolution=false`
2. `getTaskExposesNeedsResolution`: manually reopen a completed task to `RUNNING` → `needsResolution=true`
3. `getTaskExposesInvalidWhenTerminalReasonMissing`: remove `terminalReason` from a `TERMINAL` task → `violations=[TERMINAL_REASON_MISSING]`
4. `getTaskExposesInvalidWhenTerminalReasonMismatch`: set wrong `terminalReason` → `violations=[TERMINAL_REASON_MISMATCH_ALL_FAILED]`

---

## 5. State machine coverage map

### Task state paths

| Path | Tested by |
|------|-----------|
| `NEW → READY → RUNNING → TERMINAL` (all succeed) | 4.1, 4.8, 4.9, 4.10, 4.11 |
| `NEW → READY → RUNNING → TERMINAL` (all fail) | 4.2 |
| `NEW → READY → RUNNING → TERMINAL` (mixed) | 4.3 |
| `NEW → READY → RUNNING → TERMINAL` (manual cancel) | 4.7 |
| `NEW → BLOCKED → READY` (reject then approve) | 4.4 |
| `NEW → READY → PAUSED → READY` (pause then resume, no device) | 4.4 |
| `NEW → READY → RUNNING → PAUSED → TERMINAL` (callbacks while paused) | 4.5 |
| `NEW → READY → PAUSED → READY → RUNNING → TERMINAL` (resume, late device) | 4.6 |
| `READY` orphan retry (delayed device) | 4.9 |

### TaskMsg state paths

| Path | Tested by |
|------|-----------|
| `INIT → SENT → SUCCESS` | 4.1, 4.5, 4.6, 4.8, 4.9, 4.10 |
| `INIT → SENT → FAILED` | 4.2, 4.3 (one of two) |
| `INIT → SENT` (task terminated before callback) | 4.7 |
| Duplicate result idempotency | 4.8 |
| Mixed SUCCESS + FAILED in same task | 4.3 |

### `TaskTerminalReason` values

| Value | Tested by |
|-------|-----------|
| `ALL_MESSAGES_SUCCEEDED` | 4.1, 4.5, 4.6, 4.8, 4.9, 4.10 |
| `ALL_MESSAGES_FAILED` | 4.2 |
| `MIXED_MESSAGE_RESULTS` | 4.3 |
| `MANUAL_CANCELLED` | 4.7 |

All four `TaskTerminalReason` values are covered end-to-end.

---

## 6. Test technique patterns

### Pattern A: Auto-started mock clients

Used by: 4.1, 4.2, 4.10, 4.11

```java
properties = {
    "mock.client.auto-start=true",
    "mock.client.devices-config=mock/test_mock_devices.json",
    ...
}
```

`WebSocketClientStarter` connects all devices from the config file on `ApplicationReadyEvent`. The test only needs to approve the task and poll for `TERMINAL`.

Limitation: all clients use the same `mock.client.task-result-status` — you cannot mix SUCCESS and FAILED results across devices this way.

---

### Pattern B: Manual ReplayClient (direct WebSocket gateway call)

Used by: 4.3, 4.5, 4.7, 4.8

```java
ReplayClient client = new ReplayClient(wsUri, deviceId, msgId);
client.connectBlocking();
client.sendMessage(buildResultPayload(taskId, msgId, deviceId, "SUCCESS", "detail"));
assertTrue(client.awaitAck(3, TimeUnit.SECONDS));
assertEquals(200, client.ackSnapshot().code());
```

`ReplayClient` extends `MassWebSocketClientImpl`, overrides `onMessage` to capture the gateway ACK frame. Use this pattern when you need:

- Per-message control of `SUCCESS` vs `FAILED`
- To assert the gateway ACK code and message directly
- To replay an already-processed result

`buildResultPayload` constructs a real `MassMessage` of type `TASK/step` with `response=true`.

---

### Pattern C: Late device registration

Used by: 4.6, 4.9

```java
@Autowired
private DeviceManager deviceManager;

Device device = new Device();
device.setDeviceId(deviceId);
device.setGroupId("us");
device.setStatus(DeviceStatus.ONLINE);
device.setSupportedProjects(List.of(Project.DEMO_APP));
deviceManager.addDevice(device);

Token token = new Token();
token.setTokenId("token-" + deviceId);
token.setDeviceId(deviceId);
token.setChannel("us");
token.setStatus(TokenStatus.LOGIN_READY);
deviceManager.addToken(deviceId, token);

MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, deviceId);
client.connectBlocking();
```

Both a `Device` and a matching `Token` must be registered before the assignment rule can match. The `Token` status must be `LOGIN_READY`.

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

`maxAttempts=20` with `sleep=250ms` gives a 5-second window, which is enough for in-memory assignment. Use `maxAttempts=8` with `sleep=500ms` for delayed-device scenarios that include worker retry.

---

## 7. Important missing tests

The following paths are **not yet covered** by any integration test. They are listed in priority order.

### 7.1 🔴 Cancel from RUNNING via HTTP API

**Missing path**: `RUNNING → TERMINAL (MANUAL_CANCELLED)` triggered by `POST /terminate` with subsequent late callbacks being ignored

**Why it matters**: `TaskApiTerminateRunningIntegrationTest` (4.7) covers terminate-before-callbacks. What is not covered is sending callbacks **after** terminate and verifying they are silently dropped (not re-opening the task to `RUNNING` or corrupting `taskExecutedNumber`).

**Suggested test name**: `TaskApiLateCallbackAfterTerminateIntegrationTest`

**Outline**:
```
1. Approve → wait RUNNING, 2 messages SENT
2. POST /terminate → TERMINAL
3. Submit SUCCESS callback for both messages via ReplayClient
4. Assert task stays TERMINAL, taskExecutedNumber stays 0
5. Assert messages stay SENT (not flipped to SUCCESS)
```

---

### 7.2 🟡 Device disconnect mid-task

**Missing path**: device connects, receives `TASK/step`, then WebSocket disconnects before sending a result

**Why it matters**: the task stays `RUNNING` forever since no callback arrives. In production this causes stuck tasks. There is currently no timeout, expiry, or retry mechanism for this — the test would expose the gap explicitly.

**Suggested test name**: `TaskApiDeviceDisconnectMidTaskIntegrationTest`

**Outline**:
```
1. auto-start=false, 2 devices, 2 targets
2. Approve → wait RUNNING, messages at SENT
3. Disconnect all MassWebSocketClientImpl instances
4. Assert task stays RUNNING (messages stay SENT)
5. Assert scheduleDeviceCnt unchanged
```

This test intentionally documents the known limitation: the task does not self-heal today.

---

### 7.3 🟡 Cancel from READY via HTTP API

**Missing path**: `READY → TERMINAL (MANUAL_CANCELLED)` before any device assignment

**Why it matters**: the `POST /terminate` endpoint calls `cancelTask()` which accepts any non-TERMINAL task. A task that was never assigned should reach `TERMINAL` cleanly with `taskExecutedNumber=0` and all messages still `INIT`.

**Suggested test name**: `TaskApiCancelReadyTaskIntegrationTest`

**Outline**:
```
1. auto-start=false, no devices, 1 target
2. Approve → READY (stays there, no device)
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
1. auto-start=false, 2 devices, 2 targets
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

**Why it matters**: `TaskMsgStatus.canTransitionTo(EXPIRED)` allows transitions from `SENT` and `RUNNING`. No code path currently triggers this in production. A test would first require implementing the timeout mechanism.

---

## 8. Adding a new integration test

### Checklist

1. Place the test in `xa-mass-mock/src/test/java/com/xa/mass/mock/api/`
2. Annotate with `@SpringBootTest(classes = MockApplicationSpringBootApp.class, webEnvironment = RANDOM_PORT)`
3. Add `@ActiveProfiles("dev")` and `@DirtiesContext`
4. Allocate a free WebSocket port at class load:
   ```java
   private static final int WEBSOCKET_PORT = findFreePort();

   @DynamicPropertySource
   static void registerProperties(DynamicPropertyRegistry registry) {
       registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
       // Only needed if auto-start=true:
       registry.add("mock.client.uri", () -> "ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
   }
   ```
5. Use the `waitForTaskStatus` polling pattern; set `maxAttempts` generously (20 attempts × 250 ms = 5 s)
6. Use `ReplayClient` (copy from `TaskApiMixedResultsIntegrationTest`) when you need per-message SUCCESS/FAILED control
7. Use Pattern C (`DeviceManager.addDevice` + `addToken`) when the test needs a device to appear after the task is created
8. Assert both the `task` and `messages` layers — a test that only checks `task.status=TERMINAL` misses the most interesting bugs

### Template

```java
@SpringBootTest(
    classes = MockApplicationSpringBootApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "mock.client.auto-start=false",
        "mock.client.devices-config=mock/test_mock_devices.json",
        "mass.mock.data.devices=mock/test_mock_devices.json",
        "mass.mock.data.tasks=mock/test_mock_tasks.json",
        "mass.mock.data.rules=mock/test_mock_rules.json"
    }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiYourScenarioIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    void yourScenario() throws Exception {
        // arrange, act, assert
    }

    // ... helpers from existing tests
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
