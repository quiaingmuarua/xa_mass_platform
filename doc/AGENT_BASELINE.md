# XA Mass Platform Agent Baseline

This document keeps only the stable baseline facts that coding agents need first:

- platform definition and architectural guardrails
- current module and boundary truth
- current lifecycle and payload summary
- what to trust when docs and runtime disagree

It intentionally does not duplicate run commands, detailed endpoint inventories, or full protocol examples.

For those, use:

- [../AGENTS.md](../AGENTS.md)
- [./GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)

## 1. Truth Order

When code, runtime behavior, and docs disagree, use this order:

1. Actual code paths
2. Verified runtime behavior
3. [../AGENTS.md](../AGENTS.md)
4. [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
5. [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
6. [./E2E_BASELINE.md](./E2E_BASELINE.md)
7. This file
8. Module READMEs
9. Older notes only after confirming the referenced files still exist locally

Working rule:

- verify old READMEs and architecture notes against current code before using them
- update active docs after confirming runtime truth
- keep mainline docs current; delete stale history unless it explains a live operational constraint

## 2. Platform Definition

- The project is a general distributed task scheduling platform.
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The platform is scenario-agnostic. It does not care what the business payload means; it cares about who is online, who can accept work, dispatch, result write-back, and task convergence.
- The long-term stable kernel is `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- The current mainline still validates that kernel through a WebSocket adapter path using `Worker + WorkerContext + WebSocket gateway + mock clients`, but WebSocket is no longer the intended universal worker definition.
- The platform direction is transport-agnostic: task dispatch, result ingest, and worker system events should remain explicit seams rather than being encoded into one transport shape.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Worker`, `WorkerContext`, WebSocket sessions, the control console shell, and demo REST APIs are current reference adapters and verification shells. They are not the permanent product boundary.
- The project direction is library/SDK-first. Demo runtime surfaces exist to validate the kernel, not to redefine it.

## 3. Platform Model

| Abstract concept | Concrete type | Notes |
| --- | --- | --- |
| Worker | `Worker` | Current worker adapter. Examples include phone, crawler, LLM agent, and IM bot. |
| Worker context | `WorkerContext` | Optional capability or credential context. Stateless workers do not require one. |
| Work item | `TaskMsg` | Mainline message unit with `input: Map<String,Object>` and `output: Map<String,Object>`. |
| Shared config | `Task.sharedConfig` | Platform-level dispatch config merged into each downstream dispatch payload. |

Interpretation rules:

- the abstract concepts are the stable architecture boundary
- the concrete types are the current reference scenario and default adapters
- future worker forms should extend these abstract slots instead of shrinking the platform back into `worker/workerContext` vocabulary
- mock/runtime loading does not auto-create fallback worker contexts; a worker with no explicit `workerContexts` stays stateless
- SDK-first worker resource creation is the preferred path: use `WorkerRegistration` / `WorkerContextRegistration` through `MassSdkApplication.registerWorker(...)` and `registerWorkerContext(...)`; registration does not imply online state
- SDK project/event metadata is registered through `MassSdkApplication.registerProject(...)` and `registerEventDefinition(...)`; enabled project registration also extends the core runtime project registry used by task and worker-context validation
- `ResourceOperations` is the SDK project/event control-plane interface
- `EventDefinition.code` is the globally unique capability identity for SDK/runtime dispatch, worker capability declarations, and permission checks
- `EventDefinition.projectCodes` is scope metadata only; it constrains where an event may be invoked but is not part of the event identity
- SDK submitter registration is currently a minimal in-memory credential binding for task submission identity; do not treat it as a complete user/security subsystem
- SDK submitter list/get operations expose submitter metadata only; raw credentials are accepted on registration and consumed by authentication, not returned as resource read models

## 4. Model Boundaries

Boundary rules:

- each boundary layer may have its own models
- each boundary layer should have one canonical truth
- model names should expose the layer they belong to
- avoid the same class name meaning different things in different modules
- transport metadata, protocol frame metadata, and business payload should not silently share ownership of the same fields

Current canonical boundaries:

- HTTP API boundary
  - canonical response envelope: `com.xa.mass.api.model.ApiResponse<T>`
  - canonical request contract: typed controller-edge request models
  - task requests live under `com.xa.mass.api.model.task.*`
  - worker requests live under `com.xa.mass.api.model.worker.*`
- SDK boundary
  - canonical public task-create requests: `MassTaskCreateRequest` and `MassTaskRequest`
  - canonical public capability definition: `EventDefinition`
  - `EventDefinition.code` is globally unique capability identity
  - engine DTOs are internal conversion targets, not public SDK surface
- mock command boundary
  - canonical command response envelope: `com.xa.mass.command.model.CommandResponse<T>`
  - this is process-local command/runtime shape, not HTTP API response contract
- gateway transport boundary
  - canonical queue/transport wrapper: `com.xa.mass.gateway.queue.Envelope`
  - `Envelope` owns delivery metadata such as `rawJson`, queue target, and trace metadata
- gateway protocol boundary
  - canonical protocol frame: `com.xa.mass.gateway.model.massMessage.MassMessage`
  - canonical protocol header companion: `MessageContext`
  - `msgType + subMsgType` classifies a wire frame only; it is not business/control capability identity
- protocol payload helper boundary
  - `MessageAckPayload` is only for transport/protocol acknowledgement, not a general response model

## 5. Architectural Guardrails

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy.
- `Worker` is the current worker adapter name, not the permanent universal name for all worker/resource forms. Read it as the current concrete `Worker` implementation.
- `WorkerContext` is optional worker context. Not every future worker model must require one.
- The active API is explicitly `0..n`: do not reintroduce single-context helper APIs keyed only by `workerId`; use `getWorkerContexts(...)` or `getWorkerContextById(...)`.
- `WorkerContext.workerId` is the single owner truth; attachment APIs should accept the `WorkerContext` object itself rather than duplicating the owner `workerId` as a second parameter.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress back to single-purpose top-level fields such as `textContent`.
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate. Do not push project/user identity back into `sharedConfig`, `TaskMsg.input`, or attribute bags.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals, not from `workerGroupId`.
- Worker matching truth is `RuleDefinition.content` evaluated by QLExpress over `WorkerMatchContext`; the legacy JSON-DSL generator is mock/dev fixture support only.
- typed JSON DSL mainline goes through `JsonDslParser -> JsonDslDefinition -> JsonDslProcessorEngine` using canonical fields like `uniqueId`, `description`, `context.model`, `fieldDsl`, and `combineDsl`
- legacy/mock JSON DSL such as root `MODEL` / `COUNT` / `FIELDS` belongs to `JsonDslEngine` compatibility usage only and should not be mixed into typed parser examples or contracts
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels for matching and diagnostics only. They are not lifecycle, lock, or online truth.
- `addWorker(...)` and `addWorkerContext(...)` remain compatibility/high-control SDK seams for core-model callers; new resource scenarios should use SDK registration models instead.
- UI pages, mock runtime, and demo APIs must not redefine the platform kernel.
- Manual worker debug chat is a debug/control side-channel. It is not `TaskMsg` lifecycle and must not mutate task state.
- new or changed policy seams must keep ownership explicit across matching, attempt, release, refill, intake, control, and terminal decisions; use [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md) before extending those paths

## 6. Mainline Reality

- The real Spring Boot entry is `xa-mass-dev-app`.
- `xa-mass-sdk` is the consumer-facing dependency entry for third-party embedding.
- `xa-mass-sdk-api` holds the stable SDK-facing catalog, auth, and request-model types shared with HTTP surfaces.
- Embedded runtime composition now lives inside `xa-mass-sdk` under `com.xa.mass.starter.*`; it is not the primary Boot entry.
- `xa-mass-dev-app` should obtain runtime capability through `xa-mass-sdk`; its explicit `xa-mass-web` dependency is only for the current REST/control-console validation shell.
- Do not make `xa-mass-sdk` depend on `xa-mass-web` just to make `xa-mass-dev-app` depend on one internal artifact; SDK consumers should not pull demo web surfaces by default.
- The current mainline reactor is defined by the root `pom.xml`: `xa-mass-web`, `xa-mass-core`, `xa-mass-transport-api`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-sdk-api`, `xa-mass-sdk`, `xa-mass-dev-app`.
- `xa-mass-transport-api` now holds the transport-neutral SPI for task dispatch, result ingest, system events, transport servers, and worker endpoint registries.
- `xa-mass-gateway` should be read as the current WebSocket transport adapter, not as the only valid worker runtime path.
- Read [./GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md) before changing `xa-mass-gateway` or `xa-mass-transport-api`.
- Gateway tuple routing such as `MessageType + subMsgType` is a protocol-frame compatibility seam only; do not treat it as the identity of a business or control capability.
- `com.xa.mass.engine` is the active engine path.
- historical `v2` / archive engine generations are no longer present in the current repository snapshot.
- EventBus mainline has converged onto `com.xa.mass.base.channel.eventbus.core` and `com.xa.mass.base.channel.eventbus.event`.
- Mainline acceptance is end-to-end integration-test-driven through `xa-mass-dev-app`; unit tests are support coverage, not the primary acceptance gate.
- The current worker debug side-channel is exposed through `POST /status/workers/send-event` and `GET /status/workers/message-history`.

## 7. Current Contract Summary

Task and payload summary:

- task creation has one HTTP route: `POST /status/api/tasks`
- task create/update field details live in [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- `project` and `userId` are required business bindings on create
- `inputs` is the only supported create shape for work-item materialization
- `PUT /status/api/tasks/{taskId}` is metadata-only and only valid while `NEW` or `BLOCKED`
- `Task.project` and `Task.user` are canonical aggregate bindings
- `Task.sharedConfig` is the task-level generic payload/config map
- `TaskMsg.input` and `TaskMsg.output` are the per-item payload boundary
- `TaskMsgAttempt` is the attempt-level audit and callback snapshot truth
- `Task.intakeStatus` is the append-window truth; `openEnded` is the projection
- public create/update/read contracts do not define a dedicated routing-code field
- worker runtime capability truth is explicit `supportedEventCodes`; `supportedProjects` is only a coarse filter hint

Current lifecycle summary:

Verified mainline lifecycle:

```text
NEW --approve--> READY --pause--> PAUSED --resume--> READY
 |                  |                                     |
 +--reject-------> BLOCKED --approve--------------------> +
 |                                                        |
 +--cancel/terminate-----------------------------------> TERMINAL

READY --assign--> RUNNING --all task messages final--> TERMINAL
```

Important current rules:

- task completion is driven by persisted `TaskMsg` finality, not only by the visible task status
- paused tasks must still close to `TERMINAL` once all persisted callbacks are final
- no-match assignment attempts are retryable backlog, not terminal dequeue
- assignment must not dispatch if a task leaves `READY` during the matching window
- late callbacks must not mutate a task already closed to `TERMINAL`
- `Task.terminalReason` is required to interpret why a task ended
- `BLOCKED` has two distinct intents that must stay separate at the API layer:
  - review rejection uses `rejectTask` for `NEW -> BLOCKED`
  - runtime/manual blocking uses `blockTask` for `READY/RUNNING -> BLOCKED`

## 8. WorkerContext And Matching Baseline

- `WorkerContextStatus` is domain-neutral: `IDLE`, `RESERVED`, `OCCUPIED`, `BLOCKED`, `INVALID`
- `WorkerContext.project` is the first-class project/resource binding for account-like contexts; do not hide project ownership only inside attributes
- `WorkerMatchContext` exposes `workerAttributes` and `workerContextAttributes` to rule evaluation
- `WorkerMatchContext` also exposes `workerContextProject` and `workerContextProjectMatchesTaskProject`
- `WorkerMatchContext` also exposes `hasWorkerContext` and `taskHasRoutingRequirement`
- `RuleDefinition.content` is the canonical rule expression; `expression` and `desc` remain compatibility aliases, not separate rule truths
- `isWorkerContextAvailable` now means truly free for new assignment (`IDLE` and not expired)
- `isWorkerContextUsable` is the broader diagnostic signal (`IDLE` / `RESERVED` / `OCCUPIED`, excluding expired, blocked, and invalid contexts)
- new matching rules should prefer explicit worker-context signals such as `workerContextProject`, `workerContextRoutingTags`, and `workerContextAttributes`; do not reintroduce a frontend or API-level routing-code model field
- a `WorkerContext` is optional in the active platform model: workers without one can still run tasks that do not require worker-context-specific routing
- `Worker.status` is the single online truth
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`

## 9. Worker Debug Summary

- primary control-plane debug path is `POST /status/workers/send-event`
- message history path is `GET /status/workers/message-history`
- current adapter bridge is event-first `CONTROL/event -> CONTROL/event`
- this is a debug/control side-channel, not task execution or task audit truth
- detailed payload and acknowledgement notes live in [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

## 10. Recommended Entry Files

For startup/runtime:

- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-transport-api/src/main/java/com/xa/mass/transport/channel/TaskDispatchChannel.java`
- `xa-mass-transport-api/src/main/java/com/xa/mass/transport/channel/TaskResultIngestChannel.java`
- `xa-mass-transport-api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java`

For lifecycle/API:

- `xa-mass-web/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

For payload and matching:

- `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

## 11. Guardrails For Future Agents

Use these positive defaults:

- start from the real entrypoint and current call sites
- check the root `pom.xml` before treating a top-level directory as active mainline code
- verify API docs against controller DTOs and integration tests before changing request or response contracts
- treat `Worker / WorkerContext / WebSocket` as current adapter vocabulary, not as the platform's final universal resource model
- re-check whether historical files exist locally before treating older notes as actionable code paths
- treat documented capabilities as unverified until code, tests, or runtime behavior prove they are live
- add or update regression coverage before changing behavior
- update the short state-machine, trace, and E2E baselines when lifecycle semantics change
- sync active docs after verified behavior changes
- keep archive material under archive paths instead of active source trees
