# Worker Runtime Score-Band Slot Registry Inventory

Status: active inventory for
`WORKER_RUNTIME_SCORE_BAND_SLOT_REGISTRY_CONVERGENCE_ROADMAP.md`.

## Decision

SBR-0 chooses a new worker resource slot state-machine contract:

```text
com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime
```

This is not an in-place expansion of `WorkerRegistry`. The current
`WorkerRegistry` remains the production candidate / reservation / gate path
until SBR-5 pivots the internals behind the existing `WorkerSelectionRuntime`.
After SBR-5, the score-band state machine must be the single production slot
truth; the old candidate / reserve state must not remain as a parallel owner.

## Current Production Owners

`WorkerRegistry`
: Broad runtime storage contract for WorkerGroup metadata, slot metadata,
candidate buckets, heartbeat deadlines, dispatch blocks, active counts,
exclusive leases, candidate acquisition, reserve / confirm / release / final,
and cleanup. This is the current production path, not the target score-band
contract.

`WorkerCandidateRuntime` / `WorkerCandidateIndex`
: Current worker-runtime candidate acquisition facade. It may stay as the
worker-runtime selection seam during migration, but it must consume the
score-band contract after the SBR-5 pivot.

`WorkerAdmissionRuntime`
: Current capacity reservation owner used by `WorkerSelectionOwner`. SBR-5 must
either make it score-band-backed or remove it from the production selection path.
A dual reserve truth is not allowed.

`WorkerSelectionOwner`
: Current implementation behind `WorkerSelectionRuntime`. Its public caller
contract should stay stable while its internal candidate / reserve source moves
to score-band.

`RedisWorkerRegistry` / `InMemoryWorkerRegistry`
: Existing storage implementations for the old broad registry contract. Their
current keys and maps are coexistence / migration state, not score-band Redis or
memory shape.

## Target Score-Band Truth

Score-band runtime owns:

- stable worker slot metadata;
- one score per worker slot;
- explicit owner-validated score transitions;
- bounded acquire over selected home buckets;
- target-worker acquire by worker id inside selected home buckets.

Score-band runtime does not own:

- engine-facing worker selection request shape;
- transport freshness writes;
- task attempt lifecycle;
- adapter mailbox / session / endpoint facts;
- Redis key enumeration contracts.

## First Redis Shape

The first Redis shape is intentionally small:

```text
wr:{prefix}:score:{homeBucketId}
  ZSET
  member = workerId
  score  = score-band score

wr:{prefix}:meta:{homeBucketId}
  HASH
  field = workerId
  value = WorkerScoreBandSlotMetadata JSON
```

`homeBucketId = workerGroupId` in the first slice. There is no `hold` hash.
Non-negative unavailable intervals are represented by a future epoch-millis
score in the score zset. When `now >= score`, acquire can see the slot without a
writer, timeout event, or queue move.

## Transition Classification

State-machine transitions are classified by target band, not by event names.

```text
worker register/update
  -> metadata upsert plus owner-selected initial score

recoverable negative evidence
  -> LOW_RECHECK_BAND

intentional park / disable / drain / cold
  -> PARKED_BAND

owner-validated recovery
  -> ELIGIBLE_BAND or FUTURE_BAND after worker-runtime validation

reserve / preallocate / active attempt / cooldown
  -> FUTURE_BAND

claim close
  -> close or shorten an observed FUTURE_BAND claim after owner validation

future score due
  -> no stored transition; acquire interprets the existing score

attempt timeout
  -> task / attempt evidence only; it must not write an eligible score
```

`release`, `final`, and `cancel` are reason names for claim close. They are not
separate positive recovery mechanisms.

## Freshness Rule

Transport heartbeat, connected, session keepalive, and transport freshness do
not request worker-runtime recheck and do not write score-band state.

Worker-runtime may point-read transport freshness only during an already
selected recovery check and only for workers whose metadata declares
`dispatchRecoveryMode=FRESHNESS_EVIDENCE`. The default is `EXPLICIT_ONLY`.

## SBR-5 Completion Gate

This inventory is only the first gate. The roadmap is not complete until the
new score-band contract is consumed behind the existing `WorkerSelectionRuntime`
and the production selection path no longer depends on
`WorkerRegistry.acquireCandidates(...)` as the slot truth.

SBR-5 also needs score-band claim observation to survive release/final paths.
Current engine code can reconstruct `SelectedWorkerEvidence` without
`selectionToken`, so null-token evidence is migration-only and must not shorten a
future score or reopen eligibility. A candidate-acquire-only pivot is useful
progress, but it is not completion of the score-band admission / claim-close
owner migration.
