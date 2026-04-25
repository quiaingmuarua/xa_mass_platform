# XA Mass Platform Agent Baseline

This document keeps only the stable baseline facts that coding agents need first:

- platform definition and architectural guardrails
- current module and boundary truth
- current lifecycle and payload summary
- what to trust when docs and runtime disagree

It intentionally does not duplicate run commands, detailed endpoint inventories, or full protocol examples.

For those, use:

- [../AGENTS.md](../AGENTS.md)
- [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md)
- [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md)
- [./GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md)

## 1. Using This File

Use the canonical trust order in [../AGENTS.md](../AGENTS.md).
This file is the stable project baseline, not a higher-priority source than code, verified runtime behavior, or narrower owner docs such as state machine, trace, E2E, and gateway boundary baselines.

Working rule:

- verify old READMEs and architecture notes against current code before using them
- update active docs after confirming runtime truth
- keep mainline docs current; delete stale history unless it explains a live operational constraint

## 2. Platform Definition

- The project is a general distributed task scheduling platform.
- The core product problem is not "send work over one transport"; it is "match structured work items to heterogeneous, stateful executors, track each item result, and converge task-level state".
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The kernel value is the combination of `stateful worker + capability/routing match + per-item result tracking + task-level convergence`.
- The current code reality is still more object-heavy than the desired production-scale hot path; use [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md) for the approved compression target before expanding `Task`/`TaskMsg` hot-path responsibility.
- Adapter vocabulary note: current code still uses `Worker`, `WorkerContext`, and some WebSocket-named types for today's adapter surfaces. Read those names literally inside their current scope, but keep new cross-adapter boundaries transport-neutral.
- The platform is scenario-agnostic. It owns dispatch, result write-back, and task convergence rather than business payload meaning.
- The long-term stable kernel is `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- The platform direction is transport-agnostic: task dispatch, result ingest, and worker system events should remain explicit seams rather than being encoded into one transport shape.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
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
- gateway transport boundary
  - inbound mainline: `raw json + connection facts -> canonical seam`
  - outbound mainline: `canonical seam + explicit addressability -> raw json`
  - only retained gateway-local delivery record: `com.xa.mass.gateway.queue.OutboundDelivery`
  - `workerId` is the active transport addressability key; endpoint role lanes are no longer part of the gateway mainline
- gateway compatibility boundary
  - WebSocket task data-plane now uses canonical root-level task dispatch/result frames
  - WebSocket worker identity is established at handshake time; heartbeat is no longer an application JSON frame
  - manual worker debug is task-backed through `POST /status/api/tasks` plus `Task.sharedConfig.targetWorkerId`; gateway does not carry a separate control/debug protocol
  - any remaining legacy transport fields are diagnostics only; they are not business/control capability identity
  - `com.xa.mass.gateway.queue.WebSocketTransportFrameCodec` remains the adapter-local WebSocket shell codec; it is not a platform contract

## 5. Architectural Guardrails

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy.
- Prefer transport-neutral names and contracts for new cross-adapter boundaries.
- `WorkerContext` is optional worker context. Not every future worker model must require one.
- The active API is explicitly `0..n`: do not reintroduce single-context helper APIs keyed only by `workerId`; use `getWorkerContexts(...)` or `getWorkerContextById(...)`.
- `WorkerContext.workerId` is the single owner truth; attachment APIs should accept the `WorkerContext` object itself rather than duplicating the owner `workerId` as a second parameter.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress back to single-purpose top-level fields such as `textContent`.
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate. Do not push project/user identity back into `sharedConfig`, `TaskMsg.input`, or attribute bags.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals, not from `workerGroupId`.
- Worker matching truth is `RuleDefinition.content` evaluated by QLExpress over `WorkerMatchContext`; the legacy JSON-DSL generator is mock/dev fixture support only.
- typed JSON DSL mainline goes through `JsonDslParser -> JsonDslDefinition -> JsonDslProcessorEngine`
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels for matching and diagnostics only. They are not lifecycle, lock, or online truth.
- Prefer SDK registration models for new resource scenarios; low-level core-model mutation APIs are not the default path.
- UI pages, mock runtime, and demo APIs must not redefine the platform kernel.
- Manual worker debug now enters through normal task creation with explicit worker targeting in `Task.sharedConfig`; do not reintroduce a direct worker-control side-channel.
- new or changed policy seams must keep ownership explicit across matching, attempt, release, refill, intake, control, and terminal decisions; use [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md) before extending those paths

## 6. Mainline Reality

- The real Spring Boot entry is `xa-mass-dev-app`.
- `xa-mass-sdk` is the consumer-facing dependency entry for third-party embedding.
- `xa-mass-sdk-api` holds the stable SDK-facing catalog, auth, and request-model types shared with HTTP surfaces.
- Embedded runtime composition now lives inside `xa-mass-sdk` under `com.xa.mass.starter.*`; it is not the primary Boot entry.
- `xa-mass-dev-app` should obtain runtime capability through `xa-mass-sdk`; its explicit `xa-mass-web` dependency is only for the current REST/control-console validation shell.
- Do not make `xa-mass-sdk` depend on `xa-mass-web` just to make `xa-mass-dev-app` depend on one internal artifact; SDK consumers should not pull demo web surfaces by default.
- The current mainline reactor is defined by the root `pom.xml`: `xa-mass-web`, `xa-mass-core`, `xa-mass-transport-api`, `xa-mass-transport-polling`, `xa-mass-transport-runtime`, `xa-mass-engine`, `xa-mass-transport-websocket`, `xa-mass-sdk-api`, `xa-mass-sdk`, `xa-mass-testing`, `xa-mass-dev-app`.
- `xa-mass-transport-api` now holds the transport-neutral SPI for task dispatch, result ingest, system events, transport servers, and worker endpoint registries.
- `xa-mass-transport-polling` now holds the default pull/polling worker adapter implementation that `xa-mass-sdk` composes by default.
- `xa-mass-transport-runtime` now holds the shared transport runtime registry, dispatch listener, and task-result ingest channel used by `xa-mass-sdk`.
- `xa-mass-transport-websocket` should be read as the current WebSocket transport adapter artifact, not as the only valid worker runtime path. Its module sources live under `transport/websocket-adapter`, and its Java package namespace remains `com.xa.mass.gateway.*`.
- Read [./GATEWAY_BOUNDARY_BASELINE.md](./GATEWAY_BOUNDARY_BASELINE.md) before changing `xa-mass-transport-websocket` or `xa-mass-transport-api`.
- Gateway adapter frame classification is a protocol-frame compatibility seam only; do not treat it as the identity of a business or control capability.
- Gateway runtime wiring is configured as a fixed pre-start snapshot; `DispatchRuntimeContext` is not a mutable extension registry.
- `com.xa.mass.engine` is the active engine path.
- `xa-mass-testing` is the cross-cutting acceptance-tooling module for runnable `perf` plus the current SDK transport/concurrency probes and the first runnable WebSocket disconnect/reconnect chaos probe.
- EventBus mainline has converged onto `com.xa.mass.base.channel.eventbus.core` and `com.xa.mass.base.channel.eventbus.event`.
- Core acceptance is the combined `perf + concurrency + Boot-shell E2E` surface.
- Current runnable `perf` coverage lives in `xa-mass-testing`.
- Current runnable `concurrency` coverage lives in `xa-mass-engine`.
- Current runnable Boot-shell E2E coverage lives in `xa-mass-dev-app`.
- SDK embedded-runtime transport harnesses in `xa-mass-testing` are the fastest system probe when you need real SDK registration plus polling/WebSocket scheduling without booting the full dev-app shell.
- `chaos` now has an initial runnable SDK/WebSocket disconnect-reconnect probe in `xa-mass-testing`, but the lane should still be treated as scheduled or release-oriented until the suite is broader and stable.
- Concurrency coverage is a required acceptance lane for race-sensitive lifecycle changes; narrower unit/integration tests are support coverage for bug localization and invariants, not the primary acceptance gate.
- worker-targeted debug/task details live in [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md).

## 7. Current Contract Summary

Task and payload summary:

- task creation has one HTTP route: `POST /status/api/tasks`
- `project` and `userId` are required business bindings on create
- `inputs` is the only supported create shape for work-item materialization
- `PUT /status/api/tasks/{taskId}` is metadata-only and only valid while `NEW` or `BLOCKED`
- aggregate truth stays on `Task.project`, `Task.user`, and `Task.sharedConfig`
- per-item truth stays on `TaskMsg.input/output`; `TaskMsgAttempt` is the attempt-level audit snapshot
- `Task.intakeStatus` is the append-window truth; `openEnded` is only the create/read projection
- public create/update/read contracts do not define a dedicated routing-code field
- worker runtime capability truth is `supportedEventCodes`; `supportedProjects` is only a coarse filter hint
- exact HTTP fields and examples live in [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

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

- task completion is driven by persisted `TaskMsg` finality, not only by the visible task status; paused tasks may still close to `TERMINAL`
- no-match assignment is retryable backlog, not terminal dequeue; assignment must not dispatch if the task leaves `READY` during the matching window
- late callbacks must not mutate a task already closed to `TERMINAL`
- `Task.terminalReason` is required to interpret why a task ended
- `BLOCKED` has two distinct intents that must stay separate at the API layer:
  - review rejection uses `rejectTask` for `NEW -> BLOCKED`
  - runtime/manual blocking uses `blockTask` for `READY/RUNNING -> BLOCKED`

## 8. WorkerContext And Matching Baseline

- `WorkerContextStatus` is domain-neutral: `IDLE`, `RESERVED`, `OCCUPIED`, `BLOCKED`, `INVALID`
- `WorkerContext.project` is the first-class project/resource binding for account-like contexts; do not hide project ownership only inside attributes
- `WorkerMatchContext` is the canonical rule-evaluation shape; match logic should prefer explicit signals such as `workerAttributes`, `workerContextAttributes`, `workerContextProject`, `workerContextProjectMatchesTaskProject`, `hasWorkerContext`, and `taskHasRoutingRequirement`
- `RuleDefinition.content` is the canonical rule expression; `expression` and `desc` remain compatibility aliases, not separate rule truths
- `isWorkerContextAvailable` means truly free for new assignment; `isWorkerContextUsable` is only the broader diagnostic signal
- new matching rules should prefer explicit worker-context signals such as `workerContextProject`, `workerContextRoutingTags`, and `workerContextAttributes`; do not reintroduce a frontend or API-level routing-code model field
- `WorkerContext` is optional in the active platform model: workers without one can still run tasks that do not require worker-context-specific routing
- `Worker.status` is the single online truth
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`

## 9. Recommended Entry Files

- startup/runtime:
  - `xa-mass-dev-app/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
  - `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java`
- lifecycle/API:
  - `xa-mass-web/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
  - `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- payload/matching:
  - `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
  - `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`
  - `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

## 10. Guardrails For Future Agents

Use these positive defaults:

- start from the real entrypoint and current call sites
- check the root `pom.xml` before treating a top-level directory as active mainline code
- verify API docs against controller DTOs and integration tests before changing request or response contracts
- prefer transport-neutral contracts for new cross-adapter boundaries
- treat documented capabilities as unverified until code, tests, or runtime behavior prove they are live
- consult [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md) before extending compatibility or legacy seams
- add or update regression coverage before changing behavior
- update the short state-machine, trace, and E2E baselines when lifecycle semantics change
- sync active docs after verified behavior changes
