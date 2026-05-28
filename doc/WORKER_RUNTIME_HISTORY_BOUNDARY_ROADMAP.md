# Worker Runtime History Boundary Roadmap

Status: proposed convergence roadmap.

This roadmap protects a core boundary:

- engine and `xa-mass-worker-runtime` expose current runtime state
- control-plane storage owns stable worker declaration truth
- historical worker connections, historical scheduling, historical dispatch,
  historical results, and analytics belong to trace/event/archive read models

The goal is not to add a new DB implementation. The goal is to prevent
`WorkerStorage` and worker-runtime APIs from becoming a business-history query
system as JDBC/PostgreSQL support grows.

Read with:

- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../platform_infra/README.md](../platform_infra/README.md)
- [../xa-mass-worker-runtime/README.md](../xa-mass-worker-runtime/README.md)
- [../xa-mass-worker-runtime/CONTRACTS.md](../xa-mass-worker-runtime/CONTRACTS.md)

## Current Facts

- `WorkerStorage` currently lives in `mass-storage-api` and is documented as a
  control-plane worker row abstraction, not runtime scheduling truth.
- Current production implementation found in this slice is
  `InMemoryWorkerStorage`; no JDBC `WorkerStorage` implementation was found.
- `WorkerResourceOwner` writes worker registration rows to `WorkerStorage` and
  projects them into `WorkerRegistry` slots.
- `WorkerManager` publishes worker resource/current-state APIs from
  `WorkerResourceOwner`, `WorkerRegistry`, capability reports, and transport
  reachability views.
- `Worker` still mixes stable declaration fields with runtime-flavored fields
  such as `status`, `lastHeartbeat`, compatibility supported project/event
  hints, and helper methods like `updateHeartbeat()`.
- WorkerGroup is now the capability owner. Worker rows should not become a
  second project/event capability source.
- `DB_STORAGE_PRINCIPLES.md` already bans worker online/offline churn,
  heartbeat streams, locks, reservation churn, dispatch history, and analytics
  from DB ownership.

## Boundary Decision

Use three distinct surfaces:

```text
control-plane declaration
  WorkerDeclarationStore / WorkerDeclarationRecord
  stable worker identity, group/node binding, adapter identity, static
  scheduling attributes, declared capacity

runtime current state
  xa-mass-worker-runtime + WorkerRegistry + transport reachability
  online, heartbeat freshness, dispatch gates, capacity reservations,
  candidate source, active leases, current capability projection

history / analytics
  trace/event/archive pipeline
  worker connection timeline, scheduling decisions, candidate rejection
  history, dispatch attempts, result timelines, cross-task analysis
```

The current `WorkerStorage` name is too broad for this boundary. Its target role
is a declaration/control-plane row store, not a runtime store and not a history
store.

## Non-Goals

- Do not implement durable worker history tables in JDBC.
- Do not add a synchronous DB write for worker heartbeat, online/offline,
  dispatch, reservation, lock, candidate rejection, or result history.
- Do not route scheduling decisions through control-plane DB scans.
- Do not preserve old and new worker storage names as two public seams.
  Converge in-repo callers.
- Do not make trace/archive the source of runtime correctness. Trace is for
  history, replay assistance, debugging, and analytics.
- Do not introduce a generic repository abstraction that hides whether data is
  control-plane, runtime, or trace-shaped.

## WHB-0 Inventory And Classification

Scope:

- Inventory every production and test caller of:
  - `WorkerStorage`
  - `InMemoryWorkerStorage`
  - `WorkerResourceOwner.getAllWorkers()`
  - `WorkerManager.workers()`
  - `MassSdkApplication.getAllWorkers()`
  - server/catalog worker read models
- Classify each caller as one of:
  - declaration/control-plane
  - runtime current-state
  - support/debug read model
  - test fixture
  - history/analytics-shaped residue
- Identify which `Worker` fields are stable declaration fields and which are
  runtime projection fields.
- Decide the target names before moving code. Recommended names:
  `WorkerDeclarationStore` and `InMemoryWorkerDeclarationStore`.

Acceptance:

- A committed inventory table lists every caller and its classification.
- No implementation starts before ambiguous callers are classified.
- The inventory explicitly states that current JDBC does not provide worker
  runtime/history storage.

## WHB-1 Rename Storage Contract To Declaration Store

Scope:

- Rename `WorkerStorage` to a declaration-oriented contract.
- Rename `InMemoryWorkerStorage` accordingly.
- Update SDK builder/config names so external embedding callers do not see
  "worker storage" as a generic runtime/history extension point.
- Keep the same behavior in this slice; this is a semantic convergence and
  call-site update.

Acceptance:

- No production import of `WorkerStorage` remains.
- No compatibility alias remains for the old name.
- `mass-storage-api` README says the worker contract is declaration/control
  plane only.
- `platform_infra/README.md` and `DB_STORAGE_PRINCIPLES.md` use declaration
  vocabulary consistently.

## WHB-2 Split Worker Declaration From Runtime Projection

Scope:

- Introduce a declaration-shaped record if needed, instead of persisting the
  mixed `Worker` model directly.
- Stable declaration candidates:
  - `workerId`
  - `workerGroupId`
  - `adapterNodeId`
  - `adapterId`
  - `onlineStrategy` or transport hint
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

## WHB-3 Current-State API Guardrails

Scope:

- Review worker APIs exposed by SDK/server:
  - list workers
  - worker capability read model
  - worker state reports
  - worker command/status endpoints
- Make naming and docs explicit that these are current-state or support/debug
  read models, not historical query APIs.
- Add guard tests where useful:
  - engine/worker-runtime current-state APIs do not depend on archive/history
    stores
  - storage modules do not import worker-runtime scheduling owners
  - JDBC module does not implement heartbeat/dispatch/history worker tables

Acceptance:

- Public/server worker query docs do not imply historical retention.
- New worker current-state APIs must state their canonical layer.
- Architecture tests block new storage-side worker history/hot-write contracts.

## WHB-4 Trace/Event Archive Direction For Worker History

Scope:

- Define the minimal worker-history events that should enter trace/event/archive
  later, without making runtime wait for that pipeline:
  - worker registered / declaration changed
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

- A follow-up trace/archive design note exists before any durable worker history
  store is introduced.
- Runtime hot-path writes are not blocked on archive/analytics availability.
- Analytics requirements are expressed against trace/archive read models, not
  worker-runtime or control-plane storage APIs.

## WHB-5 Remove Compatibility Residue

Scope:

- Remove or demote worker-level supported project/event capability hints once
  current server/read-model consumers use WorkerGroup capability views.
- Remove old worker storage wording from SDK docs and tests.
- Delete obsolete helper methods that make `Worker` look like a runtime state
  owner if callers have moved to explicit runtime APIs.

Acceptance:

- WorkerGroup-first capability has no worker-row fallback in production
  scheduling or catalog read models.
- Runtime/history terms in worker declaration code are either removed or marked
  compatibility-only with a deletion path.
- The proof registry or testing index points to current-state tests and
  trace/archive proof gaps separately.

## Suggested Implementation Order

1. WHB-0 inventory.
2. WHB-1 rename storage contract.
3. WHB-2 declaration/runtime field split.
4. WHB-3 current-state API guardrails.
5. WHB-4 trace/archive history direction.
6. WHB-5 residue removal.

Do not start with WHB-4. A trace/archive plan cannot compensate for a
misnamed storage/runtime boundary. First make the current ownership explicit,
then add history ingestion/read models as a separate async path.

## Verification

Minimum local checks per implementation slice:

```bash
mvn -pl platform_infra/mass-storage-api,platform_infra/mass-storage-memory,xa-mass-worker-runtime -am test
mvn -pl xa-mass-sdk,xa-mass-server -am -DskipTests compile
```

When server worker read models change, also run focused server tests around
worker/catalog APIs and at least one external worker registration E2E.

When trace/archive direction changes, update `TRACE_CONTRACT.md`,
`INFRA_TRUTH_LAYERS.md`, and `DB_STORAGE_PRINCIPLES.md` in the same change.
