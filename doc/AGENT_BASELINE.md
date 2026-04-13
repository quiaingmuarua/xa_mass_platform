# XA Mass Platform Agent Baseline

This document keeps only the stable baseline facts that coding agents need first:

- real module responsibilities
- mainline versus historical directory boundaries
- what to trust when docs and runtime disagree
- current unresolved convergence gaps

It intentionally does not duplicate run commands, verification logs, or daily investigation notes.

For those, use:

- [../AGENTS.md](../AGENTS.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)

## 1. Truth Order

When code, runtime behavior, and docs disagree, use this order:

1. Actual code paths
2. Verified runtime behavior
3. [../AGENTS.md](../AGENTS.md)
4. This file
5. Module READMEs
6. `doc/archive/` historical material

Working rule:

- do not assume old READMEs or architecture notes still describe the live path
- update active docs after confirming runtime truth
- keep historical explanation in archive paths instead of letting it leak back into mainline docs

## 2. Mainline Reality

- The real Spring Boot entry is `xa-mass-mock`
- `xa-mass-runtime` is a composition layer, not the primary Boot entry
- The current mainline reactor is defined by the root `pom.xml`: `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-runtime`, `xa-mass-mock`
- `xa-mass-base` and `xa-mass-starter` still exist as top-level directories but are not current root-reactor modules
- The project is library/SDK-first; backend pages and HTTP endpoints are validation surfaces
- API-first task flow is the current mainline truth
- `com.xa.mass.engine` is the active engine path
- `xa-mass-engine/archive/v2/` is historical experiment code, not the current mainline
- EventBus mainline has converged onto `com.xa.mass.base.channel.eventbus.core` and `com.xa.mass.base.channel.eventbus.event`
- API integration coverage includes terminate-from-running and delete-after-terminal after real assignment but before downstream callbacks
- API integration coverage also proves that paused tasks can still close to terminal when real callbacks arrive after pause
- `TaskManager.createTask()` is now fail-fast in the mainline runtime: it requires a materialized `targetList`, rejects non-empty `targetJsonList`, rejects unsupported `project` codes, and preserves request `batchSize`
- Engine regression coverage now locks two important closure rules:
  - paused tasks must close to terminal once all persisted message callbacks are final
  - ready tasks without a current device match must stay in the assignment loop through retry
- Engine regression coverage also locks two race/immutability rules:
  - assignment must not dispatch if a task leaves `READY` during device matching
  - late callbacks must not mutate a task that was already closed to `TERMINAL`
- `Task` now carries `terminalReason`, so `TERMINAL` can be interpreted as manual cancel, all-success completion, all-failed completion, or mixed-result completion
- `TaskManager.validateTaskState(...)` now gives an explicit state-audit result for `Task + TaskMsg` consistency and whether a non-final task still needs terminal closure

## 3. Module Facts

### `xa-mass-mock`

- real Spring Boot shell
- wires `api + runtime + gateway + engine`
- best place for end-to-end lifecycle verification

### `xa-mass-runtime`

- lifecycle and composition layer
- builds `MassApplication`, `MassEngine`, and `MassGateway`
- not the main `spring-boot:run` target

### `xa-mass-api`

- REST controllers, status pages, and DTO layer
- loaded through `xa-mass-mock`
- not the independently verified application entry

### `xa-mass-engine`

- active business-logic module
- state-machine correctness, assignment, and rule management live here
- matching policy extension seam is `TaskDeviceMatchingStrategy`
- do not route new work into archived `v2`

### `xa-mass-core`

- shared models, enums, messaging abstractions, JSON DSL, and event bus code
- Maven module is `xa-mass-core`
- Java package names intentionally remain under `com.xa.mass.base`
- the active EventBus code now lives under `channel.eventbus.core` and `channel.eventbus.event`
- do not infer active module ownership from package names alone

### `xa-mass-gateway`

- WebSocket server, routing, and session context
- validated as part of the full mock runtime path

## 4. Convergence Conclusions

### Startup

- start from `xa-mass-mock`
- do not infer runtime entry from `xa-mass-runtime`

### Task Lifecycle

- trust `TaskManager` and `TaskStatus` over older docs
- if a document disagrees with `TaskStatus.canTransitionTo(...)`, the document is stale
- task completion is driven by persisted `TaskMsg` finality, not only by the current task status label
- read terminal tasks as `TaskStatus + terminalReason`, not `TaskStatus` alone
- use `TaskManager.resumeTaskDetailed(...)` when the caller needs to distinguish `PAUSED -> READY` from `PAUSED -> TERMINAL`
- use `TaskManager.resolveTaskStateFromMessages(...)` when the caller needs an explicit message-aggregation verdict instead of relying on `updateTaskProgress()` side effects
- use `TaskManager.validateTaskState(...)` when the caller needs to audit whether counters, terminal reason, and persisted message aggregates are still self-consistent
- terminal closure freezes later non-final callbacks; duplicate results are only accepted as idempotent no-ops once the message is already final

### Matching

- task-to-device selection should extend through engine strategy interfaces
- `RuleBasedTaskDeviceMatchingStrategy` is the current default implementation
- a no-match assignment attempt should be treated as retryable backlog, not as a terminal dequeue
- a successful device match is still not enough to dispatch if the task status changed away from `READY` during the matching window

### Event Bus

- the current EventBus namespace is converged, but the active implementation remains Guava-backed
- inspect real call sites before making architecture claims

### Historical Code

- `xa-mass-engine/archive/v2/` is kept only as archive material
- it is outside the active source tree by design to reduce agent confusion
- the former `channel.eventbus.legacy` compatibility package has been removed from the active source tree

## 5. Known Gaps

- `SimpleTaskScheduler.scheduleTasks()` is still a stub
- app shutdown path is now Spring-managed and idempotent at the runtime layer, but single-interrupt behavior is not yet re-verified end-to-end
- EventBus naming is converged on the current core/event namespace, but Redis remains unimplemented
- Redis and Database storage are still fail-fast placeholders
- API integration coverage is improved but still not exhaustive for remaining cancel follow-up variants

## 6. Recommended Entry Files

For startup/runtime:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`

For lifecycle/API:

- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

For assignment/result handling:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskDeviceAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-gateway/src/main/java/com/xa/mass/gateway/dispatcher/ServerMessageDispatcher.java`

## 7. Guardrails For Future Agents

Do not assume, without re-verification:

- `xa-mass-runtime` is the only runnable entry
- `v2` is the active engine generation
- Redis-backed EventBus behavior exists in the active runtime path
- older API docs still match implementation exactly
- a documented capability is live just because it is written down

Better default behavior:

- start from the real entrypoint and current call sites
- check the root `pom.xml` before treating a top-level directory as active mainline code
- add or update regression coverage before changing behavior
- sync active docs after verified behavior changes
- keep archive material under archive paths instead of active source trees
