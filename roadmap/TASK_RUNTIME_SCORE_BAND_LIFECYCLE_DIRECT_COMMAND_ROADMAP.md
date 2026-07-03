# Task Runtime Score-Band Lifecycle Direct Command Roadmap

Status: active on 2026-07-02.

Current execution cursor:

- TRLC-2 / TRLC-3A first slice is implemented for
  approve/reject/block/pause/resume.
- TRLC-3B append cutover is implemented for the embedded SDK/MassApplication
  path: append calls task-runtime command handling, writes accepted backlog
  only, and never updates task score.
- TRLC-3B append/score separation is now enforced at the runtime contract
  layer: appending backlog without an existing score does not create a
  schedulable candidate, and remaining serving-lane append helpers are guarded
  against score writes.
- SBL score-band core is implemented and is the single score contract consumed
  by this roadmap. TRLC must not redefine task score ranges or reintroduce
  append/result/retry score refresh.
- TRLC-3B create cutover is implemented for the embedded SDK/MassApplication
  path: create writes descriptor metadata/read projection outside `TaskManager`
  and initializes runtime with a positive non-schedulable score.
- TRLC-3C cancel/terminate cutover is implemented for the embedded
  SDK/MassApplication path: terminal commands call task-runtime command
  handling, discard runtime work, and write negative terminal score.
- TRLC-6 read-view entry is partially implemented: `TaskReadViewPort` now lives
  in `sdk/xa-mass-task-runtime-starter-sdk`, `EngineTaskReadOperations`
  implements it, and embedded SDK read calls consume `MassApplication.taskReadView()`.
  Server task read controllers consume `TaskReadViewPort`, and old starter
  `TaskReadOperations` accessors are removed. `TaskReadOperations` remains only
  as the SDK facade/interface shape and is not yet physically removed.
- `pause` is a no-argument lifecycle event. Runtime maps it to its configured
  scheduler-hold timestamp; callers do not pass pause duration or pause-until
  time. `TaskRuntimeContractShapeTest` guards that the public command port
  exposes only `pause(String taskId)`.
- `reject` is a terminal negative-score transition. `block` is the positive
  non-schedulable manual hold.
- physical engine lifecycle code deletion, full result/lease/finality owner
  closure, and final read facade cleanup remain later slices.
- TRLC-5 is partially advanced at the runtime API level:
  `closeIfDrained(...)` now has shared Redis/memory contract coverage. Memory no
  longer returns a stubbed `false`; both implementations prove mutable work
  blocks closure and final rows can remain while dispatch visibility is closed.
  `TaskRuntimeServingLane` calls runtime closure only after task terminal-policy
  resolution reaches `FINALIZED_TO_TERMINAL` / `ALREADY_FINAL`; session/open
  tasks that merely drain mutable work keep their lifecycle open. Tests prove
  the serving append -> claim -> result path closes sealed batch runtime score,
  while session append remains backlog-only until a score owner explicitly
  writes the next due score.
- TRLC-4 is partially advanced in the assignment queue: `TaskAssignWorker` no
  longer gates assignment processing on shell `TaskStatus.READY` /
  `TaskStatus.RUNNING`; it only drops terminal shell projections. Runtime score
  candidate discovery remains the dispatch eligibility source.
- `TaskWorkerAssignListener` no longer lets shell `READY -> RUNNING`
  projection failure cancel already-bound dispatch. Worker selection plus
  dispatch binding decide assignment success; shell RUNNING remains projection
  residue.
- TRLC-4 is further advanced in allocation/refill policy:
  `DefaultAssignmentAllocationPolicy` uses runtime active-worker count, not
  shell `READY`, to decide whether the min-start gate applies;
  `DefaultAssignmentRefillPolicy` uses runtime-ready work, not shell
  `RUNNING`, to request replenishment. Both keep terminal shell projection only
  as a defensive stop.
- TRLC-4 dispatch wakeup is narrowed: `TaskRuntimeServingLane.requestTaskDispatch`
  no longer rewrites scheduler eligibility or checks shell `isActive()`.
  Wakeup publishes for non-terminal projections and lets runtime score/ready
  facts decide whether dispatch can actually proceed.
- TRLC-5 retry rescore is closed by SBL: retry/result paths no longer write the
  next live task score. Retry promotion moves item state only, and score
  visibility remains owned by lifecycle/score-owner transitions and explicit
  scheduling trigger.

Contract impact: baseline/roadmap execution contract revision. This file does
not change Java runtime behavior by itself, but changes the task-runtime owner
boundary, public surface rules, slice classification, stop conditions, and
completion criteria that future implementation must satisfy. Treat edits here
as baseline contract changes, not ordinary explanatory documentation.

Mainline anchor:

```text
task-runtime lifecycle command capability
  <- embedded SDK/server command adapters
  -> sdk/xa-mass-task-runtime-starter-sdk command handle
  -> xa-mass-task-runtime lifecycle state machine
  -> task meta + score-band + backlog/work/result truth
  -> engine scheduling consumes score candidates only
```

Read-view anchor:

```text
task read-view capability
  <- embedded SDK/server read adapters
  -> sdk/xa-mass-task-runtime-starter-sdk read-view handle
  -> projection over descriptor metadata + task-runtime owner truth
```

This roadmap exists to close the remaining engine task lifecycle owner path.
It is not a `TaskManager` split roadmap, not a view cleanup roadmap, and not a
new wrapper/facade roadmap.

The target is that task lifecycle writes do not need to call engine at all.
Engine may still own worker selection bridge, worker-runtime evidence usage,
matching/worker reservation, assignment orchestration, transport dispatch
binding, and result-ingest/pull consumption. For task lifecycle, engine is a
consumer of task-runtime scheduling candidates and result/finality outcomes,
not the command owner.

`TaskManager` has no terminal role. Existing methods must be challenged:
delete them when they encode old design, or reimplement the minimal behavior in
the real owner when a current invariant proves it is still needed. Do not
preserve a renamed or narrower `TaskManager` shell.
This roadmap does not physically delete production engine classes. Completion
requires the new mainline to stop entering `TaskManager`,
`TaskLifecycleService`, engine `TaskCommandPort`, or
`EngineConfig.ensureTaskManager()` for task lifecycle commands and scheduling
truth. Physical deletion of the remaining engine code is deferred to
`ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md` after the mainline cutover
is proven.

Serving cutover invariant: implementing the task-runtime score-band mechanism
is not enough. For each cutover command family, the production-reachable
embedded SDK path must enter the task-runtime starter command handle directly.
If `MassApplication` / `MassSdkApplication` can still route the selected
lifecycle command through `MassEngine`, engine `TaskCommandPort`,
`TaskManager`, or `TaskLifecycleService`, that command family is not on the
new mainline yet. Keeping the old engine classes physically present is allowed
only as frozen residue for commands that are not yet cut over.

This roadmap is not a code relocation plan. Keeping external SDK/server route
semantics stable is allowed; preserving the internal engine design is not. A
cutover must delete, replace, or reimplement behavior according to the target
owner model. It must not transplant `TaskLifecycleService`, `TaskManager`, or
status-to-score-sync logic into task-runtime, starter, or another renamed
class as an intermediate "move first, clean later" step.

Design source rule: target task interfaces are derived from task-runtime
capabilities, not from current `MassSdkApplication` inheritance, current
`TaskReadOperations`, `TaskStore`/`TaskShellStore`, or engine internal ports.
Embedded SDK and server routes may keep compatible caller semantics, but they
adapt to the approved task-runtime command/read surfaces. Engine internal task
interfaces have no compatibility obligation; delete them unless they protect a
current engine-owned scheduling/dispatch invariant.

`TaskShellRuntimeStore` and `TaskShellRuntimeLifecycleQuery` are not the
replacement landing zone for `TaskManager`. Their storage CRUD de-scoping and
runtime-backed read-query boundary is owned by
[TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md](TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md).
TRLC must not expand them while removing engine lifecycle ownership from the
new mainline.

Roadmap relationship: TRLC is the controlling roadmap for task lifecycle
ownership, score-band scheduling truth, and old engine lifecycle path
quarantine. Physical removal of old engine lifecycle code is intentionally out
of this roadmap and must be handled by
`ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md`.
TSDC is a supporting roadmap for storage/read-query de-scoping only. If TSDC
touches lifecycle state, status transitions, terminal facts, or deadline
maintenance source, it must follow TRLC owner decisions instead of inventing a
parallel storage/read-view lifecycle mechanism.

Read with:

- [TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_INVENTORY.md](TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_INVENTORY.md)
- [TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md](TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md)
- [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md)
- [TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_ROADMAP.md](TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_ROADMAP.md)
- [../xa-mass-task-runtime/README.md](../xa-mass-task-runtime/README.md)
- [../sdk/xa-mass-task-runtime-starter-sdk/README.md](../sdk/xa-mass-task-runtime-starter-sdk/README.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)

## Current Gap

Current serving code still preserves this command chain:

```text
MassApplication / MassSdkApplication
  -> MassEngine task command methods
  -> EngineConfig / EngineRuntimeKernel
  -> engine TaskCommandPort
  -> TaskManager
  -> TaskLifecycleService
  -> Task.transitionTo(...)
  -> TaskRuntimeServingLane.syncSchedulerEligibility(...)
  -> xa-mass-task-runtime score-range update
```

That chain keeps engine as the lifecycle command owner and makes runtime score
the synced copy. The smell is not the class count by itself; the smell is that
engine shell status still drives dispatch eligibility.

The first lifecycle cutpoint is the simple `TaskLifecycleService.transitionTask`
family: approve, reject, block, and pause. Those commands currently write
engine shell status and then synchronize runtime scheduler eligibility. They
are the smallest useful proof that the state machine belongs in task-runtime
because they can update runtime score-range and lifecycle reason facts without creating active leases,
without touching result/finality, and without using append/claim as the first
cut.

Current code also leaves these cleanup blockers:

- `TaskManager` implements `TaskQueryPort` and engine `TaskCommandPort`, so it still
  looks like both task read owner and lifecycle write owner.
- Task reads are still easy to route through manager/starter wrapper chains,
  which hides the owner truth behind packaging instead of making the read-view
  surface explicit.
- `TaskLifecycleService` writes `TaskStatus` first, then asks runtime to sync
  scheduler eligibility.
- `TaskRuntimeServingLane` is still an engine bridge that mixes scheduling,
  claim, result, read, lifecycle score sync, and shell hooks.
- Existing guards protect the selected `TaskRuntimeServingLane` path, so they
  must be retargeted before old-path quarantine can honestly complete.
- `TaskRuntimeStarter.start(...)` creates the backend and loop host in one API.
  With no loops it should create no thread, but the API name still blurs
  backend bootstrap and maintenance-loop startup.
- Many ports exist because previous migrations protected current code shape
  instead of asking whether the concept should exist. This roadmap treats every
  extra task interface as suspicious until it proves an owner boundary that
  cannot be expressed by command, read-view, or an internal mechanism.

`TaskLifecycleService` is not a target component. It is old-path lifecycle
owner residue. It may exist only while uncut commands still need the old engine
path to keep the system running. It must not receive new lifecycle logic, new
projection responsibilities, new dispatch hooks, or a narrower replacement
name. Each command cutover removes one method family from it. Side effects are
not copied forward by default: each one is either deleted as old-design noise or
reimplemented minimally in the real owner when a current external behavior or
runtime invariant proves it is needed. After the command families are cut over,
the class becomes frozen engine residue and its physical deletion is handed to
the separate engine cleanup roadmap.

## Owner Decision

`xa-mass-task-runtime` owns task runtime lifecycle truth:

- task runtime meta
- score-range lifecycle state
- score-band visibility
- accepted backlog
- active leases
- retry and lease repair
- result finality
- progress and terminal convergence facts

`Task.status` may remain as public read vocabulary or descriptor metadata
during migration, but it must stop being the dispatch eligibility owner. Public
status is a projection of task-runtime truth plus descriptor metadata.

`TaskReadViewPort` is an external read projection surface, not a runtime-core
mechanism. It must not live in `xa-mass-task-runtime` core because view
projection, pagination, diagnostics, and snapshot shape would pollute the
runtime owner module.

`sdk/xa-mass-task-runtime-starter-sdk` is the host entry for embedded lifecycle
commands and read handles. It may assemble memory/Redis implementations and
maintenance loops, and it may carry the approved `TaskReadViewPort` external
contract for this roadmap. That placement does not make starter the read truth
owner: starter exposes a handle and wires a projection implementation over
descriptor metadata plus task-runtime owner truth. It must not expose Redis key
structures, raw physical implementation objects, or task-runtime internal
mechanism ports as the embedded command/read API.

Decision corrected on 2026-07-02: `TaskReadViewPort` does not live in
`xa-mass-task-runtime` core. The approved current location is
`sdk/xa-mass-task-runtime-starter-sdk` as an external kernel read handle, with a
future option to move the pure contract into a dedicated task/kernel API module
after broader API/DTO convergence. It is not defined in engine, engine-starter,
`TaskManager`, or `sdk/xa-mass-embedded-sdk-api` as a parallel read contract.
Existing embedded SDK read contracts are migration residue unless they are
directly replaced by the approved starter read-view surface.

`xa-mass-engine` owns scheduling orchestration:

- acquire score-band candidates from task-runtime
- resolve/select/reserve workers through worker-runtime/matching
- claim runtime backlog with selected-worker evidence for real dispatch
- bind assigned work to transport
- consume result-ingest/pull, lease, and terminal outcomes for trace/resource
  release

Engine must not own approve, pause, resume, block/manual hold, cancel, append, or task
terminal lifecycle decisions. Intake-window state is out of scope for this
roadmap and must not be modeled in the task-runtime score state machine.

There is no long-term engine task lifecycle service. The target owner path is:
task-runtime command writes runtime score-range and owner-local reason truth; scheduling
loops discover score-visible task candidates; engine matches workers and
orchestrates dispatch only after task-runtime exposes a candidate; task-runtime
continues to own claim, retry, lease repair, result finality, and terminal
convergence. A class named `TaskLifecycleService`, or any renamed equivalent in
engine, violates the target owner model.

## Target Data Flow

Command path:

```text
embedded SDK/server validated command
  -> TaskRuntimeStarter command handle
  -> task-runtime lifecycle command
  -> lifecycle/score-owner commands update runtime reason + task:score:{laneKey}
  -> append commands append accepted backlog only and never write task score
  -> publish read/evidence projection as non-authoritative side effect
  -> return a small command result
```

Scheduling path:

```text
engine scheduling loop / dispatch scheduler
  -> task-runtime acquire score-visible dispatch candidates(laneKey, now, limit)
  -> worker-runtime selection/reservation
  -> task-runtime claimBacklog(candidate, worker reservation evidence)
  -> transport assigned dispatch
```

Maintenance path:

```text
task-runtime maintenance loop or explicit runtime repair command
  -> scan retry due / active leases from runtime-owned state
  -> update runtime reason/work state by runtime policy
  -> no engine TaskStatus polling
```

Result/finality path:

```text
transport/worker result ingest
  -> task-runtime apply result/finality/retry/lease outcome
  -> update work truth + progress/finality facts
  -> engine consumes outcome for trace/resource release only
```

Append/score ownership rule:

- AppendOwner owns accepted backlog only.
- Append does not change visibility in `task:score:{laneKey}`, does not
  rescore an already open task, and does not emit dirty/wakeup score hints.
- Task score is written only by lifecycle/score-owner transitions and the
  explicit task scheduling trigger: approve/resume/block/pause/reject/cancel,
  retained terminal close, or a named score-owner rescore selected by runtime
  policy.
- Claim, retry promotion, result, progress, finality, and no-work skips do not
  refresh live task score.
- Append and score do not drive each other in the write path. They meet only at
  claim time: dispatch consumes a score candidate, claim consumes backlog. If
  backlog exists but no due score candidate exists, dispatch must not see that
  backlog.

Read path:

```text
TaskReadViewPort
  -> project descriptor metadata + task-runtime lifecycle/work truth
  -> no lifecycle mutation, no score repair
```

## Interface Direction

Keep the surface deliberately small. This section defines target capability
categories, not a copy of embedded-SDK or engine interfaces. The target external
surface has two and only two categories:

```text
TaskRuntimeCommandPort
  create
  append
  approve
  pause
  block
  reject
  resume
  cancel
  terminate

TaskReadViewPort
  task view
  runtime view
  result view
  diagnostics
  snapshots
```

Everything else is task-runtime internal mechanism or engine assembly wiring.
An interface outside these two categories must justify why it is externally
visible from a target owner invariant. If it exists only because engine or
embedded-sdk currently has a method/port, delete it or make it internal.

### Command Surface

The command surface is the only lifecycle mutation surface:

```text
TaskRuntimeCommandPort
  createTask(...)
  appendItems(...)
  approve(...)
  pause(...)
  resume(...)
  block(...)
  reject(...)
  cancel(...)
  terminate(...)
```

Target code name: use
`com.xa.mass.task.runtime.command.TaskRuntimeCommandPort` for the new command
surface. `TaskCommandPort` without the runtime-qualified name is historical
engine vocabulary in this repo and must not be reused as the implementation
type for the new surface.

Rules:

- Commands use stable primitives or caller-owned values.
- Commands do not accept engine `Task`, engine `TaskStatus`, transport facts,
  worker-runtime internals, Redis keys, or view snapshots.
- `pause(...)` is a no-argument lifecycle event beyond `taskId`; callers do
  not pass pause duration, explicit pause deadline, or scheduler score.
- Runtime decides whether a transition is legal from the current score range
  and owner-local reason facts. Callers do not pass an `allow` list or
  duplicate the state machine.
- Start with one lightweight command result shape. Do not create per-command
  outcome classes unless a real caller branches differently by command type.
- `TaskCommandStatus` is the coarse result category; `reasonCode` is the
  stable machine-readable explanation. Do not expand enum families before
  there is a receiver.
- Intake-window state is not part of the runtime lifecycle command surface.
  This roadmap assigns it no target behavior.
- The command surface is not a DTO staging area. Repeated command/request
  wrappers that only mirror method arguments should be deleted or collapsed.

### Read View Surface

The read surface is projection-only and independently exposed outside
`xa-mass-task-runtime` core. It must not be a method group hidden inside
`TaskManager`, a manager interface implementation, an engine-starter view, an
`embedded-sdk-api` duplicate contract, or a wrapper that only calls another
bridge. The approved current host is `sdk/xa-mass-task-runtime-starter-sdk`;
that module exposes the external read handle but does not own read truth.
`sdk/xa-mass-embedded-sdk` is already the kernel-facing external bridge; adding
more read wrappers that do not change ownership is noise.

```text
TaskReadViewPort
  read task descriptor projection
  read runtime lifecycle/work projection
  read bounded result/progress diagnostics
  read snapshots for diagnostics
```

Rules:

- Reads project owner truth only.
- Reads never repair score or mutate lifecycle.
- Query/view reads are one read side, not mixed into command ports.
- `TaskReadViewPort` is a standalone exposed read surface hosted by
  `sdk/xa-mass-task-runtime-starter-sdk` for this roadmap and kept out of
  `xa-mass-task-runtime` core.
- `TaskQueryPort` should be deleted, package-private, or frozen as old-path
  residue during closure.
- The approved read-view path must not expose or call `TaskManager` as the
  public read surface. If `TaskManager` still implements old read interfaces
  during TRLC, that is quarantine residue only.
- Diagnostics and snapshots are read projections. They are not a reason to
  create mutation ports or push trace/view fields into runtime commands.
- `sdk/xa-mass-embedded-sdk-api` must not retain a parallel read-view contract.
  Starter may expose the approved `TaskReadViewPort`, but it must not define a
  second same-purpose read API beside it. If a temporary compatibility type is
  needed during migration, it must have a deletion target in the same closure
  matrix.
- A valid implementation may compose descriptor metadata and task-runtime
  projection stores directly. It must not be implemented as
  `TaskReadViewPort -> Manager -> bridge -> runtime`.

### Internal Mechanisms

Scheduling, claim, result apply, retry, lease repair, finality, retention, and
score evaluation are internal runtime mechanisms. They are not public task
interfaces.

Allowed internal mechanics:

- `task:score:{laneKey}` candidate scan.
- `claimBacklog` over a score candidate and selected-worker reservation
  evidence.
- result/finality apply.
- retry due promotion.
- lease expiry repair.
- retention/cleanup.
- owner-local score evaluation.

These may exist as package-private classes, backend methods, or assembly-only
handles. They must not become embedded SDK, public starter, or server-facing
interfaces. If engine needs one for scheduling, it should receive a narrow
assembly handle from starter wiring, not a new public port family.

Internal mechanism rules:

- `task:score:{laneKey}` is the lifecycle score index and due candidate
  discovery surface. It is not a work-ready, maintenance, or result index.
- Do not add a second `ready` list/set in v0.
- `ScoreCandidate` may carry lane/fence/epoch/meta-version needed to reject a
  stale claim candidate.
- Claim validates candidate freshness atomically enough for the backend; it
  does not re-run lifecycle policy or prove backlog availability. If the score
  was wrong, the bug belongs to the score owner and should be visible.
- Claim, retry promotion, result apply, progress, and finality must not refresh
  a live task back into the due score band. Terminal close may request the
  owner-authorized retained negative score transition.
- Internal handles must not expose engine `Task`, engine `TaskStatus`, public
  view snapshots, Redis key names, or transport adapter/session facts.

### Starter Surface

```text
TaskRuntimeStarter
  open/bootstrap backend without requiring loop threads
  expose handles for runtime-owned `TaskRuntimeCommandPort` and approved external
    TaskReadViewPort
  provide internal scheduling handle only to engine assembly
  start/register/stop maintenance loops separately
```

Rules:

- Backend bootstrap is not a child-thread requirement.
- Threads belong only to maintenance loops such as retry due promotion, lease
  expiry, retention, or optional health checks.
- `runtime()` / `TaskRuntimePortSet` is assembly-only and must not become the
  embedded SDK command API.
- Starter must not become a second service locator. If a handle is not
  command, read-view, or internal engine assembly, it should not be exposed.
- Starter may publish the standalone read-view handle and may host the current
  `TaskReadViewPort` contract, but it must not own read truth or runtime
  lifecycle truth. Starter must not wrap a manager-owned read implementation
  just to preserve old shape.
- `TaskReadViewPort` must stay out of `xa-mass-task-runtime` core. Embedded SDK
  may expose or return that handle, but should not define a same-shape read
  contract in `embedded-sdk-api`.

## Score-Band State Machine

The score-band is the lifecycle scheduling truth. TRLC consumes
[`TASK_RUNTIME_LIFECYCLE_SCORE_BAND_CORE_ROADMAP.md`](TASK_RUNTIME_LIFECYCLE_SCORE_BAND_CORE_ROADMAP.md)
as the score-band contract. This section maps command vocabulary onto that
contract; it must not redefine a parallel score model.

First target bands:

| Runtime condition | Score visibility |
| --- | --- |
| schedulable now | timestamp score in the schedulable-time band, `score <= now` |
| scheduled future work / scheduler hold | timestamp score in the schedulable-time band, `score > now`; pause uses a runtime-owned far-future hold timestamp |
| non-schedulable but non-terminal | positive enum score outside the timestamp band, such as created/unapproved, manual hold, blocked, or policy-held |
| terminal/discarded/canceled/closed | negative terminal score; no further lifecycle transition |

First target runtime encoding:

| Runtime state | Gate/meta encoding | Score encoding | First-slice command legality |
| --- | --- | --- | --- |
| created / unapproved | owner-local reason metadata only | positive non-schedulable enum score | `approve`, `reject`, `block` allowed; no dispatch |
| approved / open | optional owner-local reason metadata only | timestamp score when the task should be considered by dispatch; `score <= now` means due | `pause`, `block`, terminal commands allowed |
| rejected | owner-local reason `REVIEW_REJECTED` | negative terminal score | no lifecycle transition out |
| manual blocked | owner-local reason `MANUAL_BLOCKED` | positive non-schedulable enum score | unblock/resume moves back to timestamp band |
| paused / delayed | optional owner-local delay reason | `SCHEDULER_HOLD_FLOOR`-style future timestamp chosen by runtime default pause policy; still in the schedulable-time band | no normal dispatch until explicitly resumed or the far-future hold becomes due |
| terminal / discarded / canceled / closed | terminal reason metadata | negative terminal score | command rejected except explicit retention/diagnostic reads |

TRLC-0 may rename these internal enum values, but it must keep the score-band
shape: timestamp scores are schedulable-time facts, positive enums are
non-schedulable non-terminal facts, and negative scores are terminal facts.
Pause is an event, not an externally parameterized timestamp command. Runtime
maps a pause event to its default scheduler-hold timestamp, for example
`SCHEDULER_HOLD_FLOOR`; no caller-provided pause time is accepted in this
roadmap. Manual hold or explicit block can use a positive non-schedulable enum.
Terminal must not be represented as another removable non-schedulable enum
during lifecycle convergence.

Command/event ownership:

| Command/event | Owner action |
| --- | --- |
| create task | create descriptor metadata and runtime meta; default positive non-schedulable score until explicit lifecycle command |
| append items | append accepted backlog only; never write or repair task score |
| approve/resume | write a schedulable timestamp score chosen by the score owner |
| pause event | move score to a runtime-chosen scheduler-hold timestamp; no caller-provided pause time and no separate paused parked state |
| reject | move score to a negative terminal score |
| block/manual hold | move score to a positive non-schedulable enum |
| cancel/terminate | close or discard runtime work by command policy; move score to negative terminal |
| retry due | maintenance loop promotes retry item state into backlog only; it does not refresh task score |
| lease expiry | runtime closes attempt and decides retry/finality; it does not refresh live task score |
| result finality | runtime updates work truth, progress, and terminal candidate; terminal close may request a retained negative score transition |

Intake-window state is intentionally not a task-runtime lifecycle concept in
this roadmap. The kernel does not gate dispatch on that state.

`evaluateTask` / score evaluation must not become a universal repair function.
It should evaluate task-runtime-owned facts only. It should not scan engine
status, retry engine lifecycle policy, or hide broken owner writes by repeatedly
ensuring unrelated state.

## Non-Goals

- No server route contract redesign in this roadmap.
- No frontend/control-console repair in this roadmap.
- No worker-runtime rewrite.
- No transport runtime rewrite.
- No new ready list/set beside the score-band candidate index.
- No new same-module bridge/facade whose only job is forwarding to engine.
- No new public task port family beyond `TaskRuntimeCommandPort` and
  `TaskReadViewPort`.
- No manager-hosted read facade or wrapper chain for `TaskReadViewPort`.
- No broad field cleanup unless the field crosses module boundaries or blocks
  the lifecycle owner cutover.
- No view-first cleanup. Read projection follows owner cutover; it does not
  drive the lifecycle state machine.

## Phase Classification

Do not classify old engine task owner deletion as pre-converge. Do not make
physical deletion of old engine task classes a TRLC completion gate.

Pre-converge in this roadmap is limited to work that makes the real cutover
possible without changing serving truth:

- inventory and classify current callers
- decide descriptor metadata owner
- decide raw `TaskRuntimeHandle.runtime()` closure mode
- narrow or classify public surfaces so command/read-view boundaries are clear
- prepare guards that can be enabled after the owner path is proven

The following are TRLC completion or guard-freeze acceptance, not
pre-converge:

- new mainline paths no longer expose or call `TaskManager` as public task
  command/read surface
- new mainline lifecycle command and scheduling paths do not call
  `TaskManager`, `EngineConfig.ensureTaskManager()`, production
  `new TaskManager(...)` construction paths, or engine task command surfaces
- engine `TaskCommandPort` and `TaskQueryPort` stop being public lifecycle/read
  owner surfaces
- `TaskLifecycleService` is frozen as old-path residue only; it is not kept as
  a thinner lifecycle service, renamed engine command service, or target owner
- `TaskRuntimeServingLane` lifecycle sync is deleted or narrowed away
- engine `Task.transitionTo(...) -> runtime score sync` path is removed
- `TaskAssignWorker`, `TaskWorkerAssignListener`, and `RuntimeReadyDispatchPump`
  no longer use
  shell `TaskStatus.READY/RUNNING` as dispatch truth
- physical deletion scope for `TaskManager`, `TaskLifecycleService`,
  `EngineConfig.ensureTaskManager()`, and related engine residue is recorded
  for `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md`

If an implementation slice removes one of these as part of a proven mechanism
cutover, that is fine. It must be reported as cutover/acceptance progress, not
as pre-converge cleanup.

## TRLC-0 Inventory And Cut Line

Goal: finish the cut-line inventory before changing serving behavior.

Scope:

- Complete
  [TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_INVENTORY.md](TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_INVENTORY.md).
- List every embedded SDK task write method and whether it currently calls
  `MassEngine`, `EngineConfig`, `TaskCommandPort`, or `TaskManager`.
- List every engine status writer that calls `Task.transitionTo(...)`.
- List every `syncRuntimeSchedulerEligibility` or equivalent score-sync caller.
- List the exact `TaskLifecycleService.transitionTask(...)` callers for
  approve, reject, block/manual hold, pause, and resume, and classify each as the first lifecycle
  command cutpoint or a deferred lifecycle command.
- Decide first-slice runtime encoding for created/unapproved, approved/open,
  rejected, manual blocked, paused/delayed, terminal, and discarded. The
  decision must name score ranges, owner-local reason facts, and command
  legality for approve/reject/block/pause/resume.
- List concrete shell-status scheduling gates, at minimum:
  `TaskAssignWorker` assignment queue admission,
  `TaskWorkerAssignListener#onTaskAssign` READY/RUNNING admission,
  `TaskWorkerAssignListener` READY -> RUNNING mutation after dispatch, and
  `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING filtering.
- Classify each `TaskQueryPort` caller as read projection, scheduling
  consumer, test fixture, or delete.
- Classify every task-related interface into exactly one bucket:
  external command, external read-view, internal runtime mechanism, engine
  assembly handle, descriptor metadata, test fixture, or delete.
- Treat embedded SDK interfaces, `TaskReadOperations`, `TaskStore` /
  `TaskShellStore`, engine `TaskCommandPort`, engine `TaskQueryPort`, and
  serving-lane ports as current callers or residue only. They do not define the
  target task-runtime surface.
- Decide descriptor/shell metadata write owner before TRLC-1/TRLC-2 begins.
  This cannot remain a coordination note once command implementation starts.
- Decide the target command interface FQCN. Default is
  `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort`; engine
  `com.xa.mass.engine.TaskCommandPort` is the old path and must be forbidden
  outside old-path residue.
- Decide the first-slice read projection writer for approve/reject/block/pause/resume.
  Default is a task-runtime-starter command wrapper/projector that writes the
  approved read-view projection after runtime command success. Runtime core must
  not import read-view projection or engine-starter wrappers.
- Decide raw `TaskRuntimeHandle.runtime()` closure mode: delete public raw
  getter, narrow it, or guard it with an explicit engine-starter/loop-context
  allowlist.
- Classify current guards that protect the old selected path and name their new
  target guard.

Acceptance:

- No inventory row needed for TRLC-1 through TRLC-4 remains `_TBD`, `pending`,
  or `later classify`.
- The first production command caller to reroute is named.
- The first `TaskLifecycleService.transitionTask(...)` command to replace is
  named; default candidate is approve/reject/block/pause/resume, not append or claim.
- First-slice score-range and reason encoding is written and does not require engine
  `TaskStatus`, `RuntimeGate`, or a second gate field to decide
  approve/reject/block/pause/resume legality.
- The first scheduling cutpoint to remove shell-status admission is named.
- Descriptor/shell metadata write owner is named, with its allowed fields and
  deletion target for any temporary engine metadata writer.
- New command interface FQCN is named; old engine `TaskCommandPort` has a
  forbidden-import/old-path classification.
- Projection writer owner for the selected first-slice commands is named.
- `TaskRuntimeHandle.runtime()` raw access has a closure mode and guard target.
- Every public task interface outside `TaskRuntimeCommandPort` / `TaskReadViewPort`
  has either a deletion target or a written reason why it is not actually
  public.
- Every engine-internal task interface has a default closure of delete/internal
  unless it is tied to a current engine-owned scheduling or dispatch invariant.
- Every old path has a closure mode: reroute, delete, package-private
  temporary, or scheduling-only keep.

## TRLC-1 Starter Bootstrap And Handle Boundary

Goal: make the starter boundary express the real lifecycle: backend bootstrap
is separate from maintenance loops.

Scope:

- Keep or introduce a starter API that can open memory/Redis task-runtime
  backend without requiring background loop threads.
- Expose only `TaskRuntimeCommandPort` and `TaskReadViewPort` as host-facing task
  surfaces.
- Expose the command surface as
  `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort`, not engine
  `com.xa.mass.engine.TaskCommandPort`.
- Define or host `TaskReadViewPort` in
  `sdk/xa-mass-task-runtime-starter-sdk` for this roadmap; do not place the
  target read-view contract in `xa-mass-task-runtime` core, engine,
  engine-starter, `TaskManager`, or `sdk/xa-mass-embedded-sdk-api`.
- Prove starter is only the external handle/assembly host for read projection,
  not a read truth owner or runtime lifecycle owner.
- Provide the first-slice command projection writer in starter/read-view
  composition: it may publish read-view status/evidence after runtime command
  success, but it must not own lifecycle legality or score mutation.
- Provide scheduling/claim/result internal handles only to engine assembly when
  engine is started for scheduling.
- Remove public raw `TaskRuntimeHandle.runtime()` access or guard it so only
  engine-starter assembly and loop context can use it during migration.
- Make loop start/register/stop the only thread-owning part of the starter.

Acceptance:

- A starter test proves opening task-runtime with no loops creates no scheduled
  executor/thread.
- Embedded command code can get a command handle without touching engine.
- Embedded read code can get a read-view handle without touching engine
  lifecycle command paths.
- Embedded read code obtains the standalone read-view surface directly from the
  starter composition, not through `xa-mass-task-runtime` core public ports,
  `TaskManager`, `MassEngine`, `embedded-sdk-api` duplicate contracts, or a
  pass-through read bridge.
- Guard fails if embedded SDK command code uses raw `TaskRuntimePortSet` or a
  Redis/memory implementation class.
- Guard fails if non-assembly code uses public raw `TaskRuntimeHandle.runtime()`
  or `TaskRuntimePortSet`.
- Guard fails if starter exposes a new public task surface outside command/read
  without marking it internal assembly.
- Guard fails if `xa-mass-task-runtime` core exposes the external
  `TaskReadViewPort` contract.
- Guard fails if starter command code imports engine `TaskCommandPort` for the
  new runtime command surface.

## TRLC-2 Runtime Lifecycle Command State Machine

Goal: implement the first lifecycle command state machine inside
`xa-mass-task-runtime`, starting with the simple transition commands that
currently flow through `TaskLifecycleService.transitionTask(...)`.

Slice boundary: TRLC-2 is mechanism proof only unless it is paired with the
TRLC-3A embedded SDK route cutover for the same command family. Runtime
contract tests alone must not be counted as lifecycle mainline convergence.

Scope:

- Add the minimal runtime command surface.
- Use `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort` as the target
  code surface; do not reuse engine `TaskCommandPort`.
- Start with approve/reject/block/pause/resume unless TRLC-0 proves a smaller
  equivalent cutpoint with the same owner value.
- Implement the first-slice runtime encoding decided in TRLC-0, including a
  created/unapproved state that is distinguishable from rejected/manual blocked
  without engine `TaskStatus`.
- Consume the SBL `TaskScoreV1` band contract before implementing the command
  state machine. Do not reintroduce old `PARKED_PAUSED`, `PARKED_BLOCKED`, or
  negative parked semantics.
- Remove or demote `RuntimeGate` from runtime truth. Claim, discovery, and
  transition legality must be decided from score range, epoch/fence, and
  owner-local reason facts, not from `RuntimeGate.OPEN/PAUSED/BLOCKED`.
- Update memory and Redis implementations, including Lua claim guards, so they
  do not treat positive non-schedulable enum scores as `OPEN` or require
  `RuntimeGate.OPEN` for a due score candidate.
- Implement these transitions from the score-band lifecycle model, not by
  copying `TaskLifecycleService.transitionTask(...)` or its status/store/sync
  sequence.
- Implement memory runtime first only if it is behind the same public runtime
  command interface used by Redis.
- Implement Redis runtime against agreed score-band keys and task-local meta.
- Command writes update task score range and owner-local reason facts directly.
- Append writes backlog only. It never writes task score, even when the task
  already has a schedulable timestamp score. Score visibility updates belong to
  lifecycle/score-owner transitions and explicit task scheduling trigger only;
  terminal close may request the retained negative transition.
- Do not start with append-to-claim, because claim creates active work and
  requires result/lease/finality proof in the same serving path.
- Do not solve caller message-id idempotency in this slice.
- Do not preserve `TaskLifecycleService` as the owner being called underneath
  the runtime command. Runtime command implementation must not delegate
  lifecycle legality, status mutation, or score mutation to engine.

Acceptance:

- Contract tests prove approve/reject/block/pause/resume transitions update
  score range and owner-local reason facts without engine classes, engine
  `Task`, engine `TaskStatus`, or `RuntimeGate`.
- Contract tests prove created/unapproved, approved/open, rejected, manual
  blocked, and paused/delayed are distinguishable by runtime-owned facts.
- Contract tests prove `TaskScoreV1` exposes exactly the target bands:
  due timestamp, future timestamp, positive non-schedulable enum, and negative
  terminal.
- Memory and Redis tests prove discovery/claim ignore positive enum and
  negative terminal ranges, accept only due timestamp candidates, and do not
  require `RuntimeGate.OPEN`.
- Redis tests prove no old `dirty`, `ids`, or task-local `ready` key is needed
  for the command path.
- A first-slice projection test proves accepted approve/reject/block/pause/resume
  commands update the read-view/status projection without using the old
  engine-starter `TaskReadViewPublishingTaskCommandPort` over engine
  `TaskCommandPort`.
- Runtime command tests prove lifecycle mutation does not require an engine
  command or query port.
- TRLC-2 acceptance cannot close the selected lifecycle command family by
  itself. The command family remains non-mainline until TRLC-3A proves the
  production-reachable embedded SDK route enters the task-runtime starter
  command handle and the old embedded SDK -> engine route is closed.
- Append contract tests, when TRLC-3B starts, must prove append does not mutate
  `task:score:{laneKey}` or publish dirty score hints.
- A source guard or focused test fails if the selected command path still calls
  `TaskLifecycleService.transitionTask(...)` or performs
  `Task.transitionTo(...) -> runtime score sync`.

Implemented evidence on 2026-07-02:

- `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort` and
  `TaskRuntimeLifecycleCommandService` implement approve/reject/block/pause/resume
  from score-band state.
- `TaskScoreV1` uses timestamp schedulable scores, positive non-schedulable
  enum scores, and negative terminal scores. Negative paused/blocked parked
  constants are removed.
- Memory and Redis score discovery/claim paths use the new score-band
  contract for this slice.

## TRLC-3A Embedded SDK Direct Command Cutover For Simple Transitions

Goal: make approve/reject/block/pause/resume production-reachable through the
embedded SDK without entering engine lifecycle command ownership.

Scope:

- Change only embedded SDK approve/reject/block/pause/resume paths to call
  task-runtime-starter `TaskRuntimeCommandPort`.
- Keep server/auth/request validation outside task-runtime.
- Preserve existing server HTTP route shapes unless a route is already proven
  obsolete by a separate route-classification decision.
- Keep engine startup for scheduling/dispatch roles only.
- Remove or quarantine the selected `MassEngine` task lifecycle command methods
  as soon as their embedded callers are gone.
- Create and append are handled by TRLC-3B, and cancel/terminate are handled by
  TRLC-3C; they must not be treated as deferred old-path callers after those
  slices.

Acceptance:

- SDK command smoke proves approve/reject/block/pause/resume execute without using
  engine `TaskCommandPort`.
- The smoke must enter through the same embedded SDK command surface used by
  production/server adapters, not by invoking the runtime backend or starter
  handle directly in isolation.
- SDK before/after command readback for approve/reject/block/pause/resume is updated
  by the selected read projection writer, not by engine command wrapping.
- Source guard fails if embedded SDK lifecycle write code calls
  `MassEngine.*Task*`, `EngineConfig.getTaskCommandPort()`, engine
  `TaskCommandPort`, or `TaskManager` for approve/reject/block/pause/resume.
- Source guard does not allow create/append/cancel/terminate embedded SDK
  old-path calls after TRLC-3B/TRLC-3C.
- Server API behavior is unchanged unless explicitly classified outside this
  roadmap.

Implemented evidence on 2026-07-02:

- `MassSdkApplication` routes APPROVE/REJECT/BLOCK/PAUSE/RESUME through
  `MassApplication.taskRuntimeCommands()`.
- `MassApplication` maps those commands to the task-runtime starter command
  handle instead of `MassEngine` task command methods.
- Focused SDK proof covers create through deferred old path, append through
  the task-runtime command handle, approve through the new runtime command,
  runtime-score dispatch, transport result, and terminal convergence.

## TRLC-3B Create And Append Cutover

Goal: move create and append out of engine command ownership while keeping
descriptor metadata out of `xa-mass-task-runtime` core and keeping append out
of score/lifecycle ownership.

Scope:

- Create writes descriptor metadata/read projection through the embedded
  assembly descriptor writer and initializes runtime with a positive
  non-schedulable score through `TaskRuntimeCommandPort.create(taskId)`.
- Create does not revive task CRUD ownership in `TaskManager`, and it does not
  pass `TaskShellCreateRequestDto` into `xa-mass-task-runtime` core.
- Append writes accepted backlog; it does not infer lifecycle from item count
  and does not solve caller message-id idempotency.
- Append must not update score visibility, dirty, wake, or otherwise influence task
  score. Score visibility is maintained independently by the score owner; append
  and score meet only when claim consumes a score candidate and backlog.
- Projection writer updates create descriptor fields and append read-view
  fields needed by core SDK/server behavior. Missing display-only fields are
  not blockers for this slice.

Acceptance:

- SDK create/append smoke runs without engine `TaskCommandPort`.
- Create proof shows descriptor facts do not become runtime lifecycle truth and
  runtime initial state is a positive non-schedulable score.
- Append proof shows accepted backlog truth is in task-runtime, not storage
  task rows.
- Append proof shows append leaves `task:score:{laneKey}` unchanged.
- Source guard fails if embedded SDK append write code calls
  `MassEngine.appendTaskItems`, `EngineConfig.getTaskCommandPort()`, engine
  `TaskCommandPort`, `TaskManager`, or `TaskLifecycleService`.
- Source guard fails if embedded SDK create write code calls
  `MassEngine.createTaskShell`, engine `TaskCommandPort`, `TaskManager`, or
  `TaskLifecycleService`.
- Runtime proof only needs to show that score range, dispatch candidate
  acquisition, and runtime append semantics are independent of intake-window
  state.

Implemented evidence on 2026-07-02:

- `MassApplication.appendTaskItems(...)` and
  `MassSdkApplication.appendTaskItemsWithReceipt(...)` call
  `TaskRuntimeCommandPort.append(...)` through the task-runtime starter command
  handle.
- `MassApplication.createTaskShell(...)` writes descriptor metadata through
  `EngineConfig.createTaskShellDescriptor(...)` and initializes runtime through
  `TaskRuntimeCommandPort.create(taskId)`, bypassing `MassEngine.createTaskShell`
  and engine `TaskCommandPort`.
- The public `EngineConfig#getTaskCommandPort()` backdoor is deleted; old
  engine command access remains only as package-private engine assembly /
  legacy-test quarantine until the separate engine cleanup roadmap removes the
  old path physically.
- Raw `TaskRuntimeHandle.runtime()` remains an assembly handle for
  engine-starter and starter-sdk owner tests; a source guard rejects
  non-assembly production imports of `TaskRuntimeHandle` / `TaskRuntimePortSet`,
  keeping external task access on `TaskRuntimeCommandPort` and
  `TaskReadViewPort`.
- Runtime append command delegates only to `TaskRuntimeWorkPort.appendBacklog`.
  It does not call score APIs, dirty markers, task status sync, or dispatch
  wakeup.
- SDK append tests capture the runtime `AppendItemInput` batch and verify event
  code/payload mapping without invoking engine append.

## TRLC-3C Cancel And Terminate Cutover

Goal: move commands that can interact with active work, terminal state, or
runtime finality only after the corresponding owner rules are proven.

Scope:

- Cancel/terminate close/discard runtime work according to task-runtime
  convergence rules.
- This slice may require TRLC-5 result/lease/finality proof if serving active
  leases are affected.

Acceptance:

- SDK cancel/terminate smoke runs without engine `TaskCommandPort`.
- Runtime proof shows terminal/discard transitions cannot be overwritten by
  stale engine status or stale result.

Implemented evidence on 2026-07-02:

- `TaskRuntimeCommandPort.cancel(...)` and `terminate(...)` are implemented by
  `TaskRuntimeLifecycleCommandService`.
- Terminal command handling calls `TaskRuntimeConvergencePort.discardWork(...)`
  and then writes a negative terminal score. It does not call engine
  `TaskCommandPort`, `TaskManager`, or `TaskLifecycleService`.
- `MassApplication.cancelTask(...)`,
  `MassApplication.terminateTask(...)`, `MassSdkApplication.cancelTask(...)`,
  and `MassSdkApplication.executeTaskCommand(TERMINATE)` call the task-runtime
  command handle.
- Embedded SDK guard now fails if cancel/terminate route through engine command
  methods.

## TRLC-4 Internal Scheduling Mechanism Cutover

Goal: make engine consume task-runtime score candidates instead of engine shell
status, without turning scheduling into a new public task API.

Scope:

- Add or converge an internal runtime scheduling handle over
  `task:score:{laneKey}`.
- Move `RuntimeReadyDispatchPump` / scheduling loops to score candidates.
- Replace `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING
  filtering with runtime score-candidate filtering.
- Replace `TaskAssignWorker` / `TaskWorkerAssignListener` READY/RUNNING
  admission with runtime candidate/active-work facts.
- Remove `TaskWorkerAssignListener` READY -> RUNNING mutation as dispatch
  truth; public status becomes projection only.
- Keep worker selection/reservation in engine/worker-runtime.
- Claim backlog using the runtime candidate plus selected-worker reservation
  evidence.
- Claim rejects stale score candidates using runtime-owned fence/epoch facts,
  without duplicating lifecycle policy in engine.
- Remove scheduling decisions that treat mutable `Task.status` as the dispatch
  admission owner.
- Delete `syncRuntimeSchedulerEligibility` after the command path writes score
  directly.

Acceptance:

- Engine scheduling proof fails if task-runtime score candidate acquisition is
  bypassed.
- Tests prove future pause timestamps, positive non-schedulable enum scores,
  and negative terminal scores are not dispatched even if a stale engine shell
  status says otherwise.
- Tests prove `TaskAssignWorker`, `TaskWorkerAssignListener`, and
  `RuntimeReadyDispatchPump` no longer gate dispatch by `TaskStatus.READY` /
  `TaskStatus.RUNNING`.
- Tests prove assignment allocation/refill policies use runtime-ready work and
  active-worker facts instead of shell `READY/RUNNING` as dispatch truth.
- `TaskWorkerAssignListener` does not let shell RUNNING projection failure
  cancel dispatch after worker selection and binding have succeeded.
- `TaskRuntimeServingLane` is narrowed to scheduling/claim/result support or
  deleted; it no longer owns lifecycle command sync.
- No embedded SDK, server controller, or public starter API can call the
  scheduling/claim handle directly.
- If this slice creates serving active leases, it must include TRLC-5
  result/lease/finality proof in the same serving cutover. Otherwise this slice
  is non-serving proof only.

## TRLC-5 Result, Lease, And Terminal Lifecycle Closure

Goal: prevent active work from creating a second lifecycle owner.

Current evidence:

- `TaskRuntimePortContractTest#closeIfDrainedClosesScoreOnlyAfterMutableWorkIsGone`
  proves the runtime convergence port does not close scheduling visibility
  while ready or active work remains, and does retain final rows after mutable
  work is drained.
- `InMemoryTaskRuntime#closeIfDrained(...)` implements the same owner contract
  instead of returning a stubbed `false`.
- Redis already implements the same closure atomically through its score-band
  script; the focused Redis score-band test exercises the shared contract.
- `TaskRuntimeServingLaneTest` proves the current serving append -> claim ->
  result path calls `closeIfDrained(...)` after accepted logical finality and
  progress projection, so a drained task is no longer left in active
  dispatch visibility.
- `TaskRuntimeServingLaneTest#servingLaneLeaseRepairAppliesTimeoutResultAndConvergesTaskTerminal`
  proves expired active leases are discovered from task-runtime repair
  candidates and applied through task-runtime result/finality handling.
- Runtime score-band proof now covers retryable lease expiry as item-state
  convergence only: retry/result paths do not write the next live task score
  from retry timing or shell status.
- `TaskRuntimeServingLaneOldPathClosureGuardTest` now rejects lease-repair
  wording or code paths that put result/finality ownership back on engine
  `TaskResultService`, legacy result runtime DTOs, or shell lifecycle status.

Scope:

- Result apply, retry, lease expiry, finality, progress, and terminal candidate
  updates remain task-runtime-owned.
- Runtime finality updates task reason/score.
- Engine consumes outcome facts for trace, worker resource release, and
  dispatch accounting.
- Engine does not close task lifecycle by shell-status rules after runtime
  finality has already decided the runtime state.

Acceptance:

- A real path proves independent backlog append plus score-owner schedulable
  score -> score candidate -> worker reservation -> claim -> dispatch ->
  result -> runtime finality -> projected terminal read.
- Late/stale result tests prove runtime rejects stale attempt/result facts
  without engine lifecycle repair.
- Lease expiry/retry proof uses task-runtime maintenance loops or explicit
  runtime repair commands, not engine status polling.
- No serving cutover is complete until active lease creation, result apply,
  retry/finality, lease repair, and projected terminal read are proven on the
  same owner path.

## TRLC-6 Read Projection And Query Path Closure

Goal: make reads projection-only, independently exposed, and remove read
ownership from `TaskManager`.

Current evidence:

- `TaskReadViewPort` is defined in `sdk/xa-mass-task-runtime-starter-sdk` and
  extends the current SDK `TaskReadOperations` snapshot shape to avoid a second
  DTO family during migration.
- `EngineConfig#getTaskReadViewPort()` is the approved starter assembly entry,
  and `EngineTaskReadOperations` implements `TaskReadViewPort`.
- `MassApplication#taskReadView()` exposes the approved read surface, and
  `MassSdkApplication` consumes `delegate.taskReadView()` under its existing
  `TaskReadOperations` facade.
- Server `TaskApiController` and `InternalTaskReviewController` consume
  `TaskReadViewPort` directly; HTTP route and response contracts are unchanged.
- `MassSdkApplication` is the Spring bean satisfying `TaskReadViewPort` in the
  server runtime, proven by the memory-local server context test.
- `MassSdkApplicationTaskReadBoundaryTest` proves runtime score-band command
  projection for approve/pause/resume/block/reject without entering the old
  engine lifecycle command path. Pause is verified as a no-argument event that
  projects from the runtime default future timestamp.
- `TaskReadOperations` is still retained as the SDK facade/interface shape.
  This means TRLC-6 is materially advanced but not complete.

Scope:

- Converge external reads through standalone `TaskReadViewPort` hosted by
  `sdk/xa-mass-task-runtime-starter-sdk`, outside `xa-mass-task-runtime` core.
- Treat existing `sdk/xa-mass-embedded-sdk-api` `TaskReadOperations` as a
  migration facade/interface shape. It may reuse `TaskReadViewPort` underneath
  but must not be treated as the owner source for future read semantics.
- Project public status from descriptor metadata plus runtime reason/score,
  active leases, progress, and finality.
- Delete or demote `TaskQueryPort`; it must not stay as public lifecycle/read
  owner.
- Delete read manager wrappers and bridge-only read implementations whose only
  job is forwarding to `TaskManager`, `MassEngine`, or another read bridge.
- Remove read paths that mutate score, repair lifecycle, or call command ports.
- Collapse result view, runtime view, diagnostics, and snapshots under the
  read-view surface unless a current caller proves a separate owner boundary.

Acceptance:

- Approved `TaskReadViewPort` paths do not call `TaskManager` or
  `TaskQueryPort`.
- `TaskManager` does not implement or host the approved `TaskReadViewPort`.
  Any old `TaskQueryPort` implementation left on `TaskManager` is quarantine
  residue only and not the read-view path.
- There is no `TaskReadViewPort -> Manager -> bridge -> runtime` chain.
- `TaskReadViewPort` does not live in `xa-mass-task-runtime` core.
- Starter has exactly one approved read-view contract/handle and does not
  define a second same-purpose read API beside it.
- `TaskReadViewPort` is not duplicated in `sdk/xa-mass-embedded-sdk-api`.
- Existing `TaskReadOperations` callers either consume `TaskReadViewPort`
  underneath or are classified as server-compatible facade residue.
- Read tests prove status projection changes after task-runtime command
  transitions without engine lifecycle writes.
- Diagnostics and snapshot reads cannot call lifecycle mutation APIs.
- Guard fails if a new public diagnostic/query port is added outside the
  read-view surface.

## TRLC-7 Old Path Quarantine And Guard Freeze

Goal: make the new task-runtime lifecycle/score-band path the only mainline
path, quarantine the old engine lifecycle path as frozen residue, and freeze
the owner boundary. Physical engine code deletion is handed off to a separate
engine cleanup roadmap.

Scope:

- Prove embedded SDK/server command adapters for cutover commands enter
  task-runtime starter command handles, not `MassEngine`, engine
  `TaskCommandPort`, `TaskManager`, or `TaskLifecycleService`.
- Prove engine scheduling consumes task-runtime score candidates and does not
  derive dispatch admission from engine shell `TaskStatus`.
- Freeze old engine lifecycle code as non-mainline residue: no new commands, no
  new lifecycle side effects, no new read-view responsibilities, no renamed
  replacement owner.
- Keep production engine classes physically present if needed to avoid mixing a
  large engine deletion with this mainline cutover.
- Record the exact engine cleanup deletion scope for a separate roadmap:
  `TaskManager`, `TaskLifecycleService`, `EngineConfig.ensureTaskManager()`,
  production `new TaskManager(...)`, engine task command/read ports, and any
  old guards that only protect lifecycle sync residue.
- Delete or narrow only the old pieces that are directly required to make the
  selected mainline cutover true. Broad engine class deletion is out of scope.
- Replace old guards that require `TaskRuntimeServingLane` lifecycle sync with
  owner guards for the cutover paths.
- Update current owner docs after code proof exists:
  - `xa-mass-task-runtime/README.md`
  - `sdk/xa-mass-task-runtime-starter-sdk/README.md`
  - `sdk/xa-mass-embedded-sdk/README.md`
  - `xa-mass-engine/README.md`
  - `xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md`
  - `doc/TASK_LIFECYCLE_BASELINE.md`
- Run residue scan for old command paths, old score-sync vocabulary, and stale
  docs.

Acceptance:

- Guard fails on embedded SDK -> engine lifecycle command calls.
- Guard fails on engine lifecycle command methods that call
  `Task.transitionTo(...)` and then sync score.
- Guard fails if any cutover command path calls `MassEngine`,
  `EngineConfig.getTaskCommandPort()`, engine `TaskCommandPort`,
  `TaskManager`, or `TaskLifecycleService`.
- Guard fails if engine scheduling admission reads shell
  `TaskStatus.READY/RUNNING` instead of task-runtime score candidates.
- Guard fails on reintroducing task-local `ready` list/set, `dirty` score hint,
  or engine-status-driven score repair.
- Guard fails if `TaskQueryPort` returns as a public read surface.
- Guard fails if a new public task interface appears outside the approved
  command/read-view surface.
- Guard fails if `TaskReadViewPort` is implemented by `TaskManager` or by a
  bridge-only wrapper around a manager read method.
- Guard fails if a same-purpose `TaskReadViewPort` / `TaskReadOperations`
  public contract remains beside the approved starter read-view surface or in
  `sdk/xa-mass-embedded-sdk-api` without an explicit deletion target.
- Guard fails if `xa-mass-task-runtime` core exposes the external read-view
  contract.
- Guard fails if `TaskManager`, `TaskLifecycleService`,
  `EngineConfig.ensureTaskManager()`, or production `new TaskManager(...)`
  gains new mainline callers outside the explicitly deferred old-path residue.
- Guard fails if a renamed replacement class combines task command, query,
  shell lifecycle maintenance, state runtime, and runtime serving-lane assembly
  responsibilities.
- Guard fails if a renamed engine service owns approve/reject/block/pause,
  resume, append, cancel, terminate, or terminal lifecycle transitions.
- `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md` or a same-named handoff
  section names the physical deletion candidates and the proof needed before
  deleting them. TRLC does not claim those classes are removed.

Implemented evidence on 2026-07-02:

- `LeaseExpireWatchdog` is documented as a starter/engine-hosted tick over
  task-runtime maintenance ports; it no longer claims engine owns lease repair.
- Owner docs were updated so `xa-mass-task-runtime` owns score-band lifecycle
  and item/result convergence, `sdk/xa-mass-task-runtime-starter-sdk` hosts the
  command/read assembly surfaces without owning runtime truth, and
  `xa-mass-engine` is orchestration/worker/transport/result-consumption around
  task-runtime truth rather than the lifecycle command owner.
- `sdk/xa-mass-embedded-sdk/README.md` now states embedded SDK task lifecycle
  commands use the task-runtime starter command handle and reads use
  `TaskReadViewPort`; `TaskManager`, `TaskLifecycleService`,
  engine `TaskCommandPort`, and `TaskQueryPort` are not the SDK task
  command/read surface.
- `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md` records the deferred
  physical deletion candidates and proof needed before removing old engine
  lifecycle residue.

## Verification

Existing support tests can prevent unrelated regressions, but they do not prove
TRLC owner cutover by themselves.

Existing support tests:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimePortContractTest,TaskRuntimeContractShapeTest,TaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeContractTest,InMemoryTaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisTaskRuntimeScoreBandKeyspaceProofTest,RedisTaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk test "-Dtest=TaskRuntimeStarterBootstrapTest,TaskRuntimeStarterArchitectureGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest"
```

Implemented first-slice proof for TRLC-2 / TRLC-3A:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandServiceTest,TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory -am test "-Dtest=InMemoryTaskRuntimeContractTest,TaskRuntimeLifecycleCommandServiceTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisScoreBandTaskRuntimeTest,RedisTaskRuntimeScoreBandAdvanceCandidateTest,RedisTaskRuntimeScoreBandKeyspaceProofTest"
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk test "-Dtest=TaskRuntimeStarterBootstrapTest,TaskRuntimeNonServingAppendToClaimProofTest,TaskRuntimeStarterArchitectureGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneTest,TaskAssignWorkerTest,TaskWorkerAssignListenerTest,RuntimeReadyDispatchPumpTest,TaskRuntimeServingLaneOldPathClosureGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EmbeddedSdkEngineDependencyGuardTest,MassSdkApplicationTaskReadBoundaryTest,MassSdkTest#pullWorkerSessionCompletesTaskWithoutWebsocketPush"
```

Implemented append cutover proof for TRLC-3B:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandServiceTest,TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassSdkTest#*append*,EmbeddedSdkEngineDependencyGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EmbeddedSdkEngineDependencyGuardTest,MassSdkApplicationTaskReadBoundaryTest,MassSdkTest#pullWorkerSessionCompletesTaskWithoutWebsocketPush"
```

Implemented create cutover proof for TRLC-3B:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandServiceTest,TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassSdkTest#createTaskUsesSdkRequestAsPrimaryContract,MassSdkTest#pullWorkerSessionCompletesTaskWithoutWebsocketPush,EmbeddedSdkEngineDependencyGuardTest"
```

Implemented cancel/terminate cutover proof for TRLC-3C:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandServiceTest,TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassSdkTest#taskTerminalCommandsUseTaskRuntimeCommandSurface,MassSdkTest#engineDependentHelpersFailFastWhenEngineIsUnavailable,MassSdkTest#engineDependentHelpersFailFastBeforeStart,EmbeddedSdkEngineDependencyGuardTest"
```

Implemented read-view entry proof for TRLC-6:

```powershell
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine-starter,sdk/xa-mass-embedded-sdk -am install -DskipTests
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EngineStarterBackdoorGuardTest,MassSdkApplicationTaskReadBoundaryTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=TaskApiControllerTest,TaskApiListControllerTest,ServerMainSourceArchitectureGuardTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=ServerMemoryLocalProfileContextTest"
```

Focused score-band projection proof added for TRLC-6:

```powershell
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassSdkApplicationTaskReadBoundaryTest"
```

Partial TRLC-4 assignment-queue proof:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=EngineSchedulingCoreArchitectureGuardTest#taskAssignWorkerDoesNotGateAssignmentQueueByReadyRunningShellStatus,TaskAssignWorkerTest,TaskWorkerAssignListenerTest,RuntimeReadyDispatchPumpTest,TaskRuntimeServingLaneTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=EngineSchedulingCoreArchitectureGuardTest#taskAssignWorkerDoesNotGateAssignmentQueueByReadyRunningShellStatus+taskWorkerAssignListenerDoesNotMakeShellRunningProjectionDispatchTruth,TaskWorkerAssignListenerTest,TaskAssignWorkerTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=DefaultAssignmentAllocationPolicyTest,DefaultAssignmentRefillPolicyTest,TaskResourceReleaseListenerTest,TaskWorkerAssignListenerTest,TaskAssignWorkerTest,EngineSchedulingCoreArchitectureGuardTest#taskAssignWorkerDoesNotGateAssignmentQueueByReadyRunningShellStatus+taskWorkerAssignListenerDoesNotMakeShellRunningProjectionDispatchTruth+assignmentAllocationAndRefillDoNotUseShellReadyRunningAsDispatchTruth"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneTest,EngineSchedulingCoreArchitectureGuardTest#dispatchWakeupDoesNotRewriteSchedulerEligibilityFromShellStatus"
```

Partial TRLC-5 runtime closure proof:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandServiceTest,TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeContractTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisScoreBandTaskRuntimeTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneTest"
```

Deferred guard/proof hardening:

```powershell
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EmbeddedSdkEngineDependencyGuardTest"
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk test "-Dtest=TaskRuntimeStarterArchitectureGuardTest"
```

Later required proof:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=EngineSchedulingConsumesTaskRuntimeScoreTest,TaskRuntimeServingLaneNarrowingGuardTest,SimpleTaskDispatchBinderTest,TaskResultRuntimeConvergenceTest"
```

Tests in the required groups are target proof names and may need to be created
in the implementation slice. Do not pass `-Dsurefire.failIfNoSpecifiedTests=false`
for required proof or guard commands; missing test names must fail.

If a slice touches Spring/server assembly, add the relevant Spring context or
Boot-shell proof. Constructor-only tests are not enough for startup changes.

## Coordination Points

These are real design decisions, not reasons to defer the roadmap:

- Descriptor/shell owner: TRLC-0 must decide whether shell metadata remains in
  the current engine-owned store temporarily, moves behind starter command
  composition, or becomes a task-runtime descriptor store. Read-view projection
  is not a command truth source, and descriptor metadata must not remain a
  lifecycle truth owner.
- Public `Task.status`: decide whether it is a stored projection/cache during
  migration or removed from write paths entirely. Dispatch must not read it as
  truth.
- Runtime command vocabulary:
  approve/reject/block/pause/resume/cancel/terminate/create/append should be
  enough for v0 unless a current caller proves otherwise. `pause` is a
  no-argument event that uses the runtime default pause window. Manual
  indefinite hold uses `block` / positive non-schedulable enum, not `pause`.
- Multi-engine fence: decide the exact candidate freshness fields needed for
  score-candidate acquisition -> claim without turning claim into policy
  evaluation or a public task API.
- Result cutover blast radius: if transport/result serving cutover cannot land
  in the same slice as score-candidate scheduling, keep the candidate path
  non-serving until result/lease/finality ownership is proven.

## Stop Conditions

Stop and re-plan if a slice requires any of these:

- embedded SDK must call engine for task lifecycle writes
- embedded SDK must mutate raw `TaskRuntimePortSet`
- non-assembly code must use public raw `TaskRuntimeHandle.runtime()` /
  `TaskRuntimePortSet`
- task-runtime public commands need engine `Task` or `TaskStatus`
- a new public task interface is needed beyond command/read-view and cannot be
  justified as internal assembly
- `TaskReadViewPort` can only be implemented by routing through
  `TaskManager`, `MassEngine`, or a bridge-only wrapper
- `TaskReadViewPort` needs to live in `xa-mass-task-runtime` core or be
  duplicated in `embedded-sdk-api` as a parallel public read contract
- starter read-view exposure starts defining read truth instead of projecting
  descriptor metadata plus runtime owner truth
- a serving path creates active leases before result/lease/finality ownership
  and repair are proven on the same path
- a first slice starts with append/claim/active lease creation instead of
  proving direct lifecycle command state transitions, unless that same slice
  also proves result/lease/finality on the owner path
- score is maintained by polling engine shell status
- a new ready list/set is introduced beside score-band without a separate
  high-ROI decision
- engine remains the only place that can approve, pause, resume,
  block/manual hold, append, cancel, or terminal-close a task after the
  corresponding cutover slice
- `TaskLifecycleService` must be retained as a thinner or renamed lifecycle
  owner instead of frozen old-path residue pending a separate engine cleanup
  roadmap
- view/diagnostics cleanup becomes the mainline before command and scheduling
  cutover

## Completion Criteria

- Embedded SDK task lifecycle writes enter task-runtime through
  `sdk/xa-mass-task-runtime-starter-sdk`, not through engine.
- External task surface is reduced to command and read-view. Other task-runtime
  mechanisms are internal or engine assembly only.
- `TaskReadViewPort` is kept out of `xa-mass-task-runtime` core; starter hosts
  the approved external read handle/contract for this roadmap without owning
  read truth.
- Task-runtime score-range/reason/backlog/work/result/finality is the lifecycle
  truth used by dispatch.
- Engine scheduling acquires task-runtime score candidates and no longer derives
  dispatch admission from engine shell status.
- Engine owns matching/worker selection/reservation, transport dispatch
  orchestration, and result-ingest/pull consumption only.
- Read APIs project task state from owner truth and do not mutate lifecycle.
- `TaskReadViewPort` is a standalone exposed read surface; it is not hosted by
  `TaskManager`, not duplicated beside the approved starter surface or in
  `embedded-sdk-api`, and not hidden behind bridge-only wrappers.
- `TaskManager`, `TaskLifecycleService`, `EngineConfig.ensureTaskManager()`,
  and production `new TaskManager(...)` are no longer on the new mainline
  lifecycle command or score scheduling paths. If they still exist physically,
  they are frozen old-path residue with no new command/read/scheduling owner
  responsibilities.
- Cutover command/read/scheduling paths no longer use `TaskRuntimeServingLane`
  as a lifecycle command sync owner. Any remaining
  `TaskManager -> TaskLifecycleService -> syncRuntimeSchedulerEligibility`
  call chain is old-path residue and is in scope for
  `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md`, not a valid target path
  for new lifecycle behavior.
- Old-path guards prove the embedded SDK -> engine lifecycle command chain
  cannot return silently.
- The physical deletion scope for remaining engine task lifecycle residue is
  handed off to `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md`; TRLC
  completion does not claim production engine classes are removed.
