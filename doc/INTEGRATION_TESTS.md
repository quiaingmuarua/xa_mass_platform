# Integration Test Guide

Last updated: 2026-04-23

This file is the active map of the integration-test layer in `xa-mass-dev-app`. It exists to answer four questions quickly:

- where the E2E tests live
- what runtime risks they cover
- how to run the important subsets
- what still is not pinned well enough

Use with:

- [E2E_BASELINE.md](./E2E_BASELINE.md)
- [STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)

## 1. Why This Layer Is The Acceptance Gate

The integration layer is the mainline acceptance gate because it is the first place where these truths must converge together:

- task lifecycle
- logical `TaskMsg` lifecycle
- `TaskMsgAttempt` creation/closure
- worker and worker-context assignment/release
- gateway dispatch and callback write-back
- task-level counters, terminal reasons, and audit output

Unit tests still matter, but they do not prove that the full runtime path converges correctly.

## 2. Layout

Active integration tests live under:

```text
xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/
```

Current domain packages:

- `lifecycle/`
- `assignment/`
- `results/`
- `audit/`
- `console/`
- `support/`

Shared base:

- `xa-mass-dev-app/src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest.java`

Common runtime shape:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- real `MockApplicationSpringBootApp`
- real HTTP paths through Spring test infrastructure
- real WebSocket gateway when push/callback behavior matters
- real pull-style worker path through `MassSdkApplication.pullWorker(...)` when server push is not the behavior under test
- isolated contexts via `@DirtiesContext`
- dynamic WebSocket port injection via `@DynamicPropertySource`

## 3. Fixture Baseline

Frequently used fixture properties:

| Property | Typical fixture |
|---|---|
| `mock.client.workers-config` | `mock/test_mock_workers.json` |
| `mass.mock.data.workers` | `mock/test_mock_workers.json` or `mock/test_mock_workers_empty.json` |
| `mass.mock.data.worker-contexts` | `mock/test_mock_worker_contexts.json` or `mock/test_mock_worker_contexts_empty.json` |
| `mass.mock.data.tasks` | `mock/test_mock_tasks.json` |
| `mass.mock.data.rules` | `mock/test_mock_rules.json` |

Frequently used runtime switches:

- `mock.client.auto-start=true`: workers connect automatically at boot
- `mock.client.auto-start=false`: tests control worker connect timing manually
- `mock.client.task-result-status=FAILED`: mock workers send failed results
- `mock.client.retry-attempts=1`: keeps retry noise low

Fixture guidance:

- startup/bootstrap data in `xa-mass-dev-app` is loaded through `MassRuntimeControl` / `MassSdkApplication`
- prefer SDK capability methods such as `addWorker(...)`, `addWorkerContext(...)`, `replaceDefaultRules(...)`, and `createTask(...)` for new E2E setup code
- keep direct `TaskManager`, `WorkerManager`, and `RuleManager` fixture access only for focused white-box assertions or fault injection

Contract reminders:

- `textContent` is not a top-level task field; pass it inside `sharedConfig`
- task creation uses `inputs`, not retired target-list fields
- unknown top-level task fields fail fast
- prefer the task helper methods from `AbstractMockE2eTest`

## 4. Commands

Run the integration layer and required modules:

```bash
./mvnw -pl xa-mass-dev-app -am clean test
```

Run the full repository regression:

```bash
./mvnw clean test
```

Run the focused high-signal subset:

```bash
mvn -pl xa-mass-dev-app -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,WorkerManualDebugChatIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected result:

- `BUILD SUCCESS`
- no failed tests

## 5. Coverage Map

Lifecycle:

- `TaskApiIntegrationTest`
- `TaskApiLifecycleGuardsIntegrationTest`
- `TaskApiPauseCompletionIntegrationTest`
- `TaskApiResumeAndCompleteIntegrationTest`
- `TaskApiTerminateRunningIntegrationTest`

Assignment and capacity:

- `TaskApiDelayedWorkerAvailabilityIntegrationTest`
- `TaskApiMultiTaskAssignmentIntegrationTest`
- `TaskApiMinimumWorkerGateIntegrationTest`
- `TaskApiMultiRoundDispatchIntegrationTest`
- `TaskApiSingleWorkerReuseIntegrationTest`
- `TaskApiTerminateReuseIntegrationTest`
- `TaskApiWorkerContextAttributeRoutingIntegrationTest`
- `TaskApiWorkerWithoutContextIntegrationTest`
- `PollingWorkerTaskFlowIntegrationTest`
- `TransportChannelWiringIntegrationTest`

Results and callbacks:

- `TaskApiFailureResultIntegrationTest`
- `TaskApiMixedResultsIntegrationTest`
- `TaskApiCallbackReplayIntegrationTest`

Audit:

- `TaskApiStateValidationIntegrationTest`

Control-console shell:

- `ControlConsoleRoutingIntegrationTest`

Worker debug side-channel:

- `WorkerManualDebugChatIntegrationTest`
- `WorkerManualDebugCommandIntegrationTest`
- `WorkerManualDebugDisconnectIntegrationTest`

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

## 6. What This Surface Currently Proves

- create, approve, assign, dispatch, result write-back, and terminal convergence work through the real runtime
- failure-path result write-back still converges to terminal closure
- reject/approve, pause/resume, running terminate, and delete guards are covered through real HTTP flows
- paused tasks can still close to `TERMINAL` after real callbacks arrive
- duplicate callback replay is idempotent
- late callbacks after manual terminal closure are ignored
- worker-context attribute routing works end to end
- stateless workers can run tasks that do not require worker-context routing
- the same worker slot can be reused after normal completion and manual termination
- `minRequiredWorkerCount` is a real `READY -> RUNNING` gate
- multi-round refill works when `batchSize` is lower than total work-item count
- `READY` tasks without a current match are retried instead of being orphaned
- manual worker debug chat is visible end to end without touching `TaskMsg` lifecycle
- manual worker debug chat can execute `mock.*` commands and returns structured command acknowledgements
- `mock.disconnect` is verified to acknowledge first and then take the worker offline
- task detail exposes `stateValidation` so runtime state audit is observable externally
- backend-hosted SPA shell routes return the built console shell through the real Spring Boot entry
- legacy `/status*` and `/config` console aliases redirect to the primary SPA routes instead of serving a separate page system

## 7. Pinned Mainline Paths

Task paths:

- `NEW -> READY -> RUNNING -> TERMINAL`
- `NEW -> BLOCKED -> READY`
- `NEW -> READY -> PAUSED -> READY`
- `READY/RUNNING -> BLOCKED`
- `RUNNING -> PAUSED -> callback -> TERMINAL`
- `RUNNING -> TERMINAL -> delete`

Task-message paths:

- `INIT -> ASSIGNED -> RUNNING -> SUCCESS`
- `INIT -> ASSIGNED -> RUNNING -> FAILED`
- duplicate final callback replay keeps the first final state

Terminal metadata:

- `ALL_MESSAGES_SUCCEEDED`
- `ALL_MESSAGES_FAILED`
- `MIXED_MESSAGE_RESULTS`
- invalid terminal metadata is exposed through `stateValidation`

## 8. Common Test Patterns

Auto-started workers:

- best for broad happy-path convergence coverage
- use `mock.client.auto-start=true`

Manual replay client:

- best for callback replay, mixed results, and write-back edge cases
- use `mock.client.auto-start=false`

Delayed worker registration:

- best for backlog retry and start-gate semantics
- approve first, connect workers later

Snapshot polling:

- assert persisted task and message truth together
- avoid sleep-heavy assertions

Debug-side-channel verification:

- use `POST /status/workers/send-message`
- poll `GET /status/workers/message-history`
- keep debug protocol assertions separate from `TaskMsg` lifecycle assertions

Control-console routing verification:

- point `mass.frontend.dist-path` at a test fixture build directory
- assert `/`, `/tasks`, and `/resources/workers` return the SPA shell through real HTTP
- assert `/status`, `/status/tasks`, `/status/workers`, `/status/rules`, and `/config` remain redirect aliases only

## 9. Weak Or Missing Areas

- cancel from `RUNNING` via HTTP API
- cancel from `READY` via HTTP API
- worker disconnect during in-flight execution
- stronger real-runtime `EXPIRED` message coverage
- broader `batchSize > 1` multi-worker coverage
- resume short-circuit where a paused task is already complete underneath

## 10. Rule For New Tests

When adding a new integration test:

1. Put it in the correct `com.xa.mass.mock.e2e` domain package.
2. Reuse `AbstractMockE2eTest` helpers unless the helper itself is under test.
3. Assert both task-level and message-level truth.
4. Assert terminal metadata when terminal semantics are part of the risk.
5. Update baseline docs if the change affects lifecycle, trace, or API semantics.

Keep each new class focused on one runtime risk unless multiple scenarios are inseparable.
