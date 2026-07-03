# Task Runtime Score-Band Lifecycle Direct Command Inventory

Status: active inventory and baseline contract ledger for
[TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md](TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md).

This file is the working cut-line inventory for removing engine from task
lifecycle command ownership and quarantining old engine lifecycle code as
non-mainline residue. It must be completed before implementation starts.
Rows here are not explanatory notes: they define the current closure ledger for
old-path ownership, public surface classification, acceptance targets, and guard
targets. Changing target classifications or closure modes is a baseline
contract revision for this roadmap.

Score-band rule: this inventory consumes
[`TASK_RUNTIME_LIFECYCLE_SCORE_BAND_CORE_ROADMAP.md`](TASK_RUNTIME_LIFECYCLE_SCORE_BAND_CORE_ROADMAP.md)
as the score contract. Rows here must not redefine task score ranges, re-add
append-driven score visibility, or treat claim/retry/result/progress/finality
as live task-score refresh writers.

Inventory rule: every task-related symbol must justify why it exists. It must
end in exactly one of these target classifications:

```text
external TaskRuntimeCommandPort
external TaskReadViewPort
task-runtime internal mechanism
engine assembly-only handle
keep as descriptor metadata only
old-path quarantine residue
test fixture only
delete
```

The inventory must not turn old engine ports into a new public port family.
Scheduling, claim, result apply, retry, lease repair, finality, retention, and
score evaluation are internal mechanisms unless a row proves a real external
caller and owner boundary.

Inventory rule: target closure is not code relocation. Rows classify behavior
to delete, replace, or reimplement from the target owner model. They must not
authorize moving old `TaskManager`, `TaskLifecycleService`, or
status-to-score-sync code into task-runtime, starter, or another renamed class
as a temporary landing zone.

Surface rule: this inventory does not derive the target surface from current
embedded SDK methods, `TaskReadOperations`, `TaskStore` / `TaskShellStore`, or
engine internal task ports. Those are current callers or residue. Target
surfaces come from task-runtime command capability, task read-view capability,
and narrow internal scheduling/dispatch handles only.

`external TaskRuntimeCommandPort` is the target command bucket. The implementation
type is `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort`. Engine
`com.xa.mass.engine.TaskCommandPort` remains old-path residue and must not be
used as the new runtime command surface.

`external TaskReadViewPort` means the approved read projection surface hosted by
`sdk/xa-mass-task-runtime-starter-sdk` for this roadmap. It does not live in
`xa-mass-task-runtime` core and does not make starter the read truth owner.

Rows marked `seed` are current observations from the initial scan and must be
rechecked in TRLC-0 before code changes.

Phase rule: rows whose closure mode is `split/delete`, `delete/replace`, or
`delete/reroute` are roadmap acceptance or guard-freeze targets only when the
row can be closed without physically deleting broad production engine classes.
`TaskManager`, `TaskLifecycleService`, `EngineConfig.ensureTaskManager()`, and
production `new TaskManager(...)` are TRLC quarantine targets; their physical
deletion is deferred to a separate engine cleanup roadmap. They are not
pre-converge cleanup by default.

## Current And Residual Command Chains

Implemented cutover chain for selected embedded SDK commands:

```text
sdk/xa-mass-embedded-sdk MassApplication / MassSdkApplication
  -> task-runtime command handle
  -> xa-mass-task-runtime TaskRuntimeCommandPort
  -> runtime score-band / accepted backlog owner
```

Remaining old-path residue for deferred commands:

```text
sdk/xa-mass-embedded-sdk MassApplication / MassSdkApplication
  -> xa-mass-engine-starter MassEngine
  -> xa-mass-engine TaskCommandPort
  -> xa-mass-engine TaskManager
  -> xa-mass-engine TaskLifecycleService
  -> xa-mass-engine TaskRuntimeServingLane
  -> xa-mass-task-runtime ports
```

The residual chain is the chain to close. The target is not to add another
bridge around it. The target is to make selected embedded SDK task writes call
the task-runtime command handle directly, and to quarantine old engine command
ownership until its physical deletion roadmap starts.

Serving cutover rule: rows are not closed by proving a runtime backend method
works in isolation. A selected lifecycle command row is closed only when the
production-reachable embedded SDK command path stops entering `MassEngine`,
engine `TaskCommandPort`, `TaskManager`, and `TaskLifecycleService` for that
command. Physical engine classes may remain present as frozen residue for
deferred command families, but they are not allowed to remain in the selected
embedded SDK serving route.

## Dependency Inventory

| Module/file | Current dependency fact | Target classification | Status |
| --- | --- | --- | --- |
| `sdk/xa-mass-embedded-sdk/pom.xml` | depends on `xa-mass-engine-starter` and now declares the task-runtime command contract dependency used by embedded SDK commands | keep selected command callers on the task-runtime command contract; do not call engine `TaskCommandPort` as the command route | first-slice implemented |
| `xa-mass-engine-starter/pom.xml` | depends on `xa-mass-task-runtime-starter-sdk` and `xa-mass-engine` | keep for scheduling/dispatch assembly; not the embedded SDK lifecycle command owner | seed |
| `sdk/xa-mass-task-runtime-starter-sdk/pom.xml` | depends on `xa-mass-embedded-sdk-api`, `xa-mass-task-runtime`, memory/Redis task-runtime implementations, and Lettuce | correct host for runtime bootstrap and approved external read-view handle; exposes runtime-owned command handle, starter-hosted read projection handle, and internal engine assembly handles only when needed | read-view entry implemented |
| `sdk/xa-mass-task-runtime-starter-sdk/src/main/java/com/xa/mass/task/runtime/starter/TaskReadViewPort.java` | approved starter-hosted read-view contract extending current SDK `TaskReadOperations` snapshot shape | target read-view entry; kept out of `xa-mass-task-runtime` core and avoids new duplicate read DTOs in this slice | read-view entry implemented |
| `sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/TaskReadOperations.java` | current SDK-owned public read facade/interface shape | migration facade only; not a target design source; embedded SDK and server read paths consume approved starter-hosted `TaskReadViewPort` underneath | facade residue |
| `xa-mass-server/pom.xml` | now depends directly on `xa-mass-task-runtime-starter-sdk` for the approved read-view contract | server reads should type against `TaskReadViewPort` while preserving HTTP/API response contracts | read-view entry implemented |
| `xa-mass-task-runtime/README.md` | states task-runtime owns accepted backlog, score visibility, claim, result finality, active-lease repair, progress, discard, and final-result reads | extend with score-band lifecycle command ownership when implemented | seed |
| `xa-mass-kernel-spi/src/main/java/com/xa/mass/kernel/spi/task/TaskShellRuntimeStore.java` / `TaskShellRuntimeLifecycleQuery.java` | runtime-facing storage SPI used by engine `TaskManager` construction | not a TRLC replacement landing zone; converge separately through `TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md` and do not expand during TRLC | seed |

## Surface Collapse Inventory

| Surface bucket | Allowed visibility | Examples | Non-examples |
| --- | --- | --- | --- |
| External command | task-runtime command capability consumed by embedded SDK/server adapters | create, append, approve, reject, pause, block/manual hold, resume, cancel, terminate | intake-window state, claim, retry repair, scheduler scan, diagnostics, copying current SDK/engine method shapes |
| External read-view | standalone read-view capability hosted by `sdk/xa-mass-task-runtime-starter-sdk` and projected over owner truth | `TaskReadViewPort`, task view, runtime view, result view, diagnostics, snapshots | mutation, score repair, lifecycle transition, runtime-core API pollution, manager-hosted read facade, embedded-sdk-api duplicate contract, `TaskStore`/`TaskReadOperations` as target source |
| Task-runtime internal mechanism | package/internal backend or runtime classes | score evaluation, candidate scan, backlog claim, result apply, lease repair, retry promotion | public starter/API port family |
| Engine assembly-only handle | only engine scheduling/dispatch wiring | candidate acquisition + claim handle passed during starter assembly | embedded SDK or server controller dependency |
| Descriptor metadata | task identity/contract/project metadata | shell record, submitter/project fields, audit metadata | dispatch eligibility truth, runtime lifecycle truth, or intake-window state design |
| Delete | no owner boundary and not needed as TRLC quarantine residue | same-purpose bridge, broad query port, status-to-score sync adapter | real external command/read surface, deferred engine cleanup residue |

## Lifecycle Write Surface Inventory

| Symbol | Current role | Current owner path | Target owner | Closure mode | Status |
| --- | --- | --- | --- | --- | --- |
| `MassApplication#createTaskShell` / SDK equivalent | embedded caller task create | now writes descriptor metadata through embedded assembly and initializes runtime through task-runtime command handle | descriptor metadata write plus task-runtime positive non-schedulable created score; no `TaskShellCreateRequestDto` enters runtime core | guard old path | create cutover implemented |
| `MassApplication#appendTaskItems` / SDK equivalent | embedded caller append | now routed through task-runtime command handle for embedded SDK path | task-runtime append command writes accepted backlog only; no score update, dirty hint, or score visibility mutation | guard old path | append cutover implemented |
| `MassApplication#sealTask` / SDK equivalent | embedded caller intake-window state | delegates through engine command path | out of scope for TRLC; do not define target behavior here | out-of-scope residue | seed |
| `MassApplication#approveTask` / SDK equivalent | dispatch admission command | now routed through task-runtime command handle for embedded SDK path | task-runtime score-range transition to schedulable timestamp band | reroute then guard old path | first-slice implemented |
| `MassApplication#rejectTask` / SDK equivalent | manual rejection command | now routed through task-runtime command handle for embedded SDK path | task-runtime negative terminal score | reroute then guard old path | first-slice implemented |
| `MassApplication#blockTask` / SDK equivalent | manual block command | now routed through task-runtime command handle for embedded SDK path | task-runtime positive non-schedulable enum score | reroute then guard old path | first-slice implemented |
| `MassApplication#pauseTask` / SDK equivalent | no-argument pause event | now routed through task-runtime command handle for embedded SDK path | task-runtime writes a future timestamp using its default pause window; caller-provided pause time is not accepted; indefinite manual stop maps to block/manual hold positive enum | reroute then guard old path | first-slice implemented |
| `MassApplication#resumeTask` / SDK equivalent | resume dispatch command | now routed through task-runtime command handle for embedded SDK path | task-runtime owner-local transition to schedulable timestamp band | reroute then guard old path | first-slice implemented |
| `MassApplication#cancelTask` / SDK equivalent | manual cancel command | now routed through task-runtime command handle for embedded SDK path | task-runtime discards runtime work and writes negative terminal score | guard old path | terminal cutover implemented |
| `MassApplication#terminateTask` / SDK equivalent | manual terminal command | now routed through task-runtime command handle for embedded SDK path | task-runtime discards runtime work and writes negative terminal score; reason remains primitive evidence/projection input, not a runtime enum dependency | guard old path | terminal cutover implemented |
| `MassEngine` task command methods | starter-facing task command facade | delegates to engine `TaskCommandPort` | remove from the new lifecycle command path; keep only as old-path quarantine residue until separate engine cleanup | quarantine/reroute | seed |
| `EngineConfig#getTaskCommandPort` / `taskCommandPort` | old public getter exposed engine task command surface; public getter is now deleted, package-private assembly/quarantine method remains for engine kernel and legacy tests | constructs `TaskManager` command surface | no terminal role; embedded SDK command path uses task-runtime starter, and any descriptor helper is a separately named owner | public getter closed; package-private quarantine remains until engine cleanup | first-slice implemented with residue |
| `TaskCommandPort` in `xa-mass-engine` | create/lifecycle/append/seal surface | implemented by `TaskManager` | not a lifecycle owner and not a target interface source; lifecycle pieces are forbidden from new mainline, physical removal deferred to engine cleanup roadmap; intake-window state is out of scope here | old-path quarantine residue | seed |
| `TaskManager implements TaskCommandPort` | broad engine task facade | calls `TaskLifecycleService` | no terminal role; not a target landing zone; freeze as old-path residue after cutover, and reimplement only proven owner-specific behavior outside the manager | old-path quarantine residue | seed |
| `EngineConfig.ensureTaskManager()` / production `new TaskManager(...)` | constructs the broad engine task owner and leaks it into starter/kernel wiring | no terminal role; starter/engine assembly must wire real owners directly for new mainline; physical removal is separate engine cleanup | old-path quarantine residue | seed |
| `TaskLifecycleService` | old-path lifecycle owner residue | writes `TaskStatus`, calls runtime sync, publishes ready | freeze after cutover; do not transplant old methods; side effects are deleted or minimally reimplemented by the real owner only when a current external behavior or runtime invariant requires it | old-path quarantine residue; no new logic | seed |
| `TaskRuntimeServingLane#appendRuntimeIngressItems` | engine old-path adapter appends runtime backlog only | still exists for deferred engine residue and tests, but embedded SDK append no longer enters it; source guard forbids score writes from append ingress | append is backlog-only in both direct command path and remaining serving-lane append helper; score visibility belongs to lifecycle/score-owner transitions and explicit scheduling trigger, not append | quarantine old engine path, then delete with engine cleanup | append cutover implemented with guarded residue |
| `TaskRuntimeServingLane#updateSchedulerEligibility` | maps engine `TaskStatus` to `RuntimeGate` and score | hidden sync from engine lifecycle to runtime score | delete mapping; command writes score directly; dispatch wakeup no longer calls it | delete/replace | partially narrowed |
| `TaskLifecycleService.transitionTask(...)` approve/reject/block/pause/resume callers | smallest current lifecycle transition family | remains as old-path residue for non-cutover callers/tests, but embedded SDK approve/reject/block/pause/resume no longer enters it | first direct task-runtime lifecycle command cutpoint; runtime writes score range plus owner-local reason directly; `pause` is a no-argument default-delay event; block/manual hold is the indefinite stop | reroute selected caller then guard old transition path | first-slice embedded path closed |

## Runtime Code Contract Conflicts

These are current-code facts that must be resolved before TRLC-2 can implement
the new command state machine. They are not optional cleanup.

| Symbol | Current conflict | Target contract | Closure mode | Status |
| --- | --- | --- | --- | --- |
| `TaskScoreV1` | negative parked constants such as paused/blocked were incompatible with the target negative-terminal-only band | exposes target bands only: due/future timestamp, positive non-schedulable enum, and negative terminal | replace before first command cutover | first-slice implemented |
| `RuntimeGate` / `TaskRuntimeMetaV1.runtimeGate` | duplicates lifecycle truth beside score and can reverse-drive command legality | demote to residue/projection or remove from runtime truth; lifecycle legality comes from score range plus owner-local reason facts | close before TRLC-2 proof | seed |
| in-memory task-runtime score discovery/claim logic | previously interpreted default task state as due/open when backlog was appended without score | dispatch discovers only explicit due timestamp scores; append-only backlog does not create a score candidate, and positive enums are not open | replace with target score-band logic | first-slice implemented with append/score contract proof |
| Redis task-runtime scripts/claim logic | current scripts may validate old gate values or old score bands | atomic claim validates candidate lane/epoch/fence/observed score remains in due timestamp band; it does not use `RuntimeGate.OPEN` as lifecycle truth | replace with target score-band logic | first-slice implemented |

## Scheduling And Dispatch Inventory

These rows may stay in engine, but they must consume task-runtime truth instead
of engine lifecycle truth. They are not external task interfaces.

| Symbol | Current role | Target role | Required check | Status |
| --- | --- | --- | --- | --- |
| `EngineRuntimeKernel` | starts assignment, runtime-ready pump, lease watchdog, event listeners | scheduling/dispatch runtime only | must not require engine task command owner for lifecycle writes | seed |
| `RuntimeReadyDispatchPump` | discovers runtime-ready tasks and requests dispatch | may stay as scheduling loop | consumes task-runtime score candidates; no READY/RUNNING shell-status filter in runtime-ready path | first-slice implemented |
| `TaskAssignWorker` assignment queue admission | previously required shell `TaskStatus.READY` / `TaskStatus.RUNNING` before invoking assignment listener | assignment queue processes non-terminal shell projections; runtime score candidate discovery owns dispatch eligibility | no READY/RUNNING shell-status gate; terminal-only drop proof | first-slice implemented |
| `DefaultAssignmentAllocationPolicy` | worker allocation/min-start logic previously used shell `TaskStatus.READY` to decide first-start gate | allocation uses runtime active-worker count and ready-work count; shell status is not dispatch truth | guard forbids `TaskStatus.READY/RUNNING` in allocation policy | first-slice implemented |
| `DefaultAssignmentRefillPolicy` | refill previously required shell `TaskStatus.RUNNING` before reading runtime-ready work | refill uses runtime-ready work and keeps terminal projection only as a defensive stop | guard forbids `TaskStatus.READY/RUNNING` in refill policy | first-slice implemented |
| `TaskRuntimeServingLane#requestTaskDispatch` | dispatch wakeup previously rewrote scheduler eligibility from mutable shell status and used shell active status before publishing | wakeup-only path; publishes for non-terminal shell projection and leaves score truth to lifecycle/score-owner transitions | guard forbids `updateSchedulerEligibility(...)` and `.isActive()` in request dispatch | first-slice implemented |
| `TaskRuntimeServingLane#rescoreTaskForRetry` | retry rescore previously reloaded shell task state and reused scheduler eligibility mapping | no target role; retry/result paths must not refresh live task score, and retry promotion moves item state only | guard forbids reintroducing shell reload, scheduler eligibility mapping, or any retry/result live-score writer | SBL core removed |
| `SimpleTaskDispatchBinder` | claims ready backlog after worker selection | stay in engine scheduling/dispatch | no `TaskStatus` admission dependency | seed |
| `TaskAssignmentRuntimePort#claimReady` | claim port into runtime | internal runtime mechanism or engine assembly-only handle, not public port | only accepts selected worker reservation evidence and score candidate | seed |
| `TaskResultIngestPort` / result facade | result ingress and finality outcome consumer | internal result/finality mechanism or engine assembly-only handle | no task lifecycle command ownership | seed |
| `TaskLeaseMaintenancePort` | expired lease repair and resource release reads | internal maintenance mechanism or engine assembly-only handle | no shell status lifecycle owner | seed |
| `TaskRuntimeConvergencePort#closeIfDrained` | runtime closure hook after mutable work/result convergence | runtime owner closes dispatch visibility only after backlog/retry/active work is drained and terminal policy has accepted task closure; final rows may remain as read truth, and session/open drain does not close lifecycle | shared Redis/memory contract proof plus serving-lane result closure/session-open proof | partially implemented with serving proof |

## Status And Projection Inventory

| Symbol/pattern | Current risk | Target | Status |
| --- | --- | --- | --- |
| `TaskStatus` imports in engine scheduling/assignment | dispatch behavior may read shell status as lifecycle truth | replace behavior reads with task-runtime score/progress/active-lease facts | seed |
| `Task.transitionTo(...)` and `Task.setStatus(...)` in engine lifecycle tests | tests preserve mutable engine lifecycle truth | rewrite tests around task-runtime command transitions and read projections | seed |
| `TaskStateResolver` / `TaskStateValidator` | may blend shell status and runtime progress | become read projection only | seed |
| `TaskWorkerAssignListener#onTaskAssign` READY/RUNNING admission | concrete dispatch gate used to read shell `TaskStatus` before runtime-ready work and worker selection | runtime-ready path now skips only terminal shell states; full projection-only RUNNING cleanup remains later | first-slice implemented with residue |
| `TaskAssignWorker` duplicate-signal handling | duplicate signal handling previously only deferred requeue for shell `RUNNING` | duplicate non-terminal task signals become bounded deferred requeue; terminal projection still drops | test proves duplicate non-terminal submit is serialized and replayed once | first-slice implemented |
| `EngineTaskReadOperations` | engine-starter read provider over projection/runtime reads | implements approved `TaskReadViewPort`; must not call `TaskManager`, `TaskQueryPort`, or `TaskShellStore` query methods | read-view entry implemented |
| `MassApplication#taskReadView` / `MassSdkApplication#taskReads` | embedded SDK read entry | `MassApplication` exposes `TaskReadViewPort`; `MassApplication.taskReads`, `MassEngine.taskReads`, and `EngineConfig.getTaskReadOperations` are removed; `MassSdkApplication` keeps `TaskReadOperations` facade while consuming `delegate.taskReadView()` | read-view entry implemented with facade residue |
| `TaskApiController` / `InternalTaskReviewController` | server HTTP read consumers | consume `TaskReadViewPort` directly; API shapes remain unchanged | read-view entry implemented |
| `TaskWorkerAssignListener` READY -> RUNNING mutation after dispatch | concrete dispatch path still projects shell RUNNING after actual dispatch | no longer dispatch truth; worker selection plus dispatch binding decide assignment success, and shell RUNNING remains projection residue pending later read-model cleanup | first-slice implemented with projection residue |
| `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING filter | runtime-ready pump previously filtered tasks by shell `TaskStatus` | runtime-ready pump now filters only final shell status after score candidate discovery | first-slice implemented |
| `TaskReadOperations` | current SDK read facade in `sdk/xa-mass-embedded-sdk-api` | migration caller adapter only; starter/server/starter-assembly code must use standalone `TaskReadViewPort`: read-only projection over descriptor and task-runtime facts; must not live in `xa-mass-task-runtime` core, be hosted by `TaskManager`, or route through manager bridge wrappers | facade residue |
| public `TaskStatus` response fields | useful API vocabulary, dangerous owner truth | projection only; no dispatch reads | seed |

## Target Score-Band Map

This table records the first target mapping. Names may change, but the owner
direction must not.

First-slice runtime encoding:

| Runtime state | Gate/meta encoding | Score encoding | Notes |
| --- | --- | --- | --- |
| created / unapproved | owner-local reason metadata only | positive non-schedulable enum score | no dispatch until approve/resume moves to timestamp band |
| approved / open | optional owner-local reason metadata only | timestamp score; `score <= now` is due for dispatch, `score > now` is future scheduled | score is owner-local lifecycle/scheduling transition, not engine status sync |
| rejected | owner-local reason `REVIEW_REJECTED` | negative terminal score | no lifecycle transition out |
| manual blocked | owner-local reason `MANUAL_BLOCKED` | positive non-schedulable enum score | recoverable only through explicit resume |
| paused / delayed | optional owner-local delay reason | future timestamp score supplied by runtime default pause policy | no dispatch until due; not a manual hold and not a negative parked state |
| terminal / discarded / canceled / closed | terminal reason metadata | negative terminal score | no lifecycle transition out |

| Input | Score-range target | Descriptor metadata target | Notes |
| --- | --- | --- | --- |
| create | positive non-schedulable enum score | create identity/contract/project metadata | create does not imply dispatch admission |
| append | accepted backlog only; no score mutation | optional append audit/count metadata | append owns backlog truth, not shell status or score visibility; backlog and score meet only when claim consumes a score candidate and backlog frame |
| intake-window state | out of scope | no lifecycle change | do not design runtime behavior in this roadmap |
| approve | due or scheduled timestamp score chosen by score owner | optional approval metadata/audit | approval is a score transition, not shell lifecycle |
| block | positive non-schedulable enum score | optional reason/audit metadata | manual hold is recoverable only through explicit resume |
| reject | negative terminal score | optional reason/audit metadata | rejection is terminal in this roadmap |
| pause event | future schedulable timestamp score chosen by runtime default pause policy | optional reason/audit metadata | no caller-provided pause time; active leases may continue or timeout by runtime policy; manual indefinite stop is block/manual hold |
| resume | owner-local timestamp score recompute | optional audit metadata | no engine status-to-score sync |
| cancel/terminate | discard runtime work and write negative terminal score | terminal metadata projection | runtime finality/progress remains task-runtime owned |
| result/lease/retry | task-runtime convergence updates work/progress/finality facts; terminal close may request retained negative score | trace/projection consumers only | engine consumes outcomes but does not own lifecycle; no live due-score refresh |

## Slice Guards

Enable these as the corresponding slice cuts over. They are not final physical
deletion guards.

- TRLC-2 guard fails if target runtime command implementation imports engine
  `Task`, engine `TaskStatus`, engine `TaskCommandPort`, SDK DTOs, transport
  types, server HTTP types, Redis implementation classes from public runtime
  API, or delegates lifecycle legality to engine.
- TRLC-2 guard fails if approve/reject/block/pause/resume legality
  cannot be decided from runtime-owned score-range and owner-local reason facts.
- TRLC-3A guard fails if embedded SDK approve/reject/block/pause/resume paths call
  `MassEngine` lifecycle command methods.
- TRLC-3A guard fails if embedded SDK approve/reject/block/pause/resume paths call
  `EngineConfig.getTaskCommandPort()`, engine `TaskCommandPort`, or
  `TaskManager`.
- TRLC-3A guard fails if selected command readback depends on engine
  `TaskReadViewPublishingTaskCommandPort` wrapping engine `TaskCommandPort`.
- TRLC-3B guard extends the old-path ban to embedded SDK create and append.
  TRLC-3C guard extends the same old-path ban to embedded SDK cancel/terminate.
  Intake-window state is out of scope for these guards.
- embedded SDK task write methods use raw `TaskRuntimePortSet`
- non-assembly callers use public raw `TaskRuntimeHandle.runtime()` or raw
  `TaskRuntimePortSet`
- public starter or SDK surface exposes task-related ports outside
  `TaskRuntimeCommandPort` / `TaskReadViewPort`
- `TaskReadViewPort` is implemented by `TaskManager`, `MassEngine`, or a
  bridge-only wrapper around manager reads
- `TaskReadViewPort` is exposed from `xa-mass-task-runtime` core
- same-shape read contracts remain beside the approved starter-hosted
  `TaskReadViewPort` or in `sdk/xa-mass-embedded-sdk-api` without a deletion
  target
- engine lifecycle code maps `TaskStatus` to `RuntimeGate` for command writes
- scheduling admission reads `TaskStatus.READY` or `TaskStatus.RUNNING` as
  dispatch truth
- read/diagnostic paths mutate score as a repair side effect

## TRLC-7 Quarantine Guards

Enable these only after the corresponding owner paths have been cut over and
TRLC-7 starts. These are not physical source-absence guards for broad engine
classes; physical deletion belongs to the separate engine cleanup roadmap.

- cutover command/read/scheduling paths call `TaskManager`,
  `TaskLifecycleService`, `EngineConfig.ensureTaskManager()`, production
  `new TaskManager(...)`, or engine `TaskCommandPort`
- production source contains a renamed engine lifecycle command service that
  owns approve/reject/block/pause, resume, append, cancel, terminate, or
  terminal transitions
- a renamed replacement class combines task command, query, shell lifecycle
  maintenance, state runtime, and runtime serving-lane assembly
- task-runtime starter lifecycle command surface imports `xa-mass-engine`

## Follow-Up Engine Cleanup Guard Candidates

These are not enabled by TRLC. They belong to the separate engine cleanup
roadmap after the new mainline has been proven and old-path residue no longer
serves a production lane:

- production source still contains `class TaskManager`,
  `class TaskLifecycleService`, `EngineConfig.ensureTaskManager()`, or
  `new TaskManager(...)`
- engine `TaskCommandPort` / `TaskQueryPort` remain public production surfaces
- old tests still require engine status-to-score sync as the serving behavior

## First Slice Candidate

First implementation slice should be the smallest lifecycle owner proof, not an
append-to-claim path. Start by replacing the simple
`TaskLifecycleService.transitionTask(...)` family for approve/reject/block/pause/resume
with runtime-owned command transitions that update score range and owner-local
reason facts directly.
This slice must not create active leases, dispatch work, or require
result/lease/finality convergence.

```text
embedded SDK approve/reject/block/pause/resume command
  -> task-runtime-starter lifecycle command handle
  -> memory task-runtime backend
  -> runtime command state machine
  -> runtime score-range/reason update
  -> read-view/status projection proof
```

This first slice is only complete if it also closes the matching old embedded
SDK -> engine command route for the selected methods. A proof that the new path
works while the old path remains live is not convergence.
The anti-proof is explicit: a test that calls the task-runtime command backend
directly, while `MassApplication` / `MassSdkApplication` still uses the old
engine route for the same selected command, is mechanism proof only and does
not close the row.

Append, candidate acquisition, and claim are later cutpoints. Serving cutover
rule: if any slice is production-visible and creates an active lease, it must
extend the proof to:

```text
claim -> dispatch -> result apply / lease expiry -> retry/finality
  -> projected terminal/read view
```

Otherwise it remains non-serving proof.

## TRLC-0 Hard Decisions

- exact command type names and package placement
- exact target command FQCN; default:
  `com.xa.mass.task.runtime.command.TaskRuntimeCommandPort`
- exact first-slice runtime encoding for created/unapproved, approved/open,
  rejected, manual blocked, paused/delayed, terminal, and discarded
- descriptor metadata write owner, allowed descriptor fields, and deletion
  target for any temporary engine metadata writer
- exact first-slice projection writer for approve/reject/block/pause/resume readback;
  default is starter/read-view composition, not runtime core and not
  engine-starter wrapping engine `TaskCommandPort`
- exact first `TaskLifecycleService.transitionTask(...)` caller set to reroute;
  default is approve/reject/block/pause/resume unless TRLC-0 proves otherwise
- exact current `pauseTask` mapping: no-argument pause becomes a runtime
  default-delay timestamp update; indefinite operator hold remains
  block/manual hold. Do not introduce caller-provided pause time in the runtime
  command surface.
- exact `TaskScoreV1` replacement constants and helper methods for timestamp,
  positive non-schedulable enum, and negative terminal bands.
- exact `RuntimeGate` closure mode: remove, demote to read projection, or keep
  as legacy metadata that command/discovery/claim never reads for lifecycle
  truth.
- exact owner-local reason sidecar/meta fields needed for first-slice readback
  without reintroducing engine `TaskStatus` or fat `Task` DTO truth.
- raw `TaskRuntimeHandle.runtime()` closure mode: public getter remains for
  engine-starter assembly and starter-sdk owner tests; non-assembly production
  code is guarded from importing `TaskRuntimeHandle` or `TaskRuntimePortSet`.
- exact quarantine plan for `MassEngine` task command methods: which calls are
  removed from the new mainline in TRLC and which physical deletion checks are
  deferred to the separate engine cleanup roadmap
- exact quarantine and follow-up deletion scope for `TaskLifecycleService`; it
  is not a target component and must not be split into a thinner engine
  lifecycle service
- exact side-effect classification for `TaskLifecycleService`: validation,
  descriptor/read projection publication, trace/evidence, runtime command, and
  scheduling wakeup must each be deleted or minimally reimplemented by its real
  owner; do not move old code wholesale
- exact replacement for remaining `TaskWorkerAssignListener` and
  `RuntimeReadyDispatchPump` shell-status gates
- server route reroute order after embedded SDK command path is proven
