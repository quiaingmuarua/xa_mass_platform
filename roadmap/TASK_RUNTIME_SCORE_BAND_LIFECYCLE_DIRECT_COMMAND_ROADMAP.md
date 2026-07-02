# Task Runtime Score-Band Lifecycle Direct Command Roadmap

Status: proposed on 2026-07-02.

Contract impact: baseline/roadmap execution contract revision. This file does
not change Java runtime behavior by itself, but changes the task-runtime owner
boundary, public surface rules, slice classification, stop conditions, and
completion criteria that future implementation must satisfy. Treat edits here
as baseline contract changes, not ordinary explanatory documentation.

Mainline anchor:

```text
embedded SDK task command
  -> sdk/xa-mass-task-runtime-starter-sdk command handle
  -> xa-mass-task-runtime lifecycle state machine
  -> task meta + score-band + backlog/work/result truth
  -> engine scheduling consumes score candidates only
```

Read-view anchor:

```text
embedded SDK/server read
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

`TaskManager` has no terminal role. Any useful method must be migrated to the
real owner instead of preserving a renamed or narrower `TaskManager` shell.
Roadmap completion requires the production `TaskManager` class and
`EngineConfig.ensureTaskManager()` construction path to disappear.

`TaskShellRuntimeStore` and `TaskShellRuntimeLifecycleQuery` are not the
replacement landing zone for `TaskManager`. Their storage CRUD de-scoping and
runtime-backed read-query boundary is owned by
[TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md](TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md).
TRLC must not expand them while deleting engine lifecycle ownership.

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
  -> xa-mass-task-runtime score/gate update
```

That chain keeps engine as the lifecycle command owner and makes runtime score
the synced copy. The smell is not the class count by itself; the smell is that
engine shell status still drives dispatch eligibility.

Current code also leaves these cleanup blockers:

- `TaskManager` implements `TaskQueryPort` and `TaskCommandPort`, so it still
  looks like both task read owner and lifecycle write owner.
- Task reads are still easy to route through manager/starter wrapper chains,
  which hides the owner truth behind packaging instead of making the read-view
  surface explicit.
- `TaskLifecycleService` writes `TaskStatus` first, then asks runtime to sync
  scheduler eligibility.
- `TaskRuntimeServingLane` is still an engine bridge that mixes scheduling,
  claim, result, read, lifecycle score sync, and shell hooks.
- Existing guards protect the selected `TaskRuntimeServingLane` path, so they
  must be retargeted before old-path deletion can honestly complete.
- `TaskRuntimeStarter.start(...)` creates the backend and loop host in one API.
  With no loops it should create no thread, but the API name still blurs
  backend bootstrap and maintenance-loop startup.
- Many ports exist because previous migrations protected current code shape
  instead of asking whether the concept should exist. This roadmap treats every
  extra task interface as suspicious until it proves an owner boundary that
  cannot be expressed by command, read-view, or an internal mechanism.

## Owner Decision

`xa-mass-task-runtime` owns task runtime lifecycle truth:

- task runtime meta
- append gate / lifecycle gate
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

Engine must not own approve, pause, resume, block, cancel, seal, append, or task
terminal lifecycle decisions.

## Target Data Flow

Command path:

```text
embedded SDK/server validated command
  -> TaskRuntimeStarter command handle
  -> task-runtime lifecycle command
  -> update runtime meta/gate/score/backlog
  -> return a small command result
```

Scheduling path:

```text
engine scheduling loop
  -> task-runtime acquireReadyTasks(laneKey, now, limit)
  -> worker-runtime selection/reservation
  -> task-runtime claimBacklog(candidate, worker reservation evidence)
  -> transport assigned dispatch
```

Result/finality path:

```text
transport/worker result ingest
  -> task-runtime apply result/finality/retry/lease outcome
  -> update work truth + task meta/gate/score
  -> engine consumes outcome for trace/resource release only
```

Read path:

```text
TaskReadViewPort
  -> project descriptor metadata + task-runtime lifecycle/work truth
  -> no lifecycle mutation, no score repair
```

## Interface Direction

Keep the surface deliberately small. The target external surface has two and
only two categories:

```text
TaskCommandPort
  create
  append
  seal
  approve
  pause
  block
  resume
  cancel

TaskReadViewPort
  task view
  runtime view
  result view
  diagnostics
  snapshots
```

Everything else is task-runtime internal mechanism or engine assembly wiring.
An interface outside these two categories must justify why it is externally
visible. If it cannot, delete it or make it internal.

### Command Surface

The command surface is the only lifecycle mutation surface:

```text
TaskCommandPort
  createTask(...)
  appendItems(...)
  sealIntake(...)
  approve(...)
  pause(...)
  resume(...)
  block(...)
  cancel(...)
  terminate(...)
```

Rules:

- Commands use stable primitives or caller-owned values.
- Commands do not accept engine `Task`, engine `TaskStatus`, transport facts,
  worker-runtime internals, Redis keys, or view snapshots.
- Runtime decides whether a transition is legal from current meta/gate/score.
  Callers do not pass an `allow` list or duplicate the state machine.
- Start with one lightweight command result shape. Do not create per-command
  outcome classes unless a real caller branches differently by command type.
- `TaskCommandStatus` is the coarse result category; `reasonCode` is the
  stable machine-readable explanation. Do not expand enum families before
  there is a receiver.
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
- `TaskQueryPort` should be deleted or package-private residue during closure.
- `TaskManager` must stop implementing or hosting the public read surface.
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

- `task:score:{laneKey}` is the ready/maintenance candidate index.
- Do not add a second `ready` list/set in v0.
- `ScoreCandidate` may carry lane/fence/epoch/meta-version needed to reject a
  stale claim candidate.
- Claim validates candidate freshness atomically enough for the backend; it
  does not re-run lifecycle policy. If the score was wrong, the bug belongs to
  the score owner and should be visible.
- Internal handles must not expose engine `Task`, engine `TaskStatus`, public
  view snapshots, Redis key names, or transport adapter/session facts.

### Starter Surface

```text
TaskRuntimeStarter
  open/bootstrap backend without requiring loop threads
  expose handles for runtime-owned TaskCommandPort and approved external
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

The score-band is the lifecycle scheduling truth.

First target bands:

| Runtime condition | Score visibility |
| --- | --- |
| open and dispatchable now | positive due score in dispatch range |
| delayed retry / scheduled future work | positive future due score |
| active leases but no backlog | maintenance-visible score band, not dispatch range |
| paused by operator | negative paused parked score |
| blocked/rejected pending manual action | negative blocked parked score |
| terminal/discarded and no retained maintenance need | absent from score |

Command/event ownership:

| Command/event | Owner action |
| --- | --- |
| create task | create descriptor metadata and runtime meta; default not dispatchable until explicit gate command |
| append items | append accepted backlog; do not infer lifecycle from item count alone |
| seal intake | close append gate only; sealed task may still have active work |
| approve/resume | open runtime gate and rescore from backlog/retry/active facts |
| pause | move score/gate to paused parked band; no new claims |
| block/reject | move score/gate to blocked parked band; no new claims |
| cancel/terminate | close or discard runtime work by command policy; remove dispatch visibility |
| retry due | maintenance loop promotes retry into backlog or schedulable score according to runtime owner rules |
| lease expiry | runtime closes attempt, decides retry/finality, and rescores |
| result finality | runtime updates work truth, progress, terminal candidate, and score |

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
- No new public task port family beyond `TaskCommandPort` and
  `TaskReadViewPort`.
- No manager-hosted read facade or wrapper chain for `TaskReadViewPort`.
- No broad field cleanup unless the field crosses module boundaries or blocks
  the lifecycle owner cutover.
- No view-first cleanup. Read projection follows owner cutover; it does not
  drive the lifecycle state machine.

## Phase Classification

Do not classify old engine task owner deletion as pre-converge.

Pre-converge in this roadmap is limited to work that makes the real cutover
possible without changing serving truth:

- inventory and classify current callers
- decide descriptor metadata owner
- decide raw `TaskRuntimeHandle.runtime()` closure mode
- narrow or classify public surfaces so command/read-view boundaries are clear
- prepare guards that can be enabled after the owner path is proven

The following are completion or guard-freeze acceptance, not pre-converge:

- `TaskManager` no longer implements or hosts public task command/read surfaces
- production `TaskManager` class, `EngineConfig.ensureTaskManager()`, and
  production `new TaskManager(...)` construction paths are removed
- engine `TaskCommandPort` and `TaskQueryPort` stop being public lifecycle/read
  owner surfaces
- `TaskLifecycleService` stops being lifecycle owner
- `TaskRuntimeServingLane` lifecycle sync is deleted or narrowed away
- engine `Task.transitionTo(...) -> runtime score sync` path is removed
- `TaskWorkerAssignListener` and `RuntimeReadyDispatchPump` no longer use
  shell `TaskStatus.READY/RUNNING` as dispatch truth

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
- List concrete shell-status scheduling gates, at minimum:
  `TaskWorkerAssignListener#onTaskAssign` READY/RUNNING admission,
  `TaskWorkerAssignListener` READY -> RUNNING mutation after dispatch, and
  `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING filtering.
- Classify each `TaskQueryPort` caller as read projection, scheduling
  consumer, test fixture, or delete.
- Classify every task-related interface into exactly one bucket:
  external command, external read-view, internal runtime mechanism, engine
  assembly handle, descriptor metadata, test fixture, or delete.
- Decide descriptor/shell metadata write owner before TRLC-1/TRLC-2 begins.
  This cannot remain a coordination note once command implementation starts.
- Decide raw `TaskRuntimeHandle.runtime()` closure mode: delete public raw
  getter, narrow it, or guard it with an explicit engine-starter/loop-context
  allowlist.
- Classify current guards that protect the old selected path and name their new
  target guard.

Acceptance:

- No inventory row needed for TRLC-1 through TRLC-4 remains `_TBD`, `pending`,
  or `later classify`.
- The first production command caller to reroute is named.
- The first scheduling cutpoint to remove shell-status admission is named.
- Descriptor/shell metadata write owner is named, with its allowed fields and
  deletion target for any temporary engine metadata writer.
- `TaskRuntimeHandle.runtime()` raw access has a closure mode and guard target.
- Every public task interface outside `TaskCommandPort` / `TaskReadViewPort`
  has either a deletion target or a written reason why it is not actually
  public.
- Every old path has a closure mode: reroute, delete, package-private
  temporary, or scheduling-only keep.

## TRLC-1 Starter Bootstrap And Handle Boundary

Goal: make the starter boundary express the real lifecycle: backend bootstrap
is separate from maintenance loops.

Scope:

- Keep or introduce a starter API that can open memory/Redis task-runtime
  backend without requiring background loop threads.
- Expose only `TaskCommandPort` and `TaskReadViewPort` as host-facing task
  surfaces.
- Define or host `TaskReadViewPort` in
  `sdk/xa-mass-task-runtime-starter-sdk` for this roadmap; do not place the
  target read-view contract in `xa-mass-task-runtime` core, engine,
  engine-starter, `TaskManager`, or `sdk/xa-mass-embedded-sdk-api`.
- Prove starter is only the external handle/assembly host for read projection,
  not a read truth owner or runtime lifecycle owner.
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

## TRLC-2 Runtime Lifecycle Command State Machine

Goal: implement lifecycle commands inside `xa-mass-task-runtime`.

Scope:

- Add the minimal runtime command surface.
- Implement memory runtime first only if it is behind the same public runtime
  command interface used by Redis.
- Implement Redis runtime against agreed score-band keys and task-local meta.
- Command writes update task meta/gate/score directly.
- Append writes backlog only; score changes happen only if current runtime gate
  makes the task dispatchable.
- Do not solve caller message-id idempotency in this slice.

Acceptance:

- Contract tests prove command transitions update meta/gate/score without
  engine classes.
- Redis tests prove no old `dirty`, `ids`, or task-local `ready` key is needed
  for the command path.
- Claim rejects stale score candidates using runtime-owned fence/epoch facts,
  without duplicating lifecycle policy in engine.
- Runtime command tests prove lifecycle mutation does not require an engine
  command or query port.

## TRLC-3 Embedded SDK Direct Command Cutover

Goal: reroute task lifecycle writes away from engine.

Scope:

- Change embedded SDK task write methods to call task-runtime-starter command
  handle.
- Keep server/auth/request validation outside task-runtime.
- Preserve existing server HTTP route shapes unless a route is already proven
  obsolete by a separate route-classification decision.
- Keep engine startup for scheduling/dispatch roles only.
- Remove or quarantine `MassEngine` task lifecycle command methods as soon as
  their embedded callers are gone.

Acceptance:

- SDK command smoke proves create/append/seal/approve/pause/resume/block/cancel
  can execute without using engine `TaskCommandPort`.
- Source guard fails if embedded SDK lifecycle write code calls
  `MassEngine.*Task*`, `EngineConfig.getTaskCommandPort()`, engine
  `TaskCommandPort`, or `TaskManager`.
- Server API behavior is unchanged unless explicitly classified outside this
  roadmap.

## TRLC-4 Internal Scheduling Mechanism Cutover

Goal: make engine consume task-runtime score candidates instead of engine shell
status, without turning scheduling into a new public task API.

Scope:

- Add or converge an internal runtime scheduling handle over
  `task:score:{laneKey}`.
- Move `RuntimeReadyDispatchPump` / scheduling loops to score candidates.
- Replace `RuntimeReadyDispatchPump#isRuntimeDrivenBatchTask` READY/RUNNING
  filtering with runtime score-candidate filtering.
- Replace `TaskWorkerAssignListener#onTaskAssign` READY/RUNNING admission with
  runtime candidate/active-work facts.
- Remove `TaskWorkerAssignListener` READY -> RUNNING mutation as dispatch
  truth; public status becomes projection only.
- Keep worker selection/reservation in engine/worker-runtime.
- Claim backlog using the runtime candidate plus selected-worker reservation
  evidence.
- Remove scheduling decisions that treat mutable `Task.status` as the dispatch
  admission owner.
- Delete `syncRuntimeSchedulerEligibility` after the command path writes score
  directly.

Acceptance:

- Engine scheduling proof fails if task-runtime score candidate acquisition is
  bypassed.
- Tests prove paused/blocked/terminal score bands are not dispatched even if a
  stale engine shell status says otherwise.
- Tests prove `TaskWorkerAssignListener` and `RuntimeReadyDispatchPump` no
  longer gate dispatch by `TaskStatus.READY` / `TaskStatus.RUNNING`.
- `TaskRuntimeServingLane` is narrowed to scheduling/claim/result support or
  deleted; it no longer owns lifecycle command sync.
- No embedded SDK, server controller, or public starter API can call the
  scheduling/claim handle directly.
- If this slice creates serving active leases, it must include TRLC-5
  result/lease/finality proof in the same serving cutover. Otherwise this slice
  is non-serving proof only.

## TRLC-5 Result, Lease, And Terminal Lifecycle Closure

Goal: prevent active work from creating a second lifecycle owner.

Scope:

- Result apply, retry, lease expiry, finality, progress, and terminal candidate
  updates remain task-runtime-owned.
- Runtime finality updates task meta/gate/score.
- Engine consumes outcome facts for trace, worker resource release, and
  dispatch accounting.
- Engine does not close task lifecycle by shell-status rules after runtime
  finality has already decided the runtime state.

Acceptance:

- A real path proves append -> score candidate -> worker reservation -> claim
  -> dispatch -> result -> runtime finality -> projected terminal read.
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

Scope:

- Converge external reads through standalone `TaskReadViewPort` hosted by
  `sdk/xa-mass-task-runtime-starter-sdk`, outside `xa-mass-task-runtime` core.
- Treat existing `sdk/xa-mass-embedded-sdk-api` read contracts as migration
  residue: replace callers with the approved starter read-view surface, then
  delete or narrow the residue in the same closure plan.
- Project public status from descriptor metadata plus runtime meta/gate/score,
  active leases, progress, and finality.
- Delete or demote `TaskQueryPort`; it must not stay as public lifecycle/read
  owner.
- Delete read manager wrappers and bridge-only read implementations whose only
  job is forwarding to `TaskManager`, `MassEngine`, or another read bridge.
- Remove read paths that mutate score, repair lifecycle, or call command ports.
- Collapse result view, runtime view, diagnostics, and snapshots under the
  read-view surface unless a current caller proves a separate owner boundary.

Acceptance:

- `TaskManager` no longer implements `TaskQueryPort`.
- `TaskManager` does not implement or host `TaskReadViewPort`.
- There is no `TaskReadViewPort -> Manager -> bridge -> runtime` chain.
- `TaskReadViewPort` does not live in `xa-mass-task-runtime` core.
- Starter has exactly one approved read-view contract/handle and does not
  define a second same-purpose read API beside it.
- `TaskReadViewPort` is not duplicated in `sdk/xa-mass-embedded-sdk-api`.
- Read tests prove status projection changes after task-runtime command
  transitions without engine lifecycle writes.
- Diagnostics and snapshot reads cannot call lifecycle mutation APIs.
- Guard fails if a new public diagnostic/query port is added outside the
  read-view surface.

## TRLC-7 Old Path Deletion And Guards

Goal: remove the old lifecycle path and freeze the new owner boundary.

Scope:

- Delete the production `TaskManager` class and migrate useful methods to their
  real owner. Do not replace it with a renamed broad manager.
- Delete `EngineConfig.ensureTaskManager()` and production `new TaskManager(...)`
  construction paths.
- Delete or make package-private any remaining engine task lifecycle command
  ports.
- Delete or make internal any task port that is not command/read-view and does
  not protect a real cross-module owner boundary.
- Delete old guards that require `TaskRuntimeServingLane` lifecycle sync and
  replace them with owner guards.
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
- Guard fails if production source still contains `class TaskManager`,
  `ensureTaskManager()`, or `new TaskManager(...)`.
- Guard fails if a renamed replacement class combines task command, query,
  shell lifecycle maintenance, state runtime, and runtime serving-lane assembly
  responsibilities.

## Verification Candidates

Command names will settle during implementation; keep proof intent stable.

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeLifecycleCommandContractTest,TaskRuntimeCommandArchitectureGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeLifecycleCommandTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisTaskRuntimeLifecycleCommandTest,RedisTaskRuntimeScoreBandKeyspaceProofTest"
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk test "-Dtest=TaskRuntimeStarterHandleBoundaryTest,TaskRuntimeStarterNoLoopThreadTest,TaskRuntimeStarterPublicSurfaceGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EmbeddedSdkTaskRuntimeDirectCommandTest,EmbeddedSdkTaskCommandNoEngineGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=EngineSchedulingConsumesTaskRuntimeScoreTest,TaskRuntimeServingLaneNarrowingGuardTest,SimpleTaskDispatchBinderTest,TaskResultRuntimeConvergenceTest"
```

If a slice touches Spring/server assembly, add the relevant Spring context or
Boot-shell proof. Constructor-only tests are not enough for startup changes.

## Coordination Points

These are real design decisions, not reasons to defer the roadmap:

- Descriptor/shell owner: decide whether shell metadata remains in the current
  engine-owned store temporarily, moves behind starter command composition, or
  becomes a task-runtime descriptor store. It must not remain a lifecycle
  truth owner.
- Public `Task.status`: decide whether it is a stored projection/cache during
  migration or removed from write paths entirely. Dispatch must not read it as
  truth.
- Command vocabulary: approve/reject/block/pause/resume/cancel/terminate/seal
  should be enough for v0 unless a current caller proves otherwise.
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
- score is maintained by polling engine shell status
- a new ready list/set is introduced beside score-band without a separate
  high-ROI decision
- engine remains the only place that can approve, pause, resume, block, cancel,
  append, seal, or terminal-close a task
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
- Task-runtime meta/gate/score/backlog/work/result/finality is the lifecycle
  truth used by dispatch.
- Engine scheduling acquires task-runtime score candidates and no longer derives
  dispatch admission from engine shell status.
- Engine owns matching/worker selection/reservation, transport dispatch
  orchestration, and result-ingest/pull consumption only.
- Read APIs project task state from owner truth and do not mutate lifecycle.
- `TaskReadViewPort` is a standalone exposed read surface; it is not hosted by
  `TaskManager`, not duplicated beside the approved starter surface or in
  `embedded-sdk-api`, and not hidden behind bridge-only wrappers.
- `TaskManager` is physically removed from production code; useful methods are
  migrated to the real owner instead of retained under a narrower manager name.
- `TaskRuntimeServingLane` is deleted or narrowed so it cannot own lifecycle
  command sync.
- Old-path guards prove the embedded SDK -> engine lifecycle command chain
  cannot return silently.
