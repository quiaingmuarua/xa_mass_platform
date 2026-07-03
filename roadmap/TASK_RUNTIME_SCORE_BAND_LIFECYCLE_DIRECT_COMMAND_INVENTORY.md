# Task Runtime Score-Band Lifecycle Direct Command Inventory

Status: seed inventory and baseline contract ledger for
[TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md](TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md).

This file is the working cut-line inventory for removing engine from task
lifecycle command ownership. It must be completed before implementation starts.
Rows here are not explanatory notes: they define the current closure ledger for
old-path ownership, public surface classification, acceptance targets, and guard
targets. Changing target classifications or closure modes is a baseline
contract revision for this roadmap.

Inventory rule: every task-related symbol must justify why it exists. It must
end in exactly one of these target classifications:

```text
external TaskCommandPort
external TaskReadViewPort
task-runtime internal mechanism
engine assembly-only handle
keep as descriptor metadata only
test fixture only
delete
```

The inventory must not turn old engine ports into a new public port family.
Scheduling, claim, result apply, retry, lease repair, finality, retention, and
score evaluation are internal mechanisms unless a row proves a real external
caller and owner boundary.

`external TaskReadViewPort` means the approved read projection surface hosted by
`sdk/xa-mass-task-runtime-starter-sdk` for this roadmap. It does not live in
`xa-mass-task-runtime` core and does not make starter the read truth owner.

Rows marked `seed` are current observations from the initial scan and must be
rechecked in TRLC-0 before code changes.

Phase rule: rows whose closure mode is `split/delete`, `delete/replace`, or
`delete/reroute` are roadmap acceptance or guard-freeze targets unless the same
slice also proves the replacement owner path. They are not pre-converge cleanup
by default.

## Current Command Chain

```text
sdk/xa-mass-embedded-sdk MassApplication / MassSdkApplication
  -> xa-mass-engine-starter MassEngine
  -> xa-mass-engine TaskCommandPort
  -> xa-mass-engine TaskManager
  -> xa-mass-engine TaskLifecycleService
  -> xa-mass-engine TaskRuntimeServingLane
  -> xa-mass-task-runtime ports
```

This is the chain to close. The target is not to add another bridge around it.
The target is to make embedded SDK task writes call the task-runtime starter
command handle directly.

## Dependency Inventory

| Module/file | Current dependency fact | Target classification | Status |
| --- | --- | --- | --- |
| `sdk/xa-mass-embedded-sdk/pom.xml` | depends on `xa-mass-engine-starter`; does not currently declare direct `xa-mass-task-runtime-starter-sdk` dependency | add direct starter dependency for task command/read-view access; do not rely on engine-starter as the command or read route | seed |
| `xa-mass-engine-starter/pom.xml` | depends on `xa-mass-task-runtime-starter-sdk` and `xa-mass-engine` | keep for scheduling/dispatch assembly; not the embedded SDK lifecycle command owner | seed |
| `sdk/xa-mass-task-runtime-starter-sdk/pom.xml` | depends on `xa-mass-task-runtime`, memory/Redis task-runtime implementations, and Lettuce | correct host for runtime bootstrap and approved external read-view handle; exposes runtime-owned command handle, starter-hosted read projection handle, and internal engine assembly handles only when needed | seed |
| `sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/TaskReadOperations.java` | current SDK-owned public read contract | migration residue; replace with approved starter-hosted `TaskReadViewPort` or keep only with same-slice deletion target | seed |
| `xa-mass-task-runtime/README.md` | states task-runtime owns accepted backlog, score visibility, claim, result finality, active-lease repair, progress, discard, and final-result reads | extend with score-band lifecycle command ownership when implemented | seed |
| `xa-mass-kernel-spi/src/main/java/com/xa/mass/kernel/spi/task/TaskShellRuntimeStore.java` / `TaskShellRuntimeLifecycleQuery.java` | runtime-facing storage SPI used by engine `TaskManager` construction | not a TRLC replacement landing zone; converge separately through `TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md` and do not expand during TRLC | seed |

## Surface Collapse Inventory

| Surface bucket | Allowed visibility | Examples | Non-examples |
| --- | --- | --- | --- |
| External command | embedded SDK/server validated command path | create, append, seal, approve, pause, block, resume, cancel | claim, retry repair, score scan, diagnostics |
| External read-view | standalone embedded SDK/server read path hosted by `sdk/xa-mass-task-runtime-starter-sdk` and projected over owner truth | task view, runtime view, result view, diagnostics, snapshots | mutation, score repair, lifecycle transition, runtime-core API pollution, manager-hosted read facade, embedded-sdk-api duplicate contract |
| Task-runtime internal mechanism | package/internal backend or runtime classes | score evaluation, candidate scan, backlog claim, result apply, lease repair, retry promotion | public starter/API port family |
| Engine assembly-only handle | only engine scheduling/dispatch wiring | candidate acquisition + claim handle passed during starter assembly | embedded SDK or server controller dependency |
| Descriptor metadata | task identity/contract/project/intake metadata | shell record, submitter/project fields, audit metadata | dispatch eligibility truth |
| Delete | no owner boundary or only old-path residue | same-purpose bridge, broad query port, status-to-score sync adapter | real external command/read surface |

## Lifecycle Write Surface Inventory

| Symbol | Current role | Current owner path | Target owner | Closure mode | Status |
| --- | --- | --- | --- | --- | --- |
| `MassApplication#createTaskShell` / SDK equivalent | embedded caller task create | delegates through `MassEngine` / engine command path | task-runtime-starter lifecycle command handle plus descriptor metadata write | reroute then guard old path | seed |
| `MassApplication#appendTaskItems` / SDK equivalent | embedded caller append | delegates through `MassEngine.appendTaskItems` -> `TaskCommandPort` | task-runtime lifecycle append command; accepted backlog plus score update if gate open | reroute then guard old path | seed |
| `MassApplication#sealTask` / SDK equivalent | embedded caller intake close | delegates through engine command path | task-runtime-starter command writes append-gate metadata; no lifecycle owner state | reroute then guard old path | seed |
| `MassApplication#approveTask` / SDK equivalent | dispatch admission command | delegates through engine command path | task-runtime score/gate open transition | reroute then guard old path | seed |
| `MassApplication#rejectTask` / SDK equivalent | manual rejection command | delegates through engine command path | task-runtime blocked/terminal/discard policy command, as decided by contract | reroute then guard old path | seed |
| `MassApplication#blockTask` / SDK equivalent | manual block command | delegates through engine command path | task-runtime blocked parked score/gate | reroute then guard old path | seed |
| `MassApplication#pauseTask` / SDK equivalent | manual pause command | delegates through engine command path | task-runtime paused parked score/gate | reroute then guard old path | seed |
| `MassApplication#resumeTask` / SDK equivalent | resume dispatch command | delegates through engine command path | task-runtime open gate plus owner-local score recompute | reroute then guard old path | seed |
| `MassApplication#cancelTask` / SDK equivalent | manual cancel command | delegates through engine command path | task-runtime terminal/discard convergence plus descriptor metadata projection | reroute then guard old path | seed |
| `MassApplication#terminateTask` / SDK equivalent | manual terminal command | delegates through engine command path | task-runtime terminal convergence plus descriptor metadata projection | reroute then guard old path | seed |
| `MassEngine` task command methods | starter-facing task command facade | delegates to engine `TaskCommandPort` | delete from lifecycle command path or keep only deprecated-internal during one slice with guard | delete/reroute | seed |
| `EngineConfig#getTaskCommandPort` / `taskCommandPort` | exposes engine task command surface | constructs `TaskManager` command surface | no terminal role; embedded SDK command path uses task-runtime starter, and any descriptor helper is a separately named owner | delete/reroute | seed |
| `TaskCommandPort` in `xa-mass-engine` | create/lifecycle/append/seal surface | implemented by `TaskManager` | not a lifecycle owner; replace with task-runtime-starter command surface and remove engine command path | delete | seed |
| `TaskManager implements TaskCommandPort` | broad engine task facade | calls `TaskLifecycleService` | no terminal role; migrate useful methods to descriptor metadata store/helper, task-runtime command, read-view projection, convergence, or engine scheduling orchestration | delete | seed |
| `EngineConfig.ensureTaskManager()` / production `new TaskManager(...)` | constructs the broad engine task owner and leaks it into starter/kernel wiring | no terminal role; starter/engine assembly must wire the real owners directly | delete | seed |
| `TaskLifecycleService` | status lifecycle and intake implementation | writes `TaskStatus`, calls runtime sync, publishes ready | delete as lifecycle owner; move validation/metadata only if needed | split/delete | seed |
| `TaskRuntimeServingLane#appendRuntimeIngressItems` | engine adapter appends runtime backlog then updates score | called by `TaskLifecycleService` through `TaskManager` | task-runtime lifecycle append command | move out of engine command path | seed |
| `TaskRuntimeServingLane#updateSchedulerEligibility` | maps engine `TaskStatus` to `RuntimeGate` and score | hidden sync from engine lifecycle to runtime score | delete mapping; command writes score directly | delete/replace | seed |

## Scheduling And Dispatch Inventory

These rows may stay in engine, but they must consume task-runtime truth instead
of engine lifecycle truth. They are not external task interfaces.

| Symbol | Current role | Target role | Required check | Status |
| --- | --- | --- | --- | --- |
| `EngineRuntimeKernel` | starts assignment, runtime-ready pump, lease watchdog, event listeners | scheduling/dispatch runtime only | must not require engine task command owner for lifecycle writes | seed |
| `RuntimeReadyDispatchPump` | discovers runtime-ready tasks and requests dispatch | may stay as scheduling loop | consumes task-runtime score candidates only | seed |
| `SimpleTaskDispatchBinder` | claims ready backlog after worker selection | stay in engine scheduling/dispatch | no `TaskStatus` admission dependency | seed |
| `TaskAssignmentRuntimePort#claimReady` | claim port into runtime | internal runtime mechanism or engine assembly-only handle, not public port | only accepts selected worker reservation evidence and score candidate | seed |
| `TaskResultIngestPort` / result facade | result ingress and finality outcome consumer | internal result/finality mechanism or engine assembly-only handle | no task lifecycle command ownership | seed |
| `TaskLeaseMaintenancePort` | expired lease repair and resource release reads | internal maintenance mechanism or engine assembly-only handle | no shell status lifecycle owner | seed |

## Status And Projection Inventory

| Symbol/pattern | Current risk | Target | Status |
| --- | --- | --- | --- |
| `TaskStatus` imports in engine scheduling/assignment | dispatch behavior may read shell status as lifecycle truth | replace behavior reads with task-runtime score/progress/active-lease facts | seed |
| `Task.transitionTo(...)` and `Task.setStatus(...)` in engine lifecycle tests | tests preserve mutable engine lifecycle truth | rewrite tests around task-runtime command transitions and read projections | seed |
| `TaskStateResolver` / `TaskStateValidator` | may blend shell status and runtime progress | become read projection only | seed |
| `TaskWorkerAssignListener#onTaskAssign` READY/RUNNING admission | concrete dispatch gate reads shell `TaskStatus` before runtime-ready work and worker selection | replace with task-runtime score candidate / active-work facts | seed |
| `TaskWorkerAssignListener` READY -> RUNNING mutation after dispatch | concrete dispatch path writes shell status as lifecycle truth | delete as dispatch truth; public RUNNING becomes projection from active leases/progress | seed |
| `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING filter | runtime-ready pump filters tasks by shell `TaskStatus` | replace with task-runtime score candidate filtering | seed |
| `TaskReadOperations` | current SDK read facade in `sdk/xa-mass-embedded-sdk-api` | replace with starter-hosted standalone `TaskReadViewPort`: read-only projection over descriptor and task-runtime facts; must not live in `xa-mass-task-runtime` core, be hosted by `TaskManager`, or route through manager bridge wrappers | seed |
| public `TaskStatus` response fields | useful API vocabulary, dangerous owner truth | projection only; no dispatch reads | seed |

## Target Score-Band Map

This table records the first target mapping. Names may change, but the owner
direction must not.

| Input | Score/gate target | Descriptor metadata target | Notes |
| --- | --- | --- | --- |
| create | non-dispatchable initial score/gate | create identity/contract/project metadata | create does not imply dispatch admission |
| append while open | accepted backlog; schedulable score if ready work exists | optional append audit/count metadata | append owns backlog truth, not shell status |
| append while sealed | reject before runtime | no lifecycle change | sealed is append gate only |
| seal | no required score change | intake closed | idle close is a separate policy/convergence decision |
| approve | open gate and due score when work is ready/due | optional approval metadata/audit | approval is a score transition, not shell lifecycle |
| block/reject | blocked parked score/gate | optional reason/audit metadata | reason is product/control evidence, not lifecycle owner |
| pause | paused parked score/gate | optional reason/audit metadata | active leases may continue or timeout by runtime policy |
| resume | open gate and owner-local score recompute | optional audit metadata | no engine status-to-score sync |
| cancel/terminate | terminal/discard gate and runtime close/discard as contract defines | terminal metadata projection | runtime finality/progress remains task-runtime owned |
| result/lease/retry | task-runtime convergence updates score/progress/finality | trace/projection consumers only | engine consumes outcomes but does not own lifecycle |

## Old Path Closure Checks

TRLC implementation must add source guards or architecture tests for these
negative cases:

- embedded SDK task write methods call `MassEngine` lifecycle command methods
- embedded SDK task write methods call `EngineConfig.getTaskCommandPort()`
- embedded SDK task write methods use raw `TaskRuntimePortSet`
- non-assembly callers use public raw `TaskRuntimeHandle.runtime()` or raw
  `TaskRuntimePortSet`
- public starter or SDK surface exposes task-related ports outside
  `TaskCommandPort` / `TaskReadViewPort`
- `TaskReadViewPort` is implemented by `TaskManager`, `MassEngine`, or a
  bridge-only wrapper around manager reads
- `TaskReadViewPort` is exposed from `xa-mass-task-runtime` core
- same-shape read contracts remain beside the approved starter-hosted
  `TaskReadViewPort` or in `sdk/xa-mass-embedded-sdk-api` without a deletion
  target
- production source still contains `class TaskManager`,
  `EngineConfig.ensureTaskManager()`, or `new TaskManager(...)`
- a renamed replacement class combines task command, query, shell lifecycle
  maintenance, state runtime, and runtime serving-lane assembly
- task-runtime starter lifecycle command surface imports `xa-mass-engine`
- task-runtime public ports import SDK DTOs, transport types, server HTTP
  types, Redis implementation classes, or engine `Task`
- engine lifecycle code maps `TaskStatus` to `RuntimeGate` for command writes
- scheduling admission reads `TaskStatus.READY` or `TaskStatus.RUNNING` as
  dispatch truth
- read/diagnostic paths mutate score as a repair side effect

## First Slice Candidate

First implementation slice should be the smallest non-serving owner proof unless
it also includes result/lease/finality convergence. It may prove candidate and
claim mechanics, but it must not route production traffic into a path that
creates active leases without closing the same-path result/repair owner.

```text
embedded SDK append/approve/seal smoke path
  -> task-runtime-starter lifecycle command handle
  -> memory task-runtime backend
  -> non-serving engine scheduling runtime discovers score candidate
  -> worker-runtime selection/reservation
  -> task-runtime claim
```

This first slice is only complete if it also closes the matching old embedded
SDK -> engine command route for the selected methods. A proof that the new path
works while the old path remains live is not convergence.

Serving cutover rule: if the first slice is production-visible and creates an
active lease, it must extend the proof to:

```text
claim -> dispatch -> result apply / lease expiry -> retry/finality
  -> projected terminal/read view
```

Otherwise it remains non-serving proof.

## TRLC-0 Hard Decisions

- exact command type names and package placement
- descriptor metadata write owner, allowed descriptor fields, and deletion
  target for any temporary engine metadata writer
- raw `TaskRuntimeHandle.runtime()` closure mode: delete public getter, narrow
  it, or guard it with engine-starter/loop-context allowlist
- exact deletion plan for `MassEngine` task command methods
- exact deletion/split plan for `TaskLifecycleService`
- exact replacement for current `TaskWorkerAssignListener` and
  `RuntimeReadyDispatchPump` shell-status gates
- server route reroute order after embedded SDK command path is proven
