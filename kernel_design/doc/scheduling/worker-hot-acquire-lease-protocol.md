# Worker HOT_ACQUIRE Lease Protocol

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contract: [Worker Score-Band Scheduling](worker-score-band-scheduling.md).
Related pacers:
[Task Worker Allocation Pacer](task-worker-allocation-pacer.md),
[TaskItem Dispatch Pacer](task-item-dispatch-pacer.md), and
[Result-Routing Scheduling](result-routing-scheduling.md).

## Purpose

One opaque Worker score fence protects the bounded continuation from Worker
allocation through dispatch and result disposition:

```text
due HOT observation
  -> exact allocation lease and dirty clear
  -> Worker matcher validation
  -> CandidateWorkerEntry(workerLeaseScore)
  -> optional CandidateWorkerCache handoff
  -> PRECOMPUTED acquisition exact validate/renew and rematch
  -> ResultContext(workerLeaseScore)
  -> result exact release or RECOVERY_RECHECK demotion
```

There is no separate reservation store, attempt lifecycle, lease token model,
or transport-owned Worker state.

`WorkerId` means one scheduler-visible execution slot. A successful allocation
lease prevents that slot from receiving another independent TaskItem until
trusted result disposition or lease expiry. A physical executor with parallel
capacity exposes multiple logical WorkerIds; the kernel does not create several
active assignments behind one Worker score.

One Worker lease carries one TaskItem and one DeliverSeed. A Worker that exposes
a business batch operation receives the batch as a bounded collection inside
that TaskItem payload. The kernel does not merge multiple TaskItems, create
multiple Item claim fences behind one Worker lease, or release the slot early.

## HOT-Pool And TARGETED Acquisition Lease

```text
acquire_hot_acquire_candidates(workerGroupId, limit)
  -> workerId -> opaque observedScore

observe_due_hot_scores(workerGroupId, workerIds)
  -> due HOT workerId -> opaque observedScore

acquire_observed_hot_score_leases(
    workerGroupId,
    observedScores,
    targetTimeMillis,
)
  -> independent exact-CAS results
```

Only due positive HOT scores may be leased. Successful acquisition preserves
laneRank, writes a future time coordinate, and clears dirty before the current
matcher reads Worker metadata. Concurrent rounds may scan the same Worker, but
only one exact observed-score CAS succeeds.

`WorkerCandidateAcquirer` exposes separate source semantics:

```text
acquire_hot_pool_candidates
  bounded HOT scan for Task-level cache warming

TARGETED with target_field=workerId or dynamic.<name>
  bounded point/index Worker ids
  observe only their due HOT scores
```

Neither HOT-pool nor TARGETED acquisition reads or writes candidate cache; the
allocation Pacer owns publication. Each call is scoped to one explicit
WorkerGroup and one score ZSET. Index output is only a proposal; every accepted
Worker still passes exact lease and full allocation-rule matching.

Unmatched Workers, matcher failures, and candidate publication failures are
not actively released. Their short leases expire naturally, preventing
immediate hot-loop rematching from the same evidence.

## PRECOMPUTED Acquisition Validation

The `PRECOMPUTED` path consumes bounded cache evidence and calls:

```text
renew_active_hot_score_leases(
    workerGroupId,
    observedScores,
    taskItemClaimUntilMillis,
)
```

All accepted observations are submitted in one score-owner batch for the
call's explicit WorkerGroup; matching remains request-local so cached evidence
cannot silently change its CandidateId meaning.

Rules:

```text
storedScore != observedScore -> STALE
polarity != HOT_ACQUIRE      -> rejected
dirty == 1                   -> STALE
observed lease expired       -> STALE
target is not future         -> INVALID
lease covers target          -> exact NOOP + observedScore
lease needs extension        -> exact CAS + renewedScore
```

Only a `TRANSITIONED` or exact `NOOP` result carrying a score is rematched
against the current request. A successful rematch may proceed to Item claim.
The returned fence, unchanged or renewed, is written into
`opaqueResultContext`.

PRECOMPUTED miss or rejected evidence never falls back to TARGETED acquisition.
TaskItemDispatchPacer never calls Worker score directly. It chooses one path
from `TaskDescriptor.taskType`: PRECOMPUTED for Task-owned rules or
TARGETED for Item-owned rules. Neither the Pacer nor PRECOMPUTED acquisition
decodes scores, clears dirty, or releases rejected candidates.

## Dirty Fence

Dirty means only:

```text
an active Worker lease exists
and metadata used by its match may have changed
```

It is not scheduling-serviceability polarity, metadata version, global Worker
status, or an
attribute update lock. Non-lease owners may set dirty but never clear it.
Allocation may clear dirty only while acquiring a due Worker before fresh
matching. Active dispatch validation rejects dirty and forces a later fresh
allocation/match.

Connect/reconnect uses the same fence deliberately:

```text
existing score
  -> preserve timeSlot and laneRank
  -> converge to HOT_ACQUIRE
  -> set dirty=1
```

This invalidates candidate evidence created before reconnect without releasing
future holds or introducing a session epoch.

## Result Disposition

The opaque Worker fence returns through `ResultContext` together with its
`workerGroupId` home-bucket coordinate. Result routing must not reread Task
metadata to recover the Worker score bucket:

```text
200 / 1xxx
  -> this attempt crossed the Worker execution boundary
  -> exact release preserving positive polarity

3xxx
  -> Adapter confirmed execution was not entered
  -> exact CAS from HOT_ACQUIRE to RECOVERY_RECHECK
```

The Adapter emits evidence only; it never mutates score. Result routing invokes
WorkerScoreCore and treats Worker disposition independently from Item movement.

Every result submits its own exact lease evidence without cross-class winner
aggregation. Stale evidence cannot release or demote a newer lease. Conflicting
classes for one exact lease violate the one-DeliverSeed/one-SeedResult protocol;
the score owner accepts at most one applicable disposition.

## Serviceability Reconciliation And Recovery Demotion

```text
reconcile_worker_hot_acquire(workerGroupId, workerId)
```

- Missing score returns `STALE`; WorkerRuntime owns initialization.
- Positive clean becomes positive dirty.
- Negative becomes positive dirty.
- Positive dirty is `NOOP`.
- timeSlot and laneRank are preserved; future holds remain future.

```text
demote_observed_worker_leases_to_recovery(workerGroupId, observedScores)
```

- Accepts only clean positive opaque lease scores.
- Uses independent exact-score CAS.
- Writes `-abs(observedScore)` and preserves the complete absolute coordinate.
- `STALE` does not affect Item outcome.

Polarity is the kernel-owned classification of whether a Worker may participate
in normal TaskItem scheduling:

```text
HOT_ACQUIRE
  scheduling-available
  may enter ordinary allocation when due and after all runtime checks

RECOVERY_RECHECK
  scheduling-unavailable
  excluded from ordinary allocation; may enter only recovery validation
```

This is not a physical connection or socket state. Adapter, Worker, and endpoint
manager observations are evidence. Reconnect upsert, execution evidence,
pre-execution rejection, and future recovery probes make the kernel
classification converge when scheduling or recovery work requires it. With no
demand, bounded classification lag is allowed.

## Failure Matrix

| Stage | Evidence | Action |
| --- | --- | --- |
| Allocation | lease CAS lost | exclude Worker |
| Allocation | unmatched or publication failure | retain short lease until expiry |
| PRECOMPUTED acquisition | dirty/recovery/expired/stale fence or rematch failure | consume candidate, do not claim Item |
| Item dispatch | Item absent or claim lost | no release; leases expire |
| Dispatch | queue append failed or ambiguous | no compensation |
| Adapter | expired or malformed seed | drop; no synthetic result |
| Adapter | Worker/handler unavailable | emit `3xxx` |
| Adapter | Worker execution failure | emit `1xxx` |
| Result | `200/1xxx` | exact release |
| Result | `3xxx` | exact RECOVERY_RECHECK demotion |
| Result | malformed/missing evidence | no guessed mutation; expiry recovers |

No branch adds a repair scanner, compensation queue, distributed lock, or
cross-owner transaction.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore | score encoding, scans, exact lease, dirty fence, release and polarity mechanics | no Task policy, transport or result subcode parsing |
| WorkerRuntime | declaration validation, first score initialization and trusted reconnect reconciliation | no heartbeat or dispatch ownership |
| WorkerCandidateAcquirer HOT pool | bounded due-HOT scan, exact lease and full match for precomputation | no cache read/write |
| WorkerCandidateAcquirer TARGETED | explicit point/index source, exact lease and full match | no cache read/write or fallback |
| WorkerCandidateAcquirer PRECOMPUTED | cache consume, exact active-fence validation/renewal and rematch | no HOT scan or fallback |
| TaskWorkerAllocationPacer | retain Task-owned rule Tasks, acquire HOT-pool candidates and publish cache evidence | no direct Worker-score or result handling |
| TaskItemDispatchPacer | resolve PRECOMPUTED/TARGETED from immutable TaskType, preserve binding, claim and publish DeliverSeed | no cache/Worker-score access or release |
| External Adapter | local final-hop observation and execution evidence | no score parsing or mutation |
| ResultRoutingPacer | bounded consume, context decode, owner-key grouping and handler delegation | no direct Task/Worker owner dependency, Worker selection or exact subcode policy |
| Result-routing handlers | owner-local Task finality and Worker disposition policy | no queue ownership, score decoding or cross-owner truth |

## Deferred Policy

Recovery probe cadence and ranking are deferred. TaskItem coalescing is not a
deferred kernel policy; business batching is expressed inside one TaskItem
payload.

## Guardrails

- Do not lease negative `RECOVERY_RECHECK` scores through HOT primitives.
- Do not expose score encoding, dirty bit, sign or timeSlot to callers.
- Do not let active renewal clear dirty.
- Do not let PRECOMPUTED acquisition fall back to TARGETED acquisition.
- Do not let TaskItemDispatchPacer call WorkerScoreCore directly.
- Do not release rejected dispatch candidates as compensation.
- Do not treat missing result as `3xxx`.
- Do not let old result evidence mutate a newer Worker lease.
- Do not create separate Attempt, reservation, session epoch or lease registry.
- Do not release a Worker fence after DeliverSeed append to simulate immediate
  slot reuse.
- Do not assign independent TaskItems or different Tasks concurrently to one
  WorkerId.
