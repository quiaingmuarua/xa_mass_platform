# Task Worker Runtime History Boundary Roadmap

Status: proposed convergence roadmap.

This roadmap protects a core boundary:

- engine and worker-runtime expose current runtime state and lifecycle command
  surfaces
- DB/control-plane storage owns stable task shell, worker declaration,
  seed/admin input, and bounded light snapshots only
- historical task state, worker connections, scheduling, dispatch, results,
  usage statistics, and analytics belong to trace -> queue -> archive read
  models
- history/read-model output must not participate in runtime decisions

The issue is not only whether an API has `getAll`. The deeper issue was that
the old `TaskStorage` and `WorkerStorage` names exposed broad storage
contracts to engine/runtime. TWH-1A renamed those contracts, and the remaining
work is to keep the new `TaskShellStore` / `WorkerDeclarationStore` surfaces
from regrowing runtime state, history, or analytics queries.

Read with:

- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../platform_infra/README.md](../platform_infra/README.md)
- [../xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md](../xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md)
- [../xa-mass-worker-runtime/README.md](../xa-mass-worker-runtime/README.md)
- [../xa-mass-worker-runtime/CONTRACTS.md](../xa-mass-worker-runtime/CONTRACTS.md)

## Current Facts

- `TaskShellStore` currently lives in `mass-storage-api` and is documented as
  the task shell/control-plane contract.
- `TaskShellStore` no longer exposes `getSchedulableTasks()` or max-runtime
  deadline polling. Max-runtime shell lifecycle scanning is isolated behind
  `TaskShellLifecycleQuery.pollTasksPastMaxRuntimeDeadline(...)`.
- `TaskManager` already has a runtime-first dispatchable path through
  `TaskWorkRuntime.readyTaskIds(limit)`.
- `TaskWorkRuntime` owns ready queue membership, delayed visibility, lease
  ownership, retry timing, runtime counters, and result apply truth.
- `WorkerDeclarationStore` currently lives in `mass-storage-api` and is
  documented as a control-plane worker row abstraction, not runtime scheduling
  truth.
- Current production worker declaration storage implementation found in this
  slice is `InMemoryWorkerDeclarationStore`; no JDBC worker declaration
  implementation was found.
- `WorkerResourceOwner` writes worker registration rows to
  `WorkerDeclarationStore` and projects them into `WorkerRegistry` slots.
- `WorkerManager` publishes worker resource/current-state APIs from worker
  declaration rows, `WorkerRegistry`, capability reports, and transport
  reachability views.
- `Worker` still mixes stable declaration fields with runtime-flavored fields
  such as `status`, `lastHeartbeat`, compatibility supported project/event
  hints, and helper methods like `updateHeartbeat()`.
- WorkerGroup is now the capability owner. Worker rows should not become a
  second project/event capability source.
- `WorkerStateProjectionOwner` keeps bounded recent reports as current
  diagnostic evidence. That bounded window is not an archive/history owner and
  must not be treated as durable analytics truth.
- `DB_STORAGE_PRINCIPLES.md` already bans queue/lease history, worker
  online/offline churn, heartbeat streams, locks, reservations, dispatch
  history, result history, and analytics from DB ownership.

## Owner Review

This review treats the boundary as a runtime correctness issue, not naming
cleanup.

1. The old `TaskStorage` contract was too broad for the desired architecture.
   `saveTask`, `getTask`, `updateTask`, delete, status/project/list reads were
   retained under `TaskShellStore` as task shell/control-plane operations.
   `getSchedulableTasks()` was removed from storage because it was a
   scheduling-admission query. Max-runtime shell lifecycle polling now lives
   behind `TaskShellLifecycleQuery`, so it is not confused with queue/lease
   expiry truth.
2. The old `WorkerStorage` contract was also too broad. Its implementation
   acted like a worker declaration row store, but the old name and `Worker`
   model made it easy to persist runtime-flavored fields such as status and
   heartbeat. That remains a TWH-3 model-split risk once a JDBC worker
   declaration implementation appears.
3. `TaskManager` already has the correct runtime-first path for dispatchable
   work: `TaskWorkRuntime.readyTaskIds(limit)` followed by bounded shell
   lookup. That is the model to strengthen. Storage-level schedulable scans are
   the residue to remove.
4. `WorkerManager` projects declaration rows into `WorkerRegistry` slots.
   That projection is acceptable only as current-state setup/cache. It must not
   become a durable worker connection or dispatch history store.
5. Trace emission already exists in engine hot paths through
   `ExecutionEventSink` / `TraceEventLogger`. The missing piece is not to make
   storage bigger; it is to make the trace -> queue -> archive path the place
   where historical analysis is materialized.
6. The rename impact crosses the embedding SDK public surface. `MassSdk`,
   `MassApplicationBuilder`, `MassEngineBuilder`, and `EngineConfig` expose
   `taskStorage(...)`, `workerStorage(...)`, `setTaskStorage(...)`, and
   `setWorkerStorage(...)`. This roadmap intentionally treats the rename as a
   breaking embedded-SDK cleanup, not a deprecated compatibility track, because
   keeping old and new methods would preserve the bad abstraction as a live
   public seam.
7. The old `TaskRuntimeMaintenancePort` mixed runtime lease maintenance with
   current task-shell lifecycle scanning. It has been split into lease,
   dispatch-wakeup, and task-shell lifecycle ports so watchdog/listener wiring
   does not expose broad storage or a mixed maintenance surface.
8. Current task shell query methods need a disposition path after
   classification. `listTasksPaged(...)`, `getTasksByStatus(...)`, and
   `getTasksByProject(...)` are acceptable only as current shell/support views.
   If any is classified as history-shaped, it must be deferred until the
   archive/read-model pipeline exists; it should not be migrated into a broader
   DB query API.
9. `WorkerResourceRecord` is not yet a clean declaration record. It still
   carries runtime or compatibility projection fields such as `statusName`,
   `lastHeartbeat`, `supportedProjects`, and `supportedEventCodes`. TWH-3 must
   decide whether `WorkerResourceRecord` becomes a current-state composite view
   or is split into declaration and runtime projection records.
10. `EngineConfig` has real internal coupling between task shell storage and
    compatibility projection storage: the default `InMemoryTaskShellStore` is
    both `taskShellStore` and `taskDetailStore`, and
    `setTaskShellStore(...)` clears the detail store when both fields alias the
    same object. TWH-1 must preserve that explicit fallback-breaking behavior
    under the new names.
11. TWH-5 is a design/checkpoint slice. It should not be treated like an
    implementation slice. Its output is a trace/archive gap note or updates to
    trace contracts, not runtime/storage code.

Conclusion: the target should be stricter than "storage is control-plane." The
rule should be:

```text
engine / worker-runtime answer current runtime state;
DB stores control-plane, seed/admin input, and bounded light snapshots;
history and analytics come from trace -> queue -> archive;
history/read-models never drive runtime decisions.
```

## Boundary Decision

Use three distinct surfaces:

```text
control-plane declaration / shell
  TaskShellStore / WorkerDeclarationStore
  stable task shell truth, worker identity/declaration, WorkerGroup/node
  binding, rule/principal/catalog truth, seed/admin input, bounded light
  current operator snapshots

runtime current state
  TaskWorkRuntime / TaskResultRuntime / WorkerRegistry / worker-runtime
  ready queue, delayed visibility, active leases, runtime result apply,
  worker reachability, dispatch gates, reservations, candidate source,
  current capability projection

history / analytics
  trace -> queue -> archive pipeline
  task item timelines, attempts, dispatch decisions, candidate rejection
  history, worker connection timelines, result timelines, usage statistics,
  cross-task analysis
```

The target is not "remove DB". The target is to keep DB/control-plane storage
limited to stable current truth, seed/admin inputs, and bounded light
snapshots. Engine/runtime should consume named owner ports such as task shell
commands, task shell queries, runtime queues, worker declarations, and worker
current-state views, not broad `TaskStorage` / `WorkerStorage` seams.

Read models are one-way outputs. They may serve UI, debugging, analytics, and
operator review. They must not feed matching, dispatch, lease acceptance,
retry, result convergence, or worker reachability decisions.

## Non-Goals

- Do not implement durable task/worker history tables in JDBC.
- Do not add synchronous DB writes for dispatch, callback, retry, lease,
  heartbeat, online/offline, reservation, candidate rejection, or result
  history.
- Do not route scheduling decisions through control-plane DB scans.
- Do not route runtime decisions through archive/history/read-model output.
- Do not preserve old and new storage names as two public seams. Converge
  in-repo callers.
- Do not keep deprecated `taskStorage(...)` / `workerStorage(...)` builder
  aliases unless a later release decision explicitly chooses a public
  compatibility window. This roadmap assumes no such window.
- Do not make trace/archive the source of runtime correctness. Trace is for
  history, replay assistance, debugging, and analytics.
- Do not introduce a generic repository abstraction that hides whether data is
  control-plane, runtime, or trace-shaped.

## TWH-0 Inventory And Classification

Artifact: [TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_INVENTORY.md](./TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_INVENTORY.md)

Scope:

- Inventory every production and test caller of:
  - `TaskStorage`
  - `WorkerStorage`
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
  - `JdbcTaskStorage`
  - every `TaskStorage` method:
    `saveTask`, `getTask`, `updateTask`, `deleteTask`, `listTasksPaged`,
    `getTasksByStatus`, `getTasksByProject`, `getSchedulableTasks`, and
    `pollExpiredMaxRuntimeTasks`
  - every `WorkerStorage` method:
    `addWorker`, `getWorker`, `updateWorker`, `deleteWorker`,
    `getWorkersByGroupId`, and `getAllWorkers`
  - `TaskManager.getSchedulableTasks()`
  - `TaskManager.pollExpiredMaxRuntimeTasks(...)`
  - `TaskRuntimeMaintenancePort` method categories
  - `WorkerResourceOwner.getAllWorkers()`
  - `WorkerManager.workers()`
  - SDK/server task and worker read models
- Classify each caller as one of:
  - task shell / worker declaration control-plane
  - runtime current-state
  - lifecycle maintenance over current shells
  - support/debug read model
  - test fixture
  - history/analytics-shaped residue
- Identify fields on `Task` and `Worker` that are stable shell/declaration truth
  versus runtime projection or compatibility residue.
- Decide target names before moving code. Recommended names:
  - `TaskShellStore`
  - `WorkerDeclarationStore`
  - `TaskShellQueryPort`
  - `WorkerDeclarationQueryPort`

Acceptance:

- A committed inventory table lists every caller and classification.
- No implementation starts before ambiguous callers are classified.
- The inventory explicitly marks `getSchedulableTasks()` as runtime-shaped
  residue that must not remain a storage contract.
- The inventory classifies `listTasksPaged`, `getTasksByStatus`, and
  `getTasksByProject` as current shell queries or history-shaped queries before
  they are renamed or retained.
- If a task shell query is classified as history-shaped, mark it deferred until
  archive/read-model ownership exists. Do not migrate it to a new DB query
  surface in this roadmap.
- The inventory classifies every `TaskRuntimeMaintenancePort` method as
  runtime maintenance, current-shell lifecycle maintenance, dispatch wakeup, or
  mixed residue.
- The inventory explicitly states that current JDBC does not provide worker
  runtime/history storage.

## TWH-1 Rename Broad Storage Contracts

Goal: remove broad storage vocabulary without changing runtime behavior.

This phase is intentionally split. A single PR that renames task storage,
worker storage, implementation classes, SDK builder APIs, config methods, docs,
and all tests is too large to review safely. Each sub-slice should compile and
should not preserve old names as compatibility aliases.

### TWH-1A Rename Contracts And Implementations

Scope:

- Rename `TaskStorage` to a task-shell/control-plane name.
- Rename `WorkerStorage` to a worker-declaration/control-plane name.
- Rename memory/JDBC implementations accordingly.
- Update direct in-repo imports and constructor signatures needed to compile.
- Keep embedding SDK method names for TWH-1B if needed to keep this slice
  reviewable, but do not introduce new compatibility adapters.
- Keep behavior unchanged in this slice.

Acceptance:

- No production import of `TaskStorage` or `WorkerStorage` remains.
- Storage implementations use shell/declaration names.
- No compatibility alias type remains for old storage names.
- `mass-storage-api` README uses shell/declaration vocabulary for the renamed
  contracts.
- Architecture guards prevent new storage contract methods whose names imply
  scheduling, dispatch, lease, heartbeat, runtime history, or analytics.

### TWH-1B Rename SDK And Config Surfaces

Scope:

- Update SDK builder/config names so embedding callers do not see
  `taskStorage(...)` or `workerStorage(...)` as generic runtime/history
  extension points.
- Treat the embedded SDK rename as a breaking change in this roadmap. Update:
  - `MassSdk.EngineOptions`
  - `MassApplicationBuilder.EngineBuilder`
  - `MassEngineBuilder`
  - `EngineConfig`
  - `xa-mass-sdk/README.md`
- Add a concise migration table, for example:
  - `taskStorage(...)` -> `taskShellStore(...)`
  - `workerStorage(...)` -> `workerDeclarationStore(...)`
  - `getTaskStorage()` -> `getTaskShellStore()`
  - `getWorkerStorage()` -> `getWorkerDeclarationStore()`
- Preserve and rename the `EngineConfig` task shell/detail-store coupling
  deliberately:
  - default in-memory shell store may still also implement the compatibility
    detail store for local/test use
  - replacing the task shell store must still require an explicit compatible
    detail store when the old default alias is broken
  - do not let the rename silently reintroduce an implicit
    "task shell store also means detail store" rule
- Document that this repository is still `0.0.1-SNAPSHOT`; breaking embedded
  SDK renames are allowed inside this snapshot line. If a release version is
  cut before TWH-1, revisit whether a short compatibility window is required.
- Keep behavior unchanged in this slice.

Acceptance:

- No `taskStorage(...)`, `workerStorage(...)`, `setTaskStorage(...)`, or
  `setWorkerStorage(...)` embedding SDK API remains.
- The migration table is committed with the slice so downstream breakage is
  intentional and searchable.
- Tests cover the renamed `EngineConfig` shell-store/detail-store alias break
  behavior.
- The SDK README states this is a `0.0.1-SNAPSHOT` breaking rename with no
  compatibility alias in this roadmap.
- No old SDK/config method remains as a deprecated fallback.

### TWH-1C Global Vocabulary And Guard Sweep

Scope:

- Update global docs after the code rename has landed:
  - `platform_infra/README.md`
  - `INFRA_TRUTH_LAYERS.md`
  - `DB_STORAGE_PRINCIPLES.md`
  - SDK/server README and API references touched by renamed surfaces
- Add or update architecture guards for the renamed shell/declaration boundary.
- Keep this as documentation and guard convergence only.

Acceptance:

- `platform_infra/README.md`, `INFRA_TRUTH_LAYERS.md`, and
  `DB_STORAGE_PRINCIPLES.md` use shell/declaration vocabulary consistently.
- New code cannot reintroduce `TaskStorage` / `WorkerStorage` as broad
  runtime/history extension-point names.

## TWH-2 Move Runtime-Shaped Task Queries Out Of Storage

Scope:

- Remove `getSchedulableTasks()` from the storage contract.
- Ensure dispatchable task discovery starts from runtime:
  `TaskWorkRuntime.readyTaskIds(limit)` followed by bounded shell lookup.
- Review `pollExpiredMaxRuntimeTasks(...)`.
  - Max task runtime is a lifecycle policy over current task shells.
  - It may be backed by an indexed shell query, but it must not be presented as
    runtime queue/lease truth.
  - Prefer a lifecycle-specific owner port name such as
    `TaskShellLifecycleQuery.pollTasksPastMaxRuntimeDeadline(...)`.
- Split or rename `TaskRuntimeMaintenancePort` so lease-runtime operations and
  current task-shell lifecycle scanning are not presented as one runtime truth
  category. A split is preferred if the implementation remains readable:
  - `TaskLeaseMaintenancePort`: active leases, expired leases, lease expiry
  - `TaskShellLifecycleMaintenancePort`: max-runtime deadline scanning and
    terminal lifecycle action over current shells
  - `TaskDispatchWakeupPort`: runtime dispatch readiness and dispatch request
    wakeup
  - `TaskRuntimeRecoveryPort`: runtime ready-task discovery for startup and
    polling recovery
- Ensure watchdog code talks to engine lifecycle/maintenance ports, not a raw
  storage contract.
- Add guards in the same slice:
  - storage contracts do not expose `schedulable` or dispatch-admission
    methods
  - runtime dispatch recovery does not call shell/control-plane scans

Acceptance:

- Storage contract does not expose "schedulable" or queue-admission language.
- Runtime dispatchable work recovery does not use control-plane DB scans.
- Max-runtime task termination remains bounded and current-shell scoped, not a
  historical task execution query.
- The watchdog-facing port name makes clear whether a method reads runtime
  lease state or current task-shell lifecycle state.
- Guard coverage fails if `getSchedulableTasks()` or equivalent storage-level
  scheduling admission returns.

## TWH-3 Split Worker Declaration From Runtime Projection

Goal: make worker declaration persistence impossible to confuse with active
worker runtime state.

This is the highest-risk phase. It must not be implemented as one rename-only
commit because the current code still maps `WorkerResourceRecord <-> Worker`
and `WorkerResourceOwner.normalizeWorkerRegistrationRow()` can write runtime
heartbeat data before persistence.

### TWH-3A Decide And Add Worker Declaration Shape

Scope:

- Decide the target model shape before moving code:
  - `WorkerDeclarationRecord`: persisted declaration-only input/output
  - `WorkerRuntimeStateRecord`: current runtime state such as status,
    heartbeat freshness, dispatch gate, reachability, and reservation/load
    evidence
  - `WorkerResourceRecord`: either renamed to the declaration record or kept as
    a composite current-state read model assembled from declaration + runtime
    evidence
- Stable declaration candidates:
  - `workerId`
  - `workerGroupId`
  - `adapterNodeId`
  - `adapterId`
  - transport hint / online strategy
  - static attributes
  - declared max concurrency
  - create/update timestamps
- Runtime/current-state fields must not be declaration-store truth:
  - `status`
  - `lastHeartbeat`
  - active online/offline state
  - active dispatch gate state
  - active reservation/capacity usage
  - lock/lease state
  - candidate/rejection/dispatch/result history
- WorkerGroup remains capability truth. Worker-level supported project/event
  fields remain compatibility read hints only until removed or projected from
  WorkerGroup.
- Decide initial online semantics before persistence changes:
  - worker registration may create/update a `WorkerRegistry` slot
  - active online state must come from transport reachability, heartbeat, or
    current registry metadata
  - declaration persistence must not be the source of heartbeat freshness

Acceptance:

- The roadmap or implementation doc states the final role of `Worker`,
  `WorkerDeclarationRecord`, `WorkerRuntimeStateRecord`, and
  `WorkerResourceRecord`.
- The initial online/heartbeat semantics are written before
  `WorkerResourceOwner` persistence changes.
- No production behavior changes are required in this sub-slice unless adding
  the value type requires mechanical compile updates.

### TWH-3B Move Declaration Store Writes To Declaration Records

Scope:

- Introduce a declaration-shaped record. Do not keep `WorkerDeclarationStore`
  writing the mixed `Worker` model directly.
- `WorkerResourceOwner` registration writes the declaration record only.
- `WorkerResourceOwner.normalizeWorkerRegistrationRow()` no longer mutates a
  declaration row with runtime heartbeat data before persistence.
- Declaration rows must not store `status` or `lastHeartbeat`.
- Worker runtime derives `WorkerRegistry` slot metadata from declaration rows
  plus current runtime evidence.
- Add guards that fail if worker declaration persistence writes heartbeat,
  online/offline churn, lock/lease, or dispatch history fields.

Acceptance:

- Declaration-store writes cannot persist heartbeat or online churn as durable
  truth.
- `WorkerDeclarationStore` accepts declaration-shaped input rather than a
  `Worker` object carrying `status` / `lastHeartbeat`.
- `WorkerResourceOwner.normalizeWorkerRegistrationRow()` no longer mutates a
  declaration row with runtime heartbeat data before persistence.
- Worker runtime derives `WorkerRegistry` slot metadata from declaration rows
  plus current runtime evidence.

### TWH-3C Define Worker Current-State Read Models

Scope:

- `WorkerManager.workers()` may continue returning a current-state/composite
  view, but that view must be assembled above the declaration store rather than
  stored as a declaration row.
- Decide whether `WorkerResourceRecord` remains a composite current-state read
  model or is renamed.
- Server worker read models label fields clearly as declaration, runtime,
  transport reachability, or compatibility projection.
- Document that `WorkerStateProjectionOwner` bounded recent reports are current
  diagnostic evidence, not durable archive/history truth.

Acceptance:

- Server worker read models label fields clearly as declaration, runtime,
  transport reachability, or compatibility projection.
- `WorkerResourceRecord` role is explicit and no longer described as
  runtime-neutral if it contains runtime/current-state fields.
- Bounded worker state report history is documented as diagnostic current-state
  evidence only.

## TWH-4 Current-State API Guardrails

Scope:

- Review task/worker APIs exposed by SDK/server:
  - task shell list/status/project reads
  - task result window/archive reads
  - worker list and capability read model
  - worker state reports
  - worker command/status endpoints
- Make naming and docs explicit that these are current-state, task-local
  result, or support/debug read models, not historical analytics APIs.
- Add architecture tests where useful:
  - engine and worker-runtime current-state APIs do not depend on archive or
    analytics stores
  - storage modules do not import runtime scheduling owners
  - JDBC module does not implement heartbeat/dispatch/attempt/history worker
    tables
  - storage contracts do not grow methods named after scheduling, lease,
    dispatch, heartbeat, or analytics concerns
- Prefer adding the relevant guard in the slice that creates the boundary.
  TWH-4 is the final sweep, not the first time guards appear.

Acceptance:

- Public/server task and worker query docs do not imply historical retention
  unless they explicitly point to archive/trace.
- New task/worker current-state APIs must state their canonical layer.
- Architecture tests block new storage-side runtime/history contracts.

## TWH-5 Trace/Event Archive Direction Checkpoint

Scope:

- Define the minimal task/worker history events that should enter
  trace/event/archive later, without making runtime wait for that pipeline:
  - task shell created / sealed / approved / terminal
  - item appended / claimed / dispatched / retried / completed
  - worker declared / declaration changed
  - transport connected / disconnected
  - heartbeat stale / recovered
  - dispatch gate disabled / cleared
  - candidate selected / rejected reason
  - dispatch delivered / failed
  - result accepted / rejected
- Decide whether these are existing `ExecutionEvent` names, new trace event
  names, or a later archive materialized view.
- First compare the candidate event list with
  `platform_infra/mass-trace-sink/.../ExecutionEventType.java`, because
  `ExecutionEventType` is the stable trace vocabulary. Record:
  - existing event types that already cover the candidate
  - candidates that are archive materialized views rather than new trace events
  - true vocabulary gaps requiring a new `ExecutionEventType`
- Keep emission async and non-authoritative for runtime correctness.
- Treat this as a design checkpoint. Do not add runtime/storage code in this
  slice unless the trace contract update itself requires a compile-time enum or
  schema addition.

Acceptance:

- A follow-up trace/archive design note exists before any durable task/worker
  history store is introduced.
- Runtime hot-path writes are not blocked on archive/analytics availability.
- Analytics requirements are expressed against trace/archive read models, not
  engine/runtime or control-plane storage APIs.
- The suggested implementation order marks this as a checkpoint/documentation
  slice, not a prerequisite for TWH-1 through TWH-4 code cleanup.

## TWH-6 Remove Compatibility Residue

Scope:

- Remove or demote worker-level supported project/event capability hints once
  current server/read-model consumers use WorkerGroup capability views.
- Remove old task/worker storage wording from SDK docs and tests.
- Delete obsolete helper methods that make `Task` or `Worker` look like runtime
  queue/heartbeat owners if callers have moved to explicit runtime APIs.

Acceptance:

- WorkerGroup-first capability has no worker-row fallback in production
  scheduling or catalog read models.
- Task scheduling has no storage-level schedulable-task fallback.
- Runtime/history terms in shell/declaration code are either removed or marked
  compatibility-only with a deletion path.
- The proof registry or testing index points to current-state tests and
  trace/archive proof gaps separately.

## Suggested Implementation Order

1. TWH-0 inventory.
2. TWH-1A rename storage contracts and implementations.
3. TWH-2 move runtime-shaped task queries out of storage.
4. TWH-1B rename SDK/config surfaces.
5. TWH-1C global vocabulary and guard sweep.
6. TWH-3A decide/add worker declaration shape.
7. TWH-3B move declaration-store writes to declaration records.
8. TWH-3C define worker current-state read models.
9. TWH-4 current-state API guardrail sweep.
10. TWH-5 trace/archive history direction checkpoint.
11. TWH-6 residue removal.

Do not start with TWH-5. A trace/archive plan cannot compensate for a
misnamed storage/runtime boundary. First make the current ownership explicit,
then add history ingestion/read models as a separate async path.

## Verification

Minimum local checks per implementation slice:

```bash
mvn -pl platform_infra/mass-storage-api,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc,xa-mass-engine,xa-mass-worker-runtime -am test
mvn -pl xa-mass-sdk,xa-mass-server -am -DskipTests compile
```

When server task/worker read models change, also run focused server tests around
task, catalog, and worker APIs plus at least one external worker registration
E2E.

When trace/archive direction changes, update `TRACE_CONTRACT.md`,
`INFRA_TRUTH_LAYERS.md`, and `DB_STORAGE_PRINCIPLES.md` in the same change.
