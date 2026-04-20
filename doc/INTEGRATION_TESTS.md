# Integration Test Guide

Last updated: 2026-04-17

This document is the active guide for integration tests in `xa-mass-dev-app`.
It is intentionally concise: structure, coverage shape, test patterns, and current gaps.

Use with:

- [E2E_BASELINE.md](./E2E_BASELINE.md)
- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)

## 1. Why This Layer Matters

This project has multiple runtime truths that only converge in integration tests:

- `Task` lifecycle
- `TaskMsg` lifecycle
- worker / worker-context assignment and release
- WebSocket dispatch and callback write-back
- task-level terminal reason and aggregate counters

Unit tests can verify local state-machine rules, but they do not prove that:

- task and message state converge together
- API transitions survive real runtime dispatch
- callbacks update persisted message state correctly
- worker and worker-context occupancy is released at the right time
- retry, replay, and delayed-assignment paths do not strand tasks

For this repository, integration tests are the mainline acceptance layer.

## 2. Test Structure

All active integration tests live under:

```text
xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/
```

Current domain layout:

- `lifecycle/`
- `assignment/`
- `results/`
- `audit/`
- `support/`

Shared base:

- `xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest.java`

Common runtime shape:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` against the real `MockApplicationSpringBootApp`
- real HTTP calls through Spring test infrastructure
- real WebSocket gateway usage when dispatch or callback behavior is under test
- isolated test context with `@DirtiesContext`
- free WebSocket port injected via `@DynamicPropertySource`

## 3. Fixture Baseline

Frequently used fixture files:

| Property | Typical fixture |
|---|---|
| `mock.client.workers-config` | `mock/test_mock_workers.json` |
| `mass.mock.data.workers` | `mock/test_mock_workers.json` or `mock/test_mock_workers_empty.json` |
| `mass.mock.data.worker-contexts` | `mock/test_mock_worker_contexts.json` or `mock/test_mock_worker_contexts_empty.json` |
| `mass.mock.data.tasks` | `mock/test_mock_tasks.json` |
| `mass.mock.data.rules` | `mock/test_mock_rules.json` |

Frequently used test properties:

- `mock.client.auto-start=true`: boot-time mock workers connect automatically
- `mock.client.auto-start=false`: tests connect workers manually
- `mock.client.task-result-status=FAILED`: mock clients return failed task results
- `mock.client.retry-attempts=1`: keeps retry noise low in logs

Important request-contract note:

- `textContent` is no longer a top-level task field
- if needed, pass it via `sharedConfig: {"textContent": "..."}`
- unknown top-level fields fail fast
- prefer the task-creation helpers in `AbstractMockE2eTest`

## 4. Running The Tests

Run the mock integration layer and supporting modules:

```bash
./mvnw -pl xa-mass-dev-app -am clean test
```

Run the full repository regression:

```bash
./mvnw clean test
```

Run the currently focused regression subset:

```bash
mvn -pl xa-mass-dev-app -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,WorkerManualDebugChatIntegrationTest,MassApplicationLoadMockDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected result:

- `BUILD SUCCESS`
- no failed tests

## 5. Current Coverage Shape

Lifecycle coverage:

- `TaskApiIntegrationTest`
- `TaskApiLifecycleGuardsIntegrationTest`
- `TaskApiPauseCompletionIntegrationTest`
- `TaskApiResumeAndCompleteIntegrationTest`
- `TaskApiTerminateRunningIntegrationTest`

Assignment and capacity coverage:

- `TaskApiDelayedWorkerAvailabilityIntegrationTest`
- `TaskApiMultiTaskAssignmentIntegrationTest`
- `TaskApiMinimumWorkerGateIntegrationTest`
- `TaskApiMultiRoundDispatchIntegrationTest`
- `TaskApiSingleWorkerReuseIntegrationTest`
- `TaskApiTerminateReuseIntegrationTest`
- `TaskApiWorkerContextAttributeRoutingIntegrationTest`
- `TaskApiWorkerWithoutContextIntegrationTest`

Result and callback coverage:

- `TaskApiFailureResultIntegrationTest`
- `TaskApiMixedResultsIntegrationTest`
- `TaskApiCallbackReplayIntegrationTest`

Audit coverage:

- `TaskApiStateValidationIntegrationTest`

Support and worker-debug coverage:

- `WorkerManualDebugChatIntegrationTest`

Important support coverage outside the E2E package:

- `TaskManagerLifecycleTest`
- `TaskAssignWorkerTest`
- `SimpleTaskMsgAssignListenerTest`
- `WorkerMatchContextTest`
- `QLExpressRuleEvaluatorTest`
- `RuleBasedTaskWorkerMatchingStrategyTest`
- `GatewayTaskResultHandlerTest`
- `MassWebSocketClientImplTest`
- `WebSocketClientStarterTest`

## 6. What The Current Integration Surface Proves

The active integration layer proves these mainline properties:

- create, approve, assign, run, and terminal completion work through the real runtime
- failure-path result write-back still converges to terminal closure
- reject/approve, pause/resume, running terminate, and delete guard are covered through real HTTP paths
- paused tasks can still close to `TERMINAL` after real callbacks arrive
- duplicate callback replay is idempotent
- late callbacks after manual terminal closure are ignored
- state audit is externally visible through `GET /status/api/tasks/{taskId}`
- worker-context attribute routing works end to end
- stateless workers can execute tasks that do not require worker-context routing
- same worker slot is reusable after both normal completion and manual terminate
- `minRequiredWorkerCount` acts as a real `READY -> RUNNING` gate
- multi-round refill works when `batchSize` is lower than total target count
- `READY` tasks without a current match are retried instead of silently orphaned
- manual worker debug chat is visible end to end, including outbound queueing, inbound acknowledgement, and delivery-state promotion

## 7. State Machine Coverage Map

Task paths currently pinned:

- `NEW -> READY -> RUNNING -> TERMINAL`
- `NEW -> BLOCKED -> READY`
- `NEW -> READY -> PAUSED -> READY`
- `READY/RUNNING -> BLOCKED`
- `RUNNING -> PAUSED -> callback -> TERMINAL`
- `RUNNING -> TERMINAL -> delete`

Task message paths currently pinned:

- `INIT -> ASSIGNED -> RUNNING -> SUCCESS`
- `INIT -> ASSIGNED -> RUNNING -> FAILED`
- duplicate final callback replay keeps the first final state

Terminal metadata currently pinned:

- `ALL_MESSAGES_SUCCEEDED`
- `ALL_MESSAGES_FAILED`
- `MIXED_MESSAGE_RESULTS`
- invalid terminal metadata surfaces through `stateValidation`

## 8. Common Test Patterns

Pattern A: auto-started mock workers

- best for happy path and broad convergence coverage
- use `mock.client.auto-start=true`
- let the runtime own worker connect, dispatch, callback, and close

Pattern B: manual gateway replay client

- best for callback replay, mixed results, and other write-back edge cases
- use `mock.client.auto-start=false`
- connect a dedicated WebSocket client and send crafted `TASK/step` results

Pattern C: delayed worker registration

- best for proving `READY` backlog retry and start-gate semantics
- seed zero workers, approve first, then register/connect a worker later

Pattern D: snapshot polling

- use the shared task snapshot helpers instead of sleep-heavy assertions
- poll task state and message state together
- assert persisted runtime truth, not only intermediate listener calls

Pattern E: debug-side-channel verification

- use real `POST /status/workers/send-message`
- poll `GET /status/workers/message-history` until both outbound and inbound records appear
- assert the protocol boundary separately from `TaskMsg` lifecycle

## 9. Current Missing Or Weak Areas

Still worth adding or strengthening:

- cancel from `RUNNING` via HTTP API
- cancel from `READY` via HTTP API
- worker disconnect during in-flight execution
- explicit `EXPIRED` `TaskMsg` path under real runtime conditions
- stronger `batchSize > 1` multi-worker coverage
- resume short-circuit path where `PAUSED` task is already complete underneath

## 10. Adding A New Integration Test

Before writing a new test, decide:

- which runtime risk is not already pinned
- whether the path must use real WebSocket dispatch
- whether auto-started workers or manual replay is the right technique
- which task and message states must be asserted at the end

Checklist:

1. Put the test under the correct `com.xa.mass.mock.e2e` domain package.
2. Reuse `AbstractMockE2eTest` helpers unless the test proves the helper itself is wrong.
3. Assert both task-level and message-level truth.
4. Assert terminal metadata when terminal semantics are involved.
5. If the change affects traces or state-machine semantics, update the baseline docs too.

Keep new tests focused:

- one risk path per class unless the scenarios are tightly coupled
- prefer explicit scenario names over generic smoke-test naming
- avoid duplicating a happy path just to vary fixture values

## 11. Compression Rule

This file is a guide, not a changelog dump.

Keep it useful by:

- grouping tests by domain and coverage intent
- avoiding one long prose section per test class unless the scenario is unusually tricky
- recording what the suite proves, not every assertion line by line
- moving normative lifecycle rules to `STATE_MACHINE_BASELINE.md`
- moving release-gate minimums to `E2E_BASELINE.md`

