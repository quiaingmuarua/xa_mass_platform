# Worker Runtime Score-Band Slot State Machine Convergence Roadmap

Status: mainline complete for score-band active-hold v1; follow-up
roadmaps remain. SBR-0 through SBR-5 have landed for the worker resource slot
score-band runtime, memory/Redis parity, production selection acquire/claim
pivot, and claim-close observation propagation. The current score-band slice
models one active score-band hold per worker resource: once selected, that
worker is held in `FUTURE_BAND` until claim close or natural due time.
Remaining-permit concurrency for `declaredCapacity > 1` is not the current
score-band target. This does not assert that a worker can never process
multiple items; a future non-exclusive task policy may close the claim after
dispatch acceptance so the same worker can be reacquired. The old
`activeSelectedWorkerCount(taskId)` budgeting read has been removed from
`WorkerSelectionRuntime`; assignment budgeting now reads active dispatch
workers from task-runtime lease truth through `TaskAssignmentRuntimePort`.

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

Current implementation notes:

- `WorkerScoreBandSlotRuntime` and memory / Redis implementations exist.
- Worker declaration add/update projects score-band slot metadata when assembly
  provides a score-band runtime; heartbeat refresh does not write score-band
  state.
- SDK memory assembly defaults to `InMemoryWorkerScoreBandSlotRuntime`; server
  Redis assembly wires `RedisWorkerScoreBandSlotRuntime`.
- Production `WorkerSelectionRuntime` acquire now reads bounded score-band
  slots and moves selected workers into `FUTURE_BAND`.
- The current production score-band unit is a worker-resource single slot. A
  selected worker is not reacquired while its score is still future-held,
  regardless of `declaredCapacity`. `declaredCapacity` remains load/ranking and
  diagnostic evidence in this slice; it is not a remaining-permit acquire
  source.
- Release/final evidence now carries `scoreBandClaimScore` through
  `WorkerClaimTarget -> ActiveLeaseRecord / ClaimedTaskWork ->
  TaskDispatchBinding / RuntimeResultApplyContext -> SelectedWorkerEvidence`.
  Null-observation legacy evidence must not reopen or shorten score-band
  claims.
- `WorkerSelectionOwner` no longer writes the old capacity reservation and
  claimed/final accounting path. `WorkerAdmissionRuntime` still owns exclusive
  worker locks and remains as direct/runtime API residue outside the score-band
  selection hot path.
- `WorkerSelectionRuntime.activeSelectedWorkerCount(taskId)` has been removed.
  Task-scope budgeting reads belong to engine/task-runtime assignment truth and
  are served by `TaskAssignmentRuntimePort.countActiveDispatchWorkers(taskId)`,
  which counts distinct workers from active task work leases.
- Follow-up recovery correction: worker-level `dispatchRecoveryMode` /
  `FRESHNESS_EVIDENCE` is no longer the target direction. Global worker
  eligibility should use transport network freshness as the minimum positive
  evidence during worker-runtime recheck; task attributes and event capability
  remain task-specific selection filters and must not write score bands.

## Current Facts

- `xa-mass-worker-runtime` owns worker-plane lifecycle, admission, dispatch
  gates, worker selection, and scheduling evidence above the low-level
  `mass-runtime-api` registry SPI.
- `platform_infra/mass-runtime-api` currently exposes `WorkerRegistry`,
  `WorkerSlot`, `WorkerMeta`, and the new score-band slot contract. The old
  broad candidate and reservation APIs are retired.
- Memory and Redis implementations already share `WorkerRegistryContractTest`
  and concrete `InMemoryWorkerRegistryTest` / `RedisWorkerRegistryTest` proof.
- Current production selection now flows through score-band acquisition:

  ```text
  WorkerSelectionRuntime
    -> WorkerSelectionOwner
    -> WorkerScoreBandSlotRuntime.acquire(...)
    -> worker-runtime filters / ranking / exclusive-lock check
    -> WorkerScoreBandSlotRuntime.transition(FUTURE_INTERVAL)
  ```

- Current production selection is one active score-band hold per worker
  resource. It does not prove that an occupied worker with remaining declared
  capacity can be concurrently selected through old reservation accounting.
  That capacity semantics is not current mainline. Future non-exclusive task
  policy can be modeled as early claim close after dispatch acceptance, not as
  a revival of pre-score-band reservation counters.

- Older Redis worker-registry data still has group-partitioned slot hashes,
  candidate buckets,
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
evidence; it must not become a second current-state owner. Production
`WorkerSelectionOwner` now acquires and claims candidates from score-band slots.
The old candidate/reserve path must not return to production selection. Any old
candidate or admission path that remains must be classified as non-selection
runtime API, migration residue, test support, or a separately justified owner.

## Boundary Decision

SBR-0 production worker slot-state owner shape is fixed for this roadmap:

```text
introduce a new worker resource slot state-machine contract
```

That contract must be consumed behind the existing `WorkerSelectionRuntime`
before this roadmap is considered complete.

Reasons:

- `WorkerRegistry` is still the current production path for candidate source,
  reservation, active work accounting, dispatch gates, and tests.
- In-place mutation of `WorkerRegistry` would preserve too much old candidate /
  reserve / heartbeat / gate vocabulary and make the score-band state machine
  look like another projection instead of the new worker-runtime slot truth.
- The separate first contract lets memory and Redis prove identical transition
  semantics before matcher internals move.
- The new contract must not be a wrapper over `WorkerRegistry`; it owns a
  different runtime truth: score-band slot lifecycle and the unified
  worker-runtime `score/meta` Redis shape.
- The roadmap must not stop at a second state owner. Once the score-band
  owner is proven, `WorkerSelectionOwner` must use it through the unchanged
  engine-facing `WorkerSelectionRuntime` seam.
- The roadmap must not create a second production admission owner. Once the
  acquire pivot lands, capacity reserve/confirm/release/final accounting must
  be score-band-backed through one worker-runtime owner path, not split between
  score-band dynamic score state and the old `WorkerAdmissionRuntime` reserve
  truth.

Target owner vocabulary can be adjusted during implementation, but the
score-band slot contract must express:

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
freshness evidence only. They may be point-read during a worker-runtime recheck
for any declared worker. There is no target worker-declared recovery mode:
freshness does not request recheck and must not directly reopen parked,
platform-blocked, or held workers, but fresh network evidence is the minimum
positive evidence needed to reopen ordinary network-related low-recheck state.

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

`LOW_RECHECK_BAND` stores owner-defined priority / retry-class scores:

```text
lowRecheckScore = retryPriorityOrAttemptCount
```

Delayed low-recheck due-time is not the default. It is reserved for an explicit
backoff sub-policy, such as repeated handler failure or suspicious health
evidence. `ELIGIBLE_BAND` and `FUTURE_BAND` use real epoch millis scores.

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
- Do not add a worker-declared positive-recovery mode. A connected/fresh worker
  is generally eligible unless worker-runtime sees platform block, parked state,
  hold, missing declaration/group membership, or other owner-owned closure.
- Do not let task demand create placement indexes, bucket keys, or task-local
  worker candidate keys.
- Do not migrate or rename current `RedisWorkerRegistry` group/candidate/admin
  keyspace in this roadmap.
- Do not make old candidate buckets, heartbeat deadline zsets, or dispatch gate
  maps aliases of score-band state.
- Do not make transition evidence or diagnostics a current-state owner.
- Do not preserve old and new paths as equivalent production hot paths after a
  score-band acquire pivot lands.

## Do Not Start With

Do not start by renaming current candidate buckets or heartbeat deadline zsets
to "score-band." The first implementation must introduce and prove the
score-band lifecycle truth directly.

Do not start by mutating `WorkerRegistry` in place. `WorkerRegistry` remains
the current migration source until the score-band owner is consumed behind
`WorkerSelectionRuntime`; it is not the first score-band contract.

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
  - the retired pre-score-band candidate runtime facade and candidate index
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
  - heartbeat/freshness as point-read network evidence that worker-runtime may
    consult during bounded recheck for any declared worker; it is not a recheck
    request and not a direct eligibility writer.
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
- Record the coexistence boundary with current `WorkerRegistry`: score-band
  runtime is not production selection truth until the internal acquire pivot in
  this roadmap lands, and it must not remain shadow state after roadmap
  completion.
- Apply the SBR-5 task-scope active-count rule:
  - remove `activeSelectedWorkerCount(taskId)` from worker selection;
  - use task-runtime assignment/lease truth for allocation budgeting;
  - do not add task-scoped score-band indexes or return to old reserve
    accounting for this read.
- Decide the SBR-5 claim-close rule:
  - claim close is one state-machine transition for closing a `FUTURE_BAND`
    claim;
  - `release`, `final`, and `cancel` are reason names for claim close, not
    separate event models;
  - future score due is read-time interpretation of the existing score, not a
    stored transition event;
  - engine attempt timeout is task/attempt evidence only and must not write any
    score;
  - heartbeat/connected/session keepalive/transport freshness cannot request
    worker-runtime recheck and cannot directly write worker-runtime score;
  - freshness evidence is considered during worker-runtime recheck for declared
    workers without a platform block, parked state, or active hold; no
    worker-declared recovery mode is required;
  - only worker-runtime validation may reopen eligibility after declaration,
    WorkerGroup membership, gates, single-slot hold state, network freshness,
    and metadata checks.

Acceptance:

- Inventory separates current production `WorkerRegistry` responsibilities from
  the selected score-band slot truth.
- SBR-0 records the fixed production slot-state owner path: introduce a new
  worker resource slot state-machine contract. Direct in-place mutation of
  `WorkerRegistry` is not the selected first path.
- The chosen path forbids split production truth between old candidate/reserve
  state and new score-band score state after SBR-5.
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
- Post-SBR-5 budgeting reads are task-runtime assignment truth. Production
  selection must not return to old reserve truth, and score-band must not gain a
  task-scoped active-count index for this read.
- Claim-close, future-score due, and attempt-timeout handling are specified
  before SBR-5 starts.
- Roadmap is updated if inventory shows the first slice cannot compile without
  modifying the engine-facing `WorkerSelectionRuntime` contract.

## SBR-1 Slot-State Contract And State Machine Semantics

Scope:

- Add score-band constants and validation helpers in `mass-runtime-api`.
- Implement the SBR-0 selected worker slot-state contract path by adding the
  new score-band resource slot contract in `mass-runtime-api`. Do not add
  parallel score fields to `WorkerRegistry` as the first implementation path.
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
  - low-recheck scores are owner-defined priority / retry-class scores below
    `TIME_SCORE_FLOOR`;
  - eligible/future scores are epoch millis time scores;
  - low-recheck overflow cannot silently become time-band score.
- Contract tests prove:
  - newly validated worker resource can enter `ELIGIBLE_BAND`;
  - recoverable negative block moves resource to `LOW_RECHECK_BAND`;
  - intentional disable/drain/park moves resource to `PARKED_BAND`;
  - future interval moves resource to `FUTURE_BAND`;
  - positive freshness/heartbeat cannot directly reopen parked or low-recheck;
  - freshness-based recovery does not depend on worker metadata
    `dispatchRecoveryMode`;
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
    any score;
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
- Low-recheck maintenance query uses the reserved low-recheck priority /
  retry-class range and bounded limit. It must not assume all low-recheck scores
  are due timestamps.
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
  production candidate/reserve truth for SBR-4 only, while score-band proves
  worker-runtime transition parity. SBR-5 must replace the production selection
  acquire/claim path with score-band.

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
- Replace the old admission/reserve production truth with the SBR-0-selected
  score-band owner path. `WorkerAdmissionRuntime` may remain for exclusive
  worker locks and direct/runtime API residue, but production selection must not
  write independent reserve/confirm/claimed/final counters beside score-band
  claims.
- Preserve the current score-band v1 capacity boundary: score-band acquire is
  one active hold per worker resource. This slice must not claim proof for
  concurrent remaining-permit selection through old reservation accounting.
  `declaredCapacity > 1` remains metadata / ranking / diagnostic evidence, not
  current acquire concurrency truth. Future non-exclusive task policy may make
  workers reusable earlier by emitting claim close after dispatch acceptance.
- Move `activeSelectedWorkerCount(taskId)` out of `WorkerSelectionRuntime`.
  Score-band claims are worker-slot keyed; task-scoped active dispatch worker
  counts are computed from task-runtime active leases through
  `TaskAssignmentRuntimePort`.
- Do not mark the SBR-5 pivot complete while claim-close evidence lacks an
  observation that can be validated against the score-band claim. The current
  implementation carries `scoreBandClaimScore`, the expected `FUTURE_BAND`
  score written at selection time. `selectionToken` remains selected-handle
  identity; it is not stored as a Redis hold token.
- Apply the SBR-0 claim-close rule:
  - claim close is one transition for an existing `FUTURE_BAND` claim;
  - `release`, `final`, and `cancel` are close reasons only;
  - `FUTURE_BAND` expiry is not a close event; no writer moves the slot from
    future to eligible when time passes;
  - engine attempt timeout is not positive claim close and must not write any
    score;
  - claim close must not directly reopen a parked, low-recheck, or otherwise
    blocked worker;
  - only worker-runtime validation may write an eligible score.
- Apply the SBR-5 claim observation rule:
  - use `scoreBandClaimScore` as the score-band same-claim observation;
  - `selectionToken` is not a transport session token, endpoint lease id, or
    second Redis hold truth, and it is not the score comparison value;
  - claim-close evidence with a matching claim observation may participate in
    same-claim validation;
  - null-observation legacy `SelectedWorkerEvidence` is evidence-only for
    claim close: it may be logged or classified conservatively, but it must
    not directly shorten `FUTURE_BAND` score or write an eligible score.
- If SBR-5 keeps the engine-facing `WorkerSelectionRuntime` method shape
  unchanged, worker-runtime must still ensure that engine release/final paths
  can return a score-band claim observation through persisted selected-worker
  evidence. Otherwise SBR-5 may only pivot candidate acquire, not admission /
  claim-close ownership, and the roadmap is not complete.
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
  production selection to call the retired registry candidate-acquire SPI.

Acceptance:

- `WorkerSelectionRuntime` callers in engine compile unchanged.
- Engine still receives the same selected-worker handle/evidence semantics.
- `WorkerSelectionOwner` production selection no longer calls the retired
  pre-score-band candidate facade, candidate index, or registry candidate
  acquisition SPI.
- `WorkerSelectionOwner` does not replace those calls with an all-worker,
  all-group, or all-home-bucket scan.
- Score-band acquire returns eligible/time-due resources; parked, low-recheck,
  and not-yet-due future-held resources are not selected by the hot path.
- Score-band acquire is one active hold per worker resource in this slice. A
  selected worker is future-held until claim close or due time;
  remaining-permit capacity through old reservation accounting is not part of
  SBR-5 acceptance.
- Selected WorkerGroup, target worker, routing, and attribute constraints remain
  observable in focused selection tests after the acquire pivot.
- Owner validation can reject a score-band slot without corrupting score state.
- Release/final/claim-close proof uses non-null score-band claim observation.
  Tests must also prove null-observation legacy `SelectedWorkerEvidence` does not
  reopen or shorten a score-band claim.
- Successful selection moves the worker through worker-runtime-owned
  future/unavailable score state or a single named lock owner.
- Successful selection carries `scoreBandClaimScore` as score-band claim
  observation where claim-close validation needs same-claim proof.
- Confirm/claim-close paths validate and advance score through the score-band
  runtime rather than a separate production reserve truth.
- Production selection no longer calls the retired capacity reservation,
  reservation confirmation, claimed/final accounting, or reservation release
  methods.
- `WorkerSelectionRuntime` no longer exposes `activeSelectedWorkerCount`.
- `TaskWorkerAssignListener` reads current task worker count from
  `TaskAssignmentRuntimePort.countActiveDispatchWorkers(taskId)`, backed by
  distinct workers in task-runtime active leases.
- FUTURE_BAND due is represented by the existing score becoming due; no
  timeout event, queue move, or writer is needed.
- LOW_RECHECK_BAND is not a default time queue. Ordinary network disconnect or
  no-current-endpoint should use an owner-defined retry priority/count score;
  delayed low-recheck due-time is reserved for explicit suspicious/backoff
  policies such as repeated handler failure.
- Engine attempt timeout does not act as positive claim close; it is
  task/attempt evidence and cannot write any score.
- Stale or null-observation claim-close evidence cannot directly shorten
  `FUTURE_BAND` or reopen eligibility for the same worker.
- No engine, task assignment, transport, adapter, or result-ingress code imports
  score-band implementation classes or Redis keyspace.
- Existing architecture guards that protect old candidate acquisition are
  migrated in this slice to protect the new invariant:
  production selection must use score-band acquire/claim and must not replace
  old candidate acquire with all-worker/all-group/all-home-bucket scans.
- Candidate-bucket and old registry reserve code is either removed from
  production selection or explicitly classified as direct API residue/test
  support with a follow-up removal target.
- `activeSelectedWorkerCount(taskId)` is removed from worker-runtime selection,
  and task-runtime active-lease proof covers the replacement budgeting read.

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

1. SBR-0 inventory, event-to-transition table, and fixed production slot-state
   owner record.
2. SBR-1 API constants, transition model, and shared contract tests.
3. SBR-2 in-memory state-machine implementation.
4. SBR-3 Redis score/meta implementation and keyspace proof.
5. SBR-4 worker-runtime lifecycle transition integration proof.
6. SBR-5 `WorkerSelectionRuntime` internal acquire pivot.
7. SBR-6 owner docs, proof registry, and guards.

## Verification Candidates

SBR-0 inventory:

```powershell
rg -n "WorkerRegistry|WorkerSlot|WorkerMeta|WorkerSelectionOwner|WorkerAdmissionRuntime|DispatchAvailabilitySource|cleanupExpiredHeartbeats|WorkerScoreBand" `
  platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis xa-mass-worker-runtime xa-mass-engine `
  --glob "*.java" --glob "!**/target/**"
```

After SBR-1/SBR-2:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory -am -DskipTests test-compile
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory `
  "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest,InMemoryWorkerRegistryTest" test
```

After SBR-3:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -DskipTests test-compile
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory `
  "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest,InMemoryWorkerRegistryTest" test
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis `
  "-Dtest=RedisWorkerScoreBandSlotRuntimeTest,RedisWorkerRegistryTest" test
```

After SBR-4:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime,xa-mass-engine -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerScoreBandSlotStateMachineIntegrationTest,WorkerAdmissionOwnerTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest" test
.\mvnw.cmd -q -pl xa-mass-engine `
  "-Dtest=EngineSchedulingCoreArchitectureGuardTest" test
```

After SBR-5:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime,xa-mass-engine -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-worker-runtime `
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest,WorkerManagerTest" test
.\mvnw.cmd -q -pl xa-mass-engine `
  "-Dtest=TaskResourceReleaseListenerTest,TaskWorkerAssignListenerTest,TaskResultCorrelationSupportTest,TaskWorkAttemptIdSupportTest,EngineSchedulingCoreArchitectureGuardTest,SimpleTaskDispatchBinderTest" test
```

The SBR-5 proof currently lives in the existing focused selection and engine
tests above. They must prove bounded score-band acquire, no retired candidate
acquire in `WorkerSelectionOwner`, claim-close observation propagation, and
stale/null-observation safety. Do not rely on
`-Dsurefire.failIfNoSpecifiedTests=false` for final roadmap completion proof.
Use the two-step verification shape above: first reactor `test-compile` with
`-am`, then strict focused tests on the owning module without `-am`, so upstream
modules without matching test names do not turn the proof command itself into a
false failure.

Completion residue scan:

```powershell
rg -n "WorkerScoreBand|ScoreBand|score-band|wr:.*score|wr:.*meta|wr:.*hold" `
  xa-mass-engine transport `
  --glob "*.java" --glob "!**/target/**"
rg -n "WorkerScoreBandSlotRuntime|acquire\(" `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection xa-mass-engine/src/main/java `
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

The broader old candidate/admission surface in worker-runtime, the registry
candidate acquisition SPI, and candidate-bucket keyspace are tracked by
[2026-06-25_WORKER_RUNTIME_POST_SCORE_BAND_RESIDUE_RETIREMENT_ROADMAP.md](../doc/archive/xa-mass-worker-runtime/2026-06-25_WORKER_RUNTIME_POST_SCORE_BAND_RESIDUE_RETIREMENT_ROADMAP.md).
SBR completion only requires that production selection no longer uses those
old paths.

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
- current worker-registry lifecycle/gate tests remain green during the
  transition;
- worker-runtime has an internal lifecycle state-machine integration proof
  without changing engine assignment;
- production `WorkerSelectionRuntime` internals acquire/claim workers through
  score-band runtime while preserving the engine-facing selection contract;
- score-band completion is scoped to one active hold per worker resource;
  concurrent remaining-permit selection for `declaredCapacity > 1` is not
  current proof and is not required before old reservation residue is retired;
  future non-exclusive reuse can be expressed by earlier claim close rather
  than by preserving old reservation counters;
- `WorkerSelectionOwner` production selection no longer depends on the retired
  pre-score-band candidate facade, candidate index, or registry candidate
  acquisition SPI;
- score-band acquire is bounded by a worker-runtime-owned domain/scope and does
  not scan all workers, all groups, or all home buckets for normal selection;
- WorkerGroup, target worker, routing, attribute, exclusive-lock, and requested
  count semantics are preserved behind the unchanged `WorkerSelectionRuntime`
  contract;
- production selection acquire/claim/final close uses score-band state and does
  not write independent old reserve/confirm/claimed/final counters beside
  score-band dynamic score truth;
- `activeSelectedWorkerCount(taskId)` is removed from worker-runtime selection,
  and assignment budgeting reads distinct active workers from task-runtime lease
  truth;
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
