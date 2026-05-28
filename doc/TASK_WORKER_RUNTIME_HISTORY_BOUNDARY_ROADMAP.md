# Task Worker Runtime History Boundary Roadmap

Status: proposed convergence roadmap.

This roadmap protects a core boundary:

- engine and worker-runtime expose current runtime state and lifecycle command
  surfaces
- control-plane storage owns stable task shell and worker declaration truth
- historical task/worker connections, scheduling, dispatch, results, and
  analytics belong to trace/event/archive read models

The issue is not only whether an API has `getAll`. The deeper issue is that
`TaskStorage` and `WorkerStorage` are exposed to engine/runtime as broad storage
contracts. That makes it easy for future work to query runtime state, history,
or analytics from control-plane DB APIs.

Read with:

- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../platform_infra/README.md](../platform_infra/README.md)
- [../xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md](../xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md)
- [../xa-mass-worker-runtime/README.md](../xa-mass-worker-runtime/README.md)
- [../xa-mass-worker-runtime/CONTRACTS.md](../xa-mass-worker-runtime/CONTRACTS.md)

## Current Facts

- `TaskStorage` currently lives in `mass-storage-api` and is documented as the
  task control-plane aggregate contract.
- `TaskStorage` still exposes runtime-shaped query methods:
  `getSchedulableTasks()` and `pollExpiredMaxRuntimeTasks(...)`.
- `TaskManager` already has a runtime-first dispatchable path through
  `TaskWorkRuntime.readyTaskIds(limit)`, but still delegates
  `getSchedulableTasks()` and max-runtime polling to `TaskStorage`.
- `TaskWorkRuntime` owns ready queue membership, delayed visibility, lease
  ownership, retry timing, runtime counters, and result apply truth.
- `WorkerStorage` currently lives in `mass-storage-api` and is documented as a
  control-plane worker row abstraction, not runtime scheduling truth.
- Current production worker storage implementation found in this slice is
  `InMemoryWorkerStorage`; no JDBC `WorkerStorage` implementation was found.
- `WorkerResourceOwner` writes worker registration rows to `WorkerStorage` and
  projects them into `WorkerRegistry` slots.
- `WorkerManager` publishes worker resource/current-state APIs from worker
  declaration rows, `WorkerRegistry`, capability reports, and transport
  reachability views.
- `Worker` still mixes stable declaration fields with runtime-flavored fields
  such as `status`, `lastHeartbeat`, compatibility supported project/event
  hints, and helper methods like `updateHeartbeat()`.
- WorkerGroup is now the capability owner. Worker rows should not become a
  second project/event capability source.
- `DB_STORAGE_PRINCIPLES.md` already bans queue/lease history, worker
  online/offline churn, heartbeat streams, locks, reservations, dispatch
  history, result history, and analytics from DB ownership.

## Boundary Decision

Use three distinct surfaces:

```text
control-plane declaration / shell
  TaskShellStore / WorkerDeclarationStore
  stable task shell truth, worker identity/declaration, WorkerGroup/node
  binding, rule/principal/catalog truth, bounded current operator summaries

runtime current state
  TaskWorkRuntime / TaskResultRuntime / WorkerRegistry / worker-runtime
  ready queue, delayed visibility, active leases, runtime result apply,
  worker reachability, dispatch gates, reservations, candidate source,
  current capability projection

history / analytics
  trace/event/archive pipeline
  task item timelines, attempts, dispatch decisions, candidate rejection
  history, worker connection timelines, result timelines, cross-task analysis
```

The target is not "remove DB". The target is to keep DB/control-plane storage
limited to stable current truth and bounded shell summaries. Engine/runtime
should consume named owner ports such as task shell commands, task shell
queries, runtime queues, worker declarations, and worker current-state views,
not broad `TaskStorage` / `WorkerStorage` seams.

## Non-Goals

- Do not implement durable task/worker history tables in JDBC.
- Do not add synchronous DB writes for dispatch, callback, retry, lease,
  heartbeat, online/offline, reservation, candidate rejection, or result
  history.
- Do not route scheduling decisions through control-plane DB scans.
- Do not preserve old and new storage names as two public seams. Converge
  in-repo callers.
- Do not make trace/archive the source of runtime correctness. Trace is for
  history, replay assistance, debugging, and analytics.
- Do not introduce a generic repository abstraction that hides whether data is
  control-plane, runtime, or trace-shaped.

## TWH-0 Inventory And Classification

Scope:

- Inventory every production and test caller of:
  - `TaskStorage`
  - `WorkerStorage`
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
  - `JdbcTaskStorage`
  - `TaskManager.getSchedulableTasks()`
  - `TaskManager.pollExpiredMaxRuntimeTasks(...)`
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
- The inventory explicitly states that current JDBC does not provide worker
  runtime/history storage.

## TWH-1 Rename Broad Storage Contracts

Scope:

- Rename `TaskStorage` to a task-shell/control-plane name.
- Rename `WorkerStorage` to a worker-declaration/control-plane name.
- Rename memory/JDBC implementations accordingly.
- Update SDK builder/config names so embedding callers do not see
  `taskStorage(...)` or `workerStorage(...)` as generic runtime/history
  extension points.
- Keep behavior unchanged in this slice.

Acceptance:

- No production import of `TaskStorage` or `WorkerStorage` remains.
- No compatibility alias remains for old names.
- `mass-storage-api` README uses shell/declaration vocabulary.
- `platform_infra/README.md`, `INFRA_TRUTH_LAYERS.md`, and
  `DB_STORAGE_PRINCIPLES.md` use shell/declaration vocabulary consistently.

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
- Ensure watchdog code talks to engine lifecycle/maintenance ports, not a raw
  storage contract.

Acceptance:

- Storage contract does not expose "schedulable" or queue-admission language.
- Runtime dispatchable work recovery does not use control-plane DB scans.
- Max-runtime task termination remains bounded and current-shell scoped, not a
  historical task execution query.

## TWH-3 Split Worker Declaration From Runtime Projection

Scope:

- Introduce a declaration-shaped record if needed, instead of persisting the
  mixed `Worker` model directly.
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

Acceptance:

- Declaration-store writes cannot persist heartbeat or online churn as durable
  truth.
- Worker runtime derives `WorkerRegistry` slot metadata from declaration rows
  plus current runtime evidence.
- Server worker read models label fields clearly as declaration, runtime,
  transport reachability, or compatibility projection.

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

Acceptance:

- Public/server task and worker query docs do not imply historical retention
  unless they explicitly point to archive/trace.
- New task/worker current-state APIs must state their canonical layer.
- Architecture tests block new storage-side runtime/history contracts.

## TWH-5 Trace/Event Archive Direction For History

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
- Keep emission async and non-authoritative for runtime correctness.

Acceptance:

- A follow-up trace/archive design note exists before any durable task/worker
  history store is introduced.
- Runtime hot-path writes are not blocked on archive/analytics availability.
- Analytics requirements are expressed against trace/archive read models, not
  engine/runtime or control-plane storage APIs.

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
2. TWH-1 rename broad storage contracts.
3. TWH-2 move runtime-shaped task queries out of storage.
4. TWH-3 split worker declaration from runtime projection.
5. TWH-4 current-state API guardrails.
6. TWH-5 trace/archive history direction.
7. TWH-6 residue removal.

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
