# Task Shell Storage CRUD De-Scoping Roadmap

Status: active on 2026-07-02. The read-provider isolation slice is closed:
`TaskReadOperations` no longer uses `TaskShellStore` as its read owner,
representative SDK/server reads are backed by read projection plus task-runtime
facts, and storage CRUD/query product tests were de-maintained. The roadmap is
not complete while `TaskShellStore`, `TaskShellRuntimeStore`,
`TaskShellLifecycleQuery`, `TaskShellRuntimeLifecycleQuery`, memory/JDBC task
shell stores, or `taskShellStore(...)` assembly APIs remain in maintained
production command/lifecycle paths.

The read-provider replacement is already a closed enabling slice, not the
design source for the remaining work. Do not continue polishing
`TaskReadOperations` or embedded-SDK read facades in this roadmap unless a core
server/SDK contract breaks. The remaining mainline is deletion/de-scoping of
`TaskShellStore`, `TaskShellRuntimeStore`, and storage-shaped engine task
dependencies.

Contract impact: storage/API maintenance contract revision. This roadmap owns
the removal or narrowing of task shell storage CRUD/query as a maintained API
surface, starting with engine's internal storage-task dependency. The primary
production cut line is the `TaskManager.taskStorage` /
`TaskShellRuntimeStore` -> `InMemoryTaskShellStore` / `JdbcTaskShellStore`
dependency, plus the storage-backed deadline query used by
`LeaseExpireWatchdog`. This roadmap does not require deleting `TaskManager` or
removing `EngineConfig.ensureTaskManager()`. It requires that any remaining
`TaskManager` path no longer depends on storage task CRUD/query. TRLC is the
score-band/lifecycle reference when command semantics need alignment, but this
roadmap must not drift into a read-only cleanup while that engine-internal
storage task chain remains. TRLC is also the controlling roadmap for lifecycle
state, status transition, terminal, score-band, and deadline-maintenance owner
decisions; TSDC must not invent a storage/read-view replacement for those
truths.
Task persistence is explicitly out of scope. Server route, request, and
response DTO contracts are not changed by this roadmap; list/paging/filter
parameters may use the documented v0 bounded candidate-set semantics.
Control-console/web display completeness is explicitly deferred when the
missing data is presentation-only and task execution plus core SDK/server
behavior remain intact.

Current execution evidence:

- `EngineTaskReadOperations` reads task detail/list/status/state/access from
  `TaskReadViewProjectionStore`, not `TaskShellStore`.
- `TaskReadViewPublishingTaskCommandPort` and engine event listeners publish
  create/patch/lifecycle/intake/counter metadata into the read projection after
  accepted command/runtime events without changing task command execution.
- Result windows, final rows, archive counts, work stats, and active leases
  remain sourced through task-runtime serving-lane reads.
- Server `/api/v1/tasks?status=...` applies the requested status predicate over
  bounded candidates before returning items.
- Whole-`Task` storage CRUD/lifecycle/index contract tests were deleted and
  replaced by `StorageBoundaryGuardTest` coverage that prevents those product
  tests from returning.
- Remaining `TaskShellStore`, `TaskShellRuntimeStore`,
  `TaskShellLifecycleQuery`, builder `taskShellStore(...)`, memory/JDBC store,
  and `TaskManager` storage-backed command/lifecycle dependencies are the open
  blockers for this roadmap. Closing them does not require deleting
  `TaskManager`; it requires removing the storage task dependency.

Scope anchor:

```text
TaskShellStore / TaskShellRuntimeStore storage-shaped task surface
  -> first remove engine internal storage-task dependency
  -> keep TaskManager as needed, but remove TaskManager.taskStorage -> storage task store chain
  -> close LeaseExpireWatchdog -> TaskShellRuntimeLifecycleQuery -> storage deadline query chain
  -> remove whole-Task CRUD/query from maintained task ownership
  -> delete or narrow storage API, runtime SPI, memory/JDBC implementations, and taskShellStore(...) assembly
  -> keep durable task persistence design out of this roadmap
  -> align score-band/lifecycle semantics with TRLC only where needed
  -> do not call the roadmap complete while those production callers remain

MassSdkApplication task-facing SDK surface
  -> keep TaskReadOperations / TaskAdminOperations / TaskStageEvidenceOperations semantics stable
  -> keep server routes, request DTOs, and response DTOs stable
  -> document v0 bounded candidate-set behavior for list/paging/filter params
  -> replace internal TaskReadOperations provider with task-runtime view projection
  -> remove TaskShellStore from read/query ownership
  -> use this as the enabling cut before storage CRUD deletion, not as final closure

current TaskShellStore / TaskShellRuntimeStore callers
  -> classify as engine-internal command blocker, lifecycle deadline blocker, fixture, or delete
  -> stop maintaining whole-Task CRUD as a product/API owner
  -> keep server/API contracts stable by preserving MassSdkApplication task-facing semantics
  -> replace internal read providers with read-view metadata projection plus runtime facts
  -> close engine-internal storage task blockers before physical storage surface removal
```

This roadmap exists because `TaskShellStore` and `TaskShellRuntimeStore` make
storage look like the task CRUD owner. That is the wrong maintenance contract.
The target is to remove that storage-shaped task owner surface. The
`TaskReadOperations` provider cutover exists only to make server/SDK reads stop
depending on `TaskShellStore` before deletion. It is a compatibility facade, not
the owner model and not the source for new task-runtime API design. The next
mainline is engine internal storage-task removal, not more read-view polish. The
target is not to delete `TaskManager` in this roadmap, not to invent a renamed
storage CRUD replacement, and not to push pure server/operator presentation
fields into task-runtime. Read-view metadata projection owns the read metadata
copy; task-runtime owns runtime facts. Storage view may still serve as a read
projection/cache, but never as lifecycle/runtime truth.

## Current Gap

Current code exposes task shell data through storage-shaped CRUD:

- `platform_infra/mass-storage-api` exposes `TaskShellStore` and
  `TaskShellLifecycleQuery`.
- `xa-mass-kernel-spi` exposes `TaskShellRuntimeStore` and
  `TaskShellRuntimeLifecycleQuery`.
- `InMemoryTaskShellStore` and `JdbcTaskShellStore` implement both storage API
  and kernel runtime SPI.
- `EngineConfig` casts `TaskShellStore` to `TaskShellRuntimeStore` /
  `TaskShellRuntimeLifecycleQuery` to construct `TaskManager`.
- `TaskManager` uses whole `Task` `saveTask(Task)` / `updateTask(Task)` as its
  current command and lifecycle persistence path.

Current risky facts:

- `TaskShellRuntimeStore` accepts whole mutable `Task` objects through
  `saveTask(Task)` and `updateTask(Task)`.
- `TaskShellStore` exposes the same whole-`Task` CRUD shape from storage API.
- Whole `Task` writes can carry `status`, `schedulable`, `startTime`,
  `endTime`, progress-like fields, and other lifecycle residue.
- `TaskShellRuntimeLifecycleQuery#pollTasksPastMaxRuntimeDeadline(...)` keeps
  shell storage in the lifecycle maintenance path.
- Storage implementations maintain status/project/deadline helper indexes.
  These are not runtime lifecycle truth. If any of them survive, they are
  read-view helper indexes or migration residue, not task owner state.
- `InMemoryTaskShellStore` and `JdbcTaskShellStore` are early storage fixtures,
  not current persistence strategy. Keeping them polished makes storage CRUD look
  more legitimate than it is.
- `EngineTaskReadOperations` currently backs `TaskReadOperations` with
  `TaskShellStore#getTask`, `listTasksPaged`, and `getTasksByStatus`. This makes
  server-visible task reads depend on the fat storage row even when the external
  `MassSdkApplication` / server API contract does not require that storage owner.

## Owner Decision

Storage task CRUD mutation is not a maintained owner surface.

Specifically:

- no new storage task CRUD mutation API
- no storage or read-model owner that accepts whole mutable base `Task` as its
  maintained carrier
- no maintenance expansion for `InMemoryTaskShellStore`
- no maintenance expansion for `JdbcTaskShellStore`
- no storage-backed lifecycle query as runtime terminal decision truth
- no storage-side scanner or sync loop to keep task rows aligned with runtime

Task runtime owns runtime facts. Read-view metadata projection owns the read
metadata copy.

Read metadata facts:

- task id
- task name
- project / tenant / submitter identity
- contract
- execution-spec metadata
- source ref
- shared config and other stable task attributes

Runtime facts:

- score-band visibility
- runtime meta/gate/epoch/lane
- backlog, retry, active lease, and result/finality state
- progress snapshots and result windows
- terminal/discard evidence

Task views split into read-view metadata projection, task-runtime facts, and
storage view:

- Read-view metadata projection reads descriptor/create/patch/command metadata
  into lean read lenses. First implementation may update these lenses directly
  without adding pagination/index infrastructure.
- Read-view metadata lenses are task read facts, not diagnostics and not
  task-runtime core hot-path truth. They can be read directly by task view code;
  the first implementation may write them from create/update command handling
  without introducing a separate storage owner.
- Command handling may read read-view metadata lenses as descriptor/input facts,
  such as project, shared config, execution spec, contract, source ref, default
  retry, and worker-group selector inputs. This is allowed only to remove the
  storage task row dependency. Metadata lenses must not own lifecycle/status,
  terminal reason, score-band visibility, dispatch eligibility, result finality,
  retry state, or worker assignment.
- Storage view is optional read projection/cache for server/operator needs. It
  must not be the owner of descriptor metadata, lifecycle, score, dispatch,
  result finality, retry, or terminal decisions.
- Storage view may lag, be rebuilt, or be absent. Mainline task lifecycle,
  scheduling, result, and descriptor/write correctness must not depend on it.
- `TaskReadViewPort`, if used, should compose read-view metadata projection and
  task-runtime runtime facts. It may consult a storage view only as temporary
  read-model residue with an explicit deletion or replacement target.
- Any storage view/read-model source must be read-only from the runtime
  perspective and must not drive score, dispatch, lifecycle mutation, result
  finality, retry, or terminal decisions.
- server `list/query` endpoints are allowed and expected. Their provider must
  stop exposing fat `Task` CRUD as the owner model; it may use read-view
  metadata projection plus runtime facts, or a temporary storage view cache.
- server route/request/response DTO contracts should remain stable; this
  roadmap changes provider wiring and data-source ownership, not public API
  shape. List/paging/filter behavior is explicitly v0 bounded candidate-set
  behavior.

SDK boundary decision:

- `MassSdkApplication` remains the stable task-facing SDK facade for this
  roadmap.
- `TaskReadOperations`, `TaskAdminOperations`, and
  `TaskStageEvidenceOperations` method semantics should remain server-compatible
  unless a separate API roadmap explicitly changes them.
- `TaskReadOperations` is this roadmap's server-compatible migration facade,
  not the final external read contract and not the source of new task-runtime
  API design. Replace its provider before deleting storage CRUD/query, so server
  behavior can stay stable while the source of truth moves. Do not add fields or
  methods here to compensate for storage deletion unless a core server/SDK
  contract fails.
- Do not add or preserve guards that make the long-term location or class name
  of `TaskReadOperations` / `EngineTaskReadOperations` mandatory. Guards in
  this roadmap must protect provider ownership and forbidden storage calls, not
  the migration facade's final placement.
- `TaskAdminOperations` may continue to call commands and then read snapshots,
  but those post-command snapshots must come from the read view, not storage.
- `TaskStageEvidenceOperations` is not driven by `TaskShellStore`; keep it
  separate unless a task-existence/read-view validation need is proven.

DTO compatibility decision:

- Public `TaskReadOperations` method signatures and existing snapshot DTO types
  remain unchanged in this roadmap. The work is provider replacement, not SDK
  DTO optimization.
- Do not backfill every field in existing wide SDK snapshots. Many fields were
  display/legacy placeholders and do not justify keeping storage query alive.
- First implementation fills only fields needed by current command semantics,
  access checks, server route assembly, and runtime/result correctness.
- The goal is not to keep control-console/web display visually complete. The
  goal is to keep server routes and integration tests functionally intact while
  removing storage query ownership.
- Task execution logic has higher priority than read-view migration. Provider
  replacement must not change create, append, seal, approve, pause, block,
  resume, cancel, dispatch, result ingest, retry, lease repair, finality, or
  transport/worker-selection behavior.
- Pure display fields default to `null`, empty, or `0` in v0. Do not add
  storage query, projection indexes, or metadata fields only to make display
  output look complete.
- Web/control-console display-only gaps are non-core residue in this roadmap
  when server route shape, integration tests, authorization/access checks, and
  task execution behavior remain intact.
- If an existing core test, server route, SDK command flow, authorization path,
  or runtime/result path fails because a field became empty, fill the field from
  task metadata lenses or runtime facts. Do not weaken core test assertions just
  to make the provider cutover pass.
- Test deletion or assertion relaxation is allowed only for clearly obsolete
  non-core view/storage tests that protect the old `TaskShellStore` query
  contract rather than the public SDK/server behavior.
- `listTaskSummaries(offset, limit)` is a bounded summary read, not a true
  pagination contract in this roadmap.
- `getTaskSummariesByStatus(status)` keeps the method signature for
  server-compatible migration, but v0 does not need to implement true global
  status indexing or an unbounded scan. It must still apply the requested
  status predicate inside the bounded candidate set before results are returned
  to `/api/v1/tasks?status=...`.
- Server-side project/keyword filtering applies only to the bounded candidate
  set returned by the read facade in v0; this is an accepted temporary read
  behavior while removing storage query ownership. The status parameter may be
  bounded, but it must not be ignored.

Runtime-first degradation policy:

- This roadmap accepts a deliberate runtime-first pain period. Runtime owner
  clarity, score-band/lifecycle correctness, task execution, and removal of
  storage query ownership take priority over complete UI/display projection.
- Display-only fields, list ordering, exact counters, presentation timestamps,
  and complete filter/pagination behavior may be incomplete in v0 when they do
  not affect task submission, append admission, access checks, scheduling,
  dispatch, result convergence, final reads, or terminal behavior.
- Do not compensate for display gaps by preserving `TaskShellStore` as a query
  owner, adding storage-driven runtime truth, or forcing presentation-only
  fields into task-runtime hot paths.
- Any display gap that remains after a slice must be recorded as non-core
  read-view residue. Any task execution or core API behavior regression remains
  a blocker.

DTO reference scope for the first read-provider cut:

| DTO | Current use scope | First implementation field policy |
| --- | --- | --- |
| `TaskDetailSnapshot` | create shell post-read, command before/after read, resume detail, server get/detail/review assembly | Fill `taskId`, `status`, `terminalReason`, `intakeStatus`, plus metadata fields needed by server/integration tests. Counters, timestamps, hold fields, source/display fields, min/peak worker fields default to `0`/`null`/empty in v0 unless a core path actually requires them. |
| `TaskSummarySnapshot` | server task list assembly and bounded candidate filtering | Fill `taskId`, best-effort `taskName`, `project`, `tenantId`, `userId`, `contract`, `status`, `terminalReason`, `executionSpec` only when available or required by server/integration tests. Counters default to `0`; `updateTime` may be `null`; no true pagination or global status index required. Bounded status filtering must still exclude non-matching statuses from status-filtered responses. |
| `TaskStateSnapshot` | server update outcome and SDK state read | Fill `taskId`, `status`, `terminalReason`, `intakeStatus` from runtime/meta projection. |
| `TaskAccessSnapshot` | append worker-group selector resolution and access checks | Fill `taskId`, `project`, `sharedConfig`, `intakeStatus`; this is functional, not optional display data. |
| `TaskResultWindowSnapshot`, `TaskWorkFinalSnapshot`, `TaskResultArchiveSnapshot` | result reads, sync append, archive endpoints, review materialization | Source from runtime result/finality facts only. Do not synthesize from storage/projection rows. |
| `TaskWorkStatsSnapshot`, `TaskActiveLeaseSnapshot`, `TaskStateValidationSnapshot`, `TaskStateResolutionSnapshot` | diagnostics and validation views | Source from runtime facts where available; return empty/zero diagnostics when not available rather than preserving storage query. |

This roadmap may add or expose read-only view/query handles if inventory proves
a read need. It must not add task create/lifecycle command ownership, storage
CRUD mutation replacement APIs, or engine bridges.

Persistence decision:

- Current task-shell storage mutation interfaces and implementations are delete
  or narrowing targets.
- Do not design task persistence in this roadmap.
- Durable task persistence, if needed later, must be a separate owner decision.
  It must not revive whole-`Task` CRUD/query as the owner shape.

## Target Surface

The target is deletion or de-scoping of the fat task storage CRUD surface plus
explicit read projection:

```text
TaskShellStore                    -> delete or de-scope whole-Task CRUD
TaskShellRuntimeStore             -> delete
TaskShellLifecycleQuery           -> delete or de-scope lifecycle truth
TaskShellRuntimeLifecycleQuery    -> delete
InMemoryTaskShellStore            -> deletion target or temporary read-model residue
JdbcTaskShellStore                 -> deletion target or temporary read-model residue
taskShellStore(...) assembly APIs  -> engine storage-dependency residue or delete
TaskReadOperations                 -> server-compatible migration facade only
TaskReadViewPort                  -> read-view projection over metadata + runtime facts
task-runtime read/query ports      -> runtime facts only
read-view metadata projection/lens -> read metadata copy only
storage view/read-model            -> optional temporary lean projection/cache only
```

Rules:

- Storage task CRUD mutation must not be documented, tested, or extended as a
  supported public API.
- `TaskShellStore.saveTask(Task)`, `TaskShellStore.updateTask(Task)`, and the
  whole `Task` storage CRUD family must be removed.
- Any remaining storage task interface or implementation must be classified as
  read-model residue, test fixture, or engine storage-dependency compile
  residue. It must not remain a mutation owner.
- In-memory/JDBC task shell stores must not receive new persistence behavior,
  lifecycle helpers, or compatibility adapters.
- Removing `TaskManager.taskStorage` is removal, not replacement. Do not add a
  new task shell store, descriptor store, runtime task store, or command-local
  CRUD facade that accepts or returns whole mutable `Task`.
- Facts currently carried by the storage `Task` row must be split by owner:
  descriptor/read metadata goes to read projection/lenses; intake/lifecycle
  gate and score-band visibility go to task-runtime owner facts; backlog and
  retry/work state stays in task-runtime work/result facts; max-runtime
  candidate evidence must come from non-storage runtime/lifecycle evidence;
  server/SDK read snapshots are published to read projection.
- `TaskManager`, if retained, may orchestrate existing command flow, locking,
  runtime append, event publication, and dispatch wakeup. It must not load,
  save, update, delete, or scan a whole `Task` row.
- Task-runtime view/query support is read-only in this roadmap.
- Task runtime facts returned to callers must be derived from task-runtime data.
- Task metadata returned to callers should come from read-view metadata
  projection/lenses. First implementation may update metadata directly and use
  bounded scans before adding dedicated indexes.
- Read-view metadata publication is allowed as a create/patch/command-side
  projection side effect only. It may write metadata lenses or lean read-view
  projection rows, but it must not change lifecycle, score, dispatch, terminal,
  retry, result finality, or worker assignment semantics.
- Storage view data may be used only as temporary read-model/cache residue, not
  as the task metadata owner and not through a fat mutable `Task` DTO.
- Temporary storage view/cache cannot accept or return `Task`, cannot expose
  `saveTask`, `updateTask`, or `deleteTask`, cannot own status/deadline/lifecycle
  truth, and must have an explicit deletion or replacement target.
- Query support must not become create, approve, pause, resume, cancel, seal,
  terminate, dispatch, score mutation, or result mutation.
- Server API shape is out of scope. If a field cannot be produced from
  read-view metadata projection or task-runtime facts yet, classify it as a
  read-view gap or temporary storage-view residue instead of reviving
  whole-`Task` CRUD.
- If a current command/lifecycle caller still depends on storage CRUD, this
  roadmap records it as the next engine-internal deletion blocker. Close it
  by splitting the required facts to their owners or stop; do not change
  lifecycle behavior by inventing another storage-shaped owner.

## Relationship To TRLC

This roadmap is separate from
[TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md](TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md).

TRLC owns the target score-band lifecycle direction. This roadmap owns removal
of the storage-shaped task CRUD/query surface from engine and storage APIs.
Those are separate scopes: read-query provider cutover finished first only to
unblock storage dependency removal; the next mainline is engine-internal
storage-task removal. Storage surface closure cannot finish while `TaskManager`
or lifecycle watchdog code still requires storage-shaped CRUD/query.

Execution posture:

- This roadmap is not the full task-runtime lifecycle convergence plan and not
  a `TaskManager` deletion plan.
- Its first closed-loop purpose was to remove `TaskShellStore` and storage
  query APIs from the maintained task read owner path.
- Its remaining closed-loop purpose is to remove or narrow `TaskShellStore`,
  `TaskShellRuntimeStore`, lifecycle query surfaces, memory/JDBC task shell
  stores, and `taskShellStore(...)` assembly APIs by first removing storage
  task dependencies from engine internals.
- `TaskManager` may remain as an engine orchestrator during this roadmap, but it
  must not remain the storage task owner.

Execution rule:

- Do not use this roadmap to invent a broad new task create/lifecycle design.
- Do not introduce a new runtime task CRUD owner or renamed storage CRUD owner
  here.
- Do not preserve storage CRUD as the fallback during this storage dependency
  cutover.
- If deleting a storage API breaks current command/lifecycle mainline, stop and
  split the required facts to their owners or mark it as the current blocker.
  Do not reclassify it as harmless residue and do not expand into full
  `TaskManager` removal.
- Query-only callers move to read projection surfaces under this roadmap before
  storage CRUD deletion.
- Do not solve durable task persistence until runtime task ownership has a
  stable target and proof.

## Non-Goals

- Do not add `TaskDescriptorStore` or any similar interface as a CRUD/mutation
  replacement. A later lean read-model/view contract is allowed only if it does
  not accept whole mutable `Task`.
- Do not add `TaskRuntimeTaskStore` or task-runtime command CRUD here.
- Do not remove `TaskManager` here.
- Do not change task create, lifecycle, dispatch, or result mainline behavior.
- Do not redesign server task display routes in the first slice.
- Do not rewrite frontend/control-console task pages in this roadmap.
- Do not treat control-console/web visual completeness as a completion blocker
  when the issue is presentation-only and core task execution is intact.
- Do not add storage-side schedulers, scanners, queues, or lifecycle repair.
- Do not enhance `InMemoryTaskShellStore` or `JdbcTaskShellStore`.
- Do not decide durable task persistence here.
- Do not preserve whole-`Task` update compatibility as a maintained API.
- Do not force pure server/operator presentation-only fields into task-runtime
  just because a view needs them.
- Do not preserve the fat `Task` DTO as the read-model contract.
- Do not change server route, request, or response DTO contracts. List/paging/
  filter parameter behavior is governed by the documented v0 bounded
  candidate-set semantics.

## TSDC-0 Inventory And Cut Line

Goal: classify all task shell storage/runtime API use before changing contracts.

Scope:

- Inventory all uses of:
  - `TaskShellStore`
  - `TaskShellLifecycleQuery`
  - `TaskShellRuntimeStore`
  - `TaskShellRuntimeLifecycleQuery`
  - `InMemoryTaskShellStore`
  - `JdbcTaskShellStore`
  - whole `Task` `saveTask` / `updateTask`
  - `getTasksByStatus` / `getTasksByProject`
  - `pollTasksPastMaxRuntimeDeadline`
  - public-ish `taskShellStore(...)` / `setTaskShellStore(...)` assembly and
    builder entry points in SDK, engine-starter, and server configuration
  - `MassSdkApplication` task-facing methods from `TaskReadOperations`,
    `TaskAdminOperations`, `TaskStageEvidenceOperations`, and task methods still
    inherited through `MassRuntimeControl`
  - `EngineTaskReadOperations` methods that currently call `TaskShellStore`
  - task-runtime read/query ports and `TaskReadViewPort`
    candidates
  - server list/query routes and SDK list/query methods that currently need
    task summaries, filters, paging, or status/project selection
- Classify each caller as:
  - command/lifecycle mainline residue
  - lifecycle query residue
  - runtime read/query need
  - read-view metadata projection need
  - storage view/read-model residue
  - server/public read-view need
  - storage adapter residue
  - early persistence residue
  - test fixture
  - delete
- Decide which read needs can be answered by current task-runtime read ports,
  which require read-view metadata projection/lenses, and which remain
  temporary storage-view residue.
- Any task information or list/filter capability not currently present in
  read-view metadata projection or task-runtime data is either a read-view gap
  or temporary storage-view residue, not a reason to preserve whole-`Task` CRUD.
- Record the current server/API route/request/response DTO contract as stable
  unless a separate server roadmap explicitly changes it. Record list/paging/
  filter behavior as v0 bounded candidate-set semantics.

Acceptance:

- There is a concrete caller table for engine, engine-starter, embedded SDK,
  task-runtime starter SDK, task-runtime core, server, storage adapters, and
  tests.
- Every whole-`Task` write is either engine storage-dependency residue or a
  deletion target.
- `TaskShellRuntimeStore` is classified as delete, not a target API.
- `TaskShellStore` and whole-`Task` storage CRUD methods are classified as
  delete, not a parked or de-maintained owner surface.
- `InMemoryTaskShellStore` and `JdbcTaskShellStore` are classified as
  delete targets or temporary read-model residues, not mutation owner targets.
- Public-ish `taskShellStore(...)` / `setTaskShellStore(...)` entry points are
  classified as engine storage-dependency residue or deletion targets, not
  maintained extension APIs.
- Read-only callers have a runtime-fact target, read-view metadata projection
  target, temporary storage-view residue target, or a named view gap.
- Every `TaskReadOperations` method has a target provider source:
  read-view metadata lens, task-runtime runtime fact, temporary storage view,
  or named gap.
- Every `TaskReadOperations` method is also classified as one of:
  migration facade to `TaskReadViewPort`, temporary SDK compatibility method, or
  deletion/narrowing target for TRLC.
- `TaskAdminOperations` methods that return or inspect snapshots identify their
  post-command read source as `TaskReadOperations` / `TaskReadViewPort`, not
  `TaskShellStore`.
- `TaskStageEvidenceOperations` is confirmed independent of `TaskShellStore`, or
  any task-existence validation is routed through the read view.
- Existing guards that pin `TaskReadOperations` or `EngineTaskReadOperations`
  class placement are identified as migration guards to rewrite or delete when
  provider ownership is proven.
- Server/API route/request/response DTO contracts remain unchanged or have a
  named separate server roadmap if change is unavoidable. List/paging/filter
  behavior is explicitly v0 bounded candidate-set semantics.

## TSDC-1 SDK Task Read Boundary Cutover

Goal: keep the external SDK/server task-read semantics stable while removing
`TaskShellStore` from the read owner path.

Scope:

- Keep `MassSdkApplication` as the server-facing task SDK facade.
- Keep `TaskReadOperations` method names, return DTOs, null/not-found behavior,
  and result/archive semantics stable unless inventory proves a specific method
  impossible without a separate API roadmap. List/paging/filter parameters keep
  compatibility shape but use v0 bounded candidate-set semantics.
- Treat `TaskReadOperations` as an adapter over the approved read view, not as a
  newly protected final read surface.
- Replace `EngineTaskReadOperations` direct `TaskShellStore` reads with a
  read-view provider that composes:
  - read-view metadata projection/lenses for task id, name, project, tenant,
    submitter, contract, execution spec, source ref, shared config, and stable
    task attributes
  - task-runtime runtime facts for state, progress, active leases, result
    windows, final rows, archive readiness, and diagnostics
  - temporary storage view/cache only as explicit residue
- Add or update a source/architecture guard that fails if the read provider
  imports `TaskShellStore` or calls `getTask`, `listTasksPaged`,
  `getTasksByStatus`, or `getTasksByProject` as its read owner.
- Add or update representative status-filter proof so mixed bounded candidates
  do not leak non-matching task statuses into status-filtered server responses.
- Rewrite any guard that requires `EngineTaskReadOperations` to remain the
  package-private implementation; that class may be deleted or replaced once
  provider ownership is proven.
- Keep `TaskAdminOperations` snapshot reads after create/command execution on
  the same read-view provider.
- Keep `TaskStageEvidenceOperations` routed to its current evidence owner unless
  task metadata validation is needed; then validate through the read view.
- Do not change server controller routes, request bodies, or response DTOs.

Acceptance:

- `MassSdkApplication` still implements the same task-facing SDK interfaces used
  by server wiring.
- Representative server task read routes pass with the provider backed by the
  read view rather than `TaskShellStore`.
- Status-filtered server reads prove bounded filtering still excludes
  non-matching statuses; v0 may be incomplete, but it must not ignore the
  requested status parameter.
- `EngineTaskReadOperations`, or its replacement, has no direct
  `TaskShellStore#getTask`, `listTasksPaged`, `getTasksByStatus`, or
  `getTasksByProject` dependency for read ownership.
- No guard in this roadmap requires `TaskReadOperations` to remain in
  `embedded-sdk-api` as the final contract or requires `EngineTaskReadOperations`
  to remain as a named implementation.
- `createTaskShell`, `executeTaskCommand`, and `resumeTaskDetailed` read
  post-command snapshots through `TaskReadOperations` / `TaskReadViewPort`, not
  storage.
- Result/archive/final/active-lease reads remain sourced from task-runtime
  runtime facts.
- Server route, request, and response DTO contracts are unchanged.

## TSDC-2 Early Storage Surface Deletion Guard

Goal: make it explicit that storage task CRUD and early task-shell storage
implementations are no longer supported maintenance surfaces.

Scope:

- Remove roadmap/doc language that treats `TaskShellStore` as a target task API.
- Remove or revise storage API tests that preserve whole-`Task` CRUD as a
  desired product contract.
- Add a guard that prevents new task storage CRUD mutation methods or
  descriptor-store replacement APIs that preserve whole-`Task` mutation.
- Add a guard that prevents new production behavior on `InMemoryTaskShellStore`
  / `JdbcTaskShellStore` outside same-slice deletion support.
- If a temporary storage view/cache remains, narrow it to a lean read projection:
  it must not return or accept `Task`, must not expose `saveTask`, `updateTask`,
  or `deleteTask`, and must not own status/deadline/lifecycle truth.
- Require every temporary storage view/cache row to name a deletion or
  replacement target.
- Keep any existing code only as temporary compile residue while production
  callers still require it; this residue is not a maintained API and must be
  deleted when the current engine storage-task blocker is closed.

Acceptance:

- No active roadmap or owner doc describes `TaskShellStore` as the target task
  CRUD owner.
- No new storage task CRUD mutation API is introduced.
- A guard fails while `TaskShellStore.saveTask(Task)` /
  `TaskShellStore.updateTask(Task)` style task storage contracts are treated as
  maintained surfaces.
- Existing storage task CRUD and early storage implementations are labelled
  deletion targets, temporary read-model residues, or engine storage-dependency
  deletion blockers in the inventory.
- Any temporary storage view/cache residue is lean-read only, has no whole-Task
  CRUD methods, and has an explicit deletion or replacement target.

## TSDC-3 Read Projection Support

Goal: support read-only task queries without using fat `Task` CRUD as the
owner model.

Scope:

- Identify runtime facts already available from task-runtime read ports:
  progress snapshot, active work, result correlation, final result rows, result
  window, and score/meta candidates where appropriate.
- Identify task metadata fields needed by server and SDK reads, such as
  task name, project, contract, execution-spec display, source ref, submitter,
  and shared-config display.
- Define read-view metadata projection/lenses for those fields. First
  implementation may update metadata directly and use bounded metadata scans;
  do not add status/project/pagination indexes until a separate high-ROI
  decision proves the need.
- Keep metadata write ownership with task command/runtime command handling. Do
  not introduce storage writes as the authoritative metadata commit path.
- Define the read-view metadata publication point. It may run after
  create/patch/command acceptance and publish metadata for reads, but it must be
  side-effect free with respect to lifecycle, score, dispatch, terminal, retry,
  result finality, and worker assignment.
- Add or expose read projection capabilities needed by server and SDK reads,
  such as task summaries, pagination, status filters, project filters, and
  point task lookup, without reusing the fat mutable `Task` DTO.
- For list/filter reads, record the v0 degraded semantics explicitly:
  - `listTaskSummaries(offset, limit)` is a bounded summary read; `offset` and
    `limit` may be accepted for compatibility but do not require true pagination
    or stable ordering.
  - `getTaskSummariesByStatus(status)` is compatibility-only in v0; it may use
    the same bounded summary source and does not need true global status
    indexing or unbounded scanning, but it must apply status filtering inside the
    bounded candidate set.
  - project/keyword filtering is best-effort over the bounded candidate set.
    Complete indexed filtering is a separate read-index/API decision.
- Expose read-only handles through `xa-mass-task-runtime-starter-sdk` only when
  current assembly cannot reach the needed task-runtime view/read surface.
- Define `TaskReadViewPort` rules for server/public views as read projection
  over read-view metadata projection plus task-runtime facts.
- Preserve existing server route/request/response DTO contract while changing
  only the provider data source.
- Keep query APIs read-only and side-effect free.

Acceptance:

- Query callers do not need `TaskShellStore` for runtime progress/result/work
  facts.
- Query callers do not need the fat `Task` DTO or whole-`Task` storage rows as
  the owner model for any task information.
- Server/API list and query endpoints are backed by a read projection, not
  direct `TaskShellStore#getTasksByStatus`, `getTasksByProject`, or
  `listTasksPaged`.
- First implementation may provide bounded task metadata scan and point lookup;
  lack of optimized pagination/filter indexes is a known performance gap, not a
  lifecycle correctness blocker.
- Server-visible list/filter behavior is explicitly degraded in v0 as bounded
  candidate-set filtering. This is acceptable for read-path completion because
  the roadmap's goal is removing storage query ownership, not preserving storage
  index semantics.
- Status-filtered responses must still exclude non-matching statuses from the
  bounded candidate set. Incomplete coverage is allowed; ignoring the requested
  status is not.
- Any future requirement for true pagination, stable ordering, or complete
  status/project/keyword filtering must be handled by a separate read-index/API
  decision, not by reviving `TaskShellStore`.
- Existing core tests for server route behavior, SDK command/read behavior,
  authorization, task access, result/archive/finality, and runtime diagnostics
  are not relaxed to tolerate missing fields introduced by this cutover. Fill
  required fields from metadata lenses or runtime facts instead.
- Existing task execution behavior is not relaxed to make the read-provider
  cutover pass. A regression in create/append/dispatch/result/finality/retry or
  lease repair is a mainline failure, not a view compatibility issue.
- Server and integration tests are treated as behavior protection for this
  roadmap. If they fail because the replacement provider omits a field, either
  populate that field from task metadata/runtime facts or prove the test is a
  non-core obsolete view/storage test before deleting it.
- Only obsolete tests whose purpose is to preserve `TaskShellStore` query/index
  behavior may be deleted or relaxed, and those deletions must be called out in
  the slice summary.
- Read-view metadata publication writes only metadata/read projection fields and
  does not mutate lifecycle, score, dispatch, terminal, retry, result finality,
  or worker assignment.
- Server/API route/request/response DTO contract does not change in this
  roadmap.
- Task-runtime view/query support does not mutate score, backlog, lease, result,
  terminal, lifecycle state, or metadata.
- Runtime read-view projection does not become a command path.
- Tests prove representative read queries work without storage task CRUD.
- Tests must not rely on `InMemoryTaskShellStore` as the query owner.

## TSDC-4 Engine Internal Storage Task Removal

Goal: remove engine's internal dependency on storage-shaped task CRUD/query.
This is the mainline after the read-provider isolation slice. `TaskManager` may
remain; `taskStorage` and storage-backed deadline polling may not.
This slice is TRLC-dependent for any lifecycle/status/terminal fact it touches:
if removing storage CRUD requires deciding how lifecycle state is written,
projected, rescored, or repaired, stop and route that decision through TRLC
instead of adding a temporary storage/read-view lifecycle owner.

Scope:

- Remove the `TaskManager.taskStorage` field and constructors that require
  `TaskShellRuntimeStore`.
- Remove `TaskManager` use of whole mutable `Task` `saveTask(Task)`,
  `getTask`, `updateTask(Task)`, and `deleteTask`.
- Remove `TaskManager.pollTasksPastMaxRuntimeDeadline(...)` delegation to
  `TaskShellRuntimeLifecycleQuery`.
- Keep or narrow `EngineConfig.ensureTaskManager()` as needed, but it must not
  construct a `TaskManager` with `TaskShellRuntimeStore` /
  `TaskShellRuntimeLifecycleQuery`.
- Split current `Task` row facts by owner, not by a new CRUD interface:
  - existence and descriptor metadata: read projection/lenses or command input;
    command handling may read metadata lenses for descriptor/input facts, but
    never through `getTask()` returning base `Task`
  - intake gate and lifecycle/terminal state: task-runtime owner facts and
    score-band transitions
  - append admission inputs such as default retry and worker-group selectors:
    descriptor/read metadata lenses or command input
  - append backlog and work/result state: task-runtime work/result facts
  - command readbacks and server/SDK snapshots: read projection publication
- Close `TaskLifecycleService.storeTask(...)` as a storage write path. Command
  handlers may publish read projection updates and runtime state transitions,
  but must not persist a whole `Task` row.
- Close `LeaseExpireWatchdog` dependency on storage deadline polling as a
  same-behavior source migration: max-runtime termination semantics must remain
  unchanged while the expired-candidate source moves to non-storage
  runtime/lifecycle evidence. If that evidence is not available, this slice
  stops before deleting `TaskShellRuntimeLifecycleQuery`.
- Do not create `TaskDescriptorStore`, `TaskRuntimeTaskStore`, or another
  whole-Task CRUD landing zone to make this compile.
- Remove storage adapter implementation of kernel runtime SPI when no production
  caller remains.
- Rewrite test fixtures that instantiate `InMemoryTaskShellRuntimeStore`.
- Do not replace those fixtures with `InMemoryTaskShellStore` as a new runtime
  owner.

Acceptance:

- Engine production code has no `TaskShellRuntimeStore` or
  `TaskShellRuntimeLifecycleQuery` dependency.
- `EngineConfig` does not cast `TaskShellStore` to kernel task runtime SPI.
- `TaskManager`, if still present, has no `taskStorage` field, no constructor
  dependency on task storage, and no whole-`Task` CRUD dependency.
- No production interface introduced by this slice exposes `getTask`,
  `saveTask`, `updateTask`, `deleteTask`, `listTasks*`, or
  `pollTasksPastMaxRuntimeDeadline` over base `Task`.
- `TaskLifecycleService.storeTask(...)` no longer writes a storage task row.
- Command paths may read metadata lenses for descriptor/input facts, but no
  command path uses metadata lenses as lifecycle/status/terminal/score truth.
- Existing create/append/seal/approve/pause/resume/block/cancel/terminate
  behavior remains covered while storage writes are removed.
- `LeaseExpireWatchdog` does not source max-runtime candidates from storage
  task rows, and focused proof shows max-runtime termination behavior is
  preserved.
- Successful command paths still publish read projection metadata/status/intake
  needed by server/SDK read snapshots without using storage task rows.
- `xa-mass-kernel-spi` no longer exposes task shell runtime storage interfaces,
  or they remain only as deletion residue with no production caller.
- Memory/JDBC task shell stores do not implement kernel task runtime SPI once
  engine callers are gone.
- Guard fails if a new runtime-facing task shell store is added.

## TSDC-5 Storage CRUD Physical Removal

Goal: stop maintaining whole-`Task` storage CRUD after read callers have moved
and engine command/lifecycle paths no longer depend on storage task CRUD/query.

Scope:

- Remove or narrow `TaskShellStore` / `TaskShellLifecycleQuery` when there is no
  production mutation/lifecycle caller.
- If production command/lifecycle code still requires them, keep them only as
  temporary compile residue and mark the caller as an engine storage-dependency
  blocker. Do not extend tests, docs, or APIs around them.
- Remove `InMemoryTaskShellStore` / `JdbcTaskShellStore` as fat `Task` CRUD
  implementations after current callers are gone. Until then, they are
  temporary compile or read-model residue only; do not add new mutation
  features, lifecycle indexes, migrations, or proof around them.
- Remove `getTasksByStatus`, `getTasksByProject`, and deadline query use from
  lifecycle/dispatch paths. If status/project filters remain for server view,
  they must be read-model filters over projected status/project fields, not
  runtime decision inputs.
- Keep server/operator runtime reads on runtime read handles or
  `TaskReadViewPort`.
- Keep server list/query behavior by moving its source to read projection APIs
  instead of fat `Task` storage indexes.
- Do not remove or reshape server list/query routes as part of storage CRUD
  removal.

Acceptance:

- Storage task CRUD is removed from maintained APIs.
- `TaskShellStore.saveTask(Task)` / `TaskShellStore.updateTask(Task)` are not
  exposed by any maintained API.
- `InMemoryTaskShellStore` and `JdbcTaskShellStore` are removed, or every
  remaining reference is explicitly listed as engine storage-dependency
  temporary compile residue or temporary read-model residue with no
  lifecycle/mutation tests/docs.
- No maintained contract test treats storage task CRUD as product behavior.
- No read-only caller uses fat `Task` CRUD as the view owner.
- No read-only caller sources runtime facts from storage task rows.
- Server list/query does not use storage status/project/list indexes as
  lifecycle or dispatch truth.
- Server list/query route/request/response DTO contracts are unchanged.
- No production command/lifecycle caller requires storage task CRUD. If such a
  caller remains, this slice is not closed.

## Stop Conditions

Stop and re-plan if a slice requires any of these:

- changing externally observable task create/lifecycle/dispatch/result behavior
  instead of same-behavior source migration away from storage
- breaking task execution behavior or hiding such a regression behind a
  read-view/provider migration
- changing `MassSdkApplication` task-facing SDK method semantics to make the
  provider migration easier
- changing server task route, request DTO, or response DTO contracts to make
  provider migration easier
- adding task-runtime command CRUD in this roadmap
- adding another storage task CRUD mutation replacement
- adding a fat task view DTO that mirrors mutable base `Task`
- adding or improving task persistence in this roadmap
- adding new behavior to `InMemoryTaskShellStore` or `JdbcTaskShellStore`
- maintaining whole mutable `Task` update as a supported API
- treating `TaskShellStore`, `saveTask(Task)`, or `updateTask(Task)` as a
  frozen-but-maintained API surface
- adding storage scanner/sync logic for runtime progress
- making storage fields runtime truth for query convenience
- sourcing runtime facts from storage/projection rows instead of runtime data
- sourcing server list/query from fat `Task` storage indexes instead of a
  read-model/projection surface
- turning `TaskReadViewPort` into a mutation path
- using read-view metadata lenses as lifecycle/status/terminal/score truth
- changing server route/request/response DTO contract to make provider
  migration easier
- deleting a storage API while `TaskReadOperations` still depends on it as the
  read owner
- deleting a storage API while a production command/lifecycle caller still
  depends on it and no non-storage dependency has replaced that caller's need
- replacing `TaskManager.taskStorage` with another interface that can load,
  save, update, delete, list, or deadline-scan whole mutable `Task`

## Read-Path Milestone Criteria

This milestone proves that reads no longer block `TaskShellStore` deletion. It
does not complete the roadmap by itself.

- `MassSdkApplication` task-facing SDK method semantics remain
  server-compatible.
- `TaskReadOperations` is treated as a migration facade over the approved read
  view, not as the final read contract or a newly protected location.
- `TaskReadOperations` provider no longer uses `TaskShellStore` as the read
  owner.
- Runtime read needs are served by task-runtime read handles or
  `TaskReadViewPort`.
- Server/API list/query needs are served by read projection APIs over
  read-view metadata projection plus task-runtime facts, with storage view only
  as temporary lean projection/cache residue.
- Runtime facts exposed by this roadmap's query path are derived from runtime
  data; metadata/display fields are read-view metadata projection facts, not
  storage CRUD facts.
- Existing public SDK/server snapshot DTO types and method signatures are
  unchanged. Fields that are required by current core tests or functional paths
  are populated from metadata lenses or runtime facts; only non-core obsolete
  storage-query tests may be deleted or relaxed.
- Task execution behavior is unchanged and still proven by the existing
  command/dispatch/result/finality/retry/lease-repair coverage relevant to the
  touched slice. Web/control-console display-only gaps may be recorded as
  non-core residue and do not block read-path completion.
- Non-core display residue is explicitly listed when introduced or discovered,
  and is not fixed by reviving storage CRUD/query ownership.
- Server and integration tests remain meaningful proof for read-path cutover;
  do not weaken them to hide missing provider fields.
- Read-view metadata publication writes only metadata/read projection fields and
  does not mutate lifecycle, score, dispatch, terminal, retry, result finality,
  or worker assignment.
- Any temporary storage view/cache residue is lean-read only: it does not
  accept or return `Task`, does not expose `saveTask`, `updateTask`, or
  `deleteTask`, does not own status/deadline/lifecycle truth, and has an
  explicit deletion or replacement target.
- Server route, request, and response DTO contracts are unchanged by this
  roadmap. List/paging/filter behavior may use the documented v0 bounded
  candidate-set semantics; true pagination/filter/index semantics are explicitly
  deferred to a separate read-index/API decision.
- No storage CRUD mutation replacement API is introduced.
- No task persistence design is introduced by this roadmap.
- No whole-`Task` storage CRUD/query contract is protected by active product
  tests.
- Source/architecture guard proves the read provider cannot satisfy the read
  path by calling `TaskShellStore#getTask`, `listTasksPaged`,
  `getTasksByStatus`, or `getTasksByProject`.

## Final Physical Deletion Criteria

These criteria complete this roadmap. They depend on engine command/lifecycle
paths no longer requiring storage task CRUD/query; they do not require deleting
`TaskManager`.

- `TaskShellStore` and `TaskShellLifecycleQuery` are deleted from storage API
  mutation/lifecycle surfaces, or narrowed so they no longer expose fat `Task`
  CRUD/query.
- `TaskShellStore.saveTask(Task)` / `TaskShellStore.updateTask(Task)` and the
  whole-`Task` storage CRUD family are removed from maintained code paths.
- `TaskShellRuntimeStore` and `TaskShellRuntimeLifecycleQuery` are deleted.
- `InMemoryTaskShellStore` / `JdbcTaskShellStore` are deleted from maintained
  production and test-support mutation paths, or reduced to explicitly
  temporary lean read-model residue.
- No remaining command/lifecycle use of storage CRUD exists in maintained
  production paths. If a caller still exists, the roadmap remains active and the
  caller stays listed as a deletion blocker.

## Verification Candidates

Existing proof commands:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-storage-api "-Dtest=StorageBoundaryGuardTest" test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=EngineStarterBackdoorGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=MassSdkApplicationTaskReadBoundaryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=TaskApiControllerTest,TaskApiListControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Required new proof before TSDC-4 closure:

- engine ownership guard fails if production engine/starter code imports
  `TaskShellRuntimeStore` or `TaskShellRuntimeLifecycleQuery`.
- engine ownership guard fails if `EngineConfig` casts `TaskShellStore` to
  kernel task runtime SPI.
- engine ownership guard fails if `TaskManager` has a `taskStorage` field,
  accepts a whole-`Task` CRUD dependency, or calls whole-`Task`
  `getTask/saveTask/updateTask/deleteTask`.
- deadline source proof shows max-runtime termination behavior is unchanged
  after `LeaseExpireWatchdog` stops using storage deadline scan.
- command/read behavior proof shows create/append/seal/lifecycle command paths
  still publish read projection state without writing storage task rows.

## Do Not Start With

- Do not start by adding `TaskDescriptorStore` as a CRUD/mutation replacement.
- Do not start by adding `TaskRuntimeTaskStore`.
- Do not start by deleting `TaskShellStore` before the `TaskReadOperations`
  provider is cut over.
- Do not start by changing task create/lifecycle command flow.
- Do not start by removing `TaskManager`.
- Do not start by improving `InMemoryTaskShellStore` or `JdbcTaskShellStore`.
- Do not start by designing task persistence.
- Do not start by changing server routes or frontend task views.
- Do not start by adding an engine bridge around storage CRUD.
- Do not start by adding a scanner to keep storage rows in sync with runtime
  progress.
