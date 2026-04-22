# XA Mass Platform Agent Handoff

This is the fastest entry point for coding agents. Keep it short. Use linked baseline docs for details.

## 0. TL;DR

- XA Mass Platform is a general distributed task scheduling platform.
- Core abstraction: assign work-item inputs to online workers, track each result, and converge task state.
- The stable kernel is `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- Transport should be read as three channels: task dispatch, result ingest, and system events.
- Current reference runtime is `Worker + WorkerContext + WebSocket gateway + mock clients`, but WebSocket is an adapter path, not the product definition.
- Polling/pull workers are part of the intended mainline direction, not a side feature.
- Workers may be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- Project direction is library/SDK-first; HTTP pages and demo APIs are validation shells.
- Real Spring Boot entrypoint is `xa-mass-dev-app`.
- Embedded runtime composition lives in `xa-mass-sdk` under `com.xa.mass.starter.*`.
- Do not treat `MassApplication` or other embedded runtime classes as Spring Boot apps.
- Mainline acceptance is E2E/integration-test-driven first; unit tests are support coverage.
- Code and verified runtime behavior outrank docs.

## 1. Navigation

Read in this order when you need more detail:

1. [README.md](README.md)
2. [doc/README.md](doc/README.md)
3. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
4. [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md)
5. [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md)
6. [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md)
7. [doc/VERIFIED_RUNBOOK.md](doc/VERIFIED_RUNBOOK.md)
8. [doc/INTERNAL_API_REFERENCE.md](doc/INTERNAL_API_REFERENCE.md)
9. [doc/INTEGRATION_TESTS.md](doc/INTEGRATION_TESTS.md)
10. [doc/engine/POLICY_INTERACTION_BASELINE.md](doc/engine/POLICY_INTERACTION_BASELINE.md)
11. [doc/engine/TASK_EXECUTION_FLOW.md](doc/engine/TASK_EXECUTION_FLOW.md)

Trust order:

1. Code
2. Verified runtime behavior
3. This handoff
4. Active docs under `doc/`
5. Module README files
6. Older refactor notes only after re-verification

## 2. Platform Guardrails

- Do not shrink the product definition back into a phone/group-control system.
- `Worker`, `WorkerContext`, and WebSocket are current adapter names, not final universal platform boundaries.
- Do not define a worker as "a WebSocket client"; define it as an executor that can receive tasks, return results, and emit system events through some transport.
- Prefer transport-neutral worker hints such as `realtime` and `polling` in `Worker.onlineStrategy`; concrete protocol names like `websocket` are adapter-specific compatibility values.
- `WorkerContext` is optional; stateless workers are part of the verified mainline.
- UI, mock data, and demo APIs must not redefine kernel semantics.
- Third-party embedding should enter through `MassSdk` and `MassSdkApplication`; `unwrap()` and direct engine/manager access are deprecated escape hatches.
- `Task.sharedConfig` and `TaskMsg.input/output` are the generic payload boundaries.
- `target` is only a conventional key inside `TaskMsg.input`, not a model field.
- Create contract uses `inputs: List<Map<String,Object>>`; do not reintroduce `targetList`.
- Routing truth should come from explicit rules and worker-context signals, not from `workerGroupId`.
- Worker matching truth is `RuleDefinition.content` evaluated by QLExpress over `WorkerMatchContext`; legacy JSON-DSL generation is mock/dev fixture support only.
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels only.
- `Worker.status` is the online truth; lock truth lives in `WorkerStorage` / `WorkerManager.isLocked(...)`.
- Current concurrency model is conservative: one `Worker` is one active execution lane.
- Keep transport-specific shapes behind `xa-mass-transport-api`; WebSocket payloads must not become kernel truth.
- Treat the embedded transport server as an adapter selected by runtime composition; do not hardcode WebSocket server construction into new kernel paths.
- Manual worker debug chat is a side-channel and must not mutate task lifecycle state.
- Policy layers must not silently change another layer's source of truth; use [doc/engine/POLICY_INTERACTION_BASELINE.md](doc/engine/POLICY_INTERACTION_BASELINE.md) before adding matching, retry, release, refill, intake, or terminal-policy behavior.

## 3. Core Lifecycle

Task lifecycle:

```text
NEW --approve--> READY --assign--> RUNNING --terminal policy--> TERMINAL
NEW --reject--> BLOCKED --approve--> READY
READY/RUNNING --block--> BLOCKED
READY/RUNNING --pause--> PAUSED --resume--> READY
any non-TERMINAL --cancel/terminate--> TERMINAL
```

Task rules:

- Keep task closure modeled as `TaskStatus.TERMINAL + terminalReason`.
- Do not split task status into `SUCCEEDED / FAILED / CANCELLED` without a kernel redesign.
- `BLOCKED` requires `holdReason`; non-`BLOCKED` tasks must not carry one.
- `Task.intakeStatus` is the append-window truth; `openEnded` is only the create/accessor projection.
- Open-intake tasks do not auto-close from normal message convergence until sealed.

TaskMsg and attempt rules:

- `TaskMsg` is the logical work item.
- `TaskMsgAttempt` is the execution-history truth.
- A `TaskMsg` may have `0..N` attempts and at most one active attempt.
- `TaskMsg.latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are latest-attempt projections only.
- Worker/gateway callbacks must resolve one active attempt; do not synthesize legacy attempts.
- `taskMessageAttemptClosed` and `taskMessageLogicallyFinal` are separate events.
- Retryable failure closes the attempt but does not make the logical message stably final.

## 4. Modules

Root reactor modules are defined by `pom.xml`:

- `xa-mass-core`: shared models, enums, JSON DSL, EventBus, messaging primitives.
- `xa-mass-transport-api`: transport-neutral runtime SPI for task dispatch, result ingest, system events, transport servers, and worker endpoint registries.
- `xa-mass-engine`: task lifecycle, assignment, matching, result handling, validation.
- `xa-mass-gateway`: current WebSocket transport adapter, sessions, dispatch, inbound result routing.
- `xa-mass-sdk-api`: stable SDK-facing catalog, auth, and request-model contracts.
- `xa-mass-web`: REST controllers and the backend-hosted control console shell.
- `xa-mass-sdk`: consumer-facing SDK plus embedded runtime composition.
- `xa-mass-dev-app`: verified Spring Boot entry and E2E validation shell.

Historical top-level modules such as `xa-mass-base`, `xa-mass-starter`, and old archive/v2 engines are not active modules.

## 5. Runtime Entry

Verified Boot entry:

- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`

Verified endpoints:

- `http://localhost:8088/`
- `http://localhost:8088/tasks`
- `http://localhost:8088/resources/workers`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088/ws`
- Pull-style workers can run without any transport server through `MassSdkApplication.pullWorker(...)`; `pollingWorker(...)` remains as a compatibility alias.

Control-console routing note:

- the backend-hosted SPA routes above are the primary operator entrypoints
- `/status`, `/status/tasks`, `/status/workers`, `/status/rules`, and `/config` are redirect aliases only; do not treat them as the product surface

Default startup facts:

- HTTP port is `server.port`, currently `8088`.
- WebSocket port is `mass.websocket.port`, currently `18088`.
- Default Spring profile is `local`.
- `application-local.yml` is for local development.
- `application-dev.yml` is for CI/integration tests.
- `mock.client.auto-start=true` starts mock WebSocket clients after `ApplicationReadyEvent`.

Windows note:

- Prefer Maven commands for verification.
- If starting manually, keep classpaths short; expanded dependency classpaths can exceed Windows limits.

## 6. API And Payload Contract

Supported task create fields:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `inputs`
- `routingCode`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`
- `maxRuntimeSeconds`

Task update is metadata-only and allowed only for `NEW` or `BLOCKED`.

Update supports:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `routingCode`
- `batchSize`

Unsupported retired fields must fail fast.

Task message read model:

- `GET /status/api/tasks/{taskId}` returns `items` from persisted `TaskMsg.input`.
- `GET /status/api/tasks/{taskId}/messages` centers on `input` and `output`.
- Top-level target projections are not part of the read model.
- Execution history belongs to `TaskMsgAttempt`, not `TaskMsg.latestAttempt*`.

## 7. Regression Gate

Mainline acceptance should include integration/E2E coverage through `xa-mass-dev-app`.

Kernel and transport rule:

- use `xa-mass-dev-app` E2E coverage for shell + HTTP + current WebSocket adapter behavior
- use `xa-mass-sdk` integration coverage for transport-neutral or pull/poll worker paths until they become Boot-shell mainline

Common fast checks:

```bash
./mvnw -q -DskipTests compile
./mvnw -q test-compile
```

Targeted API/engine/SDK examples:

```bash
./mvnw --% -q -pl xa-mass-sdk-api -am -Dtest=ProjectEventCatalogRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --% -q -pl xa-mass-web -am -Dtest=TaskApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --% -q -pl xa-mass-engine -am -Dtest=TaskManagerLifecycleTest,SimpleTaskMsgAssignListenerTest,TaskResourceReleaseListenerTest,TaskWorkerAssignListenerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --% -q -pl xa-mass-sdk -am -Dtest=MassSdkTest,GatewayTaskMsgPublisherTest,GatewayTaskResultHandlerTest,MassApplicationBootstrapCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Representative E2E subset:

```bash
./mvnw --% -q -pl xa-mass-dev-app -am -Dtest=TaskApiIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,TaskApiFailureResultIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Use [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md) and [doc/INTEGRATION_TESTS.md](doc/INTEGRATION_TESTS.md) for the full expected coverage shape.

## 8. Trace Expectations

Critical lifecycle and debugging events include:

- `TASK_STATUS_TRANSITION`
- `TASK_MSG_STATUS_TRANSITION`
- `TASK_MSG_ATTEMPT_STATUS_TRANSITION`
- `TASK_MSG_ATTEMPT_CLOSED`
- `TASK_MSG_LOGICALLY_FINAL`
- `TASK_PROGRESS_SNAPSHOT`
- `TASK_TERMINAL_CLOSED`
- `ASSIGNMENT_SUMMARY`
- `ASSIGNMENT_QUEUE_SNAPSHOT`
- `RESOURCE_RELEASED`
- `WORKER_LOCK_RELEASED`
- `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`

When lifecycle behavior changes, update code, tests, [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md), [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), and [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md) together.

## 9. Files Worth Opening Early

Task lifecycle:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskStateValidator.java`

Assignment and worker matching:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskWorkerAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

Runtime and gateway:

- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/GatewayTaskMsgPublisher.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/GatewayTaskResultHandler.java`
- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/server/WebSocketServerImpl.java`
- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/dispatcher/ServerMessageDispatcher.java`

Public/control-console API:

- `xa-mass-web/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-web/src/main/java/com/xa/mass/api/internal/FrontendConsoleController.java`
- `xa-mass-web/src/main/java/com/xa/mass/api/internal/WorkerDebugController.java`

Models:

- `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsgAttempt.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/Worker.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/WorkerContext.java`

## 10. Working Rule

Before changing behavior:

- Verify the current code path.
- Prefer E2E or integration coverage for lifecycle changes.
- Keep docs concise and current.
- Delete stale refactor notes instead of preserving confusing history in active paths.
- Do not recreate removed archive/v2 code.
