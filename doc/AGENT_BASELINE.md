# XA Mass Platform Agent Baseline

This document keeps only the stable baseline facts that coding agents need first:

- platform definition and architectural guardrails
- mainline versus historical directory boundaries
- current API and lifecycle contract
- what to trust when docs and runtime disagree

It intentionally does not duplicate run commands, verification logs, or detailed endpoint examples.

For those, use:

- [../AGENTS.md](../AGENTS.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

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
9. `doc/archive/` historical material

Working rule:

- do not assume old READMEs or architecture notes still describe the live path
- update active docs after confirming runtime truth
- keep historical explanation in archive paths instead of letting it leak back into mainline docs

## 2. Platform Definition

- The project is a general distributed task scheduling platform.
- Its core abstraction is: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The platform is scenario-agnostic. It does not care what the business payload means; it cares about who is online, who can accept work, dispatch, result write-back, and task convergence.
- The long-term stable kernel is `Task / TaskMsg / assignment / result / audit / terminal policy`.
- The current mainline validates that kernel through a long-connection worker scenario using `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Worker`, `WorkerContext`, WebSocket sessions, status pages, and demo REST APIs are current reference adapters and verification shells. They are not the permanent product boundary.
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

## 4. Architectural Guardrails

- Stable platform boundaries are `Task`, `TaskMsg`, assignment, result, audit, and terminal policy.
- `Worker` is the current worker adapter name, not the permanent universal name for all worker/resource forms. Read it as the current concrete `Worker` implementation.
- `WorkerContext` is optional worker context. Not every future worker model must require one.
- The active API is explicitly `0..n`: do not reintroduce single-context helper APIs keyed only by `workerId`; use `getWorkerContexts(...)` or `getWorkerContextById(...)`.
- `WorkerContext.workerId` is the single owner truth; attachment APIs should accept the `WorkerContext` object itself rather than duplicating the owner `workerId` as a second parameter.
- `Task.sharedConfig` and `TaskMsg.input/output` are the main payload boundaries. Do not regress back to single-purpose top-level fields such as `textContent`.
- Routing truth such as country/account affinity should come from explicit rules and worker-context signals, not from `workerGroupId`.
- `Worker.attributes` and `WorkerContext.attributes` are auxiliary rule labels for matching and diagnostics only. They are not lifecycle, lock, or online truth.
- UI pages, mock runtime, and demo APIs must not redefine the platform kernel.

## 5. Mainline Reality

- The real Spring Boot entry is `xa-mass-mock`.
- `xa-mass-runtime` is a composition layer, not the primary Boot entry.
- The current mainline reactor is defined by the root `pom.xml`: `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-runtime`, `xa-mass-mock`.
- `xa-mass-base` and `xa-mass-starter` still exist as top-level directories but are not current root-reactor modules.
- `com.xa.mass.engine` is the active engine path.
- `xa-mass-engine/archive/v2/` is historical experiment code, not the current mainline.
- EventBus mainline has converged onto `com.xa.mass.base.channel.eventbus.core` and `com.xa.mass.base.channel.eventbus.event`.
- Mainline acceptance is end-to-end integration-test-driven through `xa-mass-mock`; unit tests are support coverage, not the primary acceptance gate.

## 6. Current Task And Payload Contract

### Task create contract

`TaskManager.createTask()` and `POST /status/api/tasks` currently support only:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `targetList`
- `routingCode`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`

Behavior locked in the mainline:

- `targetList` must contain at least one materialized target
- unsupported `project` codes are rejected
- unknown JSON fields such as retired `targetJsonList`, `targetType`, and `extraParams` are rejected
- `batchSize` is preserved on the task and enforced as a per-device hard cap for each dispatch round
- `defaultMsgMaxRetryCount` defaults to `3`
- `openEnded=true` keeps the task open for runtime item append until sealed

### Task update contract

`PUT /status/api/tasks/{taskId}` is metadata-only and currently supports only:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `routingCode`
- `batchSize`

Update constraints:

- `targetList` and other unknown fields are rejected
- only `NEW` and `BLOCKED` tasks may be edited
- `BLOCKED` is not reject-only: `rejectTask` is `NEW -> BLOCKED`, while `blockTask` is the runtime path from `READY` or `RUNNING`

### Task and TaskMsg payload model

- `Task.sharedConfig` is the task-level generic payload/config map
- `TaskMsg.input` is the per-item input payload
- `TaskMsg.output` is the per-item output payload
- `TaskMsg.getTarget()` is only a backwards-compat accessor over `input["target"]`
- `POST /status/api/tasks/{taskId}/items` appends new `TaskMsg.input` records to an active open-ended task
- `PUT /status/api/tasks/{taskId}/seal` closes the append window for an open-ended task

## 7. Current Lifecycle Baseline

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
- `WorkerMatchContext` exposes `workerAttributes` and `workerContextAttributes` to rule evaluation
- `WorkerMatchContext` also exposes `hasWorkerContext` and `taskHasRoutingRequirement`
- `isWorkerContextAvailable` now means truly free for new assignment (`IDLE` and not expired)
- `isWorkerContextUsable` is the broader diagnostic signal (`IDLE` / `RESERVED` / `OCCUPIED`, excluding expired, blocked, and invalid contexts)
- `workerContextAttributes['country'] == taskRoutingCode` is the verified attribute-routing pattern
- a `WorkerContext` is optional in the active platform model: workers without one can still run tasks that do not require worker-context-specific routing
- `Worker.status` is the single online truth
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`

## 9. Recommended Entry Files

For startup/runtime:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`

For lifecycle/API:

- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

For payload and matching:

- `xa-mass-core/src/main/java/com/xa/mass/base/model/Task.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/model/TaskMsg.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`

## 10. Guardrails For Future Agents

Do not assume, without re-verification:

- `xa-mass-runtime` is the main runnable app
- `v2` is the active engine generation
- older API docs still match implementation exactly
- current `Worker / WorkerContext / WebSocket` names are the platform's only future resource model
- a documented capability is live just because it is written down

Better default behavior:

- start from the real entrypoint and current call sites
- check the root `pom.xml` before treating a top-level directory as active mainline code
- add or update regression coverage before changing behavior
- update the short state-machine, trace, and E2E baselines when lifecycle semantics change
- sync active docs after verified behavior changes
- keep archive material under archive paths instead of active source trees
