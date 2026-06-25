# Worker Runtime Score-Band Slot State Machine Convergence Roadmap

Status: proposed direction document.

This is the first implementation roadmap derived from:

- [Score-Band Resource Slot Scheduling Blueprint](../architecture/score-band-resource-slot-scheduling-blueprint.md)
- [Score-Band Worker Runtime Redis Shape](../architecture/score-band-worker-runtime-redis-shape.md)

This roadmap covers Program 1 plus the minimum internal Program 2 acquire
pivot: worker execution slot score-band state machine, the worker-runtime Redis
`score/meta` shape, and production worker selection acquiring from that state
machine behind the existing engine-facing `WorkerSelectionRuntime`.

It does not change engine task assignment, dispatch binding, transport
delivery, or the public shape consumed by engine. Program names are reference
labels from the blueprint, not separate roadmap owners.

The first useful slice is not just a registry skeleton. It must establish the
worker-runtime-owned state machine for worker resource slots and prove memory /
Redis parity for the unified score-band worker data structure. The roadmap is
not complete until `WorkerSelectionRuntime` production internals acquire and
claim workers through that score-band runtime instead of leaving score-band
state as a shadow projection.

## Current Facts

- `xa-mass-worker-runtime` owns worker-plane lifecycle, admission, dispatch
  gates, worker selection, and scheduling evidence above the low-level
  `mass-runtime-api` registry SPI.
- `platform_infra/mass-runtime-api` currently exposes `WorkerRegistry`,
  `WorkerSlot`, `WorkerMeta`, `ReserveResult`, `ReserveStatus`, and candidate
  bucket policy contracts.
- Memory and Redis implementations already share `WorkerRegistryContractTest`
  and concrete `InMemoryWorkerRegistryTest` / `RedisWorkerRegistryTest` proof.
- Current selection still flows through group/candidate acquisition:

  ```text
  WorkerSelectionRuntime
    -> WorkerCandidateRuntime / WorkerCandidateIndex
    -> WorkerRegistry.acquireCandidates(...)
    -> worker-runtime filters/ranking
    -> WorkerAdmissionRuntime.reserveWorkerCapacity(...)
  ```

- Redis currently has group-partitioned slot hashes, candidate buckets,
  heartbeat deadline zsets, and candidate-bucket lifecycle deadline zsets. These
  are current candidate/source projections, not score-band resource-slot truth,
  and this roadmap must not rename them into score-band keys.
- Current heartbeat deadlines and dispatch disable sources are important
  migration facts, but they are not yet the target score-band model:

  ```text
  score zset + stable scheduling metadata
  ```

- The worker-runtime pre-score-band cleanup removed transport-positive
  presence writes from the eligibility mainline. The remaining migration
  problem is turning worker-runtime-owned worker metadata, eligibility, and
  recheck/recovery decisions into one score-band slot state machine.
- Current selection domain semantics are not just "any eligible worker":
  `WorkerSelectionRequest` carries selection scope, requested count, exclusive
  lock, and `WorkerSelectionIntent` fields such as WorkerGroup ids, target
  worker id, routing code, route attributes, and target worker attributes.
  Score-band acquire must preserve those semantics without falling back to a
  full worker scan.
- Current persistent claim-close paths can reconstruct
  `SelectedWorkerEvidence` from worker id, WorkerGroup id, and task scope only.
  The score-band first slice should not turn this into a session/lease-token
  problem. Engine/resource `claim close` may close a `FUTURE_BAND` claim after
  worker-runtime validation. `release`, `final`, and `cancel` are reason names
  for that same state-machine transition, not separate recovery mechanisms.
  `FUTURE_BAND` due is not a timeout event and requires no writer; the same
  score naturally becomes due when `now >= score`. Engine-owned attempt timeout
  is separate task/attempt evidence, not positive claim close. Transport
  freshness evidence such as heartbeat, connected, session keepalive, and
  transport freshness only updates transport-local evidence; it cannot request
  worker-runtime recheck.

## Owner Review

The score-band slot state machine belongs below worker-runtime and above
storage:

```text
xa-mass-worker-runtime -> mass-runtime-api score-band slot contract
platform_infra/mass-runtime-memory -> in-memory adapter implementation
platform_infra/mass-runtime-redis -> Redis adapter implementation
```

Worker-runtime owns the state transitions. Memory and Redis implement the
contract. Engine must not consume score keys, parked reasons, low-recheck
counters, Redis buckets, or raw slot metadata directly.

Score-band slot state is worker-runtime truth, not trace, diagnostics,
transport presence, or task policy truth. Transition evidence is proof/audit
evidence; it must not become a second current-state owner. Existing
`WorkerRegistry` remains the current production candidate/reserve path until
the score-band acquire pivot in this roadmap lands. After that pivot, any old
candidate path that remains must be classified as migration residue, test
support, or a separately justified non-selection owner.

## Boundary Decision

Introduce a new worker resource slot state-machine contract instead of mutating
`WorkerRegistry` in place, then consume that contract behind the existing
`WorkerSelectionRuntime` before this roadmap is considered complete.

Reasons:

- `WorkerRegistry` is still the current production path for candidate source,
  reservation, active work accounting, dispatch gates, and tests.
- Replacing it in-place would force selection migration before the score-band
  state machine is proven.
- A separate first contract lets memory and Redis prove identical transition
  semantics before matcher internals move.
- The new contract is not a wrapper over `WorkerRegistry`; it owns a different
  runtime truth: score-band slot lifecycle and the unified worker-runtime
  `score/meta` Redis shape.
- The roadmap must not stop at a second state owner. Once the score-band
  contract is proven, `WorkerSelectionOwner` must use it through the unchanged
  engine-facing `WorkerSelectionRuntime` seam.
- The roadmap must not create a second production admission owner. Once the
  acquire pivot lands, capacity reserve/confirm/release/final accounting must
  be score-band-backed through one worker-runtime owner path, not split between
  score-band dynamic score state and the old `WorkerAdmissionRuntime` reserve
  truth.

Target first contract vocabulary can be adjusted during implementation, but the
owner shape should stay:

```text
WorkerResourceSlotRegistry or WorkerScoreBandSlotRuntime
  upsert worker resource metadata
  apply explicit score-band transition commands
  acquire bounded eligible worker resources
  write owner-validated score transitions
  accept positive evidence only as recheck input
  record transition evidence
```

Do not expose Redis key shape, zset names, candidate buckets, or worker group
enumeration through this contract.

SBR-5 requires an explicit score-band acquire scope derived from the existing
selection request:

```text
WorkerSelectionRequest
  -> score-band acquire scope
      selected WorkerGroup universe
      optional target worker id
      approved routing / placement tags
      requested worker count
      exclusive lock requirement
      selection scope key
```

The acquire scope is worker-runtime-owned. Engine still passes the same
`WorkerSelectionRuntime` request shape and must not know score-band buckets,
home bucket ids, or Redis keys.

First-slice transition coverage must be closed for the worker-runtime events it
chooses to support:

```text
worker register/update
  -> upsert scheduling metadata and initial score-band state

recoverable negative evidence
  -> LOW_RECHECK_BAND with bounded owner recheck

intentional disable / drain / offline / cold
  -> PARKED_BAND

owner-validated recovery / enable / resume / explicit unpark
  -> ELIGIBLE_BAND or LOW_RECHECK_BAND according to recovery policy

reserve / preallocate / active attempt / cooldown
  -> FUTURE_BAND with owner deadline

claim close
  reason = release | final | cancel
  -> same-claim close validation; never direct reopen

future score due
  -> no writer; the existing FUTURE_BAND score enters the acquire-time range
     when now >= score

attempt timeout
  -> engine/task-side evidence only; not positive claim close and not required
     for FUTURE_BAND due

heartbeat / connected / session keepalive / transport freshness
  -> transport-local freshness evidence only; no worker-runtime recheck request
```

Heartbeat, connected, session keepalive, and transport freshness evidence are
freshness evidence only. They may be point-read during an already requested or
maintenance-selected recovery check only when worker metadata explicitly opts
into `dispatchRecoveryMode=FRESHNESS_EVIDENCE`. The default recovery mode is
explicit-only: freshness does not request recheck and must not directly reopen
parked, low-recheck, or blocked workers.

## Target Shape

First-slice resource identity:

```text
resourceKind = worker
resourceId = workerId
homeBucketId = workerGroupId
```

First-slice state lanes:

```text
Eligibility Index
  score-band index by homeBucketId

Scheduling Metadata
  stable worker scheduling metadata used for owner validation
```

Target Redis shape:

```text
wr:{prefix}:score:{homeBucketId}       ZSET member = workerId, score = band score
wr:{prefix}:meta:{homeBucketId}        HASH field = workerId, value = WorkerSchedulingMetadata
```

`score` and `meta` for the same worker must use the same `homeBucketId`.
For the first slice `homeBucketId = workerGroupId`; future computed
partitioning needs a separate roadmap. The score zset is the only current
dynamic schedulability truth. Metadata must not
duplicate score, band, hold state, lease token, session token, or worker
currentness.

Transition evidence is not part of the first-slice Redis runtime shape. The
default proof/evidence path is trace plus focused contract tests. A Redis
transition stream is deferred until a concrete repair/debug owner proves the
need.

If SBR-5 needs to acquire by WorkerGroup universe or approved routing domain,
the implementation must provide bounded domain-to-home-bucket access. The first
acceptable forms are:

```text
homeBucketId is itself the approved worker-runtime scheduling partition
or
an auxiliary domain index points to bounded homeBucketId / workerId candidates
and score/meta remain the only current truth
```

An acquire implementation that scans every home bucket or every worker and then
filters by WorkerGroup, routing code, or attributes is invalid.

First-slice bands:

```text
PARKED_BAND
  not acquired
  not time-recovered
  not periodically rechecked
  reopened only by explicit owner event or policy promotion

LOW_RECHECK_BAND
  not acquired by hot path
  due for bounded owner recheck
  does not time-recover into eligible

ELIGIBLE_BAND
  acquire candidate window
  still validated by owner before claim

FUTURE_BAND
  time-bounded non-negative unavailable / occupied period
  naturally enters the acquire-time range after score time unless renewed,
  parked, or blocked; no queue move or timeout event is required
```

Recommended score constants follow the blueprint:

```text
PARKED_BAND:      score < 0
LOW_RECHECK_BAND: 0 <= score < TIME_SCORE_FLOOR
TIME_BAND:        score >= TIME_SCORE_FLOOR
```

`LOW_RECHECK_BAND` stores relative due scores:

```text
lowRecheckScore = nextRecheckAtMillis - LOW_RECHECK_EPOCH_MILLIS
```

`ELIGIBLE_BAND` and `FUTURE_BAND` use real epoch millis scores.

## Non-Goals

- Do not change the engine-facing `WorkerSelectionRuntime` contract,
  `SelectedWorkerHandle`, or `SelectedWorkerEvidence` shape.
- Do not replace engine dispatch binding or assignment semantics.
- Do not touch transport dispatch, adapter mailbox routing, adapter final-hop,
  or result ingress.
- Do not expose demand-lane matching as a public policy or engine contract.
  The internal acquire pivot may use a minimal demand scope derived from the
  current `WorkerSelectionRequest`.
- Do not introduce multi-resource claims.
- Do not introduce per-permit worker resource ids.
- Do not convert transport heartbeat, session keepalive, or connected events
  into score writes.
- Do not let task demand create placement indexes, bucket keys, or task-local
  worker candidate keys.
- Do not migrate or rename current `RedisWorkerRegistry` group/candidate/admin
  keyspace in this roadmap.
- Do not make old candidate buckets, heartbeat deadline zsets, or dispatch gate
  maps aliases of score-band state.
- Do not make transition evidence or diagnostics a current-state owner.
- Do not preserve old and new paths as equivalent production hot paths after a
  later migration slice chooses one.

## Do Not Start With

Do not start by renaming current candidate buckets or heartbeat deadline zsets
to "score-band." The first implementation must introduce and prove the
score-band lifecycle truth directly.

Do not start by changing engine assignment, task dispatch binding, transport,
or the engine-facing `WorkerSelectionRuntime` contract. The first useful slice
is the resource-slot state machine with memory and Redis parity; the acquire
pivot comes after the state machine is proven.

Do not start with a generic resource abstraction for every future resource
kind. Use `resourceKind=worker` and `resourceId=workerId` until the worker
resource proof is stable.

## SBR-0 Inventory And Contract Decision

Scope:

- Inventory current worker-runtime and runtime-api callers of:
  - `WorkerRegistry`
  - `WorkerSlot`
  - `WorkerMeta`
  - `WorkerCandidateRuntime`
  - `WorkerCandidateIndex`
  - `WorkerSelectionOwner`
  - `WorkerAdmissionRuntime`
  - `DispatchAvailabilitySource`
  - heartbeat cleanup and dispatch block paths
- Classify which current facts map to first-slice score-band lanes:
  - scheduling metadata;
  - score/band current state;
  - recheck/recovery evidence;
  - transition evidence;
  - migration-only candidate projection;
  - diagnostics only.
- Classify current worker-runtime events into the first transition table:
  - worker registration/update;
  - worker state disable/drain/offline/cold;
  - negative block / current-session disconnect;
  - reserve/preallocation/active attempt/cooldown;
  - claim close as the state-machine transition, with `release`, `final`, and
    `cancel` as reason names only;
  - future score due as read-time eligibility, not a stored transition event;
  - engine attempt timeout as task/attempt evidence, not positive close;
  - owner-validated recovery/recheck;
  - heartbeat/freshness as recheck evidence only.
- Classify current selection-domain inputs that must survive SBR-5:
  - WorkerGroup universe;
  - target worker id;
  - routing code and approved route attributes;
  - target worker attributes;
  - candidate source / source-guard evidence currently emitted into selected
    worker evidence;
  - exclusive lock request;
  - selection scope key.
- Decide first Java names for:
  - score-band registry contract;
  - score constants / band classifier;
  - worker resource metadata;
  - transition command/result/evidence;
  - eligible acquire request/result.
- Decide first score-band acquire-scope contract for SBR-5. It must be
  derivable from `WorkerSelectionRequest` and owner-approved worker metadata
  without requiring engine-facing contract changes.
- Decide whether transition command names are worker-specific
  (`WorkerSlotTransitionCommand`) or generic resource-specific
  (`ResourceSlotTransitionCommand`) while keeping first implementation worker
  only.
- Decide whether the first contract lives directly in
  `com.xa.mass.runtime.worker` or in a subpackage such as
  `com.xa.mass.runtime.worker.slot`.
- First `homeBucketId` source is `workerGroupId`. It is long-lived
  worker-runtime partitioning, not a task-created placement key. Future
  computed partitioning is out of this first slice.
- Decide initial state after registration:
  - immediately eligible only when worker declaration, recovery mode, and
    owner validation allow it;
  - otherwise low-recheck or parked with explicit reason.
- Decide coexistence boundary with current `WorkerRegistry`: score-band runtime
  is not production selection truth until the internal acquire pivot in this
  roadmap lands, and it must not remain shadow state after roadmap completion.
- Decide the SBR-5 admission/reserve convergence rule:
  - `WorkerAdmissionRuntime` becomes a score-band-backed port; or
  - production selection stops using `WorkerAdmissionRuntime`; or
  - another single owner is named.
  A dual production path is not allowed.
- Decide the SBR-5 claim-close rule:
  - claim close is one state-machine transition for closing a `FUTURE_BAND`
    claim;
  - `release`, `final`, and `cancel` are reason names for claim close, not
    separate event models;
  - future score due is read-time interpretation of the existing score, not a
    stored transition event;
  - engine attempt timeout is task/attempt evidence and must not write an
    eligible score;
  - heartbeat/connected/session keepalive/transport freshness cannot request
    worker-runtime recheck and cannot directly write an eligible score;
  - freshness evidence is considered only for workers with
    `dispatchRecoveryMode=FRESHNESS_EVIDENCE`; default `EXPLICIT_ONLY` workers
    need explicit worker report/control evidence;
  - only worker-runtime validation may reopen eligibility after declaration,
    WorkerGroup membership, gates, capacity, recovery mode, and metadata checks.

Acceptance:

- Inventory separates current production `WorkerRegistry` from new
  score-band slot truth.
- First-slice contract does not require engine, transport, or concrete adapter
  changes.
- `WorkerGroup` may remain metadata or management scope, but the hot-path
  target is not WorkerGroup-first enumeration.
- `homeBucketId` is `workerGroupId` in the first slice; it is not a placement
  tag bucket and not created by task demand.
- Event-to-transition table exists before code lands.
- Current `RedisWorkerRegistry` keyspace is classified as coexistence/migration
  state, not part of the new score-band Redis shape.
- Score-band acquire scope is specified before SBR-5 starts and proves bounded
  acquisition without full worker/home-bucket scan.
- Admission/reserve owner after SBR-5 is specified as exactly one production
  truth.
- Claim-close, future-score due, and attempt-timeout handling are specified
  before SBR-5 starts.
- Roadmap is updated if inventory shows the first slice cannot compile without
  modifying the engine-facing `WorkerSelectionRuntime` contract.

## SBR-1 Contract And State Machine Semantics

Scope:

- Add score-band constants and validation helpers in `mass-runtime-api`.
- Add first-slice worker resource slot state-machine contracts in
  `mass-runtime-api`.
- Add a shared contract test suite for memory and Redis implementations.
- Model only worker resources:

  ```text
  resourceKind = worker
  resourceId = workerId
  ```

- Keep scheduling metadata narrow:
  - workerGroupId;
  - capacityLimit;
  - approved scheduling attributes;
  - placement tag values if inventory approves them;
  - metadata version if needed for diagnostics.
- Do not introduce first-slice hold state:
  - no `WorkerHoldState`;
  - no lease/session token as scheduling decision truth;
  - no separate current hold owner record beside score;
  - reason, owner action, failed recheck count, and reopen policy are trace or
    diagnostic evidence unless a later roadmap proves a runtime owner.
- Model transition commands/results explicitly enough to prove:
  - target worker resource;
  - owner preconditions when required;
  - source/reason code;
  - observed/effective time;
  - next score;
  - whether transition changed state, was rejected, or was a stale no-op.
- Model acquire request/result narrowly enough to prove:
  - bounded acquire scope;
  - selected WorkerGroup universe or equivalent approved domain;
  - target worker id fast path;
  - requested count limit;
  - selected worker identities returned to worker-runtime selection.
- Keep positive evidence as recheck input, not a direct reopen command.

Acceptance:

- Band classifier proves:
  - parked scores are negative and never treated as time;
  - low-recheck scores are relative due scores below `TIME_SCORE_FLOOR`;
  - eligible/future scores are epoch millis time scores;
  - low-recheck overflow cannot silently become time-band score.
- Contract tests prove:
  - newly validated worker resource can enter `ELIGIBLE_BAND`;
  - recoverable negative block moves resource to `LOW_RECHECK_BAND`;
  - intentional disable/drain/park moves resource to `PARKED_BAND`;
  - future interval moves resource to `FUTURE_BAND`;
  - positive freshness/heartbeat cannot directly reopen parked or low-recheck;
  - freshness-based recovery is rejected unless worker metadata has
    `dispatchRecoveryMode=FRESHNESS_EVIDENCE`;
  - explicit owner recovery may reopen low-recheck after validation;
  - parked requires explicit unpark/resume/enable or owner policy promotion.
- Contract tests prove positive evidence cannot reopen directly:
  - claim close with reason `release`, `final`, or `cancel` cannot move parked
    or low-recheck resources into eligible by itself;
  - claim close may close or shorten `FUTURE_BAND` only through same-claim
    validation;
  - FUTURE_BAND due is proven by acquire range semantics, not by a timeout
    event or queue move;
  - engine attempt timeout cannot act as positive claim close and cannot write
    an eligible score;
  - heartbeat/connected/session keepalive/transport freshness cannot create a
    worker-runtime recheck request;
  - owner-validated recheck can move the resource to the next validated band;
  - stale positive evidence from an older observation is a no-op for reopening.
- Contract tests prove acquire-scope behavior:
  - acquire does not return resources outside the selected WorkerGroup universe;
  - target-worker acquire does not scan unrelated workers;
  - requested count bounds the returned resources;
  - score-band acquire returns only the minimal selected worker facts needed by
    worker-runtime selection.
- Contract does not expose list/count/stats surfaces on the scheduling hot path.
- Contract does not expose Redis key names, task ids as candidate keys, or
  transport/session identifiers.

## SBR-2 In-Memory Score-Band State Machine

Scope:

- Implement the new score-band contract in `mass-runtime-memory`.
- Use bounded in-memory structures that mirror the target lanes:
  - eligibility index;
  - scheduling metadata;
  - transition evidence sink suitable for tests.
- Implement the full first-slice transition table from SBR-0.
- Keep implementation independent from `xa-mass-worker-runtime`.

Acceptance:

- Shared contract tests pass for in-memory implementation.
- Bounded eligible acquire reads only `ELIGIBLE_BAND`.
- Low-recheck and parked resources are never returned by hot-path acquire.
- Future interval becomes acquire-visible only after score time; owner
  validation still happens before binding.
- Stale positive evidence or stale claim-close evidence cannot directly reopen
  eligibility.
- Transition evidence records old band, new band, reason code, owner action,
  and source type.
- Register/update, negative block, park, reserve, extend, claim close,
  future-score due acquire visibility, attempt-timeout classification, and
  owner recovery transitions are all covered by focused tests.
- In-memory implementation does not reuse candidate bucket keys as score-band
  truth.

## SBR-3 Redis Score / Meta Runtime Shape

Scope:

- Add Redis keyspace for first-slice score-band lanes, following the Redis
  shape reference:

  ```text
  wr:{prefix}:score:{homeBucketId}
  wr:{prefix}:meta:{homeBucketId}
  ```

- Implement the new score-band contract in `mass-runtime-redis`.
- Use atomic Redis transitions for score mutations and any same-transition
  metadata replacement that must be one resource-runtime transition from the
  caller perspective.
- Use the score zset as current band/score truth; do not duplicate current
  score or band in metadata JSON.
- Implement bounded acquire-domain access for the SBR-5 selection scope, or
  record that SBR-5 is blocked until the domain index is implemented.
- Keep current `RedisWorkerRegistry` candidate/slot keys intact until a later
  migration slice replaces the selection internals.

Acceptance:

- Shared contract tests pass for Redis implementation.
- Redis keyspace uses score/meta lanes and does not store `band` as an
  independent current truth.
- Redis score zset owns score; metadata hash does not duplicate current score.
- No `wr:{prefix}:hold:{homeBucketId}` key or `WorkerHoldState` record is
  introduced in the first slice.
- One logical resource transition updates score and any affected metadata
  atomically enough that readers cannot observe an owner-invalid mix for that
  same resource.
- Transition evidence is emitted through trace or a test evidence sink. Redis
  transition stream is not required for SBR-3 completion.
- Eligible acquire query uses `TIME_SCORE_FLOOR` lower bound and bounded limit.
- Low-recheck due query uses the relative due-score range and is maintenance
  only.
- Redis acquire proof does not scan all workers, all WorkerGroups, or all
  home buckets for a normal worker selection request.
- Any auxiliary domain index is a bounded locator only. It must not duplicate
  score or scheduling metadata truth.
- Existing `RedisWorkerRegistry` tests continue to pass.
- Redis implementation does not introduce per-task worker candidate keys.
- Redis implementation does not write current `RedisWorkerRegistry` candidate,
  heartbeat, dispatch-gate, or group slot keys.

## SBR-4 Worker-Runtime Lifecycle State Machine Integration

Scope:

- Wire the score-band runtime into `xa-mass-worker-runtime` as an internal
  worker-runtime-owned state machine.
- Do not change the engine-facing `WorkerSelectionRuntime` output or engine
  assignment behavior.
- Add worker-runtime tests that exercise score-band transition decisions through
  worker-runtime owners:
  - worker registration/update projects scheduling metadata;
  - current-session disconnect as recoverable close;
  - worker state `DRAINING` as parked/disabled intent;
  - worker state `AVAILABLE` or equivalent owner evidence as recheck request,
    not direct reopen;
  - reservation/final accounting as future-band interval/recheck support proof.
- Keep this slice focused on transition parity. Production selection remains on
  the existing `WorkerRegistry` / candidate path until SBR-5, but SBR-4 cannot
  be declared roadmap completion.

Acceptance:

- Existing worker selection tests remain green.
- Engine still consumes `SelectedWorkerHandle` / `SelectedWorkerEvidence`.
- Worker-runtime can project a worker resource into score-band and drive the
  first-slice state machine without changing current dispatch binding semantics.
- No transport connected/heartbeat path writes a score or reopens eligibility.
- No engine code imports score-band implementation classes or Redis keyspace.
- Score-band state is not consumed by current production selection until SBR-5.
- The temporary dual state is explicitly transitional: `WorkerRegistry` remains
  production candidate/reserve truth for this slice only, while score-band
  proves worker-runtime transition parity.

## SBR-5 WorkerSelectionRuntime Internal Acquire Pivot

Scope:

- Keep the engine-facing `WorkerSelectionRuntime` contract and selected handle
  shape unchanged.
- Replace `WorkerSelectionOwner` production internals from candidate-bucket
  acquisition to score-band acquisition and claim:

  ```text
  WorkerSelectionRuntime
    -> WorkerSelectionOwner
    -> score-band acquire bounded eligible worker resources
    -> worker-runtime validation / ranking / admission
    -> score-band future/unavailable score transition
    -> existing SelectedWorkerHandle / SelectedWorkerEvidence
  ```

- Keep engine task assignment, task dispatch binding, and transport untouched.
- Build the acquire request from the current `WorkerSelectionRequest` and
  resolved worker-scheduling facts already available to worker-runtime. Do not
  introduce public demand-lane policy or task-created candidate keys.
- Preserve current selection semantics:
  - selected WorkerGroup universe is still enforced;
  - target worker id has a bounded direct path;
  - routing code and approved route/target attributes are validated by
    worker-runtime metadata;
  - WorkerGroup capability checks remain worker-runtime / WorkerGroup evidence,
    not engine-side score-band reads;
  - exclusive worker lock requirement maps to score-band future/unavailable
    score semantics or a
    single named worker-runtime lock owner.
- Replace the old admission/reserve production truth with one SBR-0-selected
  owner path. If `WorkerAdmissionRuntime` remains, it must be backed by
  score-band slot state for production selection, not by an independent reserve
  counter.
- Apply the SBR-0 claim-close rule:
  - claim close is one transition for an existing `FUTURE_BAND` claim;
  - `release`, `final`, and `cancel` are close reasons only;
  - `FUTURE_BAND` expiry is not a close event; no writer moves the slot from
    future to eligible when time passes;
  - engine attempt timeout is not positive claim close and must not write an
    eligible score;
  - claim close must not directly reopen a parked, low-recheck, or otherwise
    blocked worker;
  - only worker-runtime validation may write an eligible score.
- Apply the SBR-5 claim observation rule:
  - reuse existing `SelectedWorkerHandle.selectionToken` as the score-band
    claim observation when it is available;
  - `selectionToken` is not a transport session token, endpoint lease id, or
    second Redis hold truth;
  - claim-close evidence with a matching claim observation may participate in
    same-claim validation;
  - null-token legacy `SelectedWorkerEvidence` is evidence-only for claim
    close: it may be logged or classified conservatively, but it must not
    directly shorten `FUTURE_BAND` score or write an eligible score.
- Keep old candidate/runtime registry structures only where still needed for
  non-selection owners, tests, or migration residue. They must not remain the
  production selection source after this slice.
- Define the first failure mapping for score-band acquire:
  - no eligible slot;
  - stale or rejected claim;
  - owner validation rejected slot;
  - retryable runtime failure.
- Add or update worker-selection tests around the unchanged public selection
  seam, not around engine assignment.
- Update architecture guards in the same slice when they currently protect the
  old candidate acquisition path. SBR-5 must not leave guards that require
  production `WorkerSelectionOwner` or `WorkerCandidateIndex` to call
  `WorkerRegistry.acquireCandidates(...)`.

Acceptance:

- `WorkerSelectionRuntime` callers in engine compile unchanged.
- Engine still receives the same selected-worker handle/evidence semantics.
- `WorkerSelectionOwner` production selection no longer calls
  `WorkerCandidateRuntime`, `WorkerCandidateIndex`, or
  `WorkerRegistry.acquireCandidates(...)`.
- `WorkerSelectionOwner` does not replace those calls with an all-worker,
  all-group, or all-home-bucket scan.
- Score-band acquire returns eligible/time-due resources; parked, low-recheck,
  and not-yet-due future-held resources are not selected by the hot path.
- Selected WorkerGroup, target worker, routing, and attribute constraints remain
  observable in focused selection tests after the acquire pivot.
- Owner validation can reject a score-band slot without corrupting score state.
- Successful selection moves the worker through worker-runtime-owned
  future/unavailable score state or a single named lock owner.
- Successful selection carries the existing `selectionToken` as score-band
  claim observation where claim-close validation needs same-claim proof.
- Confirm/claim-close paths validate and advance score through the score-band
  runtime rather than a separate production reserve truth.
- FUTURE_BAND due is represented by the existing score becoming due; no
  timeout event, queue move, or writer is needed.
- Engine attempt timeout does not act as positive claim close; it is
  task/attempt evidence and cannot write an eligible score.
- Stale or null-token claim-close evidence cannot directly shorten
  `FUTURE_BAND` or reopen eligibility for the same worker.
- No engine, task assignment, transport, adapter, or result-ingress code imports
  score-band implementation classes or Redis keyspace.
- Existing architecture guards that protect old candidate acquisition are
  migrated in this slice to protect the new invariant:
  production selection must use score-band acquire/claim and must not replace
  old candidate acquire with all-worker/all-group/all-home-bucket scans.
- Candidate-bucket and old registry reserve code is either removed from
  production selection or explicitly classified as residue/test support with a
  follow-up removal target.

## SBR-6 Docs, Guards, And Proof Registry

Scope:

- Update `xa-mass-worker-runtime/README.md` and `CONTRACTS.md` only after the
  first implementation slices land.
- Update `platform_infra/README.md` with score/meta runtime truth
  placement.
- Update `doc/PROOF_REGISTRY.md` with the new proof row or expand
  `sched.worker-state-dimensions` only after focused tests exist.
- Add architecture guards that protect stable owner boundaries:
  - engine main sources must not import score-band Redis/keyspace types;
  - transport/adapters must not write score-band state;
  - current `RedisWorkerRegistry` keys must not be treated as score-band keys;
  - transition evidence must not drive hot-path acquire;
  - Redis transition stream must not be required as first-slice completion
    proof;
  - task demand must not create score-band bucket keys;
  - production `WorkerSelectionOwner` must not call the old candidate acquire
    path after SBR-5;
  - production selection must not use all-worker/all-home-bucket scans as a
    replacement for old candidate acquire;
  - production selection must not maintain both score-band dynamic score truth and old
    admission reserve truth as independent writable state.

Acceptance:

- Current docs describe score-band slot state machine and Redis runtime shape
  as implemented only after code exists.
- Blueprint remains architecture reference, not implementation proof.
- Proof registry points to actual tests, not placeholder names.
- Guards protect owner invariants rather than provisional class names.
- Docs say score-band is the worker-runtime slot state-machine runtime and,
  after SBR-5, the production acquire source behind `WorkerSelectionRuntime`.

## Suggested Implementation Order

1. SBR-0 inventory, event-to-transition table, and naming decision.
2. SBR-1 API constants, transition model, and shared contract tests.
3. SBR-2 in-memory state-machine implementation.
4. SBR-3 Redis score/meta implementation and keyspace proof.
5. SBR-4 worker-runtime lifecycle transition integration proof.
6. SBR-5 `WorkerSelectionRuntime` internal acquire pivot.
7. SBR-6 owner docs, proof registry, and guards.

## Verification Candidates

SBR-0 inventory:

```powershell
rg -n "WorkerRegistry|WorkerSlot|WorkerMeta|WorkerCandidateRuntime|WorkerCandidateIndex|WorkerSelectionOwner|WorkerAdmissionRuntime|DispatchAvailabilitySource|cleanupExpiredHeartbeats|acquireCandidates|tryReserve" `
  platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis xa-mass-worker-runtime xa-mass-engine `
  --glob "*.java" --glob "!**/target/**"
```

After SBR-1/SBR-2:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory -am -DskipTests test-compile
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory -am test `
  "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest,InMemoryWorkerRegistryTest"
```

After SBR-3:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -DskipTests test-compile
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory -am test `
  "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest,InMemoryWorkerRegistryTest"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -am test `
  "-Dtest=RedisWorkerScoreBandSlotRuntimeTest,RedisWorkerRegistryTest"
```

After SBR-4:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime,xa-mass-engine -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-worker-runtime -am test `
  "-Dtest=WorkerManagerTest,WorkerScoreBandSlotStateMachineIntegrationTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine -am test `
  "-Dtest=EngineSchedulingCoreArchitectureGuardTest"
```

After SBR-5:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime,xa-mass-engine -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-worker-runtime -am test `
  "-Dtest=WorkerSelectionScoreBandRuntimeTest,WorkerSelectionScoreBandDomainTest,WorkerSelectionScoreBandPositiveEvidenceTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest,WorkerManagerTest"
.\mvnw.cmd -q -pl xa-mass-engine -am test `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest"
```

`WorkerSelectionScoreBandRuntimeTest`, bounded-domain proof, and stale
positive/release evidence proof are mandatory new or renamed focused proofs for
SBR-5. If
implementation uses different class names, these commands must be corrected
before completion; do not rely on
`-Dsurefire.failIfNoSpecifiedTests=false`.

Completion residue scan:

```powershell
rg -n "WorkerScoreBand|ScoreBand|score-band|wr:.*score|wr:.*meta|wr:.*hold" `
  xa-mass-engine transport `
  --glob "*.java" --glob "!**/target/**"
rg -n "WorkerCandidateRuntime|WorkerCandidateIndex|acquireCandidates\(" `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerManager.java `
  --glob "*.java" --glob "!**/target/**"
rg -n "adapterMailboxKey|routeKey|connectionId|sessionHandle" `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection xa-mass-engine/src/main/java `
  --glob "*.java" --glob "!**/target/**"
```

Redis keyspace proof should be done by focused keyspace tests, not by treating
all `wr:*score/meta` or `RedisWorkerRegistry` mentions as residue.
`RedisWorkerRegistry` may remain while old candidate structures are being
retired, but it must not write score/meta keys and score-band Redis runtime
must not write the old candidate/heartbeat/group slot keys.

Mandatory new test classes must exist before the corresponding verification
command is considered proof. Do not use `-Dsurefire.failIfNoSpecifiedTests=false`
for roadmap completion proof.

## Completion Criteria

This roadmap can be considered complete only when:

- memory and Redis share the same score-band worker resource slot contract;
- worker-runtime first-slice events have a closed transition table and focused
  proof;
- score-band constants and band predicates are explicit and tested;
- parked, low-recheck, eligible, and future semantics are proven in contract
  tests;
- Redis uses the score/meta lane shape without duplicating score/band as
  current truth;
- Redis one-resource transitions keep score and same-transition metadata
  mutations consistent for the logical transition;
- transition evidence is proven through trace/test evidence, not required Redis
  stream writes;
- current `WorkerRegistry` candidate/reserve tests remain green during the
  transition;
- worker-runtime has an internal lifecycle state-machine integration proof
  without changing engine assignment;
- production `WorkerSelectionRuntime` internals acquire/claim workers through
  score-band runtime while preserving the engine-facing selection contract;
- `WorkerSelectionOwner` production selection no longer depends on
  `WorkerCandidateRuntime`, `WorkerCandidateIndex`, or
  `WorkerRegistry.acquireCandidates(...)`;
- score-band acquire is bounded by a worker-runtime-owned domain/scope and does
  not scan all workers, all groups, or all home buckets for normal selection;
- WorkerGroup, target worker, routing, attribute, exclusive-lock, and requested
  count semantics are preserved behind the unchanged `WorkerSelectionRuntime`
  contract;
- admission/reserve/final accounting has one production owner path after the
  acquire pivot, with no independent old reserve truth running beside
  score-band dynamic score truth;
- stale claim-close evidence from an older observation cannot reopen
  eligibility;
- docs and proof registry reflect implemented behavior;
- no task-created worker candidate keys, transport/session facts, or engine
  imports become score-band owner truth;
- current `RedisWorkerRegistry` group/candidate/heartbeat keyspace has not been
  renamed into score-band or made an alias of score-band truth.

## Follow-Up Roadmaps

This roadmap intentionally stops before engine assignment, transport, or
full demand-lane redesign. Follow-up roadmaps should cover:

- unification or retirement plan for old `WorkerRegistry` candidate/reserve
  Redis structures after score-band acquire becomes stable;
- engine attempt interval integration;
- demand lane shape and bounded lane acquire policy;
- all-or-nothing multi-resource claims;
- archive/supersede cleanup for candidate-bucket roadmaps once score-band
  acquire becomes the primary selection path.
