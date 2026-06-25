# Worker Runtime / Transport Pre-Score-Band Residue Convergence Roadmap

Status: proposed convergence roadmap.

## Purpose

Prepare `xa-mass-worker-runtime` and transport integration for the
Score-Band Resource Slot Scheduling direction without starting the score-band
registry implementation yet.

The immediate problem is not that score-band lacks a roadmap. The problem is
that current worker-runtime and transport integration still carry older helper
models that would become hidden second scheduling paths if score-band work
starts now:

- `WorkerPresenceRuntime` and its remaining call-site/doc residue combine or
  imply session-presence writes, reachability reads, and delivery-target reads
  in one composite owner.
- `InMemoryWorkerPresenceRuntime` mixes active session projection,
  reachability state, and selected-worker delivery target derivation.
- `WorkerRuntimePresenceIngress` still makes transport session events look like
  a worker-runtime presence lane, even though only confirmed current-session
  disconnect should be scheduling-relevant negative evidence.
- `TaskCandidateWarmPool` and `WorkerWarmHintRuntime` preserve a task-local
  warm-candidate hint mechanism that conflicts with the supply-side slot
  leasing direction.
- `WorkerRuntimeStateRecord`, `WorkerReadinessState`, and
  `WorkerOccupancyState` are diagnostic/delete candidates. They must not
  become alternate scheduling truth while score-band eligibility is introduced.
- `AdapterNodeRecord` / `NodeGroupBindingRecord` preserve an older
  adapter-node topology model. That model needs explicit classification before
  score-band work starts, because it is not the same thing as a resource slot,
  demand lane, or adapter mailbox.
- `WorkerCandidateBucketPolicy` and
  `WorkerTaskSelector#candidateBucketKeys` are current bounded-candidate
  machinery. They need classification so they are not mistaken for the future
  score-band lane/index model.
- `WorkerCandidateBatch` has become mostly a warm/cold diagnostic carrier. If
  the remaining caller only needs candidate rows, the wrapper should be
  deleted with the warm-pool path instead of narrowed into another DTO.

This roadmap is a cleanup prerequisite. It should leave current assignment and
transport delivery behavior working, but remove unnecessary old seams before
the score-band registry is introduced.

## References

- [Score-Band Resource Slot Scheduling Blueprint](../architecture/score-band-resource-slot-scheduling-blueprint.md)
- [Score-Band Worker Runtime Redis Shape](../architecture/score-band-worker-runtime-redis-shape.md)
- [Worker Runtime Dispatch Eligibility Signal Convergence Roadmap](./WORKER_RUNTIME_NEGATIVE_SIGNAL_DISPATCH_ELIGIBILITY_CONVERGENCE_ROADMAP.md)
- [Worker Runtime Bounded Candidate Acquisition Roadmap](./WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md)
- [Worker Runtime README](../xa-mass-worker-runtime/README.md)
- [Worker Runtime Contracts](../xa-mass-worker-runtime/CONTRACTS.md)
- [Transport Boundary Baseline](../transport/TRANSPORT_BOUNDARY_BASELINE.md)

## Blueprint Alignment

This cleanup follows the score-band blueprint, but it does not implement the
blueprint yet.

The blueprint target says:

```text
task = demand
resource slot = supply

engine exposes task demand lanes and attempt lifecycle needs
worker-runtime/resource-runtime maintains schedulable resource slots
matcher leases eligible slots from supply and binds them to demand
```

Relevant blueprint sections:

- [Core mechanism](../architecture/score-band-resource-slot-scheduling-blueprint.md#core-mechanism):
  score-band resource availability is supply-owned.
- [Demand-Guided Sparse Acquire](../architecture/score-band-resource-slot-scheduling-blueprint.md#demand-guided-sparse-acquire):
  task demand compiles into an acquire plan; tasks must not query workers,
  choose bucket keys, or create placement tag specs at runtime.
- [Transport To Worker-Runtime](../architecture/score-band-resource-slot-scheduling-blueprint.md#transport-to-worker-runtime):
  heartbeat, transport refresh, session keepalive, and connected are
  transport-local freshness events, not worker-runtime scheduling reopen
  events.
- [Worker Runtime Redis Shape](../architecture/score-band-worker-runtime-redis-shape.md):
  worker scheduling state should split into eligibility index, scheduling
  metadata, and lease/hold state; transport/session facts stay out of
  scheduling metadata.

Therefore this roadmap cleans the pre-score-band residue as follows:

| Current mechanism | Classification | Why it can be cleaned before score-band | Target disposition |
| --- | --- | --- | --- |
| `WorkerPresenceRuntime extends WorkerReachabilityView, WorkerDeliveryTargetView` | mixed projection / residue | It collapses transport session writes, reachability diagnostics, and delivery-target lookup into one contract. The blueprint separates transport-local freshness from worker-runtime schedulability and post-selection delivery. Current code is already moving away from the composite type; this roadmap must finish the cleanup rather than recreate it. | Split into narrow presence write/write-ingress contract plus separate read/projection surfaces. |
| `InMemoryWorkerPresenceRuntime` backing presence, reachability, and delivery target | transitional embedded implementation | A single implementation may temporarily back multiple views, but callers should not see a composite owner. | Keep only as implementation detail if needed; expose through narrow interfaces in assembly. |
| `WorkerRuntimePresenceIngress` connected / heartbeat path | transport freshness bridge residue | Connected and heartbeat prove session freshness only. They must not request scheduling recheck or reopen eligibility. | Keep as transport-local/presence projection only; only current-session disconnect may emit negative block evidence. |
| `WorkerReachabilityView` / `WorkerManager#getWorkerReachability` | diagnostic read model residue | Reachability is not score-band eligibility and must not become a second scheduling gate. The interface is being removed, but `Function<String, WorkerReachabilityState>` and direct getters can still preserve the old behavior if left unclassified. | Keep only as diagnostic point read if needed; forbid selection and recovery from depending on it. |
| Delivery-target resolver / `SelectedWorkerDeliveryTargetEvidence` | still-required post-selection evidence | Assigned dispatch still needs selected worker to opaque `adapterMailboxKey` lookup. This is after worker selection and not a scheduling input. The interface may disappear, but the resolver surface still exists. | Keep as point lookup; classify each evidence field as dispatch-required or diagnostic. Forbid list/stats/scheduling use. |
| `WorkerRuntimeStateRecord` + readiness/occupancy derivations | diagnostic read model / delete candidate | It composes reachability, heartbeat freshness, dispatch gate, admission, and load into one observation. That is acceptable only as a read model, but dangerous if reused as slot eligibility truth. Current code must prove a real SDK/server/operator consumer before retaining it. | Delete if there is no real consumer. If retained, keep only as read/diagnostic output and forbid selection, admission, or score-band registry writes from this composite record. |
| `AdapterNodeRecord` / `NodeGroupBindingRecord` | topology/control-plane residue | Adapter node/group binding is not the same thing as worker slot supply, demand lane, or adapter mailbox. It may still be useful as admin/control-plane inventory. | Classify as keep/narrow/delete/defer before score-band; do not use as score-band placement truth. |
| `WorkerCandidateBucketPolicy` / `candidateBucketKeys` | current bounded-candidate partition mechanism | BCA still uses candidate buckets for sparse candidate acquisition. They are not the final score-band lane model. | Keep until score-band replacement is designed; document as current migration mechanism, not future lane truth. |
| `TaskCandidateWarmPool` | task-local worker candidate cache residue | It is a task-side worker hint. The blueprint forbids new task-side worker indexes and moves future warming toward lane/resource preallocation. | Delete, not rename. |
| `WorkerWarmHintRuntime` | mutation surface for warm-pool residue | It only exists to feed task-local warm candidates. | Delete with warm pool. |
| `WorkerCandidateBatch` | warm-pool-era wrapper / delete candidate | Selection currently needs candidate rows, not warm/cold source diagnostics. If the wrapper exists only to carry `candidates()`, it is not a useful owner boundary. | Prefer deleting the wrapper and returning `List<WorkerCandidateRow>` from the candidate runtime. Retain a batch only if inventory proves non-warm-pool metadata is still required. |
| `WorkerCandidateIndex` / `WorkerCandidateRuntime` | current migration mechanism | These remain the current bounded candidate path until the score-band matcher roadmap replaces internals with eligible slot acquire. | Do not delete in this roadmap. |

In short:

```text
clean now:
  composite presence contract
  transport freshness as worker-runtime scheduling input
  composite runtime-state as selection truth, and unused runtime-state DTOs
  task-local warm candidate cache
  warm/cold candidate diagnostics
  candidate batch wrapper if it only carries rows

keep narrow for now:
  post-selection delivery target resolver
  current bounded candidate acquisition
  candidate bucket partitioning as current migration mechanism
  worker-runtime dispatch block/recovery owner

classify before changing score-band:
  adapter-node / node-group topology
  runtime state/readiness/occupancy diagnostic surfaces
  selected-worker delivery-target evidence fields

defer:
  score-band registry
  eligible slot acquire
  demand-lane preallocation
  multi-resource claims
```

## Current Code Observations

- `WorkerPresenceRuntime`, `WorkerReachabilityView`, and
  `WorkerDeliveryTargetView` are already being removed from main sources in
  the current working tree. The remaining residue is now the concrete
  `InMemoryWorkerPresenceRuntime`, `WorkerManager#getWorkerReachability`,
  `EngineConfig` resolver wiring, and documentation/guard vocabulary.
- `InMemoryWorkerPresenceRuntime` owns active session records, seen-worker
  reachability projection, current mailbox by worker, and delivery-target
  generation in one class.
- `EngineConfig` still constructs `WorkerManager` with
  `workerPresenceRuntime::getWorkerReachability` and defaults
  `workerDeliveryTargetResolver` to
  `workerPresenceRuntime::resolveDeliveryTarget`.
- `WorkerRuntimePresenceIngress` is the transport-to-worker-runtime bridge for
  connected / heartbeat / disconnected events and emits the negative
  `TRANSPORT_DISCONNECTED` block only when a current session becomes
  unreachable.
- Delivery-target resolution is still required for assigned dispatch:
  delivery integration resolves an already-selected worker to an opaque
  `adapterMailboxKey` through `SelectedWorkerDeliveryTargetEvidence`.
- `SelectedWorkerDeliveryTargetEvidence` carries `workerId`,
  `adapterMailboxKey`, `generation`, `observedAtEpochMillis`, and
  `expiresAtEpochMillis`. Those fields need explicit disposition so future
  split/shared runtime work does not treat all of them as scheduling truth.
- `WorkerReachabilityState` remains observable through
  `WorkerManager#getWorkerReachability` and SDK config helpers. It must stay
  diagnostic and must not become selection truth or a score-band eligibility
  source.
- `WorkerRuntimeStateRecord` composes status, heartbeat freshness,
  reachability, dispatch gate, capacity/reservation, exclusive lease, and
  observation time. No production main-source caller currently appears to use
  it directly, so it is a delete candidate; owner docs still protect it as
  current runtime evidence and must be corrected in the same slice if it is
  removed.
- `WorkerReadinessState` appears tied to `WorkerRuntimeStateRecord` and should
  be deleted with it unless inventory finds a real API/SDK consumer.
  `WorkerOccupancyState` is still exposed through `WorkerLoadSnapshot` and can
  remain as a narrow diagnostic helper unless a later score-band roadmap
  replaces it with slot-state vocabulary.
- `AdapterNodeRecord` / `NodeGroupBindingRecord` are exposed through
  `WorkerManager`, `WorkerResourceQueryRuntime`, `WorkerNodeBindingRuntime`,
  and embedded SDK snapshots. They look like older adapter topology/control
  surface, not score-band supply truth.
- `TaskCandidateWarmPool` is used by `WorkerCandidateSourceOwner` before cold
  candidate acquisition.
- `WorkerWarmHintRuntime` exposes warm-candidate mutation through
  `WorkerManager` and SDK/starter config.
- `WorkerCandidateBatch` carries warm/cold diagnostic counts that exist only
  because the warm-pool path exists. Current selection consumes candidate rows;
  if no other non-warm metadata remains, delete the batch wrapper instead of
  preserving a row-only DTO.
- `WorkerCandidateBucketPolicy`, `WorkerTaskSelector#candidateBucketKeys`, and
  `ResolvedWorkerSchedulingPolicy#candidateBucketKeys` are current
  bounded-candidate partitioning mechanics. They are not the same as future
  demand lanes or score-band resource-slot indexes.
- Current BCA work made candidate acquisition bounded, but it did not remove
  task-local warm-candidate hints.

## Owner Review

Worker-runtime owns:

- worker declaration to runtime scheduling projection;
- dispatch eligibility and block/recovery policy;
- selected-worker read contracts consumed after assignment;
- candidate acquisition while the current selection mechanism remains active.
- current worker read models for operator/SDK diagnostics, as long as they do
  not feed selection as composite truth.

Transport owns:

- adapter-local session/channel truth;
- endpoint/session/freshness evidence;
- assigned-delivery queueing and final-hop best-effort delivery;
- carrying explicit worker reports or current-session disconnect evidence to
  worker-runtime through narrow integration contracts.

Transport must not own:

- worker schedulability;
- worker candidate warm pools;
- score-band state;
- positive recovery or reopen decisions.

Task-local warm candidates are a task-side acceleration hint. They are not
resource-slot supply truth. Under score-band direction they should be removed,
not renamed into a new slot or lane concept. Future warm capacity belongs to
lane/resource preallocation, not per-task worker candidate caches.

Adapter-node / node-group binding is not transport final-hop truth and not
score-band resource-slot truth. If retained, it should be treated as
control-plane topology inventory around adapter deployments and group
availability, not as a worker-selection index.

Candidate bucket policy is current bounded-candidate partitioning. It can
remain as a migration mechanism, but it must not be described as the target
demand lane or score-band index.

## Boundary Decision

Presence mutation, reachability diagnostics, and delivery-target lookup must be
separate contracts.

Target split:

```text
WorkerSessionPresence / WorkerPresenceIngress write path
  session presence projection only:
    sessionConnected
    sessionHeartbeat
    sessionDisconnected

WorkerReachabilityState point read
  diagnostic only, if still needed

Selected-worker delivery target resolver
  post-selection point lookup:
    selectedWorkerId -> adapterMailboxKey evidence
```

No production contract should combine those three roles. If an in-memory
implementation temporarily backs more than one role, assembly must expose it
through narrow typed fields or narrow functions, not through a composite
interface or a broad runtime object.

Runtime state/readiness/occupancy records must be treated as read models:

```text
WorkerRuntimeStateRecord
WorkerReadinessState
WorkerOccupancyState
```

They may describe current observation state only when a real SDK/server/operator
consumer exists. If no real consumer exists, delete the record instead of
documenting it as a diagnostic surface. If retained, it must not become the
source of schedulable slot membership. Score-band slot state requires its own
owner decision.

Adapter node/group binding must be treated as topology/control-plane inventory:

```text
AdapterNodeRecord
NodeGroupBindingRecord
```

It must not be reused as score-band resource supply, demand lane, or adapter
mailbox truth without a separate owner decision.

Warm candidate hints should be deleted:

```text
TaskCandidateWarmPool
WorkerWarmHintRuntime
warmCandidateCount
WorkerCandidateBatch warm/cold counters
```

The successor is not another warm-pool abstraction. The successor direction is
score-band supply and later demand-lane preallocation. `WorkerCandidateBatch`
should also be deleted if, after warm-pool removal, it carries only candidate
rows.

## Non-Goals

- Do not implement score-band registry in this roadmap.
- Do not replace `WorkerSelectionRuntime` internals with eligible slot acquire.
- Do not introduce supply-push worker-to-task matching.
- Do not introduce a generic resource abstraction.
- Do not change transport dispatch carrier or adapter mailbox delivery.
- Do not remove the selected-worker delivery-target resolver while assigned
  dispatch still needs post-assignment worker-to-mailbox lookup.
- Do not preserve `TaskCandidateWarmPool` through aliases, wrappers, or renamed
  compatibility paths.
- Do not add stats/list/count read models to compensate for removed warm-pool
  diagnostics.
- Do not convert `AdapterNodeRecord` / `NodeGroupBindingRecord` into
  score-band slot, lane, or mailbox concepts by rename.
- Do not delete current candidate-bucket partitioning until a score-band
  acquire roadmap replaces the production caller path.
- Do not treat `WorkerRuntimeStateRecord`, readiness, or occupancy as the
  future eligibility index.

## WTP-0: Inventory And Baseline Classification

Goal:

Classify all production and test callers before deleting or splitting the
contracts.

Scope:

- Inventory callers of:
  - `WorkerPresenceRuntime`
  - `InMemoryWorkerPresenceRuntime`
  - `WorkerRuntimePresenceIngress`
  - `WorkerReachabilityView`
  - `WorkerDeliveryTargetView`
  - `WorkerManager#getWorkerReachability`
  - `EngineConfig#setWorkerDeliveryTargetResolver`
  - `SelectedWorkerDeliveryTargetEvidence` fields
  - `WorkerRuntimeStateRecord`
  - `WorkerReadinessState`
  - `WorkerOccupancyState`
  - `AdapterNodeRecord`
  - `NodeGroupBindingRecord`
  - `WorkerNodeBindingRuntime`
  - `WorkerWarmHintRuntime`
  - `TaskCandidateWarmPool`
  - `WorkerCandidateBatch`
  - `WorkerCandidateBatch#warmCandidateCount`
  - `WorkerCandidateBatch#coldCandidateCount`
  - `WorkerCandidateBatch#warmSourceGuardRejectedCount`
  - `WorkerCandidateBatch#duplicateCandidateCount`
  - `WorkerCandidateBucketPolicy`
  - `WorkerTaskSelector#candidateBucketKeys`
  - `ResolvedWorkerSchedulingPolicy#candidateBucketKeys`
- Separate production, test, architecture guard, documentation, and archived
  roadmap references.
- Verify whether selection packages still read reachability directly.
- Verify whether any production path depends on warm-candidate behavior for
  correctness rather than performance.
- Verify whether `WorkerCandidateBatch` carries any non-warm-pool metadata
  required by selection. If not, target deletion and let candidate acquisition
  return candidate rows directly.
- Verify whether adapter-node / node-group APIs are production scheduling
  inputs, admin/control-plane inventory, test fixtures, or stale public SDK
  surface.
- Verify whether candidate bucket keys are only current BCA partitioning
  mechanics or are being described as future score-band demand lanes.

Acceptance:

- Inventory states each symbol's current owner, fact classification, caller
  class, and target disposition:
  `keep`, `narrow`, `delete`, `defer`, or `move to diagnostics`.
- No behavior change.
- The next slice can be implemented without discovering a hidden production
  caller that requires compatibility aliases.
- The inventory explicitly says which symbols are user-facing SDK/API
  surfaces and which are internal runtime support, so cleanup does not
  preserve stale public paths accidentally.

Suggested inventory file:

```text
roadmap/WORKER_RUNTIME_TRANSPORT_PRE_SCORE_BAND_RESIDUE_INVENTORY.md
```

## WTP-1: Split Session Presence From Read Views

Goal:

Remove the composite presence contract while preserving current embedded
delivery-target and negative-disconnect behavior.

Scope:

- Finish removing `WorkerPresenceRuntime` / `WorkerReachabilityView` /
  `WorkerDeliveryTargetView` interface residue from main sources, tests,
  docs, and guards.
- Keep `WorkerPresenceIngress` as the transport-facing ingress contract, or
  introduce a worker-runtime-local write contract only if a real owner boundary
  requires it. Do not recreate a composite presence runtime.
- Update `WorkerRuntimePresenceIngress` so its worker-runtime dependency can
  only write session presence and emit negative disconnect through
  `WorkerDispatchBlockRuntime`.
- Keep connected / heartbeat as presence writes only; they must not refresh
  registry heartbeat, request dispatch wakeup, or reopen schedulability.
- Keep disconnected as the only current transport-to-worker-runtime negative
  path, and only when current-session loss is accepted by the presence
  projection.
- Wire reachability diagnostics and delivery-target resolver separately in
  `EngineConfig` / `MassApplication` assembly. A direct function resolver is
  acceptable as an interim shape if it is narrow and documented.
- If the same in-memory object temporarily backs write, reachability, and
  delivery-target views, production callers must still receive only the narrow
  interface they own.

Acceptance:

- No production main source references `WorkerPresenceRuntime`,
  `WorkerReachabilityView`, or `WorkerDeliveryTargetView` unless WTP-0
  explicitly reclassifies one as a still-current contract.
- `WorkerRuntimePresenceIngress` cannot call `resolveDeliveryTarget(...)` or
  `getWorkerReachability(...)` through its presence dependency.
- `EngineConfig` no longer uses one composite field as the public/default owner
  of presence, reachability, and delivery target.
- Existing embedded distributed transport tests still prove selected-worker
  delivery target lookup works.
- Current-session disconnect still emits `TRANSPORT_DISCONNECTED` block; stale
  or replaced-session disconnect does not.

## WTP-2: Narrow Delivery Target And Reachability Residue

Goal:

Make delivery target and reachability explicit point-read surfaces, not
presence-owned scheduling truth.

Scope:

- Keep selected-worker delivery-target resolution as a point lookup for
  assigned dispatch only. Current code may use
  `Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>>`; a named
  interface is optional and should only be reintroduced if it improves the
  owner boundary.
- Ensure delivery-target resolution exposes no worker lists, mailbox lists,
  active counts, session handles, endpoint leases, or scheduling views.
- Classify each `SelectedWorkerDeliveryTargetEvidence` field:
  `workerId` and `adapterMailboxKey` are dispatch-required; `generation`,
  `observedAtEpochMillis`, and `expiresAtEpochMillis` must be either
  dispatch-required for stale-target rejection or explicitly diagnostic.
- Keep `WorkerReachabilityState` point reads diagnostic-only.
- Ensure selection hot path does not call `getWorkerReachability(...)` or
  consume reachability-derived read models.
- Move or delete documentation that describes reachability as dispatchability
  truth.
- Decide whether `WorkerManager`'s permissive reachability fallback is still a
  useful embedded/test default or should be narrowed to test support.

Acceptance:

- `xa-mass-worker-runtime` selection package does not import or call
  reachability diagnostics.
- Engine mainline does not read reachability diagnostics for worker selection.
- Delivery-target resolver remains post-selection only: its caller already has
  a `selectedWorkerId`.
- Architecture guard fails if delivery target view becomes a worker list,
  mailbox list, stats view, or scheduling input.
- Architecture guard fails if `SelectedWorkerDeliveryTargetEvidence` fields
  are used by worker selection or scheduling eligibility.

## WTP-3: Classify Runtime State, Topology, And Candidate Bucket Residue

Goal:

Prevent diagnostic state, adapter topology, and current candidate-bucket
partitioning from becoming accidental score-band truth.

Scope:

- Classify `WorkerRuntimeStateRecord`, `WorkerReadinessState`, and
  `WorkerOccupancyState` as delete-or-diagnostic surfaces. Delete
  `WorkerRuntimeStateRecord` and `WorkerReadinessState` if WTP-0 finds no real
  SDK/server/operator consumer. Keep `WorkerOccupancyState` only as the narrow
  `WorkerLoadSnapshot` diagnostic helper unless a later score-band roadmap
  explicitly replaces it with slot-state contracts.
- Verify no selection/admission/dispatch hot path consumes
  `WorkerRuntimeStateRecord` as input.
- Classify `AdapterNodeRecord`, `NodeGroupBindingRecord`, and
  `WorkerNodeBindingRuntime` as topology/control-plane/admin inventory unless
  WTP-0 proves all API/SDK consumers are stale. Do not delete this surface in
  the same slice as warm-pool deletion; it has a different caller set and proof
  surface.
- If adapter-node/group binding stays, document that it is not worker slot
  supply, not demand-lane routing, and not assigned-delivery mailbox truth.
- Classify `WorkerCandidateBucketPolicy`,
  `WorkerTaskSelector#candidateBucketKeys`, and
  `ResolvedWorkerSchedulingPolicy#candidateBucketKeys` as current
  bounded-candidate partitioning. They may remain until a score-band acquire
  roadmap replaces them, but docs must not present them as final demand lanes.
- Remove or quarantine any proof counters/list APIs whose only purpose is to
  preserve old warm/cold candidate behavior.

Acceptance:

- Owner docs either delete `WorkerRuntimeStateRecord` /
  `WorkerReadinessState` from current contracts or describe the retained state
  surfaces as diagnostics only.
- Adapter-node / node-group binding is either explicitly retained as
  control-plane inventory or marked for deletion with caller list and proof.
- Candidate bucket policy is documented as current BCA partitioning, not
  score-band lane/index truth.
- No new abstraction is introduced in this slice. The output is owner
  classification plus any small doc/guard corrections needed to prevent
  misread implementation.

## WTP-4: Delete Task-Local Warm Candidate Path

Goal:

Remove task-local warm-candidate hinting and its row-batch diagnostics before
score-band work starts.

Scope:

- Delete `TaskCandidateWarmPool`.
- Delete `WorkerWarmHintRuntime`.
- Remove `WorkerManager.recordWarmCandidate(...)` and
  `WorkerManager.warmCandidateCount(...)`.
- Remove `WorkerManager implements WorkerWarmHintRuntime`.
- Remove `EngineConfig#getWorkerWarmHintRuntime()` and any SDK/starter
  assembly exposure of warm-hint mutation.
- Remove warm-candidate sampling from `WorkerCandidateSourceOwner`; it should
  request the bounded candidate source directly.
- Prefer deleting `WorkerCandidateBatch` and changing
  `WorkerCandidateRuntime#findWorkerCandidateBatch(...)` to return
  `List<WorkerCandidateRow>` or an equivalently narrow row collection. Retain
  a batch type only if WTP-0 proves non-warm-pool metadata is still required.
- If a batch type is retained temporarily, remove all warm/cold/source-guard
  diagnostic counters from it.
- Delete or rewrite tests that preserve warm-pool behavior, including
  `TaskCandidateWarmPoolTest` and warm-candidate assertions in
  `WorkerManagerTest`.
- Remove architecture guards that protect `TaskCandidateWarmPool` as a current
  mechanism; replace them with guards that forbid task-local warm candidate
  caches from returning.
- Update `xa-mass-worker-runtime/README.md`,
  `xa-mass-worker-runtime/CONTRACTS.md`, engine scheduling docs, and
  architecture guards in the same slice. Do not leave docs/tests protecting
  the deleted warm-pool mechanism.

Acceptance:

- Main sources contain no `TaskCandidateWarmPool`, `WorkerWarmHintRuntime`,
  `recordWarmCandidate`, or `warmCandidateCount`.
- `EngineConfig` no longer exposes `getWorkerWarmHintRuntime()`.
- `WorkerCandidateBatch` is deleted when it carries no non-warm metadata. If
  retained by explicit WTP-0 decision, it carries no warm/cold/source-guard
  diagnostic counters and has a later deletion owner.
- Candidate acquisition remains bounded through the BCA contract.
- No correctness test depends on warm-candidate preference.
- Engine and worker-runtime architecture guards no longer require or protect
  `TaskCandidateWarmPool` / `WorkerWarmHintRuntime`; they fail if task-local
  warm candidate caches or mutation ports return.
- Documentation points future warming to demand-lane preallocation / score-band
  roadmap work, not task-local worker candidate caches.

## WTP-5: Owner Docs, Guards, And Proof Registry

Goal:

Make the cleanup durable and prevent old vocabulary from re-entering the
mainline before score-band starts.

Scope:

- Update:
  - `xa-mass-worker-runtime/README.md`
  - `xa-mass-worker-runtime/CONTRACTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md` if presence or transport
    evidence wording changes
  - `doc/PROOF_REGISTRY.md`
  - active engine scheduling docs that still describe warm-pool as current
    mainline
- Update architecture guards:
  - forbid composite presence contract extending read views;
  - forbid selection hot path importing reachability diagnostics;
  - forbid runtime-state/readiness/occupancy read models from becoming
    selection or admission inputs, or remove them entirely when WTP-3 deletes
    the unused surfaces;
  - forbid adapter-node / node-group topology from being used as
    score-band slot, lane, or mailbox truth;
  - forbid task-local warm candidate pool classes or mutation ports.
- Archive or mark superseded any roadmap sections that still treat warm pool as
  a current mechanism.

Acceptance:

- Owner docs describe:
  - transport freshness as transport-local evidence;
  - current-session disconnect as narrow negative evidence;
  - delivery target lookup as post-selection only;
  - runtime-state/readiness/occupancy as deleted surfaces or diagnostics/read
    models only;
  - adapter-node / node-group binding as classified topology or removed
    surface, not score-band truth;
  - candidate buckets as current bounded-candidate partitioning only;
  - warm-pool mechanism as removed.
- Proof registry points to focused tests for presence split, delivery target
  lookup, negative disconnect, and bounded candidate acquisition.
- Guard tests fail on reintroducing composite presence or warm-pool symbols.

## Suggested Implementation Order

1. WTP-0 inventory.
2. WTP-4 task-local warm candidate deletion.
3. WTP-1 presence contract split.
4. WTP-2 delivery-target / reachability residue cleanup.
5. WTP-3 runtime-state / topology / candidate-bucket classification.
6. WTP-5 docs and guards.

WTP-0 should be thin and current-code grounded. It must prove the warm-pool
path is not a correctness requirement, but it should not become a broad
topology redesign gate. Once WTP-0 confirms warm-pool is only acceleration,
start WTP-4 before the larger presence/topology cleanup so score-band is not
blocked by unrelated public-surface decisions.

## Do Not Start With

- Do not start from only `WorkerPresenceRuntime` and `TaskCandidateWarmPool`
  because they were named as examples. WTP-0 must inventory runtime-state,
  topology, delivery-target evidence, candidate-bucket, and warm-pool residue
  together before choosing delete/narrow/defer actions.
- Do not start by adding score-band classes.
- Do not start by renaming `TaskCandidateWarmPool`.
- Do not replace warm pool with another task-local candidate cache.
- Do not make `WorkerPresenceRuntime` smaller in name only while keeping
  reachability and delivery target on the same public contract.
- Do not delete the selected-worker delivery-target resolver until assigned
  delivery no longer needs selected-worker to mailbox resolution.
- Do not add stats, lists, or diagnostics loops to prove the cleanup.
- Do not let adapter-node / node-group topology classification block
  task-local warm candidate deletion; it has a different public/API proof
  surface.

## Verification Candidates

Commands must be corrected after WTP-0 if test names change.

```powershell
rg -n "WorkerPresenceRuntime|InMemoryWorkerPresenceRuntime|WorkerRuntimePresenceIngress|WorkerDeliveryTargetView|WorkerReachabilityView|WorkerManager#getWorkerReachability|getWorkerReachability|SelectedWorkerDeliveryTargetEvidence|WorkerRuntimeStateRecord|WorkerReadinessState|WorkerOccupancyState|AdapterNodeRecord|NodeGroupBindingRecord|WorkerNodeBindingRuntime|WorkerCandidateBucketPolicy|candidateBucketKeys|TaskCandidateWarmPool|WorkerWarmHintRuntime|warmCandidate|warmCandidateCount|coldCandidateCount|warmSourceGuardRejectedCount|duplicateCandidateCount" xa-mass-worker-runtime sdk/xa-mass-embedded-sdk transport xa-mass-engine platform_infra roadmap doc --glob "!**/target/**" --glob "!**/archive/**"

rg -n "TaskCandidateWarmPool|WorkerWarmHintRuntime|recordWarmCandidate|warmCandidateCount|WorkerCandidateBatch|coldCandidateCount|warmSourceGuardRejectedCount|duplicateCandidateCount" xa-mass-worker-runtime/src/main sdk/xa-mass-embedded-sdk/src/main xa-mass-engine/src/main platform_infra --glob "*.java" --glob "!**/target/**"

.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,InMemoryWorkerPresenceRuntimeTest" test

.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimePresenceIngressTest,MassApplicationDistributedTransportTest,TaskDispatchRoutingSubmitterTest" test

.\mvnw.cmd -q -pl transport/transport_runtime,xa-mass-engine -am "-Dtest=TransportConvergenceArchitectureGuardTest,EngineSchedulingCoreArchitectureGuardTest" test
```

For final completion proof, mandatory focused tests must run without
`-Dsurefire.failIfNoSpecifiedTests=false`. If a named test is deleted as part
of the cleanup, replace it with the new focused proof instead of masking the
absence.

## Completion Criteria

This roadmap is complete when:

1. No production composite presence contract exposes both mutation and
   reachability/delivery-target reads.
2. Transport connected / heartbeat / keepalive remains transport-local
   freshness and cannot reopen worker schedulability.
3. Current-session disconnect remains a narrow negative evidence path.
4. Selected-worker delivery-target resolution is available only as
   post-selection point lookup for assigned delivery.
5. Selection hot path does not depend on reachability diagnostics.
6. `SelectedWorkerDeliveryTargetEvidence` fields are classified and only
   dispatch-required fields are used on the assigned-delivery path.
7. `WorkerRuntimeStateRecord` and `WorkerReadinessState` are either deleted or
   documented and guarded as read/diagnostic derivations, not schedulable slot
   truth. `WorkerOccupancyState` remains only as a narrow load diagnostic if
   retained.
8. `AdapterNodeRecord` / `NodeGroupBindingRecord` are either retained as
   explicit control-plane/admin topology or removed from current runtime
   surfaces; they are not score-band supply, demand lane, or mailbox truth.
9. `WorkerCandidateBucketPolicy` and `candidateBucketKeys` are documented as
   current BCA partitioning and have a clear replacement/defer decision for
   score-band work.
10. `TaskCandidateWarmPool`, `WorkerWarmHintRuntime`, warm-candidate mutation,
   warm/cold batch counters, and row-only `WorkerCandidateBatch` residue are
   removed from main sources.
11. Bounded candidate acquisition still works without task-local warm hints.
12. Owner docs, proof registry, and guards no longer preserve warm-pool,
   composite-presence, topology-as-slot, or runtime-state-as-eligibility
   vocabulary as current truth.
13. The repo is ready for the first score-band registry roadmap without hidden
   old scheduling acceleration paths.

## Deferred Decisions

- Whether a future shared/split deployment needs a persistent delivery-target
  projection store beyond the current embedded point lookup.
- Whether reachability diagnostics should remain a public SDK/server surface or
  be moved to test/operator-only support.
- Whether `generation` / `observedAtEpochMillis` / `expiresAtEpochMillis` on
  `SelectedWorkerDeliveryTargetEvidence` are dispatch-required stale-target
  facts or diagnostics.
- Whether adapter-node / node-group topology should remain as an admin
  inventory surface, move behind server/control-plane APIs, or be deleted
  before score-band.
- Whether current candidate bucket partitioning is replaced directly by
  score-band sparse acquire or kept as a temporary implementation detail under
  the first score-band registry slice.
- Whether demand-lane preallocation later replaces any useful behavior removed
  with `TaskCandidateWarmPool`.
- How the first score-band registry will bridge from current
  `WorkerRegistry`/`WorkerSlot` without preserving the old candidate path as a
  second truth.
